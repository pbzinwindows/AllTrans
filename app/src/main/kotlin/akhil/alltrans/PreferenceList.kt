package akhil.alltrans

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal object PreferenceList {
    var Enabled: Boolean = false
    var LocalEnabled: Boolean = false
    var Debug: Boolean = false

    var SubscriptionKey: String? = null
    var SubscriptionRegion: String? = null
    var TranslateFromLanguage: String? = null
    var TranslateToLanguage: String? = null
    var TranslatorProvider: String? = null

    var SetText: Boolean = false
    var SetHint: Boolean = false
    var LoadURL: Boolean = false
    var DrawText: Boolean = false
    var Notif: Boolean = false

    var Caching: Boolean = false
    var CachingTime: Long = 0
    var Delay: Int = 0
    var DelayWebView: Int = 0
    var Scroll: Boolean = false

    fun getValue(pref: MutableMap<String?, Any?>, key: String?, defValue: Any?): Any? {
        return if (pref.containsKey(key)) pref.get(key) else defValue
    }

    fun getPref(globalPref: String?, localPref: String?, packageName: String?) {
        val gPref = Gson().fromJson<MutableMap<String?, Any?>>(
            globalPref,
            object : TypeToken<MutableMap<String?, Any?>?>() {
            }.getType()
        )
        val lPref = Gson().fromJson<MutableMap<String?, Any?>>(
            localPref,
            object : TypeToken<MutableMap<String?, Any?>?>() {
            }.getType()
        )

        Enabled = getValue(gPref, "Enabled", false) as Boolean
        LocalEnabled = getValue(gPref, packageName, false) as Boolean
        Debug = getValue(gPref, "Debug", false) as Boolean

        SubscriptionKey = getValue(gPref, "SubscriptionKey", "") as String?
        TranslatorProvider = getValue(gPref, "TranslatorProvider", "g") as String?
        SubscriptionRegion = getValue(gPref, "SubscriptionRegion", "") as String?

        CachingTime = 0L
        val clearCacheTimeObj = getValue(lPref, "ClearCacheTime", "0")
        if (clearCacheTimeObj is String) {
            try {
                CachingTime = clearCacheTimeObj.toLong()
            } catch (e: NumberFormatException) {
                utils.debugLog("Error parsing ClearCacheTime: " + clearCacheTimeObj)
                CachingTime = 0L
            }
        } else if (clearCacheTimeObj is Long) {
            CachingTime = clearCacheTimeObj
        }

        if (getValue(lPref, "OverRide", false) as Boolean) {
            utils.debugLog("Overriding global preferences with local ones for " + packageName)

            val localProvider = getValue(lPref, "TranslatorProvider", null) as String?
            if (localProvider != null) {
                TranslatorProvider = localProvider
                utils.debugLog("Using local translator provider: $localProvider")
            }

            TranslateFromLanguage = getValue(
                lPref,
                "TranslateFromLanguage",
                TranslateFromLanguage
            ) as String?
            TranslateToLanguage = getValue(
                lPref,
                "TranslateToLanguage",
                TranslateToLanguage
            ) as String?
            SetText = getValue(lPref, "SetText", SetText) as Boolean
            SetHint = getValue(lPref, "SetHint", SetHint) as Boolean
            LoadURL = getValue(lPref, "LoadURL", LoadURL) as Boolean
            DrawText = getValue(lPref, "DrawText", DrawText) as Boolean
            Notif = getValue(lPref, "Notif", Notif) as Boolean
            Caching = getValue(lPref, "Cache", Caching) as Boolean
            try {
                Delay = (getValue(lPref, "Delay", Delay.toString()) as String?)!!.toInt()
            } catch (e: NumberFormatException) {}
            Scroll = getValue(lPref, "Scroll", Scroll) as Boolean
            try {
                DelayWebView = (PreferenceList.getValue(
                    lPref,
                    "DelayWebView",
                    PreferenceList.DelayWebView.toString()
                ) as kotlin.String?)!!.toInt()
            } catch (e: NumberFormatException) {}
        } else {
            TranslateFromLanguage = getValue(gPref, "TranslateFromLanguage", "auto") as String?
            TranslateToLanguage = getValue(gPref, "TranslateToLanguage", "en") as String?

            SetText = getValue(gPref, "SetText", true) as Boolean
            SetHint = getValue(gPref, "SetHint", true) as Boolean
            LoadURL = getValue(gPref, "LoadURL", true) as Boolean
            DrawText = getValue(gPref, "DrawText", false) as Boolean
            Notif = getValue(gPref, "Notif", true) as Boolean
            Caching = getValue(gPref, "Cache", true) as Boolean
            try {
                Delay = (getValue(gPref, "Delay", "0") as String?)!!.toInt()
            } catch (e: NumberFormatException) {
                Delay = 0
            }
            Scroll = getValue(gPref, "Scroll", false) as Boolean
            try {
                DelayWebView = (getValue(gPref, "DelayWebView", "500") as String?)!!.toInt()
            } catch (e: NumberFormatException) {
                DelayWebView = 500
            }
        }

        val localSettingsEnabledFlag = getValue(lPref, "LocalEnabled", false) as Boolean
        utils.debugLog("Local settings enabled flag: " + localSettingsEnabledFlag)
    }
}