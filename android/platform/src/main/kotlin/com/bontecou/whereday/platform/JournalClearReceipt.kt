package com.bontecou.whereday.platform

import java.util.UUID

internal data class JournalClearReceipt(val id:String,val complete:Boolean) {
    init {require(UUID.fromString(id).toString()==id)}
}
internal interface JournalClearReceiptStore {
    fun read():JournalClearReceipt?
    fun begin(id:String)
    fun complete(id:String)
}
class JournalClearPending:IllegalStateException("Journal clearing must finish before using the journal.")
