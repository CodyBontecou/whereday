package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Must run alone in a fresh data.test process before its DataStore delegate is initialized. */
class AppPreferencesUpgradeTest {
    @Test fun originalSerializerFileRetainsSettingsAndPrivacyChoicesAfterUpgrade()=runBlocking {withTimeout(20000) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        val destination=File(context.filesDir,"datastore/settings.preferences_pb")
        check(!destination.exists()) {"Compatibility fixture requires a fresh disposable package"}
        check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
        instrumentation.context.assets.open("legacy-settings-1.1.7.preferences_pb").use {source->destination.outputStream().use {source.copyTo(it)}}
        val preferences=AppPreferences(context)
        val original=preferences.settings.first()
        assertTrue(original.onboarded);assertEquals("dark",original.appearance);assertFalse(original.metric)
        assertEquals(50,original.distanceFilter);assertEquals(4,original.stopAfterHours);assertFalse(original.networkGeocoding)
        assertEquals(60,original.inferredGapMinutes);assertEquals(15,original.inferredMinimumMinutes);assertEquals(3,original.inferredMinimumPoints)
        assertTrue(original.showOutliers);assertEquals("synthetic-legacy-epoch",original.photoEpoch)
        assertFalse(original.roadDisplayEnabled);assertEquals("synthetic-legacy-revision",original.roadAccessRevision)
        assertFalse(original.automaticPhotoSync);assertNull(original.exportFolder)
        assertFalse(original.showPhotoDots)
        preferences.update {it.copy(appearance="light")}
        assertEquals(original.copy(appearance="light"),preferences.settings.first())
        preferences.update {it.copy(networkGeocoding=true)}
        val saved=preferences.settings.first()
        assertTrue(saved.networkGeocoding);assertFalse(saved.roadDisplayEnabled)
        assertNotEquals(original.roadAccessRevision,saved.roadAccessRevision)
        assertEquals(original.copy(appearance="light",networkGeocoding=true,roadAccessRevision=saved.roadAccessRevision),saved)
        assertTrue(destination.length()>0)
    }}
}
