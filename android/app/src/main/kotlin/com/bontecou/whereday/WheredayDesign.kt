package com.bontecou.whereday

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Paper and blue-ink tokens shared with the iOS app's TE palette: navy on paper, amber at night. */
@Immutable internal data class WdColors(
    val accent:Color, val surface:Color, val card:Color, val border:Color, val textPrimary:Color, val textMuted:Color,
    val inkRust:Color, val inkCard:Color, val onInkCard:Color, val onInkCardMuted:Color, val liveDot:Color,
    val dialTrack:Color, val dialTick:Color, val placeInks:List<Color>, val success:Color, val warning:Color, val dark:Boolean,
    // Material 3 tonal roles from the "Whereday Material 3" design: grouped sections sit in containerLow, bars in container.
    val containerLow:Color=surface, val container:Color=card, val containerHigh:Color=card, val containerHighest:Color=dialTrack,
    val outline:Color=border, val secondaryContainer:Color=card, val onSecondaryContainer:Color=textPrimary,
    val primaryContainer:Color=card, val onPrimaryContainer:Color=textPrimary, val onSecondary:Color=surface,
    val noticeContainer:Color=card, val onNoticeContainer:Color=textPrimary,
)

private val LightWd=WdColors(
    accent=Color(0xFF1E2B45), surface=Color(0xFFF3EFE6), card=Color(0xFFFBF9F4), border=Color(0xFFD8D2C3),
    textPrimary=Color(0xFF1E2B45), textMuted=Color(0xFF55534C), inkRust=Color(0xFFA8381F),
    inkCard=Color(0xFF1E2B45), onInkCard=Color(0xFFF3EFE6), onInkCardMuted=Color(0xFFC9CEDA), liveDot=Color(0xFFF08A6B),
    dialTrack=Color(0xFFE4DED0), dialTick=Color(0xFF9A9585),
    placeInks=listOf(Color(0xFF1E2B45),Color(0xFFC2462A),Color(0xFFB7862B),Color(0xFF5E7F5A),Color(0xFF7A86A8)),
    success=Color(0xFF3F7A4E), warning=Color(0xFFB7862B), dark=false,
    containerLow=Color(0xFFEEE9DE), container=Color(0xFFE9E3D6), containerHigh=Color(0xFFE4DED0), containerHighest=Color(0xFFDCD5C5),
    outline=Color(0xFF857F70), secondaryContainer=Color(0xFFF4D9CF), onSecondaryContainer=Color(0xFF4E1607),
    primaryContainer=Color(0xFFDCE1EC), onPrimaryContainer=Color(0xFF16213A), onSecondary=Color(0xFFFFFFFF),
    noticeContainer=Color(0xFFF0E1BE), onNoticeContainer=Color(0xFF3A2A06),
)

private val DarkWd=WdColors(
    accent=Color(0xFFE0B565), surface=Color(0xFF0F1626), card=Color(0xFF18213A), border=Color(0xFF2C3754),
    textPrimary=Color(0xFFECE6D8), textMuted=Color(0xFFA3ABBF), inkRust=Color(0xFFF0805F),
    inkCard=Color(0xFFE0B565), onInkCard=Color(0xFF0F1626), onInkCardMuted=Color(0xFF5A4A2A), liveDot=Color(0xFFC2462A),
    dialTrack=Color(0xFF222C46), dialTick=Color(0xFF4A5573),
    placeInks=listOf(Color(0xFFECE6D8),Color(0xFFF0805F),Color(0xFFE0B565),Color(0xFF9DC097),Color(0xFF8FA6D9)),
    success=Color(0xFF9DC097), warning=Color(0xFFE0B565), dark=true,
    containerLow=Color(0xFF141C30), container=Color(0xFF18213A), containerHigh=Color(0xFF1E2843), containerHighest=Color(0xFF263150),
    outline=Color(0xFF6A7390), secondaryContainer=Color(0xFF5A2A1E), onSecondaryContainer=Color(0xFFFFDBD0),
    primaryContainer=Color(0xFF4A3D22), onPrimaryContainer=Color(0xFFF5E3BC), onSecondary=Color(0xFF2A0E05),
    noticeContainer=Color(0xFF4A3D22), onNoticeContainer=Color(0xFFF5E3BC),
)

internal val LocalWd=staticCompositionLocalOf {LightWd}

/** Shorthand for the active Whereday palette. */
internal val wd:WdColors @Composable @ReadOnlyComposable get()=LocalWd.current

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal object WdFonts {
    private fun serifWeight(weight:Int)=Font(R.font.newsreader,FontWeight(weight),variationSettings=FontVariation.Settings(FontVariation.weight(weight)))
    val serif=FontFamily(serifWeight(400),serifWeight(500),serifWeight(600),
        Font(R.font.newsreader_italic,FontWeight.Normal,FontStyle.Italic,variationSettings=FontVariation.Settings(FontVariation.weight(400))))
    val mono=FontFamily(Font(R.font.plex_mono_regular,FontWeight.Normal),Font(R.font.plex_mono_medium,FontWeight.Medium),Font(R.font.plex_mono_semibold,FontWeight.SemiBold))
}

