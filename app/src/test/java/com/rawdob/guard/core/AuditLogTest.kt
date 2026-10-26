package com.rawdob.guard.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditLogTest {
    @Test fun appendedEventsVerify() {
        val file = File.createTempFile("rawdob-audit", ".log")
        try {
            val log = AuditLog(file)
            log.append(AuditEvent(100L, "POLICY", "review host"))
            log.append(AuditEvent(200L, "URL_CHECK", "example.test"))
            val result = log.verify()
            assertTrue(result.valid)
            assertEquals(2, result.checkedLines)
        } finally { file.delete() }
    }

    @Test fun changedRecordIsDetected() {
        val file = File.createTempFile("rawdob-audit", ".log")
        try {
            val log = AuditLog(file)
            log.append(AuditEvent(100L, "POLICY", "review host"))
            file.writeText(file.readText().replace("review", "blocked"))
            assertFalse(log.verify().valid)
        } finally { file.delete() }
    }
}
