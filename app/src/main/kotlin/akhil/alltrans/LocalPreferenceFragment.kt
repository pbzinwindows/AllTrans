package akhil.alltrans

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.text.Collator
import java.util.TreeMap

class LocalPreferenceFragment : PreferenceFragmentCompat() {
    var applicationInfo: ApplicationInfo? = null
    private var globalSettings: SharedPreferences? = null
    private var msBatchModePreference: ListPreference? = null

    private fun handleProviderChange(translatorProviderSelected: String) {
        val translateFromLanguage = findPreference<ListPreference?>("TranslateFromLanguage")
        val translateToLanguage = findPreference<ListPreference?>("TranslateToLanguage")
        val useCustomSubscription = findPreference<SwitchPreference?>("UseCustomSubscription")
        val customSubscriptionKey = findPreference<EditTextPreference?>("CustomSubscriptionKey")
        val customSubscriptionRegion = findPreference<EditTextPreference?>("CustomSubscriptionRegion")

        if(translateFromLanguage == null || translateToLanguage == null ||
            useCustomSubscription == null || customSubscriptionKey == null ||
            customSubscriptionRegion == null) {
            Log.e("AllTrans", "LocalPref: Language or subscription preferences are null in handleProviderChange")
            return
        }

        // Mostra ou esconde as opções de assinatura personalizada com base no provedor selecionado
        val isMicrosoft = translatorProviderSelected == "m"
        useCustomSubscription.isVisible = isMicrosoft

        // A visibilidade dos campos de chave/região depende da opção useCustomSubscription
        updateSubscriptionFieldsVisibility(useCustomSubscription.isChecked && isMicrosoft)

        // Set visibility of Microsoft batch mode preference
        msBatchModePreference?.isVisible = isMicrosoft

        if (translatorProviderSelected == "y") {
            translateFromLanguage.setEntries(R.array.languageNamesYandex)
            translateFromLanguage.setEntryValues(R.array.languageCodesYandex)
            translateToLanguage.setEntries(R.array.languageNamesYandex)
            translateToLanguage.setEntryValues(R.array.languageCodesYandex)
        } else if (translatorProviderSelected == "m") {
            translateFromLanguage.setEntries(R.array.languageNames)
            translateFromLanguage.setEntryValues(R.array.languageCodes)
            translateToLanguage.setEntries(R.array.languageNames)
            translateToLanguage.setEntryValues(R.array.languageCodes)
        } else {
            translateFromLanguage.setEntries(R.array.languageNamesGoogle)
            translateFromLanguage.setEntryValues(R.array.languageCodesGoogle)
            translateToLanguage.setEntries(R.array.languageNamesGoogle)
            translateToLanguage.setEntryValues(R.array.languageCodesGoogle)
        }
        validateListPreferenceValue(translateFromLanguage)
        validateListPreferenceValue(translateToLanguage)
    }

    // Método para atualizar a visibilidade dos campos de assinatura personalizada
    private fun updateSubscriptionFieldsVisibility(visible: Boolean) {
        val customSubscriptionKey = findPreference<EditTextPreference?>("CustomSubscriptionKey")
        val customSubscriptionRegion = findPreference<EditTextPreference?>("CustomSubscriptionRegion")

        customSubscriptionKey?.isVisible = visible
        customSubscriptionRegion?.isVisible = visible
    }

    private fun validateListPreferenceValue(listPreference: ListPreference?) {
        if (listPreference == null) return
        val currentValue: CharSequence? = listPreference.value
        val entryValues = listPreference.entryValues
        if (currentValue != null && entryValues != null) {
            var found = false
            for (value in entryValues) {
                if (currentValue == value) {
                    found = true
                    break
                }
            }
            if (!found) {
                if (entryValues.isNotEmpty()) {
                    listPreference.setValueIndex(0)
                }
                Log.w("AllTrans", "LocalPref: Resetting invalid value for preference: " + listPreference.key)
            }
        }
    }