/** Text styles mirroring TE.editorial / TE.mono at iOS's default text-style sizes. */
internal object WdType {
    val largeTitle=TextStyle(fontFamily=WdFonts.serif,fontWeight=FontWeight.Medium,fontSize=34.sp,lineHeight=40.sp,letterSpacing=(-0.01).em)
    val title=TextStyle(fontFamily=WdFonts.serif,fontWeight=FontWeight.Medium,fontSize=28.sp,lineHeight=34.sp)
    val title2=TextStyle(fontFamily=WdFonts.serif,fontWeight=FontWeight.Medium,fontSize=22.sp,lineHeight=28.sp)
    val title3=TextStyle(fontFamily=WdFonts.serif,fontWeight=FontWeight.Medium,fontSize=20.sp,lineHeight=25.sp)
    val serifBody=TextStyle(fontFamily=WdFonts.serif,fontSize=17.sp,lineHeight=23.sp)
    val eyebrow=TextStyle(fontFamily=WdFonts.mono,fontWeight=FontWeight.SemiBold,fontSize=11.sp,lineHeight=14.sp,letterSpacing=0.18.em)
    val caption=TextStyle(fontFamily=WdFonts.mono,fontSize=12.sp,lineHeight=16.sp)
    val caption2=TextStyle(fontFamily=WdFonts.mono,fontSize=11.sp,lineHeight=15.sp)
    val monoBody=TextStyle(fontFamily=WdFonts.mono,fontSize=15.sp,lineHeight=20.sp)
    val numeral=TextStyle(fontFamily=WdFonts.mono,fontWeight=FontWeight.Medium,fontSize=22.sp,lineHeight=26.sp)
    /** The Material 3 boards' page title: Newsreader 36/44 at regular weight. */
    val pageTitle=TextStyle(fontFamily=WdFonts.serif,fontWeight=FontWeight.Normal,fontSize=36.sp,lineHeight=44.sp)
    /** Serif figures in the Today hero and Place stats. */
    val figure=TextStyle(fontFamily=WdFonts.serif,fontWeight=FontWeight.Normal,fontSize=26.sp,lineHeight=28.sp,fontFeatureSettings="tnum")
}

private fun wdTypography():Typography {
    val base=Typography()
    return base.copy(
        displayLarge=base.displayLarge.copy(fontFamily=WdFonts.serif,fontWeight=FontWeight.Medium),
        displayMedium=base.displayMedium.copy(fontFamily=WdFonts.serif,fontWeight=FontWeight.Medium),
        displaySmall=base.displaySmall.copy(fontFamily=WdFonts.serif,fontWeight=FontWeight.Medium),
        headlineLarge=WdType.largeTitle, headlineMedium=WdType.title, headlineSmall=WdType.title2.copy(fontSize=24.sp,lineHeight=30.sp),
        titleLarge=WdType.title2,
        titleMedium=base.titleMedium.copy(fontWeight=FontWeight.SemiBold),
        labelLarge=base.labelLarge.copy(fontWeight=FontWeight.SemiBold),
        labelMedium=WdType.caption.copy(fontWeight=FontWeight.Medium),
        labelSmall=WdType.caption2.copy(fontWeight=FontWeight.Medium,letterSpacing=0.06.em),
    )
}

private fun wdColorScheme(c:WdColors):ColorScheme {
    val scheme=if(c.dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary=c.accent,onPrimary=c.surface,primaryContainer=c.primaryContainer,onPrimaryContainer=c.onPrimaryContainer,
        secondary=c.inkRust,onSecondary=c.onSecondary,secondaryContainer=c.secondaryContainer,onSecondaryContainer=c.onSecondaryContainer,
        tertiary=c.inkRust,onTertiary=c.onSecondary,tertiaryContainer=c.secondaryContainer,onTertiaryContainer=c.onSecondaryContainer,
        background=c.surface,onBackground=c.textPrimary,surface=c.surface,onSurface=c.textPrimary,
        surfaceVariant=c.containerHighest,onSurfaceVariant=c.textMuted,surfaceTint=Color.Transparent,
        surfaceBright=c.card,surfaceDim=c.container,surfaceContainerLowest=c.card,surfaceContainerLow=c.containerLow,
        surfaceContainer=c.container,surfaceContainerHigh=c.containerHigh,surfaceContainerHighest=c.containerHighest,
        inverseSurface=c.inkCard,inverseOnSurface=c.onInkCard,inversePrimary=c.inkRust,
        error=c.inkRust,onError=c.onSecondary,errorContainer=c.secondaryContainer,onErrorContainer=c.onSecondaryContainer,
        outline=c.outline,outlineVariant=c.border,scrim=Color.Black.copy(alpha=if(c.dark) 0.5f else 0.32f),
    )
}

private fun Color.compositeOver(background:Color):Color {
    val a=alpha
    return Color(red*a+background.red*(1-a),green*a+background.green*(1-a),blue*a+background.blue*(1-a),1f)
}

private val WdShapes=Shapes(
    extraSmall=RoundedCornerShape(6.dp),small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(16.dp),
    large=RoundedCornerShape(20.dp),extraLarge=RoundedCornerShape(28.dp),
)

