package com.bontecou.whereday

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** Diagnostic branch only. No sleeps, queries, UI state, or per-frame logging. */
internal class InputPinProbe {
    private val next=AtomicInteger()
    private val times=LongArray(256)
    private val events=arrayOfNulls<String>(256)
    private val values=IntArray(256)
    fun record(event:String,value:Int=0) {
        val slot=next.getAndIncrement()
        if(slot>=times.size) return
        times[slot]=SystemClock.elapsedRealtimeNanos();events[slot]=event;values[slot]=value
    }
    fun dump() {
        val total=next.get();val count=minOf(total,times.size)
        println("[DEBUG-input-pin] retained=$count dropped=${(total-count).coerceAtLeast(0)}")
        for(i in 0 until count) println("[DEBUG-input-pin] slot=$i at=${times[i]} event=${events[i]} value=${values[i]}")
    }
}
