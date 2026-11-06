package com.bontecou.whereday.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.text.Collator
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.floor

/** Provider records are untrusted: unusable coordinates/labels never become selectable places. */
data class PlaceLookupRecord(val name:String?,val address:String?,val latitude:Double?,val longitude:Double?)
data class PlaceLookupResult(val id:String,val name:String,val address:String?,val coordinate:Coordinate)
data class NearbyPlaceSuggestion(val id:String,val name:String,val address:String?,val coordinate:Coordinate,val distanceMeters:Double)
sealed interface NearbyPlaceQuery {
    data object Businesses:NearbyPlaceQuery
    data object AllPlaces:NearbyPlaceQuery
    data class Text(val value:String):NearbyPlaceQuery
}
data class PlaceLocationSelection(val coordinate:Coordinate,val replaceMetadata:Boolean=false,val name:String?=null,val address:String?=null,val placeSource:String="userEntered")
interface PlaceLookupProvider {
    suspend fun search(query:String,language:String):List<PlaceLookupRecord>
    suspend fun reverse(coordinate:Coordinate,language:String):List<PlaceLookupRecord>
    suspend fun nearby(coordinate:Coordinate,radiusMeters:Double,language:String,query:NearbyPlaceQuery):List<PlaceLookupRecord> = throw NearbyPlaceLookupUnavailable()
}
class PlaceLookupDisabled:IllegalStateException("Network geocoding is disabled. Enable it in Settings to search, or choose coordinates offline.")
class NearbyPlaceLookupUnavailable:IllegalStateException("Nearby business search is not configured in this build.")
private const val maximumNearbySearchRadiusMeters=2000.0 // MKLocalPointsOfInterestRequest.maxRadius in the reference SDK.

private fun placeLookupKey(text:String)=Normalizer.normalize(text.lowercase(Locale.ROOT),Normalizer.Form.NFC)

fun placeLookupResults(records:List<PlaceLookupRecord>,limit:Int=10):List<PlaceLookupResult> {
    require(limit in 1..10)
    val seen=HashSet<String>()
    return records.asSequence().mapNotNull {record->
        val coordinate=try {Coordinate(record.latitude ?: return@mapNotNull null,record.longitude ?: return@mapNotNull null)} catch(_:IllegalArgumentException) {return@mapNotNull null}
        val address=record.address?.trim()?.takeIf {it.isNotEmpty()}?.take(4096)
        val name=record.name?.trim()?.takeIf {it.isNotEmpty()}?.take(4096) ?: address ?: return@mapNotNull null
        fun quantize(value:Double):Long=(floor(abs(value)*100_000+0.5)*if(value<0) -1 else 1).toLong()
        val id="${placeLookupKey(name)}-${quantize(coordinate.latitude)}-${quantize(coordinate.longitude)}"
        if(!seen.add(id)) null else PlaceLookupResult(id,name,address,coordinate)
    }.take(limit).toList()
}

/** Matches the iOS nearby-list rules, including first eligible name winning before distance sort. */
fun nearbyPlaceSuggestions(records:List<PlaceLookupRecord>,origin:Coordinate,radiusMeters:Double=1000.0,limit:Int=8,language:String=Locale.getDefault().toLanguageTag(),measureDistance:(Coordinate,Coordinate)->Double={a,b->a.distanceTo(b)}):List<NearbyPlaceSuggestion> {
    require(radiusMeters.isFinite() && radiusMeters>0)
    require(limit in 1..50)
    val seen=HashSet<String>()
    val collator=Collator.getInstance(Locale.forLanguageTag(language)).apply {strength=Collator.SECONDARY;decomposition=Collator.CANONICAL_DECOMPOSITION}
    return records.mapNotNull {record->
        val name=record.name?.trim()?.takeIf {it.isNotEmpty()}?.take(4096) ?: return@mapNotNull null
        val key=placeLookupKey(name)
        if(key in setOf("none","unknown","current location") || key in seen) return@mapNotNull null
        val coordinate=try {Coordinate(record.latitude ?: return@mapNotNull null,record.longitude ?: return@mapNotNull null)} catch(_:IllegalArgumentException) {return@mapNotNull null}
        val distance=measureDistance(origin,coordinate)
        if(!distance.isFinite() || distance<0 || distance>radiusMeters*1.2) return@mapNotNull null
        seen.add(key)
        fun quantize(value:Double):Long=(floor(abs(value)*100_000+0.5)*if(value<0) -1 else 1).toLong()
        NearbyPlaceSuggestion("$key-${quantize(coordinate.latitude)}-${quantize(coordinate.longitude)}",name,
            record.address?.trim()?.takeIf {it.isNotEmpty()}?.take(4096),coordinate,distance)
    }.sortedWith {a,b->a.distanceMeters.compareTo(b.distanceMeters).takeIf {it!=0} ?: collator.compare(a.name,b.name)}.take(limit)
}

/** Filters the loaded page only; typing never submits another provider request. */
fun filterNearbyPlaceSuggestions(suggestions:List<NearbyPlaceSuggestion>,query:String,language:String):List<NearbyPlaceSuggestion> {
    val locale=Locale.forLanguageTag(language)
    fun normalized(value:String)=Normalizer.normalize(value,Normalizer.Form.NFC).lowercase(locale)
    val text=normalized(query.trim())
    if(text.isEmpty()) return suggestions
    return suggestions.filter {text in normalized(it.name) || it.address?.let {address->text in normalized(address)}==true}
}