/** Every Whereday surface, including the automation activity, renders inside this theme. */
@Composable internal fun WheredayTheme(dark:Boolean,content:@Composable ()->Unit) {
    val colors=if(dark) DarkWd else LightWd
    CompositionLocalProvider(LocalWd provides colors) {
        MaterialTheme(colorScheme=wdColorScheme(colors),typography=wdTypography(),shapes=WdShapes) {
            CompositionLocalProvider(LocalContentColor provides colors.textPrimary,content=content)
        }
    }
}

// MARK: Field-notes artwork

/** Stationary ink sketches behind journal pages; they never take layout space or touches. */
@Composable internal fun FieldNotesBackground(modifier:Modifier=Modifier,cloudsOpacity:Float=0.28f,streetOpacity:Float=0.34f) {
    val ink=wd.accent
    BoxWithConstraints(modifier.fillMaxSize().background(wd.surface)) {
        val large=LocalDensity.current.fontScale>=1.3f
        Image(painterResource(R.drawable.journal_clouds),null,
            Modifier.fillMaxWidth().height(minOf(maxHeight*0.36f,280.dp)).align(Alignment.TopCenter),
            contentScale=ContentScale.Crop,alpha=if(large) cloudsOpacity*0.5f else cloudsOpacity,colorFilter=ColorFilter.tint(ink))
        Image(painterResource(R.drawable.journal_street),null,
            Modifier.fillMaxWidth().height(minOf(maxHeight*0.48f,360.dp)).align(Alignment.BottomCenter),
            contentScale=ContentScale.Crop,alignment=Alignment.CenterEnd,alpha=if(large) streetOpacity*0.5f else streetOpacity,colorFilter=ColorFilter.tint(ink))
    }
}

/** A single sketch confined to its own space, as on the iOS onboarding pages. */
@Composable internal fun FieldNotesArtwork(street:Boolean,modifier:Modifier=Modifier,height:Dp=140.dp,alpha:Float=1f) {
    Image(painterResource(if(street) R.drawable.journal_street else R.drawable.journal_clouds),null,
        modifier.fillMaxWidth().height(height),contentScale=ContentScale.Crop,alignment=if(street) Alignment.CenterEnd else Alignment.Center,
        alpha=alpha,colorFilter=ColorFilter.tint(wd.accent))
}

// MARK: Page structure

/** A journal page: tracked mono caption above a Newsreader title, with icon actions on the trailing edge (M3 top app bar, large). */
@Composable internal fun WdPageHeader(title:String,modifier:Modifier=Modifier,eyebrow:String?=null,compact:Boolean=false,
    actions:@Composable RowScope.()->Unit={}) {
    Row(modifier.fillMaxWidth().padding(start=20.dp,end=8.dp).padding(top=if(compact) 8.dp else 20.dp,bottom=8.dp),verticalAlignment=Alignment.Bottom) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            if(eyebrow!=null) Text(eyebrow.uppercase(),style=WdType.caption2.copy(letterSpacing=0.127.em,lineHeight=16.sp),color=wd.textMuted)
            Text(title,style=if(compact) WdType.title else WdType.pageTitle,color=wd.textPrimary,modifier=Modifier.semantics {heading()})
        }
        Row(horizontalArrangement=Arrangement.spacedBy(0.dp),verticalAlignment=Alignment.CenterVertically,content=actions)
    }
}

/** A pushed page's Material 3 small top app bar: a back arrow (labelled for TalkBack), a start-aligned title and trailing actions. */
@Composable internal fun WdTopBar(title:String,onBack:(()->Unit)?,modifier:Modifier=Modifier,backLabel:String?=null,
    actions:@Composable RowScope.()->Unit={}) {
    Row(modifier.fillMaxWidth().statusBarsPadding().heightIn(min=64.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
        if(onBack!=null) WdIconButton(Icons.AutoMirrored.Outlined.ArrowBack,backLabel ?: androidx.compose.ui.res.stringResource(R.string.common_back),onBack,Modifier.testTag("top-bar-back"))
        else Spacer(Modifier.width(16.dp))
        Text(title,Modifier.weight(1f).padding(horizontal=4.dp).semantics {heading()},style=MaterialTheme.typography.titleLarge.copy(fontFamily=FontFamily.Default,fontWeight=FontWeight.Normal,fontSize=22.sp,lineHeight=28.sp),
            color=wd.textPrimary,maxLines=2,overflow=TextOverflow.Ellipsis)
        Row(horizontalArrangement=Arrangement.spacedBy(0.dp),verticalAlignment=Alignment.CenterVertically,content=actions)
    }
}

/** A full-screen form's top bar (Visit, Log a stop, Edit place, Add country stay): close, title and a text action. */
@Composable internal fun WdFormTopBar(title:String,onClose:()->Unit,closeLabel:String,modifier:Modifier=Modifier,closeTag:String?=null,
    action:(@Composable RowScope.()->Unit)?=null) {
    Row(modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
        WdIconButton(androidx.compose.material.icons.Icons.Outlined.Close,closeLabel,onClose,if(closeTag!=null) Modifier.testTag(closeTag) else Modifier)
        Text(title,Modifier.weight(1f).padding(horizontal=4.dp).semantics {heading()},style=MaterialTheme.typography.titleLarge.copy(fontFamily=FontFamily.Default,fontWeight=FontWeight.Normal,fontSize=22.sp,lineHeight=28.sp),
            color=wd.textPrimary,maxLines=2,overflow=TextOverflow.Ellipsis)
        action?.invoke(this)
    }
}

/** The raised "‹ Today" pill used to leave full-screen pages (the Trace board's back control). */
@Composable internal fun WdBackChip(label:String,onClick:()->Unit,modifier:Modifier=Modifier) {
    val colors=wd
    Row(modifier.heightIn(min=48.dp).wdFloating(CircleShape,colors,3.dp,border=false)
        .clickable(role=Role.Button,onClick=onClick).padding(start=12.dp,end=20.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack,null,Modifier.size(20.dp),tint=wd.textPrimary)
        Text(label,style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp),color=wd.textPrimary,maxLines=1)
    }
}

