package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Progress remains cancellable even when large text needs to scroll on a short window. */
@Composable internal fun AutomationCommandContent(cancel:()->Unit) {
    Box(Modifier.fillMaxSize().background(wd.surface)) {
        FieldNotesBackground(cloudsOpacity=0.18f,streetOpacity=0.22f)
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).testTag("automation-command-screen"),
            verticalArrangement=Arrangement.spacedBy(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("automation-command-body"),
                verticalArrangement=Arrangement.spacedBy(16.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(wd.card).border(1.dp,wd.border,CircleShape),contentAlignment=Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(32.dp),color=wd.accent,trackColor=wd.dialTrack,strokeWidth=2.5.dp)
                }
                Text(stringResource(R.string.automation_command_title),style=WdType.title2,color=wd.textPrimary,textAlign=TextAlign.Center,modifier=Modifier.semantics {heading()})
                Text(stringResource(R.string.automation_command_help),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted,textAlign=TextAlign.Center)
            }
            WdButton(stringResource(R.string.automation_command_cancel),cancel,Modifier.testTag("automation-command-cancel"))
        }
    }
}
