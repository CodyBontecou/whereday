package com.bontecou.whereday

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/** These public Intents request foreground UI, never permission-free recording commands. */
internal enum class LauncherAction(val id:String,val action:String,val label:Int,val icon:Int) {
    start("recording-start","com.bontecou.whereday.shortcut.START",R.string.shortcut_start,R.drawable.shortcut_record),
    stop("recording-stop","com.bontecou.whereday.shortcut.STOP",R.string.shortcut_stop,R.drawable.shortcut_stop),
    timeline("timeline","com.bontecou.whereday.shortcut.TIMELINE",R.string.shortcut_timeline,R.drawable.shortcut_timeline),
    export("export","com.bontecou.whereday.shortcut.EXPORT",R.string.shortcut_export,R.drawable.shortcut_export);
    companion object {
        fun fromAction(action:String?)=entries.firstOrNull {it.action==action}
        fun restore(id:String?)=entries.firstOrNull {it.id==id}
    }
}

internal object LauncherShortcuts {
    fun publish(context:Context) {
        val manager=context.getSystemService(ShortcutManager::class.java)?:return
        // Dynamic explicit Intents use the actual application ID, including disposable QA builds.
        // Explicit flags preserve an existing task instead of the launcher's default CLEAR_TASK.
        val shortcuts=LauncherAction.entries.mapIndexed {rank,item ->
            ShortcutInfo.Builder(context,item.id)
                .setShortLabel(context.getString(item.label))
                .setIcon(Icon.createWithResource(context,item.icon))
                .setRank(rank)
                .setIntent(Intent(context,MainActivity::class.java).setAction(item.action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                .build()
        }
        try {
            if(!manager.isRateLimitingActive) {
                manager.updateShortcuts(shortcuts) // Also refresh any separately pinned known shortcut.
                manager.setDynamicShortcuts(shortcuts.take(manager.maxShortcutCountPerActivity.coerceAtLeast(0)))
            }
        } catch(_:IllegalStateException) {
            // Locked-user/rate-limit startup must not prevent opening the journal.
        } catch(_:SecurityException) {
            // A launcher/device policy can disallow shortcut publication.
        }
    }
}


/** Refresh localized labels/icons after a system locale change or an application update. */
class ShortcutRefreshReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action==Intent.ACTION_LOCALE_CHANGED || intent.action==Intent.ACTION_MY_PACKAGE_REPLACED)
            LauncherShortcuts.publish(context)
    }
}
