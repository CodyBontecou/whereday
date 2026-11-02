package com.bontecou.whereday

import android.view.View
import android.widget.EditText
import android.widget.TimePicker
import androidx.core.view.isVisible

/** TimePicker saves a valid time, but omits its pending text and input mode. */
internal data class FrameworkClockInput(val textMode:Boolean,val hour:String,val minute:String) {
    fun saved()=listOf("1",textMode.toString(),hour,minute)
    companion object {
        fun restored(values:List<String>):FrameworkClockInput? = runCatching {
            require(values.size==4 && values[0]=="1")
            FrameworkClockInput(values[1].toBooleanStrict(),values[2],values[3])
        }.getOrNull()
    }
}

private fun TimePicker.part(name:String):View? = resources.getIdentifier(name,"id","android").takeIf {it!=0}?.let {findViewById(it)}

internal fun TimePicker.pendingInput():FrameworkClockInput? {
    val hour=part("input_hour") as? EditText ?: return null
    val minute=part("input_minute") as? EditText ?: return null
    val mode=part("input_mode") ?: return null
    return FrameworkClockInput(mode.isVisible,hour.text.toString(),minute.text.toString())
}

internal fun TimePicker.restorePendingInput(input:FrameworkClockInput?) {
    if(input==null) return
    val hour=part("input_hour") as? EditText ?: return
    val minute=part("input_minute") as? EditText ?: return
    val mode=part("input_mode") ?: return
    if(mode.isVisible!=input.textMode) part("toggle_mode")?.performClick()
    if(input.textMode) {hour.setText(input.hour);minute.setText(input.minute)}
}
