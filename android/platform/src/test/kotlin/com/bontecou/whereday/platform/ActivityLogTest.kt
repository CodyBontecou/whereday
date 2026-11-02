package com.bontecou.whereday.platform

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors

class ActivityLogTest {
    @Test fun boundedHistoryAndExportUseFixedEventsAndUtcTimes() {
        val instant=Instant.parse("2026-10-28T12:00:00Z")
        val log=ActivityLog(2,Clock.fixed(instant,ZoneOffset.UTC))
        log.record(AppEvent.appOpened);log.record(AppEvent.recordingStarted);log.record(AppEvent.recordingStopped)
        assertEquals(listOf(2L,3L),log.snapshot().map {it.id})
        assertEquals(listOf(AppEvent.recordingStarted,AppEvent.recordingStopped),log.snapshot().map {it.event})
        assertEquals("[2026-10-28T12:00:00Z] INFO: Location recording started.\n[2026-10-28T12:00:00Z] INFO: Location recording stopped.",log.exportText())
        assertTrue(log.snapshot().all {it.time==instant})
    }
    @Test fun concurrentWritesKeepUniqueOrderedIdsAndDefaultRetention() {
        val log=ActivityLog();val pool=Executors.newFixedThreadPool(4)
        try {
            val jobs=(1..4).map {pool.submit {repeat(300) {log.record(AppEvent.automationCompleted)}}}
            jobs.forEach {it.get()}
            val entries=log.snapshot()
            assertEquals(500,entries.size)
            assertEquals((701L..1200L).toList(),entries.map {it.id})
        } finally {pool.shutdownNow()}
    }
    @Test fun snapshotsCannotModifyLiveStateAndClearDoesNotReuseIds() {
        val log=ActivityLog();log.record(AppEvent.appOpened)
        val snapshot=log.snapshot()
        try {(snapshot as MutableList).clear();fail("Snapshot was mutable")} catch(_:UnsupportedOperationException) {}
        assertEquals(1,log.snapshot().size)
        log.clear();assertTrue(log.entries.value.isEmpty());assertEquals("",log.exportText())
        log.record(AppEvent.recordingStopped)
        assertEquals(2L,log.snapshot().single().id);assertEquals(1L,snapshot.single().id)
    }
    @Test fun freshProcessOwnerStartsEmptyAndRetentionCannotBecomeUnbounded() {
        assertTrue(ActivityLog().snapshot().isEmpty())
        for(size in listOf(0,-1,501,Int.MAX_VALUE)) {
            try {ActivityLog(size);fail("Invalid retention accepted")} catch(_:IllegalArgumentException) {}
        }
    }
}
