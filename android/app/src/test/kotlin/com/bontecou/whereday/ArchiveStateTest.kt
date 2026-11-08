package com.bontecou.whereday

import com.bontecou.whereday.billing.AccessState
import com.bontecou.whereday.export.ExportFormat
import com.bontecou.whereday.export.HistoryImportProblem
import com.bontecou.whereday.export.HistoryImportRecordKind
import org.junit.Assert.*
import org.junit.Test

class ArchiveStateTest {
    @Test fun historyIsLoadingUntilTheFirstCountArrives() {
        // A null count is the first database read in flight; it must not read as "Nothing recorded yet".
        assertEquals(ArchiveHistory.loading,archiveHistory(null))
        assertEquals(ArchiveHistory.empty,archiveHistory(0))
        assertEquals(ArchiveHistory.stored,archiveHistory(25))
    }

    @Test fun onlyStatesThatNeedAPurchaseLockDelivery() {
        assertTrue(exportDeliveryLocked(AccessState.free))
        assertTrue(exportDeliveryLocked(AccessState.pending))
        assertTrue(exportDeliveryLocked(AccessState.unavailable))
        // Delivery refreshes access itself, so these still try and report a visible notice if they fail.
        assertFalse(exportDeliveryLocked(AccessState.active))
        assertFalse(exportDeliveryLocked(AccessState.checking))
        assertFalse(exportDeliveryLocked(AccessState.verificationRequired))
    }

    @Test fun everyFormatHasAProductNameRatherThanItsEnumName() {
        val expected=mapOf(ExportFormat.json to "JSON",ExportFormat.csv to "CSV",ExportFormat.markdown to "Markdown",ExportFormat.geojson to "GeoJSON",
            ExportFormat.gpx to "GPX",ExportFormat.kml to "KML",ExportFormat.owntracks to "OwnTracks",ExportFormat.overland to "Overland")
        assertEquals(ExportFormat.entries.toSet(),expected.keys)
        expected.forEach {(format,name)->assertEquals(name,recipeFormatName(format))}
    }

    @Test fun everyImportProblemAndRecordHasItsOwnText() {
        assertEquals(HistoryImportProblem.entries.size,HistoryImportProblem.entries.map(::historyImportProblemResource).toSet().size)
        assertEquals(HistoryImportRecordKind.entries.size,HistoryImportRecordKind.entries.map(::historyImportRecordResource).toSet().size)
    }

    @Test fun dialTextOnlyShrinksWhenItsDiagonalLeavesTheRing() {
        assertEquals(1f,circleFitScale(60,40,124f),0f)
        assertEquals(1f,circleFitScale(0,0,124f),0f)
        // 300×400 has a 500 diagonal: it must shrink to a fifth to fit a 100 circle.
        assertEquals(0.2f,circleFitScale(300,400,100f),0.0001f)
    }
}
