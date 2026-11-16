# Architecture & Concurrency Design Document: Nnomm Flash-Deal Claim Service

This document provides technical design details for the Nnomm Flash-Deal Claim Service, addressing the 7 architecture and concurrency questions from the brief with concrete references to the codebase and PostgreSQL engine behavior.

---

### Question 1: Two customers on different instances claim the last unit at the same instant

#### SQL Executed by Each Request:
When two requests arrive concurrently at Instance 1 and Instance 2, each request executes the following sequence inside an explicit transaction:
1. **Idempotency Acquisition** (`IdempotencyRepository.kt:47-75`):
   ```sql
   INSERT INTO idempotency_keys (customer_id, idempotency_key, deal_id, quantity, status, created_at, updated_at)
   VALUES (?, ?, ?, ?, 'IN_PROGRESS', NOW(), NOW());
   ```
2. **Pessimistic Row Lock & Invariant Check** (`ClaimRepository.kt:33-43`):
   ```sql
   SELECT price_paise, stock_available, starts_at, ends_at
   FROM deals
   WHERE id = ?
   FOR UPDATE;
   ```
3. **Stock Decrement** (`ClaimRepository.kt:57-63`):
   ```sql
   UPDATE deals SET stock_available = stock_available - ? WHERE id = ?;
   ```
4. **Claim Insertion** (`ClaimRepository.kt:70-89`):
   ```sql
   INSERT INTO claims (id, deal_id, customer_id, quantity, amount_paise, status, expires_at, created_at, updated_at)
   VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, NOW(), NOW());
   ```
5. **Idempotency Completion** (`IdempotencyRepository.kt:125-141`):
   ```sql
   UPDATE idempotency_keys
   SET status = 'COMPLETED', response_code = 201, response_body = ?, updated_at = NOW()
   WHERE customer_id = ? AND idempotency_key = ?;
   ```

#### PostgreSQL Engine Behavior:
Both transactions attempt to execute `SELECT ... FOR UPDATE` on the same `deals` tuple. PostgreSQL grants an exclusive row-level write lock (`ExclusiveLock` on the tuple) to the first transaction to reach the engine (say Request A). 
Request B is immediately put into a wait state (`LockWait`) inside the PostgreSQL kernel.

- **Request A** reads `stock_available = 1`. Its check `1 >= 1` succeeds. It decrements `stock_available = 0`, inserts the claim row, completes its idempotency record, and issues `COMMIT`.
- When Request A commits, PostgreSQL releases the row lock.
- **Request B** immediately unblocks. Under PostgreSQL's `READ COMMITTED` isolation level, when a row locked with `FOR UPDATE` is updated and committed by a concurrent transaction, PostgreSQL re-evaluates the query against the newly committed version of the tuple.
- Request B now reads the fresh committed state: `stock_available = 0`.
- Request B's validation check `0 >= 1` fails (`ClaimRepository.kt:52`). Request B throws `SoldOutException`, rolls back its transaction, and records/returns `409 SOLD_OUT`.

#### Why Mutex or synchronized won't fix this:
A Kotlin `Mutex` or Java `synchronized` block operates strictly within the memory space of a single JVM process. When running 2 (or N) instances, each process has its own heap and thread scheduler. Instance 1 has zero visibility into locks held on Instance 2. PostgreSQL acts as the single source of truth; only database-level row locks synchronize distributed nodes.

---

### Question 2: Service commits a claim, then crashes before responding

#### Scenario A: Crash occurs after commit
In our design (`ClaimService.kt:85-104`), claim creation and idempotency completion are executed within the **same atomic database transaction** (`DatabaseFactory.inTransaction`). 
- When `conn.commit()` succeeds, both the `claims` record (status `RESERVED`) and the `idempotency_keys` record (status `COMPLETED`, `response_code = 201`, and full JSON response body) are durably committed to WAL and disk.
- When the client retries with the same `Idempotency-Key`, `acquireOrGetReplay` (`IdempotencyRepository.kt:77-87`) queries `idempotency_keys`. It finds `status == 'COMPLETED'`, confirms `deal_id` and `quantity` match, and immediately returns the cached response with `HttpStatusCode.Created` and header `Idempotent-Replayed: true`. No new claim is created, and no stock is decremented.

