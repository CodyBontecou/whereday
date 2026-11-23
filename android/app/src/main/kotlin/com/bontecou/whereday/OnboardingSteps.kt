package com.bontecou.whereday

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bontecou.whereday.domain.RecordingPreset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Animations follow the system animator scale; "Remove animations" draws everything in place. */
private fun motionEnabled()=ValueAnimator.areAnimatorsEnabled()

@Composable private fun locale():Locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

@Composable private fun StepHeading(kicker:Int,title:Int) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(stringResource(kicker).uppercase(locale()),style=WdType.eyebrow.copy(letterSpacing=0.08.em),color=wd.inkRust)
        Text(stringResource(title),style=WdType.largeTitle.copy(fontWeight=FontWeight.Normal,lineHeight=38.sp),color=wd.textPrimary,modifier=Modifier.semantics {heading()})
    }
}

@Composable private fun Muted(text:String,modifier:Modifier=Modifier) {
    Text(text,modifier,style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
}

@Composable private fun PaperCard(modifier:Modifier=Modifier,padding:Dp=16.dp,content:@Composable ColumnScope.()->Unit) {
    val shape=RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(wd.card).border(1.dp,wd.border,shape).padding(padding),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}

@Composable private fun weekday(date:LocalDate)=DateTimeFormatter.ofPattern("EEE",locale()).format(date)
@Composable private fun monthDay(date:LocalDate):String {
    val locale=locale()
    return DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,"MMMd"),locale).format(date)
}

// MARK: 1 · cover

@Composable internal fun CoverStep(today:LocalDate) {
    val animate=remember {motionEnabled()}
    val first=remember {Animatable(if(animate) 0f else 1f)}
    val second=remember {Animatable(if(animate) 0f else 1f)}
    LaunchedEffect(Unit) {
        if(animate) {
            launch {first.animateTo(1f,tween(1600,easing=FastOutSlowInEasing))}
            launch {delay(500);second.animateTo(1f,tween(1600,easing=FastOutSlowInEasing))}
        }
    }
    val inks=wd.placeInks
    val segments=listOf(
        WdDialSegment(0f,0.342f*first.value,inks[0]),
        WdDialSegment(0.361f,0.361f+0.031f*second.value,inks[1]),
        WdDialSegment(0.41f,0.41f+0.08f*second.value,inks[2].copy(alpha=0.5f)),
    ).filter {it.end-it.start>0.002f}
    val day=monthDay(today)
    val description=stringResource(R.string.onboarding_cover_dial,"${weekday(today)} $day")
    Column(verticalArrangement=Arrangement.spacedBy(28.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(248.dp).testTag("onboarding-cover-dial").clearAndSetSemantics {contentDescription=description},contentAlignment=Alignment.Center) {
            WdDayDial(segments,size=248.dp,ring=0.085f)
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(weekday(today),style=WdType.largeTitle.copy(fontWeight=FontWeight.Normal,fontSize=40.sp,lineHeight=42.sp),color=wd.textPrimary)
                Text(day.uppercase(locale()),style=WdType.caption.copy(letterSpacing=0.06.em),color=wd.textMuted)
            }
        }
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.onboarding_cover_title),style=WdType.largeTitle.copy(fontWeight=FontWeight.Normal,fontSize=42.sp,lineHeight=44.sp),
                color=wd.textPrimary,modifier=Modifier.semantics {heading()})
            Text(stringResource(R.string.onboarding_cover_body),style=MaterialTheme.typography.bodyLarge,color=wd.textMuted)
        }
    }
}

// MARK: 2 · intent

private val OnboardingIntent.title get()=when(this) {
    OnboardingIntent.days->R.string.onboarding_intent_days;OnboardingIntent.countries->R.string.onboarding_intent_countries
    OnboardingIntent.routes->R.string.onboarding_intent_routes;OnboardingIntent.notes->R.string.onboarding_intent_notes
}
private val OnboardingIntent.summary get()=when(this) {
    OnboardingIntent.days->R.string.onboarding_intent_days_summary;OnboardingIntent.countries->R.string.onboarding_intent_countries_summary
    OnboardingIntent.routes->R.string.onboarding_intent_routes_summary;OnboardingIntent.notes->R.string.onboarding_intent_notes_summary
}
private val OnboardingIntent.icon:ImageVector get()=when(this) {
    OnboardingIntent.days->Icons.Outlined.CalendarMonth;OnboardingIntent.countries->Icons.Outlined.Public
    OnboardingIntent.routes->Icons.AutoMirrored.Outlined.DirectionsRun;OnboardingIntent.notes->Icons.Outlined.EditNote
}
/** Place inks in the design's order: navy days, rust countries, green routes, amber notes. */
private val OnboardingIntent.ink get()=when(this) {OnboardingIntent.days->0;OnboardingIntent.countries->1;OnboardingIntent.routes->3;OnboardingIntent.notes->2}

@Composable internal fun IntentStep(selected:Set<OnboardingIntent>,enabled:Boolean,toggle:(OnboardingIntent,Boolean)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        StepHeading(R.string.onboarding_intent_kicker,R.string.onboarding_intent_title)
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OnboardingIntent.entries.forEach {intent->
                val on=intent in selected
                val shape=RoundedCornerShape(16.dp)
                Row(Modifier.fillMaxWidth().clip(shape).background(wd.card).border(if(on) 2.dp else 1.dp,if(on) wd.accent else wd.border,shape)
                    .toggleable(on,enabled=enabled,role=Role.Checkbox) {toggle(intent,it)}.testTag("onboarding-intent-${intent.key}")
                    .padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(wd.placeInks[intent.ink]),contentAlignment=Alignment.Center) {
                        Icon(intent.icon,null,Modifier.size(22.dp),tint=wd.surface)
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(intent.title),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
                        Text(stringResource(intent.summary),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
                    }
                    if(on) Box(Modifier.size(22.dp).clip(CircleShape).background(wd.accent),contentAlignment=Alignment.Center) {Icon(Icons.Filled.Check,null,Modifier.size(15.dp),tint=wd.surface)}
                    else Box(Modifier.size(22.dp).border(1.5.dp,wd.dialTick,CircleShape))
                }
            }
        }
        Muted(stringResource(if(selected.isEmpty()) R.string.onboarding_intent_none else R.string.onboarding_intent_note))
    }
}

// MARK: 3 · scrub

@Composable private fun clockLabel(hour:Float):String {
    val minutes=(hour*60).roundToInt().coerceIn(0,24*60-1)
    val time=LocalTime.of(minutes/60,minutes%60)
    val clock24=android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    return DateTimeFormatter.ofPattern(if(clock24) "H:mm" else "h:mm a",locale()).format(time)
}

@Composable internal fun ScrubStep(hour:Float,onHour:(Float)->Unit) {
    val stop=OnboardingSampleDay.at(hour)
    val inks=wd.placeInks
    val segments=remember(inks) {OnboardingSampleDay.stops.mapNotNull {s->s.ink?.let {WdDialSegment(s.start/24f,s.end/24f,inks[it])}}}
    val time=clockLabel(hour)
    val place=stringResource(stop.name)
    val dialDescription=stringResource(R.string.onboarding_scrub_dial,stringResource(R.string.onboarding_scrub_value,time,place))
    Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
        StepHeading(R.string.onboarding_scrub_kicker,R.string.onboarding_scrub_title)
        Box(Modifier.align(Alignment.CenterHorizontally).size(252.dp).testTag("onboarding-scrub-dial").clearAndSetSemantics {contentDescription=dialDescription}) {
            WdDayDial(segments,Modifier.align(Alignment.Center),now=hour/24f,size=220.dp,ring=0.085f)
            Text(time,Modifier.align(Alignment.Center),style=WdType.numeral,color=wd.textPrimary)
            val label=WdType.caption2
            Text("0",Modifier.align(Alignment.TopCenter),style=label,color=wd.textMuted)
            Text("6",Modifier.align(Alignment.CenterEnd),style=label,color=wd.textMuted)
            Text("12",Modifier.align(Alignment.BottomCenter),style=label,color=wd.textMuted)
            Text("18",Modifier.align(Alignment.CenterStart),style=label,color=wd.textMuted)
        }
        WdInkCard {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(stop.ink?.let {inks[it]} ?: wd.dialTick).border(1.5.dp,wd.onInkCard,RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f)) {
                Text(place,style=WdType.title2.copy(fontWeight=FontWeight.Normal),color=wd.onInkCard)
                Text(stringResource(stop.detail),style=MaterialTheme.typography.bodySmall,color=wd.onInkCardMuted)
            }
        }
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            val label=stringResource(R.string.onboarding_scrub_label)
            Text(label.uppercase(locale()),style=WdType.caption2.copy(letterSpacing=0.06.em),color=wd.textMuted)
            val value=stringResource(R.string.onboarding_scrub_value,time,place)
            Slider(hour,{onHour((it*4).roundToInt()/4f)},Modifier.fillMaxWidth().testTag("onboarding-scrub").semantics {contentDescription=label;stateDescription=value},
                valueRange=0f..OnboardingSampleDay.LAST_HOUR,steps=(OnboardingSampleDay.LAST_HOUR*4).roundToInt()-1,
                colors=SliderDefaults.colors(thumbColor=wd.inkRust,activeTrackColor=wd.inkRust,inactiveTrackColor=wd.dialTrack,
                    activeTickColor=Color.Transparent,inactiveTickColor=Color.Transparent))
            Muted(stringResource(R.string.onboarding_scrub_hint))
        }
    }
}

