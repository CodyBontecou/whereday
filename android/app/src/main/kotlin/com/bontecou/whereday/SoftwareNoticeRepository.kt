package com.bontecou.whereday

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

private const val maxNoticeBytes=4*1024*1024
private val noticeDigest=Regex("[a-f0-9]{64}")
internal fun softwareNoticeDigest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
internal data class SoftwareNoticeDocument(val sha256:String,val title:String,val bytes:Int) {
    init {require(noticeDigest.matches(sha256) && title.isNotBlank() && title.length<=512 && bytes in 1..maxNoticeBytes)}
}
internal data class SoftwareNoticeComponent(val id:String,val name:String,val coordinate:String,val documents:List<SoftwareNoticeDocument>) {
    init {
        require(name.isNotBlank() && name.length<=512 && coordinate.length in 1..256)
        require(id==softwareNoticeDigest(coordinate.toByteArray(Charsets.UTF_8)))
        require(documents.isNotEmpty() && documents.size<=256 && documents.map {it.sha256}.distinct().size==documents.size)
    }
}

/** The selected digest is the only asset filename. No stored state, name or URL is a path. */
internal class SoftwareNoticeRepository(private val context:Context) {
    private suspend fun bytes(asset:String,bound:Int):ByteArray {
        val result=ByteArrayOutputStream()
        context.assets.open(asset).use {stream->
            val buffer=ByteArray(8192)
            while(true) {
                currentCoroutineContext().ensureActive()
                val count=stream.read(buffer);if(count<0) break
                require(result.size()+count<=bound)
                result.write(buffer,0,count)
            }
        }
        return result.toByteArray()
    }
    suspend fun catalog():List<SoftwareNoticeComponent> = withContext(Dispatchers.IO) {
        val root=JSONObject(bytes("software-notices/catalog.json",1024*1024).decodeToString(throwOnInvalidSequence=true))
        require(root.getInt("schemaVersion")==1)
        val entries=root.getJSONArray("entries");require(entries.length() in 1..1000)
        List(entries.length()) {index->
            val item=entries.getJSONObject(index);val documents=item.getJSONArray("documents")
            require(documents.length() in 1..256)
            SoftwareNoticeComponent(item.getString("id"),item.getString("name"),item.getString("coordinate"),
                List(documents.length()) {i->val doc=documents.getJSONObject(i)
                    SoftwareNoticeDocument(doc.getString("sha256"),doc.getString("title"),doc.getInt("bytes"))})
        }.also {require(it.map {item->item.id}.distinct().size==it.size)}
    }
    suspend fun document(document:SoftwareNoticeDocument):List<String> = withContext(Dispatchers.IO) {
        val raw=bytes("software-notices/documents/${document.sha256}.txt",maxNoticeBytes)
        require(raw.size==document.bytes && softwareNoticeDigest(raw)==document.sha256)
        softwareNoticeChunks(raw.decodeToString(throwOnInvalidSequence=true))
    }
}

/** Virtualized text chunks concatenate to the original document, without splitting surrogate pairs. */
internal fun softwareNoticeChunks(text:String,limit:Int=3000):List<String> {
    require(limit>=2)
    val result=ArrayList<String>();var start=0
    while(start<text.length) {
        var end=minOf(text.length,start+limit)
        if(end<text.length) {
            val newline=text.lastIndexOf('\n',end-1)
            if(newline>=start+limit/2) end=newline+1
            if(end>start && text[end-1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            if(end>start && text[end-1]=='\r' && text[end]=='\n') end--
        }
        result.add(text.substring(start,end));start=end
    }
    return result
}
