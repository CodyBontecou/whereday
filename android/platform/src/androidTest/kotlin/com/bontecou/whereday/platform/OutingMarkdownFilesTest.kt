package com.bontecou.whereday.platform

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.google.gson.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class OutingMarkdownFilesTest {
    @Test fun realRoomOutingPagesMatchSwiftAcrossLocalClocksAndPreserveUserTitles():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val cases=instrumentation.context.assets.open("ios-outing-markdown.json").bufferedReader().use {JsonParser.parseReader(it).asJsonArray}
        val failures=mutableListOf<String>();var checked=0
        for(entry in cases) {
            val c=entry.asJsonObject;val name=c["name"].asString
            try {
                fun JsonObject.text(key:String)=get(key)?.takeUnless {it.isJsonNull}?.asString
                val locale=Locale.forLanguageTag((c.get("localeTag") ?: c["locale"]).asString.replace('_','-'))
                val cycle=locale.getUnicodeLocaleType("hc") ?: if(locale.language=="ar" || locale==Locale.US) "h12" else "h23"
                val display=Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("hc",cycle).build()
                val configured=context.createConfigurationContext(Configuration(context.resources.configuration).apply {setLocale(display)})
                val zone=ZoneId.of(c["zone"].asString)
                fun transform(array:JsonArray,names:Map<String,String>,visit:Boolean)=JsonArray().apply {array.forEach {raw->
                    val row=raw.asJsonObject.deepCopy()
                    names.forEach {(old,new)->row.remove(old)?.let {row.add(new,it)}}
                    if(visit) {if(!row.has("source")) row.addProperty("source","automatic")}
                    else if(!row.has("horizontalAccuracy")) row.addProperty("horizontalAccuracy",5.0)
                    add(row)
                }}
                val root=JsonObject().apply {
                    add("visits",transform(c["visits"].asJsonArray,mapOf("name" to "locationName","confirmation" to "confirmationStatus","originalName" to "originalLocationName","detectedName" to "detectedLocationName"),true))
                    add("points",transform(c["points"].asJsonArray,mapOf("lat" to "latitude","lon" to "longitude","time" to "timestamp","accuracy" to "horizontalAccuracy","outlier" to "isOutlier"),false))
                }
                val history=HistoryImporter.parse(root.toString(),ExportFormat.json)
                val db=HistoryDatabase.create(context,null);val files=ExportFiles(configured,db) {}
                try {
                    val start=Instant.parse(c["start"].asString);val end=c.text("end")?.let(Instant::parse)
                    db.history().put(SessionRow(RecordingSession(c["id"].asString,start,end,c.text("title"),c.text("notes"))))
                    history.points.forEach {db.history().put(PointRow(it,c["id"].asString))};history.visits.forEach {db.history().put(VisitRow(it))}
                    val prepared=files.prepareOuting(c["id"].asString,ExportFormat.markdown,ExportOptions(zone=zone),sequence=c["sequence"].asInt,generatedAt=Instant.parse(c["now"].asString))
                    assertEquals(name,c["expected"].asString,prepared.files.single().file.readText())
                    assertEquals(3L,prepared.files.single().counts.visits);assertEquals(1L,prepared.files.single().counts.points)
                    files.discard(prepared);checked++
                } finally {files.clearCache();db.close()}
            } catch(error:Throwable) {if(error is java.util.concurrent.CancellationException) throw error;failures+="$name: $error"}
        }
        assertTrue(failures.joinToString("\n"),failures.isEmpty());assertEquals(8,checked)
    }
}
