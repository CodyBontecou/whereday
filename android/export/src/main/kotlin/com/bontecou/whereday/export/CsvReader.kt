package com.bontecou.whereday.export

import java.io.PushbackReader
import java.io.Reader

object CsvReader {
    /** RFC4180 logical rows, retaining quoted CRLF and trailing empty cells across input chunks. */
    fun read(text:String,checkpoint:()->Unit={}):List<List<String>> = buildList {
        scan(text.reader(),checkpoint) {_,row->add(row)}
    }
    /** Does not close the caller's reader; emissions are provisional until EOF validates quoting. */
    fun scan(source:Reader,checkpoint:()->Unit={},consume:(Int,List<String>)->Unit) {
        val reader=PushbackReader(ImportCheckpointReader(source,checkpoint).buffered(),1)
        var row=mutableListOf<String>();val field=StringBuilder()
        var quoted=false;var afterQuote=false;var characters=0L;var rowNumber=0;var first=true;var retained=0;var wire=0
        fun append(c:Char) {ImportRecordLimits.text(retained+field.length+1);field.append(c)}
        fun endField() {
            if(row.size>=ImportRecordLimits.columns) throw ImportRecordTooLarge()
            retained+=field.length;row+=field.toString();field.setLength(0);afterQuote=false
        }
        fun endRow() {endField();rowNumber=Math.addExact(rowNumber,1);consume(rowNumber,row);row=mutableListOf();retained=0;wire=0}
        while(true) {
            if(characters++%1024==0L) checkpoint()
            val value=reader.read();if(value<0) break
            val c=value.toChar();if(++wire>ImportRecordLimits.wireCharacters) throw ImportRecordTooLarge()
            if(first) {first=false;if(c=='\uFEFF') continue}
            if(quoted) {
                if(c=='"') {
                    val next=reader.read()
                    if(next=='"'.code) append('"') else {quoted=false;afterQuote=true;if(next>=0) reader.unread(next)}
                } else append(c)
            } else when(c) {
                '"'->{require(field.isEmpty() && !afterQuote) {"Unexpected quote in CSV"};quoted=true}
                ','->endField()
                '\n'->endRow()
                '\r'->{endRow();val next=reader.read();if(next>=0 && next!='\n'.code) reader.unread(next)}
                else->{require(!afterQuote) {"Unexpected character after CSV quote"};append(c)}
            }
        }
        require(!quoted) {"Unclosed CSV quote"}
        if(field.isNotEmpty() || row.isNotEmpty() || afterQuote) endRow()
        checkpoint()
    }
}
