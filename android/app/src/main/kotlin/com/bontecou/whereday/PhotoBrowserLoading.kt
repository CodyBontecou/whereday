package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Keep a pending opening modal while fresh metadata is checked; old loading controls expire. */
@Composable internal fun PhotoBrowserLoading(close:()->Unit) {
    val actions=rememberVisibleEditorActions();val ticket=actions.epoch
    WorkspaceDialog(fillRegion=true,onDismissRequest={if(actions.allows(ticket)) close()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=wd.surface,contentColor=wd.textPrimary) {
            Column(Modifier.fillMaxSize().testTag("photo-browser-loading").padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                key(ticket) {WdBackChip(stringResource(R.string.photo_places_close),{if(actions.allows(ticket)) close()})}
                Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.Center) {
                    WdLoading()
                    Text(stringResource(R.string.photo_places_loading),Modifier.fillMaxWidth().padding(horizontal=24.dp),style=WdType.caption,color=wd.textMuted,textAlign=TextAlign.Center)
                }
            }
        }
    }
}
