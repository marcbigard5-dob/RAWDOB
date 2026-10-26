package com.rawdob.guard.core

import java.io.File
import java.security.MessageDigest
import java.util.Base64

data class AuditEvent(val timestampMillis: Long, val type: String, val detail: String)
data class AuditVerification(val valid: Boolean, val checkedLines: Int, val error: String? = null)

/**
 * Local hash-chained audit file. It detects many edits/deletions/reorderings, but is NOT immutable:
 * an attacker able to rewrite the whole file can rebuild the chain. Never log secrets or tokens.
 */
class AuditLog(private val file: File) {
    @Synchronized
    fun append(event: AuditEvent): String {
        file.parentFile?.mkdirs()
        val previousHash = lastHash()
        val type = encode(event.type)
        val detail = encode(event.detail)
        val canonical = "$previousHash|${event.timestampMillis}|$type|$detail"
        val hash = sha256(canonical)
        file.appendText("$canonical|$hash\n", Charsets.UTF_8)
        return hash
    }

    @Synchronized
    fun verify(): AuditVerification {
        if (!file.exists()) return AuditVerification(valid = true, checkedLines = 0)
        var expectedPrevious = GENESIS
        var count = 0
        file.useLines { lines ->
            lines.forEach { line ->
                val fields = line.split('|')
                if (fields.size != 5) return AuditVerification(false, count, "Malformed record at line ${count + 1}")
                val (previous, timestamp, type, detail, storedHash) = fields
                if (previous != expectedPrevious) return AuditVerification(false, count, "Hash-chain link mismatch at line ${count + 1}")
                if (timestamp.toLongOrNull() == null) return AuditVerification(false, count, "Invalid timestamp at line ${count + 1}")
                val expectedHash = sha256("$previous|$timestamp|$type|$detail")
                if (!constantTimeEquals(expectedHash, storedHash)) {
                    return AuditVerification(false, count, "Record hash mismatch at line ${count + 1}")
                }
                try {
                    decode(type)
                    decode(detail)
                } catch (_: IllegalArgumentException) {
                    return AuditVerification(false, count, "Invalid encoded field at line ${count + 1}")
                }
                expectedPrevious = storedHash
                count++
            }
        }
        return AuditVerification(valid = true, checkedLines = count)
    }

    private fun lastHash(): String {
        if (!file.exists()) return GENESIS
        return file.useLines { lines -> lines.lastOrNull()?.split('|')?.lastOrNull() ?: GENESIS }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    private companion object { const val GENESIS = "GENESIS" }
}
