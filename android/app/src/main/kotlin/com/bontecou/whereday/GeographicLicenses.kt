package com.bontecou.whereday

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun GeographicLicenses(back: ()->Unit) {
    val context=LocalContext.current
    var document by remember { mutableStateOf<String?>(null) }
    val license by produceState<String?>(null,document) {
        value=null
        document?.let { path -> value=withContext(Dispatchers.IO) { context.assets.open("licenses/$path").bufferedReader().use { it.readText() } } }
    }
    BackHandler { if(document!=null) document=null else back() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        TextButton(onClick={ if(document!=null) document=null else back() }) { Text(if(document!=null) "Back to geographic data" else "Back to settings") }
        Text("Geographic data and licenses",style=MaterialTheme.typography.headlineSmall)
        if(document!=null) {
            if(license==null) CircularProgressIndicator() else Text(license!!,style=MaterialTheme.typography.bodySmall)
        } else {
            Text("Time-zone boundaries © OpenStreetMap contributors, distributed by timezone-boundary-builder and Timeshape under the Open Database License 1.0. Boundaries are bundled with the app; time-zone lookup sends no coordinates over the network.")
            Text("Boundary source: Timeshape 2026b.29 · Copyright Roman Iakovlev 2018 · MIT tools and ODbL data. Geometry engine: Esri Geometry API 2.2.4 · Apache License 2.0.")
            TextButton(onClick={ document="timeshape-MIT.txt" }) { Text("Timeshape license") }
            TextButton(onClick={ document="timezone-boundaries-ODbL.txt" }) { Text("Time-zone data license") }
            TextButton(onClick={ document="esri-Apache.txt" }) { Text("Geometry engine license") }
            TextButton(onClick={ context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/evansiroky/timezone-boundary-builder"))) }) { Text("Boundary data and source") }
            TextButton(onClick={ context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/RomanIakovlev/timeshape"))) }) { Text("Timeshape source and packaged data") }
            Text("Country, submitted place-search text and selected-pin address lookup use Android’s geocoding service when you enable network geocoding and request a lookup. Availability and accuracy depend on the device’s provider. Search does not read device location or journal history. Recorded coordinates and user corrections remain in your journal.")
        }
    }
}
