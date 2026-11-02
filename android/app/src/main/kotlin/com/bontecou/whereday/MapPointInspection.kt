package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bontecou.whereday.domain.TrackPoint
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/** Stable nearest-center hit selection in a 48 dp square; invalid projections never capture taps. */
internal fun pointHitIndex(x:Double,y:Double,centers:List<Pair<Double,Double>>):Int? {
    if(!x.isFinite() || !y.isFinite()) return null
    var index:Int?=null;var distance=Double.POSITIVE_INFINITY
    centers.forEachIndexed {i,(cx,cy)->
        val dx=cx-x;val dy=cy-y
        if(dx.isFinite() && dy.isFinite() && abs(dx)<=24 && abs(dy)<=24) {
            val candidate=dx*dx+dy*dy
            if(candidate<distance) {distance=candidate;index=i}
        }
    }
    return index
}
internal fun pointDistance(meters:Double,metric:Boolean,locale:Locale):String {
    val (value,unit)=if(metric) {if(meters>=1000) meters/1000 to "km" else meters to "m"}
        else {if(meters/1609.344<0.1) meters*3.28084 to "ft" else meters/1609.344 to "mi"}
    val digits=if(unit=="km" || unit=="mi") 1 else 0
    return NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=digits;minimumFractionDigits=0}.format(if(digits==0) sign(value)*floor(abs(value)+0.5) else value)+" "+unit
}
internal fun pointTimestamp(point:TrackPoint,locale:Locale,zone:ZoneId=ZoneId.systemDefault()):String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(point.time)

@Composable internal fun MapPointInspection(point:TrackPoint,metric:Boolean,dismiss:()->Unit) {
    val locale=LocalConfiguration.current.locales[0]
    Surface(Modifier.fillMaxWidth().testTag("map-point-inspection"),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.map_point_title),style=MaterialTheme.typography.titleMedium)
            SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(pointTimestamp(point,locale),Modifier.testTag("map-point-time"),style=MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.map_point_coordinates,String.format(locale,"%.5f, %.5f",point.coordinate.latitude,point.coordinate.longitude)),Modifier.testTag("map-point-coordinates"))
                if(point.accuracy.isFinite()) Text(stringResource(R.string.map_point_accuracy,pointDistance(point.accuracy,metric,locale)),Modifier.testTag("map-point-accuracy"))
                point.altitude?.takeIf {it.isFinite()}?.let {Text(stringResource(R.string.map_point_altitude,pointDistance(it,metric,locale)),Modifier.testTag("map-point-altitude"))}
                point.speed?.takeIf {it.isFinite()}?.let {speed->
                    val value=String.format(locale,"%.1f",speed*if(metric) 3.6 else 2.23693629)
                    Text(stringResource(if(metric) R.string.map_point_speed_metric else R.string.map_point_speed_imperial,value),Modifier.testTag("map-point-speed"))
                }
                if(point.outlier) Text(stringResource(R.string.map_point_outlier),Modifier.testTag("map-point-outlier"),color=MaterialTheme.colorScheme.error)
            }}
            TextButton(onClick=dismiss,modifier=Modifier.testTag("map-point-close")) {Text(stringResource(R.string.map_point_close))}
        }
    }
}
