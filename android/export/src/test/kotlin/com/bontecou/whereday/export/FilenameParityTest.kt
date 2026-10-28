package com.bontecou.whereday.export

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class FilenameParityTest {
    @Test fun tokensMatchActualSwiftInFiveZonesIncludingCivilDateBoundaries() {
        val cases=requireNotNull(javaClass.getResourceAsStream("/ios-filenames.json")).bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
        assertEquals(23,cases.size())
        for(value in cases) {
            val c=value.asJsonObject;val now=Instant.parse(c["now"].asString);val zone=ZoneId.of(c["zone"].asString)
            val format=if(c["format"].asString=="md") ExportFormat.markdown else ExportFormat.valueOf(c["format"].asString)
            val result=FilenamePlanner.path(c["pattern"].asString,format,now.atZone(zone).toLocalDate(),c["kind"].asString,c["title"].asString,now,zone)
            if(c["name"].asString=="punctuation-title") {
                // iOS expands a slash in the title into another directory; Android permits folders only in the template.
                assertEquals("Walk- café/park-.csv",c["expected"].asString)
                assertEquals("Walk- café-park-.csv",result)
            } else assertEquals(c["name"].asString,c["expected"].asString,result)
            assertEquals(c["name"].asString,c["dayStable"].asString,FilenamePlanner.dayStable(c["pattern"].asString))
        }
    }
    @Test fun omittedInstantUsesTheSelectedDaysLocalMidnight() {
        val day=LocalDate.parse("2020-02-29");val zone=ZoneId.of("America/Puerto_Rico")
        assertEquals("2020-02-29_00-00-00.csv",FilenamePlanner.path("{datetime}",ExportFormat.csv,day,zone=zone))
    }
}
