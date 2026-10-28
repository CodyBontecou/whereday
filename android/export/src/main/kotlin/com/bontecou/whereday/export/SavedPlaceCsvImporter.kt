package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

data class SavedPlaceImportRow(val rowNumber:Int,val name:String,val place:SavedPlace?,val errors:List<String>)
data class SavedPlaceImportPreview(val rows:List<SavedPlaceImportRow>) {
    val places get()=rows.mapNotNull {it.place}
    val invalidRows get()=rows.filter {it.errors.isNotEmpty()}
}

/** Saved-place CSV is a separate contract from general history CSV. Parsing never changes storage. */
object SavedPlaceCsvImporter {
    const val maximumFileSize=5*1024*1024
    const val maximumRowCount=10_000
    fun parse(bytes:ByteArray,checkpoint:()->Unit={}):SavedPlaceImportPreview {
        require(bytes.size<=maximumFileSize) {"Choose a CSV file of 5 MB or smaller."}
        val content=try {Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")}
            catch(_:java.nio.charset.CharacterCodingException) {throw IllegalArgumentException("Could not read the CSV file as UTF-8 text.")}
        var columns:Map<String,Int>?=null
        val rows=mutableListOf<SavedPlaceImportRow>()
        records(content,checkpoint) {line,fields,syntax ->
            checkpoint()
            val header=columns
            if(header==null) {
                require(syntax.isEmpty()) {"The CSV header contains invalid quotation marks."}
                val names=fields.map {it.trim().lowercase(Locale.ROOT)}
                require(names.none {it.isEmpty()}) {"Every CSV column must have a header."}
                require(names.distinct().size==names.size) {"The CSV header contains duplicate column names."}
                val missing=listOf("name","latitude","longitude").filter {it !in names}
                require(missing.isEmpty()) {"Missing required CSV columns: ${missing.joinToString(", ")}."}
                columns=names.withIndex().associate {it.value to it.index}
            } else {
                require(rows.size<maximumRowCount) {"Import up to 10,000 saved places at a time."}
                fun field(key:String)=header[key]?.let {fields.getOrNull(it)?.trim()}.orEmpty()
                val name=field("name");val errors=syntax.toMutableList()
                if(fields.size!=header.size) errors+="Expected ${header.size} columns, found ${fields.size}."
                if(errors.isEmpty()) {
                    if(name.isEmpty()) errors+="A saved place needs a name."
                    val latitude=number(field("latitude"));val longitude=number(field("longitude"))
                    val radius=field("radius_meters").let {if(it.isEmpty()) 150.0 else number(it)}
                    if(latitude==null || !latitude.isFinite() || latitude !in -90.0..90.0) errors+="Latitude must be a number from -90 to 90."
                    if(longitude==null || !longitude.isFinite() || longitude !in -180.0..180.0) errors+="Longitude must be a number from -180 to 180."
                    if(radius==null || !radius.isFinite() || radius !in 25.0..10_000.0) errors+="Radius must be a number from 25 to 10,000 metres."
                    val place=if(errors.isEmpty()) SavedPlace(name=name,coordinate=Coordinate(latitude!!,longitude!!),address=field("address").ifEmpty {null},radius=radius!!) else null
                    rows+=SavedPlaceImportRow(line,name,place,errors.toList())
                } else rows+=SavedPlaceImportRow(line,name,null,errors.toList())
            }
        }
        require(columns!=null) {"The CSV file is empty."}
        require(rows.isNotEmpty()) {"The CSV file has no saved-place rows."}
        return SavedPlaceImportPreview(rows)
    }
    private val decimal=Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val hexadecimal=Regex("[+-]?0[xX](?:[0-9a-fA-F]+(?:\\.[0-9a-fA-F]*)?|\\.[0-9a-fA-F]+)(?:[pP][+-]?[0-9]+)?")
    private fun number(value:String):Double?=when {
        decimal.matches(value)->value.toDoubleOrNull()
        hexadecimal.matches(value)->(if('p' !in value.lowercase(Locale.ROOT)) value+"p0" else value).toDoubleOrNull()
        else->null
    }
    private enum class State { start,unquoted,quoted,closed }
    private fun records(content:String,checkpoint:()->Unit,record:(Int,List<String>,List<String>)->Unit) {
        var state=State.start;val field=StringBuilder();val fields=mutableListOf<String>();val errors=mutableListOf<String>()
        var line=1;var firstLine=1;var index=0;var touched=false;var quoted=false;var count=0
        fun syntax(message:String) {if(message !in errors) errors+=message}
        fun finish() {
            fields+=field.toString()
            if(fields.size>1 || quoted || fields[0].isNotBlank() || errors.isNotEmpty()) {
                require(++count<=maximumRowCount+1) {"Import up to 10,000 saved places at a time."}
                record(firstLine,fields.toList(),errors.toList())
            }
            fields.clear();field.clear();errors.clear();state=State.start;touched=false;quoted=false
        }
        while(index<content.length) {
            if(index%4096==0) checkpoint()
            val char=content[index];val newline=char=='\r' || char=='\n';val pair=char=='\r' && content.getOrNull(index+1)=='\n'
            if(state==State.quoted) {
                if(char=='"') {
                    if(content.getOrNull(index+1)=='"') {field.append('"');index+=2;continue}
                    state=State.closed
                } else {
                    field.append(char)
                    if(newline) {if(pair) {field.append('\n');index++};line++}
                }
                index++;continue
            }
            if(newline) {finish();line++;firstLine=line;index+=if(pair) 2 else 1;continue}
            touched=true
            when(char) {
                ','->{fields+=field.toString();field.clear();state=State.start}
                '"'->if(state==State.start) {state=State.quoted;quoted=true} else {
                    syntax("Quotation marks must enclose the whole field; escape an inner quote with two quotation marks.");field.append('"');state=State.unquoted
                }
                else->{if(state==State.closed) syntax("Unexpected text after a quoted field.");field.append(char);state=State.unquoted}
            }
            index++
        }
        if(state==State.quoted) errors+="A quoted field is missing its closing quotation mark."
        if(touched || fields.isNotEmpty() || field.isNotEmpty() || errors.isNotEmpty()) finish()
    }
}
