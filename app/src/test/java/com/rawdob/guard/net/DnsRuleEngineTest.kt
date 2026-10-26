package com.rawdob.guard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsRuleEngineTest {
    private val builtIn = setOf("doubleclick.net", "googlesyndication.com")

    @Test
    fun legacyBareLineBlocksDomainAndSubdomains() {
        assertTrue(DnsRuleEngine.shouldBlock("ads.example.com", "example.com", builtIn))
        assertTrue(DnsRuleEngine.shouldBlock("example.com", "example.com", builtIn))
        assertFalse(DnsRuleEngine.shouldBlock("notexample.com", "example.com", builtIn))
    }

    @Test
    fun allowRuleOverridesBuiltInBlockRule() {
        assertTrue(DnsRuleEngine.shouldBlock("ads.doubleclick.net", "", builtIn))
        assertFalse(DnsRuleEngine.shouldBlock("ads.doubleclick.net", "allow: doubleclick.net", builtIn))
    }

    @Test
    fun explicitBlockAndCaseNormalizationWork() {
        assertTrue(DnsRuleEngine.shouldBlock("IMG.Example.COM.", "block: EXAMPLE.com", builtIn))
    }

    @Test
    fun invalidRulesAreIgnored() {
        assertEquals(0, DnsRuleEngine.parse("bad domain.com\nhttps://example.com\n*.example.com").size)
        assertFalse(DnsRuleEngine.shouldBlock("example.com", "bad domain.com\nhttps://example.com", builtIn))
    }

    @Test
    fun idnIsNormalizedToAscii() {
        val parsed = DnsRuleEngine.parse("block: bücher.example")
        assertEquals(1, parsed.size)
        assertTrue(DnsRuleEngine.shouldBlock("xn--bcher-kva.example", "block: bücher.example", emptySet()))
    }
}