// MARK: 4 · pen

private val RecordingPreset.penTag get()=when(this) {RecordingPreset.places->R.string.onboarding_pen_tag_places;RecordingPreset.routes->R.string.onboarding_pen_tag_routes;RecordingPreset.everyStep->R.string.onboarding_pen_tag_every_step}
private val RecordingPreset.penSummary get()=when(this) {RecordingPreset.places->R.string.onboarding_pen_places;RecordingPreset.routes->R.string.onboarding_pen_routes;RecordingPreset.everyStep->R.string.onboarding_pen_every_step}
private val RecordingPreset?.penPreview get()=when(this) {
    RecordingPreset.places->R.string.onboarding_pen_preview_places;RecordingPreset.routes->R.string.onboarding_pen_preview_routes
    RecordingPreset.everyStep->R.string.onboarding_pen_preview_every_step;null->R.string.onboarding_pen_preview_custom
}

@Composable internal fun PenStep(preset:RecordingPreset?,enabled:Boolean,choose:(RecordingPreset)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        StepHeading(R.string.onboarding_pen_kicker,R.string.onboarding_pen_title)
        val description=stringResource(preset.penPreview)
        PenPreview(preset,Modifier.fillMaxWidth().height(150.dp).testTag("onboarding-pen-preview").clearAndSetSemantics {contentDescription=description})
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            RecordingPreset.entries.forEach {option->
                val on=option==preset
                val shape=RoundedCornerShape(16.dp)
                Column(Modifier.fillMaxWidth().clip(shape).background(wd.card).border(if(on) 2.dp else 1.dp,if(on) wd.accent else wd.border,shape)
                    .selectableOption(on,enabled) {choose(option)}.testTag("onboarding-preset-${option.key}")
                    .padding(horizontal=16.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(stringResource(option.titleRes),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary,modifier=Modifier.weight(1f))
                        Text(stringResource(option.penTag).uppercase(locale()),style=WdType.caption2,color=wd.inkRust)
                    }
                    Text(stringResource(option.penSummary),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
                }
            }
        }
        if(preset==null) Text(stringResource(R.string.onboarding_preset_custom_kept),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
    }
}

private fun Modifier.selectableOption(selected:Boolean,enabled:Boolean,onClick:()->Unit)=
    this.then(Modifier.semantics {this.selected=selected}).then(Modifier.toggleable(selected,enabled=enabled,role=Role.RadioButton) {onClick()})

