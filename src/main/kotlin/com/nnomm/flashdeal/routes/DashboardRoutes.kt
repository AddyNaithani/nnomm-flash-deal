package com.nnomm.flashdeal.routes

import com.nnomm.flashdeal.domain.UserRole
import com.nnomm.flashdeal.security.JwtService
import com.nnomm.flashdeal.security.WebhookSignatureVerifier
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.dashboardRoutes(jwtService: JwtService, webhookVerifier: WebhookSignatureVerifier) {

    get("/demo/token") {
        val roleParam = call.request.queryParameters["role"] ?: "customer"
        val role = UserRole.fromString(roleParam) ?: UserRole.CUSTOMER
        val restaurantId = if (role == UserRole.RESTAURANT_ADMIN) UUID.randomUUID() else null
        val token = jwtService.generateToken(UUID.randomUUID(), role, restaurantId)
        call.respond(mapOf("token" to token, "role" to role.roleName))
    }

    get("/demo/sign") {
        val payload = call.request.queryParameters["payload"] ?: ""
        val now = System.currentTimeMillis() / 1000
        val sig = webhookVerifier.sign(payload.toByteArray(Charsets.UTF_8), unixSeconds = now)
        call.respond(mapOf("signature" to sig))
    }

    get("/") {
        call.respondText(DASHBOARD_HTML, ContentType.Text.Html)
    }
}

private val DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Nnomm Flash-Deal Test Console</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <style>
        @keyframes pulse-fast { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }
        .pulse-live { animation: pulse-fast 1.5s cubic-bezier(0.4, 0, 0.6, 1) infinite; }
    </style>
