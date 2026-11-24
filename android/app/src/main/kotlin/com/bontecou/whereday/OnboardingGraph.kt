package com.bontecou.whereday

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.time.LocalDate

/** Onboarding's real services: the same repositories and controllers the journal uses after setup. */
internal fun onboardingServices(graph:AppGraph)=OnboardingServices(
    locate={graph.currentVisitLocation.locate()?.let {OnboardingFix(it.coordinate.latitude,it.coordinate.longitude,it.accuracyMeters)}},
    // Default radius (150 m) from SavedPlace, as the place editor uses.
    savePlace={place,expected->graph.savedPlaces.save(place,expected)},
    saveIntents={intents->graph.preferences.update {it.copy(onboardingIntents=OnboardingIntent.encode(intents))}},
    saveExportFormat={format->
        val state=graph.exportSettings.state.value
        if(state.ready && state.selection.format!=format) graph.exportSettings.update(state.selection.copy(format=format,additionalFormats=state.selection.additionalFormats-format))
    },
    photos=OnboardingPhotos(
        granted={graph.photoAccess.current()!=PhotoLibraryAccess.None},
        // The library permissions the Photos screen already asks for, plus original locations so matching sees GPS tags.
        permissions=graph.photoAccess.requestPermissions()+Manifest.permission.ACCESS_MEDIA_LOCATION,
        enabled=graph.automaticPhotos.state.map {it.enabled},
        running=graph.automaticPhotos.state.map {it.running},
        enable={on,range->
            // The automatic sync scans its map range; widen it to the heatmap's 12 weeks before turning it on.
            if(on) graph.automaticPhotos.mapRange(range)
            graph.automaticPhotos.configure(on)
        },
        dayCounts={range,zone->
            graph.photos.changes().onStart {emit(Unit)}.map {
                val counts=mutableMapOf<LocalDate,Int>()
                graph.photos.forEachMomentPage(range) {page->page.forEach {moment->
                    val day=moment.takenAt.atZone(zone).toLocalDate()
                    counts[day]=(counts[day] ?: 0)+1
                }}
                counts.toMap()
            }.flowOn(Dispatchers.IO)
        }),
    importer=OnboardingImporter(
        active={
            val importing by graph.historyImports.state.collectAsStateWithLifecycle()
            val restoring by graph.backupRestore.state.collectAsStateWithLifecycle()
            importing.phase!=HistoryImportPhase.idle || restoring.phase!=BackupRestorePhase.idle
        },
        content={
            val importing by graph.historyImports.state.collectAsStateWithLifecycle()
            if(importing.phase!=HistoryImportPhase.idle) HistoryImportScreen(graph.historyImports) else BackupRestoreScreen(graph.backupRestore)
        },
        select={context,uris->
            val locale=AndroidHistoryLocale.capture(context)
            graph.backupRestore.select(uris.map {AndroidHistoryImportInput(context,it)}) {single->graph.historyImports.select(single,locale=locale)}
        }),
)
