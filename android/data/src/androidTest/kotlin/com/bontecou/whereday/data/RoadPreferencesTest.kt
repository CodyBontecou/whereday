package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Uses only the disposable data.test package. */
class RoadPreferencesTest {
    @Test fun policyRevisionIsAtomicOwnedByStorageAndUnrelatedWritesPreserveIt()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        val baseline=AppPreferences(context);val original=baseline.settings.first()
        val callbacks=mutableListOf<String>()
        val preferences=AppPreferences(context) {callbacks+=it}
        try {
            preferences.update {it.copy(roadDisplayEnabled=false,networkGeocoding=true)}
            callbacks.clear();val before=preferences.settings.first()
            preferences.update {it.copy(roadDisplayEnabled=true,roadAccessRevision="caller-forged")}
            val enabled=preferences.settings.first()
            assertTrue(enabled.roadDisplayEnabled);assertNotEquals(before.roadAccessRevision,enabled.roadAccessRevision)
            assertEquals(listOf(enabled.roadAccessRevision),callbacks);assertNotEquals("caller-forged",enabled.roadAccessRevision)
            preferences.update {it.copy(metric=!it.metric,roadAccessRevision="forged-again")}
            assertEquals(enabled.roadAccessRevision,preferences.settings.first().roadAccessRevision);assertEquals(1,callbacks.size)
            preferences.update {it.copy(networkGeocoding=false)}
            val off=preferences.settings.first();assertFalse(off.networkGeocoding)
            assertNotEquals(enabled.roadAccessRevision,off.roadAccessRevision);assertEquals(2,callbacks.size)
            preferences.update {it.copy(roadDisplayEnabled=false)}
            assertNotEquals(off.roadAccessRevision,preferences.settings.first().roadAccessRevision);assertEquals(3,callbacks.size)
        } finally {baseline.update {original}}
    }
    @Test fun invalidTransformAndFailedPrecommitCallbackRollBackPolicy()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        val baseline=AppPreferences(context);val before=baseline.settings.first();var callbacks=0
        val invalid=AppPreferences(context) {callbacks++}
        try {invalid.update {it.copy(roadDisplayEnabled=!it.roadDisplayEnabled,distanceFilter=-1)};fail("Invalid edit accepted")}
        catch(_:IllegalArgumentException) {}
        assertEquals(0,callbacks);assertEquals(before,baseline.settings.first())
        val failing=AppPreferences(context) {callbacks++;error("invalidation unavailable")}
        try {failing.update {it.copy(roadDisplayEnabled=!it.roadDisplayEnabled)};fail("Callback failure swallowed")}
        catch(_:IllegalStateException) {}
        assertEquals(1,callbacks);assertEquals(before,baseline.settings.first())
    }
    @Test fun defaultRoadConsentIsOff() {assertFalse(AppSettings().roadDisplayEnabled)}
}