/** A page body over paper. Pushed settings pages use this in place of Scaffold + TopAppBar. */
@Composable internal fun WdPage(title:String,onBack:(()->Unit)?,modifier:Modifier=Modifier,backLabel:String?=null,artwork:Boolean=false,
    actions:@Composable RowScope.()->Unit={},content:@Composable ColumnScope.()->Unit) {
    Box(modifier.fillMaxSize().background(wd.surface)) {
        if(artwork) FieldNotesBackground()
        Column(Modifier.fillMaxSize()) {
            WdTopBar(title,onBack,backLabel=backLabel,actions=actions)
            content()
        }
    }
}

/** Uppercase, tracked mono section header (TESectionHeader). */
@Composable internal fun WdSectionHeader(title:String,modifier:Modifier=Modifier,trailing:@Composable (()->Unit)?=null) {
    val large=LocalDensity.current.fontScale>=1.3f
    Row(modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,top=20.dp,bottom=6.dp),verticalAlignment=Alignment.Bottom) {
        Text(title.uppercase(),style=WdType.eyebrow.copy(letterSpacing=if(large) 0.08.em else 0.18.em),color=wd.textMuted,
            modifier=Modifier.weight(1f).semantics {heading()})
        trailing?.invoke()
    }
}

/** Muted mono footnote beneath a card (TESectionFooter). */
@Composable internal fun WdSectionFooter(text:String,modifier:Modifier=Modifier) {
    Text(text,style=WdType.caption2,color=wd.textMuted,modifier=modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,top=6.dp))
}

/** A grouped section: a 28dp surface-container-low card without a hairline, as in every Material 3 board. */
@Composable internal fun WdCard(modifier:Modifier=Modifier,radius:Dp=28.dp,content:@Composable ColumnScope.()->Unit) {
    val shape=RoundedCornerShape(radius)
    Column(modifier.fillMaxWidth().clip(shape).background(wd.containerLow).padding(vertical=4.dp),content=content)
}

/** One row inside a WdCard, with a hairline divider inset from the leading edge (TERow). */
@Composable internal fun WdRow(modifier:Modifier=Modifier,divider:Boolean=true,onClick:(()->Unit)?=null,enabled:Boolean=true,
    content:@Composable RowScope.()->Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(modifier.fillMaxWidth().then(if(onClick!=null) Modifier.clickable(enabled=enabled,role=Role.Button,onClick=onClick) else Modifier)
            .heightIn(min=56.dp).padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(16.dp),content=content)
        if(divider) WdDivider(Modifier.padding(start=20.dp))
    }
}

/** A navigation row: optional leading glyph, label, trailing value and chevron. */
@Composable internal fun WdNavRow(label:String,modifier:Modifier=Modifier,value:String?=null,icon:ImageVector?=null,
    divider:Boolean=true,enabled:Boolean=true,destructive:Boolean=false,onClick:()->Unit) {
    WdRow(modifier,divider,onClick,enabled) {
        val ink=when {!enabled->wd.textMuted; destructive->wd.inkRust; else->wd.textPrimary}
        if(icon!=null) Icon(icon,null,Modifier.size(20.dp),tint=if(destructive) wd.inkRust else wd.textMuted)
        Text(label,style=MaterialTheme.typography.bodyLarge,color=ink,modifier=Modifier.weight(1f))
        if(value!=null) Text(value,style=WdType.caption,color=wd.textMuted,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=160.dp))
        if(!destructive) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(18.dp),tint=wd.textMuted)
    }
}

/** A row holding a label (and optional detail) beside a Whereday switch. */
@Composable internal fun WdToggleRow(label:String,checked:Boolean,onCheckedChange:(Boolean)->Unit,modifier:Modifier=Modifier,
    detail:String?=null,enabled:Boolean=true,divider:Boolean=true) {
    WdRow(modifier.toggleable(checked,enabled=enabled,role=Role.Switch,onValueChange=onCheckedChange),divider) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(label,style=MaterialTheme.typography.bodyLarge,color=if(enabled) wd.textPrimary else wd.textMuted)
            if(detail!=null) Text(detail,style=WdType.caption2,color=wd.textMuted)
        }
        Switch(checked,null,enabled=enabled,colors=wdSwitchColors())
    }
}

@Composable internal fun WdDivider(modifier:Modifier=Modifier) {
    HorizontalDivider(modifier,thickness=1.dp,color=wd.border)
}

