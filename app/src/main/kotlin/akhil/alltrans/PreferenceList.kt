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

        // Configurações básicas de habilitação sempre são obtidas das preferências globais
        Enabled = getValue(gPref, "Enabled", false) as Boolean
        LocalEnabled = getValue(gPref, packageName, false) as Boolean
        Debug = getValue(gPref, "Debug", false) as Boolean

// Carregar o timestamp de limpeza do cache (sempre local)
// CachingTime aqui representa o localClearRequestTimestamp, ou seja,
// o momento em que o usuário clicou no botão "Limpar Cache" para este app.
        val clearCacheTimeStr = getValue(lPref, "ClearCacheTime", "0") as String? // Default string "0"
        CachingTime = try {
            // Garante que a string não seja nula ou vazia antes de tentar converter para Long.
            // Se for nula, vazia, ou não for um número, usa 0L.
            clearCacheTimeStr?.takeIf { it.isNotBlank() }?.toLong() ?: 0L
        } catch (e: NumberFormatException) {
            Utils.debugLog("Error parsing ClearCacheTime string: '$clearCacheTimeStr' for $packageName")
            0L // Default para 0L se a conversão falhar
        }

        // Verificar se devemos usar configurações locais ou globais
        val useLocalSettings = getValue(lPref, "OverRide", false) as Boolean

        if (useLocalSettings) {
            Utils.debugLog("Overriding global preferences with local ones for $packageName")

            // Usar configurações locais com fallback para as globais
            TranslateFromLanguage = getValue(lPref, "TranslateFromLanguage",
                getValue(gPref, "TranslateFromLanguage", "auto")) as String?

            TranslateToLanguage = getValue(lPref, "TranslateToLanguage",
                getValue(gPref, "TranslateToLanguage", "en")) as String?

            // Primeiro carregamos o provedor do local com fallback para o global
            val localProvider = getValue(lPref, "TranslatorProvider",
                getValue(gPref, "TranslatorProvider", "g")) as String?

            if (localProvider != null) {
                TranslatorProvider = localProvider
                Utils.debugLog("Using local translator provider: $localProvider")
            } else {
                // Fallback para o global se o local for null
                TranslatorProvider = getValue(gPref, "TranslatorProvider", "g") as String?
            }

            // Gerenciar credenciais e região com base no provedor
            if (TranslatorProvider == "m") {
                val useCustomSubscription = getValue(lPref, "UseCustomSubscription", false) as Boolean
                val useGlobalCredentials = getValue(lPref, "UseGlobalCredentials", true) as Boolean

                if (useCustomSubscription) {
                    // Usar credenciais locais personalizadas
                    val customKey = getValue(lPref, "CustomSubscriptionKey", null) as String?
                    val customRegion = getValue(lPref, "CustomSubscriptionRegion", null) as String?

                    if (!customKey.isNullOrEmpty()) {
                        SubscriptionKey = customKey
                        Utils.debugLog("Using custom Microsoft Translator key for $packageName")
                    } else if (useGlobalCredentials) {
                        // Fallback para credenciais globais
                        SubscriptionKey = getValue(gPref, "SubscriptionKey", "") as String?
                        Utils.debugLog("Using global Microsoft Translator key as fallback")
                    }

                    if (!customRegion.isNullOrEmpty()) {
                        SubscriptionRegion = customRegion
                        Utils.debugLog("Using custom Microsoft Translator region for $packageName: $customRegion")
                    } else if (useGlobalCredentials) {
                        // Fallback para região global
                        SubscriptionRegion = getValue(gPref, "SubscriptionRegion", "") as String?
                        Utils.debugLog("Using global Microsoft Translator region as fallback")
                    }
                } else if (useGlobalCredentials) {
                    // Usar diretamente credenciais globais
                    SubscriptionKey = getValue(gPref, "SubscriptionKey", "") as String?
                    SubscriptionRegion = getValue(gPref, "SubscriptionRegion", "") as String?
                    Utils.debugLog("Using global Microsoft Translator credentials")
                } else {
                    // Usar credenciais locais normais
                    SubscriptionKey = getValue(lPref, "SubscriptionKey", "") as String?
                    SubscriptionRegion = getValue(lPref, "SubscriptionRegion", "") as String?
                    Utils.debugLog("Using local Microsoft Translator credentials without custom config")
                }
            } else {
                // Para outros provedores, usar valores locais com fallback para globais
                SubscriptionKey = getValue(lPref, "SubscriptionKey",
                    getValue(gPref, "SubscriptionKey", "")) as String?
                SubscriptionRegion = getValue(lPref, "SubscriptionRegion",
                    getValue(gPref, "SubscriptionRegion", "")) as String?
            }

            // Opções de funcionalidade - usar os valores locais com fallbacks apropriados
            SetText = getValue(lPref, "SetText", getValue(gPref, "SetText", true)) as Boolean
            SetHint = getValue(lPref, "SetHint", getValue(gPref, "SetHint", true)) as Boolean
            LoadURL = getValue(lPref, "LoadURL", getValue(gPref, "LoadURL", true)) as Boolean
            DrawText = getValue(lPref, "DrawText", getValue(gPref, "DrawText", false)) as Boolean
            Notif = getValue(lPref, "Notif", getValue(gPref, "Notif", true)) as Boolean
            Caching = getValue(lPref, "Cache", getValue(gPref, "Cache", true)) as Boolean
            Scroll = getValue(lPref, "Scroll", getValue(gPref, "Scroll", false)) as Boolean

            // Valores numéricos com tratamento para conversão
            try {
                val delayStr = getValue(lPref, "Delay",
                    getValue(gPref, "Delay", "0")) as String?
                Delay = delayStr?.toIntOrNull() ?: 0
            } catch (e: NumberFormatException) {
                Delay = 0
                Utils.debugLog("Error parsing Delay, using default 0")
            }

            try {
                val delayWebViewStr = getValue(lPref, "DelayWebView",
                    getValue(gPref, "DelayWebView", "500")) as String?
                DelayWebView = delayWebViewStr?.toIntOrNull() ?: 500
            } catch (e: NumberFormatException) {
                DelayWebView = 500
                Utils.debugLog("Error parsing DelayWebView, using default 500")
            }
        } else {
            // USANDO CONFIGURAÇÕES GLOBAIS
            Utils.debugLog("Using global preferences for $packageName (OverRide is false)")

            // Carregar diretamente das configurações globais
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
                val delayStr = getValue(gPref, "Delay", "0") as String?
                Delay = delayStr?.toIntOrNull() ?: 0
            } catch (e: NumberFormatException) {
                Delay = 0
                Utils.debugLog("Error parsing global Delay, using default 0")
            }

            try {
                val delayWebViewStr = getValue(gPref, "DelayWebView", "500") as String?
                DelayWebView = delayWebViewStr?.toIntOrNull() ?: 500
            } catch (e: NumberFormatException) {
                DelayWebView = 500
                Utils.debugLog("Error parsing global DelayWebView, using default 500")
            }
        }

        // Log de informações para depuração
        val localSettingsEnabledFlag = getValue(lPref, "LocalEnabled", false) as Boolean // Este é o switch "Traduzir este app"
        val localCacheSwitchValue = getValue(lPref, "Cache", null) // Lê o valor bruto do switch local "Cache"
        val globalCacheValue = getValue(gPref, "Cache", null) // Lê o valor bruto do switch global "Cache"

        Utils.debugLog("---- Prefs loaded for $packageName ----")
        Utils.debugLog("Global Prefs state (gPref): $globalPref")
        Utils.debugLog("Local Prefs state (lPref): $localPref")
        Utils.debugLog("Override Global Settings (useLocalSettings): $useLocalSettings")
        Utils.debugLog("Translate This App (localSettingsEnabledFlag via gPref.$packageName): $LocalEnabled") // Correção: LocalEnabled é o antigo nome, deve ser gPref[packageName]
        Utils.debugLog("Translate This App (local 'LocalEnabled' switch): $localSettingsEnabledFlag") // O que o switch local realmente diz

        Utils.debugLog("Global 'Cache' switch value (from gPref): $globalCacheValue")
        Utils.debugLog("Local 'Cache' switch value (from lPref): $localCacheSwitchValue")
        Utils.debugLog("Final Calculated 'PreferenceList.Caching': ${PreferenceList.Caching}")
        Utils.debugLog("Final Calculated 'PreferenceList.CachingTime' (localClearRequestTimestamp): ${PreferenceList.CachingTime}")

        Utils.debugLog("Active settings - Provider: $TranslatorProvider, From: $TranslateFromLanguage, To: $TranslateToLanguage")
        Utils.debugLog("---- End Prefs for $packageName ----")
    }
}