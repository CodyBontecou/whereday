package com.bontecou.whereday.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PhotoSyncSpoolTest {
    private lateinit var directory:File
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        directory=File(context.cacheDir,"photo-spool-${java.util.UUID.randomUUID()}")
    }
    @After fun cleanup() {directory.deleteRecursively()}
    @Test fun boundedBatchesRetainNullsUnicodeCoordinatesAndNanoseconds()=runBlocking {
        val now=Instant.parse("2026-10-27T14:00:00.123456789Z")
        val batches=mutableListOf<Int>();var index=0
        PhotoSyncSpool.create(directory).use {spool->
            repeat(1201) {i->spool.append(PhotoAssetMetadata("content://synthetic/画像-$i",if(i==0) null else now.plusSeconds(i.toLong()),if(i%2==0) Coordinate(48.8584,2.2945) else null,if(i%3==0) "provider:画像-$i" else null))}
            assertEquals(1201,spool.count);assertEquals(now.plusSeconds(1),spool.first);assertEquals(now.plusSeconds(1200),spool.last)
            spool.seal()
            spool.batches {batch->batches+=batch.size;batch.forEach {photo->
                assertEquals("content://synthetic/画像-$index",photo.uri)
                assertEquals(if(index==0) null else now.plusSeconds(index.toLong()),photo.takenAt)
                assertEquals(if(index%2==0) Coordinate(48.8584,2.2945) else null,photo.gps);assertEquals(if(index%3==0) "provider:画像-$index" else null,photo.identity);index++
            }}
        }
        assertEquals(listOf(500,500,201),batches);assertEquals(1201,index);assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
    @Test fun orphanCleanupPreservesConcurrentLiveSpoolsAndUnrelatedFiles() {
        PhotoSyncSpool.create(directory).use {first->
            first.append(PhotoAssetMetadata("content://synthetic/first",null))
            val orphan=File(directory,"photo-orphan.stage").apply {writeText("Synthetic abandoned spool")}
            val other=File(directory,"keep.txt").apply {writeText("Unrelated fixture")}
            PhotoSyncSpool.create(directory).use {second->
                second.append(PhotoAssetMetadata("content://synthetic/second",null))
                assertFalse(orphan.exists());assertTrue(other.exists())
                assertEquals(2,directory.listFiles()!!.count {it.extension=="stage"})
            }
            assertEquals(1,directory.listFiles()!!.count {it.extension=="stage"})
        }
        assertEquals(listOf("keep.txt"),directory.listFiles()!!.map {it.name})
    }
    @Test fun corruptionIsRejectedBeforeCorrelationAndFailureStillCleansUp()=runBlocking {
        PhotoSyncSpool.create(directory).use {spool->
            spool.append(PhotoAssetMetadata("content://synthetic/corrupt",null));spool.seal()
            DataOutputStream(FileOutputStream(directory.listFiles()!!.single())).use {it.writeInt(Int.MAX_VALUE)}
            try {spool.batches {fail("Corrupt metadata must not reach correlation")};fail("Expected invalid length")} catch(_:IllegalStateException) {}
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
}
