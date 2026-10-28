package com.bontecou.whereday

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.data.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.launch
import java.time.*

@Composable fun TimelineScreen(graph: AppGraph, model: JournalViewModel, metric: Boolean, showOnMap: (RecordingSession) -> Unit) {
    val day by model.timelineDay.collectAsStateWithLifecycle()
    val state by model.timelineWindow.collectAsStateWithLifecycle()
    var selectedSession by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Visit?>(null) }
    var adding by remember { mutableStateOf(false) }
    if(selectedSession != null) {
        val header=(state as? WindowState.Ready)?.window?.inferred?.find { it.id==selectedSession }
        if(selectedSession!!.startsWith("inferred:") && header==null) {
            Column(Modifier.padding(20.dp)) { TextButton(onClick={ selectedSession=null }) { Text("Back to timeline") }; Text("This inferred outing is no longer in the current timeline.") }
        } else {
            val window=(state as? WindowState.Ready)?.window
            val chronological=(window?.sessions.orEmpty().map {it.id to it.startedAt}+window?.inferred.orEmpty().map {it.id to it.startedAt})
                .sortedWith(compareBy({it.second},{it.first}))
            val sequence=(chronological.indexOfFirst {it.first==selectedSession}+1).coerceAtLeast(1)
            OutingDetailScreen(graph,selectedSession!!,metric,{ selectedSession=null },showOnMap,header,sequence)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={ model.timelineDay.value=day.minusDays(1) }) { Text("Previous") }
            TextButton(onClick={ model.timelineDay.value=LocalDate.now() }) { Text(day.toString()) }
            TextButton(onClick={ model.timelineDay.value=day.plusDays(1) }) { Text("Next") }
        } }
        item { OutlinedButton(onClick={ adding=true }) { Icon(Icons.Outlined.Add,null); Text("Add past visit") } }
        when(val current=state) {
            WindowState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            is WindowState.Failed -> item { Text(current.message); TextButton(onClick=model::retry) { Text("Try again") } }
            is WindowState.Ready -> {
                val window=current.window
                item {
                    val outings=window.sessions.size+window.inferred.size
                    val visits=window.visits.size
                    Text("$outings ${if(outings==1) "outing" else "outings"} · $visits ${if(visits==1) "visit" else "visits"} · ${distanceText(window.summary.distanceMeters,metric)}",style=MaterialTheme.typography.titleMedium)
                }
                val entries=(window.visits.map { TimelineEntry(it.id,it.arrivedAt,visit=it) }+window.sessions.map { TimelineEntry(it.id,it.startedAt,session=it) }+window.inferred.map { TimelineEntry(it.id,it.startedAt,inferred=it) }).sortedByDescending { it.time }
                if(entries.isEmpty()) item { Text("No outings or visits for this day.",style=MaterialTheme.typography.bodyLarge) }
                items(entries,key={ if(it.visit!=null) "visit:${it.id}" else "session:${it.id}" }) { entry ->
                    val visit=entry.visit
                    if(visit!=null) {
                        Card(Modifier.fillMaxWidth().clickable { editing=visit }) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(visit.name ?: "Visit",style=MaterialTheme.typography.titleMedium)
                            Text("${timeText(visit.arrivedAt)} → ${visit.departedAt?.let(::timeText) ?: "Still here"}")
                            visit.address?.let { Text(it) }; visit.notes?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            Text(visit.confirmation.name.replaceFirstChar { it.uppercase() },style=MaterialTheme.typography.labelSmall)
                        } }
                    } else {
                        val session=entry.session ?: entry.inferred!!.let { RecordingSession(it.id,it.startedAt,it.endedAt,name="Inferred outing") }
                        ElevatedCard(Modifier.fillMaxWidth().clickable { selectedSession=session.id }) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(session.name ?: "Outing",style=MaterialTheme.typography.titleMedium)
                            Text("${timeText(session.startedAt)} → ${session.endedAt?.let(::timeText) ?: "Recording"}")
                            entry.inferred?.let { Text("${it.summary.count} points · ${distanceText(it.summary.distanceMeters,metric)}",style=MaterialTheme.typography.bodyMedium) }
                            session.notes?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            Text("View outing",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                        } }
                    }
                }
            }
        }
    }
    if(adding || editing!=null) {
        val expected=editing
        VisitEditor(graph,expected,{adding=false;editing=null},{value,reuse->graph.history.saveVisit(value,expected,reuse)},expected?.let {value->{graph.history.deleteVisit(value)}})
    }
}
private data class TimelineEntry(val id: String,val time: Instant,val visit: Visit?=null,val session: RecordingSession?=null,val inferred: InferredOutingHeader?=null)

