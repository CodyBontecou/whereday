package com.bontecou.whereday.domain

/** Remember a reviewed existing visit without moving a user-managed saved pin or changing its radius. */
fun confirmedPlaceToRemember(
    visit:Visit,existing:List<SavedPlace>,newId:String,
    distance:(Coordinate,Coordinate)->Double,nameKey:(String)->String,checkpoint:()->Unit={}
):SavedPlace? {
    if(visit.confirmation==Confirmation.unconfirmed) return null
    val name=visit.name?.trim()?.takeIf {it.isNotEmpty()} ?: visit.address?.trim()?.takeIf {it.isNotEmpty()} ?: return null
    val candidate=SavedPlace(newId,name,visit.coordinate,visit.address?.trim()?.takeIf {it.isNotEmpty()},150.0)
    val key=nameKey(name)
    for(place in existing) {
        checkpoint()
        if(nameKey(place.name)==key && distance(place.coordinate,candidate.coordinate)<=maxOf(place.radius,candidate.radius)) return null
    }
    return candidate
}
