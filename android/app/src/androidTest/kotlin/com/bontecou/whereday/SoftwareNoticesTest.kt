package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Real bundled notices, synthetic failures and disposable mapQa UI only. No server or browser. */
class SoftwareNoticesTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val doc=SoftwareNoticeDocument("a".repeat(64),"Fixture license",7)
    private val item=SoftwareNoticeComponent(softwareNoticeDigest("example:fixture:1".toByteArray()),"QA software","example:fixture:1",listOf(doc))
    @Before fun isolated() {check(target.packageName=="com.bontecou.whereday.mapqa")}
    @Composable private fun content(back:()->Unit={},load:suspend()->List<SoftwareNoticeComponent> = {listOf(item)},read:suspend(SoftwareNoticeDocument)->List<String> = {listOf("ORIGINAL FIXTURE")},dark:Boolean=false) {
        MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {
            SideEffect {androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }}
            Surface(Modifier.fillMaxSize()) {Box(Modifier.fillMaxSize().systemBarsPadding()) {SoftwareNoticesContent(back,load,read)}}
        }
    }
    private fun await(tag:String) {compose.waitUntil(10_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}}
    private fun component(value:SoftwareNoticeComponent=item) {
        await("software-notices-list")
        compose.onNodeWithTag("software-notices-list").performScrollToNode(hasTestTag("software-notice-component-${value.id}"))
        compose.onNodeWithTag("software-notice-component-${value.id}").performClick()
    }
    private fun document(value:SoftwareNoticeDocument=doc) {
        compose.onNodeWithTag("software-notices-documents").performScrollToNode(hasTestTag("software-notice-document-${value.sha256}"))
        compose.onNodeWithTag("software-notice-document-${value.sha256}").performClick()
    }
    private fun back()=compose.onNodeWithTag("software-notices-back").performClick()
    @Test fun actualAssetCatalogAndEveryReadableDocumentHaveVerifiedOriginalBytes():Unit=runBlocking {
        val repository=SoftwareNoticeRepository(target);val catalog=repository.catalog()
        assertEquals(118,catalog.size)
        assertEquals(setOf("androidx.window:window:1.5.1","androidx.window:window-core-android:1.5.1"),catalog.filter {it.coordinate.startsWith("androidx.window:")}.map {it.coordinate}.toSet())
        assertFalse(catalog.any {it.coordinate.contains("window-testing")})
        for(item in catalog) for(doc in item.documents) {
            val chunks=repository.document(doc)
            val raw=target.assets.open("software-notices/documents/${doc.sha256}.txt").use {it.readBytes()}
            assertArrayEquals(raw,chunks.joinToString("").toByteArray(Charsets.UTF_8))
            assertTrue(chunks.all {it.length<=3000})
        }
        assertFalse(catalog.any {it.coordinate.contains("timeshape") || it.coordinate.contains("ui-test")})
    }
    @Test fun actualMapLibreNoticeReachesItsFinalTextAndReturnsToTheSameSearch():Unit=runBlocking {
        val repository=SoftwareNoticeRepository(target)
        val library=repository.catalog().single {it.coordinate=="org.maplibre.gl:android-sdk:13.4.1"}
        val license=library.documents.single {it.title=="MapLibre Native Android"}
        val original=repository.document(license)
        compose.setContent {content(load=repository::catalog,read=repository::document,dark=true)}
        await("software-notices-list")
        compose.onNodeWithTag("software-notices-search").performTextReplacement(library.coordinate)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        component(library);document(license);await("software-notices-text")
        compose.onNodeWithTag("software-notice-chunk-0").assertTextEquals(original.first())
        capture("maplibre-top")
        compose.onNodeWithTag("software-notices-text").performScrollToNode(hasTestTag("software-notice-end"))
        compose.onNodeWithTag("software-notice-chunk-${original.lastIndex}").assertTextEquals(original.last()).assertIsDisplayed()
        compose.onNodeWithTag("software-notice-end").assertIsDisplayed()
        compose.onNodeWithTag("software-notices-back").assertIsDisplayed();capture("maplibre-end")
        back();compose.onNodeWithTag("software-notices-documents").assertExists()
        back();compose.onNodeWithTag("software-notices-search").assertTextContains(library.coordinate)
        compose.onNodeWithTag("software-notice-component-${library.id}").assertIsDisplayed()
    }
    @Test fun searchEmptyStateAndAllThreeBackLevelsKeepTheirMeaning() {
        var leaves=0
        compose.setContent {content(back={leaves++})};await("software-notices-list")
        capture("light-list")
        compose.onNodeWithTag("software-notices-search").performTextReplacement("No matching fixture")
        compose.onNodeWithTag("software-notices-empty").assertExists()
        androidx.test.espresso.Espresso.closeSoftKeyboard();capture("light-empty")
        compose.onNodeWithTag("software-notices-search").performTextClearance();androidx.test.espresso.Espresso.closeSoftKeyboard()
        component();document();await("software-notices-text")
        back();compose.onNodeWithTag("software-notices-documents").assertExists();assertEquals(0,leaves)
        androidx.test.espresso.Espresso.pressBack();compose.onNodeWithTag("software-notices-list").assertExists();assertEquals(0,leaves)
        back();assertEquals(1,leaves)
    }
    @Test fun everySupplementalNativeNoticeOpensVerbatimAndReachesItsEnd():Unit=runBlocking {
        val repository=SoftwareNoticeRepository(target)
        val library=repository.catalog().single {it.coordinate=="org.maplibre.gl:android-sdk:13.4.1"}
        val titles=listOf("HarfBuzz","unordered_dense","PMTiles","jni.hpp","MapLibre Tile Format",
            "Vulkan Headers","Vulkan Memory Allocator","glslang and SPIRV bundled notices",
            "Apache License, Version 2.0 (Vulkan Headers)","ICU and bundled third-party notices")
        val documents=titles.map {title->library.documents.single {it.title==title}}
        compose.setContent {content(load=repository::catalog,read=repository::document,dark=true)}
        await("software-notices-list")
        compose.onNodeWithTag("software-notices-search").performTextReplacement(library.coordinate)
        androidx.test.espresso.Espresso.closeSoftKeyboard();component(library)
        for(license in documents) {
            val original=repository.document(license)
            document(license);await("software-notices-text")
            compose.onNodeWithTag("software-notice-chunk-0").assertTextEquals(original.first())
            compose.onNodeWithTag("software-notices-text").performScrollToNode(hasTestTag("software-notice-end"))
            compose.onNodeWithTag("software-notice-chunk-${original.lastIndex}").assertTextEquals(original.last())
            compose.onNodeWithTag("software-notice-end").assertIsDisplayed()
            compose.onNodeWithTag("software-notices-back").assertIsDisplayed()
            if(license.title==titles.last()) capture("icu-end")
            back();compose.onNodeWithTag("software-notices-documents").assertExists()
        }
    }
    @Test fun documentAndQueryRestoreAfterActivitySavedStateRecreation() {
        val tester=StateRestorationTester(compose)
        tester.setContent {content()};await("software-notices-list")
        compose.onNodeWithTag("software-notices-search").performTextReplacement("QA")
        androidx.test.espresso.Espresso.closeSoftKeyboard();component();document();await("software-notices-text")
        tester.emulateSavedInstanceStateRestore();await("software-notices-text")
        compose.onNodeWithTag("software-notice-chunk-0").assertTextEquals("ORIGINAL FIXTURE")
        back();back();compose.onNodeWithTag("software-notices-search").assertTextContains("QA")
    }
    @Test fun documentFailureIsSanitizedAndOnlyExplicitRetryLoadsIt() {
        var attempts=0
        compose.setContent {content(read={attempts++;if(attempts==1) error("private-path-token");listOf("RETRIED")})}
        component();document();await("software-notices-document-error")
        compose.onNodeWithText("private-path-token",substring=true).assertDoesNotExist();assertEquals(1,attempts)
        capture("read-error")
        compose.onNodeWithTag("software-notices-document-retry").performClick();await("software-notices-text")
        compose.onNodeWithTag("software-notice-chunk-0").assertTextEquals("RETRIED");assertEquals(2,attempts)
    }
    @Test fun catalogFailureHasAnExplicitRetryAndNoRawException() {
        var attempts=0
        compose.setContent {content(load={attempts++;if(attempts==1) error("private-catalog-path");listOf(item)})}
        await("software-notices-error");compose.onNodeWithText("private-catalog-path",substring=true).assertDoesNotExist()
        compose.onNodeWithTag("software-notices-retry").performClick();component();assertEquals(2,attempts)
    }
    @Test fun leavingDuringANonCooperativeReadCannotPublishTheLateDocument() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        compose.setContent {content(read={entered.complete(Unit);withContext(NonCancellable) {release.await()};listOf("LATE TEXT")})}
        try {
            component();document();compose.waitForIdle();runBlocking {withTimeout(5000) {entered.await()}}
            back();release.complete(Unit);compose.waitForIdle()
            compose.onNodeWithTag("software-notices-documents").assertExists();compose.onNodeWithText("LATE TEXT").assertDoesNotExist()
        } finally {release.complete(Unit)}
    }
    @Test fun backgroundCancelsTheReadAndResumeRequiresAFreshResult() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var attempts=0
        compose.setContent {content(read={attempts++;if(attempts==1) {entered.complete(Unit);withContext(NonCancellable) {release.await()};listOf("OLD TEXT")} else listOf("FRESH TEXT")})}
        try {
            component();document();compose.waitForIdle();runBlocking {withTimeout(5000) {entered.await()}}
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);release.complete(Unit)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);await("software-notices-text")
            compose.onNodeWithTag("software-notice-chunk-0").assertTextEquals("FRESH TEXT");assertEquals(2,attempts)
        } finally {release.complete(Unit)}
    }
    @Test fun aHeldDocumentActionCannotRetargetAnAlreadyOpenReader() {
        val second=SoftwareNoticeDocument("b".repeat(64),"Second license",4)
        val library=item.copy(documents=listOf(doc,second))
        compose.setContent {content(load={listOf(library)},read={listOf(it.title)})}
        component(library)
        val held=compose.onNodeWithTag("software-notice-document-${second.sha256}").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        document();await("software-notices-text");compose.runOnIdle {held()}
        compose.onNodeWithTag("software-notice-chunk-0").assertTextEquals(doc.title)
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion=33)
    fun spanishDarkTextAndLongContentKeepNavigationReachable() {
        val locales=target.getSystemService(android.app.LocaleManager::class.java).applicationLocales
        try {
            applyQaAppLocales(compose,android.os.LocaleList.forLanguageTags("es"))
            val long=item.copy(name="QA software with a deliberately long original library name and preserved attribution")
            val body=softwareNoticeChunks(("Original licensed text. Preserve this content.\r\n").repeat(200))
            compose.setContent {content(load={listOf(long)},read={body},dark=true)}
            await("software-notices-list");compose.onNodeWithText("Avisos de software").assertIsDisplayed()
            compose.onNodeWithText("Buscar software").assertIsDisplayed();capture("spanish-list")
            component(long);document();await("software-notices-text")
            compose.onNodeWithTag("software-notices-text").performScrollToNode(hasTestTag("software-notice-end"))
            compose.onNodeWithTag("software-notice-chunk-${body.lastIndex}").assertIsDisplayed()
            compose.onNodeWithTag("software-notice-end").assertTextEquals("Fin del aviso").assertIsDisplayed()
            compose.onNodeWithTag("software-notices-back").assertIsDisplayed();capture("spanish-long-end")
            back();back();compose.onNodeWithTag("software-notices-list").assertExists()
        } finally {applyQaAppLocales(compose,locales)}
    }
    private fun capture(label:String) {
        if(InstrumentationRegistry.getArguments().getString("captureSoftwareNotices")!="true") return
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        compose.waitForIdle();val instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val frames=java.util.concurrent.atomic.AtomicInteger()
        val ticker=object:android.view.Choreographer.FrameCallback {
            override fun doFrame(time:Long) {if(frames.incrementAndGet()<3) android.view.Choreographer.getInstance().postFrameCallback(this)}
        }
        compose.runOnIdle {android.view.Choreographer.getInstance().postFrameCallback(ticker)}
        try {compose.waitUntil(5000) {frames.get()>=3}}
        finally {compose.runOnIdle {android.view.Choreographer.getInstance().removeFrameCallback(ticker)}}
        val size=if(target.resources.configuration.fontScale>=1.8f) "large" else "normal"
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-software-$label-$size-20261030.png");put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")}
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}}
    }
}
