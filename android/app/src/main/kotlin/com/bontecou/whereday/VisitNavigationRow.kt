package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.bontecou.whereday.domain.Visit

/** The same explicit visit action works for linked outings and accessible map selection. */
@Composable internal fun VisitNavigationRow(visit:Visit,open:()->Unit) {
    OutlinedCard(onClick=open,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).heightIn(min=48.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(visit.name?.takeIf {it.isNotBlank()} ?: stringResource(R.string.timeline_visit),style=MaterialTheme.typography.titleMedium)
                Text(timelineRange(visit.arrivedAt,visit.departedAt,R.string.visit_still_here),style=MaterialTheme.typography.bodyMedium)
                visit.address?.takeIf {it.isNotBlank()}?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                Text(stringResource(R.string.timeline_open_visit),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null)
        }
    }
}
