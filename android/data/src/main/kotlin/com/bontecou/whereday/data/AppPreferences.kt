package com.bontecou.whereday.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

/**
 * A settings file that can't be parsed would otherwise fail every read, and nearly every
 * screen reads settings, so the app would crash on each launch until its storage was
 * cleared (which also deletes the journal). It is replaced with fresh settings instead.
 */
internal val settingsCorruptionHandler=ReplaceFileCorruptionHandler {AppPreferences.recoveredPreferences()}
private val Context.preferenceStore by preferencesDataStore("settings",corruptionHandler=settingsCorruptionHandler)
data class AppSettings(val onboarded: Boolean = false, val appearance: String = "system", val metric: Boolean = true, val distanceFilter: Int = 5, val stopAfterHours: Int = 0, val networkGeocoding: Boolean = true, val exportFolder: String? = null,
    val inferredEnabled: Boolean=true,val inferredGapMinutes: Int=30,val inferredMinimumMinutes: Int=0,val inferredMinimumPoints: Int=1,val exportConfiguration:String?=null,val showOutliers:Boolean=false,val photoEpoch:String="",val showPhotoMarkers:Boolean=false,val showPhotoImages:Boolean=true,val requestPhotoLocations:Boolean=false,val automaticPhotoSync:Boolean=false,
    val roadDisplayEnabled:Boolean=false,val roadAccessRevision:String="",val showPhotoDots:Boolean=false,
    /** Set once this install first has verified access; the post-onboarding paywall never returns after that, even if a plan lapses. */
    val accessGateCleared:Boolean=false,
    /** What the map shows (the Layers sheet). Road matching stays [roadDisplayEnabled]; outliers stay [showOutliers]. */
    val map:MapLayerSettings=MapLayerSettings(),
    /** How route points are chosen: a [com.bontecou.whereday.domain.RouteSamplingMode] key. */
    val routeSamplingMode:String=com.bontecou.whereday.domain.RouteSamplingMode.distance.key,
    val routeSampleIntervalSeconds:Int=com.bontecou.whereday.domain.RouteSamplingPolicy.DEFAULT_INTERVAL_SECONDS) {
    val routeSampling get()=com.bontecou.whereday.domain.RouteSamplingPolicy(com.bontecou.whereday.domain.RouteSamplingMode.fromKey(routeSamplingMode),distanceFilter,
        routeSampleIntervalSeconds.takeIf {it in com.bontecou.whereday.domain.RouteSamplingPolicy.INTERVAL_OPTIONS} ?: com.bontecou.whereday.domain.RouteSamplingPolicy.DEFAULT_INTERVAL_SECONDS)
    val inferenceRules get()=com.bontecou.whereday.domain.InferenceRules(java.time.Duration.ofMinutes(inferredGapMinutes.toLong()),java.time.Duration.ofMinutes(inferredMinimumMinutes.toLong()),inferredMinimumPoints)
}
/** The map's persisted layer choices, mirroring iOS's @AppStorage keys where they exist. */
data class MapLayerSettings(val path:Boolean=true,
    /** GPS dots start hidden on the map-first home, as in the design; one switch brings them back. */
    val points:Boolean=false,val visits:Boolean=true,val endpoints:Boolean=true,
    /** iOS showStraightLinePathSegments: fallback straight gaps beside road-matched stretches. */
    val straightGaps:Boolean=false,
    /** The route of the recording that is running now. */
    val liveSession:Boolean=true,
    /** iOS showVisitSuggestions: the "Are you here?" card. */
    val visitSuggestions:Boolean=false,
    /** Street tiles from the online provider show by default; Layers turns them off, and that choice is remembered. */
    val online:Boolean=true) {
    fun layers(outliers:Boolean)=com.bontecou.whereday.domain.MapLayers(path,points,visits,endpoints,outliers)
}
class AppPreferences internal constructor(private val store:DataStore<Preferences>,private val beforeRoadPolicyChange:(String)->Unit={}) {
    constructor(context:Context,beforeRoadPolicyChange:(String)->Unit={}):this(context.preferenceStore,beforeRoadPolicyChange)
    companion object {
        /** New-install defaults, except network place lookup stays off: the person's earlier choice is unknown. Online tiles stay on so the map still shows streets. */
        internal fun recoveredPreferences():Preferences=preferencesOf(Keys.geocoding to false)
    }
    private object Keys {
        val onboarded = booleanPreferencesKey("onboarded")
        val appearance = stringPreferencesKey("appearance")
        val metric = booleanPreferencesKey("metric")
        val distance = intPreferencesKey("distance_filter")
        val hours = intPreferencesKey("stop_after_hours")
        val geocoding = booleanPreferencesKey("network_geocoding")
        val folder = stringPreferencesKey("export_folder")
        val inferred = booleanPreferencesKey("inferred_enabled")
        val inferredGap = intPreferencesKey("inferred_gap_minutes")
        val inferredDuration = intPreferencesKey("inferred_minimum_minutes")
        val inferredPoints = intPreferencesKey("inferred_minimum_points")
        val outliers=booleanPreferencesKey("show_outliers")
        val photos=booleanPreferencesKey("show_photo_markers")
        val photoImages=booleanPreferencesKey("show_photo_images")
        val photoDots=booleanPreferencesKey("show_photo_dots")
        val autoPhotos=booleanPreferencesKey("automatic_photo_sync")
        val photoLocations=booleanPreferencesKey("request_photo_picker_locations")
        val photoEpoch=stringPreferencesKey("photo_selection_epoch")
        val exports = stringPreferencesKey("export_configuration_v1")
        val roadDisplay=booleanPreferencesKey("road_display_enabled")
        val roadRevision=stringPreferencesKey("road_access_revision")
        val accessGate=booleanPreferencesKey("access_gate_cleared")
        val mapPath=booleanPreferencesKey("map_show_path")
        val mapPoints=booleanPreferencesKey("map_show_points")
        val mapVisits=booleanPreferencesKey("map_show_visits")
        val mapEndpoints=booleanPreferencesKey("map_show_endpoints")
        val mapStraight=booleanPreferencesKey("showStraightLinePathSegments")
        val mapLive=booleanPreferencesKey("map_show_live_session")
        val mapSuggestions=booleanPreferencesKey("showVisitSuggestions")
        val mapOnline=booleanPreferencesKey("map_online_tiles")
        val routeMode=stringPreferencesKey("route_sampling_mode")
        val routeInterval=intPreferencesKey("route_sample_interval_seconds")
    }
    private fun read(p: Preferences) = AppSettings(p[Keys.onboarded] ?: false, p[Keys.appearance] ?: "system", p[Keys.metric] ?: true, p[Keys.distance] ?: 5, p[Keys.hours] ?: 0, p[Keys.geocoding] ?: true, p[Keys.folder],
        p[Keys.inferred] ?: true,p[Keys.inferredGap] ?: 30,p[Keys.inferredDuration] ?: 0,p[Keys.inferredPoints] ?: 1,p[Keys.exports],p[Keys.outliers] ?: false,p[Keys.photoEpoch].orEmpty(),p[Keys.photos] ?: false,p[Keys.photoImages] ?: true,p[Keys.photoLocations] ?: false,p[Keys.autoPhotos] ?: false,p[Keys.roadDisplay] ?: false,p[Keys.roadRevision].orEmpty(),p[Keys.photoDots] ?: false,p[Keys.accessGate] ?: false,
        MapLayerSettings(p[Keys.mapPath] ?: true,p[Keys.mapPoints] ?: false,p[Keys.mapVisits] ?: true,p[Keys.mapEndpoints] ?: true,p[Keys.mapStraight] ?: false,p[Keys.mapLive] ?: true,p[Keys.mapSuggestions] ?: false,p[Keys.mapOnline] ?: true),
        com.bontecou.whereday.domain.RouteSamplingMode.fromKey(p[Keys.routeMode]).key,p[Keys.routeInterval]?.takeIf {it in com.bontecou.whereday.domain.RouteSamplingPolicy.INTERVAL_OPTIONS} ?: com.bontecou.whereday.domain.RouteSamplingPolicy.DEFAULT_INTERVAL_SECONDS)
    val settings = store.data.map(::read)
    suspend fun update(transform: (AppSettings) -> AppSettings) { store.edit { p ->
        val previous=read(p)
        val value=transform(previous)
        p[Keys.onboarded] = value.onboarded; p[Keys.appearance] = value.appearance; p[Keys.metric] = value.metric
        require(value.distanceFilter in listOf(5, 10, 25, 50, 100, 200))
        require(value.stopAfterHours in listOf(0, 1, 2, 4, 8, 12))
        require(value.photoEpoch.length<=128)
        p[Keys.photoEpoch]=value.photoEpoch
        p[Keys.photos]=value.showPhotoMarkers;p[Keys.photoImages]=value.showPhotoImages;p[Keys.photoLocations]=value.requestPhotoLocations;p[Keys.autoPhotos]=value.automaticPhotoSync
        p[Keys.photoDots]=value.showPhotoDots
        p[Keys.outliers]=value.showOutliers
        require(com.bontecou.whereday.domain.RouteSamplingMode.entries.any {it.key==value.routeSamplingMode})
        require(value.routeSampleIntervalSeconds in com.bontecou.whereday.domain.RouteSamplingPolicy.INTERVAL_OPTIONS)
        p[Keys.routeMode]=value.routeSamplingMode;p[Keys.routeInterval]=value.routeSampleIntervalSeconds
        p[Keys.distance] = value.distanceFilter; p[Keys.hours] = value.stopAfterHours; p[Keys.geocoding] = value.networkGeocoding
        require(value.inferredGapMinutes in listOf(15,30,60,120,240) && value.inferredMinimumMinutes in listOf(0,5,15,30,60) && value.inferredMinimumPoints in listOf(1,2,3,5,10))
        p[Keys.inferred]=value.inferredEnabled; p[Keys.inferredGap]=value.inferredGapMinutes; p[Keys.inferredDuration]=value.inferredMinimumMinutes; p[Keys.inferredPoints]=value.inferredMinimumPoints
        value.exportFolder?.let { p[Keys.folder] = it } ?: p.remove(Keys.folder)
        require(value.exportConfiguration==null || value.exportConfiguration.length<=16_384)
        value.exportConfiguration?.let {p[Keys.exports]=it} ?: p.remove(Keys.exports)
        // Revision ownership belongs to this atomic edit, never to a caller's copied snapshot.
        if(value.networkGeocoding!=previous.networkGeocoding || value.roadDisplayEnabled!=previous.roadDisplayEnabled) {
            val revision=java.util.UUID.randomUUID().toString()
            beforeRoadPolicyChange(revision)
            p[Keys.roadRevision]=revision
        }
        p[Keys.roadDisplay]=value.roadDisplayEnabled
        p[Keys.accessGate]=value.accessGateCleared
        value.map.let {map->
            p[Keys.mapPath]=map.path;p[Keys.mapPoints]=map.points;p[Keys.mapVisits]=map.visits;p[Keys.mapEndpoints]=map.endpoints
            p[Keys.mapStraight]=map.straightGaps;p[Keys.mapLive]=map.liveSession;p[Keys.mapSuggestions]=map.visitSuggestions;p[Keys.mapOnline]=map.online
        }
    } }
}
