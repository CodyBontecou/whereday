package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.export.BackupCollection
import com.bontecou.whereday.platform.*
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings › Back up journal: the complete backup iOS makes before Clear All Data. Delivery reuses the shared
 * share/save/folder sheet; like every file export it needs a plan (iOS `canExport`). Reached from the clear
 * confirmation, it also offers the way back to erasing once the backup is delivered.
 */
@Composable internal fun BackupScreen(graph:AppGraph,beforeClear:Boolean,back:()->Unit,continueToClear:()->Unit) {
    val purchase by graph.purchases.state.collectAsStateWithLifecycle()
    val checkout by graph.purchases.checkoutInFlight.collectAsStateWithLifecycle()
    val clear by graph.journalClear.state.collectAsStateWithLifecycle()
    val activity=androidx.activity.compose.LocalActivity.current
    var plans by rememberSaveable {mutableStateOf(false)}
    val locked=exportDeliveryLocked(purchase.access)
    Column(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("backup-screen")
        .verticalScroll(rememberScrollState()).padding(bottom=32.dp)) {
        SettingsBackHeader(stringResource(R.string.backup_title),stringResource(R.string.common_back),back,backTag="backup-back",eyebrow=stringResource(R.string.redesign_settings_eyebrow))
        Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(beforeClear) ExportCallout(stringResource(R.string.backup_before_clear_body),title=stringResource(R.string.backup_before_clear_title))
            ExportBody(stringResource(R.string.backup_body))
            ExportBody(stringResource(R.string.backup_restore_hint))
            ExportBody(stringResource(R.string.backup_parts_help))
            ExportBody(stringResource(R.string.backup_photos_help))
            Spacer(Modifier.height(4.dp))
            if(locked) {
                ExportCallout(stringResource(R.string.backup_locked),textModifier=Modifier.testTag("backup-locked"),warning=true)
                WdButton(stringResource(R.string.backup_plans),{plans=true},Modifier.testTag("backup-plans"),emphasis=WdEmphasis.primary,icon=Icons.Outlined.Lock)
            } else HistoryFileActions(graph,stringResource(R.string.backup_create),!clear.blocked) {graph.exports.prepareBackup()}
            if(beforeClear) WdButton(stringResource(R.string.backup_continue_clear),continueToClear,Modifier.testTag("backup-continue-clear"),
                emphasis=WdEmphasis.destructive,enabled=!clear.busy,icon=Icons.Outlined.DeleteOutline)
        }
    }
    if(plans) PaywallDialog(purchase,checkout,{plan->if(activity!=null) graph.purchases.purchase(activity,plan,UsagePaywall.settings)},
        {graph.purchases.restore(UsagePaywall.settings)},{plans=false},entry=PaywallEntry.export)
}

@Composable private fun backupFailureText(failure:BackupRestoreFailure?)=stringResource(when(failure) {
    BackupRestoreFailure.malformed->R.string.backup_error_malformed
    BackupRestoreFailure.unsupportedVersion->R.string.backup_error_version
    BackupRestoreFailure.duplicateIdentifiers->R.string.backup_error_duplicates
    BackupRestoreFailure.invalidRecords->R.string.backup_error_invalid
    BackupRestoreFailure.incompleteSet->R.string.backup_error_incomplete
    BackupRestoreFailure.mixedSelection->R.string.backup_error_mixed
    BackupRestoreFailure.fileTooLarge->R.string.backup_error_too_large
    BackupRestoreFailure.tooManyRecords->R.string.backup_error_too_many
    BackupRestoreFailure.storage->R.string.backup_error_storage
    BackupRestoreFailure.journalBusy->R.string.backup_error_busy
    BackupRestoreFailure.unreadable,null->R.string.backup_error_unreadable
})

