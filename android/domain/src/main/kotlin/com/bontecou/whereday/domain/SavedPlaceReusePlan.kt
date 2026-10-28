package com.bontecou.whereday.domain

/** Manual-visit reuse follows the displayed library order, unlike CSV's nearest-match policy. */
data class SavedPlaceReusePlan(val value:SavedPlace,val original:SavedPlace?) {
    companion object {
        fun build(candidate:SavedPlace,existing:List<SavedPlace>,distance:(Coordinate,Coordinate)->Double,nameKey:(String)->String,checkpoint:()->Unit={}):SavedPlaceReusePlan {
            val normalized=candidate.copy(name=candidate.name.trim(),address=candidate.address?.trim()?.ifEmpty {null})
            val key=nameKey(normalized.name)
            val match=existing.firstOrNull {place->
                checkpoint()
                nameKey(place.name.trim())==key && distance(place.coordinate,normalized.coordinate)<=maxOf(place.radius,normalized.radius)
            }
            if(match==null) {
                require(existing.none {it.id==normalized.id}) {"The reusable place identity is already in use."}
                return SavedPlaceReusePlan(normalized,null)
            }
            return SavedPlaceReusePlan(normalized.copy(id=match.id,name=match.name),match)
        }
    }
}
data class SavedPlaceReuseSave(val candidate:SavedPlace,val existing:List<SavedPlace>)
