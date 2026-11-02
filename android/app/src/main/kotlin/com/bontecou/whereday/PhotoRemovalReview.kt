package com.bontecou.whereday

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

/** Each confirmation owns its approval, independently of the longer-lived library screen. */
@Composable internal fun PhotoRemovalReview(busy:Boolean,dismiss:()->Unit,approve:()->Unit) {
    val actions=rememberVisibleEditorActions();val ticket=actions.epoch
    WorkspaceAlertDialog(onDismissRequest={if(actions.allows(ticket) && !busy) dismiss()},
        title={Text(stringResource(R.string.photo_remove_question))},
        text={Text(stringResource(R.string.photo_remove_description))},
        confirmButton={key(ticket) {TextButton(modifier=Modifier.testTag("photo-remove-confirm"),enabled=!busy,onClick={if(actions.allows(ticket) && !busy) approve()}) {Text(stringResource(R.string.photo_remove))}}},
        dismissButton={key(ticket) {TextButton(modifier=Modifier.testTag("photo-remove-cancel"),enabled=!busy,onClick={if(actions.allows(ticket) && !busy) dismiss()}) {Text(stringResource(R.string.visit_cancel))}}})
}
