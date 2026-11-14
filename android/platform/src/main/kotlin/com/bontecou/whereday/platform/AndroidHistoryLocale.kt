package com.bontecou.whereday.platform

import android.content.Context
import android.provider.Settings
import java.util.Locale

/** Capture once per prepared document; a later device-setting change cannot mix row clocks. */
object AndroidHistoryLocale {
    fun capture(context:Context):Locale {
        val locale=context.resources.configuration.locales[0] ?: Locale.getDefault()
        return resolve(locale,Settings.System.getString(context.contentResolver,Settings.System.TIME_12_24))
    }
    internal fun resolve(locale:Locale,clockSetting:String?):Locale {
        if(locale.getUnicodeLocaleType("hc")!=null) return locale
        val cycle=when(clockSetting) {"12"->"h12";"24"->"h23";else->return locale}
        return Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("hc",cycle).build()
    }
}
