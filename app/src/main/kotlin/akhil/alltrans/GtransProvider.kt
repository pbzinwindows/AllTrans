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

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.GuardedBy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// import android.os.ResultReceiver; // Não necessário nesta versão
class GtransProvider : ContentProvider() {
    @GuardedBy("translatorClients")
    private val translatorClients: MutableMap<String?, Translator> =
        Collections.synchronizedMap<String?, Translator?>(
            HashMap<String?, Translator?>()
        )

    override fun onCreate(): Boolean {
        Utils.debugLog(GtransProvider.Companion.TAG + ": onCreate")
        return true
    }

    // --- MÉTODO QUERY RESTAURADO ---
    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor? {
        val startTime = System.nanoTime()
        Utils.debugLog(GtransProvider.Companion.TAG + ": Received query URI: " + uri)

        // 1. Extract params
        val uriFromLanguage = uri.getQueryParameter(KEY_FROM_LANGUAGE)
        val toLanguage = uri.getQueryParameter(KEY_TO_LANGUAGE)
        val textToTranslate = uri.getQueryParameter(KEY_TEXT_TO_TRANSLATE)

        var actualSourceLanguage: String? = uriFromLanguage // Initialize with URI's from_language

        // 2. Initial null/empty checks for textToTranslate and toLanguage
        if (textToTranslate.isNullOrEmpty()) {
            Log.w(GtransProvider.Companion.TAG, "Text to translate is null or empty. URI: $uri")
            return createErrorCursor("Text to translate is null or empty", projection)
        }
        if (toLanguage.isNullOrEmpty()) {
            Log.w(GtransProvider.Companion.TAG, "Target language is null or empty. URI: $uri")
            return createErrorCursor("Target language is null or empty", projection)
        }

        // 3. Language Identification Block
        var detectedLanguageCode: String? = null
        // Only run if text is not empty (already checked above, but good practice if this block were moved)
        val languageIdentifier = LanguageIdentification.getClient()
        try {
            Utils.debugLog(GtransProvider.TAG + ": Attempting language identification for text: [$textToTranslate]")
            detectedLanguageCode = Tasks.await(languageIdentifier.identifyLanguage(textToTranslate), 5, TimeUnit.SECONDS)
            Utils.debugLog(GtransProvider.TAG + ": Language identification task completed. Detected: $detectedLanguageCode")
        } catch (e: Exception) { // Catches TimeoutException, InterruptedException, ExecutionException
            Log.w(GtransProvider.Companion.TAG, "Language identification task failed for text: [$textToTranslate]", e)
            // detectedLanguageCode remains null, proceed with fallback logic
        }

        // 4. Determine actualSourceLanguage
        if (detectedLanguageCode != null && detectedLanguageCode != "und") { // Corrected based on user feedback
            Utils.debugLog(GtransProvider.TAG + ": Language auto-detected: $detectedLanguageCode for text: [$textToTranslate]")
            actualSourceLanguage = detectedLanguageCode
        } else {
            Utils.debugLog(GtransProvider.TAG + ": Language detection failed or 'und'. Using URI fromLanguage: $uriFromLanguage as actualSourceLanguage.")
            actualSourceLanguage = uriFromLanguage // Fallback to URI's fromLanguage
            if (actualSourceLanguage == "auto" || actualSourceLanguage.isNullOrEmpty()) {
                Log.w(GtransProvider.Companion.TAG, "Cannot translate: Detection failed and URI fromLanguage is '$actualSourceLanguage'.")
                return createErrorCursor("Language unclear, cannot translate", projection)
            }
        }

        // At this point, actualSourceLanguage should be a concrete, non-null, non-"auto" language code.
        // Or we've returned an error cursor in the block above.

        // 5. Final checks and setup for translation
        if (actualSourceLanguage.isNullOrEmpty()) { // Safeguard, should have been caught by the logic above.
            Log.e(GtransProvider.Companion.TAG, "Critical Error: actualSourceLanguage is null or empty before translation logic.")
            return createErrorCursor("Source language could not be determined (critical error)", projection)
        }

        // Crucial Check: If actual source is the same as target, return original text
        if (actualSourceLanguage == toLanguage) {
            Utils.debugLog(GtransProvider.TAG + ": Skipping translation: Actual source language ($actualSourceLanguage) is the same as target language ($toLanguage).")
            val resultColumns: Array<String?> = if (projection == null || projection.isEmpty()) {
                arrayOf(COLUMN_TRANSLATE)
            } else {
                if (!projection.any { it.equals(COLUMN_TRANSLATE, ignoreCase = true) }) {
                    Log.w(GtransProvider.Companion.TAG, "Requested projection does not include '$COLUMN_TRANSLATE'. Returning original text in default column.")
                    return MatrixCursor(projection) // Return empty cursor matching projection
                }
                projection
            }
            val cursor = MatrixCursor(resultColumns)
            val rowBuilder = cursor.newRow()
            for (col in resultColumns) {
                if (COLUMN_TRANSLATE.equals(col, ignoreCase = true)) {
                    rowBuilder.add(textToTranslate)
                } else {
                    rowBuilder.add(null) // Add null for other projected columns
                }
            }
            return cursor
        }

        // Validate actualSourceLanguage and toLanguage codes
        try {
            if (!TranslateLanguage.getAllLanguages().contains(actualSourceLanguage)) {
                Log.w(GtransProvider.Companion.TAG, "Invalid 'actualSourceLanguage' code: $actualSourceLanguage after detection and fallback.")
                return createErrorCursor("Invalid 'from' language: $actualSourceLanguage", projection)
            }
            if (!TranslateLanguage.getAllLanguages().contains(toLanguage)) {
                Log.w(GtransProvider.Companion.TAG, "Invalid 'to' language code: $toLanguage")
                return createErrorCursor("Invalid 'to' language: $toLanguage", projection)
            }
        } catch (e: IllegalArgumentException) {
            Log.e(GtransProvider.Companion.TAG, "Language code validation failed unexpectedly", e)
            return createErrorCursor("Language code validation error", projection)
        }

        val hashKey = actualSourceLanguage + "##" + toLanguage
        val finalFromLang: String = actualSourceLanguage!!
        val finalToLang: String = toLanguage!!

        val finalTranslator: Translator?
        synchronized(translatorClients) {
            var translator = translatorClients.get(hashKey)
            if (translator == null) {
                Utils.debugLog(GtransProvider.Companion.TAG + ": Creating new Translator for $finalFromLang -> $finalToLang")
                try {
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(finalFromLang)
                        .setTargetLanguage(finalToLang)
                        .build()
                    translator = Translation.getClient(options)
                    translatorClients.put(hashKey, translator)
                } catch (e: Exception) {
                    Log.e(
                        GtransProvider.Companion.TAG,
                        "Failed to create Translator client for " + hashKey,
                        e
                    )
                    return createErrorCursor("Failed to create translator", projection)
                }
            }
            finalTranslator = translator
        }

        val translationTask = Callable {
            try {
                val task = finalTranslator!!.translate(textToTranslate)
                val result = Tasks.await<String?>(
                    task,
                    10,
                    TimeUnit.SECONDS
                )
                Utils.debugLog(GtransProvider.Companion.TAG + ": ML Kit translation successful for: [" + textToTranslate + "]")
                return@Callable if (result != null) result else textToTranslate
            } catch (e: TimeoutException) {
                Log.w(
                    GtransProvider.Companion.TAG,
                    "ML Kit translation timed out for: [" + textToTranslate + "]"
                )
                return@Callable textToTranslate
            } catch (e: Exception) {
                if (e.cause is MlKitException && (e.cause as MlKitException).getErrorCode() == MlKitException.UNAVAILABLE) {
                    Log.w(
                        GtransProvider.Companion.TAG,
                        "ML Kit model not available/downloaded for " + finalFromLang + "->" + finalToLang
                    )
                } else {
                    Log.e(
                        GtransProvider.Companion.TAG,
                        "ML Kit translation failed for: [" + textToTranslate + "]",
                        e
                    )
                }
                return@Callable textToTranslate
            }
        }

        val futureResult: Future<String?> =
            GtransProvider.Companion.mlKitExecutor!!.submit<String?>(translationTask)

        var translatedString: String? = textToTranslate
        try {
            translatedString = futureResult.get(12, TimeUnit.SECONDS)
            Utils.debugLog(GtransProvider.Companion.TAG + ": Future task completed. Result: [" + translatedString + "]")
        } catch (e: TimeoutException) {
            Log.w(GtransProvider.Companion.TAG, "Future task timed out waiting for ML Kit result.")
            futureResult.cancel(true)
            translatedString = textToTranslate
        } catch (e: Exception) {
            Log.e(GtransProvider.Companion.TAG, "Future task failed waiting for ML Kit result", e)
            translatedString = textToTranslate
        }

        val durationNs = System.nanoTime() - startTime
        Utils.debugLog(
            GtransProvider.Companion.TAG + ": Query processing took " + TimeUnit.NANOSECONDS.toMillis(
                durationNs
            ) + "ms"
        )

        val columns: Array<String?>?
        if (projection == null || projection.size == 0) {
            columns = arrayOf<String?>(COLUMN_TRANSLATE)
        } else {
            var found = false
            for (p in projection) {
                if (COLUMN_TRANSLATE.equals(p, ignoreCase = true)) {
                    found = true
                    break
                }
            }
            if (!found) {
                Log.w(
                    GtransProvider.Companion.TAG,
                    "Requested projection does not include '" + COLUMN_TRANSLATE + "'."
                )
                return MatrixCursor(projection)
            }
            columns = projection
        }

        val cursor = MatrixCursor(columns)
        val builder = cursor.newRow()
        for (col in columns) {
            if (COLUMN_TRANSLATE.equals(col, ignoreCase = true)) {
                builder.add(translatedString)
            } else {
                builder.add(null)
            }
        }
        return cursor
    }

