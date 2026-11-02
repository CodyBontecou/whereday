package com.bontecou.whereday

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.platform.*
import com.bontecou.whereday.data.JournalItemNotFound
import com.bontecou.whereday.data.NoCurrentOuting
import com.bontecou.whereday.data.ManualVisitCommandFailed
import com.bontecou.whereday.data.ManualVisitProblem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Launch for result only. Consent is managed in Settings, never through caller-supplied extras. */
class AutomationActivity:ComponentActivity() {
    private val graph by lazy {AppGraph.from(this)}
    private val focused=MutableStateFlow(false)
    private var operation:Job?=null
    private var responded=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {operation?.cancel();failure("cancelled")}
        })
        if(savedInstanceState!=null) {failure("interrupted");return}
        val caller=callingPackage
        if(caller==null || callingActivity?.packageName!=caller) {failure("not_authorized");return}
        if(android.os.Build.VERSION.SDK_INT>=35) {
            val origin=getCaller()?.`package`
            if(origin!=null && origin!=caller) {failure("not_authorized");return}
        }
        if(intent.action!=ACTION_RUN || intent.flags and Intent.FLAG_ACTIVITY_FORWARD_RESULT!=0) {failure("invalid_request");return}
        val request=try {AutomationRequest.decode(requireNotNull(intent.getStringExtra(EXTRA_REQUEST)))}
            catch(_:Exception) {failure("invalid_request");return}
        setContent {
            val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
            val dark=when(settings?.appearance) {"dark"->true;"light"->false;else->isSystemInDarkTheme()}
            val view=androidx.compose.ui.platform.LocalView.current
            SideEffect {androidx.core.view.WindowInsetsControllerCompat(window,view).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }}
            MaterialTheme(colorScheme=if(dark) darkColorScheme(primary=androidx.compose.ui.graphics.Color(0xFFA8D8B8)) else lightColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF285D42))) {
                AutomationCommandContent {operation?.cancel();failure("cancelled")}
            }
        }
        operation=lifecycleScope.launch {
            try {
                // This standard Activity stays off the lock screen; a recording start additionally
                // checks focused foreground eligibility immediately before service dispatch.
                withTimeout(15_000) {focused.first {it}}
                graph.journalClear.awaitReady()
                val result=graph.automation(this@AutomationActivity).execute(caller,request)
                val uri=Uri.parse("content://$packageName.automation-results/${result.id}")
                if(!responded && !isFinishing && !isDestroyed) {
                    graph.automationResults.grantRead(caller,result.id)
                    if(responded || isFinishing || isDestroyed) return@launch
                    responded=true
                    graph.activityLog.record(AppEvent.automationCompleted)
                    setResult(RESULT_OK,Intent().setDataAndType(uri,result.mime).apply {
                        clipData=ClipData.newRawUri("Whereday automation result",uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(EXTRA_VERSION,1)
                    })
                    finish()
                }
            } catch(error:CancellationException) {if(!responded && !isFinishing && !isDestroyed) failure("interrupted")}
            catch(error:Exception) {failure(when(error) {
                is RecordingNeedsForeground,is RecordingNeedsLocation->"open_app"
                is AutomationLocationUnavailable->"location_unavailable"
                is ManualVisitCommandFailed->when(error.problem) {
                    ManualVisitProblem.missingPlace->"missing_place"
                    ManualVisitProblem.overlap->"overlap"
                    ManualVisitProblem.noOpenVisit->"no_open_visit"
                    ManualVisitProblem.ambiguousVisit->"ambiguous_visit"
                    ManualVisitProblem.closedVisit->"visit_closed"
                }
                is com.bontecou.whereday.billing.VerifiedAccessRequired,is AutomationExportAccessRequired->"purchase_required"
                is AutomationNotAuthorized,is AutomationSettingsUnavailable,is SecurityException->"not_authorized"
                is AutomationInvalidRequest,is IllegalArgumentException->"invalid_request"
                is JournalItemNotFound,is NoCurrentOuting->"not_found"
                else->"unavailable"
            })}
        }
    }
    override fun onWindowFocusChanged(hasFocus:Boolean) {super.onWindowFocusChanged(hasFocus);focused.value=hasFocus}
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);operation?.cancel();failure("interrupted")}
    private fun failure(code:String) {
        if(responded) return
        ActivityLog.process.record(AppEvent.automationFailed)
        responded=true;setResult(RESULT_CANCELED,Intent().putExtra(EXTRA_ERROR,code).putExtra(EXTRA_VERSION,1));finish()
    }
    companion object {
        const val ACTION_RUN="com.bontecou.whereday.automation.RUN"
        const val EXTRA_REQUEST="com.bontecou.whereday.automation.REQUEST"
        const val EXTRA_ERROR="com.bontecou.whereday.automation.ERROR"
        const val EXTRA_VERSION="com.bontecou.whereday.automation.VERSION"
    }
}
