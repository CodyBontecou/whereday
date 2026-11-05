package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading

internal val LocalInlineVisitEditor=staticCompositionLocalOf {false}

/** Move the same field composition between pane and dialog so nested timestamp drafts keep their owner. */
@Composable internal fun VisitEditorFrame(inline:Boolean,backLabel:Int,onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,confirmButton:@Composable ()->Unit,dismissButton:@Composable ()->Unit) {
    val fieldStates=rememberSaveableStateHolder()
    val fields=rememberWorkspaceContent {fieldStates.SaveableStateProvider("visit-fields") {text()}}
    if(inline) Surface(Modifier.fillMaxSize().testTag("visit-inline-inspector"),color=wd.surface,contentColor=wd.textPrimary) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,top=8.dp)) {
                WdBackChip(stringResource(backLabel),onDismissRequest,Modifier.testTag("visit-inspector-back"))
            }
            Box(Modifier.padding(start=20.dp,end=20.dp,top=14.dp,bottom=10.dp).semantics(mergeDescendants=true) {heading()}) {
                CompositionLocalProvider(LocalContentColor provides wd.textPrimary) {ProvideTextStyle(WdType.title) {title()}}
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal=20.dp)) {fields()}
            WdDivider()
            // Cancel on paper, Save as the full ink slab, as iOS pins its primary action under the form.
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {dismissButton()}
                Box(Modifier.weight(1f)) {confirmButton()}
            }
        }
    } else WorkspaceAlertDialog(onDismissRequest=onDismissRequest,title=title,text={fields()},confirmButton=confirmButton,dismissButton=dismissButton)
}
