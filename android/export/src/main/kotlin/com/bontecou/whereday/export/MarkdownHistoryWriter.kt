package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import java.io.*
import java.time.*
import java.time.format.*
import java.util.Locale

/** Shared display contract. Markdown intentionally has the same display precision as iOS. */
internal class MarkdownHistoryWriter(private val out:Writer,private val options:ExportOptions,private val generatedAt:Instant,private val locale:Locale=Locale.getDefault()) {
    private val calendar=HistoryCalendarFormats(locale,options.zone)
    private fun date(at:Instant,style:FormatStyle)=calendar.date(at,style)
    private fun time(at:Instant,style:FormatStyle)=calendar.time(at,style)
    private fun dateTime(at:Instant,dateStyle:FormatStyle,timeStyle:FormatStyle)=calendar.dateTime(at,dateStyle,timeStyle)
    fun day(at:Instant)=at.atZone(options.zone).toLocalDate()
    fun combined(visits:Long,points:Long) {out.append("# Whereday Complete Export\n\n");exportDate();out.append("**Total Visits:** $visits\n\n**Total Location Points:** $points\n\n---\n\n")}
    private fun exportDate() {out.append("**Export Date:** ${dateTime(generatedAt,FormatStyle.LONG,FormatStyle.SHORT)}\n\n")}
    fun visits(count:Long) {out.append(if(options.kind==DataKind.all) "# Visits\n\n" else "# Whereday Export\n\n");exportDate();out.append("**Total Visits:** $count\n\n---\n\n")}
    fun between() {out.append("\n---\n\n")}
    fun points(count:Long,first:Instant?,last:Instant?) {
        out.append(if(options.kind==DataKind.all) "# Location Points\n\n" else "# Whereday Location Points Export\n\n");exportDate();out.append("**Total Points:** $count\n\n")
        if(first!=null && last!=null) {
            val seconds=(XmlExportValues.swiftDateSeconds(last)-XmlExportValues.swiftDateSeconds(first)).toLong()
            out.append("**Time Range:** ${dateTime(first,FormatStyle.MEDIUM,FormatStyle.MEDIUM)} → ${dateTime(last,FormatStyle.MEDIUM,FormatStyle.MEDIUM)}\n\n**Duration:** ${seconds/3600}h ${(seconds%3600)/60}m\n\n")
        }
        out.append("---\n\n")
    }
    fun visitDay(at:Instant) {dayHeader(at,visitHeaders())}
    fun pointDay(at:Instant) {dayHeader(at,pointHeaders())}
    private fun dayHeader(at:Instant,headers:List<String>) {out.append("## ${date(at,FormatStyle.FULL)}\n\n");row(headers);out.append("|"+headers.joinToString("|") {"------"}+"|\n")}
    fun endDay() {out.append('\n')}
    private fun row(cells:List<String>) {out.append("| ").append(cells.joinToString(" | ")).append(" |\n")}
    private fun visitHeaders()=listOf("Arrived","Departed")+(if(options.visitDuration) listOf("Duration") else emptyList())+(if(options.visitCoordinates) listOf("Lat","Lon") else emptyList())+(if(options.visitName) listOf("Location") else emptyList())+(if(options.visitAddress) listOf("Address") else emptyList())+(if(options.visitNotes) listOf("Notes") else emptyList())+listOf("Source","Status","Visit Metadata")
    private fun pointHeaders()=listOf("Time","Lat","Lon")+(if(options.pointSpeed) listOf("Speed") else emptyList())+(if(options.pointAltitude) listOf("Altitude") else emptyList())+(if(options.pointAccuracy) listOf("Accuracy") else emptyList())+(if(options.pointOutlier) listOf("Outlier") else emptyList())
    fun visit(v:Visit) {row(buildList {
        add(time(v.arrivedAt,FormatStyle.SHORT));add(v.departedAt?.let {time(it,FormatStyle.SHORT)} ?: "-")
        if(options.visitDuration) add(v.departedAt?.let {val minutes=((XmlExportValues.swiftDateSeconds(it)-XmlExportValues.swiftDateSeconds(v.arrivedAt))/60.0).toLong();if(minutes/60>0) "${minutes/60}h ${minutes%60}m" else "${minutes}m"} ?: "-")
        if(options.visitCoordinates) {add(XmlExportValues.number(v.coordinate.latitude,6));add(XmlExportValues.number(v.coordinate.longitude,6))}
        if(options.visitName) add(cell(v.name));if(options.visitAddress) add(cell(v.address));if(options.visitNotes) add(cell(v.notes))
        add(v.source.name);add(v.confirmation.name);add(cell(VisitAudit.json(v,options)))
    })}
    fun point(p:TrackPoint) {row(buildList {
        add(time(p.time,FormatStyle.MEDIUM));add(XmlExportValues.number(p.coordinate.latitude,6));add(XmlExportValues.number(p.coordinate.longitude,6))
        if(options.pointSpeed) add(p.speed?.let {XmlExportValues.number(it,1)+" m/s"} ?: "-")
        if(options.pointAltitude) add(p.altitude?.let {XmlExportValues.number(it,1)+" m"} ?: "-")
        if(options.pointAccuracy) add(XmlExportValues.number(p.accuracy,1)+" m")
        if(options.pointOutlier) add(if(p.outlier) "yes" else "-")
    })}
    companion object {
        fun cell(value:String?):String=if(value.isNullOrEmpty()) "-" else value.replace("\\","\\\\").replace("|","\\|").replace("\r\n"," ").replace("\n","<br>").replace("\r"," ")
        fun write(out:Writer,visits:List<Visit>,points:List<TrackPoint>,options:ExportOptions,generatedAt:Instant,locale:Locale=Locale.getDefault()) {
            val writer=MarkdownHistoryWriter(out,options,generatedAt,locale)
            if(options.kind==DataKind.all) writer.combined(visits.size.toLong(),points.size.toLong())
            if(options.kind!=DataKind.points) {
                writer.visits(visits.size.toLong())
                visits.groupBy {writer.day(it.arrivedAt)}.toSortedMap(reverseOrder()).values.forEach {day->writer.visitDay(day.first().arrivedAt);day.sortedBy {it.arrivedAt}.forEach(writer::visit);writer.endDay()}
            }
            if(options.kind==DataKind.all) writer.between()
            if(options.kind!=DataKind.visits) {
                val ordered=points.sortedBy {it.time};writer.points(ordered.size.toLong(),ordered.firstOrNull()?.time,ordered.lastOrNull()?.time)
                ordered.groupBy {writer.day(it.time)}.toSortedMap().values.forEach {day->writer.pointDay(day.first().time);day.forEach(writer::point);writer.endDay()}
            }
        }
    }
}

