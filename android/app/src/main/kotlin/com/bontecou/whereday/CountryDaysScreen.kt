package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.util.Locale

@Composable fun CountryDaysScreen(graph: AppGraph, back: () -> Unit) {
    var periodName by rememberSaveable { mutableStateOf(CountryDayPeriod.year.name) }
    val period=CountryDayPeriod.valueOf(periodName)
    var report by remember { mutableStateOf<CountryDayReport?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CountryStay?>(null) }
    var preview by remember { mutableStateOf(false) }
    var analyzeConfirmation by remember { mutableStateOf(false) }
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
    val analysis by graph.countryAnalysis.state.collectAsStateWithLifecycle()
    var today by remember { mutableStateOf(LocalDate.now()) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit) { while(true) { kotlinx.coroutines.delay(30_000); today=LocalDate.now() } }
    LaunchedEffect(period,today,retry) {
        report=null; failure=null
        try { withContext(Dispatchers.IO) { graph.history.observeCountryDays(period.bounds(today)).collect { report=it } } }
        catch (e:Exception) { if(e is CancellationException) throw e; failure="Country days could not be loaded. Try again." }
    }
    BackHandler { if(selected!=null) selected=null else back() }
    val current=report
    val selectedDays=current?.days?.filter { it.countryCode==selected }.orEmpty()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick={ if(selected!=null) selected=null else back() }) { Text(if(selected!=null) "Back to Country Days" else "Back to settings") } }
        item { Text(selected?.let(::countryName) ?: "Country Days",style=MaterialTheme.typography.headlineMedium) }
        if(selected!=null) {
            item { Text("${selectedDays.size} ${if(selectedDays.size==1) "day" else "days"} present",style=MaterialTheme.typography.titleMedium) }
            items(selectedDays,key={ it.day.toString() }) { day -> ListItem(headlineContent={ Text(day.day.toString()) },supportingContent={ Text(day.sourceLabel()) }) }
        } else {
            item { Choice("Period",period.title,CountryDayPeriod.entries.map { it.title }) { title -> periodName=CountryDayPeriod.entries.first { it.title==title }.name } }
            item { Text("Each local date counts once per country. Travel days can count in more than one country. Open visits and unverified departures count only the arrival date; route points can add other dates. Gaps are not filled.") }
            item { OutlinedButton(onClick={ adding=true }) { Text("Add country stay") } }
            item {
                if(analysis.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${analysis.progress.completed} checked · ${analysis.progress.resolved} identified")
                    TextButton(onClick=graph.countryAnalysis::stop) { Text("Stop analysis") }
                } else OutlinedButton(enabled=settings?.networkGeocoding==true,onClick={ analyzeConfirmation=true }) { Text("Analyze saved locations") }
                analysis.progress.message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                if(settings?.networkGeocoding==false) Text("Network geocoding is disabled. Enable it in Settings to analyze locations, or add manual stays.",style=MaterialTheme.typography.bodySmall)
            }
            failure?.let { message -> item { Text(message,color=MaterialTheme.colorScheme.error); TextButton(onClick={ retry++ }) { Text("Try again") } } }
            if(current==null && failure==null) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Calculating country days…") }
            current?.let { data ->
                val countries=data.days.groupBy { it.countryCode }.entries.sortedWith(compareByDescending<Map.Entry<String,List<CountryPresence>>> { it.value.size }.thenBy { countryName(it.key) })
                if(data.unresolvedPoints>0 || data.unresolvedVisits>0) item {
                    Text("${data.unresolvedPoints} points and ${data.unresolvedVisits} visits need a verified country and local time zone. They are excluded from recorded totals.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Analyze saved locations to identify their countries. Completed results are saved on this phone.",style=MaterialTheme.typography.bodySmall)
                }
                if(countries.isEmpty()) item { Text("No country days yet",style=MaterialTheme.typography.titleMedium) }
                items(countries,key={ it.key }) { country ->
                    OutlinedCard(Modifier.fillMaxWidth().clickable { selected=country.key }) {
                        ListItem(headlineContent={ Text(countryName(country.key)) },supportingContent={ Text("${country.key} · ${country.value.size} ${if(country.value.size==1) "day" else "days"}") },trailingContent={ Text("View dates") })
                    }
                }
                if(data.days.isNotEmpty()) item { OutlinedButton(onClick={ preview=true }) { Text("Preview country CSV") } }
                item { HorizontalDivider(); Text("Manual stays",style=MaterialTheme.typography.titleLarge) }
                if(data.stays.isEmpty()) item { Text("Add missing dates or known stays. Overlapping dates count only once.") }
                items(data.stays,key={ "stay:${it.id}" }) { stay -> OutlinedCard(Modifier.fillMaxWidth().clickable { editing=stay }) {
                    ListItem(headlineContent={ Text(countryName(stay.countryCode)) },supportingContent={ Text("${stay.firstDay} → ${stay.lastDay}") },trailingContent={ Text("Edit stay") })
                } }
            }
            item {HistoryFileActions(graph,"Export country CSV",current?.days?.isNotEmpty()==true) {graph.exports.prepareCountryDays(period.bounds(today))}}
        }
    }
    if(adding || editing!=null) CountryStayEditor(editing,{ adding=false; editing=null },{ stay -> scope.launch {
        try { graph.history.dao.put(StayRow(stay)); adding=false; editing=null }
        catch(e:Exception) { if(e is CancellationException) throw e; failure="Country stay could not be saved." }
    } },editing?.let { stay -> { scope.launch {
        try { graph.history.dao.deleteStay(stay.id); editing=null }
        catch(e:Exception) { if(e is CancellationException) throw e; failure="Country stay could not be deleted." }
    } } })
    if(preview && current!=null) AlertDialog(onDismissRequest={ preview=false },title={ Text("Country CSV preview") },text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Preview shows the first 100 local dates. Export country CSV includes all dates in the selected period and requires verified lifetime access.")
            Text(countryDaysCsv(current.days.take(100)),style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={ TextButton(onClick={ preview=false }) { Text("Done") } })
    if(analyzeConfirmation) AlertDialog(onDismissRequest={ analyzeConfirmation=false },title={ Text("Analyze saved locations?") },text={
        Text("Coordinates are sent to your device’s geocoding service to identify countries. Geographic time zones are calculated on this phone from bundled boundaries. Results are estimates; unavailable locations stay unresolved. You can stop and continue later.")
    },confirmButton={ TextButton(onClick={ analyzeConfirmation=false; graph.countryAnalysis.start() }) { Text("Start analysis") } },dismissButton={ TextButton(onClick={ analyzeConfirmation=false }) { Text("Cancel") } })
}