    private fun sortListPreferenceByEntries(preferenceKey: String) {
        val preference = findPreference<ListPreference?>(preferenceKey)
        if (preference == null || preference.entries == null || preference.entryValues == null) {
            Log.w("AllTrans", "LocalPref: Cannot sort ListPreference, null or missing entries/values: $preferenceKey")
            return
        }

        val entries = preference.entries
        val entryValues = preference.entryValues

        if (entries.size != entryValues.size) {
            Log.e("AllTrans", "LocalPref: Mismatch between entries and entryValues length for: $preferenceKey")
            return
        }

        val sortRules = Collator.getInstance(resources.configuration.locales.get(0))
        sortRules.strength = Collator.PRIMARY
        val sorter = TreeMap<CharSequence?, CharSequence?>(sortRules)

        for (i in entries.indices) {
            if (!sorter.containsKey(entries[i])) {
                sorter.put(entries[i], entryValues[i])
            } else {
                Log.w("AllTrans", "LocalPref: Duplicate entry found while sorting: " + entries[i])
            }
        }

        val sortedLabels = arrayOfNulls<CharSequence>(sorter.size)
        val sortedValues = arrayOfNulls<CharSequence>(sorter.size)
        var i = 0
        for (entry in sorter.entries) {
            sortedLabels[i] = entry.key
            sortedValues[i] = entry.value
            i++
        }

        val currentValue = preference.value
        preference.entries = sortedLabels
        preference.entryValues = sortedValues
        preference.value = currentValue
    }

