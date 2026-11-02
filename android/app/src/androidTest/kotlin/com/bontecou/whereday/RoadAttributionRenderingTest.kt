package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import java.time.Instant
import org.junit.*
import org.junit.Assert.*

class RoadAttributionRenderingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val points=listOf(TrackPoint("a",Coordinate(0.0,0.0),Instant.EPOCH,5.0),TrackPoint("b",Coordinate(0.0,.01),Instant.EPOCH.plusSeconds(600),5.0))
    private val credits=listOf(RoadAttribution("Geoapify","https://www.geoapify.com/"),RoadAttribution("OpenStreetMap","https://www.openstreetmap.org/copyright",RoadAttributionKind.data))
    private fun route()=RoadRoute(RoadSnapping.fingerprint(points),points.size,listOf(RoadSegment(0,1,listOf(points.first().coordinate,Coordinate(.005,.005),points.last().coordinate),true)),credits)
    @Before fun guard() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
    }
    @Composable private fun Fixture(spanish:Boolean=false,handler:UriHandler,content:@Composable ()->Unit) {
        val base=LocalContext.current;val configuration=LocalConfiguration.current
        val localized=remember(configuration,spanish) {android.content.res.Configuration(configuration).apply {if(spanish) setLocales(android.os.LocaleList.forLanguageTags("es"))}}
        val context=remember(base,localized) {base.createConfigurationContext(localized)}
        CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides localized,LocalUriHandler provides handler) {
            MaterialTheme(colorScheme=if(spanish) darkColorScheme() else lightColorScheme()) {
                SideEffect {androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).apply {
                    isAppearanceLightStatusBars=!spanish;isAppearanceLightNavigationBars=!spanish
                }}
                Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {content()}}
            }
        }
    }
    private fun capture(name:String) {
        if(InstrumentationRegistry.getArguments().getString("captureRoutingConfiguration")!="true") return
        compose.waitForIdle()
        val frames=java.util.concurrent.atomic.AtomicInteger()
        val ticker=object:android.view.Choreographer.FrameCallback {
            override fun doFrame(time:Long) {if(frames.incrementAndGet()<3) android.view.Choreographer.getInstance().postFrameCallback(this)}
        }
        compose.runOnIdle {android.view.Choreographer.getInstance().postFrameCallback(ticker)}
        try {compose.waitUntil(5000) {frames.get()>=3}}
        finally {compose.runOnIdle {android.view.Choreographer.getInstance().removeFrameCallback(ticker)}}
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val size=if(target.resources.configuration.fontScale>=1.8f) "large" else "normal"
            val values=android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-routing-attribution-$name-$size-20261031.png")
                put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
            }
            val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
            target.contentResolver.openOutputStream(uri)!!.use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        } finally {bitmap.recycle()}
    }
    @Test fun creditsAreLinkedAndDisappearWhenThePathIsHiddenOrSourceBecomesStale() {
        var source by mutableStateOf(points);val opened=mutableListOf<String>()
        compose.setContent {Fixture(handler=object:UriHandler {override fun openUri(uri:String) {opened+=uri}}) {JournalMap(source,emptyList(),roadRoute=route(),showStraightSegments=false)}}
        compose.onNodeWithText("Powered by Geoapify").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("© OpenStreetMap contributors").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(credits.map {it.url},opened);capture("english-linked")
        compose.onNodeWithText("Map layers").performScrollTo().performClick()
        compose.onNodeWithTag("map-layer-path").performClick()
        compose.onNodeWithText(target.getString(R.string.map_layers_done)).performClick()
        compose.onNodeWithText("Powered by Geoapify").assertDoesNotExist()
        compose.onNodeWithText("© OpenStreetMap contributors").assertDoesNotExist()
        compose.onNodeWithText("Map layers").performClick();compose.onNodeWithTag("map-layer-path").performClick()
        compose.onNodeWithText(target.getString(R.string.map_layers_done)).performClick()
        compose.onNodeWithText("Powered by Geoapify").assertExists()
        compose.runOnIdle {source=points.map {it.copy(accuracy=6.0)}}
        compose.onNodeWithText("Powered by Geoapify").assertDoesNotExist()
        compose.onNodeWithText("© OpenStreetMap contributors").assertDoesNotExist()
    }
    @Test fun spanishDarkCreditsWrapAndLinkFailureUsesASanitizedMessage() {
        compose.setContent {Fixture(spanish=true,handler=object:UriHandler {override fun openUri(uri:String) {error("synthetic detail must not be displayed")}}) {JournalMap(points,emptyList(),roadRoute=route(),showStraightSegments=false)}}
        compose.onNodeWithText("Con tecnología de Geoapify").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("© colaboradores de OpenStreetMap").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No se pudo abrir el enlace de atribución.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("synthetic detail must not be displayed").assertDoesNotExist()
        capture("spanish-error")
    }
}
