package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Uses the production cards and real pointer taps, with explicit synthetic data and no AppGraph. */
@RunWith(AndroidJUnit4::class)
class PhotoStripInteractionTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun revokedOrReboundImageNeverKeepsThePreviousBitmapVisible() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")
        val uri=mutableStateOf("content://synthetic/a")
        val available=mutableStateOf(true)
        val first=android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888)
        val pending=kotlinx.coroutines.CompletableDeferred<android.graphics.Bitmap>()
        val next=kotlinx.coroutines.CompletableDeferred<android.graphics.Bitmap>()
        compose.setContent {MaterialTheme {PhotoImageContent(uri.value,available.value,128,androidx.compose.ui.Modifier,{reference,_->if(reference.endsWith("/a")) first else if(reference.endsWith("/b")) pending.await() else next.await()},{})}}
        compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("Selected photo").fetchSemanticsNodes().size==1}
        compose.runOnIdle {available.value=false}
        compose.onNodeWithContentDescription("Selected photo").assertDoesNotExist()
        compose.onNodeWithText("Photo unavailable").assertExists()
        compose.runOnIdle {uri.value="content://synthetic/b";available.value=true}
        compose.onNodeWithContentDescription("Selected photo").assertDoesNotExist()
        compose.onNodeWithText("Loading photo…").assertExists()
        compose.runOnIdle {pending.complete(first)}
        compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("Selected photo").fetchSemanticsNodes().size==1}
        compose.runOnIdle {uri.value="content://synthetic/c"}
        compose.onNodeWithContentDescription("Selected photo").assertDoesNotExist()
        compose.onNodeWithText("Loading photo…").assertExists()
    }
    @Test fun libraryCardsSelectTheCorrectReferenceAndIncludeAvailabilityInTheirLabel() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")
        val now=Instant.parse("2026-10-27T14:00:00Z")
        val entries=listOf(true,false).mapIndexed {index,available->com.bontecou.whereday.data.PhotoLibraryEntry(com.bontecou.whereday.data.PhotoAssetRow("content://synthetic/library-$index",now.plusSeconds(index*300L),null,true,available,now),null)}
        val selected=mutableStateOf<String?>(null)
        compose.setContent {MaterialTheme {Column {entries.forEach {entry->PhotoReferenceCard(entry,{selected.value=entry.asset.uri}) {modifier->Box(modifier)}}}}}
        compose.onAllNodesWithTag("photo-library-card").assertCountEquals(2)
        compose.onAllNodesWithTag("photo-library-card")[1].assertHasClickAction().performTouchInput {click(center)}
        compose.runOnIdle {assertEquals(entries[1].asset.uri,selected.value)}
        compose.onAllNodesWithContentDescription("Unavailable",substring=true).assertCountEquals(1)
        compose.onAllNodesWithTag("photo-library-card")[0].performTouchInput {click(center)}
        compose.runOnIdle {assertEquals(entries[0].asset.uri,selected.value)}
    }
    @Test fun tappingEachCardSelectsThatPhotoAndPublishesOneAccessibleButton() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")
        val photos=listOf("a","b").mapIndexed {index,id->PhotoMoment("content://synthetic/$id",Instant.parse("2026-10-27T14:00:00Z").plusSeconds(index*300L),Coordinate(48.8584,2.2945),PhotoCoordinateSource.route)}
        val selected=mutableStateOf<String?>(null)
        compose.setContent {MaterialTheme {MatchedPhotoCards(photos,{selected.value=it.uri}) {_,modifier->Box(modifier)}}}
        compose.onAllNodesWithTag("matched-photo-card").assertCountEquals(2)
        compose.onAllNodesWithTag("matched-photo-card")[1].assertHasClickAction().performTouchInput {click(center)}
        compose.runOnIdle {assertEquals(photos[1].uri,selected.value)}
        compose.onAllNodesWithTag("matched-photo-card")[0].performTouchInput {click(center)}
        compose.runOnIdle {assertEquals(photos[0].uri,selected.value)}
        compose.onAllNodesWithContentDescription("Photo taken at",substring=true).assertCountEquals(2)
    }
}
