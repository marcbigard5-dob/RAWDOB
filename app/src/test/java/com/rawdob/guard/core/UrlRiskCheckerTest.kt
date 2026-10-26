package com.rawdob.guard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlRiskCheckerTest {
    @Test fun plainHttpsDomainHasNoConfiguredFlags() {
        val result = UrlRiskChecker.check("https://example.com")
        assertEquals(UrlRiskLevel.LOW, result.level)
        assertEquals("example.com", result.normalizedHost)
    }
    @Test fun httpIsFlaggedForReview() {
        val result = UrlRiskChecker.check("http://example.com")
        assertEquals(UrlRiskLevel.REVIEW, result.level)
        assertTrue(result.reasons.any { it.contains("HTTP") })
    }
    @Test fun malformedInputIsInvalid() {
        assertEquals(UrlRiskLevel.INVALID, UrlRiskChecker.check("not a url").level)
    }
    @Test fun sensitivePhraseIsFlagged() {
        val result = UrlRiskChecker.check("https://example.com/verify-account")
        assertEquals(UrlRiskLevel.REVIEW, result.level)
    }
}
