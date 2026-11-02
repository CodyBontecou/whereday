package com.bontecou.whereday.export

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.Reader
import java.time.*
import java.util.Locale
import java.util.concurrent.CancellationException

class StreamingImportTest {
    private val point="""{"latitude":18,"longitude":-66,"timestamp":"2026-10-26T13:00:00Z"}"""
    private fun chunked(text:String,size:Int)=object:Reader() {
        var offset=0
        override fun read(buffer:CharArray,start:Int,length:Int):Int {
            if(offset==text.length) return -1
            val n=minOf(size,length,text.length-offset);text.toCharArray(buffer,start,offset,offset+n);offset+=n;return n
        }
        override fun close() {}
    }
    @Test fun everySwiftFixtureRetainsResultsAcrossTinyReaderBoundaries() {
        val fixtures=requireNotNull(javaClass.getResourceAsStream("/ios-history-import.json")).bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
        for(value in fixtures) {
            val fixture=value.asJsonObject;val text=fixture["text"].asString;val format=ExportFormat.valueOf(fixture["format"].asString)
            for(chunk in listOf(1,7,8192)) {
                assertEquals(fixture["name"].asString,format,HistoryImporter.detect(chunked(text,chunk)))
                if(fixture.has("expectedError")) {
                    assertThrows(IllegalArgumentException::class.java) {HistoryImporter.scan(chunked(text,chunk),format,Locale.US,ZoneId.of("America/Puerto_Rico"))}
                } else {
                    val expected=HistoryImporter.parse(text,format,Locale.US,ZoneId.of("America/Puerto_Rico"))
                    val visits=mutableListOf<com.bontecou.whereday.domain.Visit>();val points=mutableListOf<com.bontecou.whereday.domain.TrackPoint>()
                    val scan=HistoryImporter.scan(chunked(text,chunk),format,Locale.US,ZoneId.of("America/Puerto_Rico"),onVisit=visits::add,onPoint=points::add)
                    assertEquals(fixture["name"].asString,expected,ImportResult(visits,points,scan.errors,scan.rejected))
                }
            }
        }
    }
    @Test fun malformedRecordsHaveExactCountsAndOnlyTenBoundedDiagnostics() {
        val invalid="""{"latitude":"${"x".repeat(400)}","longitude":-66,"timestamp":"bad"}"""
        val text="""{"points":["""+List(2000) {invalid}.joinToString(",")+",$point]}"
        val scan=HistoryImporter.scan(text.reader(),ExportFormat.json)
        assertEquals(1,scan.points);assertEquals(2000,scan.rejected);assertEquals(10,scan.errors.size)
        assertTrue(scan.errors.all {it.length<=200})
        assertEquals(2000,HistoryImporter.parse(text,ExportFormat.json).rejected)
    }
    @Test fun sinkFailuresNeverBecomeMalformedSourceRowsInAnyFormat() {
        val records=listOf(com.bontecou.whereday.domain.TrackPoint(coordinate=com.bontecou.whereday.domain.Coordinate(18.0,-66.0),time=Instant.EPOCH,accuracy=5.0))
        for(format in listOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown)) {
            val text=HistoryExporter.render(format,emptyList(),records)
            val failure=IllegalArgumentException("Synthetic disk staging failure")
            val actual=assertThrows(IllegalArgumentException::class.java) {
                HistoryImporter.scan(text.reader(),format,onPoint={throw failure})
            }
            assertSame(format.name,failure,actual)
            val visit=com.bontecou.whereday.domain.Visit(coordinate=com.bontecou.whereday.domain.Coordinate(18.0,-66.0),arrivedAt=Instant.EPOCH)
            val visitText=HistoryExporter.render(format,listOf(visit),emptyList())
            assertSame(failure,assertThrows(IllegalArgumentException::class.java) {HistoryImporter.scan(visitText.reader(),format,onVisit={throw failure})})
            val canceled=CancellationException("Synthetic cancellation")
            assertSame(canceled,assertThrows(CancellationException::class.java) {HistoryImporter.scan(text.reader(),format,onPoint={throw canceled})})
        }
        val legacy="### Cafe\n- **Arrived:** 2026-10-26T13:00:00Z\n- **Coordinates:** 18, -66\n"
        val failure=IllegalStateException("Synthetic legacy staging failure")
        assertSame(failure,assertThrows(IllegalStateException::class.java) {HistoryImporter.scan(legacy.reader(),ExportFormat.markdown,onVisit={throw failure})})
    }
    @Test fun malformedTailRejectsTheWholeScanAfterProvisionalEmissions() {
        for(text in listOf("""{"points":[$point]""","""{"points":[$point]} garbage""","""{"points":[$point],"points":[]}""")) {
            var emitted=0
            assertThrows(Exception::class.java) {HistoryImporter.scan(text.reader(),ExportFormat.json,onPoint={emitted++})}
            assertEquals(1,emitted) // A staging sink must discard these on failed EOF validation.
        }
    }
    @Test fun unknownLargeMetadataIsSkippedAndCancellationReachesSkippedContent() {
        val text="""{"future":{"payload":"${"x".repeat(100_000)}"},"points":[$point]}"""
        assertEquals(1,HistoryImporter.scan(chunked(text,7),ExportFormat.json).points)
        var checks=0
        assertThrows(CancellationException::class.java) {HistoryImporter.scan(chunked(text,7),ExportFormat.json,checkpoint={if(++checks==30) throw CancellationException()})}
        assertEquals(30,checks)
    }
    @Test fun duplicateKnownJsonFieldsAndCsvColumnsAreRejectedWithoutDroppingValidNeighbors() {
        val json="""{"points":[{"latitude":18,"latitude":19,"longitude":-66,"timestamp":"2026-10-26T13:00:00Z"},$point]}"""
        val scan=HistoryImporter.scan(json.reader(),ExportFormat.json)
        assertEquals(1,scan.points);assertEquals(1,scan.rejected)
        val csv="timestamp,latitude,latitude,longitude\n2026-10-26T13:00:00Z,18,19,-66\ntimestamp,latitude,longitude\n2026-10-26T13:00:00Z,18,-66\n"
        val result=HistoryImporter.scan(csv.reader(),ExportFormat.csv)
        assertEquals(1,result.points);assertEquals(1,result.rejected)
    }
    @Test fun scanDoesNotCloseTheCallersReaderOnSuccessOrFailure() {
        for(text in listOf("""{"points":[$point]}""","""{"points":[$point]""")) {
            var closed=false
            val reader=object:java.io.StringReader(text) {override fun close() {closed=true;super.close()}}
            runCatching {HistoryImporter.scan(reader,ExportFormat.json)}
            assertFalse(closed);reader.close();assertTrue(closed)
        }
    }
}
