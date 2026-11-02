package com.bontecou.whereday

import androidx.compose.runtime.saveable.listSaver
import com.bontecou.whereday.domain.*
import java.time.Instant

/** Small logical opening only; archives, records and native renderers are reacquired. */
internal data class ReplayOpening(val owner:String,val request:ReplayRequest) {
    companion object {
        fun saved(value:ReplayOpening):List<Any> = listOf(1,value.owner)+when(val r=value.request) {
            is ReplayRequest.Range->listOf("range",r.range.from.toString(),r.range.until.toString(),r.includeOutliers)
            is ReplayRequest.Outing->listOf("outing",r.id)
            is ReplayRequest.Inferred->with(r.header) {listOf("inferred",id,startedAt.toString(),endedAt.toString(),summary.count,summary.outlierCount,summary.distanceMeters,summary.firstTime?.toString().orEmpty(),summary.lastTime?.toString().orEmpty())}
        }
        fun restored(values:List<Any>):ReplayOpening?=runCatching {
            require(values[0]==1)
            val request=when(values[2]) {
                "range"->{require(values.size==6);ReplayRequest.Range(HistoryRange(Instant.parse(values[3] as String),Instant.parse(values[4] as String)),values[5] as Boolean)}
                "outing"->{require(values.size==4);ReplayRequest.Outing((values[3] as String).also {require(it.isNotBlank())})}
                "inferred"->{
                    require(values.size==11)
                    val start=Instant.parse(values[4] as String);val end=Instant.parse(values[5] as String);require(start<=end)
                    val count=values[6] as Long;val outliers=values[7] as Long;val distance=values[8] as Double
                    require(count>=0 && outliers in 0..count && distance.isFinite() && distance>=0)
                    ReplayRequest.Inferred(InferredOutingHeader((values[3] as String).also {require(it.isNotBlank())},start,end,PointSummary(count,outliers,distance,(values[9] as String).takeIf {it.isNotEmpty()}?.let(Instant::parse),(values[10] as String).takeIf {it.isNotEmpty()}?.let(Instant::parse))))
                }
                else->error("Unknown replay opening")
            }
            ReplayOpening(values[1] as String,request)
        }.getOrNull()
        val Saver=listSaver<ReplayOpening?,Any>(save={it?.let(::saved).orEmpty()},restore=::restored)
    }
}
