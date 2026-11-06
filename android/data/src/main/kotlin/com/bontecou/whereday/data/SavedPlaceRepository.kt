package com.bontecou.whereday.data

import android.icu.lang.UCharacter
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import java.text.Normalizer
import java.util.Locale

class SavedPlacesChanged:IllegalStateException("Saved places changed. Review the updated list before saving or importing.")

class SavedPlaceRepository(private val database:HistoryDatabase) {
    fun nameKey(value:String):String {
        val turkic=Locale.getDefault().language in setOf("tr","az")
        return Normalizer.normalize(UCharacter.foldCase(value.trim(),!turkic),Normalizer.Form.NFC)
    }
    fun distance(a:Coordinate,b:Coordinate):Double = a.distanceTo(b)
    suspend fun previewReuse(candidate:SavedPlace,existing:List<SavedPlace>):SavedPlaceReusePlan=withContext(Dispatchers.Default) {
        val coroutine=currentCoroutineContext()
        SavedPlaceReusePlan.build(candidate,existing,::distance,::nameKey) {coroutine.ensureActive()}
    }
    suspend fun preview(incoming:List<SavedPlace>,existing:List<SavedPlace>,policy:SavedPlaceDuplicatePolicy):SavedPlaceImportPlan=withContext(Dispatchers.Default) {
        val coroutine=currentCoroutineContext()
        SavedPlaceImportPlan.build(incoming,existing,policy,::distance,::nameKey) {coroutine.ensureActive()}
    }
    suspend fun import(incoming:List<SavedPlace>,expected:List<SavedPlace>,policy:SavedPlaceDuplicatePolicy):SavedPlaceImportSummary=withContext(Dispatchers.IO) {
        require(incoming.isNotEmpty()) {"There are no valid saved places to import."}
        require(incoming.map {it.id}.distinct().size==incoming.size) {"Import rows must have distinct identities."}
        database.writeTransaction {
            val existing=database.history().places().map {it.value}
            if(existing.associateBy {it.id}!=expected.associateBy {it.id}) throw SavedPlacesChanged()
            val coroutine=currentCoroutineContext()
            val plan=SavedPlaceImportPlan.build(incoming,existing,policy,::distance,::nameKey) {coroutine.ensureActive()}
            for(entry in plan.entries) {
                coroutine.ensureActive()
                if(entry.action==SavedPlaceImportAction.add && database.history().place(entry.targetId)!=null) throw SavedPlacesChanged()
                if(entry.action!=SavedPlaceImportAction.skip) database.history().put(PlaceRow(entry.place.copy(id=entry.targetId)))
            }
            coroutine.ensureActive();plan.summary
        }
    }
    suspend fun save(place:SavedPlace,expected:SavedPlace?)=withContext(Dispatchers.IO) {
        database.writeTransaction {
            val normalized=place.copy(name=place.name.trim(),address=place.address?.trim()?.ifEmpty {null})
            val existing=database.history().place(place.id)?.value
            if(existing!=normalized) {
                if(existing!=expected) throw SavedPlacesChanged()
                database.history().put(PlaceRow(normalized))
            }
        }
    }
    suspend fun delete(expected:SavedPlace)=withContext(Dispatchers.IO) {
        database.writeTransaction {
            if(database.history().place(expected.id)?.value!=expected) throw SavedPlacesChanged()
            database.history().deletePlace(expected.id)
        }
    }
}
