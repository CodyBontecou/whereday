package com.bontecou.whereday.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Launch and boot reconciliation must report a failure instead of crashing every start. */
class LaunchRecoveryTest {
    @Test fun successRunsOnceAndReportsNothing()=runBlocking {
        var runs=0;var failures=0
        assertTrue(recoverOrReport({failures++}) {runs++})
        assertEquals(1,runs);assertEquals(0,failures)
    }

    @Test fun failureIsReportedNotThrown()=runBlocking {
        var failures=0
        assertFalse(recoverOrReport({failures++}) {throw IllegalStateException("Room cannot verify the data integrity")})
        assertEquals(1,failures)
    }

    @Test fun failingReporterStillDoesNotThrow()=runBlocking {
        assertFalse(recoverOrReport({error("log unavailable")}) {throw java.io.IOException("disk I/O error")})
    }

    @Test fun cancellationIsNotSwallowed() {
        var failures=0
        assertThrows(CancellationException::class.java) {runBlocking {recoverOrReport({failures++}) {throw CancellationException("left the screen")}}}
        assertEquals(0,failures)
    }
}
