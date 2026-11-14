package com.bontecou.whereday.export

import com.bontecou.whereday.domain.ExportSchedule
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduledExportPlanTest {
    private val zone=ZoneId.of("America/Los_Angeles")
    private fun local(day:String,time:String)=LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private val now=local("2026-05-04","18:30")
    private fun plan(format:ExportFormat=ExportFormat.csv,interval:Int=3,mode:ScheduledFileMode=ScheduledFileMode.append,
                     cursor:Instant?=local("2026-05-04","12:00"),at:Instant=now)=ScheduledExportPlan.resolve(format,
        ExportOptions(zone=zone),"exports/{year}/track-{datetime}",ExportSchedule(9,0,interval),mode,cursor,at)

    @Test fun rewriteAlwaysCapturesFullCurrentDayAndDailyIgnoresPersistedAppend() {
        for(interval in listOf(0,3)) {
            val p=plan(interval=interval,mode=ScheduledFileMode.rewrite)
            assertEquals(local("2026-05-04","00:00"),p.options.from)
            assertEquals(now,p.options.until)
            assertFalse(p.usesDeltaWindow)
        }
        assertEquals(ScheduledFileMode.rewrite,plan(interval=0).effectiveFileMode)
        assertEquals(local("2026-05-04","00:00"),plan(interval=0).options.from)
    }
    @Test fun intervalAppendUsesSameDayCursorAndClampsPriorOrFutureCursor() {
        assertEquals(local("2026-05-04","12:00"),plan().options.from)
        assertEquals(local("2026-05-04","00:00"),plan(cursor=local("2026-05-03","23:00")).options.from)
        assertEquals(local("2026-05-04","00:00"),plan(cursor=null).options.from)
        assertEquals(now,plan(cursor=now.plusSeconds(1)).options.from)
        assertTrue(plan().shouldAdvanceAppendCursor)
    }
    @Test fun everyFormatMatchesIOSAppendPolicy() {
        ExportFormat.entries.forEach {format ->
            val p=plan(format)
            val appendable=format !in setOf(ExportFormat.gpx,ExportFormat.kml)
            assertEquals(format.name,appendable,p.usesDeltaWindow)
            assertEquals(appendable,p.shouldAdvanceAppendCursor)
            assertEquals(if(appendable) local("2026-05-04","12:00") else local("2026-05-04","00:00"),p.options.from)
        }
    }
    @Test fun filenameIsStableAndExecutionDayDoesNotComeFromOldOccurrence() {
        val p=plan(at=local("2026-05-05","01:00"))
        assertEquals("exports/{year}/track-{date}",p.filenamePattern)
        assertEquals(local("2026-05-05","00:00"),p.options.from)
        assertEquals(local("2026-05-05","01:00"),p.filenameDate)
        assertEquals("track - {date}",FilenamePlanner.dayStable("track"))
    }
    @Test fun civilMidnightUsesActualDSTOffsetAndPreservesOtherOptions() {
        for(day in listOf("2026-03-08","2026-11-01")) {
            val options=ExportOptions(kind=DataKind.points,zone=zone,excludeOutliers=true,pointAltitude=false)
            val at=local(day,"23:30")
            val p=ScheduledExportPlan.resolve(ExportFormat.gpx,options,"track",ExportSchedule(9,0,3),ScheduledFileMode.append,null,at)
            assertEquals(local(day,"00:00"),p.options.from)
            assertEquals(at,p.options.until)
            assertEquals(options.copy(from=p.options.from,until=at),p.options)
        }
    }
    @Test fun outingSubjectAndInferenceAreCapturedWithoutChangingIosAppendWindows() {
        val rules=com.bontecou.whereday.domain.InferenceRules(Duration.ofHours(1),Duration.ofMinutes(5),3)
        for(format in ExportFormat.entries) {
            val p=ScheduledExportPlan.resolve(format,ExportOptions(zone=zone),"{type}-{date}",ExportSchedule(9,0,3),ScheduledFileMode.append,
                local("2026-05-04","12:00"),now,outings=true,inference=rules)
            assertTrue(p.outings);assertEquals(rules,p.inference)
            assertEquals(plan(format).options.from,p.options.from);assertEquals(plan(format).usesDeltaWindow,p.usesDeltaWindow)
        }
        val history=ScheduledExportPlan.resolve(ExportFormat.json,ExportOptions(),"track",ExportSchedule(),ScheduledFileMode.rewrite,null,now,inference=rules)
        assertFalse(history.outings);assertNull(history.inference)
    }
}
