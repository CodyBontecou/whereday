package com.bontecou.whereday

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.bontecou.whereday.domain.Coordinate
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale

/** The map-first paper look: a full custom MapLibre style over OpenFreeMap vector tiles, with a night ink variant.
 *  Offline, the same style keeps only its paper background so the route still sits on paper. */
internal object MapStyles {
    private val cache=HashMap<Boolean,String>()
    fun json(context:Context,online:Boolean,dark:Boolean):String {
        val source=synchronized(cache) {cache.getOrPut(dark) {
            context.resources.openRawResource(if(dark) R.raw.map_style_night else R.raw.map_style_paper).bufferedReader().use {it.readText()}
        }}
        if(online) return source
        val style=JSONObject(source)
        val background=style.getJSONArray("layers").let {layers->(0 until layers.length()).map {layers.getJSONObject(it)}.first {it.getString("type")=="background"}}
        return JSONObject().put("version",8).put("name",style.getString("name")).put("sources",JSONObject()).put("layers",JSONArray().put(background)).toString()
    }
    /** The paper land colour behind every map, matching the style's background. */
    fun land(dark:Boolean)=if(dark) 0xFF111A2E.toInt() else 0xFFECE6D8.toInt()
}

/** A visit pin, count circle or numbered candidate drawn by the home map. [icon] names a label bitmap anchored at its left edge. */
internal data class MapHomeMarker(val id:String,val coordinate:Coordinate,val color:Int,val radius:Float,val opacity:Float=1f,val icon:String?=null,val centeredIcon:String?=null,val priority:Boolean=false)
internal data class MapHomeLine(val coordinates:List<Coordinate>,val color:Int,val width:Float=4f,val opacity:Float=1f,val halo:Boolean=true)

/** Everything the map-first presentation adds on top of the journal layers. Built in Compose; the renderer only publishes it. */
internal data class MapHomeRender(
    val lines:List<MapHomeLine> = emptyList(),
    val chips:List<Pair<Coordinate,String>> = emptyList(),
    val markers:List<MapHomeMarker> = emptyList(),
    val now:Coordinate?=null,
    val start:Coordinate?=null,
    val focus:Pair<Coordinate,Double>?=null,
    val images:Map<String,Bitmap> = emptyMap(),
    /** Replace the single-ink journal path with these coloured legs. */
    val replacesPath:Boolean=false,
    /** Ranges mark places by visit-days; start and end flags would only add noise. */
    val hidesEndpoints:Boolean=false,
) {
    companion object {
        const val lineSource="home-lines";const val chipSource="home-chips";const val markerSource="journal-visits"
        const val labelSource="home-labels";const val nowSource="home-now";const val startSource="home-start";const val focusSource="home-focus"
    }
}

