package com.bontecou.whereday.platform

import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ExportPickerUiState(val loaded:Boolean=false,val request:ExportPickerState?=null,val busy:Boolean=false,val notice:ExportDeliveryNotice?=null) {
    val message:String? get()=notice?.diagnostic
}

/** Keeps a received picker result alive when its composable/activity is disposed. */
class ExportPickerController(private val files:ExportFiles) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val serial=Mutex()
    private val mutable=MutableStateFlow(ExportPickerUiState())
    val state=mutable.asStateFlow()
    init {refresh()}
    fun refresh()=run {files.pickerState()}
    fun result(id:String?,uri:Uri?,flags:Int)=run {files.pickerResult(id,uri,flags) ?: files.pickerState()}
    fun resume(id:String)=run {files.resumePicker(id)}
    fun cancel(id:String) {scope.coroutineContext.cancelChildren();run {files.cancelPicker(id);files.pickerState()}}
    fun discardInterrupted() {scope.coroutineContext.cancelChildren();run {files.clearCache();null}}
    private fun run(action:suspend ()->ExportPickerState?):Job = scope.launch {
        serial.withLock {
            mutable.update {it.copy(busy=true,notice=null)}
            try {
                val result=action()
                mutable.value=ExportPickerUiState(true,result,false,when(result?.phase) {
                    ExportPickerPhase.completed -> ExportDeliveryNotice.saved
                    ExportPickerPhase.canceled -> ExportDeliveryNotice.canceled
                    ExportPickerPhase.writing -> ExportDeliveryNotice.interrupted
                    ExportPickerPhase.failed -> result.notice ?: ExportDeliveryNotice.failed
                    else -> if(result?.error!=null) ExportDeliveryNotice.failed else null
                })
            } catch(e:Exception) {
                withContext(NonCancellable) {
                    val restored=runCatching {files.pickerState()}.getOrNull()
                    val fallback=when(restored?.phase) {
                        ExportPickerPhase.canceled->ExportDeliveryNotice.canceled
                        ExportPickerPhase.failed->restored.notice ?: ExportDeliveryNotice.failed
                        else->ExportDeliveryNotice.unavailable
                    }
                    mutable.value=ExportPickerUiState(true,restored,false,exportDeliveryNotice(e,fallback))
                }
                if(e is CancellationException) throw e
            }
        }
    }
    suspend fun close() {scope.coroutineContext.job.cancelAndJoin()}
}
