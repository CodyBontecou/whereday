package com.bontecou.whereday.platform

import android.content.Context
import com.bontecou.whereday.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.bontecou.whereday.domain.PlaceLookupService
import com.bontecou.whereday.export.ExportSelectionCodec

class AppGraph internal constructor(private val context: Context, roadDirections:com.bontecou.whereday.domain.RoadDirections?=null,
    private val roadDisclosure:RoadProviderDisclosure?=null) {
    init {require(roadDisclosure==null || roadDirections!=null)}
    val activityLog=ActivityLog.process
    val reviewMilestones=ReviewMilestoneController(AndroidReviewMilestoneStore(context.applicationContext),unavailable={activityLog.record(AppEvent.reviewUnavailable)})
    val usageAnalytics by lazy {
        val core=UsageAnalyticsCore(AndroidUsageAnalyticsStore(context.applicationContext),UsageAnalyticsHttpTransport.configured()) {
            @Suppress("DEPRECATION") val info=context.packageManager.getPackageInfo(context.packageName,0)
            UsageMetadata(requireNotNull(info.versionName).substringBefore('-'),androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info).toString())
        }
        UsageAnalyticsController(core,UsageAnalyticsWorkScheduler(androidx.work.WorkManager.getInstance(context.applicationContext),core))
    }
    val usageFunnel by lazy {UsageFunnel(usageAnalytics)}
    val database = HistoryDatabase.open(context)
    val history = HistoryRepository(database)
    val historyImports=HistoryImportController(DatabaseHistoryImportTarget(database),DiskHistoryImportWorkspaceFactory(java.io.File(context.cacheDir,"history-import-review")))
    val journalCommands=JournalCommands(history)
    // Starting either kind of tracking needs a subscription or Lifetime once Play billing is configured; stopping never does.
    val recordingCommands:RecordingCommands=RecordingCommands(context.applicationContext,history,canStart={!journalClear.state.value.blocked},
        access={purchases.requireAccess(com.bontecou.whereday.billing.Capability.recording)})
    val visitMonitoringCommands=VisitMonitoringCommands(context.applicationContext,history,canStart={!journalClear.state.value.blocked},
        access={purchases.requireAccess(com.bontecou.whereday.billing.Capability.recording)})
    val automationAccess=AutomationAccess(context.applicationContext)
    val automationResults=AutomationResults(context.applicationContext,automationAccess) {purchases.requireAccess(com.bontecou.whereday.billing.Capability.fileExport)}
    val automationController=AutomationController(automationAccess,automationResults)
    val savedPlaces=SavedPlaceRepository(database)
    val photos=PhotoRepository(database)
    val photoPlaces=PhotoPlaceRepository(java.io.File(context.cacheDir,"photo-place-directories"),photos)
    val photoSource=AndroidPhotoSource(context)
    val photoAccess=AndroidPhotoAccess(context.applicationContext)
    val replay=ReplayRepository(java.io.File(context.cacheDir,"route-replay"),history)
    val currentVisitLocation=FusedCurrentVisitLocationSource(context)
    val preferences:AppPreferences = AppPreferences(context.applicationContext) {revision->roadDisplay.invalidatePolicy(revision)}
    val roadDisplay:RoadDisplayController by lazy {RoadDisplayController(roadDirections,preferences.settings,
        {preferences.settings.first()},{preferences.update {it.copy(roadDisplayEnabled=false)}},journalClear.state,
        elapsedMillis={android.os.SystemClock.elapsedRealtime()})}
    // A configured provider does not grant consent; each request is still admitted by roadDisplay.
    fun roadConsentSession()=RoadConsentSession(roadDisclosure,{roadDisplay.state.value.configured},journalClear.state,preferences::update,
        beforeDisable={roadDisplay.invalidatePolicy(java.util.UUID.randomUUID().toString())})
    val settingsActions=SettingsActionController(preferences::update) {value->
        val uri=android.net.Uri.parse(value)
        require(uri.scheme=="content" && android.provider.DocumentsContract.isTreeUri(uri))
        context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        preferences.update {it.copy(exportFolder=uri.toString())}
    }
    val photoLibrary=PhotoLibraryController(context.applicationContext,database,photos,photoSource,preferences)
    val automaticPhotos=AutomaticPhotoSync(context.applicationContext,preferences,photos,photoLibrary)
    val placeLookup=PlaceLookupService(GeoapifyPlaceLookup.configured(SystemPlaceLookup(context)),preferences.settings.map {it.networkGeocoding},{java.util.Locale.getDefault().toLanguageTag()},elapsedMillis={android.os.SystemClock.elapsedRealtime()})
    val purchases = PurchaseController(context.applicationContext) {usageFunnel}
    val webhooks=WebhookCoordinator(context.applicationContext,database,purchases::requireAccess)
    val webhookWork=WebhookWorkScheduler(androidx.work.WorkManager.getInstance(context.applicationContext),webhooks)
    val webhookController=WebhookController(webhooks,webhookWork,WebhookQueueRepository(database).observe().map {Unit},preferences.settings.map {it.showOutliers})
    val exports = ExportFiles(context.applicationContext,database,purchases::requireAccess,reviewMilestones::fileExported)
    val exportSettings = ExportSettingsController(preferences)
    val exportPicker = ExportPickerController(exports)
    val automaticExports=ScheduledExportCoordinator(context.applicationContext,exports,{
        val settings=preferences.settings.first()
        ScheduledExportInputs(settings.exportFolder ?: error("Choose an export folder in Settings first."),ExportSelectionCodec.decode(settings.exportConfiguration).filename,settings.inferenceRules.takeIf {settings.inferredEnabled})
    },purchases::requireAccess)
    val automaticExportWork=ScheduledExportWorkScheduler(androidx.work.WorkManager.getInstance(context.applicationContext),automaticExports)
    val automaticExportController=AutomaticExportController(automaticExports,automaticExportWork)
    val countryAnalysis = CountryAnalysisController(context.applicationContext,database,preferences)
    val journalClear:JournalClearController by lazy {JournalClearController(AndroidJournalClearReceiptStore(context.applicationContext),{
        historyImports.clearAll {
            roadDisplay.clearAndJoin()
            reviewMilestones.clearJournalMilestones()
            usageAnalytics.clearAll()
            automationController.clearAll()
            countryAnalysis.stopAndJoin()
            placeLookup.clearCache()
            automaticExportController.clearAll()
            exports.clearCache()
            visitMonitoringCommands.stop()
            recordingCommands.stop()
            automaticPhotos.clearAll()
            webhookController.clearAll {photoLibrary.clearAll {photoPlaces.clearCache {replay.clearCache {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {database.clearAllTables()}
            }}}}
            activityLog.clear()
        }
    },{activityLog.record(AppEvent.journalClearFailed)})}
    /** A boot broadcast also follows force-stop on Android 15+. Persisted boot evidence
     * distinguishes that event; runtime/ticket and identity guards protect a fresh start. */
    suspend fun reconcileLocationServices() {
        val boot=locationBootCount(context)
        val recording=history.dao.recording()
        if(recording!=null && !RecordingRuntime.active.value && !recordingCommands.preparing)
            history.markInterrupted(com.bontecou.whereday.domain.locationProcessInterruption(recording.startedBootCount,boot).code,recording)
        val visits=history.dao.visitMonitoring()
        if(visits!=null) {
            val reason=com.bontecou.whereday.domain.locationProcessInterruption(visits.startedBootCount,boot)
            visitMonitoringCommands.reconcile(if(reason==com.bontecou.whereday.domain.RecordingInterruption.phoneRestarted) "visits.restarted" else "visits.interrupted",visits)
        }
    }
    /** For launch and boot: a failure is logged instead of crashing each start; the next start retries. */
    suspend fun reconcileLocationServicesSafely():Boolean=
        recoverOrReport({activityLog.record(AppEvent.locationReconciliationFailed)}) {reconcileLocationServices()}
    fun automation(activity:android.app.Activity)=automation(recordingCommands.bind(activity),AutomationVisitLocation(activity)::locate)
    fun automation(recording:AutomationRecording,currentLocation:suspend ()->com.bontecou.whereday.domain.Coordinate={throw AutomationLocationUnavailable()})=AutomationExecutor(journalCommands,automationResults,recording,exports,{
        val settings=preferences.settings.first();settings.inferenceRules.takeIf {settings.inferredEnabled}
    },currentLocation=currentLocation)
    companion object {
        @Volatile private var instance: AppGraph? = null
        fun from(context: Context): AppGraph = instance ?: synchronized(this) { instance ?: run {
            val routing=GeoapifyRoadDirections.configured()
            AppGraph(context.applicationContext,routing?.directions,routing?.disclosure).also {instance=it}
        } }
    }
}

object RecordingRuntime {
    private val state = MutableStateFlow(false)
    val active = state.asStateFlow()
    internal fun update(active: Boolean) { state.value = active }
}

/** Runs [block]; any failure other than cancellation calls [onFailure] and returns false instead of propagating. */
internal suspend fun recoverOrReport(onFailure:()->Unit,block:suspend ()->Unit):Boolean=try {block();true}
    catch(error:kotlinx.coroutines.CancellationException) {throw error}
    catch(_:Exception) {runCatching(onFailure);false}
