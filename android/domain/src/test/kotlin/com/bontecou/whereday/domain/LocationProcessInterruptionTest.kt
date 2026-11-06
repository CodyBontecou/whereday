package com.bontecou.whereday.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationProcessInterruptionTest {
    @Test fun sameBootIsAnInterruptionEvenWhenTheBootBroadcastIsDeliveredAgain() {
        for(boot in listOf(0,1,100,Int.MAX_VALUE)) assertEquals(RecordingInterruption.processInterrupted,locationProcessInterruption(boot,boot))
    }
    @Test fun changedKnownBootProvesRestart() {
        for((old,current) in listOf(0 to 1,1 to 2,100 to 101,Int.MAX_VALUE to 0))
            assertEquals(RecordingInterruption.phoneRestarted,locationProcessInterruption(old,current))
    }
    @Test fun missingOrUnreadableBootCannotClaimRestart() {
        for(old in listOf(null,-1,0,20)) for(current in listOf(null,-1,0,20))
            if(old==null || old<0 || current==null || current<0)
                assertEquals(RecordingInterruption.processInterrupted,locationProcessInterruption(old,current))
    }
}
