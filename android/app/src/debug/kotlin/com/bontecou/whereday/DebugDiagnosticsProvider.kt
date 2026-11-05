package com.bontecou.whereday

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import com.bontecou.whereday.platform.AppGraph
import com.bontecou.whereday.platform.RecordingRuntime
import com.bontecou.whereday.platform.geographicTimeZones
import com.bontecou.whereday.domain.Coordinate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.Instant

/** Debug-only ADB diagnostic. Exposes aggregate counts, never coordinates, text or credentials. */
class DebugDiagnosticsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        check(Binder.getCallingUid() == 2000) { "Diagnostics are available only to the ADB shell" }
        if(uri.path=="/geography") return runBlocking(Dispatchers.IO) {
            val graph=AppGraph.from(requireNotNull(context))
            check(!RecordingRuntime.active.value && !graph.countryAnalysis.state.value.running) { "Stop active work before the offline memory probe" }
            val lookup=geographicTimeZones(requireNotNull(context))
            val start=android.os.SystemClock.elapsedRealtime()
            try {
                lookup.prepare()
                // Fixed public fixtures only. No history reads or geocoder/network calls.
                lookup.query(Coordinate(40.7128,-74.0060))
                lookup.query(Coordinate(55.7558,37.6173))
                val runtime=Runtime.getRuntime()
                val memory=android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                MatrixCursor(arrayOf("load_ms","java_heap_used_bytes","java_heap_max_bytes","total_pss_kb")).apply {
                    addRow(arrayOf<Any>(android.os.SystemClock.elapsedRealtime()-start,runtime.totalMemory()-runtime.freeMemory(),runtime.maxMemory(),memory.totalPss))
                }
            } finally { lookup.release() }
        }
        require(uri.path == "/recording")
        val dao = AppGraph.from(requireNotNull(context)).history.dao
        return runBlocking(Dispatchers.IO) {
            val state = dao.recording()
            val age = dao.latestPointTime()?.let { (Instant.now().toEpochMilli() - it.toEpochMilli()) / 1000 }
            MatrixCursor(arrayOf("points", "visits", "open_sessions", "requested", "runtime_active", "latest_fix_age_seconds")).apply {
                addRow(arrayOf(dao.totalPointCount(), dao.totalVisitCount(), dao.openSessionCount(), if(state?.requested == true) 1 else 0, if(RecordingRuntime.active.value) 1 else 0, age))
            }
        }
    }
    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.whereday.diagnostics"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
