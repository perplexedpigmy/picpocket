package com.picpocket.app.drive.sync

import org.junit.Assert.assertTrue
import org.junit.Test

class DriveFileManagerVerifyOutcomeTest {

    @Test
    fun `matching size is verified`() {
        assertTrue(DriveFileManager.verifyOutcome(10, 10L) is WriteOutcome.Verified)
    }

    @Test
    fun `mismatched size is failed`() {
        val outcome = DriveFileManager.verifyOutcome(10, 7L)
        assertTrue(outcome is WriteOutcome.Failed)
    }

    @Test
    fun `mismatched size reason mentions expected and actual`() {
        val outcome = DriveFileManager.verifyOutcome(10, 7L)
        assertTrue(outcome is WriteOutcome.Failed)
        val reason = (outcome as WriteOutcome.Failed).reason
        assertTrue(reason.contains("expected=10"))
        assertTrue(reason.contains("actual=7"))
    }

    @Test
    fun `null size is failed`() {
        assertTrue(DriveFileManager.verifyOutcome(10, null) is WriteOutcome.Failed)
    }
}
