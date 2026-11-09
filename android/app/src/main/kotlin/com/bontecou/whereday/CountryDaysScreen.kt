package com.bontecou.whereday

import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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

@Composable fun CountryDaysScreen(graph:AppGraph,analysisController:CountryAnalysisController=graph.countryAnalysis,back:(()->Unit)?) {
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
    fun goBack() {if(selected!=null) selected=null else {active.set(false);analysisController.stop();back?.invoke()}}
    // As the Atlas tab the overview is a root page: Back leaves the app as from any other tab.
    BackHandler(enabled=back!=null || selected!=null) {goBack()}
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
    val card=Modifier.padding(horizontal=16.dp)
    val maximum=countries.firstOrNull()?.value?.size ?: 1
    LazyColumn(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("country-days-content"),state=if(current==null) loadingState else if(selectedCountry==null) overviewState else detailState,contentPadding=PaddingValues(bottom=32.dp)) {
        item {if(back==null && selectedCountry==null) WdPageHeader(stringResource(R.string.shell_atlas),Modifier.statusBarsPadding(),eyebrow=stringResource(R.string.country_title))
            else SettingsBackHeader(selectedCountry?.let {CountryRegions.displayName(it,locale)} ?: stringResource(R.string.country_title),
            stringResource(if(selectedCountry!=null) R.string.country_back_days else R.string.country_back_settings),{goBack()},backTag="country-back")}
        if(failure) item {SettingsNotice(card.padding(bottom=12.dp),warning=true) {Text(stringResource(R.string.country_load_error));SettingsTextAction(stringResource(R.string.country_retry),{retry++})}}
        if(current==null && !failure) item {SettingsProgress(stringResource(R.string.country_loading),card.padding(bottom=12.dp))}
        if(selectedCountry!=null) {
            if(current!=null) item {Text(pluralStringResource(R.plurals.country_days_present,selectedDays.size,number.format(selectedDays.size)),Modifier.padding(start=20.dp,end=20.dp,bottom=12.dp).testTag("country-detail-count"),style=WdType.title3,color=wd.textPrimary)}
            itemsIndexed(selectedDays,key={_,it->it.day.toString()}) {index,day->
                Row(card.settingsSegment(index==0,index==selectedDays.lastIndex).testTag("country-day-${day.day}").heightIn(min=48.dp).padding(horizontal=16.dp,vertical=12.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    val shape=RoundedCornerShape(3.dp)
                    Box(Modifier.size(12.dp).clip(shape).background(if(day.recorded) wd.accent else wd.card).border(1.5.dp,wd.accent,shape))
                    Text(day.day.toString(),Modifier.weight(1f),style=WdType.monoBody,color=wd.textPrimary)
                    Text(stringResource(when {day.recorded&&day.manual->R.string.country_source_both;day.manual->R.string.country_source_manual;else->R.string.country_source_recorded}),style=WdType.caption,color=wd.textMuted)
                }
            }
        } else {
            // Country Days board: period segments, then the total in a large serif figure.
            item {WdSegmented(CountryDayPeriod.entries.map {it.name},periodName,{periods.getValue(it)},{periodName=it},card.testTag("country-period"))}
            if(current!=null) item {Column(card.padding(top=12.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(wd.containerLow).padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(number.format(uniqueDays),style=WdType.pageTitle.copy(fontSize=44.sp,lineHeight=48.sp),color=wd.textPrimary)
                Text(pluralStringResource(R.plurals.country_unique_days,uniqueDays,number.format(uniqueDays)),Modifier.testTag("country-summary-days"),style=WdType.caption,color=wd.textMuted)
                Text(pluralStringResource(R.plurals.country_country_count,countries.size,number.format(countries.size)),Modifier.testTag("country-summary-countries"),style=MaterialTheme.typography.bodyMedium,color=wd.textPrimary)
            }}
            item {WdSectionFooter(stringResource(R.string.country_rules))}
            item {WdButton(stringResource(R.string.stay_add),{adding=true},card.padding(top=16.dp).testTag("country-add-stay"),icon=Icons.Outlined.Add)}
            item {WdCard(card.padding(top=12.dp)) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(analysis.running) {
                    SettingsProgress(stringResource(R.string.country_checked,number.format(analysis.progress.completed),number.format(analysis.progress.resolved)))
                    SettingsTextAction(stringResource(R.string.country_stop),analysisController::stop,Modifier.testTag("country-analysis-stop"))
                } else WdButton(stringResource(R.string.country_analyze),{if(active.get()) analyzeConfirmation=true},Modifier.testTag("country-analysis-open"),enabled=settings?.networkGeocoding==true,icon=Icons.Outlined.TravelExplore)
                status?.let {Text(it,Modifier.testTag("country-analysis-status"),style=WdType.caption,color=wd.textPrimary)}
                if(settings?.networkGeocoding==false) Text(stringResource(R.string.country_network_disabled),style=WdType.caption2,color=wd.textMuted)
            }}}
            current?.let {data->
                if(data.unresolvedPoints>0 || data.unresolvedVisits>0) item {SettingsNotice(card.padding(top=12.dp)) {
                    Text(stringResource(R.string.country_unresolved,number.format(data.unresolvedPoints),number.format(data.unresolvedVisits)),Modifier.testTag("country-unresolved"),color=wd.textMuted)
                    Text(stringResource(R.string.country_unresolved_help),style=WdType.caption2,color=wd.textMuted)
                }}
                item {WdSectionHeader(stringResource(R.string.redesign_settings_by_country))}
                if(countries.isEmpty()) item {WdCard(card) {WdEmptyState(stringResource(R.string.country_empty),null,icon=Icons.Outlined.Public)}}
                itemsIndexed(countries,key={_,it->it.key}) {index,country->
                    Column(card.settingsSegment(index==0,index==countries.lastIndex).testTag("country-row-${country.key}").clickable {analysisController.stop();selected=country.key}
                        .padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(CountryRegions.displayName(country.key,locale),Modifier.weight(1f),style=WdType.title3,color=wd.textPrimary)
                            Text(stringResource(R.string.country_view_dates),style=WdType.caption2,color=wd.textMuted)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(18.dp),tint=wd.textMuted)
                        }
                        Text(stringResource(R.string.country_code_days,country.key,pluralStringResource(R.plurals.country_day_count,country.value.size,number.format(country.value.size))),style=WdType.caption,color=wd.textPrimary)
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(wd.dialTrack)) {
                            Box(Modifier.fillMaxWidth((country.value.size.toFloat()/maximum.coerceAtLeast(1)).coerceIn(0.02f,1f)).fillMaxHeight().clip(CircleShape).background(wd.placeInks[index%wd.placeInks.size]))
                        }
                    }
                }
                if(data.days.isNotEmpty()) item {WdButton(stringResource(R.string.country_preview),{preview=true},card.padding(top=12.dp).testTag("country-preview"),icon=Icons.Outlined.Visibility)}
                item {WdSectionHeader(stringResource(R.string.country_manual))}
                if(data.stays.isEmpty()) item {Text(stringResource(R.string.country_manual_help),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)}
                itemsIndexed(data.stays,key={_,it->"stay:${it.id}"}) {index,stay->
                    Row(card.settingsSegment(index==0,index==data.stays.lastIndex).testTag("country-stay-${stay.id}").clickable {editing=stay}.padding(horizontal=16.dp,vertical=12.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        val shape=RoundedCornerShape(3.dp)
                        Box(Modifier.size(12.dp).border(1.5.dp,wd.accent,shape))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(CountryRegions.displayName(stay.countryCode,locale),style=WdType.title3,color=wd.textPrimary)
                            Text(stringResource(R.string.country_stay_range,stay.firstDay.toString(),stay.lastDay.toString()),style=WdType.caption2,color=wd.textMuted)
                        }
                        Text(stringResource(R.string.stay_edit),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.inkRust)
                    }
                }
            }
            item {Box(card.padding(top=20.dp)) {HistoryFileActions(graph,stringResource(R.string.country_export),current?.days?.isNotEmpty()==true) {graph.exports.prepareCountryDays(period.bounds(today))}}}
        }
    }
    }
    if(adding || editing!=null) CountryStayEditor(editing,{adding=false;editing=null},{value,expected->graph.history.saveCountryStay(value,expected)},{expected->graph.history.deleteCountryStay(expected)},recorded=remember(report) {report?.days?.filter {it.recorded}?.map {it.day}?.toSet().orEmpty()})
    if(preview && current!=null) WorkspaceAlertDialog(onDismissRequest={preview=false},title={Text(stringResource(R.string.country_preview_title))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.country_preview_plan_help));Text(countryDaysCsv(current.days.take(100)),style=MaterialTheme.typography.bodySmall)
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
