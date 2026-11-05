package com.bontecou.whereday.export

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.Writer
import java.util.Locale

/** One empty, disk-backed identity set per merge; namespaces separate top-level JSON arrays. */
fun interface MergeIdentityStore { fun insert(namespace:String,identity:String):Boolean }
class ScheduledMergeRequiresRewrite(cause:Throwable):Exception("Existing JSON could not be merged; a complete-day rewrite is required.",cause)

/** Streaming port of the iOS scheduled CSV/JSON and default Markdown merge strategies.
 * Inputs are repeatable private staging files. Callers commit the output only after a successful merge.
 */
object ScheduledExportMerger {
    private val gson=GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    private val identityKeys=listOf("id","arrivedAt","timestamp","tst","startedAt","started_at")
    suspend fun merge(format:ExportFormat,existing:File,incoming:File,output:Writer,identities:MergeIdentityStore) {
        when(format) {
            ExportFormat.csv -> csv(existing,incoming,output)
            ExportFormat.json,ExportFormat.geojson,ExportFormat.owntracks,ExportFormat.overland -> try {json(existing,incoming,output,identities)} catch(e:Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                if(e is JsonSyntaxException || e is com.google.gson.stream.MalformedJsonException || e is java.io.EOFException || e is IllegalArgumentException || e is IllegalStateException) throw ScheduledMergeRequiresRewrite(e)
                throw e
            }
            ExportFormat.markdown -> markdown(existing,incoming,output)
            ExportFormat.gpx,ExportFormat.kml -> error("GPX and KML require a complete-day rewrite")
        }
        currentCoroutineContext().ensureActive();output.flush()
    }
    private data class Shape(val array:Boolean,val fields:LinkedHashMap<String,JsonElement?> = linkedMapOf())
    private fun reader(file:File)=JsonReader(file.bufferedReader(Charsets.UTF_8)).apply {strictness=Strictness.STRICT}
    private suspend fun shape(file:File):Shape=reader(file).use {r ->
        when(r.peek()) {
            JsonToken.BEGIN_ARRAY -> {r.skipValue();checkEnd(r);Shape(true)}
            JsonToken.BEGIN_OBJECT -> {
                val fields=linkedMapOf<String,JsonElement?>();r.beginObject()
                while(r.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    val key=r.nextName();require(!fields.containsKey(key) && fields.size<256) {"Invalid or oversized JSON export metadata"}
                    if(r.peek()==JsonToken.BEGIN_ARRAY) {fields[key]=null;r.skipValue()}
                    else fields[key]=JsonParser.parseReader(r)
                }
                r.endObject();checkEnd(r);Shape(false,fields)
            }
            else -> error("The existing export file could not be merged; a complete-day rewrite is required.")
        }
    }
    private fun checkEnd(r:JsonReader) {require(r.peek()==JsonToken.END_DOCUMENT) {"Unexpected trailing JSON content"}}
    private suspend fun json(existing:File,incoming:File,output:Writer,store:MergeIdentityStore) {
        val old=shape(existing);val fresh=shape(incoming)
        require(old.array==fresh.array) {"JSON root types differ; a complete-day rewrite is required."}
        val writer=JsonWriter(output).apply {setIndent("  ");isHtmlSafe=false;serializeNulls=true}
        if(fresh.array) {
            writer.beginArray();array(existing,null,writer,store,true);array(incoming,null,writer,store,true);writer.endArray()
        } else {
            writer.beginObject()
            for((key,value) in fresh.fields) {
                writer.name(key)
                if(value!=null) gson.toJson(value,writer)
                else {
                    writer.beginArray()
                    val merge=old.fields.containsKey(key) && old.fields[key]==null
                    if(merge) array(existing,key,writer,store,true)
                    array(incoming,key,writer,store,merge)
                    writer.endArray()
                }
            }
            writer.endObject()
        }
        writer.flush();output.append('\n')
    }
    private suspend fun array(file:File,key:String?,out:JsonWriter,store:MergeIdentityStore,dedupe:Boolean) {
        reader(file).use {r ->
            if(key!=null) {
                r.beginObject()
                while(r.hasNext()) {if(r.nextName()==key) break else r.skipValue()}
            }
            require(r.peek()==JsonToken.BEGIN_ARRAY)
            r.beginArray()
            while(r.hasNext()) {
                currentCoroutineContext().ensureActive()
                val value=JsonParser.parseReader(r)
                val identity=identity(value)
                if(!dedupe || identity==null || store.insert(key ?: "$",identity)) gson.toJson(value,out)
            }
            r.endArray()
            if(key!=null) {while(r.hasNext()) {r.nextName();r.skipValue()};r.endObject()}
            checkEnd(r)
        }
    }
    private fun identity(value:JsonElement):String? {
        if(!value.isJsonObject) return null
        for(key in identityKeys) {
            val item=value.asJsonObject.get(key) ?: continue
            // Exported identities are strings or numeric timestamps. NSNull also has stable identity on iOS.
            val text=when {
                item.isJsonNull -> "<null>"
                item.isJsonPrimitive -> when {
                    item.asJsonPrimitive.isBoolean -> if(item.asBoolean) "1" else "0"
                    item.asJsonPrimitive.isNumber -> {
                        val raw=item.asString;require(raw.length<=256) {"Oversized JSON identity"}
                        val number=raw.toBigDecimal().stripTrailingZeros()
                        require(number.scale() in -324..324) {"JSON identity is outside the supported numeric range"}
                        if(number.signum()==0 && raw.startsWith('-')) "-0" else number.toPlainString()
                    }
                    else -> item.asString
                }
                else -> error("A JSON record has a non-scalar identity; a complete-day rewrite is required.")
            }
            return "$key=$text"
        }
        return null
    }
    private suspend fun csv(existing:File,incoming:File,out:Writer) {
        val span=trimmedSpan(existing)
        if(span==null) return copy(incoming,out) // Existing whitespace is discarded by iOS.
        var body=false;var first=true
        lines(incoming) {line,_,_ ->if(first) first=false else if(line.trim().isNotEmpty()) body=true;true}
        if(!body) return copy(existing,out)
        copySpan(existing,span.first,span.second,out);out.append('\n');first=true
        lines(incoming) {line,_,_ ->
            if(first) first=false else line.trim().takeIf {it.isNotEmpty()}?.let {out.append(it).append('\n')}
            true
        }
    }
    private suspend fun trimmedSpan(file:File):Pair<Long,Long>? {
        var position=0L;var first:Long?=null;var end=0L
        file.bufferedReader(Charsets.UTF_8).use {reader ->
            val chars=CharArray(16*1024)
            while(true) {
                currentCoroutineContext().ensureActive();val n=reader.read(chars);if(n<0) break
                for(i in 0 until n) {if(!chars[i].isWhitespace()) {if(first==null) first=position;end=position+1};position++}
            }
        }
        return first?.let {it to end}
    }
    /** LF splitting preserves CRs and includes the final empty line, matching Swift components. */
    private suspend fun lines(file:File,consume:suspend (String,Long,Long)->Boolean) {
        file.bufferedReader(Charsets.UTF_8).use {r ->
            val line=StringBuilder();var start=0L;var position=0L;val chars=CharArray(16*1024)
            while(true) {
                currentCoroutineContext().ensureActive();val n=r.read(chars);if(n<0) break
                for(i in 0 until n) {
                    val c=chars[i];position++
                    if(c=='\n') {if(!consume(line.toString(),start,position)) return;line.setLength(0);start=position}
                    else line.append(c)
                }
            }
            consume(line.toString(),start,position+1)
        }
    }
    private suspend fun copy(file:File,out:Writer)=copySpan(file,0,Long.MAX_VALUE,out,false)
    private suspend fun copySpan(file:File,start:Long,end:Long,out:Writer,addFinalLF:Boolean=true) {
        file.bufferedReader(Charsets.UTF_8).use {r ->
            val buffer=CharArray(16*1024);var position=0L
            while(position<end) {
                currentCoroutineContext().ensureActive();val n=r.read(buffer);if(n<0) break
                val from=maxOf(start-position,0).coerceAtMost(n.toLong()).toInt()
                val until=minOf(end-position,n.toLong()).toInt()
                if(until>from) out.write(buffer,from,until-from)
                position+=n
            }
            if(addFinalLF && end>position && start<=position) out.append('\n')
        }
    }
    private data class Section(val name:String,val start:Long,var end:Long)
    private data class Markdown(val file:File,val frontmatterEnd:Long,val preambleEnd:Long,val sections:List<Section>)
    private suspend fun parseMarkdown(file:File):Markdown {
        var first=true;var front=false;var frontEnd=0L
        lines(file) {line,_,end ->
            if(first) {first=false;front=line.trim {it!='\r' && it.isWhitespace()}=="---";front}
            else if(line.trim {it!='\r' && it.isWhitespace()}=="---") {frontEnd=end;false} else true
        }
        val sections=mutableListOf<Section>();var preambleEnd=frontEnd;var endOfDocument=frontEnd
        lines(file) {line,start,end ->
            endOfDocument=end
            if(start>=frontEnd) {
                if(headingLevel(line)==2) {
                    require(sections.size<10_000) {"Too many Markdown sections to merge safely"}
                    sections.lastOrNull()?.end=start
                    sections+=Section(normalizedHeading(line),start,end)
                } else if(sections.isEmpty()) preambleEnd=end
            }
            true
        }
        sections.lastOrNull()?.end=endOfDocument
        return Markdown(file,frontEnd,preambleEnd,sections)
    }
    private fun headingLevel(line:String):Int {
        val trimmed=line.trim {it!='\r' && it.isWhitespace()};val count=trimmed.takeWhile {it=='#'}.length
        return if(count>0 && trimmed.getOrNull(count)==' ') count else 0
    }
    private fun normalizedHeading(line:String)=line.dropWhile {it=='#' || it==' '}
        .filter {it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it==' '}
        .split(' ').filter {it.isNotEmpty()}.joinToString(" ").lowercase(Locale.ROOT)
    private suspend fun markdown(existing:File,incoming:File,out:Writer) {
        val old=parseMarkdown(existing);val fresh=parseMarkdown(incoming)
        val sections=fresh.sections.associateBy {it.name};val order=fresh.sections.map {it.name}.distinct();val placed=mutableSetOf<String>()
        out.append(mergeFrontmatter(frontmatter(old),frontmatter(fresh)))
        copySpan(incoming,fresh.frontmatterEnd,fresh.preambleEnd,out)
        for(section in old.sections) {
            val replacement=sections[section.name]
            if(replacement!=null) {copySpan(incoming,replacement.start,replacement.end,out);placed+=section.name}
            else copySpan(existing,section.start,section.end,out)
        }
        for(name in order) if(name !in placed) sections.getValue(name).let {copySpan(incoming,it.start,it.end,out)}
    }
    private suspend fun frontmatter(doc:Markdown):String {
        require(doc.frontmatterEnd<=1_048_576) {"Markdown frontmatter is too large to merge safely"}
        return java.io.StringWriter().also {copySpan(doc.file,0,doc.frontmatterEnd,it)}.toString()
    }
    private fun properties(value:String):List<Pair<String,String>> {
        val result=mutableListOf<Pair<String,String>>();var key:String?=null;var body="";var multiline=false
        fun trim(v:String)=v.trim {it!='\n' && it!='\r' && it.isWhitespace()}
        for(line in value.split('\n')) {
            val text=trim(line)
            if(text=="---" || text.isEmpty() && !multiline) continue
            val colon=line.indexOf(':')
            if(colon>=0 && (!multiline || !line.startsWith(' '))) {
                key?.let {result+=it to trim(body)}
                key=trim(line.substring(0,colon));body=trim(line.substring(colon+1))
                multiline=body.isEmpty() || body=="|" || body==">" || body.startsWith('[') && !body.endsWith(']')
            } else if(multiline && key!=null) {
                body=if(body.isEmpty()) line else "$body\n$line"
                if(body.startsWith('[') && ']' in line) multiline=false
            }
        }
        key?.let {result+=it to trim(body)}
        return result
    }
    private fun mergeFrontmatter(old:String,fresh:String):String {
        val a=properties(old);val b=properties(fresh)
        if(a.isEmpty() && b.isEmpty()) return ""
        if(a.isEmpty()) return fresh
        if(b.isEmpty()) return old
        val values=linkedMapOf<String,String>();(a+b).forEach {(key,value)->values[key]=value}
        return "---\n"+values.entries.joinToString("") {"${it.key}: ${it.value}\n"}+"---\n"
    }
}
