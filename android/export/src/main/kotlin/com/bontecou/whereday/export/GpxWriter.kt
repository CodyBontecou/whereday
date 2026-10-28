package com.bontecou.whereday.export

import com.bontecou.whereday.domain.TrackPoint
import com.bontecou.whereday.domain.Visit
import java.io.Writer
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

/** Forward-only GPX output, shared by preview and disk delivery. Retains only the prior fix time. */
internal class GpxWriter(private val writer:Writer,private val options:ExportOptions,generatedAt:Instant) {
    private var previous:Instant?=null
    init {
        writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<gpx version=\"1.1\" creator=\"Whereday\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:isome=\"https://isome.isolated.tech/gpx/1.0\">\n")
            .append("  <metadata>\n    <name>Whereday export</name>\n    <time>${time.format(generatedAt)}</time>\n  </metadata>\n")
    }
    fun visit(visit:Visit):Boolean {
        if(!options.visitCoordinates) return false
        writer.append("  <wpt lat=\"${number(visit.coordinate.latitude,7)}\" lon=\"${number(visit.coordinate.longitude,7)}\">\n")
            .append("    <time>${time.format(visit.arrivedAt)}</time>\n")
        val name=visit.name?.takeIf {options.visitName && it.isNotEmpty()} ?: "Visit"
        writer.append("    <name>${HistoryExporter.xml(name)}</name>\n")
        val description=listOfNotNull(visit.address?.takeIf {options.visitAddress && it.isNotEmpty()},visit.notes?.takeIf {options.visitNotes && it.isNotEmpty()}).joinToString(" — ")
        if(description.isNotEmpty()) writer.append("    <desc>${HistoryExporter.xml(description)}</desc>\n")
        visit.departedAt?.let {departed->
            writer.append("    <extensions>\n      <isome:departedAt>${time.format(departed)}</isome:departedAt>\n")
            if(options.visitDuration) {
                val minutes=(swiftDateSeconds(departed)-swiftDateSeconds(visit.arrivedAt))/60.0
                writer.append("      <isome:durationMinutes>${number(minutes)}</isome:durationMinutes>\n")
            }
            writer.append("    </extensions>\n")
        }
        writer.append("  </wpt>\n")
        return true
    }
    fun point(point:TrackPoint) {
        if(previous==null) writer.append("  <trk>\n    <name>Whereday track</name>\n    <trkseg>\n")
        else if(swiftDateSeconds(point.time)-swiftDateSeconds(previous!!)>600.0) writer.append("    </trkseg>\n    <trkseg>\n")
        writer.append("      <trkpt lat=\"${number(point.coordinate.latitude,7)}\" lon=\"${number(point.coordinate.longitude,7)}\">\n")
        if(options.pointAltitude) point.altitude?.let {writer.append("        <ele>${number(it)}</ele>\n")}
        writer.append("        <time>${time.format(point.time)}</time>\n")
        val speed=point.speed?.takeIf {options.pointSpeed}
        if(speed!=null || options.pointAccuracy || options.pointOutlier && point.outlier) {
            writer.append("        <extensions>\n")
            speed?.let {writer.append("          <isome:speed>${number(it)}</isome:speed>\n")}
            if(options.pointAccuracy) writer.append("          <isome:horizontalAccuracy>${number(point.accuracy)}</isome:horizontalAccuracy>\n")
            if(options.pointOutlier && point.outlier) writer.append("          <isome:isOutlier>true</isome:isOutlier>\n")
            writer.append("        </extensions>\n")
        }
        writer.append("      </trkpt>\n");previous=point.time
    }
    fun finish() {
        if(previous!=null) writer.append("    </trkseg>\n  </trk>\n")
        writer.append("</gpx>\n")
    }
    companion object {
        private val time=DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        // ISO8601DateFormatter enters Foundation Date through a Double Unix timestamp.
        // Reproduce that wire-format calculation at rounding boundaries, without changing stored time.
        private fun swiftDateSeconds(value:Instant)=value.epochSecond.toDouble()+value.nano/1_000_000_000.0-978_307_200.0
        // Match the Swift/C fixed-decimal conversion, including binary rounding and negative zero.
        private fun number(value:Double,decimals:Int=2):String {
            require(value.isFinite()) {"GPX numbers must be finite"}
            val magnitude=BigDecimal(kotlin.math.abs(value)).setScale(decimals,RoundingMode.HALF_EVEN).toPlainString()
            return if(java.lang.Double.doubleToRawLongBits(value)<0) "-$magnitude" else magnitude
        }
    }
}
