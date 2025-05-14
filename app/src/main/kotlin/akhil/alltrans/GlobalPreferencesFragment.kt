package akhil.alltrans

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater // Necessário para o diálogo de progresso
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
// PreferenceCategory não é usada diretamente como variável, mas sim para encontrar
import androidx.preference.PreferenceFragmentCompat
// SwitchPreferenceCompat pode ser usado se você mudar os SwitchPreference no XML para SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.text.Collator
import java.util.Locale
import java.util.TreeMap

class GlobalPreferencesFragment : PreferenceFragmentCompat() {

    private var translatorProviderPref: ListPreference? = null
    private var globalMicrosoftKeyPref: EditTextPreference? = null
    private var globalMicrosoftRegionPref: EditTextPreference? = null
    private var globalYandexKeyPref: EditTextPreference? = null
    private var translateFromLanguagePref: ListPreference? = null
    private var translateToLanguagePref: ListPreference? = null

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "AllTransPref" // Nome do arquivo de SharedPreferences globais
        addPreferencesFromResource(R.xml.preferences)

        translatorProviderPref = findPreference(KEY_TRANSLATOR_PROVIDER)
        globalMicrosoftKeyPref = findPreference(KEY_GLOBAL_MS_KEY)
        globalMicrosoftRegionPref = findPreference(KEY_GLOBAL_MS_REGION_XML_KEY) // Chave do XML global
        globalYandexKeyPref = findPreference(KEY_GLOBAL_YANDEX_KEY)
        translateFromLanguagePref = findPreference(KEY_TRANSLATE_FROM)
        translateToLanguagePref = findPreference(KEY_TRANSLATE_TO)

        // Configuração inicial da UI com base nos valores atuais
        val currentGlobalProvider = translatorProviderPref?.value
        updateGlobalKeyFieldsVisibility(currentGlobalProvider)
        updateLanguageListsForProvider(currentGlobalProvider, translateFromLanguagePref, translateToLanguagePref)

        translatorProviderPref?.setOnPreferenceChangeListener { _, newValue ->
            val provider = newValue as String?
            updateGlobalKeyFieldsVisibility(provider)
            updateLanguageListsForProvider(provider, translateFromLanguagePref, translateToLanguagePref)
            true
        }

        translateFromLanguagePref?.setOnPreferenceChangeListener { _, newValue ->
            val lang = newValue as String?
            // Só tenta baixar se o provedor global selecionado for Google
            if (translatorProviderPref?.value == "g" && lang != null && lang != "auto") {
                downloadModel(lang, true)
            }
            true
        }

        translateToLanguagePref?.setOnPreferenceChangeListener { _, newValue ->
            val lang = newValue as String?
            // Só tenta baixar se o provedor global selecionado for Google
            if (translatorProviderPref?.value == "g" && lang != null && lang != "auto") {
                downloadModel(lang, false)
            }
            true
        }
    }

    private fun updateGlobalKeyFieldsVisibility(provider: String?) {
        utils.debugLog("GlobalPrefs: Updating key field visibility for provider: $provider")
        globalMicrosoftKeyPref?.isVisible = provider == "m"
        globalMicrosoftRegionPref?.isVisible = provider == "m"
        globalYandexKeyPref?.isVisible = provider == "y"
    }

    private fun updateLanguageListsForProvider(provider: String?, fromPref: ListPreference?, toPref: ListPreference?) {
        if (fromPref == null || toPref == null) {
            Log.w("GlobalPrefs", "Language ListPreference is null, cannot update lists.")
            return
        }
        utils.debugLog("GlobalPrefs: Updating language lists for provider: $provider")

        val entriesResId: Int
        val valuesResId: Int

        when (provider) {
            "m" -> {
                entriesResId = R.array.languageNames
                valuesResId = R.array.languageCodes
            }
            "y" -> {
                entriesResId = R.array.languageNamesYandex
                valuesResId = R.array.languageCodesYandex
            }
            "g" -> {
                entriesResId = R.array.languageNamesGoogle
                valuesResId = R.array.languageCodesGoogle
            }
            else -> { // Fallback para Google
                Log.w("GlobalPrefs", "Unknown provider '$provider', defaulting to Google languages.")
                entriesResId = R.array.languageNamesGoogle
                valuesResId = R.array.languageCodesGoogle
            }
        }
        fromPref.setEntries(entriesResId)
        fromPref.setEntryValues(valuesResId)
        toPref.setEntries(entriesResId)
        toPref.setEntryValues(valuesResId)

        // Após mudar as entradas, é crucial revalidar os valores selecionados
        // e reordenar as listas se necessário.
        validateListPreferenceValue(fromPref)
        validateListPreferenceValue(toPref)
        sortListPreferenceByEntries(fromPref)
        sortListPreferenceByEntries(toPref)
    }

    private fun validateListPreferenceValue(listPreference: ListPreference?) {
        listPreference?.let { pref ->
            val currentValue = pref.value
            // Checa se entryValues é nulo ou se o valor atual não está na lista de valores
            if (pref.entryValues == null || (currentValue != null && !pref.entryValues.contains(currentValue))) {
                if (pref.entryValues != null && pref.entryValues.isNotEmpty()) {
                    pref.setValueIndex(0) // Define para o primeiro valor válido se o atual for inválido
                    Log.w("GlobalPrefs", "Resetting invalid value for ${pref.key} to ${pref.value}")
                } else if (currentValue != null) {
                    // Se entryValues for nulo ou vazio, mas havia um valor, limpe-o.
                    pref.value = null
                    Log.w("GlobalPrefs", "Cleared value for ${pref.key} due to empty/null entryValues.")
                }
            }
        }
    }

    private fun sortListPreferenceByEntries(listPreference: ListPreference?) {
        listPreference?.let { preference ->
            if (preference.entries == null || preference.entryValues == null || preference.entries.size != preference.entryValues.size) {
                Log.w("GlobalPrefs", "Cannot sort ListPreference, null or mismatched entries/values: ${preference.key}")
                return
            }
            val entries = preference.entries
            val entryValues = preference.entryValues
            val currentLocale = context?.resources?.configuration?.locales?.get(0) ?: Locale.getDefault()
            val sortRules = Collator.getInstance(currentLocale)
            sortRules.strength = Collator.PRIMARY // Ignora case e acentos para ordenação primária

            val sorter = TreeMap<CharSequence, CharSequence>(sortRules) // K, V
            for (i in entries.indices) {
                // Evitar NullPointerException se uma entrada for nula (improvável mas seguro)
                val entryText = entries[i] ?: continue
                if (!sorter.containsKey(entryText)) { // Evita duplicatas na chave de ordenação
                    sorter.put(entryText, entryValues[i])
                } else {
                    // Loga duplicata, mas continua (a primeira ocorrência será mantida no TreeMap)
                    Log.w("GlobalPrefs", "Duplicate entry found while sorting: '$entryText' for key ${preference.key}")
                }
            }

            if (sorter.isEmpty) {
                Log.w("GlobalPrefs", "Sorter is empty, cannot sort preference: ${preference.key}")
                return
            }

            val sortedLabels = arrayOfNulls<CharSequence>(sorter.size)
            val sortedValues = arrayOfNulls<CharSequence>(sorter.size)
            var i = 0
            for (entry in sorter.entries) {
                sortedLabels[i] = entry.key
                sortedValues[i] = entry.value
                i++
            }

            val currentValue = preference.value // Salva o valor atual antes de redefinir as entradas
            preference.entries = sortedLabels
            preference.entryValues = sortedValues
            // Tenta restaurar o valor. Se o valor antigo não existir nas novas entryValues ordenadas,
            // validateListPreferenceValue (chamado após isso em updateLanguageListsForProvider)
            // deve corrigi-lo para o primeiro item.
            preference.value = currentValue
            validateListPreferenceValue(preference) // Garante que o valor ainda é válido após a ordenação
        }
    }

    private fun downloadModel(translateLanguageSelected: String, isFromLanguage: Boolean) {
        // Só baixa se o provedor global selecionado for Google
        if (translatorProviderPref?.value != "g") {
            utils.debugLog("GlobalPrefs: Skipping model download, global provider is not Google.")
            return
        }
        if (translateLanguageSelected.isEmpty() || translateLanguageSelected == "auto") {
            utils.debugLog("GlobalPrefs: Skipping model download for auto/empty language.")
            return
        }

        utils.debugLog("GlobalPrefs: Preparing download for language '$translateLanguageSelected' (isFrom: $isFromLanguage)")
        val sourceLanguage: String
        val targetLanguage: String
        try {
            sourceLanguage = if (isFromLanguage) translateLanguageSelected else TranslateLanguage.ENGLISH
            targetLanguage = if (isFromLanguage) TranslateLanguage.ENGLISH else translateLanguageSelected
        } catch (e: IllegalArgumentException) {
            Toast.makeText(context, "${getString(R.string.invalid_language_code)}: $translateLanguageSelected", Toast.LENGTH_SHORT).show()
            Log.e("GlobalPrefs", "Invalid language code for ML Kit: $translateLanguageSelected", e)
            return
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
        val mlKitTranslator = Translation.getClient(options)
        // Adicionar o Translator ao ciclo de vida do Fragmento para que ele seja fechado automaticamente
        viewLifecycleOwner.lifecycle.addObserver(mlKitTranslator)

        MaterialAlertDialogBuilder(requireContext()) // Usa o tema do contexto da Activity
            .setMessage(R.string.ask_download)
            .setPositiveButton(R.string.download_now) { dialogInterface, _ ->
                val progressView = LayoutInflater.from(requireContext()).inflate(R.layout.progress_dialog_layout, null)
                val progressDialog = MaterialAlertDialogBuilder(requireContext())
                    .setView(progressView)
                    .setCancelable(false)
                    .create()
                progressDialog.show()

                mlKitTranslator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        utils.debugLog("GlobalPrefs: Successfully Downloaded Translation model for $sourceLanguage -> $targetLanguage")
                        try { progressDialog.dismiss() } catch (e: Exception) { Log.w("GlobalPrefs", "Error dismissing progressDialog (success)", e) }
                        try { dialogInterface.dismiss() } catch (e: Exception) { Log.w("GlobalPrefs", "Error dismissing dialogInterface (success)", e) }
                        Toast.makeText(context, R.string.download_sucess, Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        utils.debugLog("GlobalPrefs: Could not Download Translation model for $sourceLanguage -> $targetLanguage")
                        Log.e("GlobalPrefs", "Model download error", e)
                        try { progressDialog.dismiss() } catch (ex: Exception) { Log.w("GlobalPrefs", "Error dismissing progressDialog (failure)", ex) }
                        try { dialogInterface.dismiss() } catch (ex: Exception) { Log.w("GlobalPrefs", "Error dismissing dialogInterface (failure)", ex) }
                        Toast.makeText(context, getString(R.string.download_failure) + ": " + e.localizedMessage, Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton(R.string.cancel) { dialogInterface, _ ->
                try { dialogInterface.dismiss() } catch (e: Exception) { Log.w("GlobalPrefs", "Error dismissing dialogInterface (cancel)", e) }
            }
            .show()
    }

    companion object {
        // Chaves para as preferências globais como definidas em preferences.xml
        private const val KEY_TRANSLATOR_PROVIDER = "TranslatorProvider"
        private const val KEY_GLOBAL_MS_KEY = "GlobalMicrosoftSubscriptionKey"
        private const val KEY_GLOBAL_MS_REGION_XML_KEY = "SubscriptionRegion" // Esta é a chave usada no XML para a região MS global
        private const val KEY_GLOBAL_YANDEX_KEY = "GlobalYandexSubscriptionKey"
        private const val KEY_TRANSLATE_FROM = "TranslateFromLanguage"
        private const val KEY_TRANSLATE_TO = "TranslateToLanguage"
    }
}