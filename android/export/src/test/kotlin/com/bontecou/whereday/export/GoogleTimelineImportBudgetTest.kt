package com.bontecou.whereday.export

import org.junit.Test
import org.junit.Assert.*
import java.io.Reader
import java.io.File
import java.time.Instant

class GoogleTimelineImportBudgetTest {
    private class Chunks(private val chunks:Iterator<String>):Reader() {
        private var current="";private var offset=0
        override fun read(out:CharArray,start:Int,length:Int):Int {
            if(length==0) return 0
            while(offset==current.length) {if(!chunks.hasNext()) return -1;current=chunks.next();offset=0}
            val n=minOf(length,current.length-offset);current.toCharArray(out,start,offset,offset+n);offset+=n;return n
        }
        override fun close() {}
    }
    @Test fun maximumSizeSingleSegmentStreamsUnderNinetySixMiBAndDropsItsScratch() {
        val root=kotlin.io.path.createTempDirectory("google-budget-").toFile()
        val start=Instant.parse("2020-01-01T00:00:00Z");var count=0
        fun open()=Chunks(sequence {
            yield("[{\"timelinePath\":[")
            repeat(250_000) {index->if(index>0) yield(",");yield("{\"point\":\"geo:18,-66\",\"durationMinutesOffsetFromStartTime\":$index}")}
            yield("],\"startTime\":\"2020-01-01T00:00:00Z\",\"endTime\":\"2020-12-31T00:00:00Z\"}]")
        }.iterator())
        try {
            val result=HistoryImporter.scanSource(::open,ExportFormat.json,onPoint={assertEquals(start.plusSeconds(count.toLong()*60),it.time);count++},scratch={File.createTempFile("row-",".json",root)})
            assertEquals(250_000,count);assertEquals(250_000,result.points);assertEquals(0,result.rejected);assertTrue(root.listFiles()!!.isEmpty())
        } finally {root.deleteRecursively()}
    }
    @Test fun googleByteAndRowCapsFailBeforeAnyReviewCanBeAccepted() {
        fun oversized()=Chunks(sequence {
            yield("{\"locations\":[{\"latLng\":\"geo:18,-66\",\"timestampMs\":0}],\"unused\":\"")
            repeat(51*1024) {yield("x".repeat(1024))};yield("\"}")
        }.iterator())
        assertTrue(runCatching {HistoryImporter.scanSource(::oversized,ExportFormat.json)}.exceptionOrNull() is IllegalArgumentException)
        fun tooMany()=Chunks(sequence {yield("{\"locations\":[");repeat(250_001) {if(it>0) yield(",");yield("null")};yield("]}")}.iterator())
        assertTrue(runCatching {HistoryImporter.scanSource(::tooMany,ExportFormat.json)}.exceptionOrNull() is IllegalArgumentException)
    }
}
