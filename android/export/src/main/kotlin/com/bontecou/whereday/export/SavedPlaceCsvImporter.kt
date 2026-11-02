package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

enum class SavedPlaceCsvProblem {
    fileSize,encoding,headerQuotes,headerEmpty,headerDuplicate,headerMissing,rowLimit,fileEmpty,noRows,
    fieldCount,placeName,latitude,longitude,radius,quoteWholeField,afterQuote,closingQuote
}
data class SavedPlaceCsvIssue(val problem:SavedPlaceCsvProblem,val expected:Int=0,val actual:Int=0,val columns:List<String> = emptyList()) {
    val description:String get()=when(problem) {
        SavedPlaceCsvProblem.fileSize->"Choose a CSV file of 5 MB or smaller."
        SavedPlaceCsvProblem.encoding->"Could not read the CSV file as UTF-8 text."
        SavedPlaceCsvProblem.headerQuotes->"The CSV header contains invalid quotation marks."
        SavedPlaceCsvProblem.headerEmpty->"Every CSV column must have a header."
        SavedPlaceCsvProblem.headerDuplicate->"The CSV header contains duplicate column names."
        SavedPlaceCsvProblem.headerMissing->"Missing required CSV columns: ${columns.joinToString(", ")}."
        SavedPlaceCsvProblem.rowLimit->"Import up to 10,000 saved places at a time."
        SavedPlaceCsvProblem.fileEmpty->"The CSV file is empty."
        SavedPlaceCsvProblem.noRows->"The CSV file has no saved-place rows."
        SavedPlaceCsvProblem.fieldCount->"Expected $expected columns, found $actual."
        SavedPlaceCsvProblem.placeName->"A saved place needs a name."
        SavedPlaceCsvProblem.latitude->"Latitude must be a number from -90 to 90."
        SavedPlaceCsvProblem.longitude->"Longitude must be a number from -180 to 180."
        SavedPlaceCsvProblem.radius->"Radius must be a number from 25 to 10,000 metres."
        SavedPlaceCsvProblem.quoteWholeField->"Quotation marks must enclose the whole field; escape an inner quote with two quotation marks."
        SavedPlaceCsvProblem.afterQuote->"Unexpected text after a quoted field."
        SavedPlaceCsvProblem.closingQuote->"A quoted field is missing its closing quotation mark."
    }
}
class InvalidSavedPlaceCsv(val issue:SavedPlaceCsvIssue):IllegalArgumentException(issue.description)
private fun csvRequire(valid:Boolean,issue:SavedPlaceCsvIssue) {if(!valid) throw InvalidSavedPlaceCsv(issue)}
data class SavedPlaceImportRow(val rowNumber:Int,val name:String,val place:SavedPlace?,val issues:List<SavedPlaceCsvIssue>) {
    val errors:List<String> get()=issues.map {it.description}
}
data class SavedPlaceImportPreview(val rows:List<SavedPlaceImportRow>) {
    val places get()=rows.mapNotNull {it.place}
    val invalidRows get()=rows.filter {it.issues.isNotEmpty()}
}

