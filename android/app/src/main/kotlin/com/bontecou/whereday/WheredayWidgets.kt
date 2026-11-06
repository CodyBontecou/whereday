package com.bontecou.whereday

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.bontecou.whereday.domain.HistoryRange
import com.bontecou.whereday.domain.Visit
import com.bontecou.whereday.platform.AppGraph
import com.bontecou.whereday.platform.RecordingRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One stay on the widget's 24-hour strip or dial, as fractions of the day. */
internal data class WidgetStay(val start:Float,val end:Float,val ink:Int)

/** What every Whereday widget shows: read once per update from the local journal, never from the network. */
internal data class WidgetSnapshot(
    val day:LocalDate,val stays:List<WidgetStay>,val here:String?,val hereSince:Instant?,val places:Int,val distanceMeters:Double,
    val metric:Boolean,val recording:Boolean,val recordingSince:Instant?,val now:Float,
) {
    companion object {
        val inksLight=intArrayOf(0xFF1E2B45.toInt(),0xFFC2462A.toInt(),0xFFB7862B.toInt(),0xFF5E7F5A.toInt(),0xFF7A86A8.toInt())
        val inksDark=intArrayOf(0xFFECE6D8.toInt(),0xFFF0805F.toInt(),0xFFE0B565.toInt(),0xFF9DC097.toInt(),0xFF8FA6D9.toInt())

        /** Pure assembly so the place/ink/fraction rules are unit tested without Android. */
        fun of(day:LocalDate,zone:ZoneId,visits:List<Visit>,distanceMeters:Double,metric:Boolean,recording:Boolean,recordingSince:Instant?,now:Instant,dark:Boolean):WidgetSnapshot {
            val start=day.atStartOfDay(zone).toInstant()
            fun fraction(at:Instant)=(Duration.between(start,at).toMillis()/86_400_000f).coerceIn(0f,1f)
            val inks=if(dark) inksDark else inksLight
            val order=journalInkIndex(visits.sortedBy {it.arrivedAt}.map {it.name?.takeIf {n->n.isNotBlank()} ?: it.id})
            val stays=visits.sortedBy {it.arrivedAt}.map {visit->
                WidgetStay(fraction(visit.arrivedAt),fraction(visit.departedAt ?: now),inks[(order[visit.name?.takeIf {it.isNotBlank()} ?: visit.id] ?: 0)%inks.size])
            }
            val open=visits.filter {it.departedAt==null}.maxByOrNull {it.arrivedAt}
            val places=visits.map {it.name?.takeIf {n->n.isNotBlank()} ?: it.id}.distinct().size
            return WidgetSnapshot(day,stays,open?.name?.takeIf {it.isNotBlank()},open?.arrivedAt,places,distanceMeters,metric,recording,recordingSince,fraction(now))
        }

        suspend fun load(context:Context):WidgetSnapshot {
            val graph=AppGraph.from(context)
            val zone=ZoneId.systemDefault()
            val now=Instant.now()
            val day=now.atZone(zone).toLocalDate()
            val dark=(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES
            val window=withTimeoutOrNull(8_000) {graph.history.observeWindow(HistoryRange.day(day,zone)).first()}
            val metric=withTimeoutOrNull(2_000) {graph.preferences.settings.first().metric} ?: true
            val active=RecordingRuntime.active.value
            val since=if(active) window?.sessions?.firstOrNull {it.endedAt==null}?.startedAt else null
            return of(day,zone,window?.visits.orEmpty(),window?.summary?.distanceMeters ?: 0.0,metric,active,since,now,dark)
        }
    }
}

/** Draws the widgets' 24-hour dial and strip into bitmaps; RemoteViews cannot host Compose. */
internal object WidgetArt {
    private fun dark(context:Context)=(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES
    fun dial(context:Context,snapshot:WidgetSnapshot,sizePx:Int,stroke:Float=0.1f):Bitmap {
        val bitmap=Bitmap.createBitmap(sizePx,sizePx,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val width=sizePx*stroke
        val inset=width/2+sizePx*0.02f
        val oval=RectF(inset,inset,sizePx-inset,sizePx-inset)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=width}
        paint.color=if(dark(context)) 0xFF222C46.toInt() else 0xFFE4DED0.toInt()
        canvas.drawArc(oval,0f,360f,false,paint)
        snapshot.stays.forEach {stay->
            paint.color=stay.ink
            canvas.drawArc(oval,-90f+stay.start*360f,((stay.end-stay.start).coerceAtLeast(0.004f))*360f,false,paint)
        }
        val angle=Math.toRadians(snapshot.now*360.0-90.0)
        val radius=oval.width()/2
        val cx=sizePx/2f+(radius*Math.cos(angle)).toFloat();val cy=sizePx/2f+(radius*Math.sin(angle)).toFloat()
        val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.FILL;color=if(dark(context)) 0xFF0F1626.toInt() else 0xFFF3EFE6.toInt()}
        canvas.drawCircle(cx,cy,width*0.62f,fill)
        val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=width*0.2f;color=0xFFC2462A.toInt()}
        canvas.drawCircle(cx,cy,width*0.62f,ring)
        return bitmap
    }
    /** The last 30 days as squares inked by each day's leading country (Atlas order), grey for no record. */
    fun squares(context:Context,atlas:AtlasYear,today:LocalDate,widthPx:Int,heightPx:Int):Bitmap {
        val bitmap=Bitmap.createBitmap(widthPx,heightPx,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val inks=if(dark(context)) WidgetSnapshot.inksDark else WidgetSnapshot.inksLight
        val order=atlas.countries.map {it.code}
        val step=widthPx/30f;val gap=step*0.2f;val radius=step*0.15f
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        for(i in 0 until 30) {
            val day=today.minusDays((29-i).toLong())
            val codes=atlas.byDay[day].orEmpty().map {it.countryCode}
            val leading=codes.minByOrNull {order.indexOf(it).let {index->if(index<0) Int.MAX_VALUE else index}}
            paint.color=if(leading==null) (if(dark(context)) 0xFF2C3754.toInt() else 0xFFDCD5C5.toInt()) else inks[order.indexOf(leading).coerceAtLeast(0)%inks.size]
            canvas.drawRoundRect(RectF(i*step,0f,i*step+step-gap,heightPx.toFloat()),radius,radius,paint)
        }
        return bitmap
    }
    fun strip(context:Context,snapshot:WidgetSnapshot,widthPx:Int,heightPx:Int):Bitmap {
        val bitmap=Bitmap.createBitmap(widthPx,heightPx,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val radius=heightPx/2f
        val track=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=if(dark(context)) 0xFF222C46.toInt() else 0xFFE4DED0.toInt()}
        canvas.drawRoundRect(RectF(0f,0f,widthPx.toFloat(),heightPx.toFloat()),radius,radius,track)
        canvas.save()
        val clip=android.graphics.Path().apply {addRoundRect(RectF(0f,0f,widthPx.toFloat(),heightPx.toFloat()),radius,radius,android.graphics.Path.Direction.CW)}
        canvas.clipPath(clip)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        snapshot.stays.forEach {stay->
            paint.color=stay.ink
            canvas.drawRect(stay.start*widthPx,0f,maxOf(stay.end*widthPx,stay.start*widthPx+2f),heightPx.toFloat(),paint)
        }
        canvas.restore()
        val now=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=0xFFC2462A.toInt()}
        val x=snapshot.now*widthPx
        canvas.drawRect(x-2f,0f,x+2f,heightPx.toFloat(),now)
        return bitmap
    }
}

