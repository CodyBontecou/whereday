package com.bontecou.whereday

import android.os.Bundle
import androidx.compose.runtime.saveable.Saver
import com.bontecou.whereday.domain.*
import java.time.Instant

/** Keep the original optimistic-lock baseline, not a newly fetched visit, across UI restoration. */
internal val VisitEditorVisitSaver=Saver<Visit?,Bundle>(save={visit->visit?.let {v->Bundle().apply {
    putInt("version",1)
    putString("id",v.id);putDoubleArray("coordinate",doubleArrayOf(v.coordinate.latitude,v.coordinate.longitude))
    putString("arrivedAt",v.arrivedAt.toString());putString("departedAt",v.departedAt?.toString())
    putString("name",v.name);putString("address",v.address);putString("notes",v.notes)
    putString("source",v.source.name);putString("confirmation",v.confirmation.name)
    v.originalCoordinate?.let {putDoubleArray("originalCoordinate",doubleArrayOf(it.latitude,it.longitude))}
    putString("originalName",v.originalName);putString("originalAddress",v.originalAddress)
    v.detectedCoordinate?.let {putDoubleArray("detectedCoordinate",doubleArrayOf(it.latitude,it.longitude))}
    putString("detectedName",v.detectedName);putString("detectedAddress",v.detectedAddress)
    putString("confirmedAt",v.confirmedAt?.toString());putString("updatedAt",v.updatedAt?.toString())
    putString("placeSource",v.placeSource);v.placeDistanceMeters?.let {putDouble("placeDistanceMeters",it)}
    putString("countryCode",v.countryCode);putString("timeZone",v.timeZone)
    putBoolean("departureInferred",v.departureInferred)
    putString("countryResolutionAttemptedAt",v.countryResolutionAttemptedAt?.toString())
}}},restore={b->runCatching {
    require(b.getInt("version")==1)
    fun coordinate(key:String):Coordinate?=b.getDoubleArray(key)?.let {require(it.size==2);Coordinate(it[0],it[1])}
    fun instant(key:String):Instant?=b.getString(key)?.let(Instant::parse)
    Visit(
        id=requireNotNull(b.getString("id")),coordinate=requireNotNull(coordinate("coordinate")),arrivedAt=requireNotNull(instant("arrivedAt")),departedAt=instant("departedAt"),
        name=b.getString("name"),address=b.getString("address"),notes=b.getString("notes"),
        source=VisitSource.valueOf(requireNotNull(b.getString("source"))),confirmation=Confirmation.valueOf(requireNotNull(b.getString("confirmation"))),
        originalCoordinate=coordinate("originalCoordinate"),originalName=b.getString("originalName"),originalAddress=b.getString("originalAddress"),
        detectedCoordinate=coordinate("detectedCoordinate"),detectedName=b.getString("detectedName"),detectedAddress=b.getString("detectedAddress"),
        confirmedAt=instant("confirmedAt"),updatedAt=instant("updatedAt"),placeSource=b.getString("placeSource"),
        placeDistanceMeters=if(b.containsKey("placeDistanceMeters")) b.getDouble("placeDistanceMeters") else null,
        countryCode=b.getString("countryCode"),timeZone=b.getString("timeZone"),departureInferred=b.getBoolean("departureInferred"),
        countryResolutionAttemptedAt=instant("countryResolutionAttemptedAt"),
    )
}.getOrNull()})
