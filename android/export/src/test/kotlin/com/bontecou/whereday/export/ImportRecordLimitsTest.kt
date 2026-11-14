package com.bontecou.whereday.export

import org.junit.Assert.*
import org.junit.Test
import java.io.Reader

class ImportRecordLimitsTest {
    private class Fragments(parts:Sequence<String>):Reader() {
        private val parts=parts.iterator();private var current="";private var offset=0
        var delivered=0L;private set
        override fun read(buffer:CharArray,start:Int,length:Int):Int {
            if(length==0) return 0
            while(offset==current.length) {if(!parts.hasNext()) return -1;current=parts.next();offset=0}
            val size=minOf(length,current.length-offset);current.toCharArray(buffer,start,offset,offset+size);offset+=size;delivered+=size;return size
        }
        override fun close() {}
    }
    @Test fun aHugeKnownJsonScalarFailsBeforeItsCompleteValueIsRead() {
        val reader=Fragments(sequence {
            yield("{\"visits\":[{\"latitude\":18,\"longitude\":-66,\"arrivedAt\":\"2026-10-26T13:00:00Z\",\"notes\":\"")
            repeat(2048) {yield("x".repeat(8192))};yield("\"}]}")
        })
        var emitted=0
        assertThrows(ImportRecordTooLarge::class.java) {HistoryImporter.scan(reader,ExportFormat.json,onVisit={emitted++})}
        assertEquals(0,emitted);assertTrue(reader.delivered<2L*1024*1024)
    }
    @Test fun theSameLargeUnknownJsonValueStreamsWithoutARecordAllocation() {
        val reader=Fragments(sequence {
            yield("{\"future\":\"");repeat(2048) {yield("x".repeat(8192))}
            yield("\",\"points\":[{\"latitude\":18,\"longitude\":-66,\"timestamp\":\"2026-10-26T13:00:00Z\"}]}")
        })
        assertEquals(1,HistoryImporter.scan(reader,ExportFormat.json).points)
        assertTrue(reader.delivered>16L*1024*1024)
    }
    @Test fun jsonRecordTextBudgetIncludesMultipleFieldsAndAllowsTheBoundary() {
        val prefix="""{"visits":[{"latitude":18,"longitude":-66,"arrivedAt":"2026-10-26T13:00:00Z","notes":"""+"\""
        val maximum=ImportRecordLimits.textCharacters-56
        val accepted=prefix+"x".repeat(maximum)+"\"}]}"
        assertEquals(maximum,HistoryImporter.parse(accepted,ExportFormat.json).visits.single().notes!!.length)
        assertThrows(ImportRecordTooLarge::class.java) {HistoryImporter.scan((prefix+"x".repeat(maximum+1)+"\"}]}").reader(),ExportFormat.json)}
        val split="""{"visits":[{"latitude":18,"longitude":-66,"arrivedAt":"2026-10-26T13:00:00Z","locationName":"${"a".repeat(150_000)}","notes":"${"b".repeat(150_000)}"}]}"""
        assertThrows(ImportRecordTooLarge::class.java) {HistoryImporter.scan(split.reader(),ExportFormat.json)}
    }
    @Test fun csvBudgetSpansFieldsAndQuotedLinesAndBoundsColumnCounts() {
        val header="arrived_at,latitude,longitude,notes\n"
        val accepted=header+"2026-10-26T13:00:00Z,18,-66,"+"x".repeat(ImportRecordLimits.textCharacters-25)+"\n"
        assertEquals(1,HistoryImporter.scan(accepted.reader(),ExportFormat.csv).visits)
        assertThrows(ImportRecordTooLarge::class.java) {HistoryImporter.scan((accepted.dropLast(1)+"x\n").reader(),ExportFormat.csv)}
        val multiline=header+"2026-10-26T13:00:00Z,18,-66,\""+("line\n".repeat(60_000))+"\"\n"
        assertThrows(ImportRecordTooLarge::class.java) {HistoryImporter.scan(multiline.reader(),ExportFormat.csv)}
        assertEquals(256,CsvReader.read(List(256) {""}.joinToString(",")+"\n").single().size)
        assertThrows(ImportRecordTooLarge::class.java) {CsvReader.read(List(257) {""}.joinToString(",")+"\n")}
    }
    @Test fun markdownTablesAndMultiLineLegacySectionsCannotGrowWithoutBounds() {
        val row="| 2026-10-26T13:00:00Z | | 18 | -66 | "+"x".repeat(ImportRecordLimits.textCharacters)+" |\n"
        assertThrows(ImportRecordTooLarge::class.java) {HistoryImporter.scan(("| Arrived | Departed | Lat | Lon | Notes |\n"+row).reader(),ExportFormat.markdown)}
        val legacy="### Cafe\n- **Arrived:** 2026-10-26T13:00:00Z\n- **Coordinates:** 18, -66\n"+("> line\n".repeat(60_000))
        assertThrows(ImportRecordTooLarge::class.java) {HistoryImporter.scan(legacy.reader(),ExportFormat.markdown)}
        assertEquals(256,MarkdownHistoryReader.cells("|"+List(256) {"x"}.joinToString("|")+"|").size)
        assertThrows(ImportRecordTooLarge::class.java) {MarkdownHistoryReader.cells("|"+List(257) {"x"}.joinToString("|")+"|")}
    }
    @Test fun lineOrKeyDetectionCannotAllocateTheEntireOversizedInput() {
        val line=Fragments(sequence {repeat(2048) {yield("x".repeat(8192))}})
        assertThrows(ImportRecordTooLarge::class.java) {HistoryImporter.detect(line)}
        assertTrue(line.delivered<2L*1024*1024)
        val key=Fragments(sequence {yield("{\"");repeat(100) {yield("x".repeat(8192))};yield("\":0}")})
        assertThrows(ImportRecordTooLarge::class.java) {HistoryImporter.scan(key,ExportFormat.json)}
        assertTrue(key.delivered<64*1024)
    }
}
