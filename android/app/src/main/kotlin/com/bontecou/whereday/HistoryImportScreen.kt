package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.ui.Alignment
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.bontecou.whereday.export.HistoryImportSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.platform.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable internal fun HistoryImportScreen(controller:HistoryImportController) {
    val state by controller.state.collectAsStateWithLifecycle()
    HistoryImportForm(state,controller::cancel,controller::discard) {id,policy,selection->controller.commit(id,policy,selection)}
}
@Composable internal fun HistoryImportForm(state:HistoryImportState,cancel:()->Unit,discard:()->Unit,commit:(String,HistoryImportPolicy,HistoryImportSelection)->Unit) {
    BlockVisitLinks()
    BackHandler {if(state.busy) cancel() else discard()}
    var replace by remember(state.review?.id) {mutableStateOf(false)}
    var confirming by remember(state.review?.id) {mutableStateOf(false)}
    var includeVisits by rememberSaveable(state.review?.id) {mutableStateOf((state.review?.visits ?: 0)>0)}
    var includePoints by rememberSaveable(state.review?.id) {mutableStateOf((state.review?.points ?: 0)>0)}
    val selection=HistoryImportSelection(includeVisits,includePoints)
    val selected=(includeVisits && (state.review?.visits ?: 0)>0) || (includePoints && (state.review?.points ?: 0)>0)
    Column(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("history-import-screen")
        .verticalScroll(rememberScrollState()).padding(bottom=24.dp)) {
        WdPageHeader(stringResource(R.string.history_import_title),eyebrow=stringResource(R.string.export_redesign_import_eyebrow),compact=true)
        Column(Modifier.exportInset().padding(top=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            when(state.phase) {
                HistoryImportPhase.reading->ExportBusyLine(stringResource(R.string.history_import_reading))
                HistoryImportPhase.applying->ExportBusyLine(stringResource(R.string.history_import_applying))
                HistoryImportPhase.canceling->ExportBusyLine(stringResource(R.string.history_import_canceling))
                HistoryImportPhase.canceled->ExportCallout(stringResource(R.string.history_import_canceled))
                HistoryImportPhase.complete->state.counts?.let {ExportCallout(stringResource(R.string.history_import_complete,pluralStringResource(R.plurals.history_import_visit_count,it.visits,it.visits),pluralStringResource(R.plurals.history_import_point_count,it.points,it.points),pluralStringResource(R.plurals.history_import_kept_count,it.kept,it.kept)),textModifier=Modifier.testTag("history-import-result"))}
                HistoryImportPhase.failed->ExportCallout(state.error ?: stringResource(R.string.history_import_failed),warning=true)
                else->Unit
            }
        }
        if(state.phase==HistoryImportPhase.review) state.review?.let {review->
            ExportBody(stringResource(R.string.history_import_unchanged),Modifier.padding(horizontal=24.dp))
            val sourceLabel=when(review.source) {
                HistoryImportSource.whereday->review.format.name.uppercase(Locale.ROOT)
                HistoryImportSource.googleRaw->stringResource(R.string.history_import_google_raw)
                HistoryImportSource.googleSemantic->stringResource(R.string.history_import_google_semantic)
                HistoryImportSource.googleDevice->stringResource(R.string.history_import_google_device)
            }
            val dates=remember(review.locale,review.zone) {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(review.locale)).withZone(ZoneId.of(review.zone))}
            val language=Locale.forLanguageTag(review.locale).getDisplayName(Locale.getDefault())
            val zoneName=ZoneId.of(review.zone).getDisplayName(java.time.format.TextStyle.FULL,Locale.getDefault())
            WdSectionHeader(stringResource(R.string.export_redesign_section_review))
            ExportCard {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.history_import_summary,sourceLabel,pluralStringResource(R.plurals.history_import_visit_count,review.visits,review.visits),pluralStringResource(R.plurals.history_import_point_count,review.points,review.points)),Modifier.testTag("history-import-summary"),style=WdType.title3,color=wd.textPrimary)
                    review.from?.let {Text(stringResource(R.string.history_import_dates,dates.format(it),dates.format(review.until ?: it),zoneName),style=WdType.caption,color=wd.textMuted)}
                    if(review.format==com.bontecou.whereday.export.ExportFormat.markdown) Text(stringResource(R.string.history_import_context,language,zoneName),style=WdType.caption2,color=wd.textMuted)
                    WdDivider()
                    Text(stringResource(if(review.source==HistoryImportSource.whereday) R.string.history_import_existing else R.string.history_import_google_existing,review.existing,review.repeated),Modifier.testTag("history-import-existing"),style=MaterialTheme.typography.bodyMedium,color=wd.textPrimary)
                    if(review.unsupported>0) Text(pluralStringResource(R.plurals.history_import_unsupported,review.unsupported,review.unsupported),style=MaterialTheme.typography.bodyMedium,color=wd.warning)
                }
            }
            if(review.visits>0 || review.points>0) {
                WdSectionHeader(stringResource(R.string.export_redesign_section_include))
                ExportCard {
                    if(review.visits>0) ImportSelectionRow(stringResource(R.string.history_import_select_visits),includeVisits,"history-import-select-visits",divider=review.points>0) {includeVisits=it}
                    if(review.points>0) ImportSelectionRow(stringResource(R.string.history_import_select_points),includePoints,"history-import-select-points",divider=false) {includePoints=it}
                }
            }
            WdSectionFooter(stringResource(R.string.history_import_selection_counts))
            if(review.rejected>0) {
                WdSectionHeader(stringResource(R.string.export_redesign_section_problems))
                ExportCallout(stringResource(R.string.history_import_rejected,review.rejected),Modifier.exportInset(),warning=true) {
                    review.errors.forEach {Text(it,style=WdType.caption2,color=wd.textMuted)}
                }
            }
            if(review.replaceable>0 && review.source==HistoryImportSource.whereday) {
                WdSectionHeader(stringResource(R.string.history_import_duplicates))
                ExportCard {
                    Text(stringResource(R.string.history_import_replaceable,review.replaceable),Modifier.padding(horizontal=16.dp).padding(top=14.dp,bottom=6.dp),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                    ImportPolicyRow(stringResource(R.string.history_import_keep),!replace,"history-import-keep") {replace=false}
                    ImportPolicyRow(stringResource(R.string.history_import_replace),replace,"history-import-replace",divider=false) {replace=true}
                }
            }
            WdButton(stringResource(R.string.history_import_confirm),{if(selected) {if(replace && review.source==HistoryImportSource.whereday && review.replaceable>0) confirming=true else commit(review.id,HistoryImportPolicy.keepExisting,selection)}},
                Modifier.exportInset().padding(top=20.dp),emphasis=if(replace && review.source==HistoryImportSource.whereday && review.replaceable>0) WdEmphasis.destructive else WdEmphasis.primary,enabled=selected,icon=Icons.Outlined.FileDownload)
        }
        Column(Modifier.exportInset().padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(state.busy) {
                ExportProgress()
                WdButton(stringResource(R.string.history_import_cancel),cancel,enabled=state.phase!=HistoryImportPhase.canceling,emphasis=WdEmphasis.destructive)
            } else WdButton(stringResource(if(state.phase==HistoryImportPhase.review) R.string.history_import_discard else R.string.history_import_done),discard,
                emphasis=if(state.phase==HistoryImportPhase.review) WdEmphasis.secondary else WdEmphasis.primary)
        }
        WdSectionFooter(stringResource(R.string.history_import_lifetime))
    }
    if(confirming && state.phase==HistoryImportPhase.review) WorkspaceAlertDialog(onDismissRequest={confirming=false},title={Text(stringResource(R.string.history_import_replace_title))},
        text={Text(stringResource(R.string.history_import_replace_body))},
        confirmButton={TextButton(onClick={confirming=false;state.review?.takeIf {selected && it.source==HistoryImportSource.whereday && it.replaceable>0}?.let {commit(it.id,HistoryImportPolicy.replaceExisting,selection)}}) {Text(stringResource(R.string.history_import_replace_confirm))}},
        dismissButton={TextButton(onClick={confirming=false}) {Text(stringResource(R.string.history_import_back))}})
}

@Composable private fun ImportPolicyRow(label:String,selected:Boolean,id:String,divider:Boolean=true,choose:()->Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min=52.dp).testTag(id).selectable(selected,role=Role.RadioButton,onClick=choose).padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            RadioButton(selected,onClick=null,colors=RadioButtonDefaults.colors(selectedColor=wd.accent,unselectedColor=wd.textMuted));Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
        }
        if(divider) WdDivider(Modifier.padding(start=16.dp))
    }
}

@Composable private fun ImportSelectionRow(label:String,selected:Boolean,id:String,divider:Boolean=true,choose:(Boolean)->Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min=52.dp).testTag(id).toggleable(selected,role=Role.Checkbox,onValueChange=choose).padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Checkbox(selected,onCheckedChange=null,colors=CheckboxDefaults.colors(checkedColor=wd.accent,uncheckedColor=wd.textMuted,checkmarkColor=wd.surface));Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
        }
        if(divider) WdDivider(Modifier.padding(start=16.dp))
    }
}
