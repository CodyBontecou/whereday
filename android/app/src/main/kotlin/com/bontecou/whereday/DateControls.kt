package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.ui.res.stringResource
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Composable fun CustomDateRange(
    first:LocalDate,last:LocalDate,dismiss:()->Unit,latestDate:LocalDate?=null,
    zone:ZoneId?=ZoneId.systemDefault(),enabled:Boolean=true,save:(LocalDate,LocalDate)->Unit
) = CustomDateRangeEditor(first,last,dismiss,latestDate,zone,{_,_->null},{_,_->},enabled,save)

@Composable internal fun CustomMapDateRange(draft:MapDateDraft,dismiss:()->Unit,save:(MapSelection)->Unit) {
    var edited by rememberSaveable(draft,stateSaver=mapDateDraftSaver) {mutableStateOf(draft)}
    CustomDateRangeEditor(draft.first,draft.last,dismiss,null,draft.zone,edited::error,
        onDates={a,b->
            // Invalid/overflowing text is displayed for correction, never committed as a binding.
            runCatching {edited.edit(a,b)}.getOrNull()?.let {edited=it}
        },save={a,b->save(edited.apply(a,b))})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CustomDateRangeEditor(
    first:LocalDate,last:LocalDate,dismiss:()->Unit,latestDate:LocalDate?,zone:ZoneId?,
    validate:(LocalDate,LocalDate)->CustomDateError?,onDates:(LocalDate?,LocalDate?)->Unit,
    enabled:Boolean=true,save:(LocalDate,LocalDate)->Unit
) {
    BlockVisitLinks()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val active=remember(lifecycle) {AtomicBoolean(true)}
    val epoch=remember(lifecycle) {AtomicLong()}
    var renderEpoch by remember(lifecycle) {mutableLongStateOf(0)}
    val currentApply=remember(lifecycle) {AtomicReference<(() ->Unit)?>(null)}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) renderEpoch=epoch.incrementAndGet()}
        lifecycle.addObserver(observer)
        onDispose {active.set(false);currentApply.set(null);lifecycle.removeObserver(observer)}
    }
    val approvalEpoch=renderEpoch
    val apply:()->Unit={
        if(active.get() && epoch.get()==approvalEpoch && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) currentApply.get()?.invoke()
    }
    // Material numeric input normalizes impossible days (for example February 30) and
    // uses years of era. Use strict ISO text entry alongside the calendar for every year.
    var iso by rememberSaveable {mutableStateOf(first.year !in 1..9999 || last.year !in 1..9999 || first>last)}
    var firstText by rememberSaveable {mutableStateOf(first.toString())}
    var lastText by rememberSaveable {mutableStateOf(last.toString())}
    fun error(a:LocalDate?,b:LocalDate?)=customDateError(a,b,latestDate,zone) ?: validate(a!!,b!!)
    val a=runCatching {LocalDate.parse(firstText)}.getOrNull()
    val b=runCatching {LocalDate.parse(lastText)}.getOrNull()
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    if(iso) {
        SideEffect {onDates(a,b)}
        val error=error(a,b)
        SideEffect {currentApply.set(if(enabled && error==null) { {save(a!!,b!!)} } else null)}
        WorkspaceAlertDialog(onDismissRequest=dismiss,title={Text(stringResource(R.string.map_choose_dates),style=WdType.title2,color=wd.textPrimary)},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.date_iso_help),style=WdType.caption,color=wd.textMuted)
                OutlinedTextField(firstText,{firstText=it},label={Text(stringResource(R.string.date_range_from))},textStyle=WdType.monoBody,
                    singleLine=true,isError=a==null,colors=wdTextFieldColors(),shape=RoundedCornerShape(10.dp),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),modifier=Modifier.testTag("date-range-from"))
                OutlinedTextField(lastText,{lastText=it},label={Text(stringResource(R.string.date_range_through))},textStyle=WdType.monoBody,
                    singleLine=true,isError=b==null,colors=wdTextFieldColors(),shape=RoundedCornerShape(10.dp),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),modifier=Modifier.testTag("date-range-through"))
                error?.let {DateRangeError(it,inset=false)}
                if(a!=null && b!=null && a<=b && a.year in 1..9999 && b.year in 1..9999)
                    TextButton(onClick={iso=false},colors=dateLinkColors()) {Icon(Icons.Outlined.CalendarMonth,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.date_use_calendar).uppercase(locale),style=WdType.caption2.copy(fontWeight=FontWeight.SemiBold,letterSpacing=0.1.em))}
            }
        },confirmButton={key(approvalEpoch) {TextButton(enabled=enabled && error==null,onClick=apply,colors=dateActionColors()) {Text(stringResource(R.string.map_apply_dates),fontWeight=FontWeight.SemiBold)}}},
            dismissButton={TextButton(onClick=dismiss,colors=dateActionColors()) {Text(stringResource(R.string.map_cancel_dates))}})
        return
    }
    val selectable=remember(latestDate) {object:SelectableDates {
        override fun isSelectableDate(utcTimeMillis:Long)=latestDate==null || utcDate(utcTimeMillis)<=latestDate
        override fun isSelectableYear(year:Int)=latestDate==null || year<=latestDate.year
    }}
    val state=rememberDateRangePickerState(
        initialSelectedStartDateMillis=a!!.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        initialSelectedEndDateMillis=b!!.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange=1..9999,selectableDates=selectable
    )
    val selectedFirst=state.selectedStartDateMillis?.let(::utcDate)
    val selectedLast=state.selectedEndDateMillis?.let(::utcDate)
    SideEffect {onDates(selectedFirst,selectedLast)}
    val error=error(selectedFirst,selectedLast)
    SideEffect {currentApply.set(if(enabled && error==null) { {save(selectedFirst!!,selectedLast!!)} } else null)}
    val dateFormat=remember(locale) {DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)}
    val missing=stringResource(R.string.date_range_not_selected)
    WorkspaceDatePickerDialog(onDismissRequest=dismiss,confirmButton={key(approvalEpoch) {TextButton(enabled=enabled && error==null,onClick=apply,colors=dateActionColors()) {Text(stringResource(R.string.map_apply_dates),fontWeight=FontWeight.SemiBold)}}},dismissButton={TextButton(onClick=dismiss,colors=dateActionColors()) {Text(stringResource(R.string.map_cancel_dates))}}) {
        Column(Modifier.heightIn(max=520.dp)) {
            DateRangePicker(state=state,modifier=Modifier.weight(1f),showModeToggle=false,colors=wdDateRangeColors(),title={Text(stringResource(R.string.map_choose_dates),Modifier.padding(start=24.dp,end=24.dp,top=20.dp,bottom=8.dp),style=WdType.title2,color=wd.textPrimary)},headline={
                Column(Modifier.fillMaxWidth().padding(start=24.dp,end=16.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.date_range_label_value,stringResource(R.string.date_range_from),selectedFirst?.format(dateFormat) ?: missing),
                        style=WdType.caption.copy(fontWeight=FontWeight.Medium),color=if(selectedFirst==null) wd.textMuted else wd.textPrimary,modifier=Modifier.testTag("date-range-from-headline"))
                    Text(stringResource(R.string.date_range_label_value,stringResource(R.string.date_range_through),selectedLast?.format(dateFormat) ?: missing),
                        style=WdType.caption.copy(fontWeight=FontWeight.Medium),color=if(selectedLast==null) wd.textMuted else wd.textPrimary,modifier=Modifier.testTag("date-range-through-headline"))
                }
            })
            if(selectedFirst!=null && selectedLast!=null) error?.let {DateRangeError(it)}
            TextButton(onClick={
                firstText=selectedFirst?.toString().orEmpty();lastText=selectedLast?.toString().orEmpty();iso=true
            },colors=dateLinkColors(),modifier=Modifier.padding(horizontal=12.dp)) {Icon(Icons.Outlined.Keyboard,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.date_enter_iso))}
        }
    }
}

