package com.bontecou.whereday.export

import org.junit.Assert.*
import org.junit.Test

class HistoryImportIssueTest {
    private val validPoint="""{"latitude":18,"longitude":-66,"timestamp":"2026-10-26T13:00:00Z"}"""

    @Test fun departureBeforeArrivalIsATypedReasonNotAFailedRequirement() {
        val visit="""{"latitude":18,"longitude":-66,"arrivedAt":"2026-10-26T13:00:00Z","departedAt":"2026-10-26T12:00:00Z"}"""
        val scan=HistoryImporter.scan("""{"visits":[$visit],"points":[$validPoint]}""".reader(),ExportFormat.json)
        assertEquals(1,scan.rejected)
        assertEquals(listOf(HistoryImportIssue(HistoryImportRecordKind.visit,1,HistoryImportProblem.departureBeforeArrival,"Visit 1: Departure is before arrival")),scan.issues)
        assertEquals(listOf("Visit 1: Departure is before arrival"),scan.errors)
        assertFalse(scan.errors.any {"Failed requirement" in it})
    }

    @Test fun coordinateRangesAndMissingValuesAreDistinguished() {
        val rows=listOf(
            """{"latitude":91,"longitude":-66,"timestamp":"2026-10-26T13:00:00Z"}""",
            """{"latitude":18,"longitude":-181,"timestamp":"2026-10-26T13:00:00Z"}""",
            """{"longitude":-66,"timestamp":"2026-10-26T13:00:00Z"}""",
            """{"latitude":"north","longitude":-66,"timestamp":"2026-10-26T13:00:00Z"}""",
            """{"latitude":18,"longitude":-66,"timestamp":"yesterday"}""",
            """{"id":"not-a-uuid","latitude":18,"longitude":-66,"timestamp":"2026-10-26T13:00:00Z"}""",
            "7",
            validPoint,
        )
        val scan=HistoryImporter.scan("""{"points":[${rows.joinToString(",")}]}""".reader(),ExportFormat.json)
        assertEquals(1,scan.points)
        assertEquals(listOf(HistoryImportProblem.latitude,HistoryImportProblem.longitude,HistoryImportProblem.missingCoordinate,HistoryImportProblem.invalidNumber,
            HistoryImportProblem.missingTime,HistoryImportProblem.invalidIdentifier,HistoryImportProblem.notRecord),scan.issues.map {it.problem})
        assertEquals((1..7).toList(),scan.issues.map {it.number})
        assertTrue(scan.issues.all {it.record==HistoryImportRecordKind.point})
        assertEquals(scan.errors,scan.issues.map {it.diagnostic})
    }

    @Test fun csvRowsCarryRowNumbersAndColumnProblems() {
        val csv="timestamp,latitude,longitude\n2026-10-26T13:00:00Z,18,-66\n2026-10-26T13:01:00Z,18\n2026-10-26T13:02:00Z,95,-66\n"
        val scan=HistoryImporter.scan(csv.reader(),ExportFormat.csv)
        assertEquals(1,scan.points)
        assertEquals(listOf(HistoryImportRecordKind.row to HistoryImportProblem.columnCount,HistoryImportRecordKind.row to HistoryImportProblem.latitude),
            scan.issues.map {it.record to it.problem})
    }

    @Test fun aFileWithNothingImportableReportsItsFirstIssueOrNone() {
        val visit="""{"latitude":18,"longitude":-66,"arrivedAt":"2026-10-26T13:00:00Z","departedAt":"2026-10-26T12:00:00Z"}"""
        val failure=assertThrows(NoImportableHistory::class.java) {HistoryImporter.scan("""{"visits":[$visit]}""".reader(),ExportFormat.json)}
        assertEquals(HistoryImportProblem.departureBeforeArrival,failure.issue?.problem)
        assertEquals("Visit 1: Departure is before arrival",failure.message)
        // Still an IllegalArgumentException for callers that only know the legacy contract.
        assertTrue(failure is IllegalArgumentException)
        val empty=assertThrows(NoImportableHistory::class.java) {HistoryImporter.scan("""{"visits":[]}""".reader(),ExportFormat.json)}
        assertNull(empty.issue)
    }

    @Test fun diagnosticsStayBoundedAndAlignedWithIssues() {
        val invalid="""{"latitude":95,"longitude":-66,"timestamp":"2026-10-26T13:00:00Z"}"""
        val scan=HistoryImporter.scan("""{"points":[${List(25) {invalid}.joinToString(",")},$validPoint]}""".reader(),ExportFormat.json)
        assertEquals(25,scan.rejected);assertEquals(10,scan.issues.size);assertEquals(scan.errors,scan.issues.map {it.diagnostic})
    }
}
