package com.bontecou.whereday.domain

/** Source-bound presentation only; point markers, statistics and replay progress retain raw fixes. */
data class RoadMapGeometry(val raw:List<List<Coordinate>>,val roads:List<List<Coordinate>>,val fit:List<Coordinate>,val prepared:Boolean) {
    companion object {
        const val maximumGeometryCoordinates=100_000
        fun build(source:List<TrackPoint>,raw:List<List<TrackPoint>>,layers:MapLayers,route:RoadRoute?,
            replay:ReplayFrame?=null,fallback:Boolean=false,straightSegments:Boolean=true):RoadMapGeometry {
            val base=if(layers.path) raw.map {line->line.map {it.coordinate}} else emptyList()
            fun original()=RoadMapGeometry(base,emptyList(),emptyList(),false)
            if(!layers.path || route==null || !route.hasSnappedSegments || source.size>RoadSnapping.maximumSourcePoints) return original()
            val selected=source.filter {layers.outliers || !it.outlier}.ifEmpty {if(fallback) source else emptyList()}.sortedWith(compareBy({it.time},{it.id}))
            if(selected.size<2 || route.sourcePointCount!=selected.size || route.fingerprint!=RoadSnapping.fingerprint(selected)) return original()
            // Public domain values can be constructed outside the builder. Reject malformed/stale
            // index spans before reading or allocating their geometry.
            if(route.segments.sumOf {it.coordinates.size.toLong()}>maximumGeometryCoordinates) return original()
            var previous=0
            for(segment in route.segments) {
                if(segment.startIndex!=previous || segment.endIndex>=selected.size ||
                    segment.coordinates.first()!=selected[segment.startIndex].coordinate || segment.coordinates.last()!=selected[segment.endIndex].coordinate ||
                    (!segment.snapped && segment.coordinates.size!=segment.endIndex-segment.startIndex+1)) return original()
                previous=segment.endIndex
            }
            if(previous!=selected.lastIndex) return original()
            // Replay indexes refer to complete disk history, while source may be a 2,500-point
            // sample. Clip by the repository's (instant, ID) order rather than equating indexes.
            val end=if(replay==null) selected.lastIndex else selected.indexOfLast {point->
                point.time<replay.current.time || (point.time==replay.current.time && point.id<=replay.current.id)
            }
            val shown=route.segmentsThrough(end).filter {it.snapped}.map {it.coordinates}
            return RoadMapGeometry(if(straightSegments) base else emptyList(),shown,
                route.segments.filter {it.snapped}.flatMap {it.coordinates},true)
        }
    }
}