    private fun downloadModel(translateLanguageSelected: String, isFromLanguage: Boolean) {
        // CORREÇÃO: Obtém o provedor local em vez do global
        val isOverrideEnabled = preferenceManager.sharedPreferences?.getBoolean("OverRide", false) ?: false
        val localProvider = if (isOverrideEnabled) {
            preferenceManager.sharedPreferences?.getString("TranslatorProvider",
                globalSettings?.getString("TranslatorProvider", "g") ?: "g") ?: "g"
        } else {
            globalSettings?.getString("TranslatorProvider", "g") ?: "g"
        }

        // Verifica se o provedor atual (local ou global) é Google
        if (localProvider != "g") {
            Utils.debugLog("LocalPref: Skipping download, current provider is not Google ($localProvider)")
            return
        }

        if (translateLanguageSelected.isEmpty() || translateLanguageSelected == "auto") {
            Log.d("AllTrans", "LocalPref: Skipping model download for invalid/auto language: $translateLanguageSelected")
            return
        }

        Utils.debugLog("LocalPref: Preparing download for Language $translateLanguageSelected isFromLanguage $isFromLanguage")
        val sourceLanguage: String
        val targetLanguage: String
        try {
            if (isFromLanguage) {
                sourceLanguage = translateLanguageSelected
                targetLanguage = TranslateLanguage.ENGLISH
            } else {
                sourceLanguage = TranslateLanguage.ENGLISH
                targetLanguage = translateLanguageSelected
            }
        } catch (e: IllegalArgumentException) {
            Log.e("AllTrans", "LocalPref: Invalid language code for ML Kit: $translateLanguageSelected", e)
            Toast.makeText(context, getString(R.string.invalid_language_code) + ": $translateLanguageSelected", Toast.LENGTH_SHORT).show()
            return
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
        val mlKitTranslator = Translation.getClient(options)
        try {
            val lifecycle = requireActivity().lifecycle
            lifecycle.addObserver(mlKitTranslator)
        } catch (e: Exception) {
            Log.e("AllTrans", "LocalPref: Could not add lifecycle observer for translator", e)
        }

        MaterialAlertDialogBuilder(requireContext(), com.google.android.material.R.style.ThemeOverlay_MaterialComponents_MaterialAlertDialog)
            .setMessage(R.string.ask_download)
            .setPositiveButton(R.string.download_now) { dialogInterface, _ ->
                val progressView = LayoutInflater.from(requireContext()).inflate(R.layout.progress_dialog_layout, null)
                val progressDialog = MaterialAlertDialogBuilder(requireContext(), com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert)
                    .setView(progressView)
                    .setCancelable(false)
                    .create()
                progressDialog.show()

                mlKitTranslator.downloadModelIfNeeded()
                    .addOnSuccessListener { _ ->
                        Utils.debugLog("LocalPref: Successfully Downloaded Translation model!")
                        try { progressDialog.dismiss() } catch (e: Exception) { Log.w("AllTrans", "Error dismissing progressDialog", e) }
                        try { dialogInterface.dismiss() } catch (e: Exception) { Log.w("AllTrans", "Error dismissing dialogInterface", e) }
                        Toast.makeText(context, R.string.download_sucess, Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Utils.debugLog("LocalPref: Could not Download Translation model!")
                        Utils.debugLog("LocalPref: Download error - " + Log.getStackTraceString(e))
                        try { progressDialog.dismiss() } catch (e: Exception) { Log.w("AllTrans", "Error dismissing progressDialog on failure", e) }
                        try { dialogInterface.dismiss() } catch (e: Exception) { Log.w("AllTrans", "Error dismissing dialogInterface on failure", e) }
                        Toast.makeText(context, R.string.download_failure, Toast.LENGTH_LONG ).show()
                    }
            }
            .setNegativeButton(R.string.cancel) { dialogInterface, _ ->
                try { dialogInterface.dismiss() } catch (e: Exception) { Log.w("AllTrans", "Error dismissing dialogInterface on cancel", e) }
            }
            .show()
    }

    private fun enableLocalPrefs(enable: Boolean) {
        val keysToToggle = listOf(
            "TranslateFromLanguage", "TranslateToLanguage", "TranslatorProvider", "SetText",
            "SetHint", "LoadURL", "DrawText", "Notif", "Cache", "Scroll", "Delay", "DelayWebView",
            "UseCustomSubscription", "CustomSubscriptionKey", "CustomSubscriptionRegion",
            "app_ms_batch_translate_mode"
        )
        keysToToggle.forEach { key ->
            findPreference<Preference>(key)?.isEnabled = enable
        }
    }

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        globalSettings = requireActivity().getSharedPreferences("AllTransPref", Context.MODE_PRIVATE)

        if (applicationInfo == null) {
            val safeContext = context
            if (safeContext != null) {
                Toast.makeText(safeContext, R.string.wut_why_null, Toast.LENGTH_SHORT).show()
            }
            Log.e("AllTrans", "LocalPreferenceFragment: applicationInfo is null!")
            parentFragmentManager.popBackStack()
            return
        }

        val prefManager = preferenceManager
        prefManager.sharedPreferencesName = applicationInfo!!.packageName
        val localPrefs = prefManager.sharedPreferences

        addPreferencesFromResource(R.xml.perappprefs)

        // Get reference to the batch mode preference
        msBatchModePreference = findPreference("app_ms_batch_translate_mode")

        val isGloballyEnabled = globalSettings?.contains(applicationInfo!!.packageName) ?: false
        val overrideEnabled = localPrefs?.getBoolean("OverRide", false) ?: false

        findPreference<SwitchPreference>("LocalEnabled")?.isChecked = isGloballyEnabled
        findPreference<SwitchPreference>("OverRide")?.isChecked = overrideEnabled

        enableLocalPrefs(overrideEnabled)

        // Configuração do provedor de tradução local
        findPreference<ListPreference>("TranslatorProvider")?.apply {
            setOnPreferenceChangeListener { preference, newValue ->
                val provider = newValue as String
                handleProviderChange(provider)

                // Limpar cache ao trocar de provedor
                preferenceManager.sharedPreferences?.edit()
                    ?.putString("ClearCacheTime", System.currentTimeMillis().toString())
                    ?.apply()

                Utils.debugLog("Cleared cache due to provider change to: $provider")
                Toast.makeText(
                    preference.context,
                    R.string.clear_cache_success,
                    Toast.LENGTH_SHORT
                ).show()

                true
            }

            // Inicialização do valor correto - usando o provedor local correto
            val localProvider = preferenceManager.sharedPreferences?.getString(
                "TranslatorProvider",
                globalSettings?.getString("TranslatorProvider", "g") ?: "g"
            ) ?: "g"

            handleProviderChange(localProvider)
        }

        // Configuração do toggle para uso de assinatura personalizada
        findPreference<SwitchPreference>("UseCustomSubscription")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val useCustom = newValue as Boolean
                val isMicrosoft = preferenceManager.sharedPreferences?.getString("TranslatorProvider", "g") == "m"
                updateSubscriptionFieldsVisibility(useCustom && isMicrosoft)
                true
            }
        }

