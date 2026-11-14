package com.bontecou.whereday.domain

import java.util.TreeMap
import kotlin.math.*

enum class SavedPlaceDuplicatePolicy { keepExisting,updateExisting }
enum class SavedPlaceImportAction { add,update,skip }
data class SavedPlaceImportEntry(val place:SavedPlace,val targetId:String,val action:SavedPlaceImportAction)
data class SavedPlaceImportSummary(val added:Int=0,val updated:Int=0,val skipped:Int=0)
data class SavedPlaceImportPlan(val entries:List<SavedPlaceImportEntry>,val summary:SavedPlaceImportSummary) {
    companion object {
        /** Inject platform ellipsoidal distance and Unicode case folding; preview and persistence use this exact plan. */
        fun build(incoming:List<SavedPlace>,existing:List<SavedPlace>,policy:SavedPlaceDuplicatePolicy,
            distance:(Coordinate,Coordinate)->Double,nameKey:(String)->String,checkpoint:()->Unit={}):SavedPlaceImportPlan {
            val groups=mutableMapOf<String,Index>()
            existing.forEach {checkpoint();groups.getOrPut(nameKey(it.name.trim())) {Index(distance)}.insert(it)}
            var added=0;var updated=0;var skipped=0
            val entries=incoming.map {raw ->
                checkpoint()
                val place=raw.copy(name=raw.name.trim(),address=raw.address?.trim()?.ifEmpty {null})
                val index=groups.getOrPut(nameKey(place.name)) {Index(distance)}
                val match=index.nearest(place)
                if(match==null) {added++;index.insert(place);SavedPlaceImportEntry(place,place.id,SavedPlaceImportAction.add)}
                else if(policy==SavedPlaceDuplicatePolicy.keepExisting) {skipped++;SavedPlaceImportEntry(place,match.id,SavedPlaceImportAction.skip)}
                else {updated++;index.insert(place.copy(id=match.id));SavedPlaceImportEntry(place,match.id,SavedPlaceImportAction.update)}
            }
            return SavedPlaceImportPlan(entries,SavedPlaceImportSummary(added,updated,skipped))
        }
    }
    private class Index(private val distance:(Coordinate,Coordinate)->Double) {
        private val buckets=TreeMap<Int,MutableMap<String,SavedPlace>>()
        private val bucketById=mutableMapOf<String,Int>()
        private var maximumRadius=0.0
        private fun bucket(latitude:Double)=floor((latitude+90)/0.1).toInt()
        fun insert(place:SavedPlace) {
            bucketById.remove(place.id)?.let {old ->buckets[old]?.remove(place.id);if(buckets[old]?.isEmpty()==true) buckets.remove(old)}
            maximumRadius=max(maximumRadius,place.radius)
            val next=bucket(place.coordinate.latitude);bucketById[place.id]=next;buckets.getOrPut(next) {mutableMapOf()}[place.id]=place
        }
        fun nearest(place:SavedPlace):SavedPlace? {
            val coordinate=place.coordinate;val radius=max(place.radius,maximumRadius)
            val latitudeDelta=min(180.0,radius/6_300_000*180/PI)
            val extreme=min(90.0,abs(coordinate.latitude)+latitudeDelta)
            val longitudeDelta=if(extreme>=90) 180.0 else min(180.0,latitudeDelta/cos(extreme*PI/180))
            var best:SavedPlace?=null;var bestDistance=Double.POSITIVE_INFINITY
            for(group in buckets.subMap(bucket(max(-90.0,coordinate.latitude-latitudeDelta)),true,bucket(min(90.0,coordinate.latitude+latitudeDelta)),true).values) {
                for(candidate in group.values) {
                    if(abs(coordinate.latitude-candidate.coordinate.latitude)>latitudeDelta) continue
                    val longitude=abs(coordinate.longitude-candidate.coordinate.longitude)
                    if(min(longitude,360-longitude)>longitudeDelta) continue
                    val meters=distance(coordinate,candidate.coordinate)
                    if(meters>max(place.radius,candidate.radius) || !meters.isFinite()) continue
                    if(meters<bestDistance || meters==bestDistance && (best==null || candidate.id.uppercase()<best.id.uppercase())) {best=candidate;bestDistance=meters}
                }
            }
            return best
        }
    }
}
