package akhil.alltrans

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.text.Collator
import java.util.TreeMap

class GlobalPreferencesFragment : PreferenceFragmentCompat() {

    private var msTranslatorBatchPref: SwitchPreferenceCompat? = null

    // --- FUNÇÕES AUXILIARES ---

    @SuppressLint("ApplySharedPref")
    private fun handleSubProviderChange() {
        val translatorProvider = findPreference<ListPreference?>(KEY_TRANSLATOR_PROVIDER)
        val sharedPreferences = preferenceManager.sharedPreferences
        if (translatorProvider == null || sharedPreferences == null) return

        if (sharedPreferences.contains("EnableYandex")) {
            val subscriptionKey1: String = sharedPreferences.getString(KEY_SUBSCRIPTION_KEY, "")!!
            val defaultSubKey = getString(R.string.subKey_defaultValue)
            val isKeyEntered = subscriptionKey1.isNotEmpty() && subscriptionKey1 != defaultSubKey

            if (isKeyEntered) {
                if (sharedPreferences.getBoolean("EnableYandex", false)) {
                    translatorProvider.value = "y"
                } else {
                    translatorProvider.value = "m"
                }
                sharedPreferences.edit().remove("EnableYandex").commit()
            } else {
                sharedPreferences.edit().remove("EnableYandex").apply()
            }
        }
    }

