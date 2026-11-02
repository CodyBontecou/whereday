package com.bontecou.whereday

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.*

internal enum class SettingsDestination {usage,support,countries,licenses,software,exports,automation,webhooks,photos,places,addPlace,roads,visitsOnly}

@Composable fun SettingsScreen(graph:AppGraph,settings:AppSettings,places:List<SavedPlace>,count:Long) {
    val context=LocalContext.current
    // The overview owns its position while a child destination is shown.
    val scroll=rememberLazyListState()
    var destination by rememberSaveable {mutableStateOf<SettingsDestination?>(null)}
    var addingPlace by rememberSaveable {mutableStateOf(false)}
    val clear by graph.journalClear.state.collectAsStateWithLifecycle()
    val commands by graph.settingsActions.state.collectAsStateWithLifecycle()
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {uri->if(uri!=null) graph.settingsActions.folder(uri.toString())}
    val back={destination=null}
    when(destination) {
        SettingsDestination.usage->{UsageAnalyticsScreen(graph.usageAnalytics,back);return}
        SettingsDestination.roads->{RoadSettingsScreen(graph,back);return}
        SettingsDestination.visitsOnly->{VisitMonitoringScreen(graph,back);return}
        SettingsDestination.support->{SupportScreen(graph,back);return}
        SettingsDestination.countries->{CountryDaysScreen(graph,back=back);return}
        SettingsDestination.licenses->{GeographicLicenses(back);return}
        SettingsDestination.software->{SoftwareNoticesScreen(back);return}
        SettingsDestination.exports->{AutomaticExportsScreen(graph,settings.exportFolder!=null,back);return}
        SettingsDestination.automation->{AutomationScreen(graph,back);return}
        SettingsDestination.webhooks->{WebhookScreen(graph,back);return}
        SettingsDestination.photos->{PhotosScreen(graph,back);return}
        SettingsDestination.places->{SavedPlacesScreen(graph,places,back);return}
        else->Unit
    }
    val actions=SettingsActions(
        {graph.settingsActions.update(it)},
        {try {folder.launch(null)} catch(_:Exception) {graph.settingsActions.reportFailure(SettingsFailure.folderPicker)}},
        {try {context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))} catch(_:Exception) {graph.settingsActions.reportFailure(SettingsFailure.permissions)}},
        {if(it==SettingsDestination.addPlace) addingPlace=true else destination=it},
        {graph.journalClear.clear()},graph.journalClear::dismiss,graph.settingsActions::dismiss,
    )
    SettingsContent(settings,places.size,count,commands,clear,actions,scroll)
    if(addingPlace) PlaceEditor(null,{addingPlace=false},{graph.savedPlaces.save(it,null)},null)
}

@Composable fun Choice(label: String, value: String, choices: List<String>, enabled:Boolean=true,labelFor:(String)->String={it.replaceFirstChar {c->c.uppercase()}},change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val picker:@Composable ()->Unit = {
        Box {TextButton(enabled=enabled,onClick={expanded=true}) {Text(labelFor(value))}
            WorkspaceDropdownMenu(expanded,{expanded=false}) {choices.forEach {choice->DropdownMenuItem(text={Text(labelFor(choice))},onClick={expanded=false;change(choice)})}}
        }
    }
    if(LocalDensity.current.fontScale>=1.3f) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(label,Modifier.fillMaxWidth());picker()}
    } else {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(label,Modifier.weight(1f).padding(top=12.dp,end=8.dp));picker()}
    }
}
