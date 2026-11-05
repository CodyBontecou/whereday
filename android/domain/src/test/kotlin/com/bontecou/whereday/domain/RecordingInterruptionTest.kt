package com.bontecou.whereday.domain

import org.junit.Test
import org.junit.Assert.*

class RecordingInterruptionTest {
    @Test fun stableAndLegacyReasonsResolveWithoutMutatingTheStoredValue() {
        for(reason in RecordingInterruption.entries) {
            assertEquals(reason,RecordingInterruption.decode(reason.code))
            assertEquals(reason,RecordingInterruption.decode(reason.diagnostic))
        }
        assertEquals(7,RecordingInterruption.entries.map {it.code}.toSet().size)
    }
    @Test fun unknownFutureAndArbitraryExceptionTextNeverBecomeAKnownCause() {
        for(value in listOf("","recording.interruption.v2.permissionRemoved","Recording stopped: secret-path",RecordingInterruption.permissionRemoved.code+" extra")) assertNull(RecordingInterruption.decode(value))
    }
}