/** TEToggleStyle: accent track on, border-colored track off, white thumb either way. */
@Composable internal fun wdSwitchColors()=SwitchDefaults.colors(
    checkedThumbColor=Color.White,checkedTrackColor=wd.accent,checkedBorderColor=wd.accent,checkedIconColor=wd.accent,
    uncheckedThumbColor=Color.White,uncheckedTrackColor=wd.border,uncheckedBorderColor=wd.textMuted.copy(alpha=0.35f),
    disabledCheckedTrackColor=wd.textMuted.copy(alpha=0.4f),disabledUncheckedTrackColor=wd.border.copy(alpha=0.5f),
    disabledCheckedThumbColor=Color.White.copy(alpha=0.8f),disabledUncheckedThumbColor=Color.White.copy(alpha=0.8f),
)

// MARK: Actions

internal enum class WdEmphasis {primary,secondary,destructive}

/** Material 3 buttons in Whereday ink: a filled pill for the primary action, outlined pills for the rest. */
@Composable internal fun WdButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier,emphasis:WdEmphasis=WdEmphasis.secondary,
    enabled:Boolean=true,icon:ImageVector?=null,trailingIcon:ImageVector?=null,busy:Boolean=false) {
    val interaction=remember {MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val ink=when {!enabled->wd.textMuted; emphasis==WdEmphasis.destructive->wd.inkRust; else->wd.accent}
    val filled=emphasis==WdEmphasis.primary && enabled
    val shape=CircleShape
    val pressTint=if(filled) Color.Black.copy(alpha=0.12f) else ink.copy(alpha=0.08f)
    Row(modifier.fillMaxWidth().heightIn(min=48.dp).clip(shape).then(if(filled) Modifier.background(ink) else Modifier)
        .then(if(pressed) Modifier.background(pressTint) else Modifier)
        .then(if(filled) Modifier else Modifier.border(1.dp,if(emphasis==WdEmphasis.destructive) ink else wd.outline.copy(alpha=if(enabled) 1f else 0.5f),shape))
        .clickable(interaction,null,enabled=enabled && !busy,role=Role.Button,onClick=onClick)
        .padding(horizontal=20.dp,vertical=12.dp),
        horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
        val content=if(filled) wd.surface else ink
        if(busy) CircularProgressIndicator(Modifier.size(18.dp),color=content,strokeWidth=2.dp)
        else if(icon!=null) Icon(icon,null,Modifier.size(18.dp),tint=content)
        if(busy || icon!=null) Spacer(Modifier.width(8.dp))
        Text(text,style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp),color=content,textAlign=TextAlign.Center)
        if(trailingIcon!=null) {Spacer(Modifier.width(6.dp));Icon(trailingIcon,null,Modifier.size(16.dp),tint=content)}
    }
}

/** A 48dp round control. Over maps and media it is a raised paper disc; [raised]=false gives the plain header icon button. */
@Composable internal fun WdCircleButton(icon:ImageVector,contentDescription:String?,onClick:()->Unit,modifier:Modifier=Modifier,
    enabled:Boolean=true,selected:Boolean=false,raised:Boolean=true) {
    val colors=wd
    val shape=CircleShape
    Box(modifier.size(48.dp).then(when {
            selected->Modifier.clip(shape).background(colors.secondaryContainer)
            raised->Modifier.wdFloating(shape,colors,3.dp,border=false)
            else->Modifier.clip(shape)
        }).clickable(enabled=enabled,role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center) {
        Icon(icon,contentDescription,Modifier.size(22.dp),tint=when {!enabled->colors.textMuted.copy(alpha=0.4f); selected->colors.onSecondaryContainer; raised->colors.textPrimary; else->colors.textMuted})
    }
}

/** A header icon button (transparent, on-surface-variant), as in the Today, Atlas and Places top bars. */
@Composable internal fun WdIconButton(icon:ImageVector,contentDescription:String?,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,tonal:Boolean=false) =
    WdCircleButton(icon,contentDescription,onClick,modifier,enabled,selected=tonal,raised=false)

/** The Material 3 extended FAB in secondary container: "Log a stop", "Add place", "Month recap". */
@Composable internal fun WdExtendedFab(text:String,icon:ImageVector,onClick:()->Unit,modifier:Modifier=Modifier,expanded:Boolean=true,primary:Boolean=false) {
    val colors=wd
    val shape=RoundedCornerShape(16.dp)
    val container=if(primary) colors.primaryContainer else colors.secondaryContainer
    val content=if(primary) colors.onPrimaryContainer else colors.onSecondaryContainer
    Row(modifier.heightIn(min=56.dp).then(if(expanded) Modifier else Modifier.widthIn(min=56.dp))
        .shadow(6.dp,shape,ambientColor=Color.Black.copy(alpha=0.2f),spotColor=Color.Black.copy(alpha=0.25f)).clip(shape).background(container)
        .clickable(role=Role.Button,onClick=onClick).padding(start=16.dp,end=if(expanded) 20.dp else 16.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp,Alignment.CenterHorizontally)) {
        Icon(icon,if(expanded) null else text,Modifier.size(24.dp),tint=content)
        if(expanded) Text(text,style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp),color=content,maxLines=1)
    }
}

