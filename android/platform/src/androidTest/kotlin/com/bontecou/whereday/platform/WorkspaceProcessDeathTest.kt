package com.bontecou.whereday.platform

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import java.io.File

/** Explicit-only cross-process acceptance; never instrument or kill the owner's application. */
class WorkspaceProcessDeathTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(instrumentation)
    private val qa="com.bontecou.whereday.mapqa"
    private fun shell(command:String)=device.executeShellCommand(command).trim()
    private fun node(selector:BySelector):UiObject2 = checkNotNull(device.wait(Until.findObject(selector),20_000)) {"Missing $selector"}
    private fun tag(value:String)=node(By.res(value))
    private fun text(value:String)=node(By.text(value))
    private fun capture(label:String) {
        val folder=File(instrumentation.targetContext.filesDir,"workspace-process-proof").apply {mkdirs()}
        device.dumpWindowHierarchy(File(folder,"$label.xml"))
        check(device.takeScreenshot(File(folder,"$label.png")))
    }
    // Fresh launches open the journal through the debuggable QA extra (Today is the real default); restored tasks ignore it.
    private fun launch(journal:Int?=0) {shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $qa/com.bontecou.whereday.MainActivity"+(journal?.let {" --ei com.bontecou.whereday.qa.JOURNAL $it"} ?: ""))}
    private fun restart(label:String,readyTag:String="visit-name") {
        val before=shell("pidof $qa");check(before.matches(Regex("[0-9]+")))
        device.pressHome()
        check(device.wait(Until.gone(By.pkg(qa).depth(0)),10_000)) {"QA did not leave the foreground"}
        // Wait for a real STOPPED Activity before killing; saved state belongs to Android's task.
        val deadline=SystemClock.uptimeMillis()+10_000
        val stopped=Regex("(?s)ActivityRecord\\{[^\\n]*com\\.bontecou\\.whereday\\.mapqa/com\\.bontecou\\.whereday\\.MainActivity(?:(?!ActivityRecord\\{).)*?(?:state|mState)=STOPPED")
        while(!stopped.containsMatchIn(shell("dumpsys activity activities"))) {
            check(SystemClock.uptimeMillis()<deadline) {"QA did not reach STOPPED"}
            SystemClock.sleep(100)
        }
        shell("am kill $qa")
        check(device.wait(Until.gone(By.pkg(qa)),5_000))
        val killed=SystemClock.uptimeMillis()+10_000
        while(shell("pidof $qa").isNotEmpty()) {check(SystemClock.uptimeMillis()<killed) {"QA process survived am kill"};SystemClock.sleep(100)}
        launch();tag(readyTag)
        val after=shell("pidof $qa");check(after.matches(Regex("[0-9]+")) && after!=before)
        File(instrumentation.targetContext.filesDir,"workspace-process-proof/$label-pids.txt").apply {parentFile!!.mkdirs();writeText("before=$before\nafter=$after\n")}
    }
    @Before fun requireOwnedQaAvd() {
        check(instrumentation.targetContext.packageName=="com.bontecou.whereday.platform.test")
        val args=InstrumentationRegistry.getArguments()
        check(args.getString("allowWorkspaceProcessDeath")=="true")
        check(shell("getprop ro.kernel.qemu")=="1")
        val avd=checkNotNull(args.getString("expectedProcessAvd"));check(avd.startsWith("whereday-"))
        check(shell("getprop ro.boot.qemu.avd_name")==avd)
    }
    @Test fun visitDraftSurvivesProcessDeathWithoutImplicitCommitAndNeedsFreshSave() {
        try {
            launch();tag("navigation-timeline").click();text("Process fixture visit").click()
            tag("visit-name").text="Process draft discarded"
            capture("pending-discard");restart("discard")
            assertEquals("Process draft discarded",tag("visit-name").text)
            capture("restored-discard")
            text("Cancel").click();text("Discard changes").click()
            check(device.wait(Until.gone(By.res("visit-name")),10_000))
            // Discard returns the original persisted name, proving restoration did not commit.
            text("Process fixture visit").click()
            assertEquals("Process fixture visit",tag("visit-name").text)
            tag("visit-name").text="Process restored approved"
            restart("approve")
            assertEquals("Process restored approved",tag("visit-name").text)
            capture("restored-approve")
            text("Save").click()
            check(device.wait(Until.gone(By.res("visit-name")),10_000))
            text("Process restored approved");capture("approved")
        } catch(failure:Throwable) {runCatching {capture("failure")};throw failure}
    }
    private fun scrollTo(container:String,label:String) {
        tag(container)
        check(UiScrollable(UiSelector().resourceId(container)).scrollIntoView(UiSelector().text(label))) {"Missing $label in $container"}
        text(label).click()
    }
    @Test fun selectedEmptyAndLatestTimelineDaysSurviveActualProcessDeath() {
        try {
            launch();tag("navigation-timeline").click();text("Process fixture visit")
            fun selectedDay()=checkNotNull(tag("timeline-today").findObject(By.clazz("android.widget.TextView"))).text.also {check(it.isNotBlank())}
            val latest=selectedDay()
            tag("timeline-previous").click();tag("timeline-empty-jump")
            val selected=selectedDay();assertNotEquals(latest,selected)
            capture("selected-day");restart("selected-day","timeline-content")
            tag("timeline-empty-jump");assertEquals(selected,selectedDay())
            capture("restored-selected-day")
            tag("timeline-empty-jump").click();text("Process fixture visit")
            assertEquals(latest,selectedDay());capture("latest-day")
            restart("latest-day","timeline-content");text("Process fixture visit")
            assertEquals(latest,selectedDay());capture("restored-latest-day")
        } catch(failure:Throwable) {runCatching {capture("failure")};throw failure}
    }
    @Test fun pendingTimelineDateRecoversWithoutNavigationAndNeedsFreshApply() {
        try {
            fun selectedDay()=checkNotNull(tag("timeline-today").findObject(By.clazz("android.widget.TextView"))).text.also {check(it.isNotBlank())}
            fun openIso() {
                tag("timeline-choose-date").click();tag("timeline-date-content")
                tag("timeline-date-iso").click();tag("timeline-date-input")
            }
            launch();tag("navigation-timeline").click();text("Process fixture visit")
            val original=selectedDay()
            tag("timeline-choose-date").click();tag("timeline-date-content");capture("opening-calendar")
            tag("timeline-date-iso").click();tag("timeline-date-input").text="2020-01-02"
            capture("pending-discard");restart("discard","timeline-date-input")
            assertEquals("2020-01-02",tag("timeline-date-input").text);capture("restored-discard")
            text("Cancel").click();tag("timeline-choose-date");assertEquals(original,selectedDay())
            openIso();tag("timeline-date-input").text="2019-02-03"
            restart("approve","timeline-date-input")
            assertEquals("2019-02-03",tag("timeline-date-input").text);capture("restored-approve")
            tag("timeline-date-apply").click();tag("timeline-empty-jump")
            val expected=java.time.LocalDate.of(2019,2,3).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(java.util.Locale.US))
            assertEquals(expected,selectedDay());capture("approved")
        } catch(failure:Throwable) {runCatching {capture("failure")};throw failure}
    }
    @Test fun pendingCalendarSelectionRecoversWithoutModeChangeAndNeedsFreshApply() {
        try {
            fun selectedDay()=checkNotNull(tag("timeline-today").findObject(By.clazz("android.widget.TextView"))).text.also {check(it.isNotBlank())}
            val medium=java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(java.util.Locale.US)
            launch();tag("navigation-timeline").click();text("Process fixture visit")
            val original=selectedDay();val day=java.time.LocalDate.parse(original,medium)
            val chosen=day.withDayOfMonth(if(day.dayOfMonth==15) 16 else 15)
            val headline=chosen.format(medium)
            val cell=chosen.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu",java.util.Locale.US))
            fun openAndChoose() {
                tag("timeline-choose-date").click()
                checkNotNull(tag("timeline-date-content").findObject(By.text(original))) {"A new chooser must start on its original day"}
                text(cell).click();text(headline)
                assertFalse("Calendar selection must retain calendar mode",device.hasObject(By.res("timeline-date-input")))
            }
            openAndChoose();capture("opening-calendar");capture("pending-discard")
            restart("discard","timeline-date-content");text(headline)
            assertFalse(device.hasObject(By.res("timeline-date-input")));capture("restored-discard")
            text("Cancel").click();check(device.wait(Until.gone(By.res("timeline-date-content")),10_000))
            assertEquals(original,selectedDay())
            openAndChoose();restart("approve","timeline-date-content");text(headline)
            assertFalse(device.hasObject(By.res("timeline-date-input")));capture("restored-approve")
            tag("timeline-date-apply").click()
            check(device.wait(Until.gone(By.res("timeline-date-content")),10_000))
            node(By.res("timeline-today").hasDescendant(By.text(headline)))
            assertEquals(headline,selectedDay());capture("approved")
        } catch(failure:Throwable) {runCatching {capture("failure")};throw failure}
    }
    @Test fun savedPlaceDraftAndDeletionReviewRecoverWithFreshApprovals() {
        try {
            launch(null);tag("navigation-places").click()
            tag("saved-places-content");text("Process fixture place").click();tag("place-detail-edit").click()
            tag("saved-name").text="Process place discarded"
            tag("saved-address").text="Process address discarded"
            capture("pending-discard");restart("discard","saved-name")
            assertEquals("Process place discarded",tag("saved-name").text)
            assertEquals("Process address discarded",tag("saved-address").text)
            capture("restored-discard")
            text("Cancel").click();text("Discard changes").click()
            check(device.wait(Until.gone(By.res("saved-name")),10_000))
            tag("place-detail-edit").click()
            assertEquals("Process fixture place",tag("saved-name").text)
            assertEquals("Original process address",tag("saved-address").text)
            tag("saved-name").text="Process place approved"
            tag("saved-address").text="Process address approved"
            restart("approve","saved-name")
            assertEquals("Process place approved",tag("saved-name").text)
            assertEquals("Process address approved",tag("saved-address").text)
            capture("restored-approve");text("Save").click()
            check(device.wait(Until.gone(By.res("saved-name")),10_000))
            text("Process place approved");capture("approved")
            if(device.hasObject(By.res("place-detail-back"))) tag("place-detail-back").click()
            text("Process deletion fixture place").click();tag("place-detail-edit").click()
            scrollTo("saved-editor-fields","Delete saved place")
            tag("saved-delete-confirm");capture("pending-deletion")
            restart("delete","saved-name")
            assertEquals("Process deletion fixture place",tag("saved-name").text)
            assertFalse("Deletion approval must expire with its process",device.hasObject(By.res("saved-delete-confirm")))
            capture("restored-deletion-editor")
            text("Cancel").click()
            check(device.wait(Until.gone(By.res("saved-name")),10_000))
            tag("place-detail-edit").click()
            scrollTo("saved-editor-fields","Delete saved place")
            tag("saved-delete-confirm").click()
            check(device.wait(Until.gone(By.res("saved-name")),10_000))
            assertFalse(device.hasObject(By.text("Process deletion fixture place")))
            text("Process place approved");capture("fresh-deletion-approved")
        } catch(failure:Throwable) {runCatching {capture("failure")};throw failure}
    }
    @Test fun outingNameAndNotesRecoverWithoutImplicitCommitAndNeedFreshSave() {
        try {
            launch();tag("navigation-timeline").click();text("Unchanged process fixture outing").click()
            scrollTo("outing-detail-content","Edit outing")
            tag("outing-name").text="Process outing discarded"
            tag("outing-notes").text="Process notes discarded"
            capture("pending-discard");restart("discard","outing-name")
            assertEquals("Process outing discarded",tag("outing-name").text)
            assertEquals("Process notes discarded",tag("outing-notes").text)
            capture("restored-discard");text("Cancel").click()
            check(device.wait(Until.gone(By.res("outing-name")),10_000))
            assertEquals("Unchanged process fixture outing",tag("outing-title").text)
            scrollTo("outing-detail-content","Edit outing")
            assertEquals("Unchanged process fixture outing",tag("outing-name").text)
            assertEquals("",tag("outing-notes").text)
            tag("outing-name").text="Process outing approved"
            tag("outing-notes").text="Process notes approved"
            restart("approve","outing-name")
            assertEquals("Process outing approved",tag("outing-name").text)
            assertEquals("Process notes approved",tag("outing-notes").text)
            capture("restored-approve");text("Save outing").click()
            check(device.wait(Until.gone(By.res("outing-name")),10_000))
            assertEquals("Process outing approved",tag("outing-title").text);capture("approved")
        } catch(failure:Throwable) {runCatching {capture("failure")};throw failure}
    }
}
