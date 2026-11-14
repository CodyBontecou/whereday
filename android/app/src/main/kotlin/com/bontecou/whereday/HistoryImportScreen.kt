package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
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
    Column(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("history-import-screen")
        .verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.history_import_title),style=MaterialTheme.typography.headlineMedium)
        when(state.phase) {
            HistoryImportPhase.reading->Text(stringResource(R.string.history_import_reading))
            HistoryImportPhase.applying->Text(stringResource(R.string.history_import_applying))
            HistoryImportPhase.canceling->Text(stringResource(R.string.history_import_canceling))
            HistoryImportPhase.canceled->Text(stringResource(R.string.history_import_canceled))
            HistoryImportPhase.complete->state.counts?.let {Text(stringResource(R.string.history_import_complete,pluralStringResource(R.plurals.history_import_visit_count,it.visits,it.visits),pluralStringResource(R.plurals.history_import_point_count,it.points,it.points),pluralStringResource(R.plurals.history_import_kept_count,it.kept,it.kept)),Modifier.testTag("history-import-result"))}
            HistoryImportPhase.failed->Text(state.error ?: stringResource(R.string.history_import_failed),color=MaterialTheme.colorScheme.error)
            else->Unit
        }
        if(state.phase==HistoryImportPhase.review) state.review?.let {review->
            Text(stringResource(R.string.history_import_unchanged))
            val sourceLabel=when(review.source) {
                HistoryImportSource.whereday->review.format.name.uppercase(Locale.ROOT)
                HistoryImportSource.googleRaw->stringResource(R.string.history_import_google_raw)
                HistoryImportSource.googleSemantic->stringResource(R.string.history_import_google_semantic)
                HistoryImportSource.googleDevice->stringResource(R.string.history_import_google_device)
            }
            Text(stringResource(R.string.history_import_summary,sourceLabel,pluralStringResource(R.plurals.history_import_visit_count,review.visits,review.visits),pluralStringResource(R.plurals.history_import_point_count,review.points,review.points)),Modifier.testTag("history-import-summary"))
            val dates=remember(review.locale,review.zone) {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(review.locale)).withZone(ZoneId.of(review.zone))}
            val language=Locale.forLanguageTag(review.locale).getDisplayName(Locale.getDefault())
            val zoneName=ZoneId.of(review.zone).getDisplayName(java.time.format.TextStyle.FULL,Locale.getDefault())
            review.from?.let {Text(stringResource(R.string.history_import_dates,dates.format(it),dates.format(review.until ?: it),zoneName))}
            if(review.format==com.bontecou.whereday.export.ExportFormat.markdown) Text(stringResource(R.string.history_import_context,language,zoneName),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(if(review.source==HistoryImportSource.whereday) R.string.history_import_existing else R.string.history_import_google_existing,review.existing,review.repeated),Modifier.testTag("history-import-existing"))
            if(review.unsupported>0) Text(pluralStringResource(R.plurals.history_import_unsupported,review.unsupported,review.unsupported))
            if(review.visits>0) ImportSelectionRow(stringResource(R.string.history_import_select_visits),includeVisits,"history-import-select-visits") {includeVisits=it}
            if(review.points>0) ImportSelectionRow(stringResource(R.string.history_import_select_points),includePoints,"history-import-select-points") {includePoints=it}
            Text(stringResource(R.string.history_import_selection_counts),style=MaterialTheme.typography.bodySmall)
            if(review.rejected>0) {
                Text(stringResource(R.string.history_import_rejected,review.rejected),color=MaterialTheme.colorScheme.error)
                review.errors.forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
            }
            if(review.replaceable>0 && review.source==HistoryImportSource.whereday) {
                Text(stringResource(R.string.history_import_duplicates),style=MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.history_import_replaceable,review.replaceable))
                ImportPolicyRow(stringResource(R.string.history_import_keep),!replace,"history-import-keep") {replace=false}
                ImportPolicyRow(stringResource(R.string.history_import_replace),replace,"history-import-replace") {replace=true}
            }
            Button(enabled=selected,onClick={if(selected) {if(replace && review.source==HistoryImportSource.whereday && review.replaceable>0) confirming=true else commit(review.id,HistoryImportPolicy.keepExisting,selection)}},modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.history_import_confirm))}
        }
        if(state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(enabled=state.phase!=HistoryImportPhase.canceling,onClick=cancel) {Text(stringResource(R.string.history_import_cancel))}
        } else TextButton(onClick=discard) {Text(stringResource(if(state.phase==HistoryImportPhase.review) R.string.history_import_discard else R.string.history_import_done))}
        Text(stringResource(R.string.history_import_lifetime),style=MaterialTheme.typography.bodySmall)
    }
    if(confirming && state.phase==HistoryImportPhase.review) WorkspaceAlertDialog(onDismissRequest={confirming=false},title={Text(stringResource(R.string.history_import_replace_title))},
        text={Text(stringResource(R.string.history_import_replace_body))},
        confirmButton={TextButton(onClick={confirming=false;state.review?.takeIf {selected && it.source==HistoryImportSource.whereday && it.replaceable>0}?.let {commit(it.id,HistoryImportPolicy.replaceExisting,selection)}}) {Text(stringResource(R.string.history_import_replace_confirm))}},
        dismissButton={TextButton(onClick={confirming=false}) {Text(stringResource(R.string.history_import_back))}})
}

@Composable private fun ImportPolicyRow(label:String,selected:Boolean,id:String,choose:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(id).selectable(selected,role=Role.RadioButton,onClick=choose),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        RadioButton(selected,onClick=null);Text(label,Modifier.weight(1f).padding(top=12.dp))
    }
}

@Composable private fun ImportSelectionRow(label:String,selected:Boolean,id:String,choose:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(id).toggleable(selected,role=Role.Checkbox,onValueChange=choose),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Checkbox(selected,onCheckedChange=null);Text(label,Modifier.weight(1f).padding(top=12.dp))
    }
}
