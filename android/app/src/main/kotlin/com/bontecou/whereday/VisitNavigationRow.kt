package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.res.stringResource
import com.bontecou.whereday.domain.Visit

/** The same explicit visit action works for linked outings and accessible map selection. */
@Composable internal fun VisitNavigationRow(visit:Visit,open:()->Unit) {
    val shape=RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(wd.card).border(1.dp,wd.border,shape).clickable(role=Role.Button,onClick=open)
        .padding(16.dp).heightIn(min=48.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if(visit.departedAt==null) wd.liveDot else wd.placeInks[0]))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(visit.name?.takeIf {it.isNotBlank()} ?: stringResource(R.string.timeline_visit),style=WdType.title3,color=wd.textPrimary)
            Text(timelineRange(visit.arrivedAt,visit.departedAt,R.string.visit_still_here),style=WdType.caption2,color=wd.textMuted)
            visit.address?.takeIf {it.isNotBlank()}?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=wd.textMuted)}
            Text(stringResource(R.string.timeline_open_visit),style=WdType.caption2.copy(fontWeight=FontWeight.SemiBold,letterSpacing=0.1.em),color=wd.inkRust)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(18.dp),tint=wd.textMuted)
    }
}
