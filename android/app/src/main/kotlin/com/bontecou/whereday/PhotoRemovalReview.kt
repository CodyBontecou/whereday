package com.bontecou.whereday

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Each confirmation owns its approval, independently of the longer-lived library screen. */
@Composable internal fun PhotoRemovalReview(busy:Boolean,dismiss:()->Unit,approve:()->Unit) {
    val actions=rememberVisibleEditorActions();val ticket=actions.epoch
    WorkspaceAlertDialog(onDismissRequest={if(actions.allows(ticket) && !busy) dismiss()},
        title={Text(stringResource(R.string.photo_remove_question),style=WdType.title2)},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.photo_remove_description),color=wd.textPrimary)
            if(busy) PlacesProgress(inset=false)
        }},
        confirmButton={key(ticket) {TextButton(modifier=Modifier.testTag("photo-remove-confirm"),enabled=!busy,onClick={if(actions.allows(ticket) && !busy) approve()}) {Text(stringResource(R.string.photo_remove),color=if(busy) wd.textMuted else wd.inkRust,fontWeight=FontWeight.SemiBold)}}},
        dismissButton={key(ticket) {TextButton(modifier=Modifier.testTag("photo-remove-cancel"),enabled=!busy,onClick={if(actions.allows(ticket) && !busy) dismiss()}) {Text(stringResource(R.string.visit_cancel))}}})
}
