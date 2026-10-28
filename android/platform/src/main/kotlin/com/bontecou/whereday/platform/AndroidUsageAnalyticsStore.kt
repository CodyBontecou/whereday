package com.bontecou.whereday.platform

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class AndroidUsageAnalyticsStore internal constructor(private val directory:File):UsageAnalyticsStore {
    constructor(context:Context):this(File(context.filesDir,"usage-analytics"))
    private val file=AtomicFile(File(directory,"state.json"))
    override suspend fun read():UsageConsentState=withContext(Dispatchers.IO) {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return@withContext UsageConsentState()
        file.openRead().use {stream ->
            val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {val count=stream.read(buffer);if(count<0) break;require(bytes.size()+count<=65_536);bytes.write(buffer,0,count)}
            UsageAnalyticsCodec.decode(bytes.toString(Charsets.UTF_8.name()))
        }
    }
    override suspend fun write(value:UsageConsentState)=withContext(Dispatchers.IO) {
        if(!directory.isDirectory && !directory.mkdirs()) throw IOException("Analytics storage is unavailable")
        val bytes=UsageAnalyticsCodec.encode(value).toByteArray(Charsets.UTF_8)
        val stream=file.startWrite()
        try {stream.write(bytes);stream.fd.sync();file.finishWrite(stream)}
        catch(error:Exception) {file.failWrite(stream);throw error}
    }
}