/** Saved-place CSV is a separate contract from general history CSV. Parsing never changes storage. */
object SavedPlaceCsvImporter {
    const val maximumFileSize=5*1024*1024
    const val maximumRowCount=10_000
    fun parse(bytes:ByteArray,checkpoint:()->Unit={}):SavedPlaceImportPreview {
        csvRequire(bytes.size<=maximumFileSize,SavedPlaceCsvIssue(SavedPlaceCsvProblem.fileSize))
        val content=try {Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")}
            catch(_:java.nio.charset.CharacterCodingException) {throw InvalidSavedPlaceCsv(SavedPlaceCsvIssue(SavedPlaceCsvProblem.encoding))}
        var columns:Map<String,Int>?=null
        val rows=mutableListOf<SavedPlaceImportRow>()
        records(content,checkpoint) {line,fields,syntax ->
            checkpoint()
            val header=columns
            if(header==null) {
                csvRequire(syntax.isEmpty(),SavedPlaceCsvIssue(SavedPlaceCsvProblem.headerQuotes))
                val names=fields.map {it.trim().lowercase(Locale.ROOT)}
                csvRequire(names.none {it.isEmpty()},SavedPlaceCsvIssue(SavedPlaceCsvProblem.headerEmpty))
                csvRequire(names.distinct().size==names.size,SavedPlaceCsvIssue(SavedPlaceCsvProblem.headerDuplicate))
                val missing=listOf("name","latitude","longitude").filter {it !in names}
                csvRequire(missing.isEmpty(),SavedPlaceCsvIssue(SavedPlaceCsvProblem.headerMissing,columns=missing))
                columns=names.withIndex().associate {it.value to it.index}
            } else {
                csvRequire(rows.size<maximumRowCount,SavedPlaceCsvIssue(SavedPlaceCsvProblem.rowLimit))
                fun field(key:String)=header[key]?.let {fields.getOrNull(it)?.trim()}.orEmpty()
                val name=field("name");val errors=syntax.toMutableList()
                if(fields.size!=header.size) errors+=SavedPlaceCsvIssue(SavedPlaceCsvProblem.fieldCount,header.size,fields.size)
                if(errors.isEmpty()) {
                    if(name.isEmpty()) errors+=SavedPlaceCsvIssue(SavedPlaceCsvProblem.placeName)
                    val latitude=number(field("latitude"));val longitude=number(field("longitude"))
                    val radius=field("radius_meters").let {if(it.isEmpty()) 150.0 else number(it)}
                    if(latitude==null || !latitude.isFinite() || latitude !in -90.0..90.0) errors+=SavedPlaceCsvIssue(SavedPlaceCsvProblem.latitude)
                    if(longitude==null || !longitude.isFinite() || longitude !in -180.0..180.0) errors+=SavedPlaceCsvIssue(SavedPlaceCsvProblem.longitude)
                    if(radius==null || !radius.isFinite() || radius !in 25.0..10_000.0) errors+=SavedPlaceCsvIssue(SavedPlaceCsvProblem.radius)
                    val place=if(errors.isEmpty()) SavedPlace(name=name,coordinate=Coordinate(latitude!!,longitude!!),address=field("address").ifEmpty {null},radius=radius!!) else null
                    rows+=SavedPlaceImportRow(line,name,place,errors.toList())
                } else rows+=SavedPlaceImportRow(line,name,null,errors.toList())
            }
        }
        csvRequire(columns!=null,SavedPlaceCsvIssue(SavedPlaceCsvProblem.fileEmpty))
        csvRequire(rows.isNotEmpty(),SavedPlaceCsvIssue(SavedPlaceCsvProblem.noRows))
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
    private fun records(content:String,checkpoint:()->Unit,record:(Int,List<String>,List<SavedPlaceCsvIssue>)->Unit) {
        var state=State.start;val field=StringBuilder();val fields=mutableListOf<String>();val errors=mutableListOf<SavedPlaceCsvIssue>()
        var line=1;var firstLine=1;var index=0;var touched=false;var quoted=false;var count=0
        fun syntax(problem:SavedPlaceCsvProblem) {val issue=SavedPlaceCsvIssue(problem);if(issue !in errors) errors+=issue}
        fun finish() {
            fields+=field.toString()
            if(fields.size>1 || quoted || fields[0].isNotBlank() || errors.isNotEmpty()) {
                csvRequire(++count<=maximumRowCount+1,SavedPlaceCsvIssue(SavedPlaceCsvProblem.rowLimit))
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
                    syntax(SavedPlaceCsvProblem.quoteWholeField);field.append('"');state=State.unquoted
                }
                else->{if(state==State.closed) syntax(SavedPlaceCsvProblem.afterQuote);field.append(char);state=State.unquoted}
            }
            index++
        }
        if(state==State.quoted) errors+=SavedPlaceCsvIssue(SavedPlaceCsvProblem.closingQuote)
        if(touched || fields.isNotEmpty() || field.isNotEmpty() || errors.isNotEmpty()) finish()
    }
}
