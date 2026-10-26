package com.rawdob.guard.core

import java.net.IDN
import java.net.URI
import java.util.Locale

enum class UrlRiskLevel { LOW, REVIEW, HIGH, INVALID }

data class UrlRiskResult(
    val level: UrlRiskLevel,
    val normalizedHost: String?,
    val reasons: List<String>,
    val disclaimer: String = "Heuristic result only. No site reputation lookup or malware scan was performed."
)

/** Offline checks only; no fetching, execution, or guarantee that a URL is safe. */
object UrlRiskChecker {
    private val suspiciousWords = listOf(
        "verify-account", "login-secure", "password-reset", "wallet-connect",
        "urgent-payment", "account-suspended", "free-gift"
    )

    fun check(input: String): UrlRiskResult {
        val raw = input.trim()
        if (raw.isEmpty() || raw.any { it.isWhitespace() }) return invalid("Enter a URL without spaces.")
        val candidate = if (raw.contains("://")) raw else "https://$raw"
        val uri = try { URI(candidate) } catch (_: Exception) { return invalid("The URL could not be parsed.") }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return invalid("Only HTTP and HTTPS URLs are supported.")
        val rawHost = uri.host ?: return invalid("A valid hostname is required.")
        val host = try { IDN.toASCII(rawHost).lowercase(Locale.ROOT) } catch (_: Exception) {
            return invalid("The hostname is not valid.")
        }
        if (host.isBlank() || host.startsWith(".") || host.endsWith(".")) return invalid("The hostname is not valid.")

        val reasons = mutableListOf<String>()
        if (scheme == "http") reasons += "The URL uses unencrypted HTTP."
        if (host.split('.').any { it.startsWith("xn--") }) reasons += "The hostname uses internationalized/punycode labels; verify spelling carefully."
        if (host.count { it == '.' } >= 4) reasons += "The hostname has many subdomain levels."
        if (Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)) reasons += "The URL uses an IPv4 address instead of a domain name."
        val lowerWhole = (host + uri.path.orEmpty() + uri.rawQuery.orEmpty()).lowercase(Locale.ROOT)
        suspiciousWords.filter { lowerWhole.contains(it) }.forEach { reasons += "Contains a sensitive-action phrase: '$it'." }
        if (uri.rawUserInfo != null) reasons += "The URL includes user-info before the hostname, which can be misleading."
        if (uri.port != -1 && uri.port !in setOf(80, 443)) reasons += "The URL specifies a non-standard port."

        val level = when {
            reasons.size >= 3 -> UrlRiskLevel.HIGH
            reasons.isNotEmpty() -> UrlRiskLevel.REVIEW
            else -> UrlRiskLevel.LOW
        }
        return UrlRiskResult(level, host, reasons.ifEmpty { listOf("No configured heuristic flags were found.") })
    }

    private fun invalid(reason: String) = UrlRiskResult(UrlRiskLevel.INVALID, null, listOf(reason))
}
