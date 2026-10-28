package com.bontecou.whereday.platform

import android.content.Context
import com.bontecou.whereday.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.bontecou.whereday.domain.PlaceLookupService
import com.bontecou.whereday.export.ExportSelectionCodec

class AppGraph private constructor(context: Context) {
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
    val recordingCommands=RecordingCommands(context.applicationContext,history)
    val automationAccess=AutomationAccess(context.applicationContext)
    val automationResults=AutomationResults(context.applicationContext,automationAccess) {purchases.requireAccess(com.bontecou.whereday.billing.Capability.fileExport)}
    val automationController=AutomationController(automationAccess,automationResults)
    val savedPlaces=SavedPlaceRepository(database)
    val photos=PhotoRepository(database)
    val photoSource=AndroidPhotoSource(context)
    val photoAccess=AndroidPhotoAccess(context.applicationContext)
    val replay=ReplayRepository(java.io.File(context.cacheDir,"route-replay"),history)
    val currentVisitLocation=FusedCurrentVisitLocationSource(context)
    val preferences = AppPreferences(context.applicationContext)
    val photoLibrary=PhotoLibraryController(context.applicationContext,database,photos,photoSource,preferences)
    val automaticPhotos=AutomaticPhotoSync(context.applicationContext,preferences,photos,photoLibrary)
    val placeLookup=PlaceLookupService(SystemPlaceLookup(context),preferences.settings.map {it.networkGeocoding},{java.util.Locale.getDefault().toLanguageTag()},elapsedMillis={android.os.SystemClock.elapsedRealtime()})
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
    fun automation(activity:android.app.Activity)=automation(recordingCommands.bind(activity))
    fun automation(recording:AutomationRecording)=AutomationExecutor(journalCommands,automationResults,recording,exports,{
        val settings=preferences.settings.first();settings.inferenceRules.takeIf {settings.inferredEnabled}
    })
    companion object {
        @Volatile private var instance: AppGraph? = null
        fun from(context: Context): AppGraph = instance ?: synchronized(this) { instance ?: AppGraph(context.applicationContext).also { instance = it } }
    }
}

object RecordingRuntime {
    private val state = MutableStateFlow(false)
    val active = state.asStateFlow()
    internal fun update(active: Boolean) { state.value = active }
}