/** A Material 3 filter chip: 8dp corners, outlined at rest, secondary container with a check when selected. */
@Composable internal fun WdChip(text:String,selected:Boolean,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,icon:ImageVector?=null) {
    val shape=RoundedCornerShape(8.dp)
    Row(modifier.heightIn(min=32.dp).clip(shape).then(if(selected) Modifier.background(wd.secondaryContainer) else Modifier.border(1.dp,wd.outline.copy(alpha=if(enabled) 1f else 0.5f),shape))
        .selectable(selected,enabled=enabled,role=Role.Tab,onClick=onClick).padding(start=if(selected || icon!=null) 8.dp else 16.dp,end=16.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        val ink=when {!enabled->wd.textMuted; selected->wd.onSecondaryContainer; else->wd.textPrimary}
        if(selected) Icon(Icons.Filled.Check,null,Modifier.size(18.dp),tint=ink)
        else if(icon!=null) Icon(icon,null,Modifier.size(18.dp),tint=ink)
        Text(text,style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp,fontWeight=FontWeight.Medium),color=ink,maxLines=1)
    }
}

/** A Material 3 segmented button: an outlined pill split into segments, the selected one in secondary container with a check. */
@Composable internal fun <T> WdSegmented(options:List<T>,selected:T,label:@Composable (T)->String,onSelect:(T)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    val shape=CircleShape
    val outline=wd.outline.copy(alpha=if(enabled) 1f else 0.5f)
    Row(modifier.fillMaxWidth().heightIn(min=40.dp).clip(shape).border(1.dp,outline,shape).height(IntrinsicSize.Min)) {
        options.forEachIndexed {index,option->
            val isSelected=option==selected
            if(index>0) Box(Modifier.width(1.dp).fillMaxHeight().background(outline))
            Row(Modifier.weight(1f).fillMaxHeight().heightIn(min=40.dp).background(if(isSelected) wd.secondaryContainer else Color.Transparent)
                .selectable(isSelected,enabled=enabled,role=Role.Tab) {onSelect(option)}.padding(horizontal=8.dp),
                horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                val ink=if(isSelected) wd.onSecondaryContainer else if(enabled) wd.textPrimary else wd.textMuted
                if(isSelected) {Icon(Icons.Filled.Check,null,Modifier.size(16.dp),tint=ink);Spacer(Modifier.width(4.dp))}
                Text(label(option),style=MaterialTheme.typography.labelLarge.copy(fontSize=14.sp,fontWeight=FontWeight.Medium),
                    color=ink,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}

// MARK: Status

/** The pulsing coral "you're here" dot. */
@Composable internal fun WdLiveDot(modifier:Modifier=Modifier,color:Color=wd.liveDot) {
    Box(modifier.size(20.dp),contentAlignment=Alignment.Center) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(color.copy(alpha=0.25f)))
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

/** The inverted "you're at" card: navy on paper, amber at night (primary role, 28dp corners). */
@Composable internal fun WdInkCard(modifier:Modifier=Modifier,content:@Composable RowScope.()->Unit) {
    val shape=RoundedCornerShape(28.dp)
    CompositionLocalProvider(LocalContentColor provides wd.onInkCard) {
        Row(modifier.fillMaxWidth().clip(shape).background(wd.inkCard).padding(start=20.dp,end=12.dp,top=16.dp,bottom=16.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp),content=content)
    }
}

/** A tracked uppercase badge for statuses such as LIVE, PAUSED or PRO. */
@Composable internal fun WdBadge(text:String,modifier:Modifier=Modifier,color:Color=wd.accent,filled:Boolean=false) {
    Text(text.uppercase(),style=WdType.caption2.copy(fontWeight=FontWeight.SemiBold,letterSpacing=0.12.em),
        color=if(filled) wd.surface else color,maxLines=1,
        modifier=modifier.clip(CircleShape).background(if(filled) color else color.copy(alpha=0.10f))
            .border(1.dp,color.copy(alpha=if(filled) 1f else 0.35f),CircleShape).padding(horizontal=8.dp,vertical=3.dp))
}

/** A notice band inside a page: info on the tertiary (amber) container, warnings on the secondary (rust) container. */
@Composable internal fun WdNotice(text:String,modifier:Modifier=Modifier,title:String?=null,icon:ImageVector?=null,warning:Boolean=false,
    action:(@Composable ()->Unit)?=null) {
    val container=if(warning) wd.secondaryContainer else wd.containerLow
    val content=if(warning) wd.onSecondaryContainer else wd.textPrimary
    val shape=RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(container).padding(start=20.dp,end=16.dp,top=16.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.Top) {
            if(icon!=null) Icon(icon,null,Modifier.size(20.dp),tint=if(warning) content else wd.accent)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(title!=null) Text(title,style=MaterialTheme.typography.titleMedium,color=content)
                Text(text,style=MaterialTheme.typography.bodyMedium,color=if(title!=null && !warning) wd.textMuted else content)
            }
        }
        CompositionLocalProvider(LocalContentColor provides content) {action?.invoke()}
    }
}

/** An empty page: a quiet glyph in a paper circle, a Newsreader title and muted guidance. */
@Composable internal fun WdEmptyState(title:String,message:String?,modifier:Modifier=Modifier,icon:ImageVector?=null,
    action:(@Composable ColumnScope.()->Unit)?=null) {
    Column(modifier.fillMaxWidth().padding(horizontal=32.dp,vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(icon!=null) Box(Modifier.size(64.dp).clip(CircleShape).background(wd.containerHigh),contentAlignment=Alignment.Center) {
            Icon(icon,null,Modifier.size(28.dp),tint=wd.accent)
        }
        Text(title,style=WdType.title2,color=wd.textPrimary,textAlign=TextAlign.Center,modifier=Modifier.semantics {heading()})
        if(message!=null) Text(message,style=MaterialTheme.typography.bodyMedium,color=wd.textMuted,textAlign=TextAlign.Center)
        if(action!=null) {Spacer(Modifier.height(6.dp));action()}
    }
}

/** A centered ink spinner with an optional mono caption, for loading pages. */
@Composable internal fun WdLoading(modifier:Modifier=Modifier,caption:String?=null) {
    Column(modifier.fillMaxWidth().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp),color=wd.accent,trackColor=wd.dialTrack,strokeWidth=2.5.dp)
        if(caption!=null) Text(caption.uppercase(),style=WdType.eyebrow,color=wd.textMuted,textAlign=TextAlign.Center)
    }
}

/** A headline figure: mono numeral over a tracked caption, as in Today's overview and Atlas. */
@Composable internal fun WdStat(value:String,label:String,modifier:Modifier=Modifier,ink:Color=wd.textPrimary) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(value,style=WdType.numeral,color=ink,maxLines=1)
        Text(label.uppercase(),style=WdType.caption2.copy(letterSpacing=0.12.em),color=wd.textMuted,maxLines=2)
    }
}

