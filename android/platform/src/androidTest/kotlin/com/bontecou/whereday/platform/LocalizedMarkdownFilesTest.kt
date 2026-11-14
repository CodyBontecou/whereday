package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.export.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.Locale

class LocalizedMarkdownFilesTest {
    @Test fun truncatedMarkdownPreviewUsesOwnedScratchAndClearRetiresIt():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val db=HistoryDatabase.create(context,null);val files=ExportFiles(context,db) {}
        try {
            db.history().put(VisitRow(com.bontecou.whereday.domain.Visit(coordinate=com.bontecou.whereday.domain.Coordinate(18.0,-66.0),arrivedAt=Instant.parse("2026-10-28T12:00:00Z"),name="北".repeat(120_000))))
            val preview=files.preview(ExportFormat.markdown,ExportOptions(kind=DataKind.visits))
            assertEquals(100_000,preview.length)
            val scratch=java.io.File(context.cacheDir,"exports/.markdown-preview")
            assertTrue(scratch.isDirectory);assertTrue(scratch.listFiles()!!.isEmpty())
            files.clearCache();assertFalse(scratch.exists())
        } finally {files.clearCache();db.close()}
    }
    @Test fun deviceClockOverridePreservesCalendarAndDigitPreferences() {
        val locale=Locale.forLanguageTag("th-TH-u-ca-gregory-nu-latn")
        assertEquals("h23",AndroidHistoryLocale.resolve(locale,"24").getUnicodeLocaleType("hc"))
        assertEquals("h12",AndroidHistoryLocale.resolve(locale,"12").getUnicodeLocaleType("hc"))
        assertEquals("gregory",AndroidHistoryLocale.resolve(locale,"24").getUnicodeLocaleType("ca"))
        assertEquals("latn",AndroidHistoryLocale.resolve(locale,"24").getUnicodeLocaleType("nu"))
        assertEquals(locale,AndroidHistoryLocale.resolve(locale,null))
        val explicit=Locale.forLanguageTag("en-US-u-hc-h11")
        assertEquals(explicit,AndroidHistoryLocale.resolve(explicit,"24"))
    }

    @Test fun swiftLocalizedHistoryAndRealRoomPreparedFilesKeepCalendarAndClockSemantics():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val fixtures=instrumentation.context.assets.open("ios-markdown-locales.json").bufferedReader().use {JsonParser.parseReader(it).asJsonArray}
        val previous=Locale.getDefault();var checked=0;val failures=mutableListOf<String>()
        fun displayDates(text:String)=text.lineSequence().mapNotNull {line->
            when {
                line.startsWith("## ") || line.startsWith("**Time Range:**") -> line
                line.startsWith("| ") -> line.split(" | ").getOrNull(0)?.removePrefix("| ")?.takeIf {it.firstOrNull()?.isDigit()==true}
                else -> null
            }
        }.toList()
        try {for(entry in fixtures) {
            val c=entry.asJsonObject;val name=c["name"].asString
            try {
            val locale=Locale.forLanguageTag((c.get("localeTag") ?: c["locale"]).asString.replace('_','-'));Locale.setDefault(locale)
            val zone=ZoneId.of(c["zone"].asString);val kind=DataKind.valueOf(c["kind"].asString);val text=c["text"].asString
            val imported=try {HistoryImporter.parse(text,ExportFormat.markdown,locale,zone)} catch(error:Exception) {throw IllegalStateException(name,error)}
            assertTrue(name+imported.errors,imported.errors.isEmpty())
            if(kind!=DataKind.points) assertEquals(name,c["visits"].asJsonArray.map {Instant.parse(it.asJsonObject["arrivedAt"].asString).truncatedTo(ChronoUnit.MINUTES)},imported.visits.map {it.arrivedAt})
            if(kind!=DataKind.visits) assertEquals(name,c["points"].asJsonArray.map {Instant.parse(it.asJsonObject["time"].asString).truncatedTo(ChronoUnit.SECONDS)},imported.points.map {it.time})
            val cycle=locale.getUnicodeLocaleType("hc") ?: if(locale.language=="ar" || locale==Locale.US) "h12" else "h23"
            val displayLocale=Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("hc",cycle).build()
            val configuration=android.content.res.Configuration(context.resources.configuration).apply {setLocale(displayLocale)}
            val exportContext=context.createConfigurationContext(configuration)
            val db=HistoryDatabase.create(context,null);val files=ExportFiles(exportContext,db) {}
            try {
                imported.visits.forEach {db.history().put(VisitRow(it))};imported.points.forEach {db.history().put(PointRow(it,null))}
                val prepared=files.prepare(ExportFileRequest(setOf(ExportFormat.markdown),ExportOptions(kind=kind,zone=zone),generatedAt=Instant.parse("2026-10-28T12:00:00Z")))
                val actual=prepared.files.single().file.readText()
                assertEquals(name,displayDates(text),displayDates(actual))
                val restored=HistoryImporter.parse(actual,ExportFormat.markdown,locale,zone)
                assertTrue(name+restored.errors,restored.errors.isEmpty())
                assertEquals(name,imported.visits.map {it.arrivedAt},restored.visits.map {it.arrivedAt})
                assertEquals(name,imported.points.map {it.time},restored.points.map {it.time})
                files.discard(prepared);checked++
            } finally {files.clearCache();db.close()}
            } catch(error:Throwable) {if(error is java.util.concurrent.CancellationException) throw error;failures+="$name: $error"}
        }} finally {Locale.setDefault(previous)}
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
        assertEquals(36,checked)
    }
}
