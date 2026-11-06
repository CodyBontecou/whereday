package com.bontecou.whereday.domain

/** Stable private-state reasons; English diagnostics from earlier installs remain readable. */
enum class RecordingInterruption(val diagnostic:String) {
    historyUnavailable("Recording stopped because history could not be saved."),
    locationUnavailable("Location unavailable. Check location permission and try again."),
    permissionRemoved("Location permission was removed."),
    startInterrupted("Recording start was interrupted."),
    startFailed("Recording could not start. Please try again."),
    processInterrupted("Recording was interrupted. Start again when you are ready."),
    phoneRestarted("Your phone restarted. Open Whereday to resume recording.");
    val code:String get()="recording.interruption.v1.$name"
    companion object {
        fun decode(value:String):RecordingInterruption?=entries.firstOrNull {value==it.code || value==it.diagnostic}
    }
}