/** Outlined text field colors on paper. */
@Composable internal fun wdTextFieldColors()=OutlinedTextFieldDefaults.colors(
    focusedBorderColor=wd.accent,unfocusedBorderColor=wd.border,focusedContainerColor=wd.card,unfocusedContainerColor=wd.card,
    disabledContainerColor=wd.card,cursorColor=wd.accent,focusedLabelColor=wd.accent,unfocusedLabelColor=wd.textMuted,
    errorBorderColor=wd.inkRust,errorLabelColor=wd.inkRust,errorContainerColor=wd.card,
)

/** Shadowed floating surface used by map overlays and raised controls. */
internal fun Modifier.wdFloating(shape:Shape,colors:WdColors,elevation:Dp=12.dp,border:Boolean=true)=this
    .shadow(elevation,shape,ambientColor=colors.textPrimary.copy(alpha=0.10f),spotColor=colors.textPrimary.copy(alpha=0.20f))
    .clip(shape).background(if(colors.dark) colors.containerHigh else colors.card).then(if(border) Modifier.border(1.dp,colors.border,shape) else Modifier)

// MARK: Navigation

internal data class WdTab(val id:String,val title:String,val icon:ImageVector,val selectedIcon:ImageVector=icon)

/** The Material 3 navigation bar: an 80dp surface-container bar whose selected destination sits in a secondary-container pill. */
@Composable internal fun WdNavigationBar(tabs:List<WdTab>,selected:Int,onSelect:(Int)->Unit,modifier:Modifier=Modifier) {
    val iconOnly=LocalDensity.current.fontScale>=1.5f
    val colors=wd
    Row(modifier.fillMaxWidth().background(colors.container).navigationBarsPadding().heightIn(min=80.dp)) {
        tabs.forEachIndexed {index,tab->
            val isSelected=index==selected
            Column(Modifier.weight(1f).heightIn(min=80.dp).testTag("navigation-${tab.id}")
                .selectable(isSelected,role=Role.Tab) {onSelect(index)}.padding(vertical=12.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp,Alignment.CenterVertically)) {
                Box(Modifier.size(64.dp,32.dp).clip(CircleShape).background(if(isSelected) colors.secondaryContainer else Color.Transparent),contentAlignment=Alignment.Center) {
                    Icon(if(isSelected) tab.selectedIcon else tab.icon,if(iconOnly) tab.title else null,Modifier.size(24.dp),
                        tint=if(isSelected) colors.onSecondaryContainer else colors.textMuted)
                }
                if(!iconOnly) Text(tab.title,style=MaterialTheme.typography.labelMedium.copy(fontFamily=FontFamily.Default,fontSize=12.sp,lineHeight=16.sp,
                    fontWeight=if(isSelected) FontWeight.Bold else FontWeight.Medium),color=if(isSelected) colors.textPrimary else colors.textMuted,maxLines=1)
            }
        }
    }
}