/** The design's 342×150 route sketch, stretched to the card. */
@Composable private fun PenPreview(preset:RecordingPreset?,modifier:Modifier) {
    val street=wd.dialTrack;val ink=wd.accent;val inks=wd.placeInks
    val shape=RoundedCornerShape(16.dp)
    Canvas(modifier.clip(shape).background(wd.card).border(1.dp,wd.border,shape)) {
        val sx=size.width/342f;val sy=size.height/150f
        fun p(x:Float,y:Float)=Offset(x*sx,y*sy)
        listOf(40f,104f).forEach {y->drawLine(street,p(0f,y),p(342f,y),strokeWidth=10.dp.toPx()*0.9f)}
        listOf(70f,180f,286f).forEach {x->drawLine(street,p(x,0f),p(x,150f),strokeWidth=10.dp.toPx()*0.9f)}
        when(preset) {
            RecordingPreset.places->drawPath(Path().apply {moveTo(40*sx,120*sy);lineTo(180*sx,104*sy);lineTo(292*sx,30*sy)},ink,
                style=Stroke(2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),6.dp.toPx()))))
            RecordingPreset.routes,null->drawPath(Path().apply {
                moveTo(40*sx,120*sy);lineTo(70*sx,104*sy);lineTo(180*sx,104*sy);lineTo(180*sx,40*sy);lineTo(286*sx,40*sy);lineTo(292*sx,30*sy)
            },ink,style=Stroke(3.dp.toPx(),join=StrokeJoin.Round))
            RecordingPreset.everyStep->{
                drawPath(Path().apply {
                    moveTo(40*sx,120*sy);cubicTo(52*sx,114*sy,62*sx,108*sy,70*sx,104*sy);cubicTo(100*sx,106*sy,140*sx,101*sy,180*sx,104*sy)
                    cubicTo(183*sx,84*sy,177*sx,60*sy,180*sx,40*sy);cubicTo(214*sx,43*sy,250*sx,37*sy,286*sx,40*sy)
                    cubicTo(289*sx,36*sy,290*sx,33*sy,292*sx,30*sy)
                },ink,style=Stroke(2.5.dp.toPx(),cap=StrokeCap.Round))
                listOf(55f to 112f,90f to 105f,120f to 103f,150f to 105f,181f to 88f,179f to 66f,200f to 42f,230f to 39f,260f to 41f).forEach {(x,y)->drawCircle(ink,3.dp.toPx(),p(x,y))}
            }
        }
        drawCircle(inks[0],9.dp.toPx(),p(40f,120f));drawCircle(inks[1],9.dp.toPx(),p(180f,104f));drawCircle(inks[2],9.dp.toPx(),p(292f,30f))
    }
}

// MARK: 5 · permission

@Composable internal fun PermissionStep() {
    val inks=wd.placeInks
    Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
        StepHeading(R.string.onboarding_perm_kicker,R.string.onboarding_perm_title)
        val comparison=stringResource(R.string.onboarding_perm_compare)
        PaperCard(Modifier.testTag("onboarding-perm-compare").clearAndSetSemantics {contentDescription=comparison},padding=16.dp) {
            ComparisonBar(R.string.onboarding_perm_allowed,R.string.onboarding_perm_allowed_detail,null,listOf(35f to inks[0],4f to inks[1],31f to inks[2],6f to inks[3],18f to inks[0]))
            ComparisonBar(R.string.onboarding_perm_denied,R.string.onboarding_perm_denied_detail,wd.dialTrack,listOf(36f to null,3f to inks[1],40f to null,2f to inks[3],19f to null))
        }
        Column {
            listOf(R.string.onboarding_privacy_account,R.string.onboarding_privacy_server,R.string.onboarding_privacy_export).forEachIndexed {index,text->
                WdDivider()
                Row(Modifier.fillMaxWidth().padding(vertical=11.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("%02d".format(index+1),Modifier.width(24.dp),style=WdType.caption,color=wd.inkRust)
                    Text(stringResource(text),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),color=wd.textPrimary)
                }
            }
            WdDivider()
        }
        Text(stringResource(R.string.onboarding_perm_note),style=WdType.caption2.copy(lineHeight=17.sp),color=wd.textMuted)
    }
}

@Composable private fun ComparisonBar(label:Int,detail:Int,track:Color?,parts:List<Pair<Float,Color?>>) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(label),style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary,modifier=Modifier.weight(1f))
            Text(stringResource(detail),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
        }
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).then(if(track!=null) Modifier.background(track) else Modifier),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
            parts.forEach {(weight,color)->Box(Modifier.weight(weight).fillMaxHeight().then(if(color!=null) Modifier.background(color) else Modifier))}
        }
    }
}