/** A request observes opt-out until completion, including cached results. No history storage is involved. */
class PlaceLookupService(
    private val provider:PlaceLookupProvider,
    private val networkAllowed:Flow<Boolean>,
    private val language:()->String,
    private val elapsedMillis:()->Long={System.nanoTime()/1_000_000},
    private val timeoutMillis:Long=15_000,
    private val maximumCacheEntries:Int=64,
    private val cacheLifetimeMillis:Long=86_400_000,
    private val minimumRequestIntervalMillis:Long=500
) {
    init {require(timeoutMillis>0 && maximumCacheEntries in 1..256 && cacheLifetimeMillis>0 && minimumRequestIntervalMillis>=0)}
    private data class Entry(val created:Long,val results:List<PlaceLookupResult>)
    private enum class Kind {search,reverse,nearby}
    private data class Key(val kind:Kind,val text:String?,val coordinate:Coordinate?,val language:String,val radiusMeters:Double?=null)
    private val cache=LinkedHashMap<Key,Entry>(16,0.75f,true)
    private val mutex=Mutex()
    private var lastRequestMillis:Long?=null
    private val requestLock=Any()
    private val requests=HashSet<Job>()
    suspend fun search(query:String):List<PlaceLookupResult> {
        val trimmed=query.trim()
        require(trimmed.length<=256) {"Search with 256 characters or fewer."}
        if(trimmed.codePointCount(0,trimmed.length)<2) return emptyList()
        val lang=language()
        return request(Key(Kind.search,placeLookupKey(trimmed),null,lang)) {placeLookupResults(provider.search(trimmed,lang))}
    }
    suspend fun reverse(coordinate:Coordinate):PlaceLookupResult? {
        val lang=language()
        // Reverse results name the selected pin; a provider's nearby address centroid must not move it.
        return request(Key(Kind.reverse,null,coordinate,lang)) {
            placeLookupResults(provider.reverse(coordinate,lang).map {it.copy(latitude=coordinate.latitude,longitude=coordinate.longitude)},1)
        }.firstOrNull()
    }
    suspend fun nearby(coordinate:Coordinate,radiusMeters:Double=1000.0,limit:Int=8):List<NearbyPlaceSuggestion> {
        require(radiusMeters.isFinite() && radiusMeters>0)
        require(limit in 1..50)
        val radius=radiusMeters.coerceAtMost(maximumNearbySearchRadiusMeters)
        val lang=language()
        // Cache the full supported result page, independently of the caller's display limit.
        return request(Key(Kind.nearby,null,coordinate,lang,radius)) {
            suspend fun load(query:NearbyPlaceQuery):List<PlaceLookupRecord> {
                currentCoroutineContext().ensureActive()
                return provider.nearby(coordinate,radius,lang,query).also {currentCoroutineContext().ensureActive()}
            }
            fun normalize(records:List<PlaceLookupRecord>)=nearbyPlaceSuggestions(records,coordinate,radius,50,lang)
            var found=normalize(load(NearbyPlaceQuery.Businesses))
            if(found.isEmpty()) found=normalize(load(NearbyPlaceQuery.AllPlaces))
            if(found.isEmpty()) {
                val fallback=ArrayList<PlaceLookupRecord>()
                for(query in listOf("restaurant","coffee","bar","hotel","shop")) {
                    currentCoroutineContext().ensureActive()
                    fallback.addAll(load(NearbyPlaceQuery.Text(query)))
                }
                found=normalize(fallback)
            }
            found.map {PlaceLookupResult(it.id,it.name,it.address,it.coordinate)}
        }.take(limit).map {NearbyPlaceSuggestion(it.id,it.name,it.address,it.coordinate,coordinate.distanceTo(it.coordinate))}
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun request(key:Key,load:suspend ()->List<PlaceLookupResult>):List<PlaceLookupResult> = withTimeout(timeoutMillis) {
        val requestJob=currentCoroutineContext().job
        synchronized(requestLock) {requests.add(requestJob)}
        try {networkAllowed.distinctUntilChanged().flatMapLatest {allowed->flow {
            if(!allowed) throw PlaceLookupDisabled()
            emit(mutex.withLock {
                currentCoroutineContext().ensureActive()
                val now=elapsedMillis()
                val cached=cache[key]?.takeIf {now>=it.created && now-it.created<cacheLifetimeMillis}
                if(cached!=null) cached.results else {
                    cache.remove(key)
                    lastRequestMillis?.let {last->
                        val elapsed=elapsedMillis()-last
                        if(elapsed in 0 until minimumRequestIntervalMillis) delay(minimumRequestIntervalMillis-elapsed)
                    }
                    currentCoroutineContext().ensureActive()
                    lastRequestMillis=elapsedMillis()
                    val result=load()
                    currentCoroutineContext().ensureActive()
                    cache[key]=Entry(elapsedMillis(),result)
                    while(cache.size>maximumCacheEntries) cache.remove(cache.keys.first())
                    result
                }
            })
        }}.first()} finally {synchronized(requestLock) {requests.remove(requestJob)}}
    }
    suspend fun clearCache() {
        synchronized(requestLock) {requests.toList().forEach {it.cancel(CancellationException("Place lookup cache cleared"))}}
        mutex.withLock {cache.clear();lastRequestMillis=null}
    }
}
