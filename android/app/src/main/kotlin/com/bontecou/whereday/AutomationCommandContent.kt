package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Progress remains cancellable even when large text needs to scroll on a short window. */
@Composable internal fun AutomationCommandContent(cancel:()->Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).testTag("automation-command-screen"),
            verticalArrangement=Arrangement.spacedBy(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("automation-command-body"),
                verticalArrangement=Arrangement.spacedBy(20.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(stringResource(R.string.automation_command_title),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.automation_command_help))
            }
            TextButton(onClick=cancel,modifier=Modifier.testTag("automation-command-cancel")) {Text(stringResource(R.string.automation_command_cancel))}
        }
    }
}
