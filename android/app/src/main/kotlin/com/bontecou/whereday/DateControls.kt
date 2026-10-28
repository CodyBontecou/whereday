package com.bontecou.whereday

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CustomDateRange(first: LocalDate,last: LocalDate,dismiss: () -> Unit,save: (LocalDate,LocalDate) -> Unit) {
    val state=rememberDateRangePickerState(initialSelectedStartDateMillis=first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),initialSelectedEndDateMillis=last.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(onDismissRequest=dismiss,confirmButton={ TextButton(enabled=state.selectedStartDateMillis!=null && state.selectedEndDateMillis!=null,onClick={
        save(Instant.ofEpochMilli(state.selectedStartDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate(),Instant.ofEpochMilli(state.selectedEndDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate())
    }) { Text("Apply") } },dismissButton={ TextButton(onClick=dismiss) { Text("Cancel") } }) {
        DateRangePicker(state=state,modifier=Modifier.heightIn(max=520.dp),title={ Text("Choose dates",Modifier.padding(16.dp)) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun VisitDateTimeControl(label: String,value: Instant,enabled:Boolean=true,change: (Instant) -> Unit) {
    val zone=ZoneId.systemDefault()
    val local=value.atZone(zone)
    var dateOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled=enabled,onClick={ dateOpen=true }) { Text(local.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) }
            OutlinedButton(enabled=enabled,onClick={ timeOpen=true }) { Text(local.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) }
        }
    }
    if(dateOpen) {
        val date=rememberDatePickerState(initialSelectedDateMillis=local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={ dateOpen=false },confirmButton={ TextButton(enabled=date.selectedDateMillis!=null,onClick={
            val day=Instant.ofEpochMilli(date.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
            change(day.atTime(local.toLocalTime()).atZone(zone).toInstant()); dateOpen=false
        }) { Text("Apply date") } },dismissButton={ TextButton(onClick={ dateOpen=false }) { Text("Cancel") } }) { DatePicker(date) }
    }
    if(timeOpen) {
        val time=rememberTimePickerState(local.hour,local.minute,DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(onDismissRequest={ timeOpen=false },title={ Text(label) },text={ TimePicker(time) },confirmButton={ TextButton(onClick={
            change(local.toLocalDate().atTime(time.hour,time.minute).atZone(zone).toInstant()); timeOpen=false
        }) { Text("Apply time") } },dismissButton={ TextButton(onClick={ timeOpen=false }) { Text("Cancel") } })
    }
}
