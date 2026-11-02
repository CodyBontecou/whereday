package com.bontecou.whereday.platform

import com.bontecou.whereday.export.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.time.*
import java.util.Locale

class ImportScratchFailureTest {
    private class Target:StagedHistoryImportTarget {
        var inspected=0;var applied=0
        override suspend fun inspect(stage:HistoryImportStage,check:()->Unit):Int {inspected++;return 0}
        override suspend fun apply(stage:HistoryImportStage,policy:HistoryImportPolicy,check:()->Unit):HistoryImportCounts {applied++;return HistoryImportCounts()}
        override suspend fun inspect(records:ImportResult,check:()->Unit)=error("Staged path required")
        override suspend fun apply(records:ImportResult,baseline:HistoryImportBaseline,policy:HistoryImportPolicy,check:()->Unit)=error("Staged path required")
    }
    private val source="""{"points":[{"latitude":18,"longitude":-66,"timestamp":"2026-10-26T13:00:00Z"}]}"""
    @Test fun scratchWriteOrCloseFailureClosesInputAndCannotReachInspectionOrCommit():Unit=runBlocking {
        for(failAtClose in listOf(false,true)) {
            val root=kotlin.io.path.createTempDirectory("import-failed-output-").toFile();var released=false;var closed=false;var sinkClosed=false
            val factory=HistoryImportWorkspaceFactory {
                HistoryImportWorkspace(root,MemoryImportStage(),sourceOutput={file->object:FileOutputStream(file) {
                    override fun write(bytes:ByteArray,offset:Int,length:Int) {
                        if(!failAtClose) throw IOException("Synthetic ENOSPC")
                        super.write(bytes,offset,length)
                    }
                    override fun close() {sinkClosed=true;super.close();if(failAtClose) throw IOException("Synthetic close failure")}
                }}) {released=true}
            }
            val target=Target();val c=HistoryImportController(target,factory)
            try {
                c.select(object:HistoryImportInput {override fun open()=object:ByteArrayInputStream(source.toByteArray()) {override fun close() {closed=true;super.close()}}})!!.join()
                assertEquals(HistoryImportPhase.failed,c.state.value.phase)
                assertTrue(c.state.value.error!!.contains("storage"));assertTrue(closed);assertTrue(sinkClosed)
                assertEquals(0,target.inspected);assertEquals(0,target.applied)
                c.clearAll {};assertTrue(released);assertFalse(root.exists())
            } finally {c.close();root.deleteRecursively()}
        }
    }
    @Test fun malformedUtf8IsRejectedDuringCaptureWithoutReadingTheWholeSource():Unit=runBlocking {
        var delivered=0;var closed=false;val target=Target();val c=HistoryImportController(target,memoryImportWorkspaces())
        try {
            c.select(object:HistoryImportInput {override fun open()=object:InputStream() {
                override fun read():Int=error("Bulk reads expected")
                override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                    if(delivered>=10*1024*1024) return -1
                    bytes.fill(120,offset,offset+length);if(delivered==0) bytes[offset]=0xff.toByte();delivered+=length;return length
                }
                override fun close() {closed=true}
            }})!!.join()
            assertEquals(HistoryImportPhase.failed,c.state.value.phase);assertEquals("Choose a UTF-8 history file.",c.state.value.error)
            assertTrue(closed);assertTrue(delivered<=8192);assertEquals(0,target.inspected)
            c.clearAll {}
        } finally {c.close()}
    }
    @Test fun byteChunkBoundariesPreserveExactValidUtf8AndLineEndings() {
        val root=kotlin.io.path.createTempDirectory("import-utf8-chunks-").toFile()
        HistoryImportWorkspace(root,MemoryImportStage()) {}.use {workspace->
            val bytes="\uFEFF Café 北 🗺️\r\nOne\nTwo".toByteArray(Charsets.UTF_8)
            val input=object:ByteArrayInputStream(bytes) {override fun read(out:ByteArray,offset:Int,length:Int)=super.read(out,offset,minOf(1,length))}
            input.use {workspace.capture(it) {}}
            assertArrayEquals(bytes,File(root,"source").readBytes())
        }
        assertFalse(root.exists())
    }
}
