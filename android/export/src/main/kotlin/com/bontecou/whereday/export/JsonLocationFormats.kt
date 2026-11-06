package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import java.math.BigDecimal
import java.math.RoundingMode

internal object JsonLocationFormats {
    fun geoVisit(visit:Visit,options:ExportOptions)=HistoryExporter.feature(visit.coordinate,linkedMapOf<String,Any>(
        "kind" to "visit","arrivedAt" to XmlExportValues.time.format(visit.arrivedAt)
    ).apply {
        visit.departedAt?.let {
            put("departedAt",XmlExportValues.time.format(it))
            if(options.visitDuration) put("durationMinutes",(XmlExportValues.swiftDateSeconds(it)-XmlExportValues.swiftDateSeconds(visit.arrivedAt))/60.0)
        }
        if(options.visitName) visit.name?.let {put("locationName",it)}
        if(options.visitAddress) visit.address?.let {put("address",it)}
        if(options.visitNotes) visit.notes?.let {put("notes",it)}
        putAll(VisitAudit.values(visit,options))
    },null)
    fun geoPoint(point:TrackPoint,options:ExportOptions)=HistoryExporter.feature(point.coordinate,linkedMapOf<String,Any>(
        "kind" to "point","timestamp" to XmlExportValues.time.format(point.time),
        "timestampUnix" to unixSeconds(point)
    ).apply {
        if(options.pointAltitude) point.altitude?.let {put("altitude",it)}
        if(options.pointSpeed) point.speed?.let {put("speed",it)}
        if(options.pointAccuracy) put("horizontalAccuracy",point.accuracy)
        if(options.pointOutlier) put("isOutlier",point.outlier)
    },point.altitude.takeIf {options.pointAltitude})
    fun ownTracks(point:TrackPoint,options:ExportOptions):Map<String,Any> = linkedMapOf<String,Any>(
        "_type" to "location","lat" to point.coordinate.latitude,"lon" to point.coordinate.longitude,
        "tst" to integer(unixSeconds(point)),"tid" to "IM"
    ).apply {
        if(options.pointAccuracy) put("acc",roundedInteger(point.accuracy))
        if(options.pointAltitude) point.altitude?.let {put("alt",roundedInteger(it))}
        if(options.pointSpeed) point.speed?.let {put("vel",roundedInteger(it*3.6))}
    }
    fun overland(point:TrackPoint,options:ExportOptions)=HistoryExporter.feature(point.coordinate,linkedMapOf<String,Any>(
        "timestamp" to XmlExportValues.time.format(point.time),"device_id" to "isome"
    ).apply {
        if(options.pointAltitude) point.altitude?.let {put("altitude",it)}
        if(options.pointSpeed) point.speed?.let {put("speed",it)}
        if(options.pointAccuracy) put("horizontal_accuracy",point.accuracy)
    },null)
    private fun unixSeconds(point:TrackPoint)=XmlExportValues.swiftDateSeconds(point.time)+978_307_200.0
    private fun roundedInteger(value:Double):Long {
        require(value.isFinite()) {"OwnTracks requires finite measurements"}
        return BigDecimal(value).setScale(0,RoundingMode.HALF_UP).longValueExact()
    }
    private fun integer(value:Double):Long {
        require(value.isFinite() && value>=-9_223_372_036_854_775_808.0 && value<9_223_372_036_854_775_808.0) {"OwnTracks integer is outside the supported range"}
        return value.toLong() // Swift Int truncates toward zero, including timestamps before 1970.
    }
}
