package com.bontecou.whereday.platform

import com.bontecou.whereday.data.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Stable UI identities; no URI, preference value or exception text is retained in feedback. */
enum class SettingsFailure {preferences,folder,folderPicker,permissions}
data class SettingsActionState(val pending:Int=0,val failure:SettingsFailure?=null,val folderSaved:Boolean=false) {
    val busy:Boolean get()=pending>0
}
class SettingsActionController(private val updatePreferences:suspend((AppSettings)->AppSettings)->Unit,
    private val selectFolder:suspend(String)->Unit) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val monitor=Any()
    private var tail:Job?=null
    private val mutable=MutableStateFlow(SettingsActionState())
    val state=mutable.asStateFlow()
    fun update(change:(AppSettings)->AppSettings)=submit(SettingsFailure.preferences) {updatePreferences(change)}
    fun folder(uri:String)=submit(SettingsFailure.folder) {selectFolder(uri)}
    private fun submit(kind:SettingsFailure,block:suspend()->Unit):Job=synchronized(monitor) {
        check(scope.isActive)
        mutable.update {it.copy(pending=it.pending+1,failure=null,folderSaved=false)}
        val previous=tail
        scope.launch {
            try {
                previous?.join();block()
                mutable.update {it.copy(folderSaved=kind==SettingsFailure.folder)}
            } catch(error:CancellationException) {throw error}
            catch(_:Exception) {mutable.update {it.copy(failure=kind,folderSaved=false)}}
            finally {mutable.update {it.copy(pending=(it.pending-1).coerceAtLeast(0))}}
        }.also {tail=it}
    }
    fun reportFailure(value:SettingsFailure) {mutable.update {it.copy(failure=value,folderSaved=false)}}
    fun dismiss() {mutable.update {it.copy(failure=null,folderSaved=false)}}
    internal suspend fun close() {scope.coroutineContext.job.cancelAndJoin()}
}
