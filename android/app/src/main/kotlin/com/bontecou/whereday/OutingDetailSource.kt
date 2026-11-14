package com.bontecou.whereday

import androidx.compose.runtime.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal interface OutingDetailSource {
    val invalidations:Flow<Unit>
    suspend fun read(id:String,header:InferredOutingHeader?):OutingDetail
}

internal class DatabaseOutingDetailSource(private val graph:AppGraph):OutingDetailSource {
    override val invalidations=graph.database.invalidationTracker.createFlow("points","sessions","visits").map {Unit}
    override suspend fun read(id:String,header:InferredOutingHeader?)=
        if(header==null) graph.history.outingDetail(id) else graph.history.inferredDetail(header)
}

internal sealed interface OutingState {
    data object Loading:OutingState
    data class Ready(val detail:OutingDetail,val header:InferredOutingHeader?,val refreshing:Boolean=false):OutingState
    data object Failed:OutingState
}

/** Keep a complete snapshot composed during refresh; a different outing gets a fresh owner. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable internal fun outingDetailState(source:OutingDetailSource,id:String,header:InferredOutingHeader?):State<OutingState> {
    val latestHeader by rememberUpdatedState(header)
    return key(source,id) {produceState<OutingState>(OutingState.Loading) {
        snapshotFlow {latestHeader}.flatMapLatest {requested->source.invalidations.map {requested}}.collectLatest {requested->
            value=(value as? OutingState.Ready)?.copy(refreshing=true) ?: OutingState.Loading
            try {
                val detail=source.read(id,requested)
                currentCoroutineContext().ensureActive()
                value=OutingState.Ready(detail,requested)
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {value=OutingState.Failed}
        }
    }}
}
