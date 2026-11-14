package com.bontecou.whereday.platform

import android.Manifest
import android.content.ContentResolver
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoLibraryAccessTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private var access=PhotoLibraryAccess.Full
    private fun library(sdk:Int=37)=AndroidPhotoLibrary(context.contentResolver,Uri.parse("content://${context.packageName}.library-test/images"),sdk,{access})
    @Before fun setup() {
        check(context.packageName!="com.bontecou.whereday")
        PhotoLibraryTestProvider.reset()
    }
    @After fun cleanup() {PhotoLibraryTestProvider.ids=emptyList();PhotoLibraryTestProvider.onQuery={}}
    @Test fun cursorDisposalIsIdempotentAndCannotChargeANewerProbe() {
        val uri=Uri.parse("content://${context.packageName}.library-test/images")
        val args=android.os.Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,arrayOf("-1"))
            putInt(ContentResolver.QUERY_ARG_LIMIT,500)
        }
        PhotoLibraryTestProvider.ids=listOf(1)
        val old=context.contentResolver.query(uri,null,args,null)!!
        PhotoLibraryTestProvider.reset();old.close();old.close()
        assertEquals(0,PhotoLibraryTestProvider.closed);assertEquals(0,PhotoLibraryTestProvider.queryCount)
        PhotoLibraryTestProvider.ids=listOf(2)
        val current=context.contentResolver.query(uri,null,args,null)!!
        current.close();current.close()
        assertEquals(1,PhotoLibraryTestProvider.closed);assertEquals(1,PhotoLibraryTestProvider.queryCount)
    }
    @Test fun accessMatrixAndRequestsRespectSdkWithoutTreatingPickerGrantsAsFullAccess() {
        val image=Manifest.permission.READ_MEDIA_IMAGES;val selected=Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED;val old=Manifest.permission.READ_EXTERNAL_STORAGE
        for(sdk in listOf(29,32,33,34,37)) {
            assertEquals(PhotoLibraryAccess.None,AndroidPhotoAccess.resolve(sdk) {false})
            assertEquals(if(sdk<=32) PhotoLibraryAccess.Full else PhotoLibraryAccess.None,AndroidPhotoAccess.resolve(sdk) {it==old})
            assertEquals(if(sdk>=33) PhotoLibraryAccess.Full else PhotoLibraryAccess.None,AndroidPhotoAccess.resolve(sdk) {it==image})
            assertEquals(if(sdk>=34) PhotoLibraryAccess.Partial else PhotoLibraryAccess.None,AndroidPhotoAccess.resolve(sdk) {it==selected})
            assertFalse(AndroidPhotoAccess.permissions(sdk).contains(Manifest.permission.READ_MEDIA_VIDEO))
        }
        assertEquals(PhotoLibraryAccess.Full,AndroidPhotoAccess.resolve(37) {it==image || it==selected})
        assertArrayEquals(arrayOf(image,selected),AndroidPhotoAccess.permissions(34))
    }
    @Test fun fullAndPartialEnumerateAllAllowedIdsInBoundedKeysetPagesEvenIfLimitIsIgnored()=runBlocking {
        PhotoLibraryTestProvider.ids=(0L..1200L).toList();PhotoLibraryTestProvider.ignoreLimit=true
        val result=mutableListOf<String>();library().references {result+=it}
        assertEquals(1201,result.size);assertEquals(1201,result.distinct().size)
        assertEquals("Exactly three page queries",3,PhotoLibraryTestProvider.queryCount)
        assertEquals("Every cursor from this probe must be disposed once",3,PhotoLibraryTestProvider.closed)
        assertEquals(listOf("-1","499","999"),PhotoLibraryTestProvider.arguments.map {it.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)!!.single()})
        assertTrue(PhotoLibraryTestProvider.arguments.all {it.getInt(ContentResolver.QUERY_ARG_LIMIT)==500 && it.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)!!.contains("is_trashed=0")})
        access=PhotoLibraryAccess.Partial;PhotoLibraryTestProvider.ids=listOf(7,800,2500)
        val partial=mutableListOf<String>();library(29).references {partial+=it}
        assertEquals(listOf("7","800","2500"),partial.map {it.substringAfterLast('/')})
        assertFalse(PhotoLibraryTestProvider.arguments.last().getString(ContentResolver.QUERY_ARG_SQL_SELECTION)!!.contains("is_trashed"))
    }
    @Test fun noAccessNeverQueriesAndChangedAccessAbortsBeforeTheNextPage()=runBlocking {
        access=PhotoLibraryAccess.None
        try {library().references {fail()};fail()} catch(_:SecurityException) {}
        assertEquals(0,PhotoLibraryTestProvider.queryCount)
        access=PhotoLibraryAccess.Full;PhotoLibraryTestProvider.ids=(0L..1000L).toList()
        var seen=0
        try {library().references {seen++;if(seen==1) access=PhotoLibraryAccess.Partial};fail()} catch(_:SecurityException) {}
        assertEquals(500,seen);assertEquals(1,PhotoLibraryTestProvider.queryCount)
    }
    @Test fun cancellationClosesCursorAndUnorderedProviderCannotLoopOrSkipSilently()=runBlocking {
        PhotoLibraryTestProvider.ids=listOf(2,1)
        try {library().references {fail()};fail()} catch(_:IllegalStateException) {}
        assertEquals(1,PhotoLibraryTestProvider.closed)
        PhotoLibraryTestProvider.ids=(0L..1000L).toList()
        val entered=CompletableDeferred<Unit>()
        val job=launch {library().references {entered.complete(Unit);awaitCancellation()}}
        entered.await();job.cancelAndJoin()
        assertEquals(2,PhotoLibraryTestProvider.closed)
    }
    @Test fun cancellationSignalsAnInFlightProviderQuery()=runBlocking {
        val entered=CompletableDeferred<Unit>()
        val canceled=java.util.concurrent.CountDownLatch(1)
        PhotoLibraryTestProvider.onQuery={signal->
            signal!!.setOnCancelListener {canceled.countDown()}
            entered.complete(Unit)
            check(canceled.await(5,java.util.concurrent.TimeUnit.SECONDS)) {"Provider query did not receive cancellation"}
            signal.throwIfCanceled()
        }
        val job=launch {library().references {fail("Canceled query must not enumerate")}}
        withTimeout(5000) {entered.await()};withTimeout(5000) {job.cancelAndJoin()}
        assertTrue(job.isCancelled);assertEquals(0L,canceled.count);assertEquals(1,PhotoLibraryTestProvider.queryCount)
    }

    @Test fun pickerLocationChoicePersistsThroughPreferenceRecreation()=runBlocking {
        val preferences=com.bontecou.whereday.data.AppPreferences(context)
        val original=preferences.settings.first()
        try {
            preferences.update {it.copy(requestPhotoLocations=true)}
            assertTrue(com.bontecou.whereday.data.AppPreferences(context).settings.first().requestPhotoLocations)
            preferences.update {it.copy(requestPhotoLocations=false)}
            assertFalse(com.bontecou.whereday.data.AppPreferences(context).settings.first().requestPhotoLocations)
            preferences.update {it.copy(automaticPhotoSync=true,showPhotoMarkers=true)}
            assertTrue(com.bontecou.whereday.data.AppPreferences(context).settings.first().automaticPhotoSync)
            preferences.update {it.copy(automaticPhotoSync=false,showPhotoMarkers=false)}
            assertFalse(com.bontecou.whereday.data.AppPreferences(context).settings.first().automaticPhotoSync)
        } finally {preferences.update {original}}
    }

    @Test fun rangedEnumerationUsesHalfOpenCaptureTimesAndRealObserverUnregisters()=runBlocking {
        PhotoLibraryTestProvider.ids=listOf(1,2,3,4)
        PhotoLibraryTestProvider.times=mapOf(1L to 999L,2L to 1000L,3L to 1999L,4L to 2000L)
        val range=com.bontecou.whereday.domain.HistoryRange(java.time.Instant.ofEpochMilli(1000),java.time.Instant.ofEpochMilli(2000))
        val ids=mutableListOf<String>();library().references(range) {ids+=it.substringAfterLast('/')}
        assertEquals(listOf("2","3"),ids)
        val uri=Uri.parse("content://${context.packageName}.library-test/images")
        val changes=AndroidPhotoChanges(context,uri)
        val first=CompletableDeferred<Unit>();val count=java.util.concurrent.atomic.AtomicInteger()
        val observer=changes.observe {count.incrementAndGet();first.complete(Unit)}
        context.contentResolver.notifyChange(uri,null)
        withTimeout(5000) {first.await()};observer.close()
        val fence=CompletableDeferred<Unit>();val other=changes.observe {fence.complete(Unit)}
        try {context.contentResolver.notifyChange(uri,null);withTimeout(5000) {fence.await()};assertEquals(1,count.get())} finally {other.close()}
    }

}
