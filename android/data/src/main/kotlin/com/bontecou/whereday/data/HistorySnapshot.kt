package com.bontecou.whereday.data

import androidx.room.Transactor
import androidx.room.useReaderConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.withContext

/** Independent WAL reader: long exports must not acquire the recording writer transaction. */
suspend fun <T> HistoryDatabase.readSnapshot(block:suspend (HistoryDao)->T):T {
    val callerJob=currentCoroutineContext()[Job]
    return withContext(Dispatchers.IO) {
        check(supportsSnapshots) {"Snapshot reads require the configured history database driver"}
        useReaderConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
                // Room's reader callback uses the database job. Preserve the requesting operation's
                // cancellation while retaining the reader connection/transaction context elements.
                withContext(callerJob ?: EmptyCoroutineContext) {
                val dao=history()
                dao.totalPointCount() // Establish one snapshot before any repeated renderer pass.
                block(dao)
                }
            }
        }
}
}
