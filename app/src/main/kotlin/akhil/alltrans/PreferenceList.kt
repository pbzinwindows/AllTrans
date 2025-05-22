package akhil.alltrans

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal object PreferenceList {
    // Existing properties
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

    // New properties for Microsoft Batch Translation
    var GlobalMicrosoftBatchTranslationEnabled: Boolean = true // Default to true
    var AppMicrosoftBatchTranslationMode: String = "global" // Default to "global"
    var CurrentAppMicrosoftBatchEnabled: Boolean = true // Effective setting for the current app

    // Preference Keys
    const val KEY_GLOBAL_MS_BATCH_TRANSLATE_ENABLED = "global_ms_batch_translate_enabled"
    const val KEY_APP_MS_BATCH_TRANSLATE_MODE = "app_ms_batch_translate_mode"


    fun getValue(pref: MutableMap<String?, Any?>, key: String?, defValue: Any?): Any? {
        // Handle Boolean specifically as it might be stored as String "true"/"false" by older prefs
        // or directly as Boolean by newer ones.
        val value = if (pref.containsKey(key)) pref[key] else defValue
        return when (value) {
            is Boolean -> value
            is String -> {
                if (value.equals("true", ignoreCase = true)) true
                else if (value.equals("false", ignoreCase = true)) false
                else value // Not a boolean string, return as is (might be for other types)
            }
            else -> value
        }
    }


    fun getPref(globalPref: String?, localPref: String?, packageName: String?) {
        val gPref = Gson().fromJson<MutableMap<String?, Any?>>(
            globalPref,
            object : TypeToken<MutableMap<String?, Any?>?>() {
            }.getType()
        ) ?: mutableMapOf() // Ensure gPref is not null

        val lPref = Gson().fromJson<MutableMap<String?, Any?>>(
            localPref,
            object : TypeToken<MutableMap<String?, Any?>?>() {
            }.getType()
        ) ?: mutableMapOf() // Ensure lPref is not null


        // Configurações básicas de habilitação sempre são obtidas das preferências globais
        Enabled = getValue(gPref, "Enabled", false) as Boolean
        LocalEnabled = getValue(gPref, packageName, false) as Boolean // This is gPref[packageName]
        Debug = getValue(gPref, "Debug", false) as Boolean

        // Load Global Microsoft Batch Translation Setting
        GlobalMicrosoftBatchTranslationEnabled = getValue(gPref, KEY_GLOBAL_MS_BATCH_TRANSLATE_ENABLED, true) as Boolean

        val clearCacheTimeStr = getValue(lPref, "ClearCacheTime", "0") as String?
        CachingTime = try {
            clearCacheTimeStr?.takeIf { it.isNotBlank() }?.toLong() ?: 0L
        } catch (e: NumberFormatException) {
            Utils.debugLog("Error parsing ClearCacheTime string: '$clearCacheTimeStr' for $packageName")
            0L
        }

        val useLocalSettings = getValue(lPref, "OverRide", false) as Boolean

        if (useLocalSettings) {
            Utils.debugLog("Overriding global preferences with local ones for $packageName")

            TranslateFromLanguage = getValue(lPref, "TranslateFromLanguage",
                getValue(gPref, "TranslateFromLanguage", "auto")) as String?
            TranslateToLanguage = getValue(lPref, "TranslateToLanguage",
                getValue(gPref, "TranslateToLanguage", "en")) as String?

            val localProvider = getValue(lPref, "TranslatorProvider",
                getValue(gPref, "TranslatorProvider", "g")) as String?
            TranslatorProvider = localProvider ?: getValue(gPref, "TranslatorProvider", "g") as String?


            if (TranslatorProvider == "m") {
                val useCustomSubscription = getValue(lPref, "UseCustomSubscription", false) as Boolean
                val useGlobalCredentials = getValue(lPref, "UseGlobalCredentials", true) as Boolean

                if (useCustomSubscription) {
                    val customKey = getValue(lPref, "CustomSubscriptionKey", null) as String?
                    val customRegion = getValue(lPref, "CustomSubscriptionRegion", null) as String?
                    SubscriptionKey = if (!customKey.isNullOrEmpty()) customKey else if (useGlobalCredentials) getValue(gPref, "SubscriptionKey", "") as String? else getValue(lPref, "SubscriptionKey", "") as String?
                    SubscriptionRegion = if (!customRegion.isNullOrEmpty()) customRegion else if (useGlobalCredentials) getValue(gPref, "SubscriptionRegion", "") as String? else getValue(lPref, "SubscriptionRegion", "") as String?
                } else if (useGlobalCredentials) {
                    SubscriptionKey = getValue(gPref, "SubscriptionKey", "") as String?
                    SubscriptionRegion = getValue(gPref, "SubscriptionRegion", "") as String?
                } else {
                    SubscriptionKey = getValue(lPref, "SubscriptionKey", "") as String?
                    SubscriptionRegion = getValue(lPref, "SubscriptionRegion", "") as String?
                }
            } else {
                SubscriptionKey = getValue(lPref, "SubscriptionKey", getValue(gPref, "SubscriptionKey", "")) as String?
                SubscriptionRegion = getValue(lPref, "SubscriptionRegion", getValue(gPref, "SubscriptionRegion", "")) as String?
            }

            SetText = getValue(lPref, "SetText", getValue(gPref, "SetText", true)) as Boolean
            SetHint = getValue(lPref, "SetHint", getValue(gPref, "SetHint", true)) as Boolean
            LoadURL = getValue(lPref, "LoadURL", getValue(gPref, "LoadURL", true)) as Boolean
            DrawText = getValue(lPref, "DrawText", getValue(gPref, "DrawText", false)) as Boolean
            Notif = getValue(lPref, "Notif", getValue(gPref, "Notif", true)) as Boolean
            Caching = getValue(lPref, "Cache", getValue(gPref, "Cache", true)) as Boolean
            Scroll = getValue(lPref, "Scroll", getValue(gPref, "Scroll", false)) as Boolean

            try {
                Delay = (getValue(lPref, "Delay", getValue(gPref, "Delay", "0")) as String?)?.toIntOrNull() ?: 0
                DelayWebView = (getValue(lPref, "DelayWebView", getValue(gPref, "DelayWebView", "500")) as String?)?.toIntOrNull() ?: 500
            } catch (e: NumberFormatException) { /* Defaults already set */ }

            // Load App-Specific Microsoft Batch Translation Mode
            AppMicrosoftBatchTranslationMode = getValue(lPref, KEY_APP_MS_BATCH_TRANSLATE_MODE, "global") as String
            CurrentAppMicrosoftBatchEnabled = when (AppMicrosoftBatchTranslationMode) {
                "enabled" -> true
                "disabled" -> false
                else -> GlobalMicrosoftBatchTranslationEnabled // "global" or any other value
            }

        } else { // USING GLOBAL SETTINGS
            Utils.debugLog("Using global preferences for $packageName (OverRide is false)")

            TranslatorProvider = getValue(gPref, "TranslatorProvider", "g") as String?
            SubscriptionKey = getValue(gPref, "SubscriptionKey", "") as String?
            SubscriptionRegion = getValue(gPref, "SubscriptionRegion", "") as String?
            TranslateFromLanguage = getValue(gPref, "TranslateFromLanguage", "auto") as String?
            TranslateToLanguage = getValue(gPref, "TranslateToLanguage", "en") as String?

            SetText = getValue(gPref, "SetText", true) as Boolean
            SetHint = getValue(gPref, "SetHint", true) as Boolean
            LoadURL = getValue(gPref, "LoadURL", true) as Boolean
            DrawText = getValue(gPref, "DrawText", false) as Boolean
            Notif = getValue(gPref, "Notif", true) as Boolean
            Caching = getValue(gPref, "Cache", true) as Boolean
            Scroll = getValue(gPref, "Scroll", false) as Boolean

            try {
                Delay = (getValue(gPref, "Delay", "0") as String?)?.toIntOrNull() ?: 0
                DelayWebView = (getValue(gPref, "DelayWebView", "500") as String?)?.toIntOrNull() ?: 500
            } catch (e: NumberFormatException) { /* Defaults already set */ }

            // For global settings, AppMicrosoftBatchTranslationMode is effectively "global"
            AppMicrosoftBatchTranslationMode = "global"
            CurrentAppMicrosoftBatchEnabled = GlobalMicrosoftBatchTranslationEnabled
        }

        Utils.debugLog("---- Prefs loaded for $packageName ----")
        Utils.debugLog("Global MS Batch Enabled: $GlobalMicrosoftBatchTranslationEnabled")
        Utils.debugLog("App MS Batch Mode for $packageName: $AppMicrosoftBatchTranslationMode")
        Utils.debugLog("Effective MS Batch Enabled for $packageName: $CurrentAppMicrosoftBatchEnabled")
        Utils.debugLog("Active settings - Provider: $TranslatorProvider, From: $TranslateFromLanguage, To: $TranslateToLanguage")
        Utils.debugLog("---- End Prefs for $packageName ----")
    }
}