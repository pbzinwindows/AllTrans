package akhil.alltrans

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.text.Collator
import java.util.TreeMap
import java.util.Locale

class LocalPreferenceFragment : PreferenceFragmentCompat() {
    var applicationInfo: ApplicationInfo? = null
    private var globalSettings: SharedPreferences? = null
    private var localPrefs: SharedPreferences? = null // Referência às SharedPreferences locais

    // Preferências da UI
    private var overridePref: SwitchPreferenceCompat? = null
    private var localEnabledPref: SwitchPreferenceCompat? = null // "Traduzir este app"

    private var localProviderSettingsCategory: PreferenceCategory? = null
    private var localTranslatorProviderPref: ListPreference? = null
    private var localMsKeyPref: EditTextPreference? = null
    private var localMsRegionPref: EditTextPreference? = null
    private var localYandexKeyPref: EditTextPreference? = null
    private var localTranslateFromPref: ListPreference? = null
    private var localTranslateToPref: ListPreference? = null

    private var localTroubleshootingCategory: PreferenceCategory? = null
    private var localAdvancedCategory: PreferenceCategory? = null

    // Lista de preferências controladas pelo "OverRide" (exceto o próprio OverRide e LocalEnabled)
    private lateinit var overridablePreferenceObjects: List<Preference?>


    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        globalSettings = requireActivity().getSharedPreferences("AllTransPref", Context.MODE_PRIVATE)

        if (applicationInfo == null) {
            Toast.makeText(requireContext(), R.string.wut_why_null, Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }

        preferenceManager.sharedPreferencesName = applicationInfo!!.packageName // Define o nome do arquivo de prefs locais
        localPrefs = preferenceManager.sharedPreferences // Obtém a instância
        addPreferencesFromResource(R.xml.perappprefs)

        initializePreferences()
        loadInitialPreferenceValues() // Carrega valores salvos ou defaults
        setupInitialUIState()
        setupListeners()
    }

    private fun initializePreferences() {
        overridePref = findPreference(KEY_OVERRIDE_GLOBAL)
        localEnabledPref = findPreference(KEY_LOCAL_ENABLED_SWITCH)

        localProviderSettingsCategory = findPreference(KEY_CATEGORY_LOCAL_PROVIDER_SETTINGS)
        localTranslatorProviderPref = findPreference(KEY_LOCAL_TRANSLATOR_PROVIDER)
        localMsKeyPref = findPreference(KEY_LOCAL_MS_KEY)
        localMsRegionPref = findPreference(KEY_LOCAL_MS_REGION)
        localYandexKeyPref = findPreference(KEY_LOCAL_YANDEX_KEY)
        localTranslateFromPref = findPreference(KEY_LOCAL_TRANSLATE_FROM_XML_KEY)
        localTranslateToPref = findPreference(KEY_LOCAL_TRANSLATE_TO_XML_KEY)

        localTroubleshootingCategory = findPreference(KEY_CATEGORY_LOCAL_TROUBLESHOOTING)
        localAdvancedCategory = findPreference(KEY_CATEGORY_LOCAL_ADVANCED)

        // Lista de todas as preferências que são afetadas pelo "OverRide"
        // Não inclui "LocalEnabled" ou "OverRide" em si.
        overridablePreferenceObjects = listOf(
            localProviderSettingsCategory, // A categoria inteira
            localTranslatorProviderPref, localMsKeyPref, localMsRegionPref, localYandexKeyPref,
            localTranslateFromPref, localTranslateToPref,
            findPreference("DrawText"), findPreference("Scroll"), findPreference("Delay"),
            findPreference("DelayWebView"), findPreference("SetText"), findPreference("SetHint"),
            findPreference("LoadURL"), findPreference("Cache"), findPreference("Notif"),
            localTroubleshootingCategory, // A categoria inteira
            localAdvancedCategory // A categoria inteira
        )
    }

    private fun loadInitialPreferenceValues() {
        // "LocalEnabled" (switch "Traduzir este app"):
        // Seu valor deve vir das SharedPreferences GLOBAIS, que indicam se este app está na lista de tradução.
        // O defaultValue no XML (true) é para quando não há valor salvo AINDA nas prefs locais.
        val appIsEnabledInGlobalList = globalSettings?.getBoolean(applicationInfo!!.packageName, true) ?: true
        localEnabledPref?.isChecked = appIsEnabledInGlobalList

        // "OverRide": Lê das SharedPreferences LOCAIS
        val isOverriding = localPrefs?.getBoolean(KEY_OVERRIDE_GLOBAL, false) ?: false
        overridePref?.isChecked = isOverriding

        // Provedor local: Lê das SharedPreferences LOCAIS ou define para "g" (Google) como padrão
        val localProviderValue = localPrefs?.getString(KEY_LOCAL_TRANSLATOR_PROVIDER, "g") ?: "g"
        localTranslatorProviderPref?.value = localProviderValue
    }


    private fun setupInitialUIState() {
        val isLocallyEnabledSwitchChecked = localEnabledPref?.isChecked ?: false
        val isOverriding = overridePref?.isChecked ?: false

        // O switch "OverRide" só é relevante/habilitado se "Traduzir este app" (LocalEnabled) estiver marcado.
        overridePref?.isEnabled = isLocallyEnabledSwitchChecked

        // As preferências que são sobrescritas só são habilitadas se AMBOS os switches estiverem marcados.
        val enableOverriddenPrefs = isLocallyEnabledSwitchChecked && isOverriding
        updateOverriddenPrefsEnabledState(enableOverriddenPrefs)

        // Visibilidade dos campos de chave com base no provedor local selecionado (se override estiver ativo)
        val currentLocalProvider = localTranslatorProviderPref?.value ?: "g"
        updateLocalKeyFieldsVisibility(currentLocalProvider, enableOverriddenPrefs)

        // Listas de idiomas
        val globalProvider = globalSettings?.getString(KEY_GLOBAL_TRANSLATOR_PROVIDER_XML_KEY, "g")
        updateLocalLanguageLists(currentLocalProvider, globalProvider)
    }

    private fun updateOverriddenPrefsEnabledState(isEnabled: Boolean) {
        overridablePreferenceObjects.forEach { pref ->
            // Para categorias, apenas habilitar/desabilitar. Para outras prefs, também.
            pref?.isEnabled = isEnabled
        }
        // Se as prefs de override estão sendo desabilitadas, também esconda os campos de chave específicos
        if (!isEnabled) {
            updateLocalKeyFieldsVisibility("g", false) // Passa false para forçar o hide
        }
    }


    private fun setupListeners() {
        localEnabledPref?.setOnPreferenceChangeListener { _, newValue ->
            val isNowLocallyEnabled = newValue as Boolean
            val isCurrentlyOverriding = overridePref?.isChecked ?: false

            globalSettings?.edit()?.apply {
                if (isNowLocallyEnabled) {
                    putBoolean(applicationInfo!!.packageName, true)
                } else {
                    remove(applicationInfo!!.packageName)
                    // Se estava desabilitando a tradução local, também desabilitar o override visualmente
                    // e resetar as prefs de override para evitar confusão.
                    if (isCurrentlyOverriding) {
                        overridePref?.isChecked = false // Desmarca o switch de override
                    }
                }
                apply()
            }

            overridePref?.isEnabled = isNowLocallyEnabled
            // Atualiza o estado das prefs que dependem do override
            val enableOverridden = isNowLocallyEnabled && (overridePref?.isChecked ?: false)
            updateOverriddenPrefsEnabledState(enableOverridden)
            if (enableOverridden) { // Se as prefs de override estão ativas, atualize a visibilidade das chaves
                updateLocalKeyFieldsVisibility(localTranslatorProviderPref?.value, true)
            }
            true
        }

        overridePref?.setOnPreferenceChangeListener { _, newValue ->
            val isOverriding = newValue as Boolean
            updateOverriddenPrefsEnabledState(isOverriding) // Habilita/desabilita a categoria e outras prefs

            // Se estiver ativando o override, atualize a UI com base no provedor local
            if (isOverriding) {
                val localProvider = localTranslatorProviderPref?.value ?: "g"
                val globalProvider = globalSettings?.getString(KEY_GLOBAL_TRANSLATOR_PROVIDER_XML_KEY, "g")
                updateLocalKeyFieldsVisibility(localProvider, true)
                updateLocalLanguageLists(localProvider, globalProvider)
            } else {
                // Se estiver desativando o override, esconda os campos de chave local
                // e reconfigure as listas de idiomas para refletir o global (embora desabilitadas)
                updateLocalKeyFieldsVisibility("g", false)
                val globalProvider = globalSettings?.getString(KEY_GLOBAL_TRANSLATOR_PROVIDER_XML_KEY, "g")
                updateLocalLanguageLists("g", globalProvider)
            }
            true
        }

        localTranslatorProviderPref?.setOnPreferenceChangeListener { _, newValue ->
            val localProvider = newValue as String?
            val globalProvider = globalSettings?.getString(KEY_GLOBAL_TRANSLATOR_PROVIDER_XML_KEY, "g")
            updateLocalKeyFieldsVisibility(localProvider, overridePref?.isChecked ?: false)
            updateLocalLanguageLists(localProvider, globalProvider)
            true
        }

        localTranslateFromPref?.setOnPreferenceChangeListener { _, newValue ->
            val lang = newValue as String?
            if (getEffectiveProviderForUI() == "g" && lang != null && lang != "auto") {
                downloadModel(lang, true)
            }
            true
        }

        localTranslateToPref?.setOnPreferenceChangeListener { _, newValue ->
            val lang = newValue as String?
            if (getEffectiveProviderForUI() == "g" && lang != null && lang != "auto") {
                downloadModel(lang, false)
            }
            true
        }

        findPreference<Preference>("ClearCache")?.setOnPreferenceClickListener {
            localPrefs?.edit()?.putString("ClearCacheTime", System.currentTimeMillis().toString())?.apply()
            Toast.makeText(it.context, R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun updateLocalKeyFieldsVisibility(effectiveLocalProvider: String?, isOverrideActiveAndAppEnabled: Boolean) {
        // Só mostra campos de chave local se OverRide estiver marcado E LocalEnabled estiver marcado
        localMsKeyPref?.isVisible = isOverrideActiveAndAppEnabled && effectiveLocalProvider == "m"
        localMsRegionPref?.isVisible = isOverrideActiveAndAppEnabled && effectiveLocalProvider == "m"
        localYandexKeyPref?.isVisible = isOverrideActiveAndAppEnabled && effectiveLocalProvider == "y"
    }

    private fun updateLocalLanguageLists(localProviderSelected: String?, globalProviderFromPrefs: String?) {
        val fromPref = localTranslateFromPref ?: return
        val toPref = localTranslateToPref ?: return

        // Agora usamos diretamente o provedor local, não há mais "use_global"
        val providerToUseForLists = localProviderSelected ?: "g"

        val entriesResId: Int
        val valuesResId: Int
        when (providerToUseForLists) {
            "m" -> { entriesResId = R.array.languageNames; valuesResId = R.array.languageCodes }
            "y" -> { entriesResId = R.array.languageNamesYandex; valuesResId = R.array.languageCodesYandex }
            "g" -> { entriesResId = R.array.languageNamesGoogle; valuesResId = R.array.languageCodesGoogle }
            else -> { entriesResId = R.array.languageNamesGoogle; valuesResId = R.array.languageCodesGoogle }
        }
        fromPref.setEntries(entriesResId); fromPref.setEntryValues(valuesResId)
        toPref.setEntries(entriesResId); toPref.setEntryValues(valuesResId)

        validateListPreferenceValue(fromPref)
        validateListPreferenceValue(toPref)
        sortListPreferenceByEntries(fromPref)
        sortListPreferenceByEntries(toPref)
    }

    private fun getEffectiveProviderForUI(): String? {
        // Esta função determina qual provedor está controlando a UI (para download de modelo, etc.)
        if (overridePref?.isChecked == true && localEnabledPref?.isChecked == true) {
            return localTranslatorProviderPref?.value ?: "g"
        }
        return globalSettings?.getString(KEY_GLOBAL_TRANSLATOR_PROVIDER_XML_KEY, "g")
    }

    private fun validateListPreferenceValue(listPreference: ListPreference?) {
        listPreference?.let { pref ->
            val currentValue = pref.value
            if (pref.entryValues == null || (currentValue != null && !pref.entryValues.contains(currentValue))) {
                if (pref.entryValues != null && pref.entryValues.isNotEmpty()) {
                    pref.setValueIndex(0)
                    Log.w("LocalPrefs", "Resetting invalid value for ${pref.key} to ${pref.value}")
                } else if (currentValue != null) {
                    pref.value = null
                    Log.w("LocalPrefs", "Cleared value for ${pref.key} due to empty/null entryValues.")
                }
            }
        }
    }

    private fun sortListPreferenceByEntries(listPreference: ListPreference?) {
        listPreference?.let { preference ->
            if (preference.entries == null || preference.entryValues == null || preference.entries.size != preference.entryValues.size) {
                Log.w("LocalPrefs", "Cannot sort ListPreference: ${preference.key}")
                return
            }
            val entries = preference.entries
            val entryValues = preference.entryValues
            val currentLocale = context?.resources?.configuration?.locales?.get(0) ?: Locale.getDefault()
            val sortRules = Collator.getInstance(currentLocale)
            sortRules.strength = Collator.PRIMARY

            val sorter = TreeMap<CharSequence, CharSequence>(sortRules)
            for (i in entries.indices) {
                val entryText = entries[i] ?: continue
                if (!sorter.containsKey(entryText)) {
                    sorter.put(entryText, entryValues[i])
                } else {
                    Log.w("LocalPrefs", "Duplicate entry found: '$entryText' for key ${preference.key}")
                }
            }
            if (sorter.isEmpty && entries.isNotEmpty()) { // Se todas as entradas forem duplicatas, sorter pode ficar vazio
                Log.w("LocalPrefs", "Sorter is empty after trying to sort non-empty entries for ${preference.key}, check for identical entry labels.")
                return // Não modificar se a ordenação falhar
            }
            if (sorter.isEmpty && entries.isEmpty()){
                return // Nada a fazer
            }


            val sortedLabels = arrayOfNulls<CharSequence>(sorter.size)
            val sortedValues = arrayOfNulls<CharSequence>(sorter.size)
            var i = 0
            for (entry in sorter.entries) {
                sortedLabels[i] = entry.key; sortedValues[i] = entry.value; i++
            }
            val currentValue = preference.value
            preference.entries = sortedLabels; preference.entryValues = sortedValues
            preference.value = currentValue // Tenta restaurar
            validateListPreferenceValue(preference) // Revalida
        }
    }
    private fun downloadModel(translateLanguageSelected: String, isFromLanguage: Boolean) {
        val effectiveProvider = getEffectiveProviderForUI()
        if (effectiveProvider != "g") {
            utils.debugLog("LocalPrefs: Skipping download, effective provider for UI is not Google ($effectiveProvider)")
            return
        }
        if (translateLanguageSelected.isEmpty() || translateLanguageSelected == "auto") return

        utils.debugLog("LocalPrefs: Preparing download for $translateLanguageSelected (isFrom: $isFromLanguage)")
        val sourceLanguage: String
        val targetLanguage: String
        try {
            sourceLanguage = if (isFromLanguage) translateLanguageSelected else TranslateLanguage.ENGLISH
            targetLanguage = if (isFromLanguage) TranslateLanguage.ENGLISH else translateLanguageSelected
        } catch (e: IllegalArgumentException) {
            Toast.makeText(context, "${getString(R.string.invalid_language_code)}: $translateLanguageSelected", Toast.LENGTH_SHORT).show()
            return
        }

        val options = TranslatorOptions.Builder().setSourceLanguage(sourceLanguage).setTargetLanguage(targetLanguage).build()
        val mlKitTranslator = Translation.getClient(options)
        viewLifecycleOwner.lifecycle.addObserver(mlKitTranslator)

        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.ask_download)
            .setPositiveButton(R.string.download_now) { di, _ ->
                val progressView = LayoutInflater.from(requireContext()).inflate(R.layout.progress_dialog_layout, null)
                val progressDialog = MaterialAlertDialogBuilder(requireContext()).setView(progressView).setCancelable(false).create()
                progressDialog.show()
                mlKitTranslator.downloadModelIfNeeded()
                    .addOnSuccessListener { progressDialog.dismiss(); di.dismiss(); Toast.makeText(context, R.string.download_sucess, Toast.LENGTH_SHORT).show() }
                    .addOnFailureListener { e -> progressDialog.dismiss(); di.dismiss(); Toast.makeText(context, R.string.download_failure, Toast.LENGTH_LONG).show(); Log.e("LocalPrefs", "Model download error", e) }
            }
            .setNegativeButton(R.string.cancel) { di, _ -> di.dismiss() }
            .show()
    }

    companion object {
        // Chaves para preferências locais como definidas em perappprefs.xml
        private const val KEY_OVERRIDE_GLOBAL = "OverRide"
        private const val KEY_LOCAL_ENABLED_SWITCH = "LocalEnabled"
        private const val KEY_CATEGORY_LOCAL_PROVIDER_SETTINGS = "category_local_provider_settings"
        private const val KEY_LOCAL_TRANSLATOR_PROVIDER = "LocalTranslatorProvider"
        private const val KEY_LOCAL_MS_KEY = "LocalMicrosoftSubscriptionKey"
        private const val KEY_LOCAL_MS_REGION = "LocalMicrosoftSubscriptionRegion"
        private const val KEY_LOCAL_YANDEX_KEY = "LocalYandexSubscriptionKey"
        private const val KEY_LOCAL_TRANSLATE_FROM_XML_KEY = "TranslateFromLanguage" // Chave do XML
        private const val KEY_LOCAL_TRANSLATE_TO_XML_KEY = "TranslateToLanguage"     // Chave do XML
        private const val KEY_CATEGORY_LOCAL_TROUBLESHOOTING = "category_local_troubleshooting"
        private const val KEY_CATEGORY_LOCAL_ADVANCED = "category_local_advanced"

        // Chave para LER o provedor global (de globalSettings)
        private const val KEY_GLOBAL_TRANSLATOR_PROVIDER_XML_KEY = "TranslatorProvider"
    }
}