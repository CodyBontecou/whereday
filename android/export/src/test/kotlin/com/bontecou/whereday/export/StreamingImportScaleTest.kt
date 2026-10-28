package com.bontecou.whereday.export

import org.junit.Assert.*
import org.junit.Test
import java.io.Reader
import java.time.Instant

/** Run in its own 96 MiB JVM. Source and sink retain no history collection or complete document. */
class StreamingImportScaleTest {
    private class Fragments(parts:Sequence<String>):Reader() {
        private val parts=parts.iterator();private var current="";private var offset=0
        var characters=0L;private set
        override fun read(buffer:CharArray,start:Int,length:Int):Int {
            if(length==0) return 0
            while(offset==current.length) {
                if(!parts.hasNext()) return -1
                current=parts.next();offset=0
            }
            val size=minOf(length,current.length-offset)
            current.toCharArray(buffer,start,offset,offset+size);offset+=size;characters+=size;return size
        }
        override fun close() {}
    }
    @Test fun millionRecordsPerFormatStreamThroughA96MiBHeap() {
        assertTrue("Run with streamingImportBudgetTest",Runtime.getRuntime().maxMemory()<=100L*1024*1024)
        val count=1_000_000
        for(format in listOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown)) {
            val parts=sequence {
                yield(when(format) {ExportFormat.json->"{\"points\":[";ExportFormat.csv->"timestamp,latitude,longitude\n";else->"| Time | Lat | Lon |\n| --- | --- | --- |\n"})
                repeat(count) {i->
                    val time=Instant.EPOCH.plusSeconds(i.toLong())
                    yield(when(format) {
                        ExportFormat.json->(if(i==0) "" else ",")+"{\"timestamp\":\"$time\",\"latitude\":18,\"longitude\":-66}"
                        ExportFormat.csv->"$time,18,-66\n"
                        else->"| $time | 18 | -66 |\n"
                    })
                }
                if(format==ExportFormat.json) yield("]}")
            }
            var consumed=0
            Fragments(parts).use {reader->
                val result=HistoryImporter.scan(reader,format,onPoint={point->
                    assertEquals(Instant.EPOCH.plusSeconds(consumed.toLong()),point.time)
                    if(consumed==0) assertTrue("First record must emit before EOF",reader.characters<64*1024)
                    consumed++
                })
                assertEquals(count,consumed);assertEquals(count,result.points);assertEquals(0,result.rejected)
                assertTrue("Exercise files larger than 25 MB",reader.characters>25L*1024*1024)
                println("$format: ${result.points} records, ${reader.characters} characters, heap limit ${Runtime.getRuntime().maxMemory()}")
            }
        }
    }
}
