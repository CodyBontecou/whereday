package com.bontecou.whereday.export

import java.io.Reader

class ImportRecordTooLarge:IllegalArgumentException("One history record contains too much text to import safely. Shorten its text and choose the file again.")
/** Resource bounds apply per record/batch, never to total history length. */
object ImportRecordLimits {
    const val textCharacters=256*1024
    const val payloadBytes=1024*1024
    const val batchBytes=1024*1024
    const val columns=256
    const val wireCharacters=textCharacters*6+8192
    fun text(size:Int) {if(size>textCharacters) throw ImportRecordTooLarge()}
}

/** Guard before a JSON scalar can allocate an unbounded StringBuilder. Unknown values may
 * still stream through skipValue. Six source characters cover one JSON unicode escape;
 * fixed buffering slack does not grow with the document. Exact decoded limits follow reads.
 */
internal class ImportScalarReader(private val source:Reader):Reader() {
    private var consumed=0L;private var ceiling=Long.MAX_VALUE
    fun <T> scalar(characters:Int,read:()->T):T {
        val previous=ceiling
        ceiling=minOf(ceiling,consumed+maxOf(0,characters).toLong()*6+8192)
        return try {read()} finally {ceiling=previous}
    }
    override fun read(buffer:CharArray,offset:Int,length:Int):Int {
        if(consumed>ceiling) throw ImportRecordTooLarge()
        val size=source.read(buffer,offset,minOf(length,8192,(ceiling-consumed).coerceAtMost(8192).toInt()+1))
        if(size>0) {consumed+=size;if(consumed>ceiling) throw ImportRecordTooLarge()}
        return size
    }
    override fun close() {}
}
/** Used only for line-oriented detection/Markdown, not one-line JSON documents. */
internal class ImportLineReader(private val source:Reader):Reader() {
    private var characters=0
    override fun read(buffer:CharArray,offset:Int,length:Int):Int {
        val size=source.read(buffer,offset,minOf(length,8192))
        for(index in offset until offset+maxOf(0,size)) {
            if(buffer[index]=='\n' || buffer[index]=='\r') characters=0 else {
                characters++;if(characters>ImportRecordLimits.wireCharacters) throw ImportRecordTooLarge()
            }
        }
        return size
    }
    override fun close() {}
}
