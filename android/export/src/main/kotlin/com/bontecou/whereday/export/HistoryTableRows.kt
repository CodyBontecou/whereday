package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import java.time.Instant

/** Stable CSV syntax and numeric fields from the current Swift history renderers. */
internal object HistoryTableRows {
    fun visitHeader(o:ExportOptions)=buildList {
        addAll(listOf("arrived_at","departed_at"));if(o.visitDuration) add("duration_minutes")
        if(o.visitCoordinates) addAll(listOf("latitude","longitude"));if(o.visitName) add("location_name")
        if(o.visitAddress) add("address");if(o.visitNotes) add("notes");addAll(listOf("source","confirmation_status"))
        if(o.visitName) add("original_location_name");if(o.visitAddress) add("original_address");add("visit_metadata")
    }
    fun visit(v:Visit,o:ExportOptions):List<Any?> = buildList {
        add(XmlExportValues.time.format(v.arrivedAt));add(v.departedAt?.let(XmlExportValues.time::format))
        if(o.visitDuration) add(v.departedAt?.let {XmlExportValues.number((XmlExportValues.swiftDateSeconds(it)-XmlExportValues.swiftDateSeconds(v.arrivedAt))/60.0,1)})
        if(o.visitCoordinates) addAll(listOf(XmlVisitAudit.swiftNumber(v.coordinate.latitude),XmlVisitAudit.swiftNumber(v.coordinate.longitude)))
        if(o.visitName) add(v.name);if(o.visitAddress) add(v.address);if(o.visitNotes) add(v.notes)
        addAll(listOf(v.source.name,v.confirmation.name));if(o.visitName) add(v.originalName);if(o.visitAddress) add(v.originalAddress)
        add(VisitAudit.json(v,o))
    }
    fun pointHeader(o:ExportOptions)=buildList {
        addAll(listOf("timestamp","timestamp_unix","latitude","longitude"))
        if(o.pointAltitude) add("altitude");if(o.pointSpeed) add("speed");if(o.pointAccuracy) add("horizontal_accuracy");if(o.pointOutlier) add("is_outlier")
    }
    fun point(p:TrackPoint,o:ExportOptions):List<Any?> = buildList {
        add(XmlExportValues.time.format(p.time));add(XmlExportValues.number(XmlExportValues.unixSeconds(p.time),3))
        add(XmlVisitAudit.swiftNumber(p.coordinate.latitude));add(XmlVisitAudit.swiftNumber(p.coordinate.longitude))
        if(o.pointAltitude) add(p.altitude?.let {XmlExportValues.number(it)});if(o.pointSpeed) add(p.speed?.let {XmlExportValues.number(it)})
        if(o.pointAccuracy) add(XmlVisitAudit.swiftNumber(p.accuracy));if(o.pointOutlier) add(p.outlier)
    }
    fun dateRange(first:Instant,last:Instant):Map<String,Any> = linkedMapOf("earliest" to XmlExportValues.time.format(first),"latest" to XmlExportValues.time.format(last),"durationSeconds" to XmlExportValues.swiftDateSeconds(last)-XmlExportValues.swiftDateSeconds(first))
}
