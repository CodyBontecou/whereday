package com.bontecou.whereday.export

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

/** Shared wire conversions used by the inspected Swift GPX and KML renderers. */
internal object XmlExportValues {
    val time=DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    // ISO8601DateFormatter enters Foundation Date through a Double Unix timestamp.
    fun swiftDateSeconds(value:Instant)=value.epochSecond.toDouble()+value.nano/1_000_000_000.0-978_307_200.0
    fun unixSeconds(value:Instant)=swiftDateSeconds(value)+978_307_200.0
    fun number(value:Double,decimals:Int=2):String {
        require(value.isFinite()) {"XML export numbers must be finite"}
        val magnitude=BigDecimal(kotlin.math.abs(value)).setScale(decimals,RoundingMode.HALF_EVEN).toPlainString()
        return if(java.lang.Double.doubleToRawLongBits(value)<0) "-$magnitude" else magnitude
    }
}
