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
                // CSV rows carry no kind or note, so an update keeps the matched place's own.
                val kept=existing.firstOrNull {it.id==entry.targetId}
                if(entry.action!=SavedPlaceImportAction.skip) database.history().put(PlaceRow(entry.place.copy(id=entry.targetId,kind=kept?.kind,notes=kept?.notes)))
            }
            coroutine.ensureActive();plan.summary
        }
    }
    suspend fun save(place:SavedPlace,expected:SavedPlace?)=withContext(Dispatchers.IO) {
        database.writeTransaction {
            val normalized=place.copy(name=place.name.trim(),address=place.address?.trim()?.ifEmpty {null},notes=place.notes?.takeIf {it.isNotBlank()})
            val existing=database.history().place(place.id)?.value
            if(existing!=normalized) {
                if(existing!=expected) throw SavedPlacesChanged()
                database.history().put(PlaceRow(normalized))
            }
        }
    }
    /** Recomputes a merge against the stored library; any change since [expected] was planned refuses it. */
    suspend fun mergePlan(kept:SavedPlace,removed:SavedPlace):SavedPlaceMergePlan=withContext(Dispatchers.IO) {
        database.readSnapshot {dao->SavedPlaceMergePlan.build(kept,removed,dao.places().map {it.value},dao.allVisits().map {it.value})}
    }
    /** Folds the plan's removed place into the kept one: its visits and name matches join the kept place,
     *  its kind, address and note fill gaps, and the removed place is deleted. */
    suspend fun merge(expected:SavedPlaceMergePlan,kept:SavedPlace,removed:SavedPlace,now:java.time.Instant=java.time.Instant.now())=withContext(Dispatchers.IO) {
        database.writeTransaction {
            val dao=database.history()
            val places=dao.places().map {it.value};val visits=dao.allVisits().map {it.value}
            if(places.firstOrNull {it.id==kept.id}!=kept || places.firstOrNull {it.id==removed.id}!=removed) throw SavedPlacesChanged()
            val plan=SavedPlaceMergePlan.build(kept,removed,places,visits)
            if(plan!=expected) throw SavedPlacesChanged()
            SavedPlaceMergePlan.renamed(plan,kept,visits,now).forEach {dao.put(VisitRow(it))}
            dao.put(PlaceRow(SavedPlaceMergePlan.merged(plan,kept,removed)))
            dao.deletePlace(removed.id)
        }
    }
    suspend fun delete(expected:SavedPlace)=withContext(Dispatchers.IO) {
        database.writeTransaction {
            if(database.history().place(expected.id)?.value!=expected) throw SavedPlacesChanged()
            database.history().deletePlace(expected.id)
        }
    }
}
