package com.bontecou.whereday.export

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.StringWriter
import java.nio.file.Files

class ScheduledExportMergerTest {
    @Test fun matchesActualSwiftGeneratedMergeResults() {
        val fixture=requireNotNull(javaClass.getResourceAsStream("/ios-scheduled-merge.json")).bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        val cases=fixture["cases"].asJsonArray
        assertEquals(22,cases.size())
        for(value in cases) {
            val c=value.asJsonObject;val format=ExportFormat.valueOf(c["format"].asString)
            if(c.has("error")) {
                try {merge(format,c["existing"].asString,c["incoming"].asString);fail(c["name"].asString)} catch(_:Exception) {}
            } else {
                val actual=merge(format,c["existing"].asString,c["incoming"].asString)
                val expected=c["expected"].asString
                if(format in setOf(ExportFormat.csv,ExportFormat.markdown)) assertEquals(c["name"].asString,expected,actual)
                else assertEquals(c["name"].asString,JsonParser.parseString(expected),JsonParser.parseString(actual))
            }
        }
    }
    private fun merge(format:ExportFormat,old:String,fresh:String):String=runBlocking {
        val root=Files.createTempDirectory("scheduled-merge").toFile()
        try {
            val existing=File(root,"old").apply {writeText(old)};val incoming=File(root,"new").apply {writeText(fresh)}
            val identities=mutableSetOf<Pair<String,String>>();val out=StringWriter()
            ScheduledExportMerger.merge(format,existing,incoming,out,MergeIdentityStore {ns,id ->identities.add(ns to id)})
            out.toString()
        } finally {root.deleteRecursively()}
    }
    @Test fun jsonKeepsFirstIdentityRefreshesScalarsAndDoesNotConfuseArrays() {
        val old="""{"exportDate":"old","visits":[{"id":"same","name":"first"}],"points":[{"id":"same"}],"removed":true}"""
        val fresh="""{"exportDate":"new","visits":[{"id":"same","name":"updated"},{"id":"later"}],"points":[{"id":"same"},{"id":"next"}]}"""
        val result=JsonParser.parseString(merge(ExportFormat.json,old,fresh)).asJsonObject
        assertEquals("new",result["exportDate"].asString);assertFalse(result.has("removed"))
        assertEquals(2,result["visits"].asJsonArray.size());assertEquals("first",result["visits"].asJsonArray[0].asJsonObject["name"].asString)
        assertEquals(2,result["points"].asJsonArray.size())
        val array=JsonParser.parseString(merge(ExportFormat.owntracks,"""[{"tst":1},7,{"unknown":true}]""","""[{"tst":1},{"tst":2},7]""")).asJsonArray
        assertEquals(5,array.size())
    }
    @Test fun csvUsesIOSHeaderAndWhitespaceRulesWithoutChangingNoopExistingBytes() {
        assertEquals("a,b\n1,2\n3,4\n",merge(ExportFormat.csv,"  a,b\n1,2\n\n ","a,b\n 3,4 \n\n"))
        val original=" a,b\r\n1,2\r\n\n"
        assertEquals(original,merge(ExportFormat.csv,original,"a,b\n\n"))
        assertEquals("a,b\n3,4\n",merge(ExportFormat.csv," \n\t","a,b\n3,4\n"))
    }
    @Test fun markdownReplacesMatchingSectionsAndPreservesUnmanagedNotesAndFrontmatter() {
        val old="---\nowner: me\nstatus: old\n---\nOld preamble\n## Visits\nold visit\n## My notes\nkeep me\n"
        val fresh="---\nstatus: new\n---\nNew preamble\n## Visits\nnew visit\n## Location Points\npoint\n"
        val result=merge(ExportFormat.markdown,old,fresh)
        assertTrue(result.startsWith("---\nowner: me\nstatus: new\n---\nNew preamble\n"))
        assertFalse(result.contains("old visit"));assertTrue(result.contains("## Visits\nnew visit\n"))
        assertTrue(result.contains("## My notes\nkeep me\n"));assertTrue(result.contains("## Location Points\npoint\n"))
    }
    @Test fun malformedOrIncompatibleJSONFailsInsteadOfReturningPartialSuccess() {
        for(old in listOf("{", "[]", "{\"points\":[]} false", "{\"points\":[],\"points\":[]}")) {
            try {merge(ExportFormat.json,old,"""{"points":[]}""");fail(old)} catch(_:Exception) {}
        }
        for(format in listOf(ExportFormat.gpx,ExportFormat.kml)) {
            try {merge(format,"old","fresh");fail()} catch(_:IllegalStateException) {}
        }
    }
    @Test fun streamsLargeCSVWithoutRetainingWholePayloadAndHonorsCancellation()=runBlocking {
        val root=Files.createTempDirectory("scheduled-large").toFile()
        try {
            val old=File(root,"old");val fresh=File(root,"new")
            old.bufferedWriter().use {out ->out.append("id,value\n");repeat(100_000) {out.append("$it,value\n")}}
            fresh.writeText("id,value\n100000,next\n")
            var chars=0L
            val sink=object:java.io.Writer() {
                override fun write(c:CharArray,o:Int,n:Int) {chars+=n}
                override fun close() {};override fun flush() {}
            }
            ScheduledExportMerger.merge(ExportFormat.csv,old,fresh,sink,MergeIdentityStore {_,_->error("CSV has no identity set")})
            assertEquals(old.length()+"100000,next\n".length,chars)
            val job=async {currentCoroutineContext().cancel();ScheduledExportMerger.merge(ExportFormat.csv,old,fresh,sink,MergeIdentityStore {_,_->true})}
            try {job.await();fail()} catch(_:CancellationException) {}
        } finally {root.deleteRecursively()}
    }
}
