package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import java.time.*
import java.time.format.*
import java.util.Locale

/** Reads the current ISO tables and historical iOS date/clock tables and visit sections.
 * Historical files contain no locale/zone metadata: interpret them in the caller's explicit
 * context (device defaults in the import UI), never silently substitute UTC.
 */
internal class MarkdownHistoryReader(private val locale:Locale,private val zone:ZoneId) {
    private fun spaces(value:String)=value.replace('\u00a0',' ').replace('\u202f',' ').map {c->Character.digit(c,10).takeIf {it>=0}?.let {('0'.code+it).toChar()} ?: c}.joinToString("").trim()
    private val calendar=HistoryCalendarFormats(locale,zone)
    // Accept checked Foundation punctuation and older Android/JDK punctuation.
    private val dateFormats=listOf(true,false).map {calendar.parser(FormatStyle.FULL,null,it).withDecimalStyle(DecimalStyle.STANDARD)}+
        if(locale.language=="th" && locale.getUnicodeLocaleType("ca")==null) listOf(HistoryCalendarFormats(Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("ca","gregory").build(),zone).parser(FormatStyle.FULL,null,true).withDecimalStyle(DecimalStyle.STANDARD)) else emptyList()
    private val timeFormats=listOf(calendar.parser(null,FormatStyle.SHORT,false).withDecimalStyle(DecimalStyle.STANDARD),calendar.parser(null,FormatStyle.MEDIUM,false).withDecimalStyle(DecimalStyle.STANDARD),DateTimeFormatter.ISO_LOCAL_TIME)
    private fun timestamp(raw:String,date:LocalDate?):String {
        val value=spaces(raw)
        runCatching {Instant.parse(value)}.getOrNull()?.let {return it.toString()}
        val day=requireNotNull(date) {"A legacy clock time needs a valid date heading in the import locale"}
        val time=timeFormats.firstNotNullOfOrNull {runCatching {LocalTime.parse(value,it)}.getOrNull()}
            ?: error("Invalid legacy clock time")
        val local=day.atTime(time)
        val offsets=zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) {"Local time does not exist in the import time zone"}
        return local.toInstant(offsets.first()).toString()
    }
    fun scan(reader:java.io.Reader,onVisit:(Map<String,String>,Int)->Unit,onPoint:(Map<String,String>,Int)->Unit,
        onError:(String)->Unit,checkpoint:()->Unit={}) {
        var headers:List<String>?=null;var date:LocalDate?=null
        var fields:MutableMap<String,String>?=null;var sectionLine=0;var hasFields=false;var invalidSection=false
        var sectionCharacters=0;val notes=StringBuilder()
        fun retain(size:Int) {sectionCharacters+=size;ImportRecordLimits.text(sectionCharacters)}
        fun flush() {
            if(notes.isNotEmpty()) fields?.set("notes",notes.toString())
            if(hasFields && !invalidSection) onVisit(requireNotNull(fields),sectionLine)
            fields=null;hasFields=false;invalidSection=false;sectionCharacters=0;notes.setLength(0)
        }
        fun optional(value:String)=value.trim().takeUnless {it in setOf("-","—","Still here","Ongoing")}.orEmpty()
        ImportLineReader(reader).buffered().lineSequence().forEachIndexed {index,line->
            checkpoint();val value=line.trim().removePrefix("\uFEFF").trimStart();val lineNumber=index+1
            if(value.startsWith("## ")) {
                flush();headers=null
                date=dateFormats.firstNotNullOfOrNull {runCatching {LocalDate.parse(spaces(value.drop(3)),it)}.getOrNull()}
            } else if(value.startsWith("### ")) {
                flush();retain(value.length);sectionLine=lineNumber;fields=mutableMapOf("locationName" to value.drop(4))
            } else if(value.startsWith("- **") && value.contains(":**")) {
                if(fields==null) {fields=mutableMapOf();sectionLine=lineNumber}
                val key=value.substringAfter("- **").substringBefore(":**")
                val raw=value.substringAfter(":**").trim()
                retain(value.length)
                try {
                    when(key) {
                        "Arrived"->{hasFields=true;fields!!["arrivedAt"]=timestamp(raw,date)}
                        "Departed"->{hasFields=true;fields!!["departedAt"]=optional(raw).takeIf {it.isNotEmpty()}?.let {timestamp(it,date)}.orEmpty()}
                        "Address"->{hasFields=true;fields!!["address"]=raw}
                        "Coordinates"->{
                            hasFields=true;val coordinates=raw.split(',');require(coordinates.size==2) {"Invalid legacy coordinates"}
                            fields!!["latitude"]=coordinates[0].trim();fields!!["longitude"]=coordinates[1].trim()
                        }
                    }
                } catch(e:Exception) {
                    onError("Line $lineNumber: ${e.message}")
                    // Mark this section invalid; don't turn a malformed departure into an open stay.
                    invalidSection=true
                }
            } else if(value.startsWith("> ") && fields!=null) {
                val note=value.drop(2);retain(note.length+1);if(notes.isNotEmpty()) notes.append('\n');notes.append(note)
            } else if(value.startsWith('|')) {
                val row=cells(value,checkpoint)
                if(row.firstOrNull() in setOf("Arrived","Time")) {flush();headers=row;return@forEachIndexed}
                if(row.all {it.isNotEmpty() && it.matches(Regex("[: -]+"))}) return@forEachIndexed
                val columns=headers ?: if(date!=null && row.size in 3..7) listOf("Time","Lat","Lon","Speed","Altitude","Accuracy","Outlier").take(row.size) else return@forEachIndexed
                val prepared=try {
                    require(columns.distinct().size==columns.size) {"Duplicate Markdown columns"}
                    require(row.size==columns.size) {"Column count does not match Markdown header"}
                    val names=mapOf("Arrived" to "arrivedAt","Departed" to "departedAt","Lat" to "latitude","Lon" to "longitude",
                        "Latitude" to "latitude","Longitude" to "longitude","Location" to "locationName","Address" to "address","Notes" to "notes",
                        "Source" to "source","Status" to "confirmationStatus","Visit Metadata" to "visit_metadata","Time" to "timestamp","Speed" to "speed","Altitude" to "altitude","Accuracy" to "horizontalAccuracy","Outlier" to "isOutlier")
                    val keys=columns.map {names[it] ?: it}
                    require(keys.distinct().size==keys.size) {"Duplicate Markdown column aliases"}
                    val data=keys.zip(row).associate {(key,cell)->key to
                        if(key in setOf("arrivedAt","departedAt","timestamp")) optional(cell)
                        else cell.trim().takeUnless {it in setOf("-","—")}.orEmpty()
                    }.toMutableMap()
                    // iOS tables render display names (Automatic / Corrected), while JSON/CSV
                    // carry raw enum values. Normalize only the Markdown enum columns.
                    for(key in listOf("source","confirmationStatus")) data[key]?.let {data[key]=it.lowercase(Locale.ROOT)}
                    for(key in listOf("arrivedAt","departedAt","timestamp")) data[key]?.takeIf {it.isNotEmpty()}?.let {data[key]=timestamp(it,date)}
                    if("arrivedAt" !in data) {
                        for(key in listOf("speed","altitude","horizontalAccuracy")) data[key]?.let {data[key]=it.substringBefore(' ')}
                    }
                    ("arrivedAt" in data) to data
                } catch(e:Exception) {
                    if(e is java.util.concurrent.CancellationException || e is ImportRecordTooLarge) throw e
                    onError("Line $lineNumber: ${e.message}");null
                }
                prepared?.let {(visit,data)->if(visit) onVisit(data,lineNumber) else onPoint(data,lineNumber)}
            }
        }
        flush()
    }
    companion object {
        /** Backslash pairs are consumed together, so an escaped backslash before a delimiter
         * cannot incorrectly hide that delimiter. Preserve leading/trailing empty cells. */
        fun cells(line:String,checkpoint:()->Unit={}):List<String> {
            val result=mutableListOf<String>();val cell=StringBuilder();var i=0;var characters=0
            fun append(c:Char) {ImportRecordLimits.text(++characters);cell.append(c)}
            fun endCell() {if(result.size>=ImportRecordLimits.columns+2) throw ImportRecordTooLarge();result+=cell.toString().trim().replace("<br>","\n");cell.setLength(0)}
            while(i<line.length) {
                if(i%1024==0) checkpoint()
                val c=line[i]
                if(c=='\\' && i+1<line.length && line[i+1] in setOf('\\','|')) {append(line[i+1]);i+=2;continue}
                if(c=='|') endCell() else append(c)
                i++
            }
            endCell()
            if(result.firstOrNull().isNullOrEmpty()) result.removeAt(0)
            if(result.lastOrNull().isNullOrEmpty()) result.removeAt(result.lastIndex)
            if(result.size>ImportRecordLimits.columns) throw ImportRecordTooLarge()
            return result
        }
    }
}
