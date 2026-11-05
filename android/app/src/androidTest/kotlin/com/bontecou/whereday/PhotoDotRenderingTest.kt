package com.bontecou.whereday

import android.graphics.*
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.*
import java.time.Instant
import java.util.concurrent.atomic.*

/** Disposable synthetic photos, an offline native map and real presentation/settings code. */
@OptIn(ExperimentalTestApi::class,kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhotoDotRenderingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>(effectContext=kotlinx.coroutines.test.StandardTestDispatcher())
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val visible=mutableStateOf(true)
    private var graph:AppGraph?=null
    private var savedSettings:AppSettings?=null
    private var originalApplicationLocales:android.os.LocaleList?=null
    private var originalActivityLanguage:String?=null
    private val coordinate=Coordinate(48.8584,2.2945)
    private val at=Instant.parse("2026-10-30T12:00:00Z")
    @Before fun guard() {
        compose.mainClock.advanceTimeByFrame()
        check(target.packageName=="com.bontecou.whereday.mapqa")
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
    }
    @After fun cleanup()=runBlocking {
        try {
            compose.runOnIdle {visible.value=false};compose.waitForIdle()
            graph?.let {current->
                current.photoPlaces.clearCache {withContext(Dispatchers.IO) {current.database.clearAllTables()}}
                savedSettings?.let {original->current.preferences.update {original}}
            }
        } finally {
            if(android.os.Build.VERSION.SDK_INT>=33) originalApplicationLocales?.let {original->
                val manager=target.getSystemService(android.app.LocaleManager::class.java)
                val expected=checkNotNull(originalActivityLanguage)
                compose.runOnUiThread {manager.applicationLocales=original}
                compose.waitUntil(5000) {manager.applicationLocales==original && compose.activity.resources.configuration.locales[0].language==expected}
            }
        }
        Unit
    }
    private fun useActualSpanishLocale() {
        check(android.os.Build.VERSION.SDK_INT>=33) {"Per-app locale QA requires API 33+"}
        val manager=target.getSystemService(android.app.LocaleManager::class.java)
        originalApplicationLocales=manager.applicationLocales
        originalActivityLanguage=compose.activity.resources.configuration.locales[0].language
        compose.runOnUiThread {manager.applicationLocales=android.os.LocaleList.forLanguageTags("es")}
        compose.waitUntil(5000) {compose.activity.resources.configuration.locales[0].language=="es"}
    }
    private fun marker(id:String,c:Coordinate=coordinate,count:Long=1)=PhotoMapMarker("dots-test",PhotoAreaSummary(id,1,count,c,1,"content://synthetic/$id"))
    private fun View.mapView():MapView?=if(this is MapView) this else if(this is ViewGroup) (0 until childCount).firstNotNullOfOrNull {getChildAt(it).mapView()} else null
    @Composable private fun Fixture(dark:Boolean=false,spanish:Boolean=false,content:@Composable ()->Unit) {
        val base=LocalContext.current;val configuration=LocalConfiguration.current
        val translated=remember(configuration,spanish) {android.content.res.Configuration(configuration).apply {if(spanish) setLocales(android.os.LocaleList.forLanguageTags("es"))}}
        val context=remember(base,translated) {base.createConfigurationContext(translated)}
        CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides translated) {
            MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {
                SideEffect {androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).apply {
                    isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
                }}
                Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {if(visible.value) content()}}
            }
        }
    }
    private fun nativeMap():Pair<MapLibreMap,MapView> {
        compose.onNodeWithTag("journal-native-map").assertExists()
        val result=AtomicReference<MapLibreMap>()
        val view=compose.runOnIdle {checkNotNull(compose.activity.window.decorView.mapView())}
        compose.runOnIdle {view.getMapAsync {result.set(it)}}
        compose.waitUntil(10000) {compose.runOnIdle {result.get()?.style?.isFullyLoaded==true}}
        compose.awaitNativeMapFrame(view,result.get())
        return result.get() to view
    }
    private fun rendered(map:MapLibreMap,view:MapView,layer:String)=compose.runOnIdle {map.queryRenderedFeatures(RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),layer).size}
    private fun tap(view:MapView,pixel:PointF) {
        compose.waitUntil(5000) {compose.runOnIdle {view.hasWindowFocus()}}
        val origin=compose.runOnIdle {IntArray(2).also(view::getLocationOnScreen)}
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val time=android.os.SystemClock.uptimeMillis()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(time,android.os.SystemClock.uptimeMillis(),action,origin[0]+pixel.x,origin[1]+pixel.y,0)
            try {assertTrue(automation.injectInputEvent(event,true))} finally {event.recycle()}
        }
    }
    @Test fun dotsPaintWithoutSpritesAndNearest48dpTargetWinsAfterCameraRotation() {
        val first=marker("first");val tapped=AtomicReference<List<PhotoMapMarker>>(emptyList())
        val layer=mutableStateOf(PhotoMapLayer(true,true,listOf(first),emptyMap(),{},{},{tapped.set(it)},dots=true))
        compose.setContent {Fixture {JournalMap(emptyList(),emptyList(),photoLayer=layer.value)}}
        val (map,view)=nativeMap()
        compose.waitUntil(10000) {rendered(map,view,"journal-photo-dot")==1}
        compose.runOnIdle {map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(coordinate.latitude,coordinate.longitude)).zoom(17.0).bearing(35.0).tilt(30.0).build()))}
        val second=compose.runOnIdle {
            val p=map.projection.toScreenLocation(LatLng(coordinate.latitude,coordinate.longitude));val scale=target.resources.displayMetrics.density
            val c=map.projection.fromScreenLocation(PointF(p.x+30*scale,p.y))
            marker("second",Coordinate(c.latitude,c.longitude))
        }
        compose.runOnIdle {layer.value=layer.value.copy(clusters=listOf(first,second))}
        compose.waitUntil(10000) {rendered(map,view,"journal-photo-dot")==2}
        assertEquals(0,rendered(map,view,"journal-photo"))
        capture("nearest-light",map,view,true,listOf(first.coordinate to 10,second.coordinate to 10))
        val touch=compose.runOnIdle {
            val a=map.projection.toScreenLocation(LatLng(first.coordinate.latitude,first.coordinate.longitude))
            val b=map.projection.toScreenLocation(LatLng(second.coordinate.latitude,second.coordinate.longitude))
            // Outside both small visuals, inside both 48 dp targets, nearer the second.
            PointF(a.x+(b.x-a.x)*.7f,a.y+(b.y-a.y)*.7f)
        }
        tap(view,touch)
        compose.waitUntil(5000) {tapped.get().isNotEmpty()}
        assertEquals(listOf(second.id),tapped.get().map {it.id})
    }
    @Test fun dotsReplaceSpritesSurviveDarkStyleReloadAndDisappearWhenHidden() {
        val photo=marker("group",count=12001)
        val sprite=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.MAGENTA)}
        val layer=mutableStateOf(PhotoMapLayer(true,true,listOf(photo),mapOf(photo.id to sprite),{},{},{}))
        val dark=mutableStateOf(false)
        compose.setContent {Fixture(dark.value) {JournalMap(emptyList(),emptyList(),photoLayer=layer.value)}}
        val (map,view)=nativeMap();compose.waitUntil(10000) {rendered(map,view,"journal-photo")==1}
        // Even if a caller still carries an old bitmap, dots must never retain a symbol.
        compose.runOnIdle {layer.value=layer.value.copy(dots=true)}
        compose.waitUntil(10000) {rendered(map,view,"journal-photo-dot")==1 && rendered(map,view,"journal-photo")==0}
        compose.runOnIdle {dark.value=true}
        compose.waitUntil(10000) {compose.runOnIdle {map.style?.isFullyLoaded==true} && rendered(map,view,"journal-photo-dot")==1}
        capture("group-dark",map,view,true,listOf(photo.coordinate to 14))
        compose.runOnIdle {layer.value=layer.value.copy(enabled=false)}
        compose.waitUntil(10000) {rendered(map,view,"journal-photo-dot")==0}
        capture("hidden-dark",map,view,false)
        compose.runOnIdle {layer.value=layer.value.copy(enabled=true,dots=false)}
        compose.waitUntil(10000) {rendered(map,view,"journal-photo")==1 && rendered(map,view,"journal-photo-dot")==0}
    }
    @Test fun spanishControlsPreserveImagesAndRejectAHeldImageActionWhileDotsAreOn() {
        useActualSpanishLocale()
        var imageCalls=0
        val layer=mutableStateOf(PhotoMapLayer(true,true,emptyList(),emptyMap(),{},{imageCalls++},{}))
        layer.value=layer.value.copy(showDots={layer.value=layer.value.copy(dots=it)})
        compose.setContent {Fixture(dark=true,spanish=true) {JournalMap(emptyList(),emptyList(),photoLayer=layer.value)}}
        compose.onNodeWithText("Capas del mapa").performClick()
        compose.onNodeWithTag("map-layer-photo-images").performScrollTo().assertIsOn().assertIsEnabled()
        val held=compose.onNodeWithTag("map-layer-photo-images").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("map-layer-photo-dots").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("map-layer-photo-dots").assertIsOn()
        compose.onNodeWithTag("map-layer-photo-images").performScrollTo().assertIsOn().assertIsNotEnabled()
        compose.runOnIdle {held();assertEquals(0,imageCalls);assertTrue(layer.value.images)}
        compose.onNodeWithText("Puntos de fotos").assertIsDisplayed()
        compose.onNodeWithText("Imágenes de fotos").assertIsDisplayed()
        captureScreen("spanish-controls")
        compose.onNodeWithTag("map-layer-photo-dots").performScrollTo().performClick()
        compose.onNodeWithTag("map-layer-photo-images").performScrollTo().assertIsOn().assertIsEnabled()
        assertEquals(0,imageCalls)
    }
    private suspend fun seed(images:Boolean,dots:Boolean,count:Int=1):AppGraph {
        val current=AppGraph.from(target);graph=current;savedSettings=current.preferences.settings.first()
        current.photoPlaces.clearCache {withContext(Dispatchers.IO) {current.database.clearAllTables()}}
        current.database.writeTransaction {
            repeat(count) {index->
                val uri=if(index==0) "content://synthetic/photo-dot" else "content://synthetic/photo-dot-$index"
                val time=at.plusSeconds(index*60L)
                current.history.dao.put(PhotoAssetRow(uri,time,coordinate,true,true,at))
                current.history.dao.put(PhotoRow(uri,time,coordinate,"photo_gps",at))
            }
        }
        current.preferences.update {it.copy(showPhotoMarkers=true,showPhotoImages=images,showPhotoDots=dots)}
        return current
    }
    @Test fun dotAndPinModesDoNotRequestThumbnailsAndKeepTheirStoredPreference()=runBlocking {
        val current=seed(false,true);val calls=AtomicInteger();val state=AtomicReference<PhotoMapLayer>()
        compose.setContent {Fixture {state.set(photoMapLayer(current,HistoryRange.all,true,thumbnail={_,_->calls.incrementAndGet();Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)}))}}
        compose.waitUntil(10000) {state.get()?.clusters?.size==1 && state.get()?.dots==true}
        compose.runOnIdle {assertTrue(state.get().icons.isEmpty());assertFalse(state.get().images);assertEquals(0,calls.get());state.get().showDots(false)}
        compose.waitUntil(5000) {state.get()?.dots==false && state.get()?.icons?.size==1}
        assertEquals(0,calls.get());assertFalse(current.preferences.settings.first().showPhotoImages)
        compose.runOnIdle {state.get().showImages(true)}
        compose.waitUntil(5000) {calls.get()==1 && state.get()?.images==true}
        assertTrue(current.preferences.settings.first().showPhotoImages)
    }
    @Test fun nativeDotOpensItsCompleteProductionCollectionAndEligibilityLossDismissesIt()=runBlocking {
        val current=seed(true,true,52);val eligible=mutableStateOf(true);val state=AtomicReference<PhotoMapLayer>()
        val markerReads=AtomicInteger();val browserImages=AtomicInteger()
        compose.setContent {Fixture {
            val layer=photoMapLayer(current,HistoryRange.all,eligible.value,
                thumbnail={_,_->markerReads.incrementAndGet();error("Dots must not load marker thumbnails")},
                image={_,modifier,_->browserImages.incrementAndGet();Box(modifier)})
            SideEffect {state.set(layer)}
            JournalMap(emptyList(),emptyList(),photoLayer=layer)
        }}
        val (map,view)=nativeMap()
        compose.waitUntil(10000) {state.get()?.visibleClusters?.singleOrNull()?.photoCount==52L && rendered(map,view,"journal-photo-dot")==1}
        assertEquals(0,markerReads.get());assertEquals(0,browserImages.get())
        val center=compose.runOnIdle {val c=state.get().visibleClusters.single().coordinate;map.projection.toScreenLocation(LatLng(c.latitude,c.longitude))}
        tap(view,center)
        compose.waitUntil(10000) {compose.onAllNodesWithText("Photos Here").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Photos Here").assertIsDisplayed()
        compose.onNodeWithTag("photo-places-next").performClick()
        compose.onNodeWithTag("photo-places-page").assertTextEquals("Page 2").assertIsDisplayed()
        compose.waitUntil(5000) {compose.onAllNodesWithText("Open photo").fetchSemanticsNodes().size==2}
        assertTrue(browserImages.get()>0);assertEquals(0,markerReads.get())
        captureScreen("production-page-two")
        compose.runOnIdle {eligible.value=false}
        compose.waitUntil(5000) {compose.onAllNodesWithText("Photos Here").fetchSemanticsNodes().isEmpty() && rendered(map,view,"journal-photo-dot")==0}
        assertEquals(52L,current.history.dao.photoAssetCount())
        assertTrue(current.preferences.settings.first().showPhotoImages)
    }
    @Test fun lateThumbnailCannotReplaceTheNewImageAfterADotRoundTrip()=verifyLateThumbnail(false)
    @Test fun lateThumbnailFailureCannotPruneTheCurrentPhotoAfterADotRoundTrip()=verifyLateThumbnail(true)
    private fun verifyLateThumbnail(fails:Boolean)=runBlocking {
        val current=seed(true,false);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val completed=CompletableDeferred<Unit>()
        val calls=AtomicInteger();val state=AtomicReference<PhotoMapLayer>()
        val fresh=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.GREEN)}
        val stale=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.MAGENTA)}
        compose.setContent {Fixture {state.set(photoMapLayer(current,HistoryRange.all,true,thumbnail={_,_->
            if(calls.incrementAndGet()==1) {entered.complete(Unit);withContext(NonCancellable) {release.await();completed.complete(Unit);if(fails) error("synthetic stale failure") else stale}}
            else fresh
        }))}}
        try {
            compose.waitUntil(10000) {entered.isCompleted}
            compose.runOnIdle {state.get().showDots(true)}
            compose.waitUntil(5000) {state.get()?.dots==true && state.get()?.icons?.isEmpty()==true}
            assertTrue(current.preferences.settings.first().showPhotoImages)
            compose.runOnIdle {state.get().showDots(false)}
            compose.waitUntil(5000) {calls.get()==2 && state.get()?.icons?.size==1}
            val sprite=compose.runOnIdle {state.get().icons.values.single()}
            release.complete(Unit);compose.waitUntil(5000) {completed.isCompleted};compose.waitForIdle()
            compose.runOnIdle {assertSame(sprite,state.get().icons.values.single());assertFalse(state.get().dots)}
            assertTrue(current.history.dao.photoAsset("content://synthetic/photo-dot")!!.available)
            assertNotNull(current.history.dao.photoMoment("content://synthetic/photo-dot"))
            assertTrue(current.preferences.settings.first().showPhotoImages)
        } finally {release.complete(Unit);compose.waitForIdle()}
    }
    private fun capture(label:String,map:MapLibreMap,view:MapView,dots:Boolean,visuals:List<Pair<Coordinate,Int>> = emptyList()) {
        val complete=AtomicBoolean()
        val listener=MapView.OnDidFinishRenderingFrameListener {fully,_,_->if(fully) complete.set(true)}
        compose.runOnIdle {view.addOnDidFinishRenderingFrameListener(listener);map.triggerRepaint()}
        try {compose.waitUntil(10000) {complete.get()}} finally {compose.runOnIdle {view.removeOnDidFinishRenderingFrameListener(listener)}}
        frames()
        val bounds=compose.runOnIdle {val p=IntArray(2);view.getLocationOnScreen(p);Rect(p[0],p[1],p[0]+view.width,p[1]+view.height)}
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            assertTrue(bounds.intersect(Rect(0,0,bitmap.width,bitmap.height)))
            val pixels=IntArray(bounds.width()*bounds.height());bitmap.getPixels(pixels,0,bounds.width(),bounds.left,bounds.top,bounds.width(),bounds.height())
            val green=pixels.count {kotlin.math.abs(Color.red(it)-94)<=10 && kotlin.math.abs(Color.green(it)-127)<=10 && kotlin.math.abs(Color.blue(it)-90)<=10}
            if(dots) {assertTrue("Displayed dots required: $green",green>30);assertTrue("Dots must remain small visuals",green<pixels.size/20);assertEquals(0,pixels.count {Color.red(it)>245 && Color.green(it)<10 && Color.blue(it)>245})}
            else assertEquals("Hidden dots must leave no displayed dot pixels",0,green)
            val centers=compose.runOnIdle {visuals.map {(c,size)->map.projection.toScreenLocation(LatLng(c.latitude,c.longitude)) to size}}
            val density=target.resources.displayMetrics.density
            try {for((center,size) in centers) {
                val x=(bounds.left+center.x).toInt();val y=(bounds.top+center.y).toInt();val radius=(12*density).toInt()
                val colored=mutableListOf<Pair<Int,Int>>()
                for(row in maxOf(0,y-radius)..minOf(bitmap.height-1,y+radius)) for(col in maxOf(0,x-radius)..minOf(bitmap.width-1,x+radius)) {
                    val color=bitmap.getPixel(col,row)
                    // Paper stroke (#FBF9F4) around the photo ink (#5E7F5A).
                    if((kotlin.math.abs(Color.red(color)-251)<=6 && kotlin.math.abs(Color.green(color)-249)<=6 && kotlin.math.abs(Color.blue(color)-244)<=6) ||
                        (kotlin.math.abs(Color.red(color)-94)<=10 && kotlin.math.abs(Color.green(color)-127)<=10 && kotlin.math.abs(Color.blue(color)-90)<=10)) colored.add(col to row)
                }
                assertTrue("Visible dot and stroke required",colored.isNotEmpty())
                val width=colored.maxOf {it.first}-colored.minOf {it.first}+1
                val height=colored.maxOf {it.second}-colored.minOf {it.second}+1
                val expected=size*density
                assertTrue("Full dot diameter $size dp expected $expected pixels, found $width x $height",kotlin.math.abs(width-expected)<=4 && kotlin.math.abs(height-expected)<=4)
            }} catch(error:AssertionError) {save("$label-size-failure",bitmap);throw error}
            save(label,bitmap)
        } finally {bitmap.recycle()}
    }
    private fun frames() {
        val frames=AtomicInteger()
        val ticker=object:android.view.Choreographer.FrameCallback {override fun doFrame(time:Long) {if(frames.incrementAndGet()<3) android.view.Choreographer.getInstance().postFrameCallback(this)}}
        compose.runOnIdle {android.view.Choreographer.getInstance().postFrameCallback(ticker)}
        try {compose.waitUntil(5000) {frames.get()>=3}} finally {compose.runOnIdle {android.view.Choreographer.getInstance().removeFrameCallback(ticker)}}
    }
    private fun captureScreen(label:String) {
        compose.waitForIdle();InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300,5000);frames()
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {save(label,bitmap)} finally {bitmap.recycle()}
    }
    private fun save(label:String,bitmap:Bitmap) {
        if(InstrumentationRegistry.getArguments().getString("capturePhotoDots")!="true") return
        val size=if(target.resources.configuration.fontScale>=1.8f) "large" else "normal"
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-photo-dots-$label-$size-20261030.png");put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")}
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
}
