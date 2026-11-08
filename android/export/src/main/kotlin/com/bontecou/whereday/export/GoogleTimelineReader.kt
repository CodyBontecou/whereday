package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.*
import java.time.*
import java.util.UUID

/** Source identity is independent of the JSON transport format. */
enum class HistoryImportSource {whereday,googleRaw,googleSemantic,googleDevice}

/** Google segments can contain large paths and put their times after those paths. One
 * disposable row is spooled to caller-owned private storage; metadata and fixes are then
 * read separately. Neither the complete document nor a segment's path enters the heap. */
internal object GoogleTimelineReader {
    const val maximumBytes=50L*1024*1024
    const val maximumRecords=250_000
    private val roots=mapOf("locations" to HistoryImportSource.googleRaw,"timelineObjects" to HistoryImportSource.googleSemantic,"semanticSegments" to HistoryImportSource.googleDevice)
    private val names=setOf("latitudeE7","latE7","longitudeE7","lngE7","latLng","point","timestampMs","timestamp","time","accuracy","altitude","velocity","name","address","placeVisit","location","duration","startTimestamp","endTimestamp","startTimestampMs","endTimestampMs","activitySegment","startLocation","endLocation","simplifiedRawPath","points","waypointPath","waypoints","startTime","endTime","visit","topCandidate","placeLocation","placeName","activity","start","end","timelinePath","durationMinutesOffsetFromStartTime")
    private val paths=setOf("simplifiedRawPath","waypointPath","timelinePath")
    private val decimal=Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val hex=Regex("[+-]?0x(?:[a-fA-F0-9]+(?:\\.[a-fA-F0-9]*)?|\\.[a-fA-F0-9]+)(?:[pP][+-]?[0-9]+)?")
    private fun json(source:Reader):Pair<JsonReader,ImportScalarReader> {
        val budget=ImportScalarReader(source)
        val prefix=PushbackReader(budget.buffered(),1);var bom=false
        while(true) {val c=prefix.read();if(c<0) break;if(c.toChar().isWhitespace()) continue
            if(c=='\uFEFF'.code && !bom) {bom=true;continue};prefix.unread(c);break}
        return JsonReader(prefix).apply {strictness=Strictness.STRICT} to budget
    }
    fun source(input:Reader,check:()->Unit):HistoryImportSource {
        val (r,b)=json(ImportCheckpointReader(input,check))
        if(r.peek()==JsonToken.BEGIN_ARRAY) return HistoryImportSource.googleDevice
        require(r.peek()==JsonToken.BEGIN_OBJECT) {"Expected JSON history object or device timeline array"}
        r.beginObject();val seen=mutableSetOf<String>();var selected=HistoryImportSource.whereday
        while(r.hasNext()) {check();val key=b.scalar(1024) {r.nextName()}
            roots[key]?.let {value->require(seen.add(key)) {"Duplicate JSON history array"}
                if(selected==HistoryImportSource.whereday || value.ordinal<selected.ordinal) selected=value}
            r.skipValue()
        }
        r.endObject();require(r.peek()==JsonToken.END_DOCUMENT) {"Unexpected trailing JSON content"};return selected
    }
    fun scan(input:Reader,source:HistoryImportSource,output:ImportScanCollector,check:()->Unit,scratch:()->File) {
        require(source!=HistoryImportSource.whereday)
        val (r,b)=json(ByteBoundReader(ImportCheckpointReader(input,check)))
        val file=scratch()
        try {
            var rows=0;var emitted=0
            fun point(value:TrackPoint) {emitted++;require(emitted<=maximumRecords) {"Google history contains too many visits and points"};output.point(value)}
            fun visit(value:Visit) {emitted++;require(emitted<=maximumRecords) {"Google history contains too many visits and points"};output.visit(value)}
            fun array() {
                require(r.peek()==JsonToken.BEGIN_ARRAY) {"The Google export does not contain a supported record array"}
                r.beginArray()
                while(r.hasNext()) {
                    check();rows++;require(rows<=maximumRecords) {"Google history contains too many records"}
                    if(r.peek()!=JsonToken.BEGIN_OBJECT) {r.skipValue();output.error(HistoryImportRecordKind.googleRecord,rows,HistoryImportProblem.notRecord,"Invalid record");continue}
                    file.outputStream().buffered().writer(Charsets.UTF_8).use {writer->JsonWriter(writer).use {copy(r,b,it,check,0)}}
                    val meta=file.reader(Charsets.UTF_8).buffered().use {input->val (reader,budget)=json(input);metadata(reader,budget,check,intArrayOf(0),0).asJsonObject}
                    fun bad() {output.error(HistoryImportRecordKind.googleRecord,rows,HistoryImportProblem.invalidGoogleRecord,"Contains an invalid visit or point")}
                    fun coordinate(value:JsonElement?):Coordinate? {
                        val obj=if(value is JsonObject) value else JsonObject().apply {if(value!=null) add("latLng",value)}
                        val lat=number(obj["latitudeE7"] ?: obj["latE7"]);val lon=number(obj["longitudeE7"] ?: obj["lngE7"])
                        val pair=if(lat!=null && lon!=null) lat/10_000_000 to lon/10_000_000 else {
                            val text=text(obj["latLng"] ?: obj["point"]) ?: return null
                            val parts=text.replace("geo:","").replace("°","").split(',');if(parts.size!=2) return null
                            val a=number(JsonPrimitive(parts[0].trim()));val z=number(JsonPrimitive(parts[1].trim()));if(a==null || z==null) return null;a to z
                        }
                        return runCatching {Coordinate(pair.first,pair.second)}.getOrNull()
                    }
                    fun appendPoint(location:JsonElement?,at:Instant?,raw:Boolean=false) {
                        if(location==null) return
                        val c=coordinate(location);val obj=location as? JsonObject
                        val accuracy=number(obj?.get("accuracy")) ?: -1.0
                        if(c==null || at==null || !validDate(at) || accuracy < -1 || (raw && obj?.has("accuracy")==true && (number(obj["accuracy"])?.let {it>=0}!=true))) {bad();return}
                        val speed=if(raw) number(obj?.get("velocity"))?.takeIf {it>=0} else null
                        point(TrackPoint(identity("point",c,at),c,at,accuracy,number(obj?.get("altitude")),speed,false))
                    }
                    fun appendVisit(location:JsonObject,start:Instant?,end:Instant?) {
                        val c=coordinate(location)
                        if(c==null || start==null || end==null || end<start) {bad();return}
                        visit(Visit(identity("visit",c,start,end),c,start,end,name=text(location["name"]),address=text(location["address"]),source=VisitSource.imported,placeSource="import"))
                    }
                    fun appendPath(start:Instant?,end:Instant?) {
                        pathRows(file,source,check) {value->
                            val obj=value as? JsonObject
                            if(obj==null) bad() else {
                                var at=date(obj["time"]) ?: date(obj["timestamp"]) ?: milliseconds(obj["timestampMs"])
                                if(at==null) {val offset=number(obj["durationMinutesOffsetFromStartTime"])
                                    if(offset!=null && offset>=0 && start!=null) at=seconds(start.epochSecond.toDouble()+start.nano/1e9+offset*60)}
                                if(at==null || (start!=null && at!!<start) || (end!=null && at!!>end)) bad() else appendPoint(obj,at)
                            }
                        }
                    }
                    when(source) {
                        HistoryImportSource.googleRaw->appendPoint(meta,milliseconds(meta["timestampMs"]) ?: date(meta["timestamp"]),true)
                        HistoryImportSource.googleSemantic->{
                            val place=meta["placeVisit"] as? JsonObject;val activity=meta["activitySegment"] as? JsonObject
                            if(place!=null) {val duration=place["duration"] as? JsonObject
                                appendVisit(place["location"] as? JsonObject ?: JsonObject(),date(duration?.get("startTimestamp")) ?: milliseconds(duration?.get("startTimestampMs")),date(duration?.get("endTimestamp")) ?: milliseconds(duration?.get("endTimestampMs")))}
                            else if(activity!=null) {val duration=activity["duration"] as? JsonObject
                                val start=date(duration?.get("startTimestamp")) ?: milliseconds(duration?.get("startTimestampMs"));val end=date(duration?.get("endTimestamp")) ?: milliseconds(duration?.get("endTimestampMs"))
                                if(start==null || end==null || end<start) bad() else {
                                    appendPoint(activity["startLocation"],start);appendPoint(activity["endLocation"],end);appendPath(start,end)
                                }
                            } else output.unsupported()
                        }
                        HistoryImportSource.googleDevice->{
                            val start=date(meta["startTime"]);val end=date(meta["endTime"])
                            if(start==null || end==null || end<start) bad() else {
                                var supported=false
                                (meta["visit"] as? JsonObject)?.let {v->supported=true;val candidate=v["topCandidate"] as? JsonObject
                                    val location=(candidate?.get("placeLocation") as? JsonObject)?.deepCopy() ?: JsonObject().apply {text(candidate?.get("placeLocation"))?.let {addProperty("latLng",it)}}
                                    (candidate?.get("placeName") ?: v["name"])?.let {location.add("name",it)}
                                    (candidate?.get("address") ?: v["address"])?.let {location.add("address",it)}
                                    appendVisit(location,start,end)}
                                (meta["activity"] as? JsonObject)?.let {a->supported=true;appendPoint(a["start"],start);appendPoint(a["end"],end)}
                                if(meta["timelinePath"]?.isJsonArray==true) {supported=true;appendPath(start,end)}
                                if(!supported) output.unsupported()
                            }
                        }
                        else->error("Unexpected Google source")
                    }
                }
                r.endArray()
            }
            if(r.peek()==JsonToken.BEGIN_ARRAY) {require(source==HistoryImportSource.googleDevice);array()}
            else {
                require(r.peek()==JsonToken.BEGIN_OBJECT);r.beginObject();var found=false
                val key=roots.entries.single {it.value==source}.key
                while(r.hasNext()) {check();val name=b.scalar(1024) {r.nextName()}
                    if(name==key) {require(!found) {"Duplicate JSON history array"};found=true;array()} else r.skipValue()}
                r.endObject();require(found) {"The Google export does not contain a supported record array"}
            }
            require(r.peek()==JsonToken.END_DOCUMENT) {"Unexpected trailing JSON content"}
        } finally {check(file.delete() || !file.exists()) {"Google import staging cleanup failed"}}
    }
    private fun copy(r:JsonReader,b:ImportScalarReader,w:JsonWriter,check:()->Unit,depth:Int) {
        check();require(depth<=64) {"Google history is nested too deeply"}
        when(r.peek()) {
            JsonToken.BEGIN_OBJECT->{r.beginObject();w.beginObject();val seen=mutableSetOf<String>()
                while(r.hasNext()) {check();val name=b.scalar(1024) {r.nextName()};if(name !in names) {r.skipValue();continue}
                    require(seen.add(name)) {"Duplicate Google history field"};w.name(name);copy(r,b,w,check,depth+1)}
                r.endObject();w.endObject()}
            JsonToken.BEGIN_ARRAY->{r.beginArray();w.beginArray();var count=0
                while(r.hasNext()) {require(++count<=maximumRecords) {"Google history contains too many path records"};copy(r,b,w,check,depth+1)};r.endArray();w.endArray()}
            JsonToken.STRING->w.value(b.scalar(ImportRecordLimits.textCharacters) {r.nextString()}.also {ImportRecordLimits.text(it.length);validText(it)})
            JsonToken.NUMBER->w.jsonValue(b.scalar(1024) {r.nextString()}.also {require(it.length<=1024) {"Google number is too long"}})
            JsonToken.BOOLEAN->w.value(r.nextBoolean())
            JsonToken.NULL->{r.nextNull();w.nullValue()}
            else->error("Invalid Google history JSON")
        }
    }
    private fun metadata(r:JsonReader,b:ImportScalarReader,check:()->Unit,size:IntArray,depth:Int):JsonElement {
        check();require(depth<=64)
        return when(r.peek()) {
            JsonToken.BEGIN_OBJECT->{r.beginObject();val obj=JsonObject();while(r.hasNext()) {check();val key=b.scalar(1024) {r.nextName()};size[0]+=key.length;ImportRecordLimits.text(size[0])
                if(key !in names) {r.skipValue();continue}
                if(key in paths) {val array=r.peek()==JsonToken.BEGIN_ARRAY;r.skipValue();if(array && key=="timelinePath") obj.add(key,JsonArray());continue}
                obj.add(key,metadata(r,b,check,size,depth+1))};r.endObject();obj}
            JsonToken.BEGIN_ARRAY->{r.skipValue();JsonNull.INSTANCE}
            JsonToken.STRING,JsonToken.NUMBER->{val number=r.peek()==JsonToken.NUMBER;val value=b.scalar(ImportRecordLimits.textCharacters-size[0]) {r.nextString()};size[0]+=value.length;ImportRecordLimits.text(size[0]);if(number) JsonPrimitive(com.google.gson.internal.LazilyParsedNumber(value)) else JsonPrimitive(value)}
            JsonToken.BOOLEAN->JsonPrimitive(r.nextBoolean())
            JsonToken.NULL->{r.nextNull();JsonNull.INSTANCE}
            else->error("Invalid Google history JSON")
        }
    }
    private fun pathRows(file:File,source:HistoryImportSource,check:()->Unit,consume:(JsonElement)->Unit) {
        // Swift visits simplified paths before waypoint paths, independently of JSON key
        // order. Preserve that stable order when equal-time nearby points are deduplicated.
        val orderedPaths=if(source==HistoryImportSource.googleSemantic) listOf("simplifiedRawPath","waypointPath") else listOf("timelinePath")
        for(selected in orderedPaths) file.reader(Charsets.UTF_8).buffered().use {input->val (r,b)=json(input)
            fun points() {if(r.peek()!=JsonToken.BEGIN_ARRAY) {r.skipValue();return};r.beginArray();while(r.hasNext()) {check();consume(metadata(r,b,check,intArrayOf(0),0))};r.endArray()}
            fun path(container:String) {
                if(r.peek()!=JsonToken.BEGIN_OBJECT) {r.skipValue();return};r.beginObject()
                while(r.hasNext()) {check();val key=b.scalar(1024) {r.nextName()};if(key==container) points() else r.skipValue()};r.endObject()
            }
            r.beginObject();while(r.hasNext()) {check();val key=b.scalar(1024) {r.nextName()}
                if(source==HistoryImportSource.googleDevice && key=="timelinePath") points()
                else if(source==HistoryImportSource.googleSemantic && key=="activitySegment" && r.peek()==JsonToken.BEGIN_OBJECT) {
                    r.beginObject();while(r.hasNext()) {check();val name=b.scalar(1024) {r.nextName()}
                        if(name==selected) path(if(selected=="simplifiedRawPath") "points" else "waypoints") else r.skipValue()
                    };r.endObject()
                } else r.skipValue()
            };r.endObject()
        }
    }
    private fun validText(text:String) {
        var index=0
        while(index<text.length) {
            val c=text[index++]
            if(Character.isHighSurrogate(c)) {require(index<text.length && Character.isLowSurrogate(text[index])) {"Google history contains invalid Unicode"};index++}
            else require(!Character.isLowSurrogate(c)) {"Google history contains invalid Unicode"}
        }
    }
    private fun text(value:JsonElement?):String?=(value as? JsonPrimitive)?.takeIf {it.isString}?.asString
    private fun number(value:JsonElement?):Double? {
        val p=value as? JsonPrimitive ?: return null;if(p.isBoolean) return null
        val text=p.asString
        if(!decimal.matches(text) && !hex.matches(text)) return null
        return runCatching {(if(hex.matches(text) && !text.contains('p',true)) text+"p0" else text).toDouble()}.getOrNull()?.takeIf {it.isFinite()}
    }
    private fun validDate(at:Instant)=at>=Instant.ofEpochSecond(-62135769600) && at<=Instant.ofEpochSecond(64092211200)
    private fun seconds(value:Double):Instant? {
        if(!value.isFinite() || value < -62135769600.0 || value > 64092211200.0) return null
        val decimal=java.math.BigDecimal.valueOf(value);val whole=decimal.setScale(0,java.math.RoundingMode.FLOOR).longValueExact()
        return runCatching {Instant.ofEpochSecond(whole,decimal.subtract(java.math.BigDecimal.valueOf(whole)).movePointRight(9).toLong())}.getOrNull()?.takeIf(::validDate)
    }
    private fun milliseconds(value:JsonElement?)=number(value)?.let {seconds(it/1000)}
    // Match the selected Foundation ISO parser, including accepted date prefixes,
    // hybrid Gregorian historical dates and normalized day/24:00/leap-second inputs.
    private fun date(value:JsonElement?):Instant?=text(value)?.let {text->runCatching {
        val m=Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]+))?(Z|[+-][0-9]{2}(?::?[0-9]{2})?)").find(text) ?: return@runCatching null
        val f=m.groupValues.drop(1).take(6).map(String::toInt)
        require(f[1] in 1..12 && f[2] in 1..31 && f[3] in 0..24 && f[4] in 0..59 && f[5] in 0..60)
        val zone=m.groupValues[8]
        val offset=if(zone=="Z") 0 else {val digits=zone.drop(1).replace(":","");val h=digits.take(2).toInt();val min=digits.drop(2).ifEmpty {"0"}.toInt()
            require(h in 0..23 && min in 0..59);(h*60+min)*60_000*(if(zone[0]=='-') -1 else 1)}
        val calendar=java.util.GregorianCalendar(java.util.SimpleTimeZone(offset,"google-import"),java.util.Locale.ROOT).apply {
            clear();isLenient=true;set(java.util.Calendar.ERA,java.util.GregorianCalendar.AD);set(f[0],f[1]-1,f[2],f[3],f[4],f[5])}
        Instant.ofEpochMilli(calendar.timeInMillis).plusNanos(m.groupValues[7].take(9).padEnd(9,'0').toLong())
    }.getOrNull()?.takeIf(::validDate)}
    private fun identity(kind:String,coordinate:Coordinate,time:Instant,departure:Instant?=null):String {
        // Arrival/location alone cannot identify two nonmatching visit intervals.
        val interval=departure?.let {":$it"}.orEmpty()
        return UUID.nameUUIDFromBytes("$kind:$time:$coordinate$interval".toByteArray(Charsets.UTF_8)).toString()
    }
    private class ByteBoundReader(private val source:Reader):Reader() {
        private var bytes=0L;private var high=false
        override fun read(buffer:CharArray,offset:Int,length:Int):Int {
            val n=source.read(buffer,offset,minOf(length,8192));for(i in offset until offset+maxOf(n,0)) {val c=buffer[i];bytes+=when {c.code<=127->1;c.code<=2047->2;Character.isLowSurrogate(c) && high->1;else->3};high=Character.isHighSurrogate(c)}
            require(bytes<=maximumBytes) {"Choose a Google history file smaller than 50 MB"};return n
        }
        override fun close() {}
    }
}
