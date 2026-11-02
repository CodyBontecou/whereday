package com.bontecou.whereday

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.text.DateFormatSymbols

private val timestampDraftSaver=listSaver<VisitTimestampDraft?,String>(
    save={draft->draft?.let {listOf(it.original.toString(),it.zone.id,it.part.name)} ?: emptyList()},
    restore={if(it.isEmpty()) null else VisitTimestampDraft(Instant.parse(it[0]),ZoneId.of(it[1]),VisitTimestampPart.valueOf(it[2]))}
)

@OptIn(ExperimentalLayoutApi::class)
@Composable fun VisitDateTimeControl(
    label:String,value:Instant,modifier:Modifier=Modifier,enabled:Boolean=true,zone:ZoneId=ZoneId.systemDefault(),change:(Instant)->Unit
) {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val clock24=DateFormat.is24HourFormat(LocalContext.current)
    val clock=remember(locale,clock24) {DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale,if(clock24) "Hm" else "hm"),locale)}
    val local=value.atZone(zone)
    var draft by rememberSaveable(stateSaver=timestampDraftSaver) {mutableStateOf<VisitTimestampDraft?>(null)}
    val latestValue by rememberUpdatedState(value)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestChange by rememberUpdatedState(change)
    LaunchedEffect(value,enabled) {if(!enabled || draft?.original!=value) draft=null}
    val day=displayVisitDate(local.toLocalDate(),locale)
    val time=local.format(clock)
    val dateAction=stringResource(R.string.visit_timestamp_date_action,label,day)
    val timeAction=stringResource(R.string.visit_timestamp_time_action,label,time)
    Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label,style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textMuted)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            VisitTimestampPill(day,enabled,Modifier.testTag("visit-timestamp-date").semantics {contentDescription=dateAction}) {draft=VisitTimestampDraft(value,zone,VisitTimestampPart.date)}
            VisitTimestampPill(time,enabled,Modifier.testTag("visit-timestamp-time").semantics {contentDescription=timeAction}) {draft=VisitTimestampDraft(value,zone,VisitTimestampPart.time)}
        }
    }
    draft?.let {opening->key(opening) {
        val dismiss={draft=null}
        val apply:(Instant)->Unit={selected->
            if(latestEnabled && latestValue==opening.original) latestChange(selected)
            draft=null
        }
        if(opening.part==VisitTimestampPart.date) VisitDateDialog(label,opening,dismiss,apply)
        else VisitTimeDialog(label,opening,clock24,dismiss,apply)
    }}
}

