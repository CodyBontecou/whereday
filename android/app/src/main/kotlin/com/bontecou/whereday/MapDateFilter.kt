package com.bontecou.whereday

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Stable selection keys drive range logic. Locale changes affect labels only.
internal val mapDatePresets=listOf("Today","Yesterday","7 days","30 days","All","Custom")

/** The day scrubber's paper capsule: a mono "Dates" caption and the chosen range, opening the preset menu. */
@Composable internal fun MapDateFilter(selected:String,change:(String)->Unit) {
    val ids=listOf(R.string.map_date_today,R.string.map_date_yesterday,R.string.map_date_week,R.string.map_date_month,R.string.map_date_all,R.string.map_date_custom)
    val labels=mapDatePresets.zip(ids.map {stringResource(it)}).toMap()+mapOf("Visit" to stringResource(R.string.map_date_visit))
    val labelFor={value:String->labels[value] ?: value}
    var expanded by remember {mutableStateOf(false)}
    val colors=wd
    Box {
        Row(Modifier.heightIn(min=44.dp).wdFloating(CircleShape,colors,4.dp).clickable(role=Role.Button) {expanded=true}.padding(start=14.dp,end=10.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.CalendarMonth,null,Modifier.size(16.dp),tint=colors.textMuted)
            Text(stringResource(R.string.map_dates).uppercase(),style=WdType.eyebrow,color=colors.textMuted)
            Text(labelFor(selected),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=colors.textPrimary)
            Icon(Icons.Filled.KeyboardArrowDown,null,Modifier.size(18.dp),tint=colors.textMuted)
        }
        WorkspaceDropdownMenu(expanded,{expanded=false}) {
            mapDatePresets.forEach {choice->
                val current=choice==selected
                DropdownMenuItem(text={Text(labelFor(choice),style=WdType.caption.copy(fontWeight=if(current) FontWeight.SemiBold else FontWeight.Medium),color=if(current) colors.accent else colors.textPrimary)},
                    trailingIcon=if(current) ({Icon(Icons.Filled.Check,null,Modifier.size(16.dp),tint=colors.accent)}) else null,
                    onClick={expanded=false;change(choice)})
            }
        }
    }
}