internal object WheredayWidgets {
    const val REFRESH="com.bontecou.whereday.widgets.REFRESH"
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val providers=listOf(TodayWidget::class.java,DialWidget::class.java,RecordingWidget::class.java,AtlasWidget::class.java)

    /** Re-render every placed widget; cheap when none are placed. */
    fun refresh(context:Context) {
        val manager=AppWidgetManager.getInstance(context) ?: return
        val placed=providers.associateWith {manager.getAppWidgetIds(ComponentName(context,it))}.filterValues {it.isNotEmpty()}
        if(placed.isEmpty()) return
        scope.launch {
            val snapshot=try {WidgetSnapshot.load(context)} catch(_:Exception) {null}
            val atlas=if(AtlasWidget::class.java in placed) loadAtlas(context) else null
            placed.forEach {(provider,ids)->ids.forEach {id->runCatching {manager.updateAppWidget(id,if(provider==AtlasWidget::class.java) atlas(context,atlas) else render(context,provider,snapshot))}}}
        }
    }

    fun render(context:Context,provider:Class<*>,snapshot:WidgetSnapshot?):RemoteViews = when(provider) {
        DialWidget::class.java->dial(context,snapshot)
        RecordingWidget::class.java->recording(context,snapshot)
        else->today(context,snapshot)
    }

