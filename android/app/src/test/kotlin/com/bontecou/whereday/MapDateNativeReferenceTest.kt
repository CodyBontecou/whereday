package com.bontecou.whereday

import com.bontecou.whereday.domain.HistoryRange
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.time.temporal.ChronoUnit

/** Replays the iOS 2.0 range-calendar taps recorded by android/parity/ios-custom-dates. */
class MapDateNativeReferenceTest {
    private val root=javaClass.getResourceAsStream("/ios-custom-date-edits.json")!!.bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
    private val reference=Instant.parse("2001-01-01T00:00:00Z")

    // Mid-month, the first of a month (the span and month presets cross back a month), and the native run's own day.
    private val clocks=listOf("2026-11-17T15:30:12.345Z","2026-12-01T09:05:06.789Z","2026-11-04T18:00:00.000Z").map(Instant::parse)

    @Test fun calendarTapsMatchTheObservedNativeRanges() {
        assertEquals(18,root["cases"].asJsonArray.size())
        for(now in clocks) for(element in root["cases"].asJsonArray) replay(element.asJsonObject,now)
    }

    @Test fun exactCasesMatchTheNativeSecondsBitForBit() {
        for(element in root["cases"].asJsonArray) {
            val value=element.asJsonObject
            if(!value.has("native")) continue
            val (lower,upper)=value["native"].asString.split('|').map(String::toDouble)
            val expected=value["expected"].asJsonObject
            assertEquals(Instant.parse(expected["from"].asString),reference.plusMillis((lower*1000).toLong()))
            // Swift's closed upper bound is the exclusive end's nextDown.
            assertEquals(Instant.parse(expected["until"].asString),reference.plusMillis((Math.nextUp(upper)*1000).toLong()))
        }
    }

    private fun replay(value:JsonObject,now:Instant) {
        val name=value["name"].asString
        val zone=ZoneId.of(value["zone"].asString)
        val today=now.atZone(zone).toLocalDate()
        // The harness binds a plain closed range with no active preset, by default today's start through now.
        val window=if(value.has("first")) HistoryRange(Instant.parse(value["first"].asString),Instant.parse(value["last"].asString).plusMillis(1))
            else HistoryRange(today.atStartOfDay(zone).toInstant(),now.truncatedTo(ChronoUnit.MILLIS).plusMillis(1))
        var draft=MapRangeDraft.open(MapSelection("Custom",outingRange=window),now,zone)
        assertNull(name,draft.preset)
        for(stepElement in value["steps"].asJsonArray) {
            val step=stepElement.asJsonObject
            draft=when {
                step.has("day")->draft.tap(LocalDate.parse(step["day"].asString),now,zone)
                step.has("daysFromToday")->draft.tap(today.plusDays(step["daysFromToday"].asLong),now,zone)
                step.has("dayOfThisMonth")->draft.tap(today.withDayOfMonth(step["dayOfThisMonth"].asInt),now,zone)
                step.has("preset")->draft.select(step["preset"].asString,now,zone)
                step.has("month")->draft.showMonth(step["month"].asLong)
                step.has("reset")->draft.select("Today",now,zone)
                else->error("Unknown step in $name")
            }
        }
        val days=value["titleDays"].asJsonPrimitive.let {if(it.isNumber) it.asInt else today.dayOfMonth}
        assertEquals("$name title",days,draft.applyDayCount(null,now,zone))
        val applied=draft.apply(now,zone)
        val expected=value["expected"].asJsonObject
        when {
            expected.has("from")->{
                assertEquals(name,"Custom",applied.preset)
                assertEquals(name,HistoryRange(Instant.parse(expected["from"].asString),Instant.parse(expected["until"].asString)),applied.range(now,zone))
            }
            expected.has("preset")->{
                val preset=if(today.dayOfMonth==1 && expected.has("presetOnFirstOfMonth")) expected["presetOnFirstOfMonth"].asString else expected["preset"].asString
                assertEquals("$name at $now",MapSelection(preset),applied)
            }
            else->{
                val from=today.minusDays(expected["clampedFromDaysAgo"].asLong).atStartOfDay(zone).toInstant()
                val range=applied.range(now,zone)
                assertEquals("$name at $now",HistoryRange(from,now.truncatedTo(ChronoUnit.MILLIS).plusMillis(1)),range)
            }
        }
    }
}