/** iOS ImportPreviewView's backup review: counts, the new-record total, then one explicit "Restore backup". */
@Composable internal fun BackupRestoreScreen(controller:BackupRestoreController) {
    val state by controller.state.collectAsStateWithLifecycle()
    BlockVisitLinks()
    BackHandler {if(state.busy) controller.cancel() else controller.discard()}
    val locale=LocalConfiguration.current.locales[0]
    val number=remember(locale) {NumberFormat.getIntegerInstance(locale)}
    Column(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("backup-restore-screen")
        .verticalScroll(rememberScrollState()).padding(bottom=24.dp)) {
        WdPageHeader(stringResource(R.string.backup_restore_title),eyebrow=stringResource(R.string.export_redesign_import_eyebrow),compact=true)
        Column(Modifier.exportInset().padding(top=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            when(state.phase) {
                BackupRestorePhase.reading->ExportBusyLine(stringResource(R.string.backup_restore_reading))
                BackupRestorePhase.restoring->ExportBusyLine(stringResource(R.string.backup_restore_restoring))
                BackupRestorePhase.canceling->ExportBusyLine(stringResource(R.string.backup_restore_canceling))
                BackupRestorePhase.canceled->ExportCallout(stringResource(R.string.backup_restore_canceled))
                BackupRestorePhase.complete->state.result?.let {ExportCallout(stringResource(R.string.backup_restore_complete_detail),
                    title=stringResource(R.string.backup_restore_complete,number.format(it.added)),textModifier=Modifier.testTag("backup-restore-result"))}
                BackupRestorePhase.failed->ExportCallout(backupFailureText(state.failure),warning=true,textModifier=Modifier.testTag("backup-restore-error"))
                else->Unit
            }
        }
        if(state.phase==BackupRestorePhase.review) state.review?.let {review->
            val tally=review.tally
            val dates=remember(locale) {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault())}
            WdSectionHeader(stringResource(R.string.export_redesign_section_review))
            ExportCard {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_restore_heading),style=WdType.title3,color=wd.textPrimary)
                    Text(stringResource(R.string.backup_restore_source)+" · "+stringResource(R.string.backup_restore_exported,dates.format(tally.exportedAt)),style=WdType.caption,color=wd.textMuted)
                    WdDivider()
                    BackupCountRow(stringResource(R.string.backup_count_files),number.format(review.files))
                    BackupCountRow(stringResource(R.string.export_data_visits),number.format(tally.counts.getValue(BackupCollection.visits)))
                    BackupCountRow(stringResource(R.string.export_data_points),number.format(tally.counts.getValue(BackupCollection.points)))
                    BackupCountRow(stringResource(R.string.export_data_outings),number.format(tally.counts.getValue(BackupCollection.recordingSessions)))
                    BackupCountRow(stringResource(R.string.saved_title),number.format(tally.counts.getValue(BackupCollection.savedPlaces)))
                    BackupCountRow(stringResource(R.string.backup_count_stays),number.format(tally.counts.getValue(BackupCollection.countryStays)))
                    BackupCountRow(stringResource(R.string.backup_count_photos),number.format(tally.counts.getValue(BackupCollection.photoMoments)))
                    WdDivider()
                    Text(stringResource(R.string.backup_restore_new,number.format(tally.added)),Modifier.testTag("backup-restore-new"),
                        style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
                    Text(stringResource(R.string.backup_restore_existing,number.format(tally.existing)),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                    if(tally.unsupported>0) Text(stringResource(R.string.backup_restore_unsupported,number.format(tally.unsupported)),Modifier.testTag("backup-restore-unsupported"),
                        style=MaterialTheme.typography.bodyMedium,color=wd.warning)
                }
            }
            WdSectionFooter(stringResource(R.string.backup_restore_help))
            if(tally.added==0) ExportBody(stringResource(R.string.backup_restore_nothing_new),Modifier.padding(horizontal=24.dp))
            WdButton(stringResource(R.string.backup_restore_confirm),{controller.restore(review.id)},Modifier.exportInset().padding(top=20.dp).testTag("backup-restore-confirm"),
                emphasis=WdEmphasis.primary,enabled=tally.added>0,icon=Icons.Outlined.Restore)
        }
        Column(Modifier.exportInset().padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(state.busy) {
                ExportProgress()
                WdButton(stringResource(R.string.backup_restore_cancel),controller::cancel,enabled=state.phase!=BackupRestorePhase.canceling,emphasis=WdEmphasis.destructive)
            } else WdButton(stringResource(if(state.phase==BackupRestorePhase.review) R.string.backup_restore_cancel else R.string.backup_restore_done),controller::discard,
                Modifier.testTag("backup-restore-close"),emphasis=if(state.phase==BackupRestorePhase.review) WdEmphasis.secondary else WdEmphasis.primary)
        }
    }
}

@Composable private fun BackupCountRow(label:String,value:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=wd.textPrimary)
        Text(value,style=WdType.caption.copy(fontWeight=FontWeight.Medium),color=wd.textMuted)
    }
}
