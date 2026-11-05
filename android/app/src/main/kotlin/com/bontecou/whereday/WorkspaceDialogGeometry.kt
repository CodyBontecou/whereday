package com.bontecou.whereday

/** Keep the owner when usable; an IME-obscured modal may borrow another known safe region. */
internal object WorkspaceDialogGeometry {
    fun choose(preferred:WorkspaceRect,regions:List<WorkspaceRect>,viewport:WorkspaceRect,minimumWidth:Int,minimumHeight:Int):WorkspaceRect? {
        require(minimumWidth>0 && minimumHeight>0)
        fun clipped(rect:WorkspaceRect):WorkspaceRect? {
            val left=maxOf(rect.left,viewport.left);val top=maxOf(rect.top,viewport.top)
            val right=minOf(rect.right,viewport.right);val bottom=minOf(rect.bottom,viewport.bottom)
            return if(right>left && bottom>top) WorkspaceRect(left,top,right,bottom) else null
        }
        val owner=clipped(preferred)
        fun usable(rect:WorkspaceRect)=rect.width>=minimumWidth && rect.height>=minimumHeight
        if(owner!=null && usable(owner)) return owner
        val candidates=regions.mapNotNull(::clipped).distinct()
        return candidates.filter(::usable).maxByOrNull {it.area} ?: owner ?: candidates.maxByOrNull {it.area}
    }
}