/** The Material 3 navigation rail for medium and expanded windows: an optional FAB on top, destinations, and a footer action. */
@Composable internal fun WdNavigationRail(tabs:List<WdTab>,selected:Int,onSelect:(Int)->Unit,modifier:Modifier=Modifier,wide:Boolean=false,
    header:(@Composable ColumnScope.()->Unit)?=null,footer:(@Composable ColumnScope.()->Unit)?=null) {
    val colors=wd
    Column(modifier.width(if(wide) 96.dp else 80.dp).fillMaxHeight().systemBarsPadding().padding(vertical=20.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(header!=null) {header();Spacer(Modifier.height(16.dp))}
        tabs.forEachIndexed {index,tab->
            val isSelected=index==selected
            Column(Modifier.widthIn(min=56.dp).testTag("navigation-${tab.id}").selectable(isSelected,role=Role.Tab) {onSelect(index)}.padding(vertical=2.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(56.dp,32.dp).clip(CircleShape).background(if(isSelected) colors.secondaryContainer else Color.Transparent),contentAlignment=Alignment.Center) {
                    Icon(if(isSelected) tab.selectedIcon else tab.icon,null,Modifier.size(24.dp),tint=if(isSelected) colors.onSecondaryContainer else colors.textMuted)
                }
                Text(tab.title,style=MaterialTheme.typography.labelMedium.copy(fontFamily=FontFamily.Default,fontSize=12.sp,lineHeight=16.sp,
                    fontWeight=if(isSelected) FontWeight.Bold else FontWeight.Medium),color=if(isSelected) colors.textPrimary else colors.textMuted,maxLines=1)
            }
        }
        if(footer!=null) {Spacer(Modifier.weight(1f));footer()}
    }
}

/** A gradient that fades page content into paper above the floating tab bar. */
@Composable internal fun WdBottomFade(modifier:Modifier=Modifier,height:Dp=24.dp) {
    Box(modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(listOf(Color.Transparent,wd.surface))))
}


/** Ink toast matching iOS's export banner: a navy slab (amber at night) with paper text. */
@Composable internal fun WdSnackbar(data:SnackbarData,modifier:Modifier=Modifier) {
    Snackbar(data,modifier.padding(horizontal=16.dp,vertical=8.dp),shape=RoundedCornerShape(16.dp),containerColor=wd.inkCard,
        contentColor=wd.onInkCard,actionColor=wd.onInkCard,actionContentColor=wd.onInkCard,dismissActionContentColor=wd.onInkCardMuted)
}

/** One stay or move on the 24-hour dial, as fractions of the day from midnight. */
internal data class WdDialSegment(val start:Float,val end:Float,val ink:Color)

/** iOS's 24-hour day dial: a track ring, place-ink arcs for each stay and a coral "now" marker.
 *  Decorative only; the list beside it carries the same information for accessibility. */
@Composable internal fun WdDayDial(segments:List<WdDialSegment>,modifier:Modifier=Modifier,now:Float?=null,nowCaption:String?=null,
    centerText:String?=null,size:Dp=200.dp,hint:String?=null) {
    val track=wd.dialTrack;val tick=wd.dialTick;val card=wd.surface;val nowInk=if(wd.dark) wd.inkRust else Color(0xFFC2462A)
    Box(modifier.size(size).clearAndSetSemantics {},contentAlignment=Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val side=this.size.minDimension
            val stroke=side*0.07f
            val inset=side*0.06f+stroke/2
            val arcSize=androidx.compose.ui.geometry.Size(side-inset*2,side-inset*2)
            val topLeft=androidx.compose.ui.geometry.Offset(inset,inset)
            drawArc(track,0f,360f,false,topLeft,arcSize,style=androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            segments.forEach {segment->
                val sweep=((segment.end-segment.start).coerceAtLeast(0.004f))*360f
                drawArc(segment.ink,-90f+segment.start*360f,sweep,false,topLeft,arcSize,style=androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            }
            val center=this.center
            repeat(4) {quarter->
                val angle=Math.toRadians(quarter*90.0-90.0)
                val outer=side/2-side*0.01f;val inner=outer-side*0.03f
                drawLine(tick,androidx.compose.ui.geometry.Offset(center.x+(inner*kotlin.math.cos(angle)).toFloat(),center.y+(inner*kotlin.math.sin(angle)).toFloat()),
                    androidx.compose.ui.geometry.Offset(center.x+(outer*kotlin.math.cos(angle)).toFloat(),center.y+(outer*kotlin.math.sin(angle)).toFloat()),strokeWidth=1.dp.toPx())
            }
            if(now!=null) {
                val angle=Math.toRadians(now*360.0-90.0)
                val radius=arcSize.width/2
                val point=androidx.compose.ui.geometry.Offset(center.x+(radius*kotlin.math.cos(angle)).toFloat(),center.y+(radius*kotlin.math.sin(angle)).toFloat())
                drawCircle(card,stroke*0.62f,point)
                drawCircle(nowInk,stroke*0.62f,point,style=androidx.compose.ui.graphics.drawscope.Stroke(stroke*0.19f))
                drawCircle(nowInk,stroke*0.25f,point)
            }
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) {
            if(nowCaption!=null) Text(nowCaption.uppercase(),style=WdType.caption2.copy(letterSpacing=0.1.em),color=wd.textMuted)
            if(centerText!=null) Text(centerText,style=WdType.title.copy(fontWeight=FontWeight.Normal,fontSize=(size.value*0.2f).sp,lineHeight=(size.value*0.21f).sp,fontFeatureSettings="tnum"),color=wd.textPrimary)
            if(hint!=null) Text(hint,style=WdType.caption2.copy(fontSize=10.sp),color=wd.inkRust)
        }
    }
}