internal object MapHomeLayers {
    fun install(style:Style,inks:MapInks) {
        listOf(MapHomeRender.lineSource,MapHomeRender.chipSource,MapHomeRender.labelSource,MapHomeRender.nowSource,MapHomeRender.startSource,MapHomeRender.focusSource).forEach {
            if(style.getSource(it)==null) style.addSource(GeoJsonSource(it,"""{"type":"FeatureCollection","features":[]}"""))
        }
        val below="journal-point"
        style.addLayerBelow(FillLayer("home-focus-area",MapHomeRender.focusSource).withProperties(fillColor(inks.highlight),fillOpacity(0.12f)),below)
        style.addLayerBelow(LineLayer("home-focus-ring",MapHomeRender.focusSource).withProperties(lineColor(inks.highlight),lineWidth(1.5f),lineDasharray(arrayOf(3f,3f))),below)
        style.addLayerBelow(LineLayer("home-line-halo",MapHomeRender.lineSource).withProperties(lineColor(inks.background),lineWidth(Expression.product(Expression.get("width"),Expression.literal(2.0))),
            lineOpacity(Expression.get("haloOpacity")),lineCap(Property.LINE_CAP_ROUND),lineJoin(Property.LINE_JOIN_ROUND)),below)
        style.addLayerBelow(LineLayer("home-lines",MapHomeRender.lineSource).withProperties(lineColor(Expression.toColor(Expression.get("color"))),lineWidth(Expression.get("width")),
            lineOpacity(Expression.get("opacity")),lineCap(Property.LINE_CAP_ROUND),lineJoin(Property.LINE_JOIN_ROUND)),below)
        // The now halo sits under the visit pins; labels, pills and numbers sit above them, below the replay playhead.
        style.addLayerBelow(CircleLayer("home-now-outer",MapHomeRender.nowSource).withProperties(circleColor(inks.live),circleOpacity(0.16f),circleRadius(22f)),"journal-visit")
        style.addLayerBelow(CircleLayer("home-now-inner",MapHomeRender.nowSource).withProperties(circleColor(inks.live),circleOpacity(0.22f),circleRadius(14f)),"journal-visit")
        val top="journal-replay-position"
        style.addLayerBelow(SymbolLayer("home-start",MapHomeRender.startSource).withProperties(iconImage("home-diamond"),iconAllowOverlap(true),iconIgnorePlacement(true)),top)
        style.addLayerBelow(SymbolLayer("home-chips",MapHomeRender.chipSource).withProperties(iconImage(Expression.get("icon")),iconAllowOverlap(true),iconIgnorePlacement(true)),top)
        style.addLayerBelow(SymbolLayer("home-labels",MapHomeRender.labelSource).withProperties(iconImage(Expression.get("icon")),iconAnchor(Property.ICON_ANCHOR_LEFT),
            iconOpacity(Expression.get("opacity"))).withFilter(Expression.eq(Expression.get("kind"),Expression.literal("label"))),top)
        style.addLayerBelow(SymbolLayer("home-priority-labels",MapHomeRender.labelSource).withProperties(iconImage(Expression.get("icon")),iconAnchor(Property.ICON_ANCHOR_LEFT),iconAllowOverlap(true),
            iconOpacity(Expression.get("opacity"))).withFilter(Expression.eq(Expression.get("kind"),Expression.literal("priority"))),top)
        style.addLayerBelow(SymbolLayer("home-numbers",MapHomeRender.labelSource).withProperties(iconImage(Expression.get("icon")),iconAllowOverlap(true),iconIgnorePlacement(true),
            iconOpacity(Expression.get("opacity"))).withFilter(Expression.eq(Expression.get("kind"),Expression.literal("number"))),top)
    }

