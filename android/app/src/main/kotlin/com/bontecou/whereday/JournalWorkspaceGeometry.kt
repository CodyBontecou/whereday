package com.bontecou.whereday

import kotlin.math.ceil
import kotlin.math.max

internal data class WorkspaceRect(val left:Int,val top:Int,val right:Int,val bottom:Int) {
    init {require(right>=left && bottom>=top)}
    val width:Int get()=(right.toLong()-left).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val height:Int get()=(bottom.toLong()-top).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val area:Long get()=width.toLong()*height
}
internal enum class WorkspaceFoldAxis { vertical,horizontal }
internal data class WorkspaceFold(val bounds:WorkspaceRect,val axis:WorkspaceFoldAxis,val separating:Boolean,val occluding:Boolean=false,val halfOpened:Boolean=false)
internal data class WorkspaceArrangement(val primary:WorkspaceRect?,val companion:WorkspaceRect?=null,val safeRegions:List<WorkspaceRect> =listOfNotNull(primary,companion)) {
    val paired:Boolean get()=companion!=null
}

/** Window-local pixels only. Pane and chrome content must both live inside these rectangles. */
internal object JournalWorkspaceGeometry {
    fun arrange(width:Int,height:Int,density:Float,folds:List<WorkspaceFold>,rtl:Boolean=false,allowCompanion:Boolean=true):WorkspaceArrangement {
        require(width>=0 && height>=0 && density.isFinite() && density>0)
        if(width==0 || height==0) return WorkspaceArrangement(null)
        val gap=ceil(24.0*density).toInt().coerceAtLeast(1)
        // Tabletop (a separating horizontal hinge: half-open, or a dual screen) pairs shorter panes: the map above the fold, Today below it.
        val tabletop=folds.any {it.axis==WorkspaceFoldAxis.horizontal && (it.halfOpened || it.separating)}
        val minWidth=ceil(360.0*density).toInt();val minHeight=ceil((if(tabletop) 240.0 else 360.0)*density).toInt()
        var regions=listOf(WorkspaceRect(0,0,width,height))
        for(fold in folds.filter {it.separating || it.occluding}.sortedWith(compareBy({it.bounds.left},{it.bounds.top},{it.axis.ordinal}))) {
            regions=regions.flatMap {region->
                val b=fold.bounds
                if(fold.axis==WorkspaceFoldAxis.vertical) {
                    if(b.right<region.left || b.left>region.right || b.bottom<=region.top || b.top>=region.bottom) listOf(region)
                    else {
                        val extra=max(0,gap-b.width)
                        val start=(b.left.toLong()-extra/2).coerceIn(region.left.toLong(),region.right.toLong()).toInt()
                        val end=(b.right.toLong()+extra-extra/2).coerceIn(region.left.toLong(),region.right.toLong()).toInt()
                        listOfNotNull(if(start>region.left) region.copy(right=start) else null,if(end<region.right) region.copy(left=end) else null)
                    }
                } else {
                    if(b.bottom<region.top || b.top>region.bottom || b.right<=region.left || b.left>=region.right) listOf(region)
                    else {
                        val extra=max(0,gap-b.height)
                        val start=(b.top.toLong()-extra/2).coerceIn(region.top.toLong(),region.bottom.toLong()).toInt()
                        val end=(b.bottom.toLong()+extra-extra/2).coerceIn(region.top.toLong(),region.bottom.toLong()).toInt()
                        listOfNotNull(if(start>region.top) region.copy(bottom=start) else null,if(end<region.bottom) region.copy(top=end) else null)
                    }
                }
            }
        }
        val order=compareBy<WorkspaceRect>({it.top},{if(rtl) -it.right.toLong() else it.left.toLong()})
        val usable=regions.filter {it.width>=minWidth && it.height>=minHeight}.sortedByDescending {it.area}.take(2).sortedWith(order)
        if(allowCompanion && usable.size==2) return WorkspaceArrangement(usable[0],usable[1],regions.sortedWith(order))
        val primary=regions.sortedWith(compareByDescending<WorkspaceRect> {it.area}.then(order)).firstOrNull() ?: return WorkspaceArrangement(null)
        if(allowCompanion && regions.size==1 && primary.width>=ceil(840.0*density).toInt() && primary.height>=minHeight) {
            val firstWidth=(primary.width-gap)/2
            val left=primary.copy(right=primary.left+firstWidth)
            val right=primary.copy(left=left.right+gap)
            return if(rtl) WorkspaceArrangement(right,left) else WorkspaceArrangement(left,right)
        }
        return WorkspaceArrangement(primary,safeRegions=regions.sortedWith(order))
    }
}