    /** This year's country days for the Atlas widget; null when the journal cannot be read in time. */
    suspend fun loadAtlas(context:Context):AtlasYear? = try {
        val today=LocalDate.now()
        withTimeoutOrNull(8_000) {
            AppGraph.from(context).history.observeCountryDays(com.bontecou.whereday.domain.CountryDayBounds(LocalDate.of(today.year,1,1),LocalDate.of(today.year,12,31))).first()
        }?.let {AtlasYear.of(today.year,it.days,today)}
    } catch(_:Exception) {null}

    /** 4×1: the last 30 days as country-inked squares beside the year's country and day counts. */
    fun atlas(context:Context,atlas:AtlasYear?):RemoteViews {
        val views=RemoteViews(context.packageName,R.layout.widget_atlas)
        views.setOnClickPendingIntent(R.id.widget_root,launch(context,null,15))
        val locale=context.resources.configuration.locales[0] ?: Locale.getDefault()
        val number=java.text.NumberFormat.getIntegerInstance(locale)
        val countries=atlas?.countries?.size ?: 0
        val days=atlas?.byDay?.size ?: 0
        views.setTextViewText(R.id.widget_countries,context.resources.getQuantityString(R.plurals.widget_countries,countries,number.format(countries)))
        views.setTextViewText(R.id.widget_days,context.resources.getQuantityString(R.plurals.widget_days_recorded,days,number.format(days),(atlas?.year ?: LocalDate.now().year).toString()))
        if(atlas!=null) views.setImageViewBitmap(R.id.widget_squares,WidgetArt.squares(context,atlas,LocalDate.now(),(30*8*density(context)).toInt(),(14*density(context)).toInt()))
        return views
    }

