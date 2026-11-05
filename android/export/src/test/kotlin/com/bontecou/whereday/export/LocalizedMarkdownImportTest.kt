package com.bontecou.whereday.export

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.Locale

class LocalizedMarkdownImportTest {
    @Test fun actualSwiftLocaleFilesRestoreGregorianInstantsAndWireMeasurements() {
        val cases=requireNotNull(javaClass.getResourceAsStream("/ios-history-tables.json")).bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
        var checked=0
        for(entry in cases) {
            val c=entry.asJsonObject
            if(!c["name"].asString.startsWith("markdown-locale-")) continue
            val locale=Locale.forLanguageTag((c.get("localeTag") ?: c["locale"]).asString.replace('_','-'))
            val imported=HistoryImporter.parse(c["text"].asString,ExportFormat.markdown,locale,ZoneId.of(c["zone"].asString))
            val kind=c["kind"].asString;assertTrue(c["name"].asString+imported.errors,imported.errors.isEmpty())
            if(kind!="points") {
                val expected=c["visits"].asJsonArray.map {Instant.parse(it.asJsonObject["arrivedAt"].asString).truncatedTo(ChronoUnit.MINUTES)}
                assertEquals(c["name"].asString,expected,imported.visits.map {it.arrivedAt})
                val departed=c["visits"].asJsonArray[0].asJsonObject.get("departedAt")?.asString?.let {Instant.parse(it).truncatedTo(ChronoUnit.MINUTES)}
                assertEquals(c["name"].asString,departed,imported.visits.first().departedAt)
            }
            if(kind!="visits") {
                assertEquals(c["name"].asString,c["points"].asJsonArray.map {Instant.parse(it.asJsonObject["time"].asString).truncatedTo(ChronoUnit.SECONDS)},imported.points.map {it.time})
                assertEquals(1.2,imported.points.first().speed!!,0.0);assertEquals(5.7,imported.points.first().accuracy,0.0)
            }
            checked++
        }
        assertEquals(36,checked)
    }
    @Test fun formerAndroidArabicAndJapaneseHeadingsRemainReadable() {
        for((locale,text) in listOf(
            Locale.forLanguageTag("ar-EG") to "## الأربعاء، 28 أكتوبر 2026\n| Time | Lat | Lon |\n| 6:00 ص | 18 | -66 |",
            Locale.JAPAN to "## 2026年10月28日水曜日\n| Time | Lat | Lon |\n| 6:00:00 | 18 | -66 |",
            Locale.forLanguageTag("th-TH") to "## วันพุธที่ 28 ตุลาคม ค.ศ. 2026\n| Time | Lat | Lon |\n| 06:00:00 | 18 | -66 |")) {
            val result=HistoryImporter.parse(text,ExportFormat.markdown,locale,ZoneOffset.UTC)
            assertEquals(Instant.parse("2026-10-28T06:00:00Z"),result.points.single().time)
        }
    }
}
