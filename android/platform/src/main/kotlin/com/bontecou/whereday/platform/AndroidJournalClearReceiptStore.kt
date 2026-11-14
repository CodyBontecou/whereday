package com.bontecou.whereday.platform

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A retained completion tombstone prevents a later launch from replaying a completed deletion. */
internal class AndroidJournalClearReceiptStore(private val directory:File):JournalClearReceiptStore {
    constructor(context:Context):this(File(context.noBackupFilesDir,"journal-clear"))
    private val file=AtomicFile(File(directory,"receipt"))
    private val monitor=locks.getOrPut(directory.canonicalPath) {Any()}
    override fun read():JournalClearReceipt?=synchronized(monitor) {readLocked()}
    private fun readLocked():JournalClearReceipt? {
        require(!directory.exists() || directory.isDirectory)
        require(listOf("receipt","receipt.new","receipt.bak").none {File(directory,it).isDirectory})
        val backup=File(directory,"receipt.bak")
        val staged=File(directory,"receipt.new")
        if(staged.exists()) {
            // Keep an uncommitted newer request as a persistent fence. AtomicFile.openRead()
            // would delete .new, potentially exposing an older completion on the next launch.
            val committed=if(backup.exists()) backup else file.baseFile
            require(committed.isFile)
            val receipt=committed.inputStream().use {decode(it)}
            require(!receipt.complete)
            return receipt
        }
        if(!file.baseFile.exists() && !backup.exists()) return null
        return file.openRead().use {decode(it)}
    }
    private fun decode(input:java.io.InputStream):JournalClearReceipt {
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(128)
        while(true) {val count=input.read(buffer);if(count<0) break;require(output.size()+count<=256);output.write(buffer,0,count)}
        val bytes=output.toByteArray()
        val parts=bytes.toString(Charsets.UTF_8).split('\n')
        require(parts.size==5 && parts[0]=="whereday-clear-v1" && parts[4].isEmpty())
        require(parts[2] in setOf("pending","complete"))
        val body=parts.take(3).joinToString("\n",postfix="\n")
        require(parts[3]==digest(body))
        return JournalClearReceipt(parts[1],parts[2]=="complete")
    }
    override fun begin(id:String)=synchronized(monitor) {write(JournalClearReceipt(id,false))}
    override fun complete(id:String)=synchronized(monitor) {
        val current=requireNotNull(readLocked());require(current.id==id)
        if(!current.complete) write(current.copy(complete=true))
    }
    private fun write(receipt:JournalClearReceipt) {
        check(directory.isDirectory || directory.mkdirs())
        check(listOf("receipt","receipt.new","receipt.bak").none {File(directory,it).isDirectory})
        val body="whereday-clear-v1\n${receipt.id}\n${if(receipt.complete) "complete" else "pending"}\n"
        var output:FileOutputStream?=null
        try {output=file.startWrite();output.write((body+digest(body)+"\n").toByteArray(Charsets.UTF_8));output.fd.sync();file.finishWrite(output);check(readLocked()==receipt)}
        catch(error:Exception) {output?.let {file.failWrite(it)};throw error}
    }
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
    companion object {private val locks=ConcurrentHashMap<String,Any>()}
}
