package com.bontecou.whereday.domain

import java.time.LocalDate

/** Same iOS eligibility: use on two civil days plus a completed file export, one attempt.
 * Only two dates are needed; no growing usage history, rating or export contents are kept. */
data class ReviewMilestone(val days:Set<LocalDate> = emptySet(),val exported:Boolean=false,val requested:Boolean=false) {
    init {require(days.size<=2)}
    val eligible get()=!requested && exported && days.size>=2
    fun used(day:LocalDate)=if(days.size>=2 || day in days) this else copy(days=days+day)
    fun fileExported(day:LocalDate)=used(day).copy(exported=true)
    fun claim()=if(eligible) copy(requested=true) else this
    fun cleared()=ReviewMilestone(requested=requested)
}