// MARK: 6 · stamp

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun StampStep(fix:OnboardingFix?,locating:Boolean,available:Boolean,chosen:OnboardingPlaceName?,enabled:Boolean,choose:(OnboardingPlaceName)->Unit) {
    val locale=locale()
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        StepHeading(R.string.onboarding_stamp_kicker,R.string.onboarding_stamp_title)
        val coordinates=fix?.let {
            val degrees=java.text.NumberFormat.getNumberInstance(locale).apply {minimumFractionDigits=4;maximumFractionDigits=4}
            stringResource(R.string.onboarding_stamp_coordinates,degrees.format(it.latitude),degrees.format(it.longitude),
                java.text.NumberFormat.getIntegerInstance(locale).format(it.accuracyMeters.roundToInt()))
        }
        val mapDescription=coordinates?.let {stringResource(R.string.onboarding_stamp_map,it)} ?: stringResource(R.string.onboarding_stamp_map_empty)
        Box(Modifier.fillMaxWidth().height(210.dp).testTag("onboarding-stamp-map").clearAndSetSemantics {contentDescription=mapDescription}) {
            StampSketch(fix!=null,Modifier.matchParentSize())
            if(coordinates!=null) Text(coordinates,Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(wd.card.copy(alpha=0.92f)).padding(horizontal=8.dp,vertical=4.dp),
                style=WdType.caption2,color=wd.textPrimary)
        }
        when {
            fix!=null->Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.onboarding_stamp_question),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
                FlowRow(Modifier.selectableGroup(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    OnboardingPlaceName.entries.forEach {name->
                        WdChip(stringResource(name.label),name==chosen,{choose(name)},Modifier.heightIn(min=44.dp).testTag("onboarding-place-${name.key}"),enabled=enabled)
                    }
                }
                Muted(stringResource(when(chosen) {null->R.string.onboarding_stamp_note;OnboardingPlaceName.other->R.string.onboarding_stamp_note_other;else->R.string.onboarding_stamp_note_named}))
            }
            locating->Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp),color=wd.accent,trackColor=wd.dialTrack,strokeWidth=2.dp)
                Muted(stringResource(R.string.onboarding_stamp_locating))
            }
            !available || !locating->Muted(stringResource(R.string.onboarding_stamp_unavailable),Modifier.testTag("onboarding-stamp-unavailable"))
        }
    }
}

/** A quiet street sketch with the "you're here" pulse. Real coordinates are printed over it, not plotted on tiles. */
@Composable private fun StampSketch(located:Boolean,modifier:Modifier) {
    val street=wd.card;val block=wd.dialTrack;val pin=wd.inkRust;val paper=wd.surface
    val animate=remember {motionEnabled()}
    val pulse=if(animate && located) rememberInfiniteTransition(label="stamp").animateFloat(0f,1f,infiniteRepeatable(tween(2000,easing=LinearOutSlowInEasing)),label="pulse").value else 0f
    val shape=RoundedCornerShape(16.dp)
    Canvas(modifier.clip(shape).background(wd.containerHigh).border(1.dp,wd.border,shape)) {
        val sx=size.width/342f;val sy=size.height/210f
        fun p(x:Float,y:Float)=Offset(x*sx,y*sy)
        val width=12.dp.toPx()
        drawLine(street,p(-10f,60f),p(352f,82f),width);drawLine(street,p(-10f,160f),p(352f,140f),width)
        drawLine(street,p(90f,-10f),p(120f,220f),width);drawLine(street,p(250f,-10f),p(232f,220f),width)
        drawPath(Path().apply {moveTo(0f,118*sy);cubicTo(80*sx,108*sy,140*sx,130*sy,352*sx,110*sy)},street,style=Stroke(width))
        drawRoundRect(block,p(132f,92f),androidx.compose.ui.geometry.Size(88*sx,40*sy),CornerRadius(4.dp.toPx()))
        if(located) {
            val center=p(171f,104f)
            if(animate) drawCircle(pin.copy(alpha=0.6f*(1f-pulse)),16.dp.toPx()*(0.4f+1.8f*pulse),center)
            drawCircle(pin.copy(alpha=0.1f),40.dp.toPx(),center)
            drawCircle(pin.copy(alpha=0.4f),40.dp.toPx(),center,style=Stroke(1.dp.toPx()))
            drawCircle(paper,12.dp.toPx(),center);drawCircle(pin,9.dp.toPx(),center)
        }
    }
}