    private fun launch(context:Context,action:LauncherAction?,request:Int):PendingIntent {
        val intent=Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if(action!=null) intent.action=action.action
        return PendingIntent.getActivity(context,request,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun clock(instant:Instant)=DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(ZoneId.systemDefault()))
    private fun density(context:Context)=context.resources.displayMetrics.density

    private fun today(context:Context,snapshot:WidgetSnapshot?):RemoteViews {
        val views=RemoteViews(context.packageName,R.layout.widget_today)
        views.setOnClickPendingIntent(R.id.widget_root,launch(context,LauncherAction.timeline,10))
        val locale=context.resources.configuration.locales[0] ?: Locale.getDefault()
        if(snapshot==null) {views.setTextViewText(R.id.widget_place,context.getString(R.string.widget_no_stop));return views}
        views.setTextViewText(R.id.widget_place,snapshot.here ?: context.getString(if(snapshot.recording) R.string.widget_recording else R.string.widget_no_stop))
        views.setTextViewText(R.id.widget_since,snapshot.hereSince?.let {context.getString(R.string.widget_since,clock(it)).uppercase(locale)} ?: "")
        views.setImageViewBitmap(R.id.widget_strip,WidgetArt.strip(context,snapshot,(280*density(context)).toInt(),(14*density(context)).toInt()))
        views.setTextViewText(R.id.widget_stat_places,java.text.NumberFormat.getIntegerInstance(locale).format(snapshot.places))
        views.setTextViewText(R.id.widget_stat_places_label,context.getString(R.string.widget_places).uppercase(locale))
        views.setTextViewText(R.id.widget_stat_moved,todayDistance(snapshot.distanceMeters,snapshot.metric,locale))
        views.setTextViewText(R.id.widget_stat_moved_label,context.getString(R.string.widget_moved).uppercase(locale))
        views.setTextViewText(R.id.widget_stat_day,context.getString(R.string.widget_day,snapshot.day.dayOfYear))
        views.setTextViewText(R.id.widget_stat_day_label,context.getString(R.string.widget_recorded).uppercase(locale))
        return views
    }

    private fun dial(context:Context,snapshot:WidgetSnapshot?):RemoteViews {
        val views=RemoteViews(context.packageName,R.layout.widget_dial)
        views.setOnClickPendingIntent(R.id.widget_root,launch(context,LauncherAction.timeline,11))
        val locale=context.resources.configuration.locales[0] ?: Locale.getDefault()
        if(snapshot!=null) views.setImageViewBitmap(R.id.widget_dial,WidgetArt.dial(context,snapshot,(136*density(context)).toInt()))
        views.setTextViewText(R.id.widget_place,(snapshot?.here ?: "").uppercase(locale))
        views.setViewVisibility(R.id.widget_place,if(snapshot?.here==null) View.GONE else View.VISIBLE)
        return views
    }

    private fun recording(context:Context,snapshot:WidgetSnapshot?):RemoteViews {
        val views=RemoteViews(context.packageName,R.layout.widget_recording)
        val locale=context.resources.configuration.locales[0] ?: Locale.getDefault()
        val active=snapshot?.recording==true
        views.setOnClickPendingIntent(R.id.widget_root,launch(context,null,12))
        views.setTextViewText(R.id.widget_status,context.getString(if(active) R.string.widget_recording else R.string.widget_not_recording).uppercase(locale))
        views.setViewVisibility(R.id.widget_live_dot,if(active) View.VISIBLE else View.GONE)
        val since=snapshot?.recordingSince
        if(active && since!=null) {
            views.setChronometer(R.id.widget_elapsed,SystemClock.elapsedRealtime()-Duration.between(since,Instant.now()).toMillis().coerceAtLeast(0),null,true)
            views.setViewVisibility(R.id.widget_elapsed,View.VISIBLE)
        } else views.setViewVisibility(R.id.widget_elapsed,View.GONE)
        views.setTextViewText(R.id.widget_mode,context.getString(R.string.widget_mode))
        // Start/Stop open Whereday's own confirmation, exactly like the launcher shortcuts; no permission-free recording command.
        views.setTextViewText(R.id.widget_action,context.getString(if(active) R.string.widget_stop else R.string.widget_start))
        views.setOnClickPendingIntent(R.id.widget_action,launch(context,if(active) LauncherAction.stop else LauncherAction.start,if(active) 13 else 14))
        return views
    }
}

/** Shared update path: render from one snapshot, off the main thread. */
abstract class WheredayWidgetProvider:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snapshot=try {WidgetSnapshot.load(context)} catch(_:Exception) {null}
                val atlas=if(this@WheredayWidgetProvider is AtlasWidget) WheredayWidgets.loadAtlas(context) else null
                ids.forEach {id->runCatching {manager.updateAppWidget(id,if(atlas!=null || this@WheredayWidgetProvider is AtlasWidget) WheredayWidgets.atlas(context,atlas)
                    else WheredayWidgets.render(context,this@WheredayWidgetProvider.javaClass,snapshot))}}
            } finally {pending.finish()}
        }
    }
}

/** 4×2: where you are, since when, the day as a strip, and its figures. */
class TodayWidget:WheredayWidgetProvider()
/** 2×2: the day dial with a live clock. */
class DialWidget:WheredayWidgetProvider()
/** 2×2: recording status, elapsed time and Start/Stop. */
class RecordingWidget:WheredayWidgetProvider()
/** 4×1: the last 30 days by country and the year's counts. */
class AtlasWidget:WheredayWidgetProvider()

/** Package-private refresh signal from the recording service and the app. */
class WidgetRefreshReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action==WheredayWidgets.REFRESH || intent.action==Intent.ACTION_TIME_CHANGED || intent.action==Intent.ACTION_TIMEZONE_CHANGED) WheredayWidgets.refresh(context)
    }
}
