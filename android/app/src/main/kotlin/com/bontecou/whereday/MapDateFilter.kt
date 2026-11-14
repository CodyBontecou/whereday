package com.bontecou.whereday

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

// Stable selection keys drive range logic. Locale changes affect labels only.
internal val mapDatePresets=listOf("Today","Yesterday","7 days","30 days","All","Custom")
@Composable internal fun MapDateFilter(selected:String,change:(String)->Unit) {
    val ids=listOf(R.string.map_date_today,R.string.map_date_yesterday,R.string.map_date_week,R.string.map_date_month,R.string.map_date_all,R.string.map_date_custom)
    val labels=mapDatePresets.zip(ids.map {stringResource(it)}).toMap()+mapOf("Visit" to stringResource(R.string.map_date_visit))
    Choice(stringResource(R.string.map_dates),selected,mapDatePresets,labelFor={labels[it] ?: it},change=change)
}
