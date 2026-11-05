package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.LocalDate

class CountryCsvParityTest {
    @Test fun streamingAndPreviewCsvMatchActualSwiftBytes() {
        val cases=requireNotNull(javaClass.getResourceAsStream("/ios-country-csv.json")).bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
        assertEquals(7,cases.size())
        for(value in cases) {
            val c=value.asJsonObject
            val days=c["days"].asJsonArray.map {it.asJsonObject.let {row ->CountryPresence(row["country"].asString,LocalDate.parse(row["day"].asString),row["recorded"].asBoolean,row["manual"].asBoolean)}}
            val expected=c["expected"].asString.toByteArray(Charsets.UTF_8)
            val writer=StringWriter();writeCountryDaysCsv(writer,days)
            assertArrayEquals(c["name"].asString,expected,writer.toString().toByteArray(Charsets.UTF_8))
            assertArrayEquals(c["name"].asString,expected,countryDaysCsv(days).toByteArray(Charsets.UTF_8))
        }
    }
}
