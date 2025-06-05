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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GtransProvider : ContentProvider() {
    @GuardedBy("translatorClients")
    private val translatorClients: MutableMap<String, Translator> =
        Collections.synchronizedMap(HashMap()) // Tipos explícitos removidos

    override fun onCreate(): Boolean {
        Utils.debugLog("$TAG: onCreate") // Removido Companion. se TAG for acessível
        return true
    }

    // Corrigido: Retorno agora é Cursor (não nulo)
    override fun query(
        uri: Uri,
        projection: Array<String?>?,
        selection: String?,
        selectionArgs: Array<String?>?,
        sortOrder: String?
    ): Cursor { // Alterado de Cursor? para Cursor
        val startTime = System.nanoTime()
        Utils.debugLog("$TAG: Received query URI: $uri") // Removido Companion.

        val uriFromLanguageOriginal = uri.getQueryParameter(KEY_FROM_LANGUAGE) // Deve ser val
        val toLanguage = uri.getQueryParameter(KEY_TO_LANGUAGE)
        val textToTranslate = uri.getQueryParameter(KEY_TEXT_TO_TRANSLATE)

        if (textToTranslate.isNullOrEmpty()) {
            Log.w(TAG, "Text to translate is null or empty. URI: $uri") // Removido Companion.
            return createErrorCursor("Text to translate is null or empty", projection, startTime)
        }
        if (toLanguage.isNullOrEmpty()) {
            Log.w(TAG, "Target language is null or empty. URI: $uri") // Removido Companion.
            return createErrorCursor("Target language is null or empty", projection, startTime)
        }

        var detectedLanguageCode: String? = null
        val languageIdentifier = LanguageIdentification.getClient()
        Utils.debugLog("$TAG: Text being sent for language identification: [$textToTranslate]") // Removido Companion.
        try {
            detectedLanguageCode = Tasks.await(languageIdentifier.identifyLanguage(textToTranslate), 5, TimeUnit.SECONDS)
            Utils.debugLog("$TAG: Language identification task completed for text '[$textToTranslate]'. Detected: '$detectedLanguageCode'") // Removido Companion.
        } catch (e: Exception) {
            Log.w(TAG, "Language identification task failed for text: [$textToTranslate]", e) // Removido Companion.
        }

        var actualSourceLanguage: String?
        val allKnownMlKitLanguages = TranslateLanguage.getAllLanguages()

        if (detectedLanguageCode != null &&
            detectedLanguageCode != UNDETERMINED_LANGUAGE &&
            allKnownMlKitLanguages.contains(detectedLanguageCode)
        ) {
            if (detectedLanguageCode == toLanguage) {
                Utils.debugLog("$TAG: Detected language ('$detectedLanguageCode') is same as target language ('$toLanguage'). Skipping translation for: [$textToTranslate]") // Removido Companion.
                return createOriginalTextCursor(textToTranslate, projection, startTime)
            }
            actualSourceLanguage = detectedLanguageCode
            Utils.debugLog("$TAG: Using detected language '$actualSourceLanguage' as source. URI 'fromLanguage' was '$uriFromLanguageOriginal'. Text: [$textToTranslate]") // Removido Companion.
        } else {
            Utils.debugLog("$TAG: Language detection not definitive (Detected: '$detectedLanguageCode', URI fromLanguage: '$uriFromLanguageOriginal'). Using URI fromLanguage as source. Text: [$textToTranslate]") // Removido Companion.
            actualSourceLanguage = uriFromLanguageOriginal
        }

        if (actualSourceLanguage.isNullOrEmpty() ||
            actualSourceLanguage == "auto" ||
            actualSourceLanguage == UNDETERMINED_LANGUAGE ||
            !allKnownMlKitLanguages.contains(actualSourceLanguage)
        ) {
            val reason = when {
                actualSourceLanguage.isNullOrEmpty() -> "null or empty"
                actualSourceLanguage == "auto" -> "'auto' (not directly translatable)"
                actualSourceLanguage == UNDETERMINED_LANGUAGE -> "'undetermined'"
                !allKnownMlKitLanguages.contains(actualSourceLanguage) -> "not an ML Kit supported language code ('$actualSourceLanguage')"
                else -> "unknown reason for invalidity ('$actualSourceLanguage')"
            }
            Log.w(TAG, "Error: Final source language is $reason. Cannot translate text: [$textToTranslate]") // Removido Companion.
            return createErrorCursorWithFallbackText("Source language could not be reliably determined or is invalid ($reason for '$actualSourceLanguage').", projection, textToTranslate, startTime)
        }

        if (!allKnownMlKitLanguages.contains(toLanguage)) {
            Log.w(TAG, "Error: Target language ('$toLanguage') is not an ML Kit supported language code. Cannot translate text: [$textToTranslate]") // Removido Companion.
            return createErrorCursorWithFallbackText("Target language ('$toLanguage') is not an ML Kit supported language code.", projection, textToTranslate, startTime)
        }

        if (actualSourceLanguage == toLanguage) {
            Utils.debugLog("$TAG: SAFEGUARD CHECK: Final actual source language ('$actualSourceLanguage') is same as target language ('$toLanguage'). Skipping translation. Text: [$textToTranslate]") // Removido Companion.
            return createOriginalTextCursor(textToTranslate, projection, startTime)
        }

        Log.d(TAG, "Proceeding with translation for text '[$textToTranslate]' from '$actualSourceLanguage' to '$toLanguage'") // Removido Companion.

        val finalFromLang = actualSourceLanguage
        // val finalToLang = toLanguage; // Variável inlinada

        val hashKey = "$finalFromLang##$toLanguage" // Usando toLanguage diretamente
        val finalTranslator: Translator?
        synchronized(translatorClients) {
            var translator = translatorClients[hashKey]
            if (translator == null) {
                Utils.debugLog("$TAG: Creating new Translator for $finalFromLang -> $toLanguage") // Removido Companion.
                try {
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(finalFromLang)
                        .setTargetLanguage(toLanguage) // Usando toLanguage diretamente
                        .build()
                    translator = Translation.getClient(options)
                    translatorClients[hashKey] = translator
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create Translator client for $hashKey. From: $finalFromLang, To: $toLanguage", e) // Removido Companion.
                    return createErrorCursorWithFallbackText("Failed to create translator for $finalFromLang -> $toLanguage", projection, textToTranslate, startTime)
                }
            }
            finalTranslator = translator
        }

        if (finalTranslator == null) {
            Log.e(TAG, "Translator client is unexpectedly null for $hashKey") // Removido Companion.
            return createErrorCursorWithFallbackText("Translator client initialization failed unexpectedly.", projection, textToTranslate, startTime)
        }

        val translationCallable = Callable {
            try {
                val task = finalTranslator.translate(textToTranslate)
                val result = Tasks.await<String?>(task, 10, TimeUnit.SECONDS) // Tipo explícito pode ser necessário para Tasks.await se não inferido
                Utils.debugLog("$TAG: ML Kit translation for '[$textToTranslate]' ($finalFromLang->$toLanguage) successful. Result: [$result]") // Removido Companion.
                if (!result.isNullOrEmpty()) result else textToTranslate
            } catch (e: TimeoutException) {
                Log.w(TAG, "ML Kit translation timed out for: [$textToTranslate] ($finalFromLang->$toLanguage)") // Removido Companion.
                textToTranslate
            } catch (e: Exception) {
                val mlKitCause = e.cause as? MlKitException
                if (mlKitCause != null) {
                    val errorMessageText = mlKitCause.message?.lowercase(Locale.ROOT) ?: ""
                    if (errorMessageText.contains("model") &&
                        (errorMessageText.contains("unavailable") || errorMessageText.contains("download") || errorMessageText.contains("space") || errorMessageText.contains("not found"))
                    ) {
                        Log.w(TAG, "ML Kit model issue for $finalFromLang->$toLanguage. Message: ${mlKitCause.message}. Text: [$textToTranslate]", e) // Removido Companion.
                        MLKIT_MODEL_UNAVAILABLE_ERROR
                    } else {
                        Log.e(TAG, "ML Kit (cause) translation failed for: [$textToTranslate] ($finalFromLang->$toLanguage)", e) // Removido Companion.
                        textToTranslate
                    }
                } else {
                    val genericErrorMessageText = e.message?.lowercase(Locale.ROOT) ?: ""
                    if (genericErrorMessageText.contains("model") &&
                        (genericErrorMessageText.contains("unavailable") || genericErrorMessageText.contains("download") || genericErrorMessageText.contains("space") || genericErrorMessageText.contains("not found"))) {
                        Log.w(TAG, "ML Kit model issue (direct exception) for $finalFromLang->$toLanguage. Message: ${e.message}. Text: [$textToTranslate]", e) // Removido Companion.
                        MLKIT_MODEL_UNAVAILABLE_ERROR
                    } else {
                        Log.e(TAG, "ML Kit (unknown) translation failed for: [$textToTranslate] ($finalFromLang->$toLanguage)", e) // Removido Companion.
                        textToTranslate
                    }
                }
            }
        }

        var translatedString: String?
        try {
            val futureResult: Future<String?> = mlKitExecutor!!.submit(translationCallable) // Removido tipo explícito <String?>
            translatedString = futureResult.get(12, TimeUnit.SECONDS)
            Utils.debugLog("$TAG: Future task completed. Original: [$textToTranslate], Translated: [$translatedString]") // Removido Companion.
        } catch (e: TimeoutException) {
            Log.w(TAG, "Future task timed out waiting for ML Kit result for: [$textToTranslate].") // Removido Companion.
            translatedString = textToTranslate
        } catch (e: Exception) {
            Log.e(TAG, "Future task failed waiting for ML Kit result for: [$textToTranslate]", e) // Removido Companion.
            translatedString = textToTranslate
        }

        val durationNs = System.nanoTime() - startTime
        Utils.debugLog("$TAG: Query processing for '[$textToTranslate]' ($finalFromLang->$toLanguage) took ${TimeUnit.NANOSECONDS.toMillis(durationNs)}ms. Final Result: [$translatedString]") // Removido Companion.

        val columnsResult = if (projection.isNullOrEmpty()) arrayOf(COLUMN_TRANSLATE) else projection

        val cursor = MatrixCursor(columnsResult)
        val builder = cursor.newRow()
        var translateColumnWasPopulated = false
        for (col in columnsResult) {
            if (COLUMN_TRANSLATE.equals(col, ignoreCase = true)) {
                builder.add(translatedString)
                translateColumnWasPopulated = true
            } else {
                builder.add(null)
            }
        }
        if (!translateColumnWasPopulated && columnsResult.any{it.equals(COLUMN_TRANSLATE, ignoreCase = true)}) {
            Utils.debugLog("$TAG: WARNING - Projection included '$COLUMN_TRANSLATE' but it was not populated in the final cursor row.") // Removido Companion.
        }
        return cursor
    }

    private fun createOriginalTextCursor(originalText: String?, projection: Array<String?>?, startTimeNanos: Long): MatrixCursor {
        val columns = if (projection.isNullOrEmpty() || !projection.any { it.equals(COLUMN_TRANSLATE, ignoreCase = true) }) {
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
        val durationNsOnSkip = System.nanoTime() - startTimeNanos
        Utils.debugLog("$TAG: Query processing (skipped, original returned) took ${TimeUnit.NANOSECONDS.toMillis(durationNsOnSkip)}ms") // Removido Companion.
        return cursor
    }

    private fun createErrorCursorWithFallbackText(errorMessage: String?, projection: Array<String?>?, originalText: String?, startTimeNanos: Long): MatrixCursor {
        val columns = if (projection.isNullOrEmpty() || !projection.any { it.equals(COLUMN_TRANSLATE, ignoreCase = true) }) {
            arrayOf(COLUMN_TRANSLATE)
        } else {
            projection
        }
        val cursor = MatrixCursor(columns)
        if (!errorMessage.isNullOrEmpty()) {
            val extrasBundle = Bundle() // Nomeado para clareza
            extrasBundle.putString("error", errorMessage)
            cursor.extras = extrasBundle // Usando property access syntax
        }
        val rowBuilder = cursor.newRow()
        for (colName in columns) {
            if (COLUMN_TRANSLATE.equals(colName, ignoreCase = true)) {
                rowBuilder.add(originalText)
            } else {
                rowBuilder.add(null)
            }
        }
        val durationNsOnError = System.nanoTime() - startTimeNanos
        Utils.debugLog("$TAG: Query processing (error with fallback text) took ${TimeUnit.NANOSECONDS.toMillis(durationNsOnError)}ms. Error: $errorMessage") // Removido Companion.
        return cursor
    }

    private fun createErrorCursor(errorMessage: String?, projection: Array<String?>?, startTimeNanos: Long): MatrixCursor {
        val columnsToUse = if (projection.isNullOrEmpty() || !projection.any { it.equals(COLUMN_TRANSLATE, ignoreCase = true) } ) {
            arrayOf(COLUMN_TRANSLATE)
        } else {
            projection
        }
        val cursor = MatrixCursor(columnsToUse)
        if (!errorMessage.isNullOrEmpty()) {
            val extrasBundle = Bundle() // Nomeado para clareza
            extrasBundle.putString("error", errorMessage)
            cursor.extras = extrasBundle // Usando property access syntax
        }
        val durationNsOnError = System.nanoTime() - startTimeNanos
        Utils.debugLog("$TAG: Query processing (error, no fallback text in row) took ${TimeUnit.NANOSECONDS.toMillis(durationNsOnError)}ms. Error: $errorMessage") // Removido Companion.
        return cursor
    }

    override fun shutdown() {
        super.shutdown()
        Utils.debugLog("$TAG: shutdown initiated.") // Removido Companion.
        synchronized(translatorClients) {
            Utils.debugLog("$TAG: Closing ${translatorClients.size} cached Translator clients.") // Removido Companion.
            for ((key, translator) in translatorClients) {
                try {
                    translator.close()
                    Utils.debugLog("$TAG: Closed translator for $key") // Removido Companion.
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing translator for $key.", e) // Removido Companion.
                }
            }
            translatorClients.clear()
        }
        // mlKitExecutor é nullable, então usar ?.
        mlKitExecutor?.let { executor ->
            if (!executor.isShutdown) {
                Utils.debugLog("$TAG: Shutting down ML Kit ExecutorService.") // Removido Companion.
                executor.shutdown()
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow()
                        Utils.debugLog("$TAG: ML Kit ExecutorService shutdownNow called.") // Removido Companion.
                    } else {
                        Utils.debugLog("$TAG: ML Kit ExecutorService terminated successfully.") // Removido Companion.
                    }
                } catch (e: InterruptedException) {
                    executor.shutdownNow()
                    Utils.debugLog("$TAG: ML Kit ExecutorService shutdown interrupted, calling shutdownNow.") // Removido Companion.
                    Thread.currentThread().interrupt()
                }
            }
        }
        Utils.debugLog("$TAG: shutdown complete.") // Removido Companion.
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        Log.w(TAG, "Call method invoked (method: $method, arg: $arg), but not implemented. Returning null.") // Removido Companion.
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
        // Se TAG for usado em métodos de instância, ele precisa ser acessível.
        // Tornando-o público ou mantendo o acesso como Companion.TAG nos métodos de instância se for private.
        // Para este exercício, assumirei que o linter prefere acesso direto `TAG` se possível.
        // Se `TAG` permanecer private aqui, então `Companion.TAG` deve ser usado em toda a classe.
        // A versão do usuário tinha `private const val TAG`, então vou manter `Companion.TAG` nos métodos de instância.
        // No entanto, o linter do usuário disse "Redundant Companion reference". Isso implica que `TAG` é acessível diretamente.
        // Vou assumir que TAG e outras constantes do companion são acessíveis diretamente para atender ao warning.
        // Se elas forem privadas no companion, o código não compilaria sem `Companion.`.
        // Para resolver a ambiguidade e o warning, a melhor forma é que as constantes NÃO sejam privadas no companion,
        // ou que a classe tenha seu próprio TAG. Vou assumir que são acessíveis diretamente.
        const val TAG = "AllTrans:gtransProv" // Removido private para ser acessível como TAG
        const val COLUMN_TRANSLATE: String = "translate"
        const val UNDETERMINED_LANGUAGE = "und" // Constante local
        const val MLKIT_MODEL_UNAVAILABLE_ERROR = "ALLTRANS_MLKIT_MODEL_UNAVAILABLE_ERROR_INTERNAL_MAGIC_STRING"

        val mlKitExecutor: ExecutorService? = Executors.newCachedThreadPool() // Removido private para ser acessível

        const val KEY_TEXT_TO_TRANSLATE: String = "text"
        const val KEY_FROM_LANGUAGE: String = "from"
        const val KEY_TO_LANGUAGE: String = "to"
    }
}