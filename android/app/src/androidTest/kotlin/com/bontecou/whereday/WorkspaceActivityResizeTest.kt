package com.bontecou.whereday

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/** Explicitly invoked emulator-only tests: real display configuration, real MainActivity recreation. */
class WorkspaceActivityResizeTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val target get()=instrumentation.targetContext
    private lateinit var graph:AppGraph
    private lateinit var original:Visit
    private lateinit var outing:RecordingSession
    private var originalSize=""
    private fun shell(command:String):String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    @Before fun setup():Unit=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        val args=InstrumentationRegistry.getArguments()
        check(args.getString("allowWorkspaceResize")=="true") {"Explicit disposable emulator resize opt-in required"}
        check(shell("getprop ro.kernel.qemu")=="1") {"Never resize a physical device"}
        val expected=checkNotNull(args.getString("expectedResizeAvd"))
        check(shell("getprop ro.boot.qemu.avd_name")==expected) {"Resize AVD identity differs"}
        check(shell("wm density").contains("Override density: 200")) {"Owned driver must set actual density 200"}
        originalSize=shell("wm size")
        check(originalSize.contains("Override size: 800x1600")) {"Owned driver must start in the compact window"}
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,networkGeocoding=false,inferredEnabled=false)}
        val day=LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val now=Instant.ofEpochMilli(System.currentTimeMillis())
        val at=now.minusSeconds(600).coerceAtLeast(day)
        val end=now.minusSeconds(1).coerceAtLeast(at)
        original=Visit("123e4567-e89b-12d3-a456-426614174090",Coordinate(18.0,-66.0),at,end,name="Actual resize visit",notes="Stored note",source=VisitSource.manual,confirmation=Confirmation.confirmed)
        outing=RecordingSession("actual-resize-outing",at,end,name="Actual resize outing")
        graph.history.dao.put(VisitRow(original));graph.history.dao.put(SessionRow(outing))
        graph.history.dao.putPoints(listOf(PointRow(TrackPoint("resize-first",original.coordinate,at,5.0),outing.id),PointRow(TrackPoint("resize-last",Coordinate(18.001,-66.001),end,5.0),outing.id)))
        Unit
    }
    @After fun cleanup():Unit=runBlocking {
        instrumentation.runOnMainSync {Stage.values().flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.filterIsInstance<MainActivity>().distinct().forEach {it.finish()}}
        if(originalSize.isNotEmpty()) shell("wm size 800x1600")
        if(::graph.isInitialized) graph.journalClear.clear().join()
        Unit
    }
    private fun nodes(tag:String)=compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false)
    private fun awaitTag(tag:String)=compose.waitUntil(15000) {nodes(tag).size==1}
    private fun awaitName(value:String)=compose.waitUntil(15000) {runCatching {compose.onNodeWithTag("visit-name").assertTextContains(value,substring=true)}.isSuccess}
    private fun resumed():MainActivity {
        var activity:MainActivity?=null
        instrumentation.runOnMainSync {activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()}
        return checkNotNull(activity)
    }
    private fun model():JournalViewModel {
        val owner=resumed();val result=AtomicReference<JournalViewModel>()
        instrumentation.runOnMainSync {result.set(ViewModelProvider(owner)[JournalViewModel::class.java])}
        return result.get()
    }
    private fun awaitNativeMaps() {
        for(role in listOf("workspace-primary","workspace-companion")) {
            compose.onNode(hasTestTag("journal-native-map") and hasAnyAncestor(hasTestTag(role))).performScrollTo()
        }
        val views=AtomicReference<List<org.maplibre.android.maps.MapView>>()
        instrumentation.runOnMainSync {
            fun android.view.View.maps():List<org.maplibre.android.maps.MapView> = when(this) {
                is org.maplibre.android.maps.MapView->listOf(this)
                is android.view.ViewGroup->(0 until childCount).flatMap {getChildAt(it).maps()}
                else->emptyList()
            }
            views.set(android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap {it.maps()}.filter {it.isAttachedToWindow && it.isShown})
        }
        assertEquals("Both real primary/companion maps must recover",2,views.get().size)
        for(view in views.get()) {
            val map=AtomicReference<org.maplibre.android.maps.MapLibreMap>()
            instrumentation.runOnMainSync {view.getMapAsync {map.set(it)}}
            compose.waitUntil(10000) {compose.runOnIdle {map.get()?.style?.isFullyLoaded==true}}
            compose.awaitNativeMapFrame(view,map.get())
        }
    }
    private fun resize(wide:Boolean):MainActivity {
        val before=resumed();val width=if(wide) 2200 else 800
        shell("wm size ${width}x1600")
        compose.waitUntil(20000) {
            var ready=false
            instrumentation.runOnMainSync {
                val current=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
                ready=current!=null && current!==before && current.window.decorView.width==width
            }
            ready
        }
        val current=resumed();assertNotSame("Default config handling must really recreate the Activity",before,current)
        return current
    }
    private fun launch()=ActivityScenario.launch<MainActivity>(Intent(target,MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    private fun open() {
        awaitTag("navigation-timeline");compose.onNodeWithTag("navigation-timeline").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText(original.name!!).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        compose.onNodeWithText(original.name!!).performScrollTo().performClick();awaitName(original.name!!)
    }
    private fun closeKeyboard() {androidx.test.espresso.Espresso.closeSoftKeyboard()}
    private fun capture(stage:String) {
        if(InstrumentationRegistry.getArguments().getString("captureActualWorkspaceResize")!="true") return
        if(InstrumentationRegistry.getArguments().getString("captureExportDateOnly")=="true" && !stage.startsWith("export-date-")) return
        if(InstrumentationRegistry.getArguments().getString("captureDirectoryOnly")=="true" && !stage.startsWith("directory-")) return
        if(InstrumentationRegistry.getArguments().getString("capturePhotoOnly")=="true" && !stage.startsWith("photo-")) return
        if(InstrumentationRegistry.getArguments().getString("captureReplayOnly")=="true" && !stage.startsWith("replay-")) return
        if(InstrumentationRegistry.getArguments().getString("captureLibraryEditorsOnly")=="true" && !stage.startsWith("library-")) return
        compose.waitForIdle();instrumentation.uiAutomation.waitForIdle(500,5000)
        val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val values=android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-actual-resize-$stage-20261102.png")
                put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
            }
            val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
            target.contentResolver.openOutputStream(uri)!!.use {check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        } finally {image.recycle()}
    }
    @Test fun dirtyDraftMovesThroughActualActivityRecreationsAndSavesOnlyOnExplicitApply():Unit=runBlocking {
        launch().use {
            open();val originalModel=model()
            compose.onNodeWithTag("visit-name").performTextReplacement("Actual resized draft")
            compose.onNodeWithTag("visit-notes").performScrollTo().performTextReplacement("Uncommitted resized note");closeKeyboard()
            resize(true);awaitName("Actual resized draft");awaitTag("visit-inline-inspector")
            assertSame(originalModel,model())
            assertEquals(1,nodes("visit-editor-fields").size);compose.onNodeWithTag("timeline-content").assertDoesNotExist()
            compose.onNodeWithTag("visit-notes").assertTextContains("Uncommitted resized note",substring=true)
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
            capture("wide-dirty")
            resize(false);awaitName("Actual resized draft")
            compose.onNodeWithTag("visit-inline-inspector").assertDoesNotExist()
            assertEquals(1,compose.onAllNodes(isDialog()).fetchSemanticsNodes().size)
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
            capture("compact-dirty")
            compose.onNodeWithTag("visit-save").assertIsDisplayed().performClick()
            compose.waitUntil(15000) {nodes("visit-name").isEmpty()}
            val saved=graph.history.dao.visit(original.id)!!.value
            assertEquals("Actual resized draft",saved.name);assertEquals("Uncommitted resized note",saved.notes)
            assertEquals(original.arrivedAt,saved.arrivedAt);assertEquals(original.departedAt,saved.departedAt)
            assertEquals(1L,graph.history.dao.totalVisitCount())
            compose.onNodeWithTag("navigation-timeline").assertIsSelected()
        }
    }
    @Test fun invalidNestedIsoDraftSurvivesActualCompactExpandedRecreationsWithoutMutation():Unit=runBlocking {
        launch().use {
            open()
            compose.onNode(hasTestTag("visit-timestamp-date") and hasAnyAncestor(hasTestTag("visit-arrival-time"))).performScrollTo().performClick()
            compose.onNodeWithText(target.getString(R.string.date_enter_iso)).performScrollTo().performClick()
            compose.onNodeWithTag("visit-timestamp-date-input").performTextReplacement("2026-02-30");closeKeyboard()
            resize(true);awaitTag("visit-timestamp-date-input")
            compose.onNodeWithTag("visit-timestamp-date-input").assertTextContains("2026-02-30",substring=true)
            compose.onNodeWithText(target.getString(R.string.visit_apply_date)).assertIsNotEnabled()
            capture("wide-invalid-iso")
            resize(false);awaitTag("visit-timestamp-date-input")
            compose.onNodeWithTag("visit-timestamp-date-input").assertTextContains("2026-02-30",substring=true)
            compose.onNodeWithText(target.getString(R.string.visit_apply_date)).assertIsNotEnabled()
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }
    @Test fun timelineOutingNavigationAndNativeMapRecoverAfterRealWindowRecreation():Unit=runBlocking {
        launch().use {
            awaitTag("navigation-timeline");compose.onNodeWithTag("navigation-timeline").performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithText(outing.name!!).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithText(outing.name!!).performScrollTo().performClick();awaitTag("outing-title")
            resize(true);awaitTag("outing-title");awaitTag("workspace-companion")
            compose.onNodeWithTag("navigation-timeline").assertIsSelected()
            compose.onNodeWithTag("outing-title").assertTextEquals(outing.name!!)
            awaitNativeMaps()
            resize(false);awaitTag("outing-title")
            compose.onNodeWithTag("navigation-timeline").assertIsSelected()
            compose.onNodeWithTag("workspace-companion").assertDoesNotExist()
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
            assertEquals(2L,graph.history.dao.totalPointCount())
        }
    }
    private fun nativeMap():Pair<org.maplibre.android.maps.MapView,org.maplibre.android.maps.MapLibreMap> {
        awaitTag("journal-native-map");compose.onNodeWithTag("journal-native-map").performScrollTo()
        val view=AtomicReference<org.maplibre.android.maps.MapView>()
        instrumentation.runOnMainSync {view.set(checkNotNull(findNativeMapView()))}
        val map=AtomicReference<org.maplibre.android.maps.MapLibreMap>()
        instrumentation.runOnMainSync {view.get().getMapAsync {map.set(it)}}
        compose.waitUntil(10000) {compose.runOnIdle {map.get()?.style?.isFullyLoaded==true}}
        compose.awaitNativeMapFrame(view.get(),map.get());return view.get() to map.get()
    }
    @Test fun manuallyPositionedCameraSurvivesBothActualWindowRecreationDirections():Unit=runBlocking {
        launch().use {
            val (_,map)=nativeMap()
            val requested=org.maplibre.android.camera.CameraPosition.Builder().target(org.maplibre.android.geometry.LatLng(18.2,-66.2)).zoom(11.0).bearing(25.0).tilt(30.0).build()
            instrumentation.runOnMainSync {map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(requested))}
            fun assertCamera(actual:org.maplibre.android.camera.CameraPosition) {
                assertEquals(requested.target!!.latitude,actual.target!!.latitude,0.000001)
                assertEquals(requested.target!!.longitude,actual.target!!.longitude,0.000001)
                assertEquals(requested.zoom,actual.zoom,0.000001)
                assertEquals(requested.bearing,actual.bearing,0.000001)
                assertEquals(requested.tilt,actual.tilt,0.000001)
            }
            compose.waitForIdle();compose.runOnIdle {assertCamera(map.cameraPosition)}
            resize(true);val (_,wide)=nativeMap();compose.runOnIdle {assertCamera(wide.cameraPosition)}
            capture("wide-camera")
            resize(false);val (_,compact)=nativeMap();compose.runOnIdle {assertCamera(compact.cameraPosition)}
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }

    @Test fun restoredCameraDoesNotReplayAnAlreadyAppliedVisitFocusButAcceptsANewRequest():Unit=runBlocking {
        launch().use {
            open();compose.onNodeWithTag("visit-view-map").performScrollTo().performClick()
            val (_,map)=nativeMap()
            val focusBefore=model().mapSelection.value.visitFocus
            assertEquals(original.id,focusBefore?.id)
            val requested=org.maplibre.android.camera.CameraPosition.Builder().target(org.maplibre.android.geometry.LatLng(18.2,-66.2)).zoom(11.0).bearing(25.0).tilt(30.0).build()
            instrumentation.runOnMainSync {map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(requested))}
            resize(true);val (_,wide)=nativeMap()
            compose.runOnIdle {assertEquals(18.2,wide.cameraPosition.target!!.latitude,0.000001);assertEquals(-66.2,wide.cameraPosition.target!!.longitude,0.000001)}
            compose.onNodeWithText(original.name!!).performScrollTo().performClick();awaitName(original.name!!)
            compose.onNodeWithTag("visit-view-map").performScrollTo().performClick()
            compose.waitUntil(10000) {model().mapSelection.value.visitFocus?.requestId!=focusBefore?.requestId}
            val (_,focused)=nativeMap()
            try {
                compose.waitUntil(10000) {compose.runOnIdle {kotlin.math.abs(focused.cameraPosition.target!!.latitude-original.coordinate.latitude)<0.0001}}
            } catch(failure:androidx.compose.ui.test.ComposeTimeoutException) {
                val actual=compose.runOnIdle {focused.cameraPosition}
                throw AssertionError("Fresh focus ${model().mapSelection.value.visitFocus}; native camera $actual",failure)
            }
            compose.runOnIdle {
                assertEquals(original.coordinate.longitude,focused.cameraPosition.target!!.longitude,0.0001)
                assertEquals(0.0,focused.cameraPosition.bearing,0.000001)
                assertEquals(0.0,focused.cameraPosition.tilt,0.000001)
            }
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }

    private fun editFrameworkClock(hour:Int,minute:Int) {
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.TimePicker::class.java)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(object:androidx.test.espresso.ViewAction {
            override fun getConstraints():org.hamcrest.Matcher<android.view.View> = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.TimePicker::class.java)
            override fun getDescription()="Edit the real recreation clock draft"
            override fun perform(controller:androidx.test.espresso.UiController,view:android.view.View) {(view as android.widget.TimePicker).apply {this.hour=hour;this.minute=minute};controller.loopMainThreadUntilIdle()}
        })
    }
    private fun assertFrameworkClock(hour:Int,minute:Int) {
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.TimePicker::class.java)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check {view,error->
            if(error!=null) throw error
            val clock=view as android.widget.TimePicker
            assertEquals(hour,clock.hour);assertEquals(minute,clock.minute)
        }
    }
    private fun approval(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
    private fun openAutomaticClock():com.bontecou.whereday.platform.AutomaticExportSettings {
        awaitTag("navigation-settings");compose.onNodeWithTag("navigation-settings").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText(target.getString(R.string.settings_automatic_exports)))
        compose.onNodeWithText(target.getString(R.string.settings_automatic_exports)).performClick()
        awaitTag("automatic-export-anchor")
        compose.waitUntil(10000) {graph.automaticExportController.state.value.let {it.loaded && !it.busy}}
        val before=graph.automaticExportController.state.value.state.settings
        compose.onNodeWithTag("automatic-export-anchor").performScrollTo().performClick()
        return before
    }
    private fun openExportClock():com.bontecou.whereday.export.ExportSelection {
        awaitTag("navigation-export");compose.onNodeWithTag("navigation-export").performClick()
        awaitTag("export-advanced");compose.onNodeWithTag("export-advanced").performScrollTo().performClick()
        awaitTag("export-time-enabled")
        if(graph.exportSettings.state.value.selection.options.startTime==null) compose.onNodeWithTag("export-time-enabled").performScrollTo().performClick()
        awaitTag("export-time-from")
        compose.waitUntil(10000) {graph.exportSettings.state.value.let {it.ready && !it.saving && it.selection.options.startTime!=null}}
        val before=graph.exportSettings.state.value.selection
        compose.onNodeWithTag("export-time-from").performScrollTo().performClick()
        return before
    }
    @Test fun automaticAnchorClockDraftSurvivesActualActivityRecreationAndRequiresApply():Unit=runBlocking {
        launch().use {
            val before=openAutomaticClock()
            editFrameworkClock(23,45)
            val obsolete=approval("automatic-anchor-confirm")
            resize(true);awaitTag("automatic-anchor-clock");assertFrameworkClock(23,45);capture("clock-anchor-wide")
            compose.runOnIdle {obsolete()}
            assertEquals(before,graph.automaticExportController.state.value.state.settings)
            resize(false);awaitTag("automatic-anchor-clock");assertFrameworkClock(23,45);capture("clock-anchor-compact")
            assertEquals(before,graph.automaticExportController.state.value.state.settings)
            compose.onNodeWithTag("automatic-anchor-confirm").assertIsDisplayed().performClick()
            compose.waitUntil(10000) {graph.automaticExportController.state.value.let {!it.busy && it.state.settings.schedule.hour==23 && it.state.settings.schedule.minute==45}}
            assertEquals(before.copy(schedule=before.schedule.copy(hour=23,minute=45)),graph.automaticExportController.state.value.state.settings)
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }
    @Test fun exportTimeClockDraftSurvivesActualActivityRecreationAndCancelLeavesSettingsUntouched():Unit=runBlocking {
        launch().use {
            val before=openExportClock();editFrameworkClock(7,25)
            val obsolete=approval("export-time-from-confirm")
            resize(true);awaitTag("export-time-from-clock");assertFrameworkClock(7,25);capture("clock-export-wide")
            compose.runOnIdle {obsolete()}
            assertEquals(before,graph.exportSettings.state.value.selection)
            resize(false);awaitTag("export-time-from-clock");assertFrameworkClock(7,25);capture("clock-export-compact")
            compose.onNodeWithTag("export-time-from-cancel").assertIsDisplayed().performClick()
            compose.onNodeWithTag("export-time-from-clock").assertDoesNotExist()
            assertEquals(before,graph.exportSettings.state.value.selection)
            compose.onNodeWithTag("export-time-from").performScrollTo().performClick();editFrameworkClock(7,25)
            compose.onNodeWithTag("export-time-from-confirm").assertIsDisplayed().performClick()
            compose.waitUntil(10000) {graph.exportSettings.state.value.let {!it.saving && it.selection.options.startTime==java.time.LocalTime.of(7,25)}}
            assertEquals(before.copy(options=before.options.copy(startTime=java.time.LocalTime.of(7,25))),graph.exportSettings.state.value.selection)
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }

    @Test fun ordinaryBackgroundAfterRecreationDismissesBothClocksAndRejectsOldApprovals():Unit=runBlocking {
        launch().use {scenario->
            val automatic=openAutomaticClock();editFrameworkClock(22,30)
            resize(true);awaitTag("automatic-anchor-clock");assertFrameworkClock(22,30)
            val anchorApproval=approval("automatic-anchor-confirm")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            instrumentation.runOnMainSync {anchorApproval()}
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.onNodeWithTag("automatic-anchor-clock").assertDoesNotExist()
            assertEquals(automatic,graph.automaticExportController.state.value.state.settings)
            resize(false)
            val selection=openExportClock();editFrameworkClock(6,40)
            resize(true);awaitTag("export-time-from-clock");assertFrameworkClock(6,40)
            val exportApproval=approval("export-time-from-confirm")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            instrumentation.runOnMainSync {exportApproval()}
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.onNodeWithTag("export-time-from-clock").assertDoesNotExist()
            assertEquals(selection,graph.exportSettings.state.value.selection)
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }

    private fun invalidFrameworkText(assertOnly:Boolean=false) {
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.TimePicker::class.java)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(object:androidx.test.espresso.ViewAction {
            override fun getConstraints():org.hamcrest.Matcher<android.view.View> = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.TimePicker::class.java)
            override fun getDescription()="Verify actual clock text mode and invalid pending fields"
            override fun perform(controller:androidx.test.espresso.UiController,view:android.view.View) {
                val picker=view as android.widget.TimePicker
                fun field(name:String)=picker.findViewById<android.widget.EditText>(picker.resources.getIdentifier(name,"id","android"))
                if(!assertOnly) {
                    if(!field("input_hour").isShown) {
                        val toggle=picker.findViewById<android.view.View>(picker.resources.getIdentifier("toggle_mode","id","android"))
                        check(toggle.performClick());controller.loopMainThreadForAtLeast(350)
                    }
                    field("input_hour").setText("99");field("input_minute").setText("71")
                    controller.loopMainThreadUntilIdle()
                }
                assertTrue("Native input mode must survive actual Activity recreation",field("input_hour").isShown)
                assertEquals("99",field("input_hour").text.toString());assertEquals("71",field("input_minute").text.toString())
                assertFalse(picker.validateInput())
            }
        })
    }
    @Test fun invalidFrameworkClockTextAndModeSurviveBothRealRecreationDirections():Unit=runBlocking {
        launch().use {
            val automatic=openAutomaticClock();invalidFrameworkText()
            resize(true);awaitTag("automatic-anchor-clock");invalidFrameworkText(true)
            compose.onNodeWithTag("automatic-anchor-confirm").performClick()
            assertEquals(automatic,graph.automaticExportController.state.value.state.settings)
            capture("clock-anchor-wide-invalid")
            resize(false);awaitTag("automatic-anchor-clock");invalidFrameworkText(true)
            compose.onNodeWithTag("automatic-anchor-confirm").performClick()
            assertEquals(automatic,graph.automaticExportController.state.value.state.settings)
            capture("clock-anchor-compact-invalid")
            compose.onNodeWithTag("automatic-anchor-cancel").performClick()
            val selection=openExportClock();invalidFrameworkText()
            resize(true);awaitTag("export-time-from-clock");invalidFrameworkText(true)
            compose.onNodeWithTag("export-time-from-confirm").performClick()
            assertEquals(selection,graph.exportSettings.state.value.selection)
            capture("clock-export-wide-invalid")
            resize(false);awaitTag("export-time-from-clock");invalidFrameworkText(true)
            compose.onNodeWithTag("export-time-from-confirm").performClick()
            assertEquals(selection,graph.exportSettings.state.value.selection)
            capture("clock-export-compact-invalid")
            compose.onNodeWithTag("export-time-from-cancel").performClick()
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }

    private fun openExportDates() {
        awaitTag("navigation-export");compose.onNodeWithTag("navigation-export").performClick()
        awaitTag("export-advanced");compose.onNodeWithTag("export-advanced").performScrollTo().performClick()
        awaitTag("export-date-range");compose.onNodeWithTag("export-date-range").performScrollTo().performClick()
        compose.onNodeWithText(target.getString(R.string.date_enter_iso)).assertIsDisplayed().performClick()
        awaitTag("date-range-from")
    }
    @Test fun exportDateIsoDraftSurvivesRealRecreationAndOnlyCorrectedDatesApply():Unit=runBlocking {
        launch().use {
            compose.waitUntil(10000) {graph.exportSettings.state.value.ready}
            val zone=ZoneId.of("America/New_York")
            val from=LocalDate.of(2024,3,9).atStartOfDay(zone).toInstant()
            val until=LocalDate.of(2024,3,11).atStartOfDay(zone).toInstant().minusNanos(1)
            val selected=graph.exportSettings.state.value.selection
            graph.exportSettings.update(selected.copy(options=selected.options.copy(from=from,until=until,zone=zone,maximumAccuracy=25.0,pointAltitude=false)))
            compose.waitUntil(10000) {graph.exportSettings.state.value.let {!it.saving && it.selection.options.from==from}}
            openExportDates();val before=graph.exportSettings.state.value.selection
            val obsolete=compose.onNodeWithText(target.getString(R.string.map_apply_dates)).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            compose.onNodeWithTag("date-range-from").performTextReplacement("2024-02-30")
            compose.onNodeWithTag("date-range-through").performTextReplacement("2024-03-10");closeKeyboard()
            resize(true);awaitTag("date-range-from")
            compose.onNodeWithTag("date-range-from").assertTextContains("2024-02-30",substring=true)
            compose.onNodeWithText(target.getString(R.string.map_apply_dates)).assertIsNotEnabled()
            compose.runOnIdle {obsolete()};assertEquals(before,graph.exportSettings.state.value.selection)
            capture("export-date-wide-invalid")
            resize(false);awaitTag("date-range-from")
            compose.onNodeWithTag("date-range-from").assertTextContains("2024-02-30",substring=true)
            compose.onNodeWithTag("date-range-through").assertTextContains("2024-03-10",substring=true)
            compose.onNodeWithText(target.getString(R.string.map_apply_dates)).assertIsNotEnabled()
            assertEquals(before,graph.exportSettings.state.value.selection);capture("export-date-compact-invalid")
            compose.onNodeWithTag("date-range-from").performTextReplacement("2024-03-10");closeKeyboard()
            compose.onNodeWithText(target.getString(R.string.map_apply_dates)).assertIsEnabled().performClick()
            compose.waitUntil(10000) {nodes("date-range-from").isEmpty() && graph.exportSettings.state.value.let {!it.saving && it.selection.options.from==Instant.parse("2024-03-10T05:00:00Z")}}
            assertEquals(before.copy(options=before.options.copy(from=Instant.parse("2024-03-10T05:00:00Z"),until=Instant.parse("2024-03-11T03:59:59.999999999Z"))),graph.exportSettings.state.value.selection)
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }
    @Test fun exportDateBackgroundRetainsDraftButRejectsTheOldApplyAfterResume():Unit=runBlocking {
        launch().use {scenario->
            compose.waitUntil(10000) {graph.exportSettings.state.value.ready}
            val current=graph.exportSettings.state.value.selection
            val seed=current.copy(options=current.options.copy(from=Instant.parse("2023-01-01T00:00:00Z"),until=Instant.parse("2023-01-02T00:00:00Z")))
            graph.exportSettings.update(seed)
            compose.waitUntil(10000) {graph.exportSettings.state.value.let {!it.saving && it.selection==seed}}
            openExportDates();val before=graph.exportSettings.state.value.selection
            compose.onNodeWithTag("date-range-from").performTextReplacement("2024-03-10")
            compose.onNodeWithTag("date-range-through").performTextReplacement("2024-03-10");closeKeyboard()
            val obsolete=compose.onNodeWithText(target.getString(R.string.map_apply_dates)).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            instrumentation.runOnMainSync {obsolete()}
            assertEquals(before,graph.exportSettings.state.value.selection)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            awaitTag("date-range-from");compose.onNodeWithTag("date-range-from").assertTextContains("2024-03-10",substring=true)
            compose.runOnIdle {obsolete()}
            assertEquals(before,graph.exportSettings.state.value.selection)
            compose.onNodeWithText(target.getString(R.string.map_cancel_dates)).performClick()
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }

    private val savedPlace=SavedPlace("actual-resize-place","Actual resize place",Coordinate(18.0,-66.0),"Stored address",150.0)
    private fun openPlaceLibrary() {
        awaitTag("navigation-settings");compose.onNodeWithTag("navigation-settings").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText(target.getString(R.string.settings_manage_places)))
        compose.onNodeWithText(target.getString(R.string.settings_manage_places)).performClick();awaitTag("saved-places-content")
    }
    private fun openStoredPlace() {
        openPlaceLibrary()
        compose.waitUntil(10000) {compose.onAllNodesWithText(savedPlace.name).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        compose.onNodeWithText(savedPlace.name).performScrollTo().performClick();awaitTag("saved-name")
    }
    private fun openStoredOuting() {
        awaitTag("navigation-timeline");compose.onNodeWithTag("navigation-timeline").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText(outing.name!!).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        compose.onNodeWithText(outing.name!!).performScrollTo().performClick();awaitTag("outing-title")
        compose.onNodeWithText(target.getString(R.string.outing_edit)).performScrollTo().performClick();awaitTag("outing-name")
    }
    private suspend fun seedPhotoFixture(count:Int=4,distinct:Boolean=false):List<String> {
        val folder=java.io.File(target.cacheDir,"exports/photo-pager-resize-qa").apply {check(mkdirs() || isDirectory)}
        val formatter=java.time.format.DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss",java.util.Locale.ROOT).withZone(java.time.ZoneOffset.UTC)
        val references=(0 until count).map {i->
            val file=java.io.File(folder,"photo-$i.jpg")
            val bitmap=android.graphics.Bitmap.createBitmap(48,32,android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(40+(i%5)*35,90,65))
            try {file.outputStream().use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it))}} finally {bitmap.recycle()}
            val span=(outing.endedAt!!.toEpochMilli()-original.arrivedAt.toEpochMilli()).coerceAtLeast(0)
            val captured=Instant.ofEpochMilli(original.arrivedAt.toEpochMilli()+span*(i+1)/(count+1))
            android.media.ExifInterface(file.absolutePath).apply {
                setAttribute("DateTimeOriginal",formatter.format(captured));setAttribute("OffsetTimeOriginal","+00:00");setAttribute("SubSecTimeOriginal",(captured.nano/1000000).toString().padStart(3,'0'))
                setAttribute("GPSLatitude","18/1,0/1,0/1");setAttribute("GPSLatitudeRef","N")
                setAttribute("GPSLongitude","${660000-if(distinct) i*100 else 0}/10000,0/1,0/1");setAttribute("GPSLongitudeRef","W");saveAttributes()
            }
            androidx.core.content.FileProvider.getUriForFile(target,"${target.packageName}.exports",file).toString() to captured
        }
        graph.database.writeTransaction {references.forEach {(uri,time)->
            graph.history.dao.put(PhotoAssetRow(uri,time,original.coordinate,true,true,time))
            graph.history.dao.put(PhotoRow(uri,time,original.coordinate,"photo_gps",time))
        }}
        graph.photoLibrary.refresh()
        check(graph.photoLibrary.state.value.validated)
        check(graph.history.dao.photoAssetCount()==count.toLong())
        return references.map {it.first}
    }
    private fun awaitPhoto(number:Int) = compose.waitUntil(15000) {
        runCatching {compose.onNodeWithText("Photo $number of 4").assertExists()}.isSuccess
    }
    @Test fun libraryCurrentPhotoSurvivesActualRecreationBeyondItsOpeningAsset():Unit=runBlocking {
        val references=seedPhotoFixture()
        launch().use {
            awaitTag("navigation-settings");compose.onNodeWithTag("navigation-settings").performClick()
            compose.onNodeWithTag("settings-screen").performScrollToNode(hasText(target.getString(R.string.settings_photos)))
            compose.onNodeWithText(target.getString(R.string.settings_photos)).performClick();awaitTag("photo-library-content")
            compose.onNodeWithTag("photo-library-content").performScrollToNode(hasTestTag("photo-library-card"))
            compose.onAllNodesWithTag("photo-library-card").onFirst().performClick();awaitPhoto(1)
            repeat(2) {compose.onNodeWithTag("photo-pager-next").performScrollTo().performClick()};awaitPhoto(3)
            resize(true);awaitPhoto(3);capture("photo-library-wide")
            resize(false);awaitPhoto(3);capture("photo-library-compact")
            compose.onNodeWithTag("photo-pager-previous").performScrollTo().performClick();awaitPhoto(2)
            compose.onNodeWithTag("photo-pager-close").performScrollTo().performClick()
            assertEquals(4L,graph.history.dao.photoAssetCount())
            references.forEach {assertTrue(graph.history.dao.photoAsset(it)!!.available)}
            assertEquals(original,graph.history.dao.visit(original.id)!!.value);assertEquals(2L,graph.history.dao.totalPointCount())
        }
    }
    @Test fun outingCurrentPhotoSurvivesActualRecreationAfterValidation():Unit=runBlocking {
        seedPhotoFixture()
        launch().use {
            openStoredOuting();compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithTag("outing-detail-content").performScrollToNode(hasTestTag("matched-photo-strip"))
            compose.waitUntil(15000) {compose.onAllNodesWithTag("matched-photo-card").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onAllNodesWithTag("matched-photo-card").onFirst().performClick();awaitPhoto(1)
            repeat(2) {compose.onNodeWithTag("photo-pager-next").performScrollTo().performClick()};awaitPhoto(3)
            resize(true);awaitPhoto(3);capture("photo-outing-wide")
            resize(false);awaitPhoto(3);capture("photo-outing-compact")
            compose.onNodeWithTag("photo-pager-close").performScrollTo().performClick()
            assertEquals(outing,graph.history.dao.session(outing.id)!!.value)
            assertEquals(original,graph.history.dao.visit(original.id)!!.value);assertEquals(4L,graph.history.dao.photoAssetCount())
        }
    }
    private fun awaitDirectoryPage(page:Int)=compose.waitUntil(15000) {
        runCatching {compose.onNodeWithTag("photo-places-page").assertTextEquals("Page $page")}.isSuccess
    }
    private fun openMapPhotoPlaces() {
        awaitTag("navigation-map")
        compose.waitUntil(15000) {nodes("browse-photo-places").size==1}
        compose.onNodeWithTag("browse-photo-places").performScrollTo().performClick();awaitDirectoryPage(1)
    }
    @Test fun mapPhotoDirectoryPageAndPlaceReturnSurviveBothActualRecreations():Unit=runBlocking {
        seedPhotoFixture(52,true);graph.preferences.update {it.copy(showPhotoMarkers=true,showPhotoImages=false)}
        launch().use {
            openMapPhotoPlaces();compose.onNodeWithTag("photo-places-next").performClick();awaitDirectoryPage(2)
            resize(true);awaitDirectoryPage(2);capture("directory-list-wide")
            compose.waitUntil(10000) {compose.onAllNodesWithTag("photo-place-card").fetchSemanticsNodes().size==2}
            compose.onAllNodesWithTag("photo-place-card").onFirst().performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithText(target.getString(R.string.photo_places_here)).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("photo-places-close").performClick();awaitDirectoryPage(2)
            resize(false);awaitDirectoryPage(2);capture("directory-list-compact")
            compose.onNodeWithTag("photo-places-previous").performClick();awaitDirectoryPage(1)
            compose.onNodeWithTag("photo-places-close").performClick()
            assertEquals(52L,graph.history.dao.photoAssetCount());assertEquals(original,graph.history.dao.visit(original.id)!!.value)
            assertEquals(2L,graph.history.dao.totalPointCount())
        }
    }
    @Test fun mapPhotoCollectionAndLastViewedAssetSurviveBothActualRecreations():Unit=runBlocking {
        seedPhotoFixture(52);graph.preferences.update {it.copy(showPhotoMarkers=true,showPhotoImages=false)}
        launch().use {
            openMapPhotoPlaces();compose.waitUntil(10000) {compose.onAllNodesWithTag("photo-place-card").fetchSemanticsNodes().size==1}
            compose.onNodeWithTag("photo-place-card").performClick();awaitDirectoryPage(1)
            compose.onNodeWithTag("photo-places-next").performClick();awaitDirectoryPage(2)
            compose.waitUntil(10000) {compose.onAllNodesWithText(target.getString(R.string.photo_open)).fetchSemanticsNodes().size==2}
            compose.onAllNodesWithText(target.getString(R.string.photo_open)).onFirst().performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithText("Photo 51 of 52").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("photo-pager-next").performScrollTo().performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithText("Photo 52 of 52").fetchSemanticsNodes().isNotEmpty()}
            resize(true);compose.waitUntil(15000) {compose.onAllNodesWithText("Photo 52 of 52").fetchSemanticsNodes().isNotEmpty()};capture("directory-viewer-wide")
            resize(false);compose.waitUntil(15000) {compose.onAllNodesWithText("Photo 52 of 52").fetchSemanticsNodes().isNotEmpty()};capture("directory-viewer-compact")
            compose.onNodeWithTag("photo-pager-close").performScrollTo().performClick();awaitDirectoryPage(2)
            compose.onNodeWithTag("photo-places-previous").performClick();awaitDirectoryPage(1)
            assertEquals(52L,graph.history.dao.photoAssetCount());assertEquals(outing,graph.history.dao.session(outing.id)!!.value)
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }
    private fun awaitReplayPoint(index:Int,count:Int) = compose.waitUntil(15000) {
        runCatching {compose.onNodeWithTag("replay-counter").assertTextEquals("Point $index of $count")}.isSuccess
    }
    @Test fun outingReplayRestoresPausedPositionAcrossBothActualRecreations():Unit=runBlocking {
        launch().use {
            openStoredOuting()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Replay route").performScrollTo().performClick();awaitTag("replay-progress")
            compose.onNodeWithTag("replay-end").performScrollTo().performClick();awaitReplayPoint(2,2)
            resize(true);awaitReplayPoint(2,2)
            compose.onNodeWithTag("replay-toggle").assertTextEquals(target.getString(R.string.replay_play));capture("replay-outing-wide")
            resize(false);awaitReplayPoint(2,2)
            compose.onNodeWithTag("replay-toggle").assertTextEquals(target.getString(R.string.replay_play));capture("replay-outing-compact")
            compose.onNodeWithTag("replay-start").performScrollTo().performClick();awaitReplayPoint(1,2)
            compose.onNodeWithTag("replay-close").performScrollTo().performClick()
            compose.onNodeWithTag("route-replay-content").assertDoesNotExist()
            assertEquals(outing,graph.history.dao.session(outing.id)!!.value)
            assertEquals(original,graph.history.dao.visit(original.id)!!.value);assertEquals(2L,graph.history.dao.totalPointCount())
        }
    }
    @Test fun mapReplayRetainsOpeningRangeWhileTheLiveWindowRefreshes():Unit=runBlocking {
        launch().use {
            awaitTag("navigation-map")
            compose.onNodeWithText("Replay route").performScrollTo().performClick();awaitTag("replay-progress")
            compose.onNodeWithTag("replay-end").performScrollTo().performClick();awaitReplayPoint(2,2)
            // Observe the real 30-second live-window cadence, without changing its selection.
            val journal=model();val openingUntil=(journal.mapWindow.value as WindowState.Ready).window.range.until
            compose.waitUntil(45000) {(journal.mapWindow.value as? WindowState.Ready)?.window?.range?.until?.let {it>openingUntil}==true}
            awaitReplayPoint(2,2)
            resize(true);awaitReplayPoint(2,2);capture("replay-map-wide")
            resize(false);awaitReplayPoint(2,2);capture("replay-map-compact")
            compose.onNodeWithTag("replay-close").performScrollTo().performClick()
            assertEquals(original,graph.history.dao.visit(original.id)!!.value);assertEquals(2L,graph.history.dao.totalPointCount())
        }
    }
    @Test fun storedPlaceDraftSurvivesRealRecreationAndPreservesItsIdentityOnSave():Unit=runBlocking {
        graph.savedPlaces.save(savedPlace,null)
        launch().use {
            openStoredPlace();compose.onNodeWithTag("saved-name").performTextReplacement("Resized place draft")
            compose.onNodeWithTag("saved-latitude").performScrollTo().performTextReplacement("invalid");closeKeyboard()
            resize(true);awaitTag("saved-name")
            compose.onNodeWithTag("saved-name").assertTextContains("Resized place draft",substring=true)
            compose.onNodeWithTag("saved-latitude").performScrollTo().assertTextContains("invalid",substring=true)
            assertEquals(savedPlace,graph.history.dao.place(savedPlace.id)!!.value);capture("library-place-wide")
            resize(false);awaitTag("saved-name")
            compose.onNodeWithTag("saved-name").assertTextContains("Resized place draft",substring=true)
            compose.onNodeWithTag("saved-editor-save").assertIsDisplayed().performClick();awaitTag("saved-editor-error")
            assertEquals(savedPlace,graph.history.dao.place(savedPlace.id)!!.value)
            capture("library-place-compact")
            compose.onNodeWithTag("saved-latitude").performScrollTo().performTextReplacement("18.0");closeKeyboard()
            compose.onNodeWithTag("saved-editor-save").performClick()
            compose.waitUntil(10000) {nodes("saved-name").isEmpty()}
            assertEquals(savedPlace.copy(name="Resized place draft"),graph.history.dao.place(savedPlace.id)!!.value)
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }
    @Test fun newPlaceDraftSurvivesRealRecreationWithoutCreatingAnythingBeforeSave():Unit=runBlocking {
        graph.savedPlaces.save(savedPlace,null)
        launch().use {
            openPlaceLibrary();compose.onNodeWithText(target.getString(R.string.saved_add)).performScrollTo().performClick();awaitTag("saved-name")
            compose.onNodeWithTag("saved-name").performTextReplacement("New resized library place")
            compose.onNodeWithTag("saved-latitude").performScrollTo().performTextReplacement("18.1")
            compose.onNodeWithTag("saved-longitude").performScrollTo().performTextReplacement("-66.1");closeKeyboard()
            resize(true);awaitTag("saved-name");compose.onNodeWithTag("saved-name").assertTextContains("New resized library place",substring=true)
            assertEquals(1,graph.history.dao.places().size)
            resize(false);awaitTag("saved-name");compose.onNodeWithTag("saved-name").assertTextContains("New resized library place",substring=true)
            assertEquals(1,graph.history.dao.places().size)
            compose.onNodeWithTag("saved-editor-save").assertIsDisplayed().performClick()
            compose.waitUntil(10000) {nodes("saved-name").isEmpty()}
            val places=graph.history.dao.places().map {it.value}
            assertEquals(2,places.size);assertEquals(1,places.count {it.name=="New resized library place" && it.coordinate==Coordinate(18.1,-66.1)})
            assertEquals(savedPlace,graph.history.dao.place(savedPlace.id)!!.value)
        }
    }
    @Test fun restoredPlaceEditorKeepsTheOriginalConflictBaseline():Unit=runBlocking {
        graph.savedPlaces.save(savedPlace,null)
        launch().use {
            openStoredPlace();compose.onNodeWithTag("saved-name").performTextReplacement("Pending old-baseline edit");closeKeyboard()
            val independent=savedPlace.copy(name="Independent place change")
            graph.savedPlaces.save(independent,savedPlace)
            resize(true);awaitTag("saved-name")
            compose.onNodeWithTag("saved-name").assertTextContains("Pending old-baseline edit",substring=true)
            compose.onNodeWithTag("saved-editor-save").performClick();awaitTag("saved-editor-error")
            compose.onNodeWithTag("saved-editor-error").assertTextEquals(target.getString(R.string.saved_changed))
            assertEquals(independent,graph.history.dao.place(savedPlace.id)!!.value)
            assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }
    private fun awaitPlaceMapFrame() {
        val view=AtomicReference<org.maplibre.android.maps.MapView>()
        instrumentation.runOnMainSync {view.set(checkNotNull(findNativeMapView()))}
        val map=AtomicReference<org.maplibre.android.maps.MapLibreMap>()
        instrumentation.runOnMainSync {view.get().getMapAsync {map.set(it)}}
        compose.waitUntil(10000) {compose.runOnIdle {map.get()?.style?.isFullyLoaded==true}}
        compose.awaitNativeMapFrame(view.get(),map.get())
    }
    @Test fun nestedPlaceMapSurvivesActualRecreationAndReturnsToThePendingEditor():Unit=runBlocking {
        graph.savedPlaces.save(savedPlace,null)
        launch().use {
            openStoredPlace();compose.onNodeWithTag("saved-name").performTextReplacement("Pending nested place edit");closeKeyboard()
            compose.onNodeWithText(target.getString(R.string.visit_choose_location)).performScrollTo().performClick();awaitTag("place-location-content")
            resize(true);awaitTag("place-location-content");awaitPlaceMapFrame()
            resize(false);awaitTag("place-location-content");awaitPlaceMapFrame()
            compose.onNodeWithTag("place-pin-close").assertIsDisplayed().performClick();awaitTag("saved-name")
            compose.onNodeWithTag("saved-name").assertTextContains("Pending nested place edit",substring=true)
            assertEquals(savedPlace,graph.history.dao.place(savedPlace.id)!!.value)
        }
    }
    @Test fun outingNameAndNotesSurviveRealRecreationAndPreserveLatestEndMetadata():Unit=runBlocking {
        launch().use {
            openStoredOuting();compose.onNodeWithTag("outing-name").performTextReplacement("Resized outing draft")
            compose.onNodeWithTag("outing-notes").performScrollTo().performTextReplacement("Pending outing notes");closeKeyboard()
            resize(true);awaitTag("outing-name")
            compose.onNodeWithTag("outing-name").assertTextContains("Resized outing draft",substring=true)
            compose.onNodeWithTag("outing-notes").assertTextContains("Pending outing notes",substring=true)
            assertEquals(outing,graph.history.dao.session(outing.id)!!.value);capture("library-outing-wide")
            resize(false);awaitTag("outing-name")
            compose.onNodeWithTag("outing-name").assertTextContains("Resized outing draft",substring=true)
            compose.onNodeWithTag("outing-notes").assertTextContains("Pending outing notes",substring=true)
            val changedEnd=outing.endedAt!!.plusMillis(1)
            graph.history.dao.put(SessionRow(outing.copy(endedAt=changedEnd)))
            capture("library-outing-compact")
            compose.onNodeWithText(target.getString(R.string.outing_save)).assertIsDisplayed().performClick()
            compose.waitUntil(10000) {nodes("outing-name").isEmpty()}
            assertEquals(outing.copy(endedAt=changedEnd,name="Resized outing draft",notes="Pending outing notes"),graph.history.dao.session(outing.id)!!.value)
            assertEquals(2L,graph.history.dao.totalPointCount());assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        }
    }
    @Test fun stoppedAndOldResumedPlaceAndOutingSaveActionsCannotWrite():Unit=runBlocking {
        graph.savedPlaces.save(savedPlace,null)
        launch().use {scenario->
            openStoredPlace();compose.onNodeWithTag("saved-name").performTextReplacement("Unapproved place");closeKeyboard()
            val oldPlace=approval("saved-editor-save")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);instrumentation.runOnMainSync {oldPlace()}
            assertEquals(savedPlace,graph.history.dao.place(savedPlace.id)!!.value)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);awaitTag("saved-name");compose.runOnIdle {oldPlace()}
            assertEquals(savedPlace,graph.history.dao.place(savedPlace.id)!!.value)
            compose.onNodeWithTag("saved-editor-cancel").performClick()
            compose.onNodeWithText(target.getString(R.string.saved_discard_action)).performClick()
            openStoredOuting();compose.onNodeWithTag("outing-name").performTextReplacement("Unapproved outing");closeKeyboard()
            val oldOuting=compose.onNodeWithText(target.getString(R.string.outing_save)).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);instrumentation.runOnMainSync {oldOuting()}
            assertEquals(outing,graph.history.dao.session(outing.id)!!.value)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);awaitTag("outing-name");compose.runOnIdle {oldOuting()}
            assertEquals(outing,graph.history.dao.session(outing.id)!!.value)
        }
    }

}
