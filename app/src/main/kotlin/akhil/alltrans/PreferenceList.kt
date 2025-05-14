package akhil.alltrans

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal object PreferenceList {
    // Configurações básicas de habilitação
    var Enabled: Boolean = false // Master switch do módulo (lido de global)
    var AppSpecificEnabled: Boolean = false // Se o módulo está habilitado para ESTE app específico (lido de global)
    var LocalTranslationEnabledForApp: Boolean = false // Derivado de lPref "LocalEnabled" E AppSpecificEnabled
    var Debug: Boolean = false

    // ---- PREFERÊNCIAS GLOBAIS (lidas diretamente do arquivo de prefs global) ----
    var GlobalTranslatorProvider: String = "g"
    var GlobalMicrosoftSubscriptionKey: String = ""
    var GlobalMicrosoftSubscriptionRegion: String = "" // Nome da chave no XML global: "SubscriptionRegion"
    var GlobalYandexSubscriptionKey: String = ""
    var GlobalTranslateFromLanguage: String = "auto"
    var GlobalTranslateToLanguage: String = "en"
    var GlobalSetText: Boolean = true
    var GlobalSetHint: Boolean = true
    var GlobalLoadURL: Boolean = true
    var GlobalDrawText: Boolean = false
    var GlobalNotif: Boolean = true
    var GlobalCaching: Boolean = true
    var GlobalDelay: Int = 0
    var GlobalDelayWebView: Int = 500
    var GlobalScroll: Boolean = false

    // ---- PREFERÊNCIAS EFETIVAS (usadas pela lógica de tradução após considerar overrides) ----
    var effectiveTranslatorProvider: String = "g"
    var effectiveSubscriptionKey: String = ""
    var effectiveSubscriptionRegion: String = ""
    var TranslateFromLanguage: String = "auto"
    var TranslateToLanguage: String = "en"
    var SetText: Boolean = true
    var SetHint: Boolean = true
    var LoadURL: Boolean = true
    var DrawText: Boolean = false
    var Notif: Boolean = true
    var Caching: Boolean = true
    var CachingTime: Long = 0L
    var Delay: Int = 0
    var DelayWebView: Int = 500
    var Scroll: Boolean = false


    private inline fun <reified T> getValue(map: Map<String?, Any?>, key: String, defaultValue: T): T {
        val value = map[key]
        return when (T::class) {
            Boolean::class -> (value as? Boolean ?: defaultValue) as T
            String::class -> (value as? String ?: defaultValue) as T
            Int::class -> ((value as? Double)?.toInt() ?: (value as? String)?.toIntOrNull() ?: defaultValue) as T
            Long::class -> ((value as? Double)?.toLong() ?: (value as? String)?.toLongOrNull() ?: defaultValue) as T
            else -> defaultValue
        }
    }

    fun getPref(globalPrefJson: String?, localPrefJson: String?, packageName: String?) {
        // Salvar o provedor anterior para comparação
        val previousProvider = effectiveTranslatorProvider

        val gson = Gson()
        val mapType = object : TypeToken<MutableMap<String?, Any?>>() {}.type

        val gPrefMap: Map<String?, Any?> = try {
            gson.fromJson(globalPrefJson, mapType) ?: emptyMap()
        } catch (e: Exception) { Log.e("PreferenceList", "Error parsing global prefs: $e"); emptyMap() }

        val lPrefMap: Map<String?, Any?> = try {
            gson.fromJson(localPrefJson, mapType) ?: emptyMap()
        } catch (e: Exception) { Log.e("PreferenceList", "Error parsing local prefs: $e"); emptyMap() }

        // 1. Ler configurações globais básicas de habilitação e debug
        Enabled = getValue(gPrefMap, "Enabled", true)
        AppSpecificEnabled = getValue(gPrefMap, packageName ?: "", true)
        Debug = getValue(gPrefMap, "Debug", false)
        // Atualizar utils.Debug também, se necessário (ou remover essa duplicação)
        utils.Debug = Debug

        // 2. Ler todas as configurações globais e armazená-las nos campos "Global..."
        GlobalTranslatorProvider = getValue(gPrefMap, "TranslatorProvider", "g")
        GlobalMicrosoftSubscriptionKey = getValue(gPrefMap, "GlobalMicrosoftSubscriptionKey", "")
        GlobalMicrosoftSubscriptionRegion = getValue(gPrefMap, "SubscriptionRegion", "")
        GlobalYandexSubscriptionKey = getValue(gPrefMap, "GlobalYandexSubscriptionKey", "")
        GlobalTranslateFromLanguage = getValue(gPrefMap, "TranslateFromLanguage", "auto")
        GlobalTranslateToLanguage = getValue(gPrefMap, "TranslateToLanguage", "en")
        GlobalSetText = getValue(gPrefMap, "SetText", true)
        GlobalSetHint = getValue(gPrefMap, "SetHint", true)
        GlobalLoadURL = getValue(gPrefMap, "LoadURL", true)
        GlobalDrawText = getValue(gPrefMap, "DrawText", false)
        GlobalNotif = getValue(gPrefMap, "Notif", true)
        GlobalCaching = getValue(gPrefMap, "Cache", true)
        GlobalDelay = getValue(gPrefMap, "Delay", 0)
        GlobalDelayWebView = getValue(gPrefMap, "DelayWebView", 500)
        GlobalScroll = getValue(gPrefMap, "Scroll", false)

        // 3. Determinar se as configurações locais devem ser usadas
        val overrideGlobalSettings = getValue(lPrefMap, "OverRide", false)
        val localSwitchIsEnabled = getValue(lPrefMap, "LocalEnabled", true) // Switch "Traduzir este app"

        LocalTranslationEnabledForApp = Enabled && AppSpecificEnabled && (!overrideGlobalSettings || localSwitchIsEnabled)

        // Log para depuração dos valores de preferência local
        if (Debug) {
            Log.d("PreferenceList", "Package: $packageName")
            Log.d("PreferenceList", "Override: $overrideGlobalSettings, LocalEnabled: $localSwitchIsEnabled")
            Log.d("PreferenceList", "Global Provider: $GlobalTranslatorProvider")
            Log.d("PreferenceList", "Local preferences: ${lPrefMap.keys.joinToString()}")
        }

        if (overrideGlobalSettings && localSwitchIsEnabled) {
            utils.debugLog("PreferenceList: Using LOCAL settings for $packageName")

            // Obtém o provedor local - não existe mais "use_global", então sempre usa o valor local
            val localProvider = getValue(lPrefMap, "LocalTranslatorProvider", "g")
            utils.debugLog("PreferenceList: Read local provider: '$localProvider'")

            effectiveTranslatorProvider = localProvider
            when (localProvider) {
                "m" -> {
                    effectiveSubscriptionKey = getValue(lPrefMap, "LocalMicrosoftSubscriptionKey", GlobalMicrosoftSubscriptionKey)
                    effectiveSubscriptionRegion = getValue(lPrefMap, "LocalMicrosoftSubscriptionRegion", GlobalMicrosoftSubscriptionRegion)
                    utils.debugLog("PreferenceList: Using LOCAL Microsoft provider with key=${effectiveSubscriptionKey.take(5)}... region=${effectiveSubscriptionRegion}")
                }
                "y" -> {
                    effectiveSubscriptionKey = getValue(lPrefMap, "LocalYandexSubscriptionKey", GlobalYandexSubscriptionKey)
                    effectiveSubscriptionRegion = ""
                    utils.debugLog("PreferenceList: Using LOCAL Yandex provider with key=${effectiveSubscriptionKey.take(5)}...")
                }
                "g" -> {
                    effectiveSubscriptionKey = ""
                    effectiveSubscriptionRegion = ""
                    utils.debugLog("PreferenceList: Using LOCAL Google provider")
                }
                else -> {
                    utils.debugLog("PreferenceList: Unrecognized local provider: '$localProvider', falling back to Google")
                    effectiveTranslatorProvider = "g"
                    effectiveSubscriptionKey = ""
                    effectiveSubscriptionRegion = ""
                }
            }

            TranslateFromLanguage = getValue(lPrefMap, "TranslateFromLanguage", GlobalTranslateFromLanguage)
            TranslateToLanguage = getValue(lPrefMap, "TranslateToLanguage", GlobalTranslateToLanguage)
            SetText = getValue(lPrefMap, "SetText", GlobalSetText)
            SetHint = getValue(lPrefMap, "SetHint", GlobalSetHint)
            LoadURL = getValue(lPrefMap, "LoadURL", GlobalLoadURL)
            DrawText = getValue(lPrefMap, "DrawText", GlobalDrawText)
            Notif = getValue(lPrefMap, "Notif", GlobalNotif)
            Caching = getValue(lPrefMap, "Cache", GlobalCaching)
            Delay = getValue(lPrefMap, "Delay", GlobalDelay)
            DelayWebView = getValue(lPrefMap, "DelayWebView", GlobalDelayWebView)
            Scroll = getValue(lPrefMap, "Scroll", GlobalScroll)

        } else {
            utils.debugLog("PreferenceList: Using GLOBAL settings for $packageName. (Enabled: $Enabled, AppSpecificEnabled: $AppSpecificEnabled, LocalSwitchForThisApp: $localSwitchIsEnabled, OverrideForThisApp: $overrideGlobalSettings)")
            effectiveTranslatorProvider = GlobalTranslatorProvider
            setEffectiveKeysAndRegionFromGlobal(GlobalTranslatorProvider)

            TranslateFromLanguage = GlobalTranslateFromLanguage
            TranslateToLanguage = GlobalTranslateToLanguage
            SetText = GlobalSetText
            SetHint = GlobalSetHint
            LoadURL = GlobalLoadURL
            DrawText = GlobalDrawText
            Notif = GlobalNotif
            Caching = GlobalCaching
            Delay = GlobalDelay
            DelayWebView = GlobalDelayWebView
            Scroll = GlobalScroll
        }

        CachingTime = getValue(lPrefMap, "ClearCacheTime", 0L)

        // NOVA CORREÇÃO: Verificar se o provedor mudou e limpar o cache
        if (previousProvider != effectiveTranslatorProvider && previousProvider != ""
            && alltrans.Companion.context != null) {

            utils.debugLog("Provedor mudou de '$previousProvider' para '$effectiveTranslatorProvider' - limpando cache")

            // Limpa o cache em memória
            alltrans.Companion.cacheAccess.acquireUninterruptibly()
            try {
                alltrans.Companion.cache?.clear()

                // Também deleta o arquivo de cache
                try {
                    alltrans.Companion.context?.deleteFile("AllTransCache")
                    utils.debugLog("Arquivo de cache excluído devido à mudança de provedor")
                } catch (e: Exception) {
                    utils.debugLog("Falha ao excluir o arquivo de cache durante a mudança de provedor: ${e.message}")
                }
            } finally {
                if (alltrans.Companion.cacheAccess.availablePermits() == 0) {
                    alltrans.Companion.cacheAccess.release()
                }
            }
        }

        // Log mais detalhado das configurações efetivas
        utils.debugLog("Effective settings for $packageName: Provider='${effectiveTranslatorProvider}', KeySet='${effectiveSubscriptionKey.isNotBlank()}', Region='${effectiveSubscriptionRegion}', From='${TranslateFromLanguage}', To='${TranslateToLanguage}'")
    }

    private fun setEffectiveKeysAndRegionFromGlobal(providerToUse: String) {
        when (providerToUse) {
            "m" -> {
                effectiveSubscriptionKey = GlobalMicrosoftSubscriptionKey
                effectiveSubscriptionRegion = GlobalMicrosoftSubscriptionRegion
            }
            "y" -> {
                effectiveSubscriptionKey = GlobalYandexSubscriptionKey
                effectiveSubscriptionRegion = ""
            }
            else -> { // "g" ou default
                effectiveSubscriptionKey = ""
                effectiveSubscriptionRegion = ""
            }
        }
    }
}