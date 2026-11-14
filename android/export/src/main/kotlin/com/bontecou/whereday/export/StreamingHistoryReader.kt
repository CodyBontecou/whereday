package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader
import java.util.concurrent.CancellationException

data class ImportScan(val visits:Int,val points:Int,val rejected:Int,val errors:List<String>,val source:HistoryImportSource=HistoryImportSource.whereday,val unsupported:Int=0)

internal class ImportScanCollector(private val onVisit:(Visit)->Unit,private val onPoint:(TrackPoint)->Unit) {
    private var visits=0;private var points=0;private var rejected=0;private var unsupported=0
    private val errors=mutableListOf<String>()
    fun unsupported() {unsupported=Math.addExact(unsupported,1)}
    fun error(message:String) {rejected=Math.addExact(rejected,1);if(errors.size<10) errors+=message.take(200)}
    fun <T> decode(label:String,block:()->T):T?=try {block()} catch(failure:Exception) {
        if(failure is CancellationException || failure is ImportRecordTooLarge) throw failure
        error("$label: ${failure.message}");null
    }
    fun visit(value:Visit) {onVisit(value);visits=Math.addExact(visits,1)}
    fun point(value:TrackPoint) {onPoint(value);points=Math.addExact(points,1)}
    fun finish():ImportScan {
        require(visits>0 || points>0) {errors.firstOrNull() ?: "No importable history found"}
        return ImportScan(visits,points,rejected,errors.toList(),unsupported=unsupported)
    }
}

/** Also checkpoints while skipping unknown metadata or consuming a single very long field. */
internal class ImportCheckpointReader(private val source:Reader,private val checkpoint:()->Unit):Reader() {
    override fun read(buffer:CharArray,offset:Int,length:Int):Int {checkpoint();return source.read(buffer,offset,minOf(length,8192))}
    override fun close() {} // Ownership stays with the caller, including failure/cancellation.
}

internal object StreamingHistoryReader {
    fun json(source:Reader,known:Set<String>,checkpoint:()->Unit,consume:(String,Int,Map<String,String>,String?)->Unit) {
        val budget=ImportScalarReader(source)
        val prefix=java.io.PushbackReader(budget.buffered(),1)
        var bom=false
        while(true) {
            val c=prefix.read();if(c<0) break
            if(c.toChar().isWhitespace()) continue
            if(c=='\uFEFF'.code && !bom) {bom=true;continue}
            prefix.unread(c);break
        }
        val reader=JsonReader(prefix).apply {strictness=Strictness.STRICT}
        // Normalize the legacy whitespace/BOM prefix before feeding strict JSON.
        require(reader.peek()==JsonToken.BEGIN_OBJECT) {"Expected JSON object at root"}
        reader.beginObject();val subjects=mutableSetOf<String>()
        while(reader.hasNext()) {
            checkpoint();val key=budget.scalar(1024) {reader.nextName()}
            if(key.length>1024) throw ImportRecordTooLarge()
            if(key!="visits" && key!="points") {reader.skipValue();continue}
            require(subjects.add(key)) {"Duplicate JSON history array"}
            if(reader.peek()==JsonToken.NULL) {reader.nextNull();continue}
            require(reader.peek()==JsonToken.BEGIN_ARRAY) {"Expected $key array"}
            reader.beginArray();var index=0
            while(reader.hasNext()) {
                checkpoint();index=Math.addExact(index,1)
                val fields=linkedMapOf<String,String>();var issue:String?=null;var characters=0
                if(reader.peek()!=JsonToken.BEGIN_OBJECT) {
                    reader.skipValue();issue="Expected history record object"
                } else {
                    reader.beginObject();val seen=mutableSetOf<String>()
                    while(reader.hasNext()) {
                        checkpoint();val field=budget.scalar(1024) {reader.nextName()}
                        if(field.length>1024) throw ImportRecordTooLarge()
                        characters+=field.length;ImportRecordLimits.text(characters)
                        if(field !in known) {reader.skipValue();continue}
                        if(!seen.add(field)) issue="Duplicate value for $field"
                        when(reader.peek()) {
                            JsonToken.NULL->reader.nextNull()
                            JsonToken.STRING,JsonToken.NUMBER->{
                                val value=budget.scalar(ImportRecordLimits.textCharacters-characters) {reader.nextString()}
                                characters+=value.length;ImportRecordLimits.text(characters);fields[field]=value
                            }
                            JsonToken.BOOLEAN->{
                                val value=reader.nextBoolean().toString();characters+=value.length;ImportRecordLimits.text(characters);fields[field]=value
                            }
                            else->{reader.skipValue();issue="Invalid value for $field"}
                        }
                    }
                    reader.endObject()
                }
                consume(key,index,fields,issue)
            }
            reader.endArray()
        }
        reader.endObject()
        require(reader.peek()==JsonToken.END_DOCUMENT) {"Unexpected trailing JSON content"}
    }
}
