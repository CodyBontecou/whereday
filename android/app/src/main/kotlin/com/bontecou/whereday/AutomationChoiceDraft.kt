package com.bontecou.whereday

import androidx.compose.runtime.saveable.listSaver
import com.bontecou.whereday.platform.*

/** A restored draft is display-only and valid only for its saved installation/approval/reset binding. */
internal data class AutomationChoiceDraft(val binding:String,val scopes:Set<AutomationScope>) {
    companion object {
        val Saver=listSaver<AutomationChoiceDraft,String>(
            save={listOf("1",it.binding)+it.scopes.sortedBy {scope->scope.ordinal}.map {scope->scope.name}},
            restore={values->
                val names=values.drop(2)
                if(values.size !in 2..(2+AutomationScope.entries.size) || values[0]!="1" || values[1].length>4096 || names.distinct().size!=names.size || names.any {name->AutomationScope.entries.none {it.name==name}}) null
                else AutomationChoiceDraft(values[1],names.map {AutomationScope.valueOf(it)}.toSet())
            },
        )
    }
}
internal fun automationBinding(state:AutomationUiState):String {
    val identity=state.installed
    val installation=identity?.let {"${it.packageName}|${it.uid}|${it.firstInstalled}|${it.signers.sorted().joinToString(",")}"} ?: "absent"
    return "$installation|${state.approval?.revision.orEmpty()}|${state.resetRevision}"
}
internal fun automationBaseline(state:AutomationUiState)=state.approval?.takeIf {it.identity==state.installed}?.scopes.orEmpty()
internal data class AutomationReviewKey(val binding:String,val revision:Long,val failure:AutomationFailure?)
internal fun automationReviewKey(state:AutomationUiState)=AutomationReviewKey(automationBinding(state),state.consentRevision,state.failure)
