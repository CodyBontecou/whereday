package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.whereday.platform.AppGraph

/** Primary destinations in iOS `MainTab` order; Settings only has a rail slot of its own. */
internal enum class ShellSection(val title: Int, val icon: ImageVector, val tag: String) {
    today(R.string.shell_today, Icons.Outlined.Schedule, "navigation-today"),
    atlas(R.string.shell_atlas, Icons.Outlined.GridView, "navigation-atlas"),
    places(R.string.shell_places, Icons.Outlined.Place, "navigation-places"),
    archive(R.string.shell_archive, Icons.Outlined.Inventory2, "navigation-archive"),
    settings(R.string.shell_settings, Icons.Outlined.Settings, "navigation-settings");
    companion object { val primary = listOf(today, atlas, places, archive) }
}

/** True while the rail, which has its own Settings slot, replaces the tab bar. */
internal val LocalShellRail = staticCompositionLocalOf { false }

/** The floating capsule tab bar on compact windows, or a slim leading rail on expanded ones. */
@Composable internal fun ShellSections(section: Int, settingsOpen: Boolean, select: (ShellSection) -> Unit, messages: SnackbarHostState, content: @Composable () -> Unit) {
    val ink = LocalWheredayInk.current
    val selected = if(settingsOpen) ShellSection.settings else ShellSection.entries[section]
    BoxWithConstraints(Modifier.fillMaxSize().background(ink.surface).semantics { testTagsAsResourceId = true }) {
        val rail = maxWidth >= 600.dp
        if(rail) Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            NavigationRailColumn(selected, select, Modifier.padding(start = 14.dp, top = 20.dp, bottom = 20.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) { CompositionLocalProvider(LocalShellRail provides true) { content() }; SnackbarHost(messages, Modifier.align(Alignment.BottomCenter)) }
        } else Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content(); SnackbarHost(messages, Modifier.align(Alignment.BottomCenter)) }
            // Archive keeps its tab selected while its Settings page is open.
            CapsuleTabBar(if(selected == ShellSection.settings) ShellSection.archive else selected, select)
        }
    }
}

@Composable private fun CapsuleTabBar(selected: ShellSection, select: (ShellSection) -> Unit) {
    val ink = LocalWheredayInk.current
    val iconOnly = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.6f
    val shape = RoundedCornerShape(if(iconOnly) 28.dp else 32.dp)
    val main = stringResource(R.string.shell_main)
    Row(Modifier.padding(start = if(iconOnly) 12.dp else 16.dp, end = if(iconOnly) 12.dp else 16.dp, top = 6.dp, bottom = 2.dp).fillMaxWidth()
        .shadow(12.dp, shape, ambientColor = ink.textPrimary.copy(alpha = 0.08f), spotColor = ink.textPrimary.copy(alpha = 0.08f))
        .background(ink.card, shape).border(1.dp, ink.border, shape).padding(horizontal = 4.dp)
        .semantics { contentDescription = main; selectableGroup() }.testTag("navigation-bar")) {
        ShellSection.primary.forEach { section -> TabItem(section, section == selected, iconOnly, Modifier.weight(1f).heightIn(min = if(iconOnly) 84.dp else 64.dp)) { select(section) } }
    }
}

@Composable private fun TabItem(section: ShellSection, selected: Boolean, iconOnly: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = LocalWheredayInk.current
    val tint = if(selected) ink.inkRust else ink.textMuted
    val title = stringResource(section.title)
    Column(modifier.clip(RoundedCornerShape(24.dp)).selectable(selected, role = Role.Tab, onClick = onClick).semantics { contentDescription = title }.testTag(section.tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(section.icon, null, Modifier.size(if(iconOnly) 30.dp else 24.dp), tint = tint)
        if(!iconOnly) Text(title, fontSize = 10.sp, fontWeight = if(selected) FontWeight.SemiBold else FontWeight.Medium, color = tint, maxLines = 1)
    }
}

@Composable private fun NavigationRailColumn(selected: ShellSection, select: (ShellSection) -> Unit, modifier: Modifier) {
    val ink = LocalWheredayInk.current
    val main = stringResource(R.string.shell_main)
    Column(modifier.width(60.dp).fillMaxHeight().background(ink.card, RoundedCornerShape(30.dp)).border(1.dp, ink.border, RoundedCornerShape(30.dp))
        .semantics { contentDescription = main; selectableGroup() }.testTag("navigation-rail"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(Modifier.weight(1f))
        ShellSection.primary.forEach { RailItem(it, it == selected) { select(it) } }
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(24.dp).height(1.dp).background(ink.border))
        RailItem(ShellSection.settings, selected == ShellSection.settings, Modifier.padding(bottom = 8.dp)) { select(ShellSection.settings) }
    }
}

@Composable private fun RailItem(section: ShellSection, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = LocalWheredayInk.current
    val tint = if(selected) ink.inkRust else ink.textMuted
    val title = stringResource(section.title)
    Column(modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).selectable(selected, role = Role.Tab, onClick = onClick).semantics { contentDescription = title }.testTag(section.tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(section.icon, null, Modifier.size(24.dp), tint = tint)
        Text(title, fontSize = 9.sp, fontWeight = if(selected) FontWeight.SemiBold else FontWeight.Medium, color = tint, maxLines = 1)
    }
}

/** Trace and Timeline open over Today with a capsule back to Today and a Map/Timeline switch. */
@Composable internal fun JournalChrome(selected: Int, titles: List<String>, backToToday: () -> Unit, select: (Int) -> Unit) {
    val ink = LocalWheredayInk.current
    Row(Modifier.fillMaxWidth().background(ink.surface).statusBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp).semantics { testTagsAsResourceId = true },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(ink.card).border(1.dp, ink.border, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = backToToday).padding(horizontal = 14.dp).testTag("journal-back-today"), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, tint = ink.textPrimary)
            Text(stringResource(R.string.shell_back_today), style = WheredayType.mono(12, FontWeight.SemiBold), color = ink.textPrimary)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(50)).background(ink.card).border(1.dp, ink.border, RoundedCornerShape(50)).semantics { selectableGroup() }) {
            titles.forEachIndexed { index, title ->
                val active = selected == index
                Box(Modifier.heightIn(min = 44.dp).background(if(active) ink.accent else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(active, role = Role.Tab) { select(index) }.padding(horizontal = 16.dp).testTag(if(index == 0) "navigation-map" else "navigation-timeline"),
                    contentAlignment = Alignment.Center) {
                    Text(title, color = if(active) ink.surface else ink.textPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/** Archive: the export composer, with Settings one tap away behind its gear. */
@Composable internal fun ArchiveScreen(graph: AppGraph, openSettings: () -> Unit) {
    val ink = LocalWheredayInk.current
    Box(Modifier.fillMaxSize()) {
        ExportScreen(graph)
        if(!LocalShellRail.current) IconButton(openSettings, Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp).background(ink.card, RoundedCornerShape(50)).border(1.dp, ink.border, RoundedCornerShape(50))
            .semantics { testTagsAsResourceId = true }.testTag("navigation-settings")) {
            Icon(Icons.Outlined.Settings, stringResource(R.string.archive_settings), tint = ink.textPrimary)
        }
    }
}
