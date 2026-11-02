package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import java.text.Collator
import java.text.NumberFormat
import java.time.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Composable fun CountryDaysScreen(graph:AppGraph,analysisController:CountryAnalysisController=graph.countryAnalysis,back:()->Unit) {
    var periodName by rememberSaveable {mutableStateOf(CountryDayPeriod.year.name)}
    val period=CountryDayPeriod.valueOf(periodName)
    var report by remember {mutableStateOf<CountryDayReport?>(null)}
    var failure by remember {mutableStateOf(false)}
    var retry by remember {mutableIntStateOf(0)}
    var selected by rememberSaveable {mutableStateOf<String?>(null)}
    var adding by rememberSaveable {mutableStateOf(false)}
    var editing by rememberSaveable(stateSaver=CountryStayStateSaver) {mutableStateOf<CountryStay?>(null)}
    var preview by remember {mutableStateOf(false)}
    var analyzeConfirmation by remember {mutableStateOf(false)}
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
    val analysis by analysisController.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val active=remember(analysisController) {AtomicBoolean(true)}
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val number=remember(locale) {NumberFormat.getIntegerInstance(locale)}
    val periods=mapOf("year" to stringResource(R.string.country_period_year),"rolling" to stringResource(R.string.country_period_rolling),"all" to stringResource(R.string.country_period_all))
    var today by remember {mutableStateOf(LocalDate.now())}
    LaunchedEffect(lifecycle) {lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {while(true) {today=LocalDate.now();delay(30_000)}}}
    LaunchedEffect(period,today,retry,lifecycle) {
        report=null;failure=false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {withContext(Dispatchers.IO) {graph.history.observeCountryDays(period.bounds(today)).collect {report=it}}}
            catch(e:Exception) {if(e is CancellationException) throw e;failure=true}
        }
    }
    DisposableEffect(lifecycle,analysisController) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {analyzeConfirmation=false;analysisController.stop()}}
        lifecycle.addObserver(observer)
        onDispose {active.set(false);lifecycle.removeObserver(observer);analysisController.stop()}
    }
    LaunchedEffect(selected,analysisController) {if(selected!=null) analysisController.stop()}
    fun goBack() {if(selected!=null) selected=null else {active.set(false);analysisController.stop();back()}}
    BackHandler {goBack()}
    val overviewState=rememberLazyListState()
    val detailState=rememberSaveable(selected,saver=LazyListState.Saver) {LazyListState()}
    val loadingState=rememberLazyListState()
    val current=report
    val selectedDays=remember(current,selected) {current?.days?.filter {it.countryCode==selected}?.sortedByDescending {it.day}.orEmpty()}
    val countries=remember(current,locale) {current?.days?.groupBy {it.countryCode}?.entries?.sortedWith(Comparator {a,b->
        val count=b.value.size.compareTo(a.value.size)
        if(count!=0) count else Collator.getInstance(locale).compare(CountryRegions.displayName(a.key,locale),CountryRegions.displayName(b.key,locale))
    }).orEmpty()}
    val uniqueDays=remember(current) {current?.days?.asSequence()?.map {it.day}?.toSet()?.size ?: 0}
    val status=countryAnalysisText(analysis.progress,locale)
    // Keep the outgoing lazy content tied to its own route until disposal.
    val selectedCountry=selected
    key(selectedCountry) {
    LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("country-days-content"),state=if(current==null) loadingState else if(selectedCountry==null) overviewState else detailState,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick={goBack()},modifier=Modifier.testTag("country-back")) {Text(stringResource(if(selectedCountry!=null) R.string.country_back_days else R.string.country_back_settings))}}
        item {Text(selectedCountry?.let {CountryRegions.displayName(it,locale)} ?: stringResource(R.string.country_title),style=MaterialTheme.typography.headlineMedium)}
        if(failure) item {Text(stringResource(R.string.country_load_error),color=MaterialTheme.colorScheme.error);TextButton(onClick={retry++}) {Text(stringResource(R.string.country_retry))}}
        if(current==null && !failure) item {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.country_loading))}
        if(selectedCountry!=null) {
            if(current!=null) item {Text(pluralStringResource(R.plurals.country_days_present,selectedDays.size,number.format(selectedDays.size)),Modifier.testTag("country-detail-count"),style=MaterialTheme.typography.titleMedium)}
            items(selectedDays,key={it.day.toString()}) {day->ListItem(modifier=Modifier.testTag("country-day-${day.day}"),headlineContent={Text(day.day.toString())},supportingContent={Text(stringResource(when {day.recorded&&day.manual->R.string.country_source_both;day.manual->R.string.country_source_manual;else->R.string.country_source_recorded}))})}
        } else {
            item {Choice(stringResource(R.string.country_period),periodName,CountryDayPeriod.entries.map {it.name},labelFor={periods.getValue(it)}) {periodName=it}}
            if(current!=null) item {OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.country_country_count,countries.size,number.format(countries.size)),Modifier.testTag("country-summary-countries"),style=MaterialTheme.typography.titleLarge)
                Text(pluralStringResource(R.plurals.country_unique_days,uniqueDays,number.format(uniqueDays)),Modifier.testTag("country-summary-days"))
            }}}
            item {Text(stringResource(R.string.country_rules))}
            item {OutlinedButton(onClick={adding=true},modifier=Modifier.testTag("country-add-stay")) {Text(stringResource(R.string.stay_add))}}
            item {
                if(analysis.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.country_checked,number.format(analysis.progress.completed),number.format(analysis.progress.resolved)))
                    TextButton(onClick=analysisController::stop,modifier=Modifier.testTag("country-analysis-stop")) {Text(stringResource(R.string.country_stop))}
                } else OutlinedButton(enabled=settings?.networkGeocoding==true,onClick={if(active.get()) analyzeConfirmation=true},modifier=Modifier.testTag("country-analysis-open")) {Text(stringResource(R.string.country_analyze))}
                status?.let {Text(it,Modifier.testTag("country-analysis-status"),style=MaterialTheme.typography.bodySmall)}
                if(settings?.networkGeocoding==false) Text(stringResource(R.string.country_network_disabled),style=MaterialTheme.typography.bodySmall)
            }
            current?.let {data->
                if(data.unresolvedPoints>0 || data.unresolvedVisits>0) item {
                    Text(stringResource(R.string.country_unresolved,number.format(data.unresolvedPoints),number.format(data.unresolvedVisits)),Modifier.testTag("country-unresolved"),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.country_unresolved_help),style=MaterialTheme.typography.bodySmall)
                }
                if(countries.isEmpty()) item {Text(stringResource(R.string.country_empty),style=MaterialTheme.typography.titleMedium)}
                items(countries,key={it.key}) {country->OutlinedCard(Modifier.fillMaxWidth().testTag("country-row-${country.key}").clickable {analysisController.stop();selected=country.key}) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(CountryRegions.displayName(country.key,locale),style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.country_code_days,country.key,pluralStringResource(R.plurals.country_day_count,country.value.size,number.format(country.value.size))))
                    Text(stringResource(R.string.country_view_dates),style=MaterialTheme.typography.labelMedium)
                }}}
                if(data.days.isNotEmpty()) item {OutlinedButton(onClick={preview=true},modifier=Modifier.testTag("country-preview")) {Text(stringResource(R.string.country_preview))}}
                item {HorizontalDivider();Text(stringResource(R.string.country_manual),style=MaterialTheme.typography.titleLarge)}
                if(data.stays.isEmpty()) item {Text(stringResource(R.string.country_manual_help))}
                items(data.stays,key={"stay:${it.id}"}) {stay->OutlinedCard(Modifier.fillMaxWidth().testTag("country-stay-${stay.id}").clickable {editing=stay}) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(CountryRegions.displayName(stay.countryCode,locale),style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.country_stay_range,stay.firstDay.toString(),stay.lastDay.toString()))
                    Text(stringResource(R.string.stay_edit),style=MaterialTheme.typography.labelMedium)
                }}}
            }
            item {HistoryFileActions(graph,stringResource(R.string.country_export),current?.days?.isNotEmpty()==true) {graph.exports.prepareCountryDays(period.bounds(today))}}
        }
    }
    }
    if(adding || editing!=null) CountryStayEditor(editing,{adding=false;editing=null},{value,expected->graph.history.saveCountryStay(value,expected)},{expected->graph.history.deleteCountryStay(expected)})
    if(preview && current!=null) WorkspaceAlertDialog(onDismissRequest={preview=false},title={Text(stringResource(R.string.country_preview_title))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.country_preview_help));Text(countryDaysCsv(current.days.take(100)),style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={preview=false}) {Text(stringResource(R.string.country_done))}})
    if(analyzeConfirmation) WorkspaceAlertDialog(onDismissRequest={analyzeConfirmation=false},title={Text(stringResource(R.string.country_analyze_title))},text={Text(stringResource(R.string.country_analyze_disclosure),Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={
        if(analyzeConfirmation && active.get() && selected==null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {analyzeConfirmation=false;analysisController.start()}
    },modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("country-analysis-start")) {Text(stringResource(R.string.country_start))}},dismissButton={TextButton(onClick={analyzeConfirmation=false}) {Text(stringResource(R.string.country_cancel))}})
}

@Composable internal fun countryAnalysisText(value:CountryAnalysisProgress,locale:Locale):String? {
    val number=NumberFormat.getIntegerInstance(locale)
    return value.status?.let {stringResource(when(it) {
        CountryAnalysisStatus.preparing->R.string.country_analysis_preparing
        CountryAnalysisStatus.waiting->R.string.country_analysis_waiting
        CountryAnalysisStatus.lookingUp->R.string.country_analysis_lookup
        CountryAnalysisStatus.nothingDue->R.string.country_analysis_nothing
        CountryAnalysisStatus.finished->R.string.country_analysis_finished
        CountryAnalysisStatus.disabled->R.string.country_analysis_disabled
        CountryAnalysisStatus.stopped->R.string.country_analysis_stopped
        CountryAnalysisStatus.stopping->R.string.country_analysis_stopping
        CountryAnalysisStatus.unavailable->R.string.country_analysis_error
    },number.format(value.resolved),number.format(value.completed))}
}
