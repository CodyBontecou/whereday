package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.time.Instant

internal object VisitAuditImport {
    private val keys=setOf("source","confirmationStatus","confirmedAt","updatedAt","originalLatitude","originalLongitude","originalLocationName","originalAddress","detectedLatitude","detectedLongitude","detectedLocationName","detectedAddress","placeSource","placeDistanceMeters")
    private val fractionalIso=Regex("T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]+")
    fun apply(visit:Visit,raw:String?):Visit {
        if(raw==null || raw.isEmpty() || raw=="-") return visit
        ImportRecordLimits.text(raw.length)
        val metadata=linkedMapOf<String,String>();val seen=mutableSetOf<String>();var characters=0
        JsonReader(raw.reader()).apply {strictness=Strictness.STRICT}.use {reader->
            require(reader.peek()==JsonToken.BEGIN_OBJECT) {"Expected visit metadata object"};reader.beginObject()
            while(reader.hasNext()) {
                val key=reader.nextName();unicode(key);characters+=key.length;ImportRecordLimits.text(characters)
                require(key.length<=1024 && seen.add(key)) {"Invalid or duplicate visit metadata key"}
                require(reader.peek()==JsonToken.STRING) {"Visit metadata values must be strings"}
                val value=reader.nextString();unicode(value);characters+=value.length;ImportRecordLimits.text(characters)
                if(key in keys) metadata[key]=value
            }
            reader.endObject();require(reader.peek()==JsonToken.END_DOCUMENT) {"Unexpected trailing visit metadata"}
        }
        fun number(key:String)=metadata[key]?.toDoubleOrNull()?.also {require(it.isFinite()) {"Invalid visit metadata number"}}
        fun coordinate(prefix:String):Coordinate? {
            val latitude=number(prefix+"Latitude");val longitude=number(prefix+"Longitude")
            return if(latitude!=null && longitude!=null) Coordinate(latitude,longitude) else null
        }
        // The Swift audit decoder accepts fractional ISO strings; invalid optional values are absent.
        fun date(key:String)=metadata[key]?.takeIf {fractionalIso.containsMatchIn(it)}?.let {runCatching {Instant.parse(it)}.getOrNull()}
        return visit.copy(
            source=metadata["source"]?.let {runCatching {VisitSource.valueOf(it)}.getOrDefault(VisitSource.imported)} ?: visit.source,
            confirmation=metadata["confirmationStatus"]?.let {runCatching {Confirmation.valueOf(it)}.getOrDefault(Confirmation.unconfirmed)} ?: visit.confirmation,
            confirmedAt=date("confirmedAt"),updatedAt=date("updatedAt"),
            originalCoordinate=coordinate("original"),originalName=metadata["originalLocationName"],originalAddress=metadata["originalAddress"],
            detectedCoordinate=coordinate("detected"),detectedName=metadata["detectedLocationName"],detectedAddress=metadata["detectedAddress"],
            placeSource=metadata["placeSource"],placeDistanceMeters=number("placeDistanceMeters")
        )
    }
    private fun unicode(value:String) {
        var index=0
        while(index<value.length) {
            val c=value[index++]
            if(c.isHighSurrogate()) {require(index<value.length && value[index].isLowSurrogate()) {"Invalid Unicode in visit metadata"};index++}
            else require(!c.isLowSurrogate()) {"Invalid Unicode in visit metadata"}
        }
    }

}