/** A mono figure in a paper capsule, as iOS renders the arrival and departure pickers. */
@Composable private fun VisitTimestampPill(text:String,enabled:Boolean,modifier:Modifier,onClick:()->Unit) {
    val shape=RoundedCornerShape(10.dp)
    Box(modifier.heightIn(min=44.dp).clip(shape).background(wd.surface).border(1.dp,wd.border,shape)
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=14.dp,vertical=8.dp),contentAlignment=Alignment.Center) {
        Text(text,style=WdType.monoBody,color=if(enabled) wd.textPrimary else wd.textMuted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun visitTimePickerColors()=TimePickerDefaults.colors(
    clockDialColor=wd.dialTrack,clockDialSelectedContentColor=wd.surface,clockDialUnselectedContentColor=wd.textPrimary,selectorColor=wd.accent,
    containerColor=wd.card,periodSelectorBorderColor=wd.border,periodSelectorSelectedContainerColor=wd.accent,periodSelectorUnselectedContainerColor=wd.card,
    periodSelectorSelectedContentColor=wd.surface,periodSelectorUnselectedContentColor=wd.textMuted,timeSelectorSelectedContainerColor=wd.accent,
    timeSelectorUnselectedContainerColor=wd.dialTrack,timeSelectorSelectedContentColor=wd.surface,timeSelectorUnselectedContentColor=wd.textPrimary,
)

@Composable private fun visitRadioColors()=RadioButtonDefaults.colors(selectedColor=wd.accent,unselectedColor=wd.textMuted)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun VisitDateDialog(label:String,draft:VisitTimestampDraft,dismiss:()->Unit,apply:(Instant)->Unit) {
    var iso by rememberSaveable {mutableStateOf(draft.local.year !in 1..9999)}
    var text by rememberSaveable {mutableStateOf(draft.local.toLocalDate().toString())}
    var preferred by rememberSaveable {mutableStateOf(draft.local.offset.id)}
    val initial=runCatching {LocalDate.parse(text)}.getOrNull()
    val title=stringResource(R.string.visit_timestamp_edit_date,label)
    if(iso) {
        val choices=draft.date(initial)
        val selected=choices.selected(preferred)
        WorkspaceAlertDialog(onDismissRequest=dismiss,title={Text(title)},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.date_iso_help),color=wd.textMuted)
                OutlinedTextField(text,{if(it.length<=32) text=it},singleLine=true,label={Text(stringResource(R.string.visit_timestamp_date))},colors=wdTextFieldColors(),textStyle=WdType.monoBody,modifier=Modifier.testTag("visit-timestamp-date-input"))
                VisitTimestampReview(draft,choices,preferred,{preferred=it})
                if(initial!=null && initial.year in 1..9999) TextButton(onClick={if(runCatching {LocalDate.parse(text).year in 1..9999}.getOrDefault(false)) iso=false}) {Text(stringResource(R.string.date_use_calendar))}
            }
        },confirmButton={TextButton(enabled=selected!=null,onClick={draft.date(runCatching {LocalDate.parse(text)}.getOrNull()).selected(preferred)?.let {apply(it.instant)}}) {Text(stringResource(R.string.visit_apply_date))}},
            dismissButton={TextButton(onClick=dismiss) {Text(stringResource(R.string.visit_cancel))}})
        return
    }
    val state=rememberDatePickerState(initialSelectedDateMillis=initial!!.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),yearRange=1..9999)
    val day=state.selectedDateMillis?.let {Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()}
    val choices=draft.date(day)
    val selected=choices.selected(preferred)
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    WorkspaceDatePickerDialog(onDismissRequest=dismiss,
        confirmButton={TextButton(enabled=selected!=null,onClick={draft.date(state.selectedDateMillis?.let {Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()}).selected(preferred)?.let {apply(it.instant)}}) {Text(stringResource(R.string.visit_apply_date))}},
        dismissButton={TextButton(onClick=dismiss) {Text(stringResource(R.string.visit_cancel))}}) {
        // Month enforces six 48 dp rows. Give the picker its natural height and scroll
        // the whole body instead of squeezing those rows behind the review controls.
        Column(Modifier.heightIn(max=520.dp)) {
            Text(title,Modifier.padding(start=20.dp,end=20.dp,top=20.dp,bottom=4.dp),style=WdType.title2,color=wd.textPrimary)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("visit-timestamp-date-scroll")) {
            DatePicker(state,modifier=Modifier.fillMaxWidth(),showModeToggle=false,title=null,colors=workspaceDatePickerColors(),headline={
                Text(day?.let {displayVisitDate(it,locale)} ?: stringResource(R.string.date_range_not_selected),
                    Modifier.padding(horizontal=20.dp,vertical=8.dp),style=WdType.numeral,color=wd.textPrimary)
            })
            VisitTimestampReview(draft,choices,preferred,{preferred=it})
            TextButton(onClick={text=day?.toString().orEmpty();iso=true}) {Text(stringResource(R.string.date_enter_iso))}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun VisitTimeDialog(label:String,draft:VisitTimestampDraft,clock24:Boolean,dismiss:()->Unit,apply:(Instant)->Unit) {
    val display24=rememberSaveable {clock24}
    val state=rememberTimePickerState(draft.local.hour,draft.local.minute,display24)
    var preferred by rememberSaveable {mutableStateOf(draft.local.offset.id)}
    val large=LocalDensity.current.fontScale>=1.3f
    var input by rememberSaveable {mutableStateOf(large)}
    var hourText by rememberSaveable {mutableStateOf((if(display24) draft.local.hour else (draft.local.hour%12).takeIf {it!=0} ?: 12).toString().padStart(2,'0'))}
    var minuteText by rememberSaveable {mutableStateOf(draft.local.minute.toString().padStart(2,'0'))}
    var afternoon by rememberSaveable {mutableStateOf(draft.local.hour>=12)}
    fun adoptClock() {
        hourText=(if(display24) state.hour else (state.hour%12).takeIf {it!=0} ?: 12).toString().padStart(2,'0')
        minuteText=state.minute.toString().padStart(2,'0');afternoon=state.hour>=12
    }
    // A live font change can replace the dial with fields after a clock edit.
    if(large && !input) SideEffect {adoptClock();input=true}
    val fields=parseVisitClock(hourText,minuteText,display24,afternoon)
    val useInput=input || large
    val choices=if(useInput) fields?.let {draft.time(it.first,it.second)} ?: VisitTimestampOptions(emptyList(),VisitTimestampProblem.invalid)
        else draft.time(state.hour,state.minute)
    val selected=choices.selected(preferred)
    WorkspaceAlertDialog(onDismissRequest=dismiss,title={Text(stringResource(R.string.visit_timestamp_edit_time,label))},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(useInput) {
                OutlinedTextField(hourText,{hourText=it.take(8)},label={Text(stringResource(if(display24) R.string.visit_timestamp_hour_24 else R.string.visit_timestamp_hour_12))},
                    singleLine=true,colors=wdTextFieldColors(),textStyle=WdType.monoBody,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.testTag("visit-timestamp-hour"))
                OutlinedTextField(minuteText,{minuteText=it.take(8)},label={Text(stringResource(R.string.visit_timestamp_minute))},
                    singleLine=true,colors=wdTextFieldColors(),textStyle=WdType.monoBody,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.testTag("visit-timestamp-minute"))
                if(!display24) {
                    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
                    val periods=remember(locale) {DateFormatSymbols(locale).amPmStrings}
                    FlowRow(Modifier.selectableGroup(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        periods.forEachIndexed {index,name->Row(Modifier.minimumInteractiveComponentSize().selectable(afternoon==(index==1),onClick={afternoon=index==1},role=Role.RadioButton),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(afternoon==(index==1),onClick=null,colors=visitRadioColors());Text(name,color=wd.textPrimary)
                        }}
                    }
                }
            } else TimePicker(state,colors=visitTimePickerColors())
            VisitTimestampReview(draft,choices,preferred,{preferred=it})
            if(!large) TextButton(enabled=!input || fields!=null,onClick={
                if(input) {
                    val current=parseVisitClock(hourText,minuteText,display24,afternoon) ?: return@TextButton
                    state.hour=current.first;state.minute=current.second;input=false
                }
                else {adoptClock();input=true}
            }) {Text(stringResource(if(input) R.string.visit_timestamp_use_clock else R.string.visit_timestamp_enter_time))}
        }
    },confirmButton={TextButton(enabled=selected!=null,onClick={
        val current=if(input || large) parseVisitClock(hourText,minuteText,display24,afternoon)?.let {draft.time(it.first,it.second)}
            else draft.time(state.hour,state.minute)
        current?.selected(preferred)?.let {apply(it.instant)}
    }) {Text(stringResource(R.string.visit_apply_time))}},
        dismissButton={TextButton(onClick=dismiss) {Text(stringResource(R.string.visit_cancel))}})
}

@Composable private fun VisitTimestampReview(draft:VisitTimestampDraft,choices:VisitTimestampOptions,preferred:String,choose:(String)->Unit,modifier:Modifier=Modifier) {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val clock24=DateFormat.is24HourFormat(LocalContext.current)
    val clock=remember(locale,clock24) {DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale,if(clock24) "Hm" else "hm"),locale)}
    Column(modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.visit_timestamp_zone,draft.zone.id),style=WdType.caption,color=wd.textMuted)
        Text(if(draft.part==VisitTimestampPart.date) stringResource(R.string.visit_timestamp_kept_time,draft.local.format(clock))
            else stringResource(R.string.visit_timestamp_kept_date,displayVisitDate(draft.local.toLocalDate(),locale)),style=WdType.caption,color=wd.textMuted)
        choices.problem?.let {problem->Text(when(problem) {
            VisitTimestampProblem.invalid->stringResource(R.string.visit_timestamp_invalid)
            VisitTimestampProblem.gap->stringResource(R.string.visit_timestamp_gap,draft.zone.id)
            VisitTimestampProblem.unsupported->stringResource(R.string.date_range_unsupported)
        },modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite},color=wd.inkRust,style=MaterialTheme.typography.bodySmall)}
        if(choices.values.size>1) {
            Text(stringResource(R.string.visit_timestamp_ambiguous),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
            Column(Modifier.selectableGroup()) {choices.values.forEachIndexed {index,option->
                val offset=if(option.offset==ZoneOffset.UTC) "UTC" else "UTC${option.offset.id}"
                val name=stringResource(if(index==0) R.string.visit_timestamp_first else R.string.visit_timestamp_second,offset)
                Row(Modifier.fillMaxWidth().minimumInteractiveComponentSize().selectable(option.offset.id==preferred,onClick={choose(option.offset.id)},role=Role.RadioButton),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(option.offset.id==preferred,onClick=null,colors=visitRadioColors())
                    Text(name,Modifier.weight(1f),style=WdType.caption,color=wd.textPrimary)
                }
            }}
        }
    }
}

private fun displayVisitDate(day:LocalDate,locale:Locale)=if(day.year in 1..9999)
    day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) else day.toString()