/** Bounded day blocks with a trailing length. Reverse replay preserves ascending rows within each day. */
internal class MarkdownDaySpool(directory:File?,private val checkpoint:()->Unit):AutoCloseable {
    private val file=File.createTempFile("whereday-markdown-",".tmp",directory)
    private val data=RandomAccessFile(file,"rw")
    private var start=0L
    val writer:Writer=object:OutputStream() {
        override fun write(value:Int) {checkpoint();data.write(value)}
        override fun write(bytes:ByteArray,offset:Int,length:Int) {checkpoint();data.write(bytes,offset,length)}
    }.writer(Charsets.UTF_8).buffered()
    fun endDay() {writer.flush();data.writeLong(data.filePointer-start);start=data.filePointer}
    fun replay(out:Writer) {
        writer.flush()
        fun copy(start:Long,length:Long) {
            checkpoint();data.seek(start)
            // A bounded byte stream prevents UTF-8 decoding from crossing the binary footer.
            val input=object:InputStream() {var remaining=length
                override fun read():Int {if(remaining==0L) return -1;remaining--;return data.readUnsignedByte()}
                override fun read(bytes:ByteArray,offset:Int,size:Int):Int {if(remaining==0L) return -1;val n=minOf(size.toLong(),remaining).toInt();data.readFully(bytes,offset,n);remaining-=n;return n}
            }
            input.reader(Charsets.UTF_8).use {reader->val chars=CharArray(8192);while(true) {checkpoint();val n=reader.read(chars);if(n<0) break;out.write(chars,0,n)}}
        }
        var end=data.length()
        while(end>0) {checkpoint();data.seek(end-8);val size=data.readLong();check(size>=0 && size<=end-8);val begin=end-8-size;copy(begin,size);end=begin}
    }

    override fun close() {try {try {writer.close()} finally {data.close()}} finally {file.delete()}}
}
