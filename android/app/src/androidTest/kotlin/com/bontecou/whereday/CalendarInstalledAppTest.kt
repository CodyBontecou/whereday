package com.bontecou.whereday

import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.CalendarEventDraft
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

/** Explicit local acceptance fixture: requires installed Google Calendar on a disposable AVD.
 * Opens and cancels only; it never activates Add/Save or configures a calendar account. */
class CalendarInstalledAppTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val target get()=instrumentation.targetContext
    private fun activePackage():String?=instrumentation.uiAutomation.rootInActiveWindow?.let {root->try {root.packageName?.toString()} finally {root.recycle()}}
    private fun texts():List<String> {
        val root=instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val result=mutableListOf<String>();var visited=0
        fun walk(node:AccessibilityNodeInfo) {
            if(visited++>=256) return
            node.text?.toString()?.let(result::add)
            for(index in 0 until node.childCount) node.getChild(index)?.let {child->try {walk(child)} finally {child.recycle()}}
        }
        try {walk(root)} finally {root.recycle()};return result
    }
    private fun discardIfPresented() {
        val root=instrumentation.uiAutomation.rootInActiveWindow ?: return
        try {
            for(label in listOf("Discard","Discard changes","Descartar","Descartar cambios")) {
                val nodes=root.findAccessibilityNodeInfosByText(label)
                try {for(node in nodes) if(node.text?.toString()==label && node.isClickable) {node.performAction(AccessibilityNodeInfo.ACTION_CLICK);return}}
                finally {nodes.forEach {it.recycle()}}
            }
        } finally {root.recycle()}
    }
    @Test fun installedCalendarReceivesTheDraftAndBackReturnsWithoutSaving() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        val now=Instant.now();val draft=CalendarEventDraft("Whereday synthetic calendar draft",now,now.plusSeconds(120),"Synthetic QA location")
        compose.setContent {MaterialTheme {Surface(Modifier.fillMaxSize()) {Box(Modifier.safeDrawingPadding().padding(20.dp)) {
            CalendarEventButton("installed-calendar",makeDraft={draft})
        }}}}
        compose.onNodeWithTag("calendar-add").performClick()
        try {
            compose.waitUntil(10000) {activePackage()=="com.google.android.calendar"}
            instrumentation.uiAutomation.waitForIdle(700,5000)
            val visible=texts()
            val values=android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-calendar-installed-20261101.txt")
                put(android.provider.MediaStore.Downloads.MIME_TYPE,"text/plain");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
            }
            val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
            target.contentResolver.openOutputStream(uri)!!.bufferedWriter().use {it.write(visible.joinToString("\n"))}
            assertTrue("Installed calendar must expose a visible screen",visible.isNotEmpty())
        } finally {
            repeat(4) {
                if(activePackage()!=target.packageName) {
                    discardIfPresented()
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    compose.waitUntil(3000) {activePackage()==target.packageName || activePackage()=="com.google.android.calendar"}
                }
            }
        }
        compose.waitUntil(10000) {activePackage()==target.packageName}
        compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("calendar-add").assertIsDisplayed().assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("calendar-add").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("calendar-error").assertDoesNotExist()
    }
}
