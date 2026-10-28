package com.bontecou.whereday.platform

import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ExportPickerUiState(val loaded:Boolean=false,val request:ExportPickerState?=null,val busy:Boolean=false,val message:String?=null)

/** Keeps a received picker result alive when its composable/activity is disposed. */
class ExportPickerController(private val files:ExportFiles) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val serial=Mutex()
    private val mutable=MutableStateFlow(ExportPickerUiState())
    val state=mutable.asStateFlow()
    init {refresh()}
    fun refresh()=run {files.pickerState()}
    fun result(id:String?,uri:Uri?,flags:Int)=run {files.pickerResult(id,uri,flags)}
    fun resume(id:String)=run {files.resumePicker(id)}
    fun cancel(id:String) {scope.coroutineContext.cancelChildren();run {files.cancelPicker(id);files.pickerState()}}
    fun discardInterrupted() {scope.coroutineContext.cancelChildren();run {files.clearCache();null}}
    private fun run(action:suspend ()->ExportPickerState?) {
        scope.launch {
            serial.withLock {
                mutable.update {it.copy(busy=true,message=null)}
                try {
                    val result=action()
                    mutable.value=ExportPickerUiState(true,result,false,when(result?.phase) {
                        ExportPickerPhase.completed -> "Export saved and verified."
                        ExportPickerPhase.canceled -> "Export canceled. Files already written remain in the destination."
                        else -> result?.error
                    })
                } catch(e:Exception) {
                    withContext(NonCancellable) {
                        val restored=runCatching {files.pickerState()}.getOrNull()
                        mutable.value=ExportPickerUiState(true,restored,false,if(e is CancellationException) "Saving was interrupted. Resume or cancel the saved request." else e.message ?: "Saved export could not be restored. Discard the interrupted request or try again.")
                    }
                    if(e is CancellationException) throw e
                }
            }
        }
    }
    internal fun close() {scope.cancel()}
}
