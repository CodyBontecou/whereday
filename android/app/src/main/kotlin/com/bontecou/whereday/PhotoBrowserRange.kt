package com.bontecou.whereday

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.bontecou.whereday.domain.HistoryRange

internal data class PhotoBrowserRange(val range:HistoryRange,val hold:(HistoryRange?)->Unit)

/** An advancing map clock must not replace a directory while its browser is open.
 * Explicit selection changes reset the hold; source/access invalidation still closes it. */
@Composable internal fun rememberPhotoBrowserRange(selection:Any,range:HistoryRange):PhotoBrowserRange {
    val owner=selection.toString()
    var held by rememberSaveable(owner,stateSaver=PhotoBrowserHold.Saver) {mutableStateOf<PhotoBrowserHold?>(null)}
    return PhotoBrowserRange(held?.takeIf {it.owner==owner}?.range ?: range) {held=it?.let {PhotoBrowserHold(owner,it)}}
}
