package com.bontecou.whereday.export

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HistoryImportValidationTest {
    private fun points(rows:String)=HistoryImporter.parse("""{"points":[$rows]}""",ExportFormat.json)
    private val valid="""{"id":"10000000-0000-0000-0000-000000000001","latitude":18,"longitude":-66,"timestamp":"2026-10-26T13:00:00Z"}"""
    @Test fun structuredKnownFieldsRejectOnlyTheirRecordAndKeepValidNeighbors() {
        for(field in listOf("latitude","longitude","timestamp","horizontalAccuracy","speed","altitude","isOutlier","id")) {
            val row=com.google.gson.JsonParser.parseString(valid).asJsonObject
            row.add(field,com.google.gson.JsonParser.parseString("""{"unexpected":1}"""))
            val imported=points("$row,$valid")
            assertEquals(field,1,imported.points.size);assertEquals(field,1,imported.errors.size)
            assertTrue(imported.errors.single().contains("Invalid value for $field"))
        }
    }
    @Test fun futureFieldsAreIgnoredAtRootAndRecordLevelsWithoutDiscardingKnownValues() {
        val text="""{"future":{"payload":[1,null,{}]},"points":[${valid.dropLast(1)},"futureArray":[{}],"futureNull":null,"futurePrimitive":true}]}"""
        val imported=HistoryImporter.parse(text,ExportFormat.json)
        assertTrue(imported.errors.isEmpty());assertEquals("10000000-0000-0000-0000-000000000001",imported.points.single().id)
        assertEquals(Instant.parse("2026-10-26T13:00:00Z"),imported.points.single().time)
    }
    @Test fun unixFallbackUsesTheSameResolvedTimeForIdentityAndData() {
        val base=""""latitude":18,"longitude":-66"""
        val invalid=points("""{$base,"timestamp":"invalid","timestampUnix":-0.125}""").points.single()
        val missing=points("""{$base,"timestampUnix":-0.125}""").points.single()
        val iso=points("""{$base,"timestamp":"1969-12-31T23:59:59.875Z"}""").points.single()
        assertEquals(iso,invalid);assertEquals(iso,missing)
    }
    @Test fun unixFractionsKeepNanosecondsAndDoNotSaturateLargeValues() {
        val time=points("""{"latitude":18,"longitude":-66,"timestampUnix":1.123456789}""").points.single().time
        assertEquals(Instant.ofEpochSecond(1,123456789),time)
        for(timestamp in listOf("1e2147483647","-1e100","31556889864403200","\"NaN\"","\"Infinity\"")) {
            val imported=points("""{"latitude":18,"longitude":-66,"timestampUnix":$timestamp},$valid""")
            assertEquals(timestamp,1,imported.points.size);assertEquals(timestamp,1,imported.errors.size)
        }
        assertEquals(Instant.EPOCH,points("""{"latitude":18,"longitude":-66,"timestampUnix":1e-2147483647}""").points.single().time)
    }
    @Test fun validIsoTimeTakesPrecedenceOverInvalidUnixFallback() {
        val row=valid.dropLast(1)+",\"timestampUnix\":\"not a number\"}"
        assertEquals(Instant.parse("2026-10-26T13:00:00Z"),points(row).points.single().time)
    }
    @Test fun csvLiteralPlaceholdersRemainUserText() {
        val text="arrived_at,latitude,longitude,location_name,address,notes\n2026-10-26T13:00:00Z,18,-66,null,Ongoing,Still here\n"
        val visit=HistoryImporter.parse(text,ExportFormat.csv).visits.single()
        assertEquals("null",visit.name);assertEquals("Ongoing",visit.address);assertEquals("Still here",visit.notes)
    }
}
