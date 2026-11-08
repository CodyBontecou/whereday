package com.bontecou.whereday.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Instant
import java.util.Collections

/** Fixed messages only: callers cannot put coordinates, URLs, credentials or exceptions in logs. */
enum class AppEvent(val level:String,val message:String) {
    appOpened("INFO","App opened."),
    reviewUnavailable("INFO","Google Play review request was unavailable."),
    recordingStarted("INFO","Location recording started."),
    recordingStopped("INFO","Location recording stopped."),
    recordingInterrupted("WARN","Location recording was interrupted. Check the recording screen."),
    recordingStartFailed("ERROR","Location recording could not start. Check permissions and retry."),
    recordingStopFailed("ERROR","Location recording could not stop. Check its current status."),
    automationCompleted("INFO","Automation command completed."),
    automationFailed("WARN","Automation command did not return a result. Check access and journal state."),
    journalClearFailed("ERROR","Journal data could not be completely cleared. Try again."),
    visitReconciliationFailed("ERROR","Visit history could not be updated. Try reopening the app."),
    locationReconciliationFailed("ERROR","Interrupted recordings could not be checked. Try reopening the app.")
}

data class ActivityLogEntry(val id:Long,val time:Instant,val event:AppEvent) {
    val display:String get()="[$time] ${event.level}: ${event.message}"
}

/** Process-local, matching iOS retention. No disk, Logcat or automatic network upload. */
class ActivityLog(private val capacity:Int=500,private val clock:Clock=Clock.systemUTC()) {
    init {require(capacity in 1..500)}
    private val monitor=Any()
    private var nextId=0L
    private val mutable=MutableStateFlow<List<ActivityLogEntry>>(emptyList())
    val entries=mutable.asStateFlow()
    fun record(event:AppEvent)=synchronized(monitor) {
        val entry=ActivityLogEntry(++nextId,clock.instant(),event)
        mutable.value=Collections.unmodifiableList((mutable.value.takeLast(capacity-1)+entry))
    }
    fun clear()=synchronized(monitor) {mutable.value=emptyList()}
    fun snapshot():List<ActivityLogEntry> = mutable.value
    fun exportText():String = snapshot().joinToString("\n") {it.display}
    companion object {
        // Logging an early service rejection must not initialize Room, billing or background work.
        val process=ActivityLog()
    }
}