// MARK: 7 · backfill

@Composable internal fun BackfillStep(today:LocalDate,counts:Map<LocalDate,Int>,available:Boolean,enabled:Boolean,running:Boolean,denied:Boolean,error:Boolean,
    onToggle:(Boolean)->Unit,onImport:(()->Unit)?) {
    val days=remember(today) {OnboardingHeatmap.days(today)}
    val found=if(enabled) OnboardingHeatmap.foundDays(counts,today) else 0
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        StepHeading(R.string.onboarding_backfill_kicker,R.string.onboarding_backfill_title)
        Text(stringResource(R.string.onboarding_backfill_body),style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),color=wd.textMuted)
        val summary=if(enabled) stringResource(R.string.onboarding_backfill_heatmap_found,found) else stringResource(R.string.onboarding_backfill_heatmap_today)
        PaperCard(Modifier.testTag("onboarding-heatmap").clearAndSetSemantics {contentDescription=summary}) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.onboarding_backfill_weeks).uppercase(locale()),style=WdType.caption2.copy(letterSpacing=0.06.em),color=wd.textMuted,modifier=Modifier.weight(1f))
                Text(when {enabled && running && found==0->stringResource(R.string.onboarding_backfill_scanning);enabled->stringResource(R.string.onboarding_backfill_found,found);else->stringResource(R.string.onboarding_backfill_today)},
                    style=WdType.caption.copy(fontWeight=FontWeight.Medium),color=wd.textPrimary)
            }
            val levels=listOf(wd.dialTrack,wd.accent.copy(alpha=0.32f),wd.accent.copy(alpha=0.62f),wd.accent)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                repeat(OnboardingHeatmap.DAYS/7) {column->
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        repeat(7) {row->
                            val day=days[column*7+row]
                            val color=if(day==today) wd.inkRust else if(enabled) levels[OnboardingHeatmap.level(counts[day] ?: 0)] else levels[0]
                            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(3.dp)).background(color))
                        }
                    }
                }
            }
        }
        if(available) WdCard(radius=16.dp) {
            WdToggleRow(stringResource(R.string.onboarding_backfill_toggle),enabled,onToggle,Modifier.testTag("onboarding-photos-toggle"),
                detail=stringResource(R.string.onboarding_backfill_toggle_detail),divider=false)
        }
        if(denied && !enabled) Muted(stringResource(R.string.onboarding_backfill_denied))
        if(error) Text(stringResource(R.string.onboarding_backfill_error),style=MaterialTheme.typography.bodyMedium,color=wd.inkRust)
        if(onImport!=null) TextButton(onImport,Modifier.heightIn(min=48.dp).testTag("onboarding-backfill-import"),colors=ButtonDefaults.textButtonColors(contentColor=wd.inkRust),contentPadding=PaddingValues(0.dp)) {
            Text(stringResource(R.string.onboarding_backfill_import),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.Medium))
        }
    }
}

// MARK: 8 · glance

