package com.bontecou.whereday

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.bontecou.whereday.platform.*

/** A completed deletion must never restore private drafts from the preceding journal generation. */
@Composable internal fun JournalReadyState(clear:JournalClearState,content:@Composable ()->Unit) {
    val holder=rememberSaveableStateHolder()
    val key="ready-journal:${clear.generation ?: "initial"}"
    var previous by rememberSaveable {mutableStateOf(key)}
    LaunchedEffect(key,clear.phase) {
        if(clear.phase!=JournalClearPhase.checking && (!clear.restricted || clear.generation!=null) && key!=previous) {
            holder.removeState(previous)
            previous=key
        }
    }
    if(!clear.blocked) holder.SaveableStateProvider(key,content)
}
