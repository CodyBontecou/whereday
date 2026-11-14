package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.PhotoAssetRow
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

/** Real Room/rank/Paging and production viewer; unavailable synthetic references need no grants. */
class PhotoViewerRestorationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private var ready by mutableStateOf(true)
    private var current:PhotoViewerDraft?=null
    private fun uri(i:Int)="content://synthetic/restoration-${i.toString().padStart(4,'0')}"
    @Before fun setup():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(context);graph.journalClear.clear().join()
        graph.database.writeTransaction {repeat(125) {i->graph.history.dao.put(PhotoAssetRow(uri(i),Instant.EPOCH.plusSeconds(i*60L),null,true,false,Instant.EPOCH))}}
    }
    @After fun cleanup():Unit=runBlocking {graph.journalClear.clear().join()}
    private fun show():StateRestorationTester {
        val restoration=StateRestorationTester(compose)
        restoration.setContent {MaterialTheme {
            var draft by rememberSaveable(stateSaver=PhotoViewerDraft.Saver) {mutableStateOf<PhotoViewerDraft?>(PhotoViewerDraft.open("library",uri(49)))}
            SideEffect {current=draft}
            draft?.let {value->if(ready) key(value.id) {
                LibraryPhotoViewer(graph,value.current,{draft=null},{},onCurrent={value.current=it})
            }}
        }}
        return restoration
    }
    private fun awaitPhoto(number:Int,total:Int=125) = compose.waitUntil(10000) {runCatching {compose.onNodeWithText("Photo $number of $total").assertExists()}.isSuccess}
    private fun next() {compose.onNodeWithTag("photo-pager-next").performScrollTo().performClick()}
    @Test fun restorationRetainsTheCurrentPhotoBeyondTheOpeningLibraryPage() {
        val restoration=show();awaitPhoto(50);next();awaitPhoto(51);next();awaitPhoto(52)
        compose.runOnIdle {assertEquals(uri(51),current!!.current)}
        restoration.emulateSavedInstanceStateRestore();awaitPhoto(52)
        compose.runOnIdle {assertEquals(uri(51),current!!.current)}
        compose.onNodeWithTag("photo-pager-previous").performScrollTo().performClick();awaitPhoto(51)
    }
    @Test fun hiddenValidationGateRetainsIdentityAndReresolvesItsChangedRank():Unit=runBlocking {
        show();awaitPhoto(50);next();awaitPhoto(51)
        compose.runOnIdle {ready=false};compose.onNodeWithTag("photo-pager-content").assertDoesNotExist()
        graph.history.dao.put(PhotoAssetRow("content://synthetic/restoration-0000-extra",Instant.EPOCH,null,true,false,Instant.EPOCH))
        compose.runOnIdle {ready=true};awaitPhoto(52,126)
        compose.runOnIdle {assertEquals(uri(50),current!!.current)}
    }
    @Test fun removedCurrentReferenceCannotBeReplacedByAnotherPhotoAtItsOldRank():Unit=runBlocking {
        show();awaitPhoto(50);next();awaitPhoto(51)
        compose.runOnIdle {ready=false};graph.photos.remove(uri(50))
        compose.runOnIdle {ready=true}
        compose.waitUntil(10000) {current==null}
        compose.onNodeWithTag("photo-pager-content").assertDoesNotExist()
        assertEquals(124L,graph.history.dao.photoAssetCount());assertNotNull(graph.history.dao.photoAsset(uri(49)))
    }
}