@Composable internal fun GlanceStep(place:String?) {
    val title=place?.let {stringResource(R.string.onboarding_glance_at,it)} ?: stringResource(R.string.onboarding_glance_recording)
    val detail=stringResource(R.string.onboarding_glance_detail)
    val description=stringResource(R.string.onboarding_glance_illustration,title,detail)
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        StepHeading(R.string.onboarding_glance_kicker,R.string.onboarding_glance_title)
        val outer=RoundedCornerShape(22.dp)
        Column(Modifier.fillMaxWidth().clip(outer).background(wd.inkCard).padding(horizontal=14.dp,vertical=18.dp).testTag("onboarding-glance-notification")
            .clearAndSetSemantics {contentDescription=description},verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.onboarding_glance_caption).uppercase(locale()),Modifier.fillMaxWidth(),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.08.em),
                color=wd.onInkCardMuted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            val inner=RoundedCornerShape(18.dp)
            Column(Modifier.fillMaxWidth().clip(inner).background(wd.onInkCard.copy(alpha=0.08f)).padding(horizontal=16.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.MyLocation,null,Modifier.size(14.dp),tint=wd.liveDot)
                    Text("${stringResource(R.string.onboarding_brand)} · ${stringResource(R.string.onboarding_glance_since)}",style=WdType.caption2,color=wd.onInkCardMuted)
                }
                Text(title,style=WdType.title3.copy(fontWeight=FontWeight.Normal),color=wd.onInkCard)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(wd.onInkCard.copy(alpha=0.16f))) {
                    Box(Modifier.fillMaxWidth(0.62f).fillMaxHeight().background(wd.liveDot))
                }
                Text(detail,style=WdType.caption2,color=wd.onInkCardMuted)
                Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                    Text(stringResource(R.string.onboarding_glance_stop),style=MaterialTheme.typography.labelLarge,color=wd.onInkCard)
                    Text(stringResource(R.string.onboarding_glance_log),style=MaterialTheme.typography.labelLarge,color=wd.onInkCard)
                }
            }
        }
        PaperCard {
            Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(wd.containerHigh),contentAlignment=Alignment.Center) {
                    Icon(Icons.Outlined.NotificationsActive,null,Modifier.size(22.dp),tint=wd.accent)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.onboarding_glance_card_title),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
                    Text(stringResource(R.string.onboarding_glance_card_body),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
                }
            }
        }
    }
}

// MARK: 9 · export

private val OnboardingFormat.label get()=when(this) {OnboardingFormat.markdown->R.string.onboarding_format_markdown;OnboardingFormat.gpx->R.string.onboarding_format_gpx;OnboardingFormat.csv->R.string.onboarding_format_csv;OnboardingFormat.json->R.string.onboarding_format_json}
private val OnboardingFormat.file get()=when(this) {OnboardingFormat.markdown->R.string.onboarding_sample_file_markdown;OnboardingFormat.gpx->R.string.onboarding_sample_file_gpx;OnboardingFormat.csv->R.string.onboarding_sample_file_csv;OnboardingFormat.json->R.string.onboarding_sample_file_json}
private val OnboardingFormat.body get()=when(this) {OnboardingFormat.markdown->R.string.onboarding_sample_body_markdown;OnboardingFormat.gpx->R.string.onboarding_sample_body_gpx;OnboardingFormat.csv->R.string.onboarding_sample_body_csv;OnboardingFormat.json->R.string.onboarding_sample_body_json}
private val OnboardingFormat.note get()=when(this) {OnboardingFormat.markdown->R.string.onboarding_export_note_markdown;OnboardingFormat.gpx->R.string.onboarding_export_note_gpx;OnboardingFormat.csv->R.string.onboarding_export_note_csv;OnboardingFormat.json->R.string.onboarding_export_note_json}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ExportStep(format:OnboardingFormat,choose:(OnboardingFormat)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        StepHeading(R.string.onboarding_export_kicker,R.string.onboarding_export_title)
        val group=stringResource(R.string.onboarding_export_formats)
        FlowRow(Modifier.selectableGroup().semantics {contentDescription=group},horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OnboardingFormat.entries.forEach {option->
                WdChip(stringResource(option.label),option==format,{choose(option)},Modifier.heightIn(min=44.dp).testTag("onboarding-format-${option.key}"))
            }
        }
        val shape=RoundedCornerShape(16.dp)
        val file=stringResource(format.file);val body=stringResource(format.body)
        val preview=stringResource(R.string.onboarding_export_preview,stringResource(format.label),file)
        Column(Modifier.fillMaxWidth().clip(shape).background(wd.card).border(1.dp,wd.border,shape).testTag("onboarding-export-preview").semantics(mergeDescendants=true) {contentDescription=preview}) {
            Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(file,style=WdType.caption.copy(fontWeight=FontWeight.Medium),color=wd.textPrimary,modifier=Modifier.weight(1f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(stringResource(R.string.onboarding_export_sample).uppercase(locale()),style=WdType.caption2,color=wd.textMuted)
            }
            WdDivider()
            Text(body,Modifier.fillMaxWidth().heightIn(min=128.dp).padding(14.dp),style=WdType.caption.copy(lineHeight=19.sp),color=wd.textPrimary)
        }
        Muted("${stringResource(format.note)} ${stringResource(R.string.onboarding_export_note)}")
    }
}

