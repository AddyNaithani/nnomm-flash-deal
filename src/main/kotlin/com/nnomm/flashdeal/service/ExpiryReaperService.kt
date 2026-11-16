package com.nnomm.flashdeal.service

import com.nnomm.flashdeal.db.DatabaseFactory
import com.nnomm.flashdeal.repository.ClaimRepository
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

class ExpiryReaperService(
    private val claimRepository: ClaimRepository = ClaimRepository(),
    private val pollIntervalMs: Long = 1000
) {
    private val logger = LoggerFactory.getLogger(ExpiryReaperService::class.java)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            logger.info("Starting background ExpiryReaperService with pollIntervalMs=$pollIntervalMs")
            while (isActive) {
                try {
                    val count = DatabaseFactory.inTransaction { conn ->
                        claimRepository.expireReservedClaims(conn, batchSize = 100)
                    }
                    if (count > 0) {
                        logger.info("Expired $count reserved claim(s) and restored stock to deals")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error in ExpiryReaperService loop: ${e.message}")
                }
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
