package com.rawdob.guard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyEngineTest {
    private val engine = PolicyEngine(
        listOf(PolicyRule("r1", "example.com", PolicyAction.BLOCK, reason = "test block"))
    )
    @Test fun exactHostMatches() {
        assertEquals(PolicyAction.BLOCK, engine.evaluate("example.com", PolicyMode.ENFORCE).action)
    }
    @Test fun subdomainMatchesButLookalikeDoesNot() {
        assertEquals(PolicyAction.BLOCK, engine.evaluate("shop.example.com", PolicyMode.ENFORCE).action)
        assertEquals(PolicyAction.ALLOW, engine.evaluate("notexample.com", PolicyMode.ENFORCE).action)
    }
    @Test fun learningModeDoesNotReturnBlockDecision() {
        assertEquals(PolicyAction.REVIEW, engine.evaluate("example.com", PolicyMode.LEARNING).action)
    }
}
