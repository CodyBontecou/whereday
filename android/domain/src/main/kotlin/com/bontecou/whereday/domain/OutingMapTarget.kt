package com.bontecou.whereday.domain

import java.util.UUID

/** One explicit camera action over captured outing bounds; the stored session is never rewritten. */
data class OutingMapTarget(val session:RecordingSession,val range:HistoryRange,val requestId:String=UUID.randomUUID().toString()) {
    init {
        require(range.from==session.startedAt)
        require(UUID.fromString(requestId).toString()==requestId)
    }
    companion object {
        fun capture(detail:OutingDetail)=OutingMapTarget(detail.session,detail.metrics.range)
    }
}