        findPreference<SwitchPreference>("OverRide")?.setOnPreferenceChangeListener { _, newValue ->
            enableLocalPrefs(newValue as Boolean)
            true
        }

        findPreference<ListPreference?>("TranslateFromLanguage")?.setOnPreferenceChangeListener { _, newValue ->
            val lang = newValue as String?
            if (lang != null) {
                // CORREÇÃO: Verifica o provedor local atual, não o global
                val isOverrideEnabled = preferenceManager.sharedPreferences?.getBoolean("OverRide", false) ?: false
                val currentProviderValue = if (isOverrideEnabled) {
                    preferenceManager.sharedPreferences?.getString("TranslatorProvider",
                        globalSettings?.getString("TranslatorProvider", "g") ?: "g") ?: "g"
                } else {
                    globalSettings?.getString("TranslatorProvider", "g") ?: "g"
                }

                Utils.debugLog("TranslateFrom listener triggered. Lang: $lang, Provider (local): $currentProviderValue")
                if ("g" == currentProviderValue) {
                    downloadModel(lang, true)
                } else {
                    Utils.debugLog("Skipping model download - provider is: $currentProviderValue")
                }
            }
            true
        }

        findPreference<ListPreference?>("TranslateToLanguage")?.setOnPreferenceChangeListener { _, newValue ->
            val lang = newValue as String?
            if (lang != null) {
                // CORREÇÃO: Verifica o provedor local atual, não o global
                val isOverrideEnabled = preferenceManager.sharedPreferences?.getBoolean("OverRide", false) ?: false
                val currentProviderValue = if (isOverrideEnabled) {
                    preferenceManager.sharedPreferences?.getString("TranslatorProvider",
                        globalSettings?.getString("TranslatorProvider", "g") ?: "g") ?: "g"
                } else {
                    globalSettings?.getString("TranslatorProvider", "g") ?: "g"
                }

                Utils.debugLog("TranslateTo listener triggered. Lang: $lang, Provider (local): $currentProviderValue")
                if ("g" == currentProviderValue) {
                    downloadModel(lang, false)
                } else {
                    Utils.debugLog("Skipping model download - provider is: $currentProviderValue")
                }
            }
            true
        }

        findPreference<Preference>("ClearCache")?.setOnPreferenceClickListener { preference ->
            localPrefs?.edit()
                ?.putString("ClearCacheTime", System.currentTimeMillis().toString())
                ?.apply()
            Toast.makeText(preference.context, R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<SwitchPreference>("LocalEnabled")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            globalSettings?.edit()?.apply {
                if (enabled) {
                    putBoolean(applicationInfo!!.packageName, true)
                    Utils.debugLog("LocalPref Listener: Added ${applicationInfo!!.packageName} to global list.")
                } else {
                    remove(applicationInfo!!.packageName)
                    Utils.debugLog("LocalPref Listener: Removed ${applicationInfo!!.packageName} from global list.")
                }
                apply()
            }
            true
        }

        // Inicializar a visibilidade dos campos de assinatura personalizada
        val useCustom = localPrefs?.getBoolean("UseCustomSubscription", false) ?: false
        val isMicrosoft = localPrefs?.getString("TranslatorProvider", "g") == "m"
        val useCustomSubscription = findPreference<SwitchPreference>("UseCustomSubscription")
        useCustomSubscription?.isVisible = isMicrosoft
        updateSubscriptionFieldsVisibility(useCustom && isMicrosoft)

        // Set initial visibility of Microsoft batch mode preference
        msBatchModePreference?.isVisible = isMicrosoft
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }
}