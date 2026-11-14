package com.bontecou.whereday.platform

import com.bontecou.whereday.data.JournalDateRange
import com.bontecou.whereday.domain.Coordinate
import com.bontecou.whereday.export.ExportSelection
import com.bontecou.whereday.export.ExportSelectionCodec
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Strict and bounded at the trust boundary, including duplicate keys at every nesting level. */
internal object AutomationJson {
    fun objectValue(raw:String,maxChars:Int=32_768):JsonObject {
        require(maxChars in 1..65_536 && raw.length<=maxChars)
        JsonReader(StringReader(raw)).use {reader ->
            reader.strictness=Strictness.STRICT
            fun value(depth:Int):JsonElement {
                require(depth<=8)
                return when(reader.peek()) {
                    JsonToken.BEGIN_OBJECT ->JsonObject().apply {
                        reader.beginObject()
                        while(reader.hasNext()) {
                            require(size()<512)
                            val key=reader.nextName();require(!has(key));add(key,value(depth+1))
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY ->JsonArray().apply {
                        reader.beginArray()
                        while(reader.hasNext()) {require(size()<1024);add(value(depth+1))}
                        reader.endArray()
                    }
                    JsonToken.STRING ->JsonPrimitive(reader.nextString())
                    JsonToken.NUMBER ->JsonParser.parseString(reader.nextString())
                    JsonToken.BOOLEAN ->JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL ->{reader.nextNull();JsonNull.INSTANCE}
                    else ->error("Invalid JSON value")
                }
            }
            val json=value(0);require(reader.peek()==JsonToken.END_DOCUMENT && json.isJsonObject)
            return json.asJsonObject
        }
    }
    fun text(json:JsonObject,key:String):String {
        val value=json[key];require(value?.isJsonPrimitive==true && value.asJsonPrimitive.isString)
        return value.asString
    }
    fun integer(json:JsonObject,key:String):Long {
        val value=json[key];require(value?.isJsonPrimitive==true && value.asJsonPrimitive.isNumber)
        return requireNotNull(value.asString.toLongOrNull())
    }
}

enum class AutomationCommand(val scope:AutomationScope) {
    statistics(AutomationScope.summary),visits(AutomationScope.visits),outings(AutomationScope.outings),currentOuting(AutomationScope.outings),
    renameVisit(AutomationScope.renameVisits),renameOuting(AutomationScope.renameOutings),renameCurrentOuting(AutomationScope.renameOutings),
    savedPlaces(AutomationScope.places),openManualVisits(AutomationScope.visits),
    checkIn(AutomationScope.manualVisits),checkOut(AutomationScope.manualVisits),
    startRecording(AutomationScope.recording),stopRecording(AutomationScope.recording),export(AutomationScope.exports)
}
class AutomationInvalidRequest:IllegalArgumentException("The automation request is invalid or uses an unsupported version or field.")

/** No request bodies or names in logs/toString; coordinates and user-entered names can be sensitive. */
class AutomationRequest private constructor(val command:AutomationCommand,private val explicitRange:JournalDateRange?,private val zone:ZoneId?,
    val itemId:String?,val name:String?,val selection:ExportSelection?,val savedPlaceId:String?,
    val coordinate:Coordinate?,val visitAt:Instant?,val allowOverlap:Boolean) {
    fun range(clock:Clock=Clock.systemUTC(),defaultZone:ZoneId=ZoneId.systemDefault()):JournalDateRange = explicitRange ?:
        (zone ?: defaultZone).let {JournalDateRange.day(LocalDate.now(clock.withZone(it)),it)}
    override fun toString()="AutomationRequest(command=$command,parameters=[redacted])"
    companion object {
        fun decode(raw:String):AutomationRequest=try {
            val json=AutomationJson.objectValue(raw)
            require(AutomationJson.integer(json,"version")==1L)
            val command=AutomationCommand.valueOf(AutomationJson.text(json,"command"))
            val fields=when(command) {
                AutomationCommand.statistics,AutomationCommand.visits,AutomationCommand.outings ->setOf("from","through","zone")
                AutomationCommand.renameVisit,AutomationCommand.renameOuting ->setOf("id","name")
                AutomationCommand.renameCurrentOuting ->setOf("name")
                AutomationCommand.checkIn ->setOf("savedPlaceId","name","latitude","longitude","arrivedAt","allowOverlap")
                AutomationCommand.checkOut ->setOf("id","departedAt")
                AutomationCommand.export ->setOf("selection")
                else ->emptySet()
            }
            require(json.keySet().all {it in fields || it=="version" || it=="command"})
            val zone=if(json.has("zone")) ZoneId.of(AutomationJson.text(json,"zone")) else null
            require(json.has("from")==json.has("through"))
            val range=if(json.has("from")) JournalDateRange(Instant.parse(AutomationJson.text(json,"from")),Instant.parse(AutomationJson.text(json,"through"))).also {
                // Reject instants outside the storage representation before any database query.
                it.from.toEpochMilli();it.through.toEpochMilli()
            } else null
            val id=if("id" in fields && (command!=AutomationCommand.checkOut || json.has("id"))) AutomationJson.text(json,"id").also {require(it.isNotBlank() && it.length<=512)} else null
            val name=if("name" in fields && (command!=AutomationCommand.checkIn || json.has("name")))
                AutomationJson.text(json,"name").trim().also {if(command!=AutomationCommand.checkIn) require(it.isNotEmpty())} else null
            val savedPlace=if(json.has("savedPlaceId")) AutomationJson.text(json,"savedPlaceId").also {require(it.isNotBlank() && it.length<=512)} else null
            require(json.has("latitude")==json.has("longitude"))
            val coordinate=if(json.has("latitude")) {
                fun number(key:String):Double {
                    val value=json[key];require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
                    return value.asDouble
                }
                Coordinate(number("latitude"),number("longitude"))
            } else null
            require(savedPlace==null || coordinate==null)
            if(command==AutomationCommand.checkIn) require(savedPlace!=null || !name.isNullOrBlank())
            val timeKey=if(command==AutomationCommand.checkIn) "arrivedAt" else "departedAt"
            val visitAt=if(json.has(timeKey)) Instant.parse(AutomationJson.text(json,timeKey)).also {it.toEpochMilli()} else null
            val overlap=if(json.has("allowOverlap")) json["allowOverlap"].also {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean)
            }.asBoolean else false
            val selection=if(command==AutomationCommand.export) {
                val selection=json["selection"].asJsonObject
                val allowed=AutomationJson.objectValue(ExportSelectionCodec.encode(ExportSelection())).keySet()+setOf("startTime","endTime","maximumAccuracy")
                require(selection.keySet().all {it in allowed})
                require(AutomationJson.integer(selection,"version")==2L)
                ExportSelectionCodec.decode(selection.toString())
            } else null
            AutomationRequest(command,range,zone,id,name,selection,savedPlace,coordinate,visitAt,overlap)
        } catch(_:Exception) {throw AutomationInvalidRequest()}
    }
}