    private fun point(c:Coordinate)=JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(c.longitude,c.latitude)))
    private fun feature(geometry:JSONObject,properties:JSONObject=JSONObject())=JSONObject().put("type","Feature").put("properties",properties).put("geometry",geometry)
    private fun collection(features:List<JSONObject>)=JSONObject().put("type","FeatureCollection").put("features",JSONArray(features)).toString()

    /** A geodesic circle as a polygon, for the dashed radius around a focused visit. */
    fun circle(center:Coordinate,radiusMeters:Double,steps:Int=64):JSONObject {
        val ring=JSONArray()
        val lat=Math.toRadians(center.latitude);val lon=Math.toRadians(center.longitude);val d=radiusMeters/6_371_008.8
        for(i in 0..steps) {
            val bearing=2*Math.PI*i/steps
            val lat2=Math.asin(Math.sin(lat)*Math.cos(d)+Math.cos(lat)*Math.sin(d)*Math.cos(bearing))
            val lon2=lon+Math.atan2(Math.sin(bearing)*Math.sin(d)*Math.cos(lat),Math.cos(d)-Math.sin(lat)*Math.sin(lat2))
            ring.put(JSONArray(listOf(Math.toDegrees(lon2),Math.toDegrees(lat2))))
        }
        return JSONObject().put("type","Polygon").put("coordinates",JSONArray().put(ring))
    }

    fun publish(style:Style,home:MapHomeRender?,inks:MapInks,loaded:MutableMap<String,Bitmap>) {
        val value=home ?: MapHomeRender()
        // Install bitmaps before the symbols that name them (MapLibre leaves a symbol empty if its sprite arrives later).
        val wanted=value.images
        loaded.keys.filter {it !in wanted}.toList().forEach {style.removeImage(it);loaded.remove(it)}
        wanted.forEach {(key,bitmap)->if(loaded[key]!==bitmap) {style.addImage(key,bitmap);loaded[key]=bitmap}}
        fun hex(color:Int)=String.format(Locale.ROOT,"#%06X",color and 0xFFFFFF)
        style.getSourceAs<GeoJsonSource>(MapHomeRender.lineSource)?.setGeoJson(collection(value.lines.filter {it.coordinates.size>1}.flatMap {line->
            com.bontecou.whereday.domain.MapLineGeometry.splitAtDateLine(line.coordinates).filter {it.size>1}.map {part->
                feature(JSONObject().put("type","LineString").put("coordinates",JSONArray(part.map {JSONArray(listOf(it.longitude,it.latitude))})),
                    JSONObject().put("color",hex(line.color)).put("width",line.width.toDouble()).put("opacity",line.opacity.toDouble()).put("haloOpacity",if(line.halo) line.opacity.toDouble() else 0.0))
            }
        }))
        style.getSourceAs<GeoJsonSource>(MapHomeRender.chipSource)?.setGeoJson(collection(value.chips.map {(c,icon)->feature(point(c),JSONObject().put("icon",icon))}))
        style.getSourceAs<GeoJsonSource>(MapHomeRender.labelSource)?.setGeoJson(collection(value.markers.flatMap {marker->
            listOfNotNull(
                marker.icon?.let {feature(point(marker.coordinate),JSONObject().put("icon",it).put("kind",if(marker.priority) "priority" else "label").put("opacity",marker.opacity.toDouble()))},
                marker.centeredIcon?.let {feature(point(marker.coordinate),JSONObject().put("icon",it).put("kind","number").put("opacity",marker.opacity.toDouble()))},
            )
        }))
        style.getSourceAs<GeoJsonSource>(MapHomeRender.nowSource)?.setGeoJson(collection(listOfNotNull(value.now?.let {feature(point(it))})))
        style.getSourceAs<GeoJsonSource>(MapHomeRender.startSource)?.setGeoJson(collection(listOfNotNull(value.start?.let {feature(point(it))})))
        style.getSourceAs<GeoJsonSource>(MapHomeRender.focusSource)?.setGeoJson(collection(listOfNotNull(value.focus?.let {(c,r)->feature(circle(c,r))})))
        val replaced=home?.replacesPath==true
        listOf("journal-path","journal-road-path").forEach {style.getLayer(it)?.setProperties(visibility(if(replaced) Property.NONE else Property.VISIBLE))}
        // Flags give way to the ink diamond when the home map marks where the range starts.
        style.getLayer("journal-start-flag")?.setProperties(visibility(if(home?.start!=null || home?.hidesEndpoints==true) Property.NONE else Property.VISIBLE))
        // Where you are now is the latest point; the pulsing now pin replaces the end flag.
        style.getLayer("journal-end-flag")?.setProperties(visibility(if(home?.now!=null || home?.hidesEndpoints==true) Property.NONE else Property.VISIBLE))
    }

    fun pulse(style:Style,scale:Float) {
        style.getLayerAs<CircleLayer>("home-now-outer")?.setProperties(circleRadius(22f*scale),circleOpacity(0.16f*(1.6f-scale).coerceIn(0.2f,1f)))
        style.getLayerAs<CircleLayer>("home-now-inner")?.setProperties(circleRadius(14f*(0.6f+scale*0.4f)))
    }
}

/** Draws the map's labels, time pills, numbers and diamond with the app's own Newsreader and IBM Plex Mono faces. */
internal class MapMarkerArt(private val context:Context,private val dark:Boolean) {
    private val scaleFactor=context.resources.displayMetrics.density
    private val serif:Typeface=ResourcesCompat.getFont(context,R.font.newsreader_italic) ?: Typeface.SERIF
    private val mono:Typeface=ResourcesCompat.getFont(context,R.font.plex_mono_regular) ?: Typeface.MONOSPACE
    private val monoBold:Typeface=ResourcesCompat.getFont(context,R.font.plex_mono_semibold) ?: Typeface.MONOSPACE
    private val ink=if(dark) 0xFFECE6D8.toInt() else 0xFF1E2B45.toInt()
    private val meta=if(dark) 0xFFC9CEDA.toInt() else 0xFF4E4C46.toInt()
    private val halo=MapStyles.land(dark)
    private val pill=if(dark) 0xEB18213A.toInt() else 0xEBFBF9F4.toInt()
    private val hairline=if(dark) 0xFF2C3754.toInt() else 0xFFD8D2C3.toInt()
    private val cache=HashMap<String,Bitmap>()
    private fun key(vararg parts:Any?)=parts.joinToString("|").hashCode().toUInt().toString(16)