private fun countryName(code: String) = Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault()).ifBlank { code }

@Composable private fun CountryStayEditor(stay: CountryStay?,dismiss: () -> Unit,save: (CountryStay)->Unit,delete: (() -> Unit)?) {
    var code by remember { mutableStateOf(stay?.countryCode ?: Locale.getDefault().country.takeIf { it in Locale.getISOCountries() } ?: "US") }
    var first by remember { mutableStateOf(stay?.firstDay ?: LocalDate.now()) }
    var last by remember { mutableStateOf(stay?.lastDay ?: LocalDate.now()) }
    var chooseCountry by remember { mutableStateOf(false) }
    var chooseDates by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=dismiss,title={ Text(if(stay==null) "Add country stay" else "Edit country stay") },text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Dates are local civil dates in the chosen country.")
            OutlinedButton(onClick={ chooseCountry=true }) { Text("Country: ${countryName(code)} ($code)") }
            Text("First date: $first"); Text("Last date: $last")
            OutlinedButton(onClick={ chooseDates=true }) { Text("Choose stay dates") }
            if(delete!=null) TextButton(onClick={ confirmDelete=true }) { Text("Delete country stay",color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={ TextButton(onClick={ save(CountryStay(id=stay?.id ?: java.util.UUID.randomUUID().toString(),countryCode=code,firstDay=first,lastDay=last)) }) { Text("Save stay") } },dismissButton={ TextButton(onClick=dismiss) { Text("Cancel") } })
    if(chooseCountry) CountrySelector(code,{ chooseCountry=false }) { code=it; chooseCountry=false }
    if(chooseDates) CustomDateRange(first,last,{ chooseDates=false }) { a,b -> first=a; last=b; chooseDates=false }
    if(confirmDelete) AlertDialog(onDismissRequest={ confirmDelete=false },title={ Text("Delete this country stay?") },text={ Text("Recorded history remains. Only these manually added dates are removed.") },confirmButton={ TextButton(onClick={ delete?.invoke() }) { Text("Delete stay") } },dismissButton={ TextButton(onClick={ confirmDelete=false }) { Text("Cancel") } })
}

@Composable private fun CountrySelector(selected: String,dismiss: () -> Unit,choose: (String)->Unit) {
    var query by remember { mutableStateOf("") }
    val countries=remember { Locale.getISOCountries().map { it to countryName(it) }.sortedBy { it.second } }
    AlertDialog(onDismissRequest=dismiss,title={ Text("Choose country") },text={ Column {
        OutlinedTextField(query,{ query=it },label={ Text("Search countries") },singleLine=true)
        LazyColumn(Modifier.heightIn(max=320.dp)) {
            items(countries.filter { it.first.contains(query,true) || it.second.contains(query,true) },key={ it.first }) { (code,name) ->
                TextButton(onClick={ choose(code) },modifier=Modifier.fillMaxWidth()) { Text("$name ($code)${if(code==selected) " ✓" else ""}") }
            }
        }
    } },confirmButton={ TextButton(onClick=dismiss) { Text("Cancel") } })
}
