package com.bontecou.whereday

import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.bontecou.whereday.domain.RecapFrequency
import com.bontecou.whereday.platform.EveningRecap
import kotlinx.coroutines.delay
import java.time.Instant

/** QA-only (mapQa): runs the real evening recap job right now through WorkManager, against whatever this disposable package's
 *  journal holds (seed it first with DemoJournalActivity), and shows what it did. The "app was open this evening" rule is
 *  ignored, since this screen is itself in the foreground, and the chosen frequency runs on any weekday (a weekly recap on a
 *  Monday covers the last seven days); every other skip rule applies. Nothing is queued afterwards.
 *  `adb shell am start -n com.bontecou.whereday.mapqa/com.bontecou.whereday.RecapDemoActivity --es frequency daily`
 *  Extras: `frequency` (`daily`, `weekdays` or `weekly`; default: the saved Settings › Reminders choice). */
class RecapDemoActivity:androidx.activity.ComponentActivity() {
    override fun onCreate(state:android.os.Bundle?) {
        super.onCreate(state)
        check(packageName.endsWith(".mapqa")) {"The immediate recap is for the disposable QA package only"}
        val started=Instant.now()
        EveningRecap.runNowForQa(this,RecapFrequency.fromKey(intent.getStringExtra("frequency")))
        setContent {
            WheredayTheme(isSystemInDarkTheme()) {
                var result by remember {mutableStateOf<EveningRecap.RunResult?>(null)}
                LaunchedEffect(Unit) {
                    while(result==null) {
                        EveningRecap.lastResult(this@RecapDemoActivity)?.takeIf {!it.at.isBefore(started)}?.let {result=it}
                        delay(200)
                    }
                }
                Surface(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        val done=result
                        Text(if(done==null) "QA recap running" else "QA recap ${done.outcome}",Modifier.testTag("qa-recap-outcome"))
                        Text(done?.title.orEmpty(),Modifier.testTag("qa-recap-title"))
                        Text(done?.body.orEmpty(),Modifier.testTag("qa-recap-body"))
                    }
                }
            }
        }
    }
}