    private fun bitmap(width:Float,height:Float,draw:Canvas.()->Unit):Bitmap {
        val bitmap=Bitmap.createBitmap((width*scaleFactor).toInt().coerceAtLeast(1),(height*scaleFactor).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        bitmap.density=(160*scaleFactor).toInt()
        Canvas(bitmap).apply {scale(scaleFactor,scaleFactor);draw()}
        return bitmap
    }
    private fun paint(face:Typeface,size:Float,color:Int)=Paint(Paint.ANTI_ALIAS_FLAG).apply {typeface=face;textSize=size;this.color=color}
    private fun Canvas.haloText(text:String,x:Float,y:Float,paint:Paint,width:Float) {
        val stroke=Paint(paint).apply {style=Paint.Style.STROKE;strokeWidth=width;color=halo;strokeJoin=Paint.Join.ROUND}
        drawText(text,x,y,stroke);drawText(text,x,y,paint)
    }

    /** "Ferry Building" in serif italic over "45 min" in mono, offset right of a pin of [pinRadius]. Returns its sprite name. */
    fun label(name:String,detail:String?,detailColor:Int?,pinRadius:Float,into:MutableMap<String,Bitmap>):String {
        val id="label-"+key(name,detail,detailColor,pinRadius,dark)
        into[id]=cache.getOrPut(id) {
            val title=paint(serif,15f,ink);val small=paint(mono,9f,detailColor ?: meta).apply {letterSpacing=0.04f}
            val gap=pinRadius+6f
            val width=gap+maxOf(title.measureText(name),detail?.let {small.measureText(it)} ?: 0f)+6f
            bitmap(width,34f) {
                haloText(name,gap,15f,title,4f)
                detail?.let {haloText(it,gap,28f,small,3f)}
            }
        }
        return id
    }
    /** The small mono time pill at the start of a main leg ("08:12"). */
    fun chip(text:String,into:MutableMap<String,Bitmap>):String {
        val id="chip-"+key(text,dark)
        into[id]=cache.getOrPut(id) {
            val label=paint(mono,9.5f,meta)
            val width=maxOf(40f,label.measureText(text)+14f)
            bitmap(width+2,19f) {
                val rect=RectF(1f,1f,width+1,18f)
                drawRoundRect(rect,8.5f,8.5f,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=pill})
                drawRoundRect(rect,8.5f,8.5f,Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=1f;color=hairline})
                drawText(text,1f+(width-label.measureText(text))/2,13f,label)
            }
        }
        return id
    }
    /** A number centred on a pin: visit-days on count circles, 1–3 on candidate pins. */
    fun number(value:String,color:Int,into:MutableMap<String,Bitmap>):String {
        val id="number-"+key(value,color)
        into[id]=cache.getOrPut(id) {
            val label=paint(monoBold,9.5f,color)
            val width=label.measureText(value)+2
            bitmap(width,13f) {drawText(value,1f,10f,label)}
        }
        return id
    }
    /** The small ink diamond marking where the range starts. */
    fun diamond(into:MutableMap<String,Bitmap>) {
        into["home-diamond"]=cache.getOrPut("home-diamond") {
            bitmap(16f,16f) {
                val path=android.graphics.Path().apply {moveTo(8f,1.5f);lineTo(14.5f,8f);lineTo(8f,14.5f);lineTo(1.5f,8f);close()}
                drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=if(dark) 0xFF18213A.toInt() else 0xFFFBF9F4.toInt()})
                val inner=android.graphics.Path().apply {moveTo(8f,3.5f);lineTo(12.5f,8f);lineTo(8f,12.5f);lineTo(3.5f,8f);close()}
                drawPath(inner,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=ink})
            }
        }
    }
}
