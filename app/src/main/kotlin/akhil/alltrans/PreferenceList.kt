/*
 * Copyright 2017 Akhil Kedia
 * This file is part of AllTrans.
 *
 * AllTrans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AllTrans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AllTrans. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package akhil.alltrans

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal object PreferenceList {
    var Enabled: Boolean = false
    var LocalEnabled: Boolean = false
    var Debug: Boolean = false

    var SubscriptionKey: String? = null
    var SubscriptionRegion: String? = null // NOVO CAMPO PARA A REGIÃO
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
        // Usa getOrDefault se a chave existir mas o valor for null, senão retorna defValue
        // Correção: O comportamento original estava correto. Se a chave existe, retorna o valor (mesmo que null).
        // Se não existe, retorna defValue.
        // Vamos manter a lógica original que é mais segura com diferentes tipos de dados nas prefs.
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

        // Ajuste: A lógica original para LocalEnabled estava buscando pelo packageName nas prefs globais,
        // o que pode não ser o desejado. LocalEnabled deveria vir das prefs locais?
        // Por agora, manterei a lógica original, mas isso pode precisar de revisão.
        // Assumindo que LocalEnabled é uma pref *dentro* das preferências locais lidas.
        // Se não, a lógica original de ler gPref.get(packageName) estava correta.
        // ATUALIZAÇÃO: Revendo AttachBaseContextHookHandler, ele lê ambas as prefs e as passa.
        // A lógica original de verificar gPref pelo packageName parece ser para habilitar/desabilitar o app globalmente.
        // E depois lPref contém as configurações específicas se override estiver ativo.
        // A lógica atual pega LocalEnabled das prefs locais se override estiver ativo. Isso parece razoável.

        // Lógica original mantida para LocalEnabled (verificação global)
        LocalEnabled = getValue(gPref, packageName, false) as Boolean
        Debug = getValue(gPref, "Debug", false) as Boolean

        SubscriptionKey = getValue(gPref, "SubscriptionKey", "") as String?
        TranslatorProvider = getValue(gPref, "TranslatorProvider", "g") as String?
        SubscriptionRegion = getValue(gPref, "SubscriptionRegion", "") as String? // LER A REGIÃO

        CachingTime = 0L // Default para 0
        val clearCacheTimeObj = getValue(lPref, "ClearCacheTime", "0")
        if (clearCacheTimeObj is String) {
            try {
                CachingTime = clearCacheTimeObj.toLong()
            } catch (e: NumberFormatException) {
                utils.debugLog("Error parsing ClearCacheTime: " + clearCacheTimeObj)
                CachingTime = 0L // Reset em caso de erro
            }
        } else if (clearCacheTimeObj is Long) { // GSON pode deserializar como Long
            CachingTime = clearCacheTimeObj
        }


        // Verifica se as configurações locais devem sobrescrever as globais
        if (getValue(lPref, "OverRide", false) as Boolean) {
            // Se sobrescrever, pega as configurações de idioma e hooks de lPref
            utils.debugLog("Overriding global preferences with local ones for " + packageName)
            TranslateFromLanguage = getValue(
                lPref,
                "TranslateFromLanguage",
                TranslateFromLanguage
            ) as String? // Usa global como fallback se não estiver em local
            TranslateToLanguage = getValue(
                lPref,
                "TranslateToLanguage",
                TranslateToLanguage
            ) as String? // Usa global como fallback
            SetText = getValue(lPref, "SetText", SetText) as Boolean
            SetHint = getValue(lPref, "SetHint", SetHint) as Boolean
            LoadURL = getValue(lPref, "LoadURL", LoadURL) as Boolean
            DrawText = getValue(lPref, "DrawText", DrawText) as Boolean
            Notif = getValue(lPref, "Notif", Notif) as Boolean
            Caching = getValue(
                lPref,
                "Cache",
                Caching
            ) as Boolean // Permitir override do cache localmente?
            // Delay e Scroll podem ser sobrescritos também? Adicionando-os.
            try {
                Delay = (getValue(lPref, "Delay", Delay.toString()) as String?)!!.toInt()
            } catch (e: NumberFormatException) { /* Usa o valor global já definido */
            }
            Scroll = getValue(lPref, "Scroll", Scroll) as Boolean
            try {
                DelayWebView = (PreferenceList.getValue(
                    lPref,
                    "DelayWebView",
                    PreferenceList.DelayWebView.toString()
                ) as kotlin.String?)!!.toInt()
            } catch (e: NumberFormatException) { /* Usa o valor global já definido */
            }

            // A lógica original tinha gPref = lPref, que substituía *todas* as globais,
            // incluindo chave, provedor, debug etc, o que pode não ser o desejado.
            // A abordagem acima é mais granular.
        } else {
            // Se não sobrescrever, pega as configurações de idioma e hooks de gPref
            TranslateFromLanguage = getValue(
                gPref,
                "TranslateFromLanguage",
                "auto"
            ) as String? // Default 'auto' ou 'en'?
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

        // LocalEnabled das prefs locais indica se o *override* está ativo e o app está habilitado localmente
        // A verificação global feita antes (LocalEnabled = gPref.get(packageName)) decide se o módulo sequer roda no app.
        // Renomear a variável LocalEnabled aqui pode evitar confusão. Talvez 'isLocallyEnabledOverride'?
        // Por agora, mantendo 'LocalEnabled' mas ciente da potencial ambiguidade.
        // A flag lida de lPref["LocalEnabled"] parece indicar se o app foi *marcado* nas prefs locais.
        val localSettingsEnabledFlag = getValue(lPref, "LocalEnabled", false) as Boolean
        utils.debugLog("Local settings enabled flag: " + localSettingsEnabledFlag)
    }
}