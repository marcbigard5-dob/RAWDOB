package com.rawdob.guard.net

import java.net.IDN
import java.util.Locale

/**
 * Transparent DNS-domain policy only. This is not an IP/TCP/UDP firewall.
 * Legacy bare lines are treated as BLOCK rules for backward compatibility.
 */
object DnsRuleEngine {
    enum class Action { ALLOW, BLOCK }

    data class Rule(val action: Action, val domain: String)

    fun parse(rawRules: String): List<Rule> = rawRules.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val lower = line.lowercase(Locale.ROOT)
            val action: Action
            val rawDomain: String
            when {
                lower.startsWith("allow:") -> {
                    action = Action.ALLOW
                    rawDomain = line.substringAfter(':').trim()
                }
                lower.startsWith("block:") -> {
                    action = Action.BLOCK
                    rawDomain = line.substringAfter(':').trim()
                }
                else -> {
                    action = Action.BLOCK
                    rawDomain = line
                }
            }
            val normalized = normalizeDomain(rawDomain) ?: return@mapNotNull null
            Rule(action, normalized)
        }
        .distinct()
        .toList()

    fun shouldBlock(domain: String, rawRules: String, builtInBlockedDomains: Set<String>): Boolean {
        val normalized = normalizeDomain(domain) ?: return false
        val rules = parse(rawRules)
        if (rules.any { it.action == Action.ALLOW && matches(normalized, it.domain) }) return false
        if (rules.any { it.action == Action.BLOCK && matches(normalized, it.domain) }) return true
        return builtInBlockedDomains.mapNotNull(::normalizeDomain).any { matches(normalized, it) }
    }

    fun normalizeDomain(value: String): String? {
        val candidate = value.trim().trimEnd('.')
        if (candidate.isEmpty() || candidate.length > 253 || candidate.any { it.isWhitespace() }) return null
        val ascii = try {
            IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (ascii.length > 253) return null
        val labels = ascii.split('.')
        if (labels.any { label ->
                label.isEmpty() || label.length > 63 ||
                    !label.first().isLetterOrDigit() ||
                    !label.last().isLetterOrDigit() ||
                    label.any { !(it.isLetterOrDigit() || it == '-') }
            }) return null
        return ascii
    }

    private fun matches(host: String, ruleDomain: String): Boolean =
        host == ruleDomain || host.endsWith(".$ruleDomain")
}
