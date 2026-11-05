package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.XmlExportValues.number
import com.bontecou.whereday.export.XmlExportValues.time
import com.bontecou.whereday.export.XmlExportValues.swiftDateSeconds
import java.io.*
import java.time.Instant

/** XML emission is independent of how a bounded session is reread. */
internal class KmlWriter(private val writer:Writer,private val options:ExportOptions,generatedAt:Instant) {
    private var sessionIndex=0L
    init {
        writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\" xmlns:isome=\"https://isome.isolated.tech/gpx/1.0\">\n")
            .append("  <Document>\n    <name>Whereday export</name>\n    <open>1</open>\n    <ExtendedData>\n")
            .append("      <Data name=\"generator\"><value>Whereday</value></Data>\n")
            .append("      <Data name=\"exportDate\"><value>${time.format(generatedAt)}</value></Data>\n    </ExtendedData>\n")
    }
    private fun data(name:String,value:String) {writer.append("        <Data name=\"$name\"><value>${HistoryExporter.xml(value)}</value></Data>\n")}
    fun visit(visit:Visit):Boolean {
        if(!options.visitCoordinates) return false
        val name=visit.name?.takeIf {options.visitName && it.isNotEmpty()} ?: "Visit"
        writer.append("    <Placemark>\n      <name>${HistoryExporter.xml(name)}</name>\n")
            .append("      <TimeStamp><when>${time.format(visit.arrivedAt)}</when></TimeStamp>\n")
        val address=visit.address?.takeIf {options.visitAddress && it.isNotEmpty()}
        val notes=visit.notes?.takeIf {options.visitNotes && it.isNotEmpty()}
        val description=listOfNotNull(address,notes).joinToString("\n\n")
        if(description.isNotEmpty()) writer.append("      <description>${HistoryExporter.xml(description)}</description>\n")
        writer.append("      <ExtendedData>\n");data("kind","visit");data("arrivedAt",time.format(visit.arrivedAt))
        visit.departedAt?.let {
            data("departedAt",time.format(it))
            if(options.visitDuration) data("durationMinutes",number((swiftDateSeconds(it)-swiftDateSeconds(visit.arrivedAt))/60.0))
        }
        address?.let {data("address",it)};notes?.let {data("notes",it)}
        XmlVisitAudit.fields(visit,options).forEach {(key,value)->data(key,value)}
        writer.append("      </ExtendedData>\n      <Point><coordinates>${coordinate(visit.coordinate,null)}</coordinates></Point>\n    </Placemark>\n")
        return true
    }
    /** read must repeat exactly the same ordered session on each invocation. */
    fun session(count:Long,first:TrackPoint,last:TrackPoint,read:((TrackPoint)->Unit)->Unit) {
        require(count>0);sessionIndex=Math.addExact(sessionIndex,1)
        writer.append("    <Placemark>\n      <name>Whereday session $sessionIndex</name>\n")
            .append("      <TimeSpan><begin>${time.format(first.time)}</begin><end>${time.format(last.time)}</end></TimeSpan>\n")
            .append("      <ExtendedData>\n")
        data("kind","trackingSession");data("pointCount",count.toString())
        writer.append("      </ExtendedData>\n      <MultiGeometry>\n")
        if(count>1) {
            writer.append("        <LineString>\n          <tessellate>1</tessellate>\n          <altitudeMode>clampToGround</altitudeMode>\n          <coordinates>\n")
            read {writer.append("            ${coordinate(it.coordinate,it.altitude.takeIf {options.pointAltitude})}\n")}
            writer.append("          </coordinates>\n        </LineString>\n")
        } else writer.append("        <Point><coordinates>${coordinate(first.coordinate,first.altitude.takeIf {options.pointAltitude})}</coordinates></Point>\n")
        writer.append("        <gx:Track>\n          <altitudeMode>clampToGround</altitudeMode>\n")
        read {writer.append("          <when>${time.format(it.time)}</when>\n")}
        read {writer.append("          <gx:coord>${number(it.coordinate.longitude,7)} ${number(it.coordinate.latitude,7)} ${number(it.altitude.takeIf {options.pointAltitude} ?: 0.0)}</gx:coord>\n")}
        writer.append("        </gx:Track>\n      </MultiGeometry>\n    </Placemark>\n")
    }
    fun finish() {writer.append("  </Document>\n</kml>\n")}
    private fun coordinate(value:Coordinate,altitude:Double?)="${number(value.longitude,7)},${number(value.latitude,7)}"+(altitude?.let {",${number(it)}"} ?: "")
}

/** One app-private binary session spool, reused across gaps and deleted on every exit. */
internal class KmlSessionSpool(private val kml:KmlWriter,directory:File?,private val checkpoint:()->Unit):AutoCloseable {
    private val file=File.createTempFile("whereday-kml-",".tmp",directory)
    private var output:DataOutputStream?=null
    private var count=0L
    private var first:TrackPoint?=null
    private var last:TrackPoint?=null
    fun append(point:TrackPoint) {
        checkpoint()
        if(last?.let {swiftDateSeconds(point.time)-swiftDateSeconds(it.time)>600.0}==true) finishSession()
        if(output==null) output=DataOutputStream(file.outputStream().buffered())
        output!!.apply {
            writeDouble(point.coordinate.latitude);writeDouble(point.coordinate.longitude)
            writeLong(point.time.epochSecond);writeInt(point.time.nano)
            writeBoolean(point.altitude!=null);writeDouble(point.altitude ?: 0.0)
        }
        count=Math.addExact(count,1)
        if(first==null) first=point
        last=point
    }
    fun finishSession() {
        if(count==0L) return
        output?.close();output=null
        kml.session(count,requireNotNull(first),requireNotNull(last)) {consume->
            DataInputStream(file.inputStream().buffered()).use {input->
                var remaining=count
                while(remaining>0) {
                    checkpoint()
                    val coordinate=Coordinate(input.readDouble(),input.readDouble())
                    val time=Instant.ofEpochSecond(input.readLong(),input.readInt().toLong())
                    val hasAltitude=input.readBoolean();val altitude=input.readDouble().takeIf {hasAltitude}
                    consume(TrackPoint(id="",coordinate=coordinate,time=time,accuracy=0.0,altitude=altitude));remaining--
                }
                check(input.read()==-1) {"KML scratch session changed"}
            }
        }
        count=0;first=null;last=null
    }
    override fun close() {try {output?.close()} finally {file.delete()}}
}