</head>
<body class="bg-slate-900 text-slate-100 min-h-screen p-6 font-sans">
    <div class="max-w-5xl mx-auto space-y-6">
        
        <!-- Header -->
        <header class="bg-slate-800 border border-slate-700 rounded-xl p-6 shadow-xl flex justify-between items-center">
            <div>
                <h1 class="text-2xl font-bold text-amber-400">⚡ Nnomm Flash-Deal Service</h1>
                <p class="text-sm text-slate-400">Interactive Visual Testing Console (Ports 8080 & 8081)</p>
            </div>
            <div class="flex items-center space-x-3">
                <span class="inline-flex items-center px-3 py-1 rounded-full text-xs font-semibold bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">
                    <span class="w-2 h-2 rounded-full bg-emerald-400 mr-2 pulse-live"></span> PostgreSQL & 2 Instances Live
                </span>
            </div>
        </header>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
            
            <!-- Step 1: Create a Deal -->
            <div class="bg-slate-800 border border-slate-700 rounded-xl p-6 shadow-lg flex flex-col justify-between">
                <div>
                    <div class="flex items-center justify-between mb-4">
                        <h2 class="text-lg font-semibold text-white">1. Restaurant Admin: Create Deal</h2>
                        <span class="text-xs bg-amber-500/20 text-amber-400 px-2 py-0.5 rounded font-mono">POST /v1/deals</span>
                    </div>
                    <div class="space-y-3">
                        <div>
                            <label class="block text-xs text-slate-400 mb-1">Deal Title</label>
                            <input id="dealTitle" type="text" value="Chicken Biryani Feast Box" class="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-amber-400">
                        </div>
                        <div class="grid grid-cols-2 gap-3">
                            <div>
                                <label class="block text-xs text-slate-400 mb-1">Price (₹99.00 = 9900 paise)</label>
                                <input id="dealPrice" type="number" value="9900" class="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-amber-400">
                            </div>
                            <div>
                                <label class="block text-xs text-slate-400 mb-1">Stock Units</label>
                                <input id="dealStock" type="number" value="50" class="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-amber-400">
                            </div>
                        </div>
                    </div>
                </div>
                <button onclick="createDeal()" class="mt-5 w-full bg-amber-500 hover:bg-amber-600 text-slate-950 font-bold py-2.5 px-4 rounded-lg transition duration-150 shadow-md">
                    🚀 Create Flash Deal
                </button>
            </div>

            <!-- Step 2: Live Deal Info -->
            <div class="bg-slate-800 border border-slate-700 rounded-xl p-6 shadow-lg flex flex-col justify-between">
                <div>
                    <div class="flex items-center justify-between mb-4">
                        <h2 class="text-lg font-semibold text-white">2. Live Deal Status</h2>
                        <span class="text-xs bg-cyan-500/20 text-cyan-400 px-2 py-0.5 rounded font-mono">GET /v1/deals/{id}</span>
                    </div>
                    <div id="dealCard" class="bg-slate-900 border border-slate-700/60 rounded-lg p-4 space-y-2 text-sm text-slate-300">
                        <p class="text-slate-500 italic">No deal created yet. Click "Create Flash Deal" on the left!</p>
                    </div>
                </div>
                <div class="flex items-center space-x-3 mt-4">
                    <button onclick="refreshDeal()" id="btnRefresh" disabled class="flex-1 bg-slate-700 hover:bg-slate-600 disabled:opacity-40 text-white font-medium py-2 px-3 rounded-lg text-sm transition">
                        🔄 Refresh Stock
                    </button>
                </div>
            </div>
        </div>

        <!-- Step 3: Customer Claiming -->
        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div class="bg-slate-800 border border-slate-700 rounded-xl p-6 shadow-lg flex flex-col justify-between">
                <div>
                    <div class="flex items-center justify-between mb-4">
                        <h2 class="text-lg font-semibold text-white">3. Customer: Claim Units</h2>
                        <span class="text-xs bg-emerald-500/20 text-emerald-400 px-2 py-0.5 rounded font-mono">POST /v1/deals/{id}/claims</span>
                    </div>
                    <p class="text-xs text-slate-400 mb-4">Each claim holds units for 10 minutes (`RESERVED`) until paid via webhook.</p>
                    <div class="space-y-3">
                        <div>
                            <label class="block text-xs text-slate-400 mb-1">Quantity (1 - 3)</label>
                            <select id="claimQty" class="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white focus:outline-none focus:border-emerald-400">
                                <option value="1">1 Unit</option>
                                <option value="2" selected>2 Units</option>
                                <option value="3">3 Units</option>
                            </select>
                        </div>
                        <div>
                            <label class="block text-xs text-slate-400 mb-1">Send request to instance:</label>
                            <select id="claimInstance" class="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-2 text-sm text-white">
                                <option value="8080">Instance 1 (Port 8080)</option>
                                <option value="8081">Instance 2 (Port 8081)</option>
                            </select>
                        </div>
                    </div>
                </div>
                <button onclick="claimDeal()" id="btnClaim" disabled class="mt-5 w-full bg-emerald-500 hover:bg-emerald-600 disabled:opacity-40 text-slate-950 font-bold py-2.5 px-4 rounded-lg transition duration-150 shadow-md">
                    ⚡ Claim Deal Units
                </button>
            </div>

            <!-- Step 4: Webhook Simulation -->
            <div class="bg-slate-800 border border-slate-700 rounded-xl p-6 shadow-lg flex flex-col justify-between">
                <div>
                    <div class="flex items-center justify-between mb-4">
                        <h2 class="text-lg font-semibold text-white">4. Payment Webhook</h2>
                        <span class="text-xs bg-purple-500/20 text-purple-400 px-2 py-0.5 rounded font-mono">POST /v1/webhooks/payments</span>
                    </div>
                    <div id="claimCard" class="bg-slate-900 border border-slate-700/60 rounded-lg p-4 space-y-2 text-sm text-slate-300">
                        <p class="text-slate-500 italic">No active claim yet. Click "Claim Deal Units" first!</p>
                    </div>
                </div>
                <div class="grid grid-cols-2 gap-3 mt-4">
                    <button onclick="sendPayment('payment.succeeded')" id="btnPay" disabled class="bg-purple-600 hover:bg-purple-700 disabled:opacity-40 text-white font-bold py-2.5 px-3 rounded-lg text-sm transition">
                        💳 Pay (Succeed)
                    </button>
                    <button onclick="sendPayment('payment.failed')" id="btnFail" disabled class="bg-rose-700 hover:bg-rose-800 disabled:opacity-40 text-white font-bold py-2.5 px-3 rounded-lg text-sm transition">
                        ❌ Pay (Failed)
                    </button>
                </div>
            </div>
        </div>

        <!-- Activity & Raw JSON Log -->
        <div class="bg-slate-800 border border-slate-700 rounded-xl p-6 shadow-lg">
            <div class="flex justify-between items-center mb-3">
                <h2 class="text-sm font-semibold uppercase tracking-wider text-slate-400">Live API Response Inspector</h2>
                <button onclick="document.getElementById('logOutput').textContent = 'Ready.'" class="text-xs text-slate-500 hover:text-slate-300">Clear</button>
            </div>
            <pre id="logOutput" class="bg-slate-950 p-4 rounded-lg text-xs font-mono text-emerald-400 overflow-x-auto max-h-60 border border-slate-800">Ready. Click any button above to test the APIs.</pre>
        </div>
    </div>

    <script>
        let currentDeal = null;
        let currentClaim = null;

        function log(data) {
            document.getElementById('logOutput').textContent = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
        }

        async function getToken(role) {
            const res = await fetch('/demo/token?role=' + role);
            const data = await res.json();
            return data.token;
        }

        async function createDeal() {
            try {
                const token = await getToken('restaurant_admin');
                const now = new Date();
                const ends = new Date(now.getTime() + 2 * 3600 * 1000);
                
                const body = {
                    title: document.getElementById('dealTitle').value,
                    price_paise: parseInt(document.getElementById('dealPrice').value),
                    stock_total: parseInt(document.getElementById('dealStock').value),
                    starts_at: now.toISOString(),
                    ends_at: ends.toISOString()
                };

                const res = await fetch('/v1/deals', {
                    method: 'POST',
                    headers: {
                        'Authorization': 'Bearer ' + token,
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify(body)
                });
                
                const deal = await res.json();
                currentDeal = deal;
                log(deal);

                document.getElementById('dealCard').innerHTML = `
                    <div class="flex justify-between items-center">
                        <span class="font-bold text-white text-base">${'$'}{deal.title}</span>
                        <span class="text-amber-400 font-mono font-bold">₹${'$'}{(deal.price_paise / 100).toFixed(2)}</span>
                    </div>
                    <div class="text-xs text-slate-400 font-mono">ID: ${'$'}{deal.id}</div>
                    <div class="mt-3 flex items-center justify-between bg-slate-950/70 p-3 rounded border border-slate-800">
                        <span>Available Stock:</span>
                        <span class="text-xl font-bold text-emerald-400 font-mono" id="stockDisplay">${'$'}{deal.stock_available} / ${'$'}{deal.stock_total}</span>
                    </div>
                `;
                document.getElementById('btnRefresh').disabled = false;
                document.getElementById('btnClaim').disabled = false;
            } catch (err) {
                log('Error: ' + err.message);
            }
        }

        async function refreshDeal() {
            if (!currentDeal) return;
            const token = await getToken('customer');
            const res = await fetch('/v1/deals/' + currentDeal.id, {
                headers: { 'Authorization': 'Bearer ' + token }
            });
            const deal = await res.json();
            currentDeal = deal;
            document.getElementById('stockDisplay').textContent = deal.stock_available + ' / ' + deal.stock_total;
            log(deal);
        }

        async function claimDeal() {
            if (!currentDeal) return;
            try {
                const token = await getToken('customer');
                const port = document.getElementById('claimInstance').value;
                const qty = parseInt(document.getElementById('claimQty').value);
                const idempotencyKey = 'key-' + Math.random().toString(36).substring(2, 12);

                const res = await fetch('http://localhost:' + port + '/v1/deals/' + currentDeal.id + '/claims', {
                    method: 'POST',
                    headers: {
                        'Authorization': 'Bearer ' + token,
                        'Idempotency-Key': idempotencyKey,
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify({ quantity: qty })
                });

                const claim = await res.json();
                log(claim);
                if (res.status === 201) {
                    currentClaim = claim;
                    document.getElementById('claimCard').innerHTML = `
                        <div class="flex justify-between items-center">
                            <span class="font-bold text-white">Claim ID:</span>
                            <span class="px-2 py-0.5 rounded text-xs font-bold bg-amber-500/20 text-amber-400 font-mono">${'$'}{claim.status}</span>
                        </div>
                        <div class="text-xs text-slate-400 font-mono">${'$'}{claim.id}</div>
                        <div class="mt-2 text-xs flex justify-between">
                            <span>Quantity: <b>${'$'}{claim.quantity} units</b></span>
                            <span>Total: <b class="text-amber-400">₹${'$'}{(claim.amount_paise / 100).toFixed(2)}</b></span>
                        </div>
                        <div class="text-xs text-slate-500 mt-1">Expires at: ${'$'}{new Date(claim.expires_at).toLocaleTimeString()}</div>
                    `;
                    document.getElementById('btnPay').disabled = false;
                    document.getElementById('btnFail').disabled = false;
                    refreshDeal();
                }
            } catch (err) {
                log('Error claiming: ' + err.message);
            }
        }

        async function sendPayment(type) {
            if (!currentClaim) return;
            try {
                const eventPayload = {
                    event_id: 'evt_' + Math.random().toString(36).substring(2, 10),
                    type: type,
                    claim_id: currentClaim.id,
                    amount_paise: currentClaim.amount_paise,
                    occurred_at: new Date().toISOString()
                };
                const rawJson = JSON.stringify(eventPayload);

                // Fetch valid HMAC-SHA256 signature from backend helper
                const sigRes = await fetch('/demo/sign?payload=' + encodeURIComponent(rawJson));
                const sigData = await sigRes.json();

                const res = await fetch('/v1/webhooks/payments', {
                    method: 'POST',
                    headers: {
                        'X-Payment-Signature': sigData.signature,
                        'Content-Type': 'application/json'
                    },
                    body: rawJson
                });

                const data = await res.json();
                log({ webhookEvent: eventPayload, response: data });

                // Refresh claim
                const token = await getToken('customer');
                const claimRes = await fetch('/v1/claims/' + currentClaim.id, {
                    headers: { 'Authorization': 'Bearer ' + token }
                });
                const updatedClaim = await claimRes.json();
                
                const badgeColor = updatedClaim.status === 'PAID' ? 'bg-emerald-500/20 text-emerald-400' : 'bg-rose-500/20 text-rose-400';
                document.getElementById('claimCard').innerHTML = `
                    <div class="flex justify-between items-center">
                        <span class="font-bold text-white">Claim Status:</span>
                        <span class="px-2 py-0.5 rounded text-xs font-bold ${'$'}{badgeColor} font-mono">${'$'}{updatedClaim.status}</span>
                    </div>
                    <div class="text-xs text-slate-400 font-mono">${'$'}{updatedClaim.id}</div>
                    <div class="text-xs text-emerald-400 mt-2 font-medium">Payment processed successfully! Units stay sold.</div>
                `;
                document.getElementById('btnPay').disabled = true;
                document.getElementById('btnFail').disabled = true;
                refreshDeal();
            } catch (err) {
                log('Error sending webhook: ' + err.message);
            }
        }
    </script>
</body>
</html>
""".trimIndent()
