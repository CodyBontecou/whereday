package com.bontecou.whereday.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bontecou.whereday.export.*
import com.google.gson.JsonObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class AutomationProtocolTest {
    private fun invalid(raw:String) {
        try {AutomationRequest.decode(raw);fail("Accepted invalid request")} catch(_:AutomationInvalidRequest) { }
    }
    @Test fun manualCheckInAndCheckOutCommandsAreAvailable() {
        val checkIn=AutomationRequest.decode("""{"version":1,"command":"checkIn","name":"Cafe","latitude":18.0,"longitude":-66.0}""")
        assertEquals("checkIn",checkIn.command.name)
        assertEquals("checkOut",AutomationRequest.decode("""{"version":1,"command":"checkOut"}""").command.name)
    }
    @Test fun manualParametersAreStrictExclusiveAndRedacted() {
        val saved=AutomationRequest.decode("""{"version":1,"command":"checkIn","savedPlaceId":"saved"}""")
        assertEquals("saved",saved.savedPlaceId);assertNull(saved.coordinate);assertNull(saved.name);assertFalse(saved.allowOverlap)
        assertEquals(AutomationScope.manualVisits,saved.command.scope)
        val current=AutomationRequest.decode("""{"version":1,"command":"checkIn","name":"Cafe","allowOverlap":true}""")
        assertNull(current.coordinate);assertTrue(current.allowOverlap);assertFalse(current.toString().contains("Cafe"))
        for(extra in listOf("",",\"name\":\" \"",",\"name\":\"Cafe\",\"latitude\":18",
            ",\"name\":\"Cafe\",\"latitude\":\"18\",\"longitude\":-66",",\"name\":\"Cafe\",\"latitude\":91,\"longitude\":-66",
            ",\"savedPlaceId\":\"saved\",\"latitude\":18,\"longitude\":-66",",\"savedPlaceId\":null",
            ",\"name\":\"Cafe\",\"allowOverlap\":\"true\"",",\"name\":\"Cafe\",\"arrivedAt\":null",
            ",\"name\":\"Cafe\",\"arrivedAt\":\"+999999999-12-31T00:00:00Z\"",",\"name\":\"Cafe\",\"id\":\"chosen\""))
            invalid("{\"version\":1,\"command\":\"checkIn\"$extra}")
        invalid("""{"version":1,"command":"checkOut","id":null}""")
        invalid("""{"version":1,"command":"checkOut","allowOverlap":true}""")
        invalid("""{"version":1,"command":"savedPlaces","from":"2026-10-31T00:00:00Z"}""")
        assertEquals(AutomationScope.places,AutomationCommand.savedPlaces.scope)
        assertEquals(AutomationScope.visits,AutomationCommand.openManualVisits.scope)
    }
    @Test fun everyCommandHasOneExplicitScopeAndNoImplicitExtraFields() {
        val extras=mapOf(AutomationCommand.checkIn to ",\"name\":\"Cafe\",\"latitude\":18.0,\"longitude\":-66.0",AutomationCommand.renameVisit to ",\"id\":\"visit\",\"name\":\" New \",",
            AutomationCommand.renameOuting to ",\"id\":\"outing\",\"name\":\" New \",",
            AutomationCommand.renameCurrentOuting to ",\"name\":\" New \",")
        for(command in AutomationCommand.entries.filter {it!=AutomationCommand.export}) {
            val extra=extras[command]?.removeSuffix(",").orEmpty()
            val request=AutomationRequest.decode("{\"version\":1,\"command\":\"${command.name}\"$extra}")
            assertEquals(command,request.command)
            assertEquals(command.scope,request.command.scope)
            if(command.name.startsWith("rename")) assertEquals("New",request.name)
            assertFalse(request.toString().contains("New"))
            invalid("{\"version\":1,\"command\":\"${command.name}\",\"unexpected\":true$extra}")
        }
    }
    @Test fun strictJsonRejectsDuplicateKeysAtAllDepthsTrailingDataAndNonIntegerVersions() {
        for(raw in listOf("{}","[]","null","{\"version\":1,\"command\":\"statistics\",\"command\":\"visits\"}",
            "{\"version\":1.0,\"command\":\"statistics\"}","{\"version\":1e0,\"command\":\"statistics\"}",
            "{\"version\":\"1\",\"command\":\"statistics\"}","{\"version\":2,\"command\":\"statistics\"}",
            "{version:1,command:'statistics'}","{\"version\":1,\"command\":\"statistics\",}",
            "{\"version\":1,\"command\":\"statistics\"}{}"," ".repeat(32769))) invalid(raw)
        try {AutomationJson.objectValue("{\"outer\":{\"key\":1,\"key\":2}}");fail("Duplicate nested key")} catch(_:IllegalArgumentException) { }
    }
    @Test fun rangesDefaultToExplicitZoneAndRespectDstAndInclusiveExplicitBounds() {
        val clock=Clock.fixed(Instant.parse("2026-03-08T12:00:00Z"),ZoneOffset.UTC)
        val request=AutomationRequest.decode("{\"version\":1,\"command\":\"visits\",\"zone\":\"America/New_York\"}")
        val day=request.range(clock,ZoneId.of("Asia/Tokyo"))
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"),day.from)
        assertEquals(Duration.ofHours(23),Duration.between(day.from,day.through.plusNanos(1)))
        val explicit=AutomationRequest.decode("{\"version\":1,\"command\":\"outings\",\"from\":\"2026-01-01T00:00:00Z\",\"through\":\"2026-01-01T00:00:00Z\"}")
        assertEquals(explicit.range(clock).from,explicit.range(clock).through)
        for(extra in listOf("\"from\":\"2026-01-01T00:00:00Z\"","\"through\":null","\"zone\":\"invalid/zone\"","\"from\":3,\"through\":4","\"from\":\"2026-02-01T00:00:00Z\",\"through\":\"2026-01-01T00:00:00Z\""))
            invalid("{\"version\":1,\"command\":\"visits\",$extra}")
    }
    @Test fun renameRequiresNonemptyStringsAndCannotAttachUnapprovedParameters() {
        for(extra in listOf("",",\"id\":\"v\"",",\"id\":3,\"name\":\"x\"",",\"id\":\"v\",\"name\":null",",\"id\":\"v\",\"name\":\" \",",",\"id\":\"\",\"name\":\"x\"")) {
            val cleaned=extra.removeSuffix(",")
            invalid("{\"version\":1,\"command\":\"renameVisit\"$cleaned}")
        }
        invalid("{\"version\":1,\"command\":\"currentOuting\",\"from\":\"2026-01-01T00:00:00Z\",\"through\":\"2026-01-02T00:00:00Z\"}")
    }
    @Test fun exportUsesCompleteExistingSelectionAndNeverDefaultsMissingOrInvalidFilters() {
        for(format in ExportFormat.entries) {
            val selection=ExportSelection(format=format,options=ExportOptions(from=Instant.parse("2026-01-01T00:00:00Z"),until=Instant.parse("2026-01-02T00:00:00Z"),visitNotes=false),splitDays=true)
            val raw="{\"version\":1,\"command\":\"export\",\"selection\":${ExportSelectionCodec.encode(selection)}}"
            assertEquals(selection,AutomationRequest.decode(raw).selection)
            invalid(raw.replace("\"visitNotes\":false","\"visitNotes\":false,\"visitNotes\":true"))
            invalid(raw.replace("\"visitNotes\":false","\"visitNotes\":false,\"ignoredFilter\":false"))
        }
        invalid("{\"version\":1,\"command\":\"export\"}")
        invalid("{\"version\":1,\"command\":\"export\",\"selection\":{}}")
    }
}
