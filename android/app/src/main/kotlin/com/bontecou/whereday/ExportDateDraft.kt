package com.bontecou.whereday

import androidx.compose.runtime.saveable.listSaver
import com.bontecou.whereday.export.ExportOptions
import java.time.*
import java.util.UUID

/** A date review owns its opening bounds/zone, while unrelated filters remain current. */
internal data class ExportDateDraft(
    val from:Instant,val until:Instant,val zone:ZoneId,val first:LocalDate,val last:LocalDate,
    val id:String=UUID.randomUUID().toString(),
) {
    fun matches(options:ExportOptions)=from==options.from && until==options.until && zone==options.zone
    fun applyTo(options:ExportOptions,first:LocalDate,last:LocalDate):ExportOptions? {
        if(!matches(options) || customDateError(first,last,null,zone)!=null) return null
        return options.copy(from=first.atStartOfDay(zone).toInstant(),until=last.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1))
    }
    fun saved()=listOf("1",id,from.toString(),until.toString(),zone.id,first.toString(),last.toString())
    companion object {
        fun open(options:ExportOptions,today:LocalDate=LocalDate.now(options.zone)):ExportDateDraft {
            val from=options.from.takeIf {it!=ExportOptions.allDatesStart}?.atZone(options.zone)?.toLocalDate()
            val until=options.until.takeIf {it!=Instant.MAX}?.atZone(options.zone)?.toLocalDate()
            return ExportDateDraft(options.from,options.until,options.zone,from ?: minOf(today,until ?: today),until ?: maxOf(today,from ?: today))
        }
        fun restored(values:List<String>):ExportDateDraft? = runCatching {
            require(values.size==7 && values[0]=="1" && values[1].isNotBlank())
            val from=Instant.parse(values[2]);val until=Instant.parse(values[3]);require(from<=until)
            ExportDateDraft(from,until,ZoneId.of(values[4]),LocalDate.parse(values[5]),LocalDate.parse(values[6]),values[1])
        }.getOrNull()
        val Saver=listSaver<ExportDateDraft?,String>(save={it?.saved().orEmpty()},restore=::restored)
    }
}
