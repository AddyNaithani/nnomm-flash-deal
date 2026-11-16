package com.nnomm.flashdeal.security

import com.nnomm.flashdeal.domain.UnauthenticatedException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class WebhookSignatureVerifier(private val secret: String) {

    fun verify(signatureHeader: String?, rawBodyBytes: ByteArray, currentUnixSeconds: Long = System.currentTimeMillis() / 1000) {
        if (signatureHeader.isNullOrBlank()) {
            throw UnauthenticatedException("Missing X-Payment-Signature header")
        }

        val parts = signatureHeader.split(",")
        var tValue: Long? = null
        var v1Value: String? = null

        for (part in parts) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2) {
                when (kv[0].trim()) {
                    "t" -> tValue = kv[1].trim().toLongOrNull()
                    "v1" -> v1Value = kv[1].trim()
                }
            }
        }

        if (tValue == null || v1Value.isNullOrBlank()) {
            throw UnauthenticatedException("Malformed X-Payment-Signature header")
        }

        // Timestamp check: within 300s of clock
        if (abs(currentUnixSeconds - tValue) > 300) {
            throw UnauthenticatedException("Signature timestamp out of range (> 300s)")
        }

        // Compute HMAC-SHA256 of "$t." + rawBodyBytes
        val prefix = "$tValue.".toByteArray(Charsets.UTF_8)
        val payloadToSign = prefix + rawBodyBytes

        val calculatedHash = computeHmacSha256(payloadToSign, secret)

        // Constant-time hex compare
        val expectedHexBytes = calculatedHash.toByteArray(Charsets.UTF_8)
        val actualHexBytes = v1Value.lowercase().toByteArray(Charsets.UTF_8)

        if (!MessageDigest.isEqual(expectedHexBytes, actualHexBytes)) {
            throw UnauthenticatedException("Invalid webhook signature")
        }
    }

    fun sign(rawBodyBytes: ByteArray, unixSeconds: Long = System.currentTimeMillis() / 1000): String {
        val prefix = "$unixSeconds.".toByteArray(Charsets.UTF_8)
        val payloadToSign = prefix + rawBodyBytes
        val hexHash = computeHmacSha256(payloadToSign, secret)
        return "t=$unixSeconds,v1=$hexHash"
    }

    private fun computeHmacSha256(data: ByteArray, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val rawHmac = mac.doFinal(data)
        return rawHmac.joinToString("") { "%02x".format(it) }
    }
}