private fun utcDate(millis:Long)=Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@Composable private fun DateRangeError(error:CustomDateError,inset:Boolean=true) {
    val id=when(error) {
        CustomDateError.invalid->R.string.date_range_invalid
        CustomDateError.order->R.string.date_range_order
        CustomDateError.future->R.string.date_range_future
        CustomDateError.skipped->R.string.date_range_skipped
        CustomDateError.unsupported->R.string.date_range_unsupported
        CustomDateError.retainedTimes->R.string.date_range_retained_times
    }
    Row(Modifier.padding(horizontal=if(inset) 24.dp else 0.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.ErrorOutline,null,Modifier.size(16.dp).padding(top=1.dp),tint=wd.inkRust)
        Text(stringResource(id),style=MaterialTheme.typography.bodySmall,color=wd.inkRust)
    }
}

@Composable private fun dateActionColors()=ButtonDefaults.textButtonColors(contentColor=wd.accent,disabledContentColor=wd.textMuted.copy(alpha=0.6f))
@Composable private fun dateLinkColors()=ButtonDefaults.textButtonColors(contentColor=wd.inkRust)

/** The iOS graphical picker tinted in rust on paper. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun wdDateRangeColors()=DatePickerDefaults.colors(
    containerColor=wd.card,titleContentColor=wd.textMuted,headlineContentColor=wd.textPrimary,weekdayContentColor=wd.textMuted,
    subheadContentColor=wd.textMuted,navigationContentColor=wd.textPrimary,yearContentColor=wd.textPrimary,currentYearContentColor=wd.inkRust,
    selectedYearContentColor=wd.surface,selectedYearContainerColor=wd.inkRust,dayContentColor=wd.textPrimary,disabledDayContentColor=wd.textMuted.copy(alpha=0.4f),
    selectedDayContentColor=wd.surface,selectedDayContainerColor=wd.inkRust,todayContentColor=wd.inkRust,todayDateBorderColor=wd.inkRust,
    dayInSelectionRangeContentColor=wd.textPrimary,dayInSelectionRangeContainerColor=wd.inkRust.copy(alpha=if(wd.dark) 0.22f else 0.14f),
    dividerColor=wd.border,
)