#### Scenario B: Crash occurs just before commit
If the process crashes before `conn.commit()` executes:
- PostgreSQL detects connection termination and automatically rolls back the entire uncommitted transaction.
- The claim was never created, deal stock was never decremented, and any temporary row locks are released.
- If an `IN_PROGRESS` idempotency key was committed in an earlier phase, our lease-timeout mechanism (`IdempotencyRepository.kt:105-121`) checks `updated_at < NOW() - INTERVAL '60 SECONDS'`. The crash leaves the record abandoned; once 60 seconds have elapsed, the client's retry reclaims the slot and safely executes the claim from scratch without false `REQUEST_IN_PROGRESS` deadlocks.

---

### Question 3: Webhook and expiry job reach the same claim in the same millisecond

Both the payment webhook (`WebhookRepository.kt:53-70`) and the expiry reaper (`ClaimRepository.kt:145-168`) lock the target claim row using `SELECT ... FOR UPDATE` (the reaper uses `FOR UPDATE SKIP LOCKED`). PostgreSQL serializes the execution into two possible orderings:

| Ordering | Execution Order | What Happens | Final Claim Status | Deal Stock (`stock_available`) |
|---|---|---|---|---|
| **1A: Payment Wins (Before Expiry)** | Webhook acquires lock first (`now < expires_at`). Amount matches. | Webhook sets status to `PAID`. Commits. Reaper unblocks; ignores claim since status is no longer `RESERVED`. | `PAID` | Stock remains unchanged (units stay sold). |
| **1B: Payment Wins (Wrong Amount)** | Webhook acquires lock first (`now < expires_at`). Amount differs. | Webhook sets status to `NEEDS_REVIEW`. Commits. Reaper unblocks; ignores claim since status is no longer `RESERVED`. | `NEEDS_REVIEW` | Stock remains unchanged (units stay held for review). |
| **1C: Payment Wins (Late Payment)** | Webhook acquires lock first, but clock shows `now >= expires_at`. | Webhook sets status to `REFUND_PENDING` and increments `stock_available = stock_available + qty`. Commits. Reaper sees non-reserved. | `REFUND_PENDING` | Stock incremented by quantity (units returned to sale). |
| **2: Expiry Wins** | Expiry reaper acquires lock first. | Reaper marks claim `EXPIRED`, restores stock (`stock_available = stock_available + qty`), and commits. Webhook unblocks, sees status `EXPIRED`, transitions to `REFUND_PENDING` without touching stock. | `REFUND_PENDING` | Stock incremented by quantity (units returned to sale). |

In every possible sequence, the fundamental invariant `stock_available + sum(active_units) == stock_total` is strictly preserved.

---

### Question 4: Webhook raw body signing, timestamp protection, and constant-time comparison

1. **Why sign the raw body rather than parsed JSON?**
   JSON serialization is non-deterministic: field order (`{"a":1,"b":2}` vs `{"b":2,"a":1}`), whitespace, indentation, number precision, and character escapes vary across platforms. Re-serializing parsed JSON will produce different bytes than what the payment gateway transmitted, causing HMAC verification to fail. Computing the HMAC over the exact received bytes (`call.receiveRawBytesWithLimit()`) guarantees cryptographic reproducibility.

2. **What does the timestamp protect against?**
   Replay attacks. If an adversary intercepts a valid webhook over the network, they cannot replay that packet minutes or days later to manipulate claim status. Enforcing `abs(currentUnixSeconds - t) <= 300` rejects any event outside a 5-minute clock skew window.

3. **Why compare signatures in constant time?**
   Standard byte/string comparisons (`String.equals` or `==`) abort on the first mismatching byte. This leaks execution time proportional to the length of the matching prefix. Attackers can exploit this timing side-channel to brute-force valid signatures byte by byte. `MessageDigest.isEqual` (`WebhookSignatureVerifier.kt:47`) executes in $O(N)$ time independent of mismatch position, neutralizing timing attacks.

