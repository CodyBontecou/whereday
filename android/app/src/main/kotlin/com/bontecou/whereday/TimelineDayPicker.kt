package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TimelineDayPicker(opening:TimelineDateOpening,dismiss:()->Unit,select:(LocalDate)->Unit) {
    BlockVisitLinks()
    val actions=rememberVisibleEditorActions();val ticket=actions.epoch
    var iso by rememberSaveable(opening.id) {mutableStateOf(opening.original.year !in 1..9999)}
    var text by rememberSaveable(opening.id) {mutableStateOf(opening.original.toString())}
    fun error(day:LocalDate?)=customDateError(day,day,null,opening.zone)
    fun apply(day:LocalDate?) {if(actions.allows(ticket) && error(day)==null) select(day!!)}
    val cancel={if(actions.allows(ticket)) dismiss()}
    val title=stringResource(R.string.timeline_choose_date)
    val parsed=runCatching {LocalDate.parse(text)}.getOrNull()
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    fun label(day:LocalDate)=if(day.year in 1..9999) day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) else day.toString()
    val root=Modifier.semantics {testTagsAsResourceId=true}.testTag("timeline-date-content")
    @Composable fun Error(day:LocalDate?) {error(day)?.let {problem->
        Text(stringResource(when(problem) {
            CustomDateError.invalid->R.string.date_range_invalid
            CustomDateError.skipped->R.string.date_range_skipped
            else->R.string.date_range_unsupported
        }),modifier=Modifier.testTag("timeline-date-error"),color=MaterialTheme.colorScheme.error)
    }}
    if(iso) {
        WorkspaceAlertDialog(onDismissRequest=cancel,title={Text(title)},text={
            Column(root.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.date_iso_help))
                OutlinedTextField(text,{if(it.length<=32) text=it},singleLine=true,label={Text(stringResource(R.string.visit_timestamp_date))},modifier=Modifier.testTag("timeline-date-input"))
                Error(parsed)
                if(parsed!=null && parsed.year in 1..9999 && error(parsed)==null) TextButton(onClick={iso=false}) {Text(stringResource(R.string.date_use_calendar))}
            }
        },confirmButton={key(ticket) {TextButton(enabled=error(parsed)==null,modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("timeline-date-apply"),onClick={apply(runCatching {LocalDate.parse(text)}.getOrNull())}) {Text(stringResource(R.string.visit_apply_date))}}},
            dismissButton={TextButton(onClick=cancel) {Text(stringResource(R.string.visit_cancel))}})
        return
    }
    val state=rememberDatePickerState(initialSelectedDateMillis=parsed?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),yearRange=1..9999)
    fun selected()=state.selectedDateMillis?.let {Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()}
    val day=selected()
    WorkspaceDatePickerDialog(onDismissRequest=cancel,
        confirmButton={key(ticket) {TextButton(enabled=error(day)==null,modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("timeline-date-apply"),onClick={apply(selected())}) {Text(stringResource(R.string.visit_apply_date))}}},
        dismissButton={TextButton(onClick=cancel) {Text(stringResource(R.string.visit_cancel))}}) {
        Column(root.heightIn(max=520.dp)) {
            Text(title,Modifier.padding(16.dp),style=MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("timeline-date-scroll")) {
                DatePicker(state,modifier=Modifier.fillMaxWidth(),showModeToggle=false,title=null,headline={
                    Text(day?.let(::label) ?: stringResource(R.string.date_range_not_selected),Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.titleLarge)
                })
            }
            Error(day)
            TextButton(onClick={text=day?.toString().orEmpty();iso=true},modifier=Modifier.testTag("timeline-date-iso")) {Text(stringResource(R.string.date_enter_iso))}
        }
    }
}
