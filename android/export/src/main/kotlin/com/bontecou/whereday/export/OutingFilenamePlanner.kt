package com.bontecou.whereday.export

/** Split-outing filename suffix and collision fallback from IsoMeExportKitAdapter. */
object OutingFilenamePlanner {
    fun acrossFormats(path:String,format:ExportFormat,used:MutableSet<String>):String {
        if(used.add(path)) return path
        val stem=if(path.endsWith(".${format.extension}",true)) path.dropLast(format.extension.length+1) else path
        val token=if(format==ExportFormat.markdown) "md" else format.name
        var candidate="$stem-$token.${format.extension}";var count=2
        while(!used.add(candidate)) {candidate="$stem-$token-${count++}.${format.extension}"}
        return candidate
    }
    fun path(pattern:String,format:ExportFormat,outing:ExportableOuting,options:ExportOptions,used:MutableSet<String>):String {
        val date=outing.startedAt.atZone(options.zone).toLocalDate()
        var path=FilenamePlanner.path(pattern,format,date,type="outings",title=outing.title,now=outing.startedAt,zone=options.zone)
        fun stem(value:String)=if(value.endsWith(".${format.extension}",true)) value.dropLast(format.extension.length+1) else value
        if("{title}" !in pattern && "{name}" !in pattern) {
            // Reuse token sanitization to keep a title from introducing a path component.
            val title=FilenamePlanner.path("{title}",format,date,title=outing.title).removeSuffix(".${format.extension}")
            val directory=path.substringBeforeLast('/',"").let {if(it.isEmpty()) "" else "$it/"}
            path=directory+FilenamePlanner.path("{title}",format,date,title=stem(path.substringAfterLast('/'))+" - $title")
        }
        if(used.add(path)) return path
        val directory=path.substringBeforeLast('/',"").let {if(it.isEmpty()) "" else "$it/"}
        val basename=stem(path.substringAfterLast('/'))
        var next="$directory${date}_$basename.${format.extension}";var counter=2
        while(!used.add(next)) {next="$directory${date}_${basename}_${counter++}.${format.extension}"}
        return next
    }
}
