package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading

internal val LocalInlineVisitEditor=staticCompositionLocalOf {false}

/** Move the same field composition between pane and dialog so nested timestamp drafts keep their owner. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun VisitEditorFrame(inline:Boolean,backLabel:Int,onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,confirmButton:@Composable ()->Unit,dismissButton:@Composable ()->Unit) {
    val fieldStates=rememberSaveableStateHolder()
    val fields=rememberWorkspaceContent {fieldStates.SaveableStateProvider("visit-fields") {text()}}
    if(inline) Surface(Modifier.fillMaxSize().testTag("visit-inline-inspector")) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            TextButton(onClick=onDismissRequest,modifier=Modifier.testTag("visit-inspector-back")) {Text(stringResource(backLabel))}
            Box(Modifier.semantics(mergeDescendants=true) {heading()}) {ProvideTextStyle(MaterialTheme.typography.headlineSmall) {title()}}
            Box(Modifier.weight(1f).fillMaxWidth()) {fields()}
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,androidx.compose.ui.Alignment.End),verticalArrangement=Arrangement.spacedBy(4.dp)) {dismissButton();confirmButton()}
        }
    } else WorkspaceAlertDialog(onDismissRequest=onDismissRequest,title=title,text={fields()},confirmButton=confirmButton,dismissButton=dismissButton)
}