// MARK: 10 · tonight

@Composable internal fun TonightStep(today:LocalDate,place:String?,stamped:Boolean,canStart:Boolean,startImmediately:Boolean,busy:Boolean,onStartChange:(Boolean)->Unit,
    nightly:Boolean,nightlyAvailable:Boolean,tomorrow:Boolean,onNightlyChange:(Boolean)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
        StepHeading(R.string.onboarding_tonight_kicker,R.string.onboarding_tonight_title)
        val dashed=wd.dialTick
        val name=place ?: stringResource(R.string.onboarding_tonight_first_place)
        val line=if(stamped) stringResource(R.string.onboarding_tonight_place,name) else name
        val dateTitle="${weekday(today)}, ${monthDay(today)}"
        val draft=stringResource(R.string.onboarding_tonight_draft,dateTitle,line)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(wd.card).drawBehind {
                drawRoundRect(dashed,cornerRadius=CornerRadius(16.dp.toPx()),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(),4.dp.toPx()))))
            }.padding(20.dp).testTag("onboarding-draft").clearAndSetSemantics {contentDescription=draft},verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(dateTitle,style=WdType.title2.copy(fontWeight=FontWeight.Normal,fontSize=24.sp),color=wd.textPrimary,modifier=Modifier.weight(1f))
                Text(stringResource(R.string.onboarding_tonight_drafting).uppercase(locale()),style=WdType.caption2,color=wd.inkRust)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(wd.placeInks[0]))
                Text(line,style=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp),color=wd.textPrimary)
            }
            listOf(170.dp,120.dp).forEach {width->
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).drawBehind {drawCircle(dashed,style=Stroke(1.5.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(),2.dp.toPx()))))})
                    Box(Modifier.widthIn(max=width).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(wd.dialTrack))
                }
            }
            Text(stringResource(R.string.onboarding_tonight_fill),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
        }
        WdCard(radius=16.dp) {
            WdRow(divider=canStart) {
                Icon(if(canStart) Icons.Outlined.NearMe else Icons.Outlined.LocationOff,null,Modifier.size(20.dp),tint=if(canStart) wd.accent else wd.textMuted)
                Text(stringResource(if(canStart) R.string.onboarding_location_allowed else R.string.onboarding_location_missing),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary,modifier=Modifier.weight(1f))
                Icon(if(canStart) Icons.Outlined.CheckCircle else Icons.AutoMirrored.Outlined.HelpOutline,null,Modifier.size(18.dp),tint=if(canStart) wd.success else wd.textMuted)
            }
            if(canStart) {
                val label=stringResource(R.string.onboarding_start_now)
                WdRow(divider=false) {
                    Icon(Icons.Outlined.FiberManualRecord,null,Modifier.size(20.dp),tint=if(startImmediately) wd.liveDot else wd.textMuted)
                    Text(label,style=MaterialTheme.typography.bodyLarge,color=if(busy) wd.textMuted else wd.textPrimary,modifier=Modifier.weight(1f))
                    Switch(startImmediately,onStartChange,enabled=!busy,colors=wdSwitchColors(),modifier=Modifier.testTag("onboarding-start-now").semantics {contentDescription=label})
                }
            }
        }
        if(!canStart) Muted(stringResource(R.string.onboarding_allow_later))
        val time=DateTimeFormatter.ofPattern(if(android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "H:mm" else "h:mm a",locale())
            .format(com.bontecou.whereday.platform.NightlyPageReminder.at)
        WdCard(radius=16.dp) {
            WdToggleRow(stringResource(if(tomorrow) R.string.onboarding_tonight_toggle_tomorrow else R.string.onboarding_tonight_toggle),nightly,onNightlyChange,
                Modifier.testTag("onboarding-nightly-toggle"),enabled=nightlyAvailable && !busy,divider=false,
                detail=stringResource(if(tomorrow) R.string.onboarding_tonight_toggle_detail_tomorrow else R.string.onboarding_tonight_toggle_detail,time))
        }
        if(!nightlyAvailable) Muted(stringResource(R.string.onboarding_tonight_notifications_off))
    }
}
