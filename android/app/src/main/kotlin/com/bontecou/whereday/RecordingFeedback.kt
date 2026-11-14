package com.bontecou.whereday

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bontecou.whereday.domain.RecordingInterruption

@Composable internal fun recordingInterruptionText(stored:String):String=stringResource(when(RecordingInterruption.decode(stored)) {
    RecordingInterruption.historyUnavailable->R.string.recording_interruption_history
    RecordingInterruption.locationUnavailable->R.string.recording_interruption_location
    RecordingInterruption.permissionRemoved->R.string.recording_interruption_permission
    RecordingInterruption.startInterrupted->R.string.recording_interruption_start
    RecordingInterruption.startFailed->R.string.recording_interruption_start_failed
    RecordingInterruption.processInterrupted->R.string.recording_interruption_process
    RecordingInterruption.phoneRestarted->R.string.recording_interruption_reboot
    null->R.string.recording_interruption_unknown
})
