package com.bontecou.whereday.platform

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import java.io.InputStream

class AndroidHistoryImportInput(context:Context,private val uri:Uri):HistoryImportInput {
    private val resolver=context.applicationContext.contentResolver
    private val signal=CancellationSignal()
    override fun open():InputStream {
        val descriptor=resolver.openAssetFileDescriptor(uri,"r",signal) ?: error("File unavailable")
        try {return descriptor.createInputStream()} catch(error:Exception) {descriptor.close();throw error}
    }
    override fun cancel() {signal.cancel()}
}
