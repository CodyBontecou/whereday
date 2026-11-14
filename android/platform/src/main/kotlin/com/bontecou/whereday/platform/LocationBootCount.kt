package com.bontecou.whereday.platform

import android.content.Context
import android.provider.Settings

/** Missing/inaccessible evidence yields a generic interruption rather than a reboot claim. */
internal fun locationBootCount(context:Context):Int? = runCatching {
    Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1).takeIf {it>=0}
}.getOrNull()
