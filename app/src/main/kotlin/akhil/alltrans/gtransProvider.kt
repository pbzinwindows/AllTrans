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
class gtransProvider : ContentProvider() {
    @GuardedBy("translatorClients")
    private val translatorClients: MutableMap<String?, Translator> =
        Collections.synchronizedMap<String?, Translator?>(
            HashMap<String?, Translator?>()
        )

    override fun onCreate(): Boolean {
        utils.debugLog(gtransProvider.Companion.TAG + ": onCreate")
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
        utils.debugLog(gtransProvider.Companion.TAG + ": Received query URI: " + uri)

        // 1. Extrair parâmetros da URI
        val fromLanguage = uri.getQueryParameter(KEY_FROM_LANGUAGE)
        val toLanguage = uri.getQueryParameter(KEY_TO_LANGUAGE)
        val textToTranslate = uri.getQueryParameter(KEY_TEXT_TO_TRANSLATE)

        if (fromLanguage == null || toLanguage == null || textToTranslate == null || textToTranslate.isEmpty()) {
            Log.w(
                gtransProvider.Companion.TAG,
                "Query missing required parameters (from, to, text). URI: " + uri
            )
            return createErrorCursor("Missing parameters", projection)
        }
        // Validar códigos de idioma
        try {
            if (!TranslateLanguage.getAllLanguages()
                    .contains(fromLanguage) && "auto" != fromLanguage
            ) {
                Log.w(gtransProvider.Companion.TAG, "Invalid 'from' language code: " + fromLanguage)
                return createErrorCursor("Invalid 'from' language", projection)
            }
            if (!TranslateLanguage.getAllLanguages().contains(toLanguage)) {
                Log.w(gtransProvider.Companion.TAG, "Invalid 'to' language code: " + toLanguage)
                return createErrorCursor("Invalid 'to' language", projection)
            }
        } catch (e: IllegalArgumentException) {
            Log.e(gtransProvider.Companion.TAG, "Language code validation failed", e)
            return createErrorCursor("Language validation error", projection)
        }


        val hashKey = fromLanguage + "##" + toLanguage
        val finalFromLang: String? = fromLanguage
        val finalToLang: String? = toLanguage

        // 2. Criar/Obter Translator
        var translator: Translator?
        synchronized(translatorClients) {
            translator = translatorClients.get(hashKey)
            if (translator == null) {
                utils.debugLog(gtransProvider.Companion.TAG + ": Creating new Translator for " + hashKey)
                try {
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(finalFromLang!!)
                        .setTargetLanguage(finalToLang!!)
                        .build()
                    translator = Translation.getClient(options)
                    // TODO: Gerenciar ciclo de vida/fechar este translator? (shutdown() ajuda)
                    translatorClients.put(hashKey, translator)
                } catch (e: Exception) {
                    Log.e(
                        gtransProvider.Companion.TAG,
                        "Failed to create Translator client for " + hashKey,
                        e
                    )
                    return createErrorCursor("Failed to create translator", projection)
                }
            }
        }
        val finalTranslator = translator

        // 3. Submeter a tarefa bloqueante para o ExecutorService
        val translationTask = Callable {
            try {
                val task = finalTranslator!!.translate(textToTranslate)
                val result = Tasks.await<String?>(
                    task,
                    10,
                    TimeUnit.SECONDS
                ) // BLOQUEANTE AQUI (dentro da thread do executor)
                utils.debugLog(gtransProvider.Companion.TAG + ": ML Kit translation successful for: [" + textToTranslate + "]")
                return@Callable if (result != null) result else textToTranslate
            } catch (e: TimeoutException) {
                Log.w(
                    gtransProvider.Companion.TAG,
                    "ML Kit translation timed out for: [" + textToTranslate + "]"
                )
                return@Callable textToTranslate
            } catch (e: Exception) {
                // Diferenciar erro de modelo não baixado
                if (e.cause is MlKitException && (e.cause as MlKitException).getErrorCode() == MlKitException.UNAVAILABLE) {
                    Log.w(
                        gtransProvider.Companion.TAG,
                        "ML Kit model not available/downloaded for " + finalFromLang + "->" + finalToLang
                    )
                } else {
                    Log.e(
                        gtransProvider.Companion.TAG,
                        "ML Kit translation failed for: [" + textToTranslate + "]",
                        e
                    )
                }
                return@Callable textToTranslate
            }
        }

        val futureResult: Future<String?> =
            gtransProvider.Companion.mlKitExecutor!!.submit<String?>(translationTask)

        // 4. Esperar pelo Future (AINDA BLOQUEIA o Binder thread do provider)
        var translatedString: String? = textToTranslate
        try {
            translatedString = futureResult.get(12, TimeUnit.SECONDS)
            utils.debugLog(gtransProvider.Companion.TAG + ": Future task completed. Result: [" + translatedString + "]")
        } catch (e: TimeoutException) {
            Log.w(gtransProvider.Companion.TAG, "Future task timed out waiting for ML Kit result.")
            futureResult.cancel(true)
            translatedString = textToTranslate
        } catch (e: Exception) {
            Log.e(gtransProvider.Companion.TAG, "Future task failed waiting for ML Kit result", e)
            translatedString = textToTranslate
        }

        // 5. Construir e retornar o Cursor
        val durationNs = System.nanoTime() - startTime
        utils.debugLog(
            gtransProvider.Companion.TAG + ": Query processing took " + TimeUnit.NANOSECONDS.toMillis(
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
                    gtransProvider.Companion.TAG,
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

    // Função auxiliar para criar cursor de erro
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
        // ... (código de shutdown como antes) ...
        super.shutdown()
        utils.debugLog(gtransProvider.Companion.TAG + ": shutdown initiated.")
        synchronized(translatorClients) {
            utils.debugLog(gtransProvider.Companion.TAG + ": Closing " + translatorClients.size + " cached Translator clients.")
            for (translator in translatorClients.values) {
                try {
                    translator.close()
                } catch (e: Exception) {
                    Log.w(gtransProvider.Companion.TAG, "Error closing translator.", e)
                }
            }
            translatorClients.clear()
        }
        if (gtransProvider.Companion.mlKitExecutor != null && !gtransProvider.Companion.mlKitExecutor.isShutdown()) {
            utils.debugLog(gtransProvider.Companion.TAG + ": Shutting down ML Kit ExecutorService.")
            gtransProvider.Companion.mlKitExecutor.shutdown()
            try {
                if (!gtransProvider.Companion.mlKitExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    gtransProvider.Companion.mlKitExecutor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                gtransProvider.Companion.mlKitExecutor.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        utils.debugLog(gtransProvider.Companion.TAG + ": shutdown complete.")
    }


    // --- Métodos não suportados ---
    override fun getType(uri: Uri): String? {
        return null
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        Log.w(
            gtransProvider.Companion.TAG,
            "Call method invoked, but not implemented. Returning null."
        )
        return null
    } // Retornar null ou Bundle vazio

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
        const val COLUMN_TRANSLATE: String = "translate" // Manter público

        // Usar um ExecutorService para rodar a tarefa bloqueante do ML Kit
        private val mlKitExecutor: ExecutorService? =
            Executors.newCachedThreadPool() // Ou newFixedThreadPool(N)

        // Chaves (mantidas públicas para referência, embora não usadas diretamente por GetTranslateToken nesta versão)
        const val KEY_TEXT_TO_TRANSLATE: String = "text"
        const val KEY_FROM_LANGUAGE: String = "from"
        const val KEY_TO_LANGUAGE: String = "to"
        const val KEY_RESULT_RECEIVER: String = "receiver"
    }
}