package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.*
import java.util.Locale

class LegacyMarkdownPersistenceTest {
    private val text="""
        ## Monday, October 26, 2026
        ### Cafe \\ | North
        - **Arrived:** 9:00 AM
        - **Departed:** 10:00 AM
        - **Coordinates:** 18, -66
        > Synthetic note
        ## Monday, October 26, 2026
        | Time | Lat | Lon | Speed | Altitude | Accuracy | Outlier |
        | --- | --- | --- | --- | --- | --- | --- |
        | 9:00:15 AM | 18 | -66 | - | - | 5 m | No |
    """.trimIndent()
    @Test fun appDetectionImportTransactionAndReimportPreserveLegacyIdentityAndText():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val db=HistoryDatabase.create(context,null)
        try {
            val input=" \uFEFF "+text
            val format=HistoryImporter.detect(input);assertEquals(ExportFormat.markdown,format)
            val parsed=HistoryImporter.parse(input,format,Locale.US,ZoneId.of("America/Puerto_Rico"))
            assertTrue(parsed.errors.toString(),parsed.errors.isEmpty())
            repeat(2) {db.writeTransaction {
                parsed.visits.forEach {db.history().put(VisitRow(it))};parsed.points.forEach {db.history().put(PointRow(it,null))}
            }}
            assertEquals(1L,db.history().totalVisitCount());assertEquals(1L,db.history().totalPointCount())
            val visit=db.history().visit(parsed.visits.single().id)!!.value
            assertEquals("""Cafe \\ | North""",visit.name);assertEquals(Instant.parse("2026-10-26T13:00:00Z"),visit.arrivedAt)
            val output=StringWriter()
            StreamingHistoryExporter.write(output,ExportFormat.markdown,object:ExportRecordSource {
                override suspend fun visits(consume:suspend(Visit)->Unit) {db.history().exportFirstVisits(Instant.EPOCH,Instant.parse("2100-01-01T00:00:00Z")).forEach {consume(it.value)}}
                override suspend fun points(consume:suspend(TrackPoint)->Unit) {db.history().exportFirstPoints(Instant.EPOCH,Instant.parse("2100-01-01T00:00:00Z"),null).forEach {consume(it.value)}}
            },ExportOptions(zone=ZoneId.of("America/Puerto_Rico")))
            val restored=HistoryImporter.parse(output.toString(),ExportFormat.markdown,Locale.US,ZoneId.of("America/Puerto_Rico"))
            assertTrue(restored.errors.toString(),restored.errors.isEmpty())
            assertEquals(visit,restored.visits.single());assertEquals(parsed.points,restored.points)
        } finally {db.close()}
    }
    @Test fun androidLocaleTablesAndDstErrorsMatchJvmPolicy():Unit=runBlocking {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.platform.test")
        val french="## lundi 26 octobre 2026\n| 21:15:30 | 48.85 | 2.35 |"
        val result=HistoryImporter.parse(french,HistoryImporter.detect(french),Locale.FRANCE,ZoneId.of("Europe/Paris"))
        assertEquals(Instant.parse("2026-10-26T20:15:30Z"),result.points.single().time)
        val gap="## Sunday, March 8, 2026\n| 2:30:00 AM | 40 | -74 |\n| 3:30:00 AM | 40 | -74 |"
        val imported=HistoryImporter.parse(gap,ExportFormat.markdown,Locale.US,ZoneId.of("America/New_York"))
        assertEquals(1,imported.errors.size);assertEquals(Instant.parse("2026-03-08T07:30:00Z"),imported.points.single().time)
    }
}