    private fun createErrorCursor(errorMessage: String?, projection: Array<String?>?): Cursor {
        val columns: Array<String?> =
            if (projection == null || projection.size == 0) arrayOf<String?>(
                COLUMN_TRANSLATE
            ) else projection
        val cursor = MatrixCursor(columns)
        val extras = Bundle()
        extras.putString("error", errorMessage)
        cursor.setExtras(extras)
        return cursor
    }

    override fun shutdown() {
        super.shutdown()
        Utils.debugLog(GtransProvider.Companion.TAG + ": shutdown initiated.")
        synchronized(translatorClients) {
            Utils.debugLog(GtransProvider.Companion.TAG + ": Closing " + translatorClients.size + " cached Translator clients.")
            for (translator in translatorClients.values) {
                try {
                    translator.close()
                } catch (e: Exception) {
                    Log.w(GtransProvider.Companion.TAG, "Error closing translator.", e)
                }
            }
            translatorClients.clear()
        }
        if (GtransProvider.Companion.mlKitExecutor != null && !GtransProvider.Companion.mlKitExecutor.isShutdown()) {
            Utils.debugLog(GtransProvider.Companion.TAG + ": Shutting down ML Kit ExecutorService.")
            GtransProvider.Companion.mlKitExecutor.shutdown()
            try {
                if (!GtransProvider.Companion.mlKitExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    GtransProvider.Companion.mlKitExecutor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                GtransProvider.Companion.mlKitExecutor.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        Utils.debugLog(GtransProvider.Companion.TAG + ": shutdown complete.")
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        Log.w(
            GtransProvider.Companion.TAG,
            "Call method invoked, but not implemented. Returning null."
        )
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Insert not supported")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int {
        throw UnsupportedOperationException("Delete not supported")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int {
        throw UnsupportedOperationException("Update not supported")
    }

    companion object {
        private const val TAG = "AllTrans:gtransProv"
        const val COLUMN_TRANSLATE: String = "translate"

        private val mlKitExecutor: ExecutorService? =
            Executors.newCachedThreadPool()

        const val KEY_TEXT_TO_TRANSLATE: String = "text"
        const val KEY_FROM_LANGUAGE: String = "from"
        const val KEY_TO_LANGUAGE: String = "to"
        const val KEY_RESULT_RECEIVER: String = "receiver"
    }
}