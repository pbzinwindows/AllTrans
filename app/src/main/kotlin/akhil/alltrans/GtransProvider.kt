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
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GtransProvider : ContentProvider() {

    // Cache para traduções já realizadas para evitar duplicação
    @GuardedBy("translationCache")
    private val translationCache: MutableMap<String, String> = ConcurrentHashMap()

    // Limite do cache para evitar uso excessivo de memória
    private val maxCacheSize = 1000

    @GuardedBy("translatorClients")
    private val translatorClients: MutableMap<String, Translator> =
        Collections.synchronizedMap(HashMap())

    override fun onCreate(): Boolean {
        Utils.debugLog("$TAG: onCreate")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor {
        val startTime = System.nanoTime()
        val requestId = generateRequestId()
        Utils.debugLog("$TAG: [$requestId] Received query URI: $uri")

        val uriFromLanguageOriginal = uri.getQueryParameter(KEY_FROM_LANGUAGE)
        val toLanguage = uri.getQueryParameter(KEY_TO_LANGUAGE)
        val textToTranslate = uri.getQueryParameter(KEY_TEXT_TO_TRANSLATE)

        // Validação de entrada mais robusta
        if (textToTranslate.isNullOrBlank()) {
            Log.w(TAG, "[$requestId] Text to translate is null or blank. URI: $uri")
            return createErrorCursor("Text to translate is null or blank", projection, startTime)
        }

        if (toLanguage.isNullOrBlank()) {
            Log.w(TAG, "[$requestId] Target language is null or blank. URI: $uri")
            return createErrorCursor("Target language is null or blank", projection, startTime)
        }

        // Normalizar texto para evitar traduções duplicadas de textos similares
        val normalizedText = textToTranslate.trim()

        // Verificar se o texto é muito curto ou apenas caracteres especiais
        if (normalizedText.length < 2 || normalizedText.matches(Regex("^[\\s\\p{P}\\p{S}]*$"))) {
            Utils.debugLog("$TAG: [$requestId] Text too short or only special characters, returning original: [$normalizedText]")
            return createOriginalTextCursor(normalizedText, projection, startTime)
        }

        var detectedLanguageCode: String? = null
        val languageIdentifier = LanguageIdentification.getClient()
        Utils.debugLog("$TAG: [$requestId] Text being sent for language identification: [$normalizedText]")

        try {
            detectedLanguageCode = Tasks.await(languageIdentifier.identifyLanguage(normalizedText), 5, TimeUnit.SECONDS)
            Utils.debugLog("$TAG: [$requestId] Language identification completed. Detected: '$detectedLanguageCode'")
        } catch (e: Exception) {
            Log.w(TAG, "[$requestId] Language identification failed for text: [$normalizedText]", e)
        }

        var actualSourceLanguage: String?
        val allKnownMlKitLanguages = TranslateLanguage.getAllLanguages()

        if (detectedLanguageCode != null &&
            detectedLanguageCode != UNDETERMINED_LANGUAGE &&
            allKnownMlKitLanguages.contains(detectedLanguageCode)
        ) {
            if (detectedLanguageCode == toLanguage) {
                Utils.debugLog("$TAG: [$requestId] Detected language same as target, skipping translation")
                return createOriginalTextCursor(normalizedText, projection, startTime)
            }
            actualSourceLanguage = detectedLanguageCode
            Utils.debugLog("$TAG: [$requestId] Using detected language '$actualSourceLanguage' as source")
        } else {
            Utils.debugLog("$TAG: [$requestId] Using URI fromLanguage as source: '$uriFromLanguageOriginal'")
            actualSourceLanguage = uriFromLanguageOriginal
        }

        if (actualSourceLanguage.isNullOrBlank() ||
            actualSourceLanguage == "auto" ||
            actualSourceLanguage == UNDETERMINED_LANGUAGE ||
            !allKnownMlKitLanguages.contains(actualSourceLanguage)
        ) {
            val reason = when {
                actualSourceLanguage.isNullOrBlank() -> "null or blank"
                actualSourceLanguage == "auto" -> "'auto' (not directly translatable)"
                actualSourceLanguage == UNDETERMINED_LANGUAGE -> "'undetermined'"
                !allKnownMlKitLanguages.contains(actualSourceLanguage) -> "not supported ('$actualSourceLanguage')"
                else -> "unknown reason"
            }
            Log.w(TAG, "[$requestId] Invalid source language: $reason")
            return createErrorCursorWithFallbackText(
                "Source language invalid ($reason)",
                projection,
                normalizedText,
                startTime
            )
        }

        if (!allKnownMlKitLanguages.contains(toLanguage)) {
            Log.w(TAG, "[$requestId] Target language not supported: '$toLanguage'")
            return createErrorCursorWithFallbackText(
                "Target language not supported ('$toLanguage')",
                projection,
                normalizedText,
                startTime
            )
        }

        if (actualSourceLanguage == toLanguage) {
            Utils.debugLog("$TAG: [$requestId] Source equals target language, skipping translation")
            return createOriginalTextCursor(normalizedText, projection, startTime)
        }

        // Criar chave única para cache incluindo o texto exato
        val cacheKey = "${actualSourceLanguage}##${toLanguage}##${normalizedText.hashCode()}"

        // Verificar cache primeiro
        val cachedTranslation = translationCache[cacheKey]
        if (cachedTranslation != null) {
            Utils.debugLog("$TAG: [$requestId] Using cached translation for: [$normalizedText]")
            return createSuccessCursor(cachedTranslation, projection, startTime)
        }

        Log.d(TAG, "[$requestId] Proceeding with translation: '$actualSourceLanguage' -> '$toLanguage'")

        val translatorKey = "$actualSourceLanguage##$toLanguage"
        val translator = getOrCreateTranslator(translatorKey, actualSourceLanguage, toLanguage, requestId)
            ?: return createErrorCursorWithFallbackText(
                "Failed to create translator",
                projection,
                normalizedText,
                startTime
            )

        val translationCallable = Callable {
            try {
                val task = translator.translate(normalizedText)
                val result = Tasks.await(task, 10, TimeUnit.SECONDS)
                Utils.debugLog("$TAG: [$requestId] Translation successful. Result: [$result]")

                val finalResult = if (!result.isNullOrBlank() && result != normalizedText) {
                    // Cache da tradução bem-sucedida
                    synchronized(translationCache) {
                        if (translationCache.size >= maxCacheSize) {
                            // Remove entrada mais antiga (implementação simples)
                            val firstKey = translationCache.keys.firstOrNull()
                            if (firstKey != null) {
                                translationCache.remove(firstKey)
                            }
                        }
                        translationCache[cacheKey] = result
                    }
                    result
                } else {
                    normalizedText
                }

                finalResult
            } catch (e: TimeoutException) {
                Log.w(TAG, "[$requestId] Translation timed out")
                normalizedText
            } catch (e: Exception) {
                handleTranslationException(e, actualSourceLanguage, toLanguage, normalizedText, requestId)
            }
        }

        var translatedString: String?
        try {
            val futureResult: Future<String?> = mlKitExecutor!!.submit(translationCallable)
            translatedString = futureResult.get(12, TimeUnit.SECONDS)
            Utils.debugLog("$TAG: [$requestId] Future task completed. Result: [$translatedString]")
        } catch (e: TimeoutException) {
            Log.w(TAG, "[$requestId] Future task timed out")
            translatedString = normalizedText
        } catch (e: Exception) {
            Log.e(TAG, "[$requestId] Future task failed", e)
            translatedString = normalizedText
        }

        val durationNs = System.nanoTime() - startTime
        Utils.debugLog("$TAG: [$requestId] Processing took ${TimeUnit.NANOSECONDS.toMillis(durationNs)}ms")

        return createSuccessCursor(translatedString ?: normalizedText, projection, startTime)
    }

    private fun generateRequestId(): String {
        return System.currentTimeMillis().toString(36) + (Math.random() * 1000).toInt().toString(36)
    }

    private fun getOrCreateTranslator(
        hashKey: String,
        sourceLanguage: String,
        targetLanguage: String,
        requestId: String
    ): Translator? {
        synchronized(translatorClients) {
            var translator = translatorClients[hashKey]
            if (translator == null) {
                Utils.debugLog("$TAG: [$requestId] Creating new Translator for $sourceLanguage -> $targetLanguage")
                try {
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLanguage)
                        .setTargetLanguage(targetLanguage)
                        .build()
                    translator = Translation.getClient(options)
                    translatorClients[hashKey] = translator
                } catch (e: Exception) {
                    Log.e(TAG, "[$requestId] Failed to create Translator client", e)
                    return null
                }
            }
            return translator
        }
    }

    private fun handleTranslationException(
        e: Exception,
        sourceLanguage: String,
        targetLanguage: String,
        text: String,
        requestId: String
    ): String {
        val mlKitCause = e.cause as? MlKitException
        if (mlKitCause != null) {
            val errorMessage = mlKitCause.message?.lowercase(Locale.ROOT) ?: ""
            if (errorMessage.contains("model") &&
                (errorMessage.contains("unavailable") ||
                        errorMessage.contains("download") ||
                        errorMessage.contains("space") ||
                        errorMessage.contains("not found"))
            ) {
                Log.w(TAG, "[$requestId] ML Kit model issue: ${mlKitCause.message}", e)
                return MLKIT_MODEL_UNAVAILABLE_ERROR
            }
        } else {
            val genericErrorMessage = e.message?.lowercase(Locale.ROOT) ?: ""
            if (genericErrorMessage.contains("model") &&
                (genericErrorMessage.contains("unavailable") ||
                        genericErrorMessage.contains("download") ||
                        genericErrorMessage.contains("space") ||
                        genericErrorMessage.contains("not found"))
            ) {
                Log.w(TAG, "[$requestId] ML Kit model issue (direct): ${e.message}", e)
                return MLKIT_MODEL_UNAVAILABLE_ERROR
            }
        }

        Log.e(TAG, "[$requestId] Translation failed for: [$text] ($sourceLanguage->$targetLanguage)", e)
        return text
    }

    private fun createSuccessCursor(
        translatedText: String,
        projection: Array<String?>?,
        startTimeNanos: Long
    ): MatrixCursor {
        val columns = if (projection.isNullOrEmpty()) {
            arrayOf(COLUMN_TRANSLATE)
        } else {
            projection
        }

        val cursor = MatrixCursor(columns)
        val builder = cursor.newRow()

        for (col in columns) {
            if (COLUMN_TRANSLATE.equals(col, ignoreCase = true)) {
                builder.add(translatedText)
            } else {
                builder.add(null)
            }
        }

        return cursor
    }

    private fun createOriginalTextCursor(
        originalText: String?,
        projection: Array<String?>?,
        startTimeNanos: Long
    ): MatrixCursor {
        val columns = if (projection.isNullOrEmpty() ||
            !projection.any { it.equals(COLUMN_TRANSLATE, ignoreCase = true) }) {
            arrayOf(COLUMN_TRANSLATE)
        } else {
            projection
        }

        val cursor = MatrixCursor(columns)
        val rowBuilder = cursor.newRow()

        for (colName in columns) {
            if (COLUMN_TRANSLATE.equals(colName, ignoreCase = true)) {
                rowBuilder.add(originalText)
            } else {
                rowBuilder.add(null)
            }
        }

        val durationNs = System.nanoTime() - startTimeNanos
        Utils.debugLog("$TAG: Query processing (original returned) took ${TimeUnit.NANOSECONDS.toMillis(durationNs)}ms")
        return cursor
    }

    private fun createErrorCursorWithFallbackText(
        errorMessage: String?,
        projection: Array<String?>?,
        originalText: String?,
        startTimeNanos: Long
    ): MatrixCursor {
        val columns = if (projection.isNullOrEmpty() ||
            !projection.any { it.equals(COLUMN_TRANSLATE, ignoreCase = true) }) {
            arrayOf(COLUMN_TRANSLATE)
        } else {
            projection
        }

        val cursor = MatrixCursor(columns)
        if (!errorMessage.isNullOrEmpty()) {
            val extrasBundle = Bundle()
            extrasBundle.putString("error", errorMessage)
            cursor.extras = extrasBundle
        }

        val rowBuilder = cursor.newRow()
        for (colName in columns) {
            if (COLUMN_TRANSLATE.equals(colName, ignoreCase = true)) {
                rowBuilder.add(originalText)
            } else {
                rowBuilder.add(null)
            }
        }

        val durationNs = System.nanoTime() - startTimeNanos
        Utils.debugLog("$TAG: Query processing (error with fallback) took ${TimeUnit.NANOSECONDS.toMillis(durationNs)}ms")
        return cursor
    }

    private fun createErrorCursor(
        errorMessage: String?,
        projection: Array<String?>?,
        startTimeNanos: Long
    ): MatrixCursor {
        val columnsToUse = if (projection.isNullOrEmpty() ||
            !projection.any { it.equals(COLUMN_TRANSLATE, ignoreCase = true) }) {
            arrayOf(COLUMN_TRANSLATE)
        } else {
            projection
        }

        val cursor = MatrixCursor(columnsToUse)
        if (!errorMessage.isNullOrEmpty()) {
            val extrasBundle = Bundle()
            extrasBundle.putString("error", errorMessage)
            cursor.extras = extrasBundle
        }

        val durationNs = System.nanoTime() - startTimeNanos
        Utils.debugLog("$TAG: Query processing (error) took ${TimeUnit.NANOSECONDS.toMillis(durationNs)}ms")
        return cursor
    }

    override fun shutdown() {
        super.shutdown()
        Utils.debugLog("$TAG: shutdown initiated")

        // Limpar cache
        synchronized(translationCache) {
            translationCache.clear()
            Utils.debugLog("$TAG: Translation cache cleared")
        }

        synchronized(translatorClients) {
            Utils.debugLog("$TAG: Closing ${translatorClients.size} cached Translator clients")
            for ((key, translator) in translatorClients) {
                try {
                    translator.close()
                    Utils.debugLog("$TAG: Closed translator for $key")
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing translator for $key", e)
                }
            }
            translatorClients.clear()
        }

        mlKitExecutor?.let { executor ->
            if (!executor.isShutdown) {
                Utils.debugLog("$TAG: Shutting down ML Kit ExecutorService")
                executor.shutdown()
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow()
                        Utils.debugLog("$TAG: ML Kit ExecutorService shutdownNow called")
                    } else {
                        Utils.debugLog("$TAG: ML Kit ExecutorService terminated successfully")
                    }
                } catch (e: InterruptedException) {
                    executor.shutdownNow()
                    Utils.debugLog("$TAG: ML Kit ExecutorService shutdown interrupted")
                    Thread.currentThread().interrupt()
                }
            }
        }

        Utils.debugLog("$TAG: shutdown complete")
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        Log.w(TAG, "Call method invoked but not implemented (method: $method, arg: $arg)")
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Insert not supported by AllTrans GtransProvider")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?): Int {
        throw UnsupportedOperationException("Delete not supported by AllTrans GtransProvider")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String?>?
    ): Int {
        throw UnsupportedOperationException("Update not supported by AllTrans GtransProvider")
    }

    companion object {
        const val TAG = "AllTrans:gtransProv"
        const val COLUMN_TRANSLATE: String = "translate"
        const val UNDETERMINED_LANGUAGE = "und"
        const val MLKIT_MODEL_UNAVAILABLE_ERROR = "ALLTRANS_MLKIT_MODEL_UNAVAILABLE_ERROR_INTERNAL_MAGIC_STRING"

        val mlKitExecutor: ExecutorService? = Executors.newCachedThreadPool()

        const val KEY_TEXT_TO_TRANSLATE: String = "text"
        const val KEY_FROM_LANGUAGE: String = "from"
        const val KEY_TO_LANGUAGE: String = "to"
    }
}