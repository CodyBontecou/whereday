package com.bontecou.whereday.domain

import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.GregorianCalendar
import java.util.Calendar
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.UUID

class InvalidVisitLink:IllegalArgumentException("Invalid visit link")
data class VisitLinkSeed(val mode:ManualVisitMode,val name:String,val coordinate:Coordinate?,val arrival:Instant,val departure:Instant)
sealed interface ManualVisitLink {
    data class Existing(val id:String):ManualVisitLink
    data class Form(val mode:ManualVisitMode,val name:String?,val coordinate:Coordinate?,val arrival:Instant?,val departure:Instant?):ManualVisitLink {
        fun seed(now:Instant):VisitLinkSeed {
            val end=departure ?: now
            return VisitLinkSeed(mode,name.orEmpty(),coordinate,arrival ?: if(mode==ManualVisitMode.current) now else end.minusSeconds(3600),end)
        }
    }
    companion object {
        const val MAX_URL_LENGTH=8192
        fun parse(raw:String,now:Instant):ManualVisitLink {
            try {return parseChecked(raw,now)} catch(_:Exception) {throw InvalidVisitLink()}
        }
        private fun parseChecked(raw:String,now:Instant):ManualVisitLink {
            require(raw.length<=MAX_URL_LENGTH)
            val uri=URI(raw)
            require(uri.scheme in setOf("isome","whereday") && uri.rawFragment==null)
            val host=decode(requireNotNull(uri.rawAuthority))
            val path=decode(uri.rawPath.orEmpty())
            if(host=="visit") {
                require(uri.rawQuery==null && Regex("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(path))
                return Existing(UUID.fromString(path.drop(1)).toString())
            }
            require(host=="check-in" || host=="log-visit")
            require(path=="" || path=="/")
            val allowed=if(host=="check-in") setOf("name","latitude","longitude","arrival") else setOf("name","latitude","longitude","arrival","departure")
            val values=mutableMapOf<String,String>()
            uri.rawQuery?.takeIf {it.isNotEmpty()}?.split('&')?.forEach {item->
                val at=item.indexOf('=');require(at>=0)
                val key=decode(item.substring(0,at));val value=decode(item.substring(at+1))
                require(key in allowed && key !in values);values[key]=value
            }
            val name=values["name"]?.trim();require(name==null || name.isNotEmpty())
            fun number(key:String):Double?=values[key]?.let {value->
                val decimal=Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
                val hex=Regex("[+-]?0x(?:[a-fA-F0-9]+(?:\\.[a-fA-F0-9]*)?|\\.[a-fA-F0-9]+)(?:[pP][+-]?[0-9]+)?")
                require(decimal.matches(value) || hex.matches(value))
                (if(hex.matches(value) && !value.contains('p',true)) value+"p0" else value).toDouble().also {require(it.isFinite())}
            }
            val lat=number("latitude");val lon=number("longitude");require((lat==null)==(lon==null))
            val coordinate=lat?.let {Coordinate(it,requireNotNull(lon))}
            fun date(key:String)=values[key]?.let {swiftDate(it).also {date->require(date<=now)}}
            val arrival=date("arrival");val departure=date("departure")
            val mode=if(host=="check-in") ManualVisitMode.current else ManualVisitMode.past
            if(mode==ManualVisitMode.past) require((arrival==null)==(departure==null) && (arrival==null || departure!!>=arrival))
            return Form(mode,name,coordinate,arrival,departure)
        }
        private fun decode(raw:String):String {
            val bytes=ByteArrayOutputStream();var index=0
            while(index<raw.length) {
                if(raw[index]=='%') {require(index+2<raw.length);bytes.write(raw.substring(index+1,index+3).toInt(16));index+=3}
                else {
                    val end=raw.indexOf('%',index).takeIf {it>=0} ?: raw.length
                    val encoded=Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(raw.substring(index,end)))
                    while(encoded.hasRemaining()) bytes.write(encoded.get().toInt() and 255)
                    index=end
                }
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        }
        // Foundation's selected ISO formatter accepts a valid prefix and uses the hybrid
        // Gregorian calendar, including day normalization and the 1582 cutover. Reference tested.
        private fun swiftDate(value:String):Instant {
            val m=Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(Z|[+-][0-9]{2}(?::?[0-9]{2})?)").find(value) ?: throw InvalidVisitLink()
            val fields=m.groupValues.drop(1).take(6).map(String::toInt)
            val year=fields[0];val month=fields[1];val day=fields[2];val hour=fields[3];val minute=fields[4];val second=fields[5]
            require(month in 1..12 && day in 1..31 && hour in 0..24 && minute in 0..59 && second in 0..59)
            val zone=m.groupValues[7]
            val offset=if(zone=="Z") 0 else {
                val digits=zone.drop(1).replace(":","");val h=digits.take(2).toInt();val min=digits.drop(2).ifEmpty {"0"}.toInt()
                require(h in 0..23 && min in 0..59);(h*60+min)*60_000*(if(zone[0]=='-') -1 else 1)
            }
            val calendar=GregorianCalendar(SimpleTimeZone(offset,"visit-link"),Locale.ROOT).apply {
                clear();isLenient=true;set(Calendar.ERA,GregorianCalendar.AD);set(year,month-1,day,hour,minute,second)
            }
            return Instant.ofEpochMilli(calendar.timeInMillis)
        }
    }
}
