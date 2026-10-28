package com.bontecou.whereday.export

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class ExportSelection(val format:ExportFormat=ExportFormat.json,val additionalFormats:Set<ExportFormat> =emptySet(),
    val options:ExportOptions=ExportOptions(),val filename:String=FilenamePlanner.defaultPattern,val splitDays:Boolean=false,val outings:Boolean=false) {
    init {require(filename.length<=4096)}
}

/** Invalid saved filters require explicit reset; never silently broaden an export to all history. */
object ExportSelectionCodec {
    fun encode(value:ExportSelection):String=JsonObject().apply {
        addProperty("version",2);addProperty("format",value.format.name);addProperty("outings",value.outings)
        add("additionalFormats",com.google.gson.JsonArray().apply {value.additionalFormats.sortedBy {it.ordinal}.forEach {add(it.name)}})
        addProperty("filename",value.filename);addProperty("splitDays",value.splitDays)
        val o=value.options
        addProperty("kind",o.kind.name);addProperty("from",o.from.toString());addProperty("until",o.until.toString());addProperty("zone",o.zone.id)
        o.startTime?.let {addProperty("startTime",it.toString())};o.endTime?.let {addProperty("endTime",it.toString())}
        addProperty("completedOnly",o.completedOnly);addProperty("minimumVisitMinutes",o.minimumVisitMinutes)
        addProperty("excludeOutliers",o.excludeOutliers);o.maximumAccuracy?.let {addProperty("maximumAccuracy",it)}
        addProperty("visitCoordinates",o.visitCoordinates);addProperty("visitName",o.visitName);addProperty("visitAddress",o.visitAddress)
        addProperty("visitNotes",o.visitNotes);addProperty("visitDuration",o.visitDuration)
        addProperty("pointAltitude",o.pointAltitude);addProperty("pointSpeed",o.pointSpeed);addProperty("pointAccuracy",o.pointAccuracy);addProperty("pointOutlier",o.pointOutlier)
    }.toString()

    fun decode(saved:String?):ExportSelection {
        if(saved==null) return ExportSelection()
        require(saved.length<=16_384)
        val json=JsonParser.parseString(saved).asJsonObject
        fun text(name:String):String {val v=json[name];require(v?.isJsonPrimitive==true && v.asJsonPrimitive.isString);return v.asString}
        fun boolean(name:String):Boolean {val v=json[name];require(v?.isJsonPrimitive==true && v.asJsonPrimitive.isBoolean);return v.asBoolean}
        fun long(name:String):Long {val v=json[name];require(v?.isJsonPrimitive==true && v.asJsonPrimitive.isNumber);return requireNotNull(v.asString.toLongOrNull())}
        val version=long("version");require(version in 1L..2L)
        val outings=if(version==2L) boolean("outings") else {require(!json.has("outings"));false}
        val maximum=json["maximumAccuracy"]?.let {require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber);it.asDouble.also {v->require(v.isFinite() && v>0)}}
        val formats=json["additionalFormats"].asJsonArray.map {require(it.isJsonPrimitive && it.asJsonPrimitive.isString);ExportFormat.valueOf(it.asString)}.toSet()
        return ExportSelection(ExportFormat.valueOf(text("format")),formats,
            ExportOptions(kind=DataKind.valueOf(text("kind")),from=Instant.parse(text("from")),until=Instant.parse(text("until")),zone=ZoneId.of(text("zone")),
                startTime=json["startTime"]?.let {LocalTime.parse(text("startTime"))},endTime=json["endTime"]?.let {LocalTime.parse(text("endTime"))},
                completedOnly=boolean("completedOnly"),minimumVisitMinutes=long("minimumVisitMinutes"),excludeOutliers=boolean("excludeOutliers"),maximumAccuracy=maximum,
                visitCoordinates=boolean("visitCoordinates"),visitName=boolean("visitName"),visitAddress=boolean("visitAddress"),visitNotes=boolean("visitNotes"),visitDuration=boolean("visitDuration"),
                pointAltitude=boolean("pointAltitude"),pointSpeed=boolean("pointSpeed"),pointAccuracy=boolean("pointAccuracy"),pointOutlier=boolean("pointOutlier")),
            text("filename"),boolean("splitDays"),outings)
    }
}
