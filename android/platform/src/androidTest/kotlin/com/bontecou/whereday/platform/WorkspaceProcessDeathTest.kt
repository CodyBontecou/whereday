package com.bontecou.whereday.platform

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Test
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
    private fun launch() {shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $qa/com.bontecou.whereday.MainActivity")}
    private fun restart(label:String) {
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
        launch();tag("visit-name")
        val after=shell("pidof $qa");check(after.matches(Regex("[0-9]+")) && after!=before)
        File(instrumentation.targetContext.filesDir,"workspace-process-proof/$label-pids.txt").apply {parentFile!!.mkdirs();writeText("before=$before\nafter=$after\n")}
    }
    @Test fun visitDraftSurvivesProcessDeathWithoutImplicitCommitAndNeedsFreshSave() {
        check(instrumentation.targetContext.packageName=="com.bontecou.whereday.platform.test")
        val args=InstrumentationRegistry.getArguments()
        check(args.getString("allowWorkspaceProcessDeath")=="true")
        check(shell("getprop ro.kernel.qemu")=="1")
        val avd=checkNotNull(args.getString("expectedProcessAvd"));check(avd.startsWith("whereday-"))
        check(shell("getprop ro.boot.qemu.avd_name")==avd)
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
}
