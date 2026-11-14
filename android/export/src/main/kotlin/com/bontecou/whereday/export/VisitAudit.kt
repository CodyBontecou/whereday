package com.bontecou.whereday.export

import com.bontecou.whereday.domain.Visit

/** Typed audit fields, ordered and filtered using the current Swift export contract. */
internal object VisitAudit {
    fun values(visit:Visit,options:ExportOptions):Map<String,Any> = linkedMapOf<String,Any>().apply {
        put("source",visit.source.name);put("confirmationStatus",visit.confirmation.name)
        visit.confirmedAt?.let {put("confirmedAt",XmlExportValues.time.format(it))}
        visit.updatedAt?.let {put("updatedAt",XmlExportValues.time.format(it))}
        if(options.visitCoordinates) visit.originalCoordinate?.let {put("originalLatitude",it.latitude);put("originalLongitude",it.longitude)}
        if(options.visitName) visit.originalName?.let {put("originalLocationName",it)}
        if(options.visitAddress) visit.originalAddress?.let {put("originalAddress",it)}
        if(options.visitCoordinates) visit.detectedCoordinate?.let {put("detectedLatitude",it.latitude);put("detectedLongitude",it.longitude)}
        if(options.visitName) visit.detectedName?.let {put("detectedLocationName",it)}
        if(options.visitAddress) visit.detectedAddress?.let {put("detectedAddress",it)}
        visit.placeSource?.let {put("placeSource",it)}
        visit.placeDistanceMeters?.let {put("placeDistanceMeters",it)}
    }
    fun json(visit:Visit,options:ExportOptions):String=XmlVisitAudit.fields(visit,options).joinToString(",","{","}") {(key,value)->quote(key)+":"+quote(value)}
    private fun quote(value:String):String=buildString {
        append('"')
        for(c in value) when(c) {
            '"'->append("\\\"")
            '\\'->append("\\\\")
            '<'->append("\\u003c")
            in '\u0000'..'\u001f'->append("\\u"+c.code.toString(16).padStart(4,'0'))
            else->append(c)
        }
        append('"')
    }

}
