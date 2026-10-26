package com.rawdob.guard.core

enum class PolicyAction { ALLOW, BLOCK, REVIEW }
enum class PolicyMode { MONITOR_ONLY, LEARNING, ENFORCE }

data class PolicyRule(
    val id: String,
    val hostSuffix: String,
    val action: PolicyAction,
    val enabled: Boolean = true,
    val reason: String
)

data class PolicyDecision(
    val action: PolicyAction,
    val matchedRuleId: String?,
    val explanation: String
)

/** Pure policy evaluation only. It does not intercept or block device network traffic. */
class PolicyEngine(private val rules: List<PolicyRule>) {
    fun evaluate(hostInput: String, mode: PolicyMode): PolicyDecision {
        val host = hostInput.trim().trimEnd('.').lowercase()
        if (host.isBlank() || host.any { it.isWhitespace() }) {
            return PolicyDecision(PolicyAction.REVIEW, null, "Invalid or empty hostname; manual review recommended.")
        }
        val match = rules.asSequence().filter { it.enabled }.filter { rule ->
            val suffix = rule.hostSuffix.trim().trim('.').lowercase()
            suffix.isNotBlank() && (host == suffix || host.endsWith(".$suffix"))
        }.maxByOrNull { it.hostSuffix.length }

        if (match == null) {
            return PolicyDecision(PolicyAction.ALLOW, null, "No local rule matched. This is not a guarantee that the destination is safe.")
        }
        if (mode != PolicyMode.ENFORCE) {
            return PolicyDecision(PolicyAction.REVIEW, match.id, "${match.reason} (configured action: ${match.action}; mode: $mode)")
        }
        return PolicyDecision(match.action, match.id, match.reason)
    }
}