    private fun handleProviderChange(translatorProviderSelected: String?) {
        val translateFromLanguage = findPreference<ListPreference?>(KEY_TRANSLATE_FROM)
        val translateToLanguage = findPreference<ListPreference?>(KEY_TRANSLATE_TO)
        val subscriptionKey = findPreference<EditTextPreference?>(KEY_SUBSCRIPTION_KEY)
        val subscriptionRegion = findPreference<EditTextPreference?>(KEY_SUBSCRIPTION_REGION)

        if (translateFromLanguage == null || translateToLanguage == null || subscriptionKey == null || subscriptionRegion == null) {
            Log.e("AllTrans", "One or more preference views are null in handleProviderChange.")
            return
        }

        var keyRequired = false

        if ("y" == translatorProviderSelected) {
            translateFromLanguage.setEntries(R.array.languageNamesYandex)
            translateFromLanguage.setEntryValues(R.array.languageCodesYandex)
            translateToLanguage.setEntries(R.array.languageNamesYandex)
            translateToLanguage.setEntryValues(R.array.languageCodesYandex)
            subscriptionKey.setTitle(getString(R.string.subKey_yandex))
            subscriptionRegion.setTitle(getString(R.string.subRegion_yandex))
            keyRequired = true
            // Hide batch translation for Yandex
            msTranslatorBatchPref?.isVisible = false
        } else if ("m" == translatorProviderSelected) {
            translateFromLanguage.setEntries(R.array.languageNames)
            translateFromLanguage.setEntryValues(R.array.languageCodes)
            translateToLanguage.setEntries(R.array.languageNames)
            translateToLanguage.setEntryValues(R.array.languageCodes)
            subscriptionKey.setTitle(getString(R.string.subKey_micro))
            subscriptionRegion.setTitle(getString(R.string.subRegion_micro))
            keyRequired = true
            // Show batch translation for Microsoft
            msTranslatorBatchPref?.isVisible = true
        } else {
            translateFromLanguage.setEntries(R.array.languageNamesGoogle)
            translateFromLanguage.setEntryValues(R.array.languageCodesGoogle)
            translateToLanguage.setEntries(R.array.languageNamesGoogle)
            translateToLanguage.setEntryValues(R.array.languageCodesGoogle)
            subscriptionKey.setTitle(getString(R.string.subKey))
            subscriptionRegion.setTitle(getString(R.string.subRegion_title))
            keyRequired = false
            // Hide batch translation for Google
            msTranslatorBatchPref?.isVisible = false
        }

        subscriptionKey.isEnabled = keyRequired
        subscriptionRegion.isEnabled = keyRequired

        sortListPreferenceByEntries(KEY_TRANSLATE_FROM)
        sortListPreferenceByEntries(KEY_TRANSLATE_TO)

        validateListPreferenceValue(translateFromLanguage)
        validateListPreferenceValue(translateToLanguage)
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
                Log.w("AllTrans", "Resetting invalid value for preference: " + listPreference.key)
            }
        }
    }

    private fun sortListPreferenceByEntries(preferenceKey: String) {
        val preference = findPreference<ListPreference?>(preferenceKey)
        if (preference == null || preference.entries == null || preference.entryValues == null) {
            Log.w("AllTrans", "Cannot sort ListPreference, null or missing entries/values: $preferenceKey")
            return
        }

        val entries = preference.entries
        val entryValues = preference.entryValues

        if (entries.size != entryValues.size) {
            Log.e("AllTrans", "Mismatch between entries and entryValues length for: $preferenceKey")
            return
        }

        val sortRules = Collator.getInstance(resources.configuration.locales.get(0))
        sortRules.strength = Collator.PRIMARY

        val sorter = TreeMap<CharSequence?, CharSequence?>(sortRules)
        for (i in entries.indices) {
            if (!sorter.containsKey(entries[i])) {
                sorter.put(entries[i], entryValues[i])
            } else {
                Log.w("AllTrans", "Duplicate entry found while sorting: " + entries[i])
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

    // Função downloadModel (ATUALIZADA com tema de overlay)
    private fun downloadModel(translateLanguageSelected: String?, isFromLanguage: Boolean) {
        val globalSettings = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val translatorProviderSelected1: String = globalSettings.getString(
            KEY_TRANSLATOR_PROVIDER,
            "g"
        )!!

        if ("g" != translatorProviderSelected1) {
            Log.d("AllTrans", "Skipping model download, Google provider not selected.")
            return
        }
        if (translateLanguageSelected == null || translateLanguageSelected.isEmpty() || translateLanguageSelected == "auto") {
            Log.d("AllTrans", "Skipping model download for invalid/auto language: $translateLanguageSelected")
            return
        }

        Utils.debugLog("Preparing download for Translation model for Language $translateLanguageSelected isFromLanguage $isFromLanguage")
        val sourceLanguage: String?
        val targetLanguage: String?
        try {
            if (isFromLanguage) {
                sourceLanguage = translateLanguageSelected
                targetLanguage = TranslateLanguage.ENGLISH
            } else {
                sourceLanguage = TranslateLanguage.ENGLISH
                targetLanguage = translateLanguageSelected
            }
        } catch (e: IllegalArgumentException) {
            Log.e("AllTrans", "Invalid language code for ML Kit: $translateLanguageSelected", e)
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
            Log.e("AllTrans", "Could not add lifecycle observer for translator", e)
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
                        Utils.debugLog("Successfully Downloaded Translation model!")
                        try { progressDialog.dismiss() } catch (e: Exception) { Log.w("AllTrans", "Error dismissing progressDialog", e) }
                        try { dialogInterface.dismiss() } catch (e: Exception) { Log.w("AllTrans", "Error dismissing dialogInterface", e) }
                        Toast.makeText(context, R.string.download_sucess, Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Utils.debugLog("Could not Download Translation model!")
                        Utils.debugLog("Download error - " + Log.getStackTraceString(e))
                        try { progressDialog.dismiss() } catch (e: Exception) { Log.w("AllTrans", "Error dismissing progressDialog on failure", e) }
                        try { dialogInterface.dismiss() } catch (e: Exception) { Log.w("AllTrans", "Error dismissing dialogInterface on failure", e) }
                        Toast.makeText(context, R.string.download_failure, Toast.LENGTH_LONG).show()
                    }
            }
            .setNegativeButton(R.string.cancel) { dialogInterface, _ ->
                try { dialogInterface.dismiss() } catch (e: Exception) { Log.w("AllTrans", "Error dismissing dialogInterface on cancel", e) }
            }
            .show()
    }

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        val preferenceManager = preferenceManager
        preferenceManager.sharedPreferencesName = "AllTransPref"
        addPreferencesFromResource(R.xml.preferences)

        handleSubProviderChange()

        // Get reference to the batch translation preference
        msTranslatorBatchPref = findPreference("global_ms_batch_translate_enabled")

        val translatorProvider = findPreference<ListPreference?>(KEY_TRANSLATOR_PROVIDER)

        if (translatorProvider != null) {
            val currentProvider = translatorProvider.value

            // Initialize the visibility of the batch translation option based on current provider
            msTranslatorBatchPref?.isVisible = currentProvider == "m"

            handleProviderChange(currentProvider)

            translatorProvider.setOnPreferenceChangeListener { _, newValue ->
                val translatorProviderSelected = newValue as String?
                handleProviderChange(translatorProviderSelected ?: "g")

                // Update the visibility of batch translation option when provider changes
                msTranslatorBatchPref?.isVisible = translatorProviderSelected == "m"

                true
            }
        } else {
            Log.e("AllTrans", "TranslatorProvider preference not found!")
        }

        val translateFromLanguage = findPreference<ListPreference?>(KEY_TRANSLATE_FROM)
        val translateToLanguage = findPreference<ListPreference?>(KEY_TRANSLATE_TO)

        if (translateFromLanguage != null) {
            translateFromLanguage.setOnPreferenceChangeListener { _, newValue ->
                val translateLanguageSelected = newValue as String?
                val providerPref = findPreference<ListPreference?>(KEY_TRANSLATOR_PROVIDER)
                val currentProviderValue = providerPref?.value ?: "g"
                Utils.debugLog("TranslateFrom listener triggered. Lang: $translateLanguageSelected, Provider (from object): $currentProviderValue")
                if ("g" == currentProviderValue) {
                    if(translateLanguageSelected != null) {
                        downloadModel(translateLanguageSelected, true)
                    } else {
                        Utils.debugLog("Skipping downloadModel call: selected language is null")
                    }
                } else {
                    Utils.debugLog("Skipping model download prompt (reading pref obj) in FromLanguage listener, provider is: $currentProviderValue")
                }
                true
            }
        }

        if (translateToLanguage != null) {
            translateToLanguage.setOnPreferenceChangeListener { _, newValue ->
                val translateLanguageSelected = newValue as String?
                val providerPref = findPreference<ListPreference?>(KEY_TRANSLATOR_PROVIDER)
                val currentProviderValue = providerPref?.value ?: "g"
                Utils.debugLog("TranslateTo listener triggered. Lang: $translateLanguageSelected, Provider (from object): $currentProviderValue")
                if ("g" == currentProviderValue) {
                    if(translateLanguageSelected != null) {
                        downloadModel(translateLanguageSelected, false)
                    } else {
                        Utils.debugLog("Skipping downloadModel call: selected language is null")
                    }
                } else {
                    Utils.debugLog("Skipping model download prompt (reading pref obj) in ToLanguage listener, provider is: $currentProviderValue")
                }
                true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_TRANSLATOR_PROVIDER = "TranslatorProvider"
        private const val KEY_SUBSCRIPTION_KEY = "SubscriptionKey"
        private const val KEY_SUBSCRIPTION_REGION = "SubscriptionRegion"
        private const val KEY_TRANSLATE_FROM = "TranslateFromLanguage"
        private const val KEY_TRANSLATE_TO = "TranslateToLanguage"
    }
}