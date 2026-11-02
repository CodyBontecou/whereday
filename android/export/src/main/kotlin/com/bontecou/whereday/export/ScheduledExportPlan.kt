package com.bontecou.whereday.export

import com.bontecou.whereday.domain.ExportSchedule
import com.bontecou.whereday.domain.InferenceRules
import java.time.Instant

enum class ScheduledFileMode { rewrite, append }

/** A captured execution window. Retries reuse this value instead of extending an unfinished write. */
data class ScheduledExportPlan(
    val format:ExportFormat,
    val options:ExportOptions,
    val filenamePattern:String,
    val filenameDate:Instant,
    val effectiveFileMode:ScheduledFileMode,
    val usesDeltaWindow:Boolean,
    val outings:Boolean=false,
    val inference:InferenceRules?=null,
) {
    init {require(outings || inference==null) {"Inference settings belong to an outing export."}}
    val shouldAdvanceAppendCursor get()=usesDeltaWindow
    companion object {
        /** Port of IsoMeScheduledExportPlan.resolve and its per-format write policy. */
        fun resolve(format:ExportFormat,baseOptions:ExportOptions,filenamePattern:String,schedule:ExportSchedule,
                    fileMode:ScheduledFileMode,appendCursor:Instant?,now:Instant,outings:Boolean=false,inference:InferenceRules?=null):ScheduledExportPlan {
            val midnight=now.atZone(baseOptions.zone).toLocalDate().atStartOfDay(baseOptions.zone).toInstant()
            val effective=if(schedule.intervalHours>0) fileMode else ScheduledFileMode.rewrite
            val delta=effective==ScheduledFileMode.append && format !in setOf(ExportFormat.gpx,ExportFormat.kml)
            val from=if(delta) minOf(maxOf(appendCursor ?: midnight,midnight),now) else midnight
            return ScheduledExportPlan(format,baseOptions.copy(from=from,until=now),FilenamePlanner.dayStable(filenamePattern),now,effective,delta,outings,inference.takeIf {outings})
        }
    }
}
