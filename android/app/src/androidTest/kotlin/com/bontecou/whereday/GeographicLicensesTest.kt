package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
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
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.IOException

/** Bundled assets and synthetic read/link failures only; never opens a browser or geocoder. */
@SdkSuppress(minSdkVersion=33)
class GeographicLicensesTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var oldLocales:LocaleList?=null
    @Before fun setup() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
    }
    @After fun cleanup() {oldLocales?.let {applyQaAppLocales(compose,it)}}
    @Composable private fun content(back:()->Unit={},read:suspend(GeographicDocument)->String={readGeographicLicense(target,it)},open:(GeographicLink)->Unit={}) {
        MaterialTheme(colorScheme=darkColorScheme()) {Surface(Modifier.fillMaxSize().systemBarsPadding()) {GeographicLicensesContent(back,read,open)}}
    }
    private fun document(item:GeographicDocument)=compose.onNodeWithTag("geo-license-document-${item.name}").performScrollTo().performClick()
    private fun awaitText(text:String) {compose.waitUntil(10_000) {compose.onAllNodesWithTag("geo-license-text").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()};compose.onNodeWithTag("geo-license-text").assertTextEquals(text)}
    private fun back()=compose.onNodeWithTag("geo-license-back").performClick()
    private fun held(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    @Test fun spanishOverviewPreservesVersionAttributionAndEveryBundledLicenseVerbatim() {
        var backs=0;val links=mutableListOf<GeographicLink>()
        compose.setContent {content(back={backs++},open={links.add(it)})}
        compose.onNodeWithText("Datos geográficos y licencias").assertIsDisplayed()
        compose.onNodeWithText(target.getString(R.string.geo_license_versions)).performScrollTo().assertIsDisplayed()
        assertTrue(target.getString(R.string.geo_license_versions).contains("2026b.29"));assertTrue(target.getString(R.string.geo_license_versions).contains("2.2.4"))
        for(item in GeographicDocument.entries) {
            document(item)
            val expected=target.assets.open("licenses/${item.asset}").bufferedReader(Charsets.UTF_8).use {it.readText()}
            awaitText(expected);compose.onNodeWithText("Volver a los datos geográficos").assertIsDisplayed();back()
        }
        assertEquals(0,backs);assertTrue(links.isEmpty());back();assertEquals(1,backs)
    }
    @Test fun documentSelectionRestoresAndReturningPreservesOverviewPosition() {
        val tester=StateRestorationTester(compose)
        tester.setContent {content(read={"SYNTHETIC ${it.name}"})}
        document(GeographicDocument.geometry);awaitText("SYNTHETIC geometry")
        tester.emulateSavedInstanceStateRestore();awaitText("SYNTHETIC geometry")
        compose.onNodeWithText("Licencia del motor geométrico").assertIsDisplayed();back()
        compose.onNodeWithTag("geo-license-document-geometry").assertIsDisplayed()
        compose.onNodeWithText("Volver a Ajustes").assertIsDisplayed()
    }
    @Test fun failedReadIsSanitizedAndOnlyExplicitRetryLoadsTheSameDocument() {
        var attempts=0
        compose.setContent {content(read={attempts++;if(attempts==1) throw IOException("synthetic-private-path-token");"RETRIED ${it.name}"})}
        document(GeographicDocument.boundaries)
        compose.waitUntil {compose.onAllNodesWithTag("geo-license-error").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("geo-license-error").assertTextEquals("No se pudo abrir esta licencia. Inténtalo de nuevo.")
        compose.onNodeWithText("synthetic-private-path-token",substring=true).assertDoesNotExist();assertEquals(1,attempts)
        compose.onNodeWithTag("geo-license-retry").performClick();awaitText("RETRIED boundaries");assertEquals(2,attempts)
    }
    @Test fun returningDuringANonCooperativeReadRejectsItsLateText() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        compose.setContent {content(read={entered.complete(Unit);withContext(NonCancellable) {release.await()};"STALE LICENSE"})}
        try {
            document(GeographicDocument.timeshape);compose.waitForIdle();runBlocking {withTimeout(5000) {entered.await()}}
            back();release.complete(Unit);compose.waitForIdle()
            compose.onNodeWithText("Datos geográficos y licencias").assertExists();compose.onNodeWithText("STALE LICENSE").assertDoesNotExist()
            compose.onNodeWithTag("geo-license-text").assertDoesNotExist()
        } finally {release.complete(Unit)}
    }
    @Test fun backgroundCancelsTheReadAndResumeLoadsAFreshResult() {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var attempts=0
        compose.setContent {content(read={attempts++;if(attempts==1) {entered.complete(Unit);withContext(NonCancellable) {release.await()};"OLD BACKGROUND TEXT"} else "RESUMED LICENSE"})}
        try {
            document(GeographicDocument.geometry);compose.waitForIdle();runBlocking {withTimeout(5000) {entered.await()}}
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            release.complete(Unit)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            awaitText("RESUMED LICENSE");assertEquals(2,attempts)
            compose.onNodeWithText("OLD BACKGROUND TEXT").assertDoesNotExist()
        } finally {release.complete(Unit)}
    }
    @Test fun stopAndResumeInOneFrameCancelTheOldReadBeforeRecomposition() {
        val owner=object:androidx.lifecycle.LifecycleOwner {
            override val lifecycle=androidx.lifecycle.LifecycleRegistry(this)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {owner.lifecycle.currentState=Lifecycle.State.RESUMED}
        val release=CompletableDeferred<Unit>();var oldRead:Job?=null;var attempts=0
        compose.setContent {CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
            content(read={attempts++;if(attempts==1) {oldRead=currentCoroutineContext().job;withContext(NonCancellable) {release.await()};"STALE FRAME"} else "FRESH FRAME"})
        }}
        try {
            document(GeographicDocument.geometry);compose.waitForIdle();assertNotNull(oldRead)
            compose.runOnIdle {
                owner.lifecycle.currentState=Lifecycle.State.CREATED
                assertTrue("Read must be canceled by ON_STOP before another Compose frame",oldRead!!.isCancelled)
                owner.lifecycle.currentState=Lifecycle.State.RESUMED
            }
            release.complete(Unit);awaitText("FRESH FRAME");assertEquals(2,attempts)
            compose.onNodeWithText("STALE FRAME").assertDoesNotExist()
        } finally {release.complete(Unit)}
    }
    @Test fun allSourceLinksAreExplicitAndAFailureDoesNotExposeTheException() {
        val opened=mutableListOf<GeographicLink>()
        compose.setContent {content(open={opened.add(it);if(it==GeographicLink.boundaries) throw IllegalStateException("synthetic-browser-token")})}
        for(item in GeographicLink.entries) compose.onNodeWithTag("geo-license-link-${item.name}").performScrollTo().performClick()
        assertEquals(GeographicLink.entries,opened)
        assertEquals(listOf("https://github.com/evansiroky/timezone-boundary-builder","https://github.com/RomanIakovlev/timeshape","https://www.geoapify.com/","https://www.openstreetmap.org/copyright"),opened.map {it.url})
        compose.onNodeWithTag("geo-license-link-boundaries").performScrollTo().performClick()
        compose.onNodeWithTag("geo-license-link-error").performScrollTo().assertTextEquals("No se pudo abrir el enlace de la fuente. Inténtalo de nuevo cuando haya un navegador disponible.")
        compose.onNodeWithText("synthetic-browser-token",substring=true).assertDoesNotExist()
    }
    @Test fun heldOverviewLinksCannotOpenAfterDocumentSelectionOrDisposal() {
        val visible=mutableStateOf(true);var opened=0
        compose.setContent {if(visible.value) content(read={"SYNTHETIC"},open={opened++})}
        compose.onNodeWithTag("geo-license-link-boundaries").performScrollTo()
        val link=held("geo-license-link-boundaries")
        document(GeographicDocument.timeshape);awaitText("SYNTHETIC")
        compose.runOnIdle {link()};assertEquals(0,opened)
        back();compose.onNodeWithTag("geo-license-link-boundaries").performScrollTo();val disposed=held("geo-license-link-boundaries")
        compose.runOnIdle {visible.value=false};compose.waitForIdle();compose.runOnIdle {disposed()};assertEquals(0,opened)
    }
    @Test fun largeTextOverviewAndLongLicenseKeepBackReachable() {
        compose.setContent {content()}
        compose.onNodeWithTag("geo-license-link-timeshape").performScrollTo().assertIsDisplayed();capture("overview")
        document(GeographicDocument.boundaries)
        val expected=target.assets.open("licenses/timezone-boundaries-ODbL.txt").bufferedReader().use {it.readText()}
        awaitText(expected)
        compose.onNodeWithTag("geo-license-text").performSemanticsAction(SemanticsActions.ScrollBy) {it(0f,1000000f)}
        compose.onNodeWithTag("geo-license-back").assertIsDisplayed();capture("document")
        back();compose.onNodeWithTag("geo-license-back").assertIsDisplayed()
    }
    private fun capture(suffix:String) {
        if(InstrumentationRegistry.getArguments().getString("captureLicenses")!="true") return
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        val instrumentation=InstrumentationRegistry.getInstrumentation();compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-geographic-license-$suffix-large-20261030.png");put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")}
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}}
    }
}
