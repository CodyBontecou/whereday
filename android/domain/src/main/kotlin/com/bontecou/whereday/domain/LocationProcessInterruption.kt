package com.bontecou.whereday.domain

/** BOOT_COMPLETED alone cannot prove a reboot: Android 15 also sends it after force-stop. */
fun locationProcessInterruption(startedBootCount:Int?,currentBootCount:Int?):RecordingInterruption =
    if(startedBootCount!=null && startedBootCount>=0 && currentBootCount!=null && currentBootCount>=0 && startedBootCount!=currentBootCount)
        RecordingInterruption.phoneRestarted else RecordingInterruption.processInterrupted