---

### Question 5: Transaction isolation level & anomalies with weaker approaches

- **Isolation Level Used**: `READ COMMITTED` combined with pessimistic row-level locking (`SELECT ... FOR UPDATE`).
- **Why it is enough**: In PostgreSQL, `READ COMMITTED` guarantees that statements see only committed data. When combined with `FOR UPDATE`, PostgreSQL forces any concurrent transaction attempting to read or modify that row to block. Upon unblocking, PostgreSQL re-evaluates the row lock on the updated version of the tuple. This prevents lost updates, dirty reads, and non-repeatable reads on locked rows without incurring the heavy abort-and-retry overhead of `SERIALIZABLE` or `REPEATABLE READ` under high concurrency.
- **Anomaly seen with weaker approach**:
  Without `FOR UPDATE` (or under `READ UNCOMMITTED`), a **Lost Update / Overselling Anomaly** occurs:
  1. Deal has `stock_available = 1`.
  2. Transaction 1 reads `stock_available = 1`.
  3. Transaction 2 concurrently reads `stock_available = 1`.
  4. Transaction 1 decrements `1 - 1 = 0` and writes `stock_available = 0`.
  5. Transaction 2 decrements `1 - 1 = 0` and overwrites `stock_available = 0`.
  Both transactions create claims, selling 2 units when only 1 existed.

---

### Question 6: Most critical constraint/index and what breaks if dropped

The most critical constraint in our architecture is the **partial unique index on active claims** (`V2__create_claims_table.sql:16-19`):
```sql
CREATE UNIQUE INDEX idx_claims_active_per_customer_deal 
ON claims (deal_id, customer_id) 
WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');
```

#### What breaks if someone drops it?
**Invariant 2 completely collapses: "One active claim per customer per deal."**
Without this index, two concurrent claim requests from the same customer (e.g. rapid double-tap on a mobile UI or scripted bot attacks across multiple instances) will both pass application-level queries (`SELECT COUNT(*) FROM claims ... WHERE customer_id = ?`) before either transaction commits. Both transactions would insert a `RESERVED` claim, allowing customers to hoard flash deals. The partial unique index pushes duplicate detection directly into the PostgreSQL B-Tree engine, guaranteeing that concurrent inserts produce a `23505 unique_violation`, cleanly caught and mapped to `409 ALREADY_CLAIMED`.

Secondarily, `CONSTRAINT ck_deals_stock_available CHECK (stock_available >= 0)` guarantees at the storage engine level that stock can never drop below zero.

---

### Question 7: 100x traffic spike on a viral deal — What fails first and remediation

#### What fails first:
**Row lock contention on the single `deals` table row for that viral deal.**
If 10,000 customers claim the same deal in a single second, all 10,000 database connections queue up on the identical row lock (`SELECT ... FOR UPDATE WHERE id = ?`).
1. HikariCP connection pools (50 connections per instance) become completely saturated within milliseconds.
2. Thread starvation cascades into HTTP connection timeouts and 500 errors.
3. PostgreSQL CPU spikes due to lock manager spinlocks and transaction context switches.

#### Architectural Changes for 100x Scale:
1. **Redis Atomic Counter / Token Bucket Pre-allocation**:
   Store available stock in Redis (`DECRBY deal:{id}:stock {quantity}`). Requests that drop stock below 0 are rejected at the edge in sub-milliseconds without touching PostgreSQL. Only the winning requests proceed to PostgreSQL for durable persistence.
2. **Stock Partitioning / Sharded Counters**:
   Divide deal stock into $K$ shards (e.g. `deal_inventory_shards` with 10 rows of 5 units each). Incoming requests randomly choose a shard to lock, reducing lock contention by a factor of $K$.
3. **Asynchronous Ingestion via Partitioned Kafka Queue**:
   Route claim requests into a Kafka topic partitioned by `deal_id`. A dedicated single-threaded consumer claims inventory in batches, transforming concurrent random writes into high-throughput sequential updates.
