package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.AppPreferences
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ExportSettingsTest {
    @Test fun editsPersistAcrossControllerRecreationWithoutResettingUnrelatedSettings()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        val preferences=AppPreferences(context);val original=preferences.settings.first()
        var controller:ExportSettingsController?=null
        try {
            preferences.update {it.copy(networkGeocoding=false,exportConfiguration=null)}
            val first=ExportSettingsController(preferences);controller=first
            withTimeout(5000) {first.state.first {it.ready}}
            val selection=ExportSelection(format=ExportFormat.csv,additionalFormats=setOf(ExportFormat.gpx),filename="{year}/Saved - {date}",splitDays=true,outings=true,
                options=ExportOptions(kind=DataKind.points,from=Instant.parse("2026-01-01T00:00:00Z"),until=Instant.parse("2026-02-01T00:00:00Z"),zone=ZoneOffset.UTC,startTime=LocalTime.of(22,0),endTime=LocalTime.of(6,0),excludeOutliers=true,pointAltitude=false))
            repeat(20) {i -> first.update(selection.copy(filename="Draft $i - {date}"))}
            first.update(selection)
            preferences.update {it.copy(metric=false,distanceFilter=25)}
            withTimeout(5000) {first.state.first {it.ready && !it.saving && it.error==null && it.selection==selection}}
            assertEquals(selection,ExportSelectionCodec.decode(preferences.settings.first().exportConfiguration))
            assertFalse(preferences.settings.first().networkGeocoding);assertFalse(preferences.settings.first().metric)
            first.close()
            val restored=ExportSettingsController(preferences);controller=restored
            assertEquals(selection,withTimeout(5000) {restored.state.first {it.ready}}.selection)
            assertEquals(25,preferences.settings.first().distanceFilter)
        } finally {controller?.close();preferences.update {original}}
    }

    @Test fun corruptSavedFiltersBlockExportUntilExplicitReset()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        val preferences=AppPreferences(context);val original=preferences.settings.first()
        var controller:ExportSettingsController?=null
        try {
            preferences.update {it.copy(exportConfiguration="{")}
            val current=ExportSettingsController(preferences);controller=current
            val failed=withTimeout(5000) {current.state.first {it.error!=null}}
            assertEquals(ExportSettingsFailure.restore,failed.failure)
            assertFalse(failed.ready);assertEquals("{",preferences.settings.first().exportConfiguration)
            current.reset()
            val reset=withTimeout(5000) {current.state.first {it.ready && !it.saving}}
            assertNull(reset.error);assertEquals(ExportSelection(),ExportSelectionCodec.decode(preferences.settings.first().exportConfiguration))
        } finally {controller?.close();preferences.update {original}}
    }
}
