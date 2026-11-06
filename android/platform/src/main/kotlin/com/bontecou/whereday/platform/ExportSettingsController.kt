package com.bontecou.whereday.platform

import com.bontecou.whereday.data.AppPreferences
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

enum class ExportSettingsFailure(val diagnostic:String) {
    restore("Saved export settings could not be restored. Reset them and review filters before exporting."),
    save("Export settings could not be saved. Retry before exporting.")
}
data class ExportSettingsState(val selection:ExportSelection=ExportSelection(),val ready:Boolean=false,val saving:Boolean=false,val failure:ExportSettingsFailure?=null,val revision:Long=0) {
    val error:String? get()=failure?.diagnostic
}

/** Application-owned writes survive screen disposal and coalesce rapid edits without resetting other settings. */
class ExportSettingsController(private val preferences:AppPreferences) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(ExportSettingsState())
    val state=mutable.asStateFlow()
    private val writes=Channel<Pair<Long,ExportSelection>>(Channel.CONFLATED)
    private val editLock=Any()
    init {
        scope.launch {
            try {mutable.value=ExportSettingsState(selection=ExportSelectionCodec.decode(preferences.settings.first().exportConfiguration),ready=true)}
            catch(e:Exception) {if(e is CancellationException) throw e;mutable.value=ExportSettingsState(failure=ExportSettingsFailure.restore)}
            for((revision,selection) in writes) {
                try {
                    preferences.update {it.copy(exportConfiguration=ExportSelectionCodec.encode(selection))}
                    mutable.update {if(it.revision==revision) it.copy(ready=true,saving=false,failure=null) else it}
                } catch(e:Exception) {
                    if(e is CancellationException) throw e
                    mutable.update {if(it.revision==revision) it.copy(saving=false,failure=ExportSettingsFailure.save) else it}
                }
            }
        }
    }
    fun update(selection:ExportSelection) {
        if(!mutable.value.ready) return
        enqueue(selection)
    }
    fun reset()=enqueue(ExportSelection())
    fun retry()=enqueue(mutable.value.selection)
    private fun enqueue(selection:ExportSelection)=synchronized(editLock) {
        val next=mutable.value.copy(selection=selection,saving=true,failure=null,revision=mutable.value.revision+1)
        mutable.value=next
        writes.trySend(next.revision to selection)
        Unit
    }
    internal fun close() {scope.cancel()}
}
