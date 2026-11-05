package com.bontecou.whereday.domain

import java.time.Instant

enum class ManualVisitMode { past, current }
enum class VisitDraftProblem(val description:String) {
    identity("The visit identity changed."),coordinate("Choose a location or enter valid coordinates."),
    placeName("Enter a place name."),departure("Past visits need a departure time."),
    times("Enter past arrival and departure times. Departure must follow arrival."),
}
class InvalidVisitDraft(val problem:VisitDraftProblem):IllegalArgumentException(problem.description)

/** A past visit always has a departure; current-place capture can remain open. */
data class VisitDraft(
    val id:String,val name:String,val address:String,val notes:String,val coordinate:Coordinate?,
    val arrivedAt:Instant,val departedAt:Instant?,val placeSource:String="userEntered",
    val placeDistanceMeters:Double?=null,val placeSelectionChanged:Boolean=false
) {
    init {require(placeDistanceMeters==null || placeDistanceMeters.isFinite() && placeDistanceMeters>=0)}

    fun selectNearby(suggestion:NearbyPlaceSuggestion,providerSource:String):VisitDraft = copy(
        coordinate=suggestion.coordinate,name=suggestion.name,address=suggestion.address.orEmpty(),
        placeSource=providerSource,placeDistanceMeters=suggestion.distanceMeters,placeSelectionChanged=true)

    fun selectLocation(selection:PlaceLocationSelection):VisitDraft {
        if(!selection.replaceMetadata) return if(selection.coordinate==coordinate) this else copy(coordinate=selection.coordinate,placeSource="userEntered",placeDistanceMeters=null,placeSelectionChanged=false)
        val pickedName=selection.name?.takeIf {it.isNotBlank()}
        val pickedAddress=selection.address?.takeIf {it.isNotBlank()}
        val fallbackName=if(coordinate==null) name else ""
        val fallbackAddress=if(coordinate==null) address else ""
        val usedFallback=(pickedName==null && fallbackName.isNotBlank()) || (pickedAddress==null && fallbackAddress.isNotBlank())
        return copy(coordinate=selection.coordinate,name=pickedName ?: fallbackName,address=pickedAddress ?: fallbackAddress,
            placeSource=if(usedFallback) "userEntered" else selection.placeSource,placeDistanceMeters=null,placeSelectionChanged=false)
    }

    fun changesPlace(original:Visit):Boolean = placeSelectionChanged || coordinate!=original.coordinate ||
        name.trim().ifEmpty {null}!=original.name || address.trim().ifEmpty {null}!=original.address

    fun remembersPlace(original:Visit?,confirm:Boolean):Boolean = original!=null &&
        (changesPlace(original) || confirm && original.confirmation==Confirmation.unconfirmed)

    fun build(original:Visit?,now:Instant,confirm:Boolean=false,mode:ManualVisitMode=ManualVisitMode.past):Visit {
        if(original!=null && original.id!=id) throw InvalidVisitDraft(VisitDraftProblem.identity)
        val location=coordinate ?: throw InvalidVisitDraft(VisitDraftProblem.coordinate)
        val normalizedName=name.trim().ifEmpty {null};val normalizedAddress=address.trim().ifEmpty {null}
        if(original==null && normalizedName==null) throw InvalidVisitDraft(VisitDraftProblem.placeName)
        if(original==null && mode!=ManualVisitMode.current && departedAt==null) throw InvalidVisitDraft(VisitDraftProblem.departure)
        if(!(arrivedAt<=now && (departedAt==null || departedAt in arrivedAt..now))) throw InvalidVisitDraft(VisitDraftProblem.times)
        if(original==null) return Visit(id,location,arrivedAt,departedAt,normalizedName,normalizedAddress,notes.ifBlank {null},
            VisitSource.manual,Confirmation.confirmed,confirmedAt=now,updatedAt=now,placeSource=placeSource,placeDistanceMeters=placeDistanceMeters)
        val placeChanged=changesPlace(original)
        val base=if(placeChanged) original.correct(location,normalizedName,normalizedAddress,now).copy(placeSource=placeSource,placeDistanceMeters=placeDistanceMeters)
            else if(confirm && original.confirmation==Confirmation.unconfirmed) original.copy(confirmation=Confirmation.confirmed,confirmedAt=now)
            else original
        return base.copy(arrivedAt=arrivedAt,departedAt=departedAt,notes=notes.ifBlank {null},updatedAt=now,
            departureInferred=if(departedAt!=original.departedAt) false else original.departureInferred)
    }
}
