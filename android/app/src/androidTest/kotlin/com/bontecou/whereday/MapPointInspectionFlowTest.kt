package com.bontecou.whereday

import android.graphics.*
import android.view.MotionEvent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import org.junit.*
import org.junit.Assert.*
import org.maplibre.android.maps.*
import org.maplibre.android.geometry.LatLng
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class MapPointInspectionFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val point=TrackPoint("inspection-first",Coordinate(18.0,-66.0),Instant.parse("2026-11-01T06:30:21Z"),8.5,35.0,2.5)
    private val points=mutableStateOf(listOf(point))
    private val outliers=mutableStateOf(false)
    private val scope=mutableStateOf("first")
    private val focus=mutableStateOf<VisitMapFocus?>(null)
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private var originalLocales:android.os.LocaleList?=null
    @Before fun guard() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        if(args.getString("pointSpanish")=="true") {
            originalLocales=target.getSystemService(android.app.LocaleManager::class.java).applicationLocales
            applyQaAppLocales(compose,android.os.LocaleList.forLanguageTags("es"))
        }
    }
    @After fun restoreLocale() {originalLocales?.let {applyQaAppLocales(compose,it)}}
    private fun value(format:String,number:Double)=String.format(target.resources.configuration.locales[0],format,number)
    private fun capture(stage:String) {
        if(args.getString("captureMapPoint")!="true") return
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val name="whereday-point-inspection-$stage-${(target.resources.configuration.fontScale*10).toInt()}-20261101.png"
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,name);put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {output->
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output))} finally {bitmap.recycle()}
        }
        println("Point inspection QA capture: $uri $name")
    }
    private fun show(metric:Boolean=true):StateRestorationTester=StateRestorationTester(compose).also {tester->tester.setContent {
        val dark=args.getString("pointDark")=="true"
        val colors=if(dark) darkColorScheme(primary=androidx.compose.ui.graphics.Color(0xFFA8D8B8),secondary=androidx.compose.ui.graphics.Color(0xFFBBCBBF)) else lightColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF285D42),secondary=androidx.compose.ui.graphics.Color(0xFF4E6355))
        MaterialTheme(colorScheme=colors) {Surface {Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            JournalMap(points.value,emptyList(),followMetric=metric,showOutliers=outliers.value,onOutliers={outliers.value=it},inspectionScope=scope.value,visitFocus=focus.value)
        }}}
    }}
    private fun choose(id:String=point.id) {
        native()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("map-point-browse").fetchSemanticsNodes().size==1}
        compose.onNodeWithTag("map-point-browse").performScrollTo().performClick()
        compose.onNodeWithTag("map-point-choice-$id").performScrollTo().performClick()
        compose.onNodeWithTag("map-point-inspection").performScrollTo().assertIsDisplayed()
    }
    private fun native():Pair<MapView,MapLibreMap> {
        compose.onNodeWithTag("journal-native-map").performScrollTo()
        val view=compose.runOnIdle {checkNotNull(findNativeMapView())};val map=AtomicReference<MapLibreMap>()
        compose.runOnUiThread {view.getMapAsync {map.set(it)}}
        compose.waitUntil(10000) {compose.runOnIdle {map.get()?.style?.isFullyLoaded==true}}
        compose.awaitNativeMapFrame(view,map.get());return view to map.get()
    }
    private fun tap(view:MapView,pixel:PointF) {
        compose.waitUntil(5000) {compose.runOnIdle {view.hasWindowFocus()}}
        val origin=compose.runOnIdle {IntArray(2).also(view::getLocationOnScreen)}
        val now=SystemClock.uptimeMillis();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,origin[0]+pixel.x,origin[1]+pixel.y,0)
            try {assertTrue(automation.injectInputEvent(event,true))} finally {event.recycle()}
        }
    }
    private fun awaitDetails(shown:Boolean=true) {compose.waitUntil(5000) {
        compose.onAllNodesWithTag("map-point-inspection").fetchSemanticsNodes().isNotEmpty()==shown
    }}
    @Test fun actualPointTapHighlightsMetadataAndRepeatOrBlankTapDismisses() {
        show();val (view,map)=native()
        fun center()=compose.runOnIdle {map.projection.toScreenLocation(LatLng(18.0,-66.0))}
        tap(view,center());awaitDetails();compose.onNodeWithTag("map-point-inspection").assertExists()
        compose.onNodeWithTag("map-point-accuracy").assertTextContains("9 m",substring=true)
        compose.onNodeWithTag("map-point-altitude").assertTextContains("35 m",substring=true)
        compose.onNodeWithTag("map-point-speed").assertTextContains(value("%.1f",9.0)+" km/h",substring=true)
        compose.waitUntil(5000) {compose.runOnIdle {map.queryRenderedFeatures(RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),"journal-selected-point").singleOrNull()?.getStringProperty("id")==point.id}}
        tap(view,center());awaitDetails(false);compose.onNodeWithTag("map-point-inspection").assertDoesNotExist()
        tap(view,center());awaitDetails();compose.onNodeWithTag("map-point-inspection").assertExists()
        tap(view,PointF(view.width*.1f,view.height*.1f));awaitDetails(false);compose.onNodeWithTag("map-point-inspection").assertDoesNotExist()
    }
    @Test fun accessibleChoiceUsesImperialFieldsAndRetainsSelectionThroughRestoration() {
        val tester=show(false);choose()
        compose.onNodeWithTag("map-point-speed").assertTextContains(value("%.1f",2.5*2.23693629)+" mph",substring=true)
        capture("imperial-details")
        tester.emulateSavedInstanceStateRestore();native();awaitDetails();compose.onNodeWithTag("map-point-inspection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("map-point-close").performScrollTo().performClick();compose.onNodeWithTag("map-point-inspection").assertDoesNotExist()
    }
    @Test fun hiddenPointsCannotRetainOrResurrectAnInspection() {
        show();choose()
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.map_layers)).performScrollTo().performClick()
        compose.onNodeWithTag("map-layer-points").performClick()
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.map_layers_done)).performClick()
        compose.onNodeWithTag("map-point-inspection").assertDoesNotExist();compose.onNodeWithTag("map-point-browse").assertDoesNotExist()
    }
    @Test fun scopedNavigationAndVisitFocusClearAnOtherwiseStillVisiblePoint() {
        show();choose();compose.runOnIdle {scope.value="other-range"};compose.onNodeWithTag("map-point-inspection").assertDoesNotExist()
        choose();compose.runOnIdle {focus.value=VisitMapFocus("visit",point.coordinate)};compose.onNodeWithTag("map-point-inspection").assertDoesNotExist()
    }
    @Test fun updatedPointUsesFreshMetadataAndRemovalCannotRestoreStaleDetails() {
        show();choose();compose.runOnIdle {points.value=listOf(point.copy(accuracy=12.0,altitude=null,speed=null))}
        compose.onNodeWithTag("map-point-accuracy").assertTextContains("12 m",substring=true);compose.onNodeWithTag("map-point-altitude").assertDoesNotExist();compose.onNodeWithTag("map-point-speed").assertDoesNotExist()
        compose.runOnIdle {points.value=emptyList()};compose.onNodeWithTag("map-point-inspection").assertDoesNotExist()
        compose.runOnIdle {points.value=listOf(point)};compose.onNodeWithTag("map-point-inspection").assertDoesNotExist()
    }
    @Test fun outlierInspectionRequiresVisibleOutlierAndDisplaysStatus() {
        compose.runOnIdle {points.value=listOf(point,point.copy(id="outlier",coordinate=Coordinate(18.01,-66.01),outlier=true));outliers.value=true}
        show();choose("outlier");compose.onNodeWithTag("map-point-outlier").assertExists();capture("outlier-details")
        compose.runOnIdle {outliers.value=false};compose.onNodeWithTag("map-point-inspection").assertDoesNotExist()
    }
    @Test fun nativeTapAfterScopeChangeUsesTheCurrentSelectionOwner() {
        show();val (view,map)=native()
        fun center()=compose.runOnIdle {map.projection.toScreenLocation(LatLng(18.0,-66.0))}
        tap(view,center());awaitDetails()
        compose.runOnIdle {scope.value="replacement-range"}
        compose.onNodeWithTag("map-point-inspection").assertDoesNotExist()
        compose.waitUntil(5000) {compose.runOnIdle {
            map.queryRenderedFeatures(RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),"journal-point").any {it.getStringProperty("id")==point.id}
        }}
        tap(view,center());awaitDetails()
        compose.onNodeWithTag("map-point-inspection").assertExists()
    }

}
