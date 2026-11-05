package com.bontecou.whereday

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.saveable.listSaver
import com.bontecou.whereday.domain.ExportSchedule
import com.bontecou.whereday.export.DataKind
import com.bontecou.whereday.export.ExportFormat
import com.bontecou.whereday.export.ScheduledFileMode
import com.bontecou.whereday.platform.AutomaticExportSettings
import java.util.UUID
import java.time.LocalTime

/** Configuration recreation retains a draft; an ordinary background stop dismisses it. */
internal tailrec fun Context.activityConfigurationChange():Boolean = when(this) {
    is Activity->isChangingConfigurations
    is ContextWrapper->if(baseContext===this) false else baseContext.activityConfigurationChange()
    else->false
}

internal data class ExportClockDraft(val expected:LocalTime,val id:String=UUID.randomUUID().toString()) {
    fun saved()=listOf("1",id,expected.toString())
    companion object {
        fun restored(values:List<String>):ExportClockDraft? = runCatching {
            require(values.size==3 && values[0]=="1" && values[1].isNotBlank())
            ExportClockDraft(LocalTime.parse(values[2]),values[1])
        }.getOrNull()
        val Saver=listSaver<ExportClockDraft?,String>(save={it?.saved().orEmpty()},restore=::restored)
    }
}

/** Save data, never the approval closure or its old Activity ownership. */
internal data class AutomaticAnchorDraft(val expected:AutomaticExportSettings,val id:String=UUID.randomUUID().toString()) {
    fun saved():List<String> = expected.let {listOf("1",id,it.enabled.toString(),it.schedule.hour.toString(),it.schedule.minute.toString(),it.schedule.intervalHours.toString(),it.format.name,it.kind.name,it.fileMode.name,it.outings.toString())}
    companion object {
        fun restored(values:List<String>):AutomaticAnchorDraft? = runCatching {
            require(values.size==10 && values[0]=="1" && values[1].isNotBlank())
            AutomaticAnchorDraft(AutomaticExportSettings(values[2].toBooleanStrict(),ExportSchedule(values[3].toInt(),values[4].toInt(),values[5].toInt()),ExportFormat.valueOf(values[6]),DataKind.valueOf(values[7]),ScheduledFileMode.valueOf(values[8]),values[9].toBooleanStrict()),values[1])
        }.getOrNull()
        val Saver=listSaver<AutomaticAnchorDraft?,String>(save={it?.saved().orEmpty()},restore=::restored)
    }
}
