package akhil.alltrans

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException

class GetTranslate : Callback {
    var stringToBeTrans: String? = null
    var originalCallable: OriginalCallable? = null
    var canCallOriginal: Boolean = false
    var userData: Any? = null
    private var translatedString: String? = null

    // A assinatura DEVE corresponder exatamente à interface Callback
    override fun onResponse(call: Call, response: Response) {
        val localOriginalString = stringToBeTrans
        var textViewHashCode: Int? = null // Variável para guardar o hashcode
        if (userData is TextView) {
            textViewHashCode = userData.hashCode() // Pega o hashcode se for TextView
        }

        try {
            if (!response.isSuccessful) {
                utils.debugLog("Got response code as : " + response.code)
                translatedString = localOriginalString
                try {
                    val errorBody =
                        if (response.body != null) response.body!!.string() else "null"
                    utils.debugLog("Got error response body as : " + errorBody)
                } catch (ignored: Exception) {
                } finally {
                    if (response.body != null) response.body!!.close()
                }
            } else {
                var result: String? = null
                try {
                    if (response.body == null) {
                        throw IOException("Response body is null")
                    }
                    result = response.body!!.string()
                    utils.debugLog(
                        "In Thread " + Thread.currentThread()
                            .getId() + " In GetTranslate, for: [" + localOriginalString + "] got response as " + result
                    )

                    if (PreferenceList.TranslatorProvider == "y") {
                        if (result.contains("<text>")) {
                            translatedString = result.substring(
                                result.indexOf("<text>") + 6,
                                result.lastIndexOf("</text>")
                            )
                        } else {
                            Log.w("AllTrans", "Unexpected Yandex response format: " + result)
                            translatedString = localOriginalString
                        }
                    } else if (PreferenceList.TranslatorProvider == "m") {
                        try {
                            translatedString =
                                JSONArray(result).getJSONObject(0).getJSONArray("translations")
                                    .getJSONObject(0).getString("text")
                        } catch (jsonEx: JSONException) { // Captura específica para JSON
                            Log.e(
                                "AllTrans",
                                "Error parsing Microsoft JSON response: " + Log.getStackTraceString(
                                    jsonEx
                                ) + "\nResponse body was: " + result
                            )
                            translatedString = localOriginalString
                        }
                    } else { // Google ou default
                        translatedString = result
                    }

                    translatedString = utils.XMLUnescape(translatedString)
                } catch (e: Exception) { // Catch mais genérico para outras exceções (IOException, etc)
                    Log.e(
                        "AllTrans",
                        "Error parsing translation response: " + Log.getStackTraceString(e) + "\nResponse body was: " + result
                    )
                    translatedString = localOriginalString
                } finally {
                    // Fechar o corpo da resposta aqui é redundante se já fechado acima, mas seguro
                    if (response.body != null) try {
                        response.body!!.close()
                    } catch (ignored: Exception) {
                    }
                }

                if (translatedString == null || translatedString!!.isEmpty()) {
                    utils.debugLog("Translation result is null or empty after parsing, using original.")
                    translatedString = localOriginalString
                }

                if (PreferenceList.Caching && translatedString != null && (translatedString != localOriginalString)) {
                    val finalTranslated = translatedString
                    alltrans.Companion.cacheAccess.acquireUninterruptibly()
                    try {
                        utils.debugLog("Putting in cache: [" + localOriginalString + "] -> [" + finalTranslated + "]")
                        val cacheRef = alltrans.Companion.cache
                        if (cacheRef != null) {
                            cacheRef[localOriginalString] = finalTranslated
                            cacheRef[finalTranslated] = finalTranslated
                        } else {
                            utils.debugLog("Cache object is null, cannot update cache.")
                        }
                    } finally {
                        if (alltrans.Companion.cacheAccess.availablePermits() == 0) {
                            alltrans.Companion.cacheAccess.release()
                        }
                    }
                } else if (translatedString == localOriginalString) {
                    utils.debugLog("Skipping cache update for identical translation.")
                }
            }
        } catch (e: Throwable) {
            Log.e(
                "AllTrans",
                "Unexpected error processing translation response: " + Log.getStackTraceString(e)
            )
            translatedString = localOriginalString
            if (response.body != null) try {
                response.body!!.close()
            } catch (ignored: Exception) {
            }
        } finally {
            // Garante que finalString nunca seja nulo antes do callback
            // Fixed: Simplified the expression to avoid the redundant null check
            val finalString: String = translatedString ?: localOriginalString ?: ""
            utils.debugLog("Final translation result before callback: [" + finalString + "]")

            // --- Lógica de Callback Atualizada (com remoção do Set) ---
            if (userData is TextView) {
                val textView = userData as TextView
                // Usa o hashcode guardado no início do método
                val currentTextViewHashCode = textViewHashCode
                Handler(Looper.getMainLooper()).postDelayed(Runnable {
                    try {
                        if (textView != null && finalString != localOriginalString) {
                            utils.debugLog("Updating TextView (" + textView.hashCode() + ") with different translated text: [" + finalString + "]")
                            textView.setText(finalString)
                        } else if (textView != null) {
                            utils.debugLog("Skipping TextView update for (" + textView.hashCode() + "), translated text is same as original: [" + finalString + "]")
                        } else {
                            utils.debugLog("Skipping TextView update, view no longer exists.")
                        }
                    } catch (e: Exception) {
                        Log.e(
                            "AllTrans",
                            "Error setting text directly on TextView from callback",
                            e
                        )
                    } finally {
                        // --- INÍCIO DA REMOÇÃO DO SET ---
                        if (currentTextViewHashCode != null) {
                            if (alltrans.Companion.pendingTextViewTranslations.remove(
                                    currentTextViewHashCode
                                )
                            ) {
                                utils.debugLog("Removed TextView (" + currentTextViewHashCode + ") from pending set after onResponse.")
                            }
                        }
                        // --- FIM DA REMOÇÃO DO SET ---
                    }
                }, PreferenceList.Delay.toLong())
            } else if (canCallOriginal && originalCallable != null) {
                utils.debugLog("Calling originalCallable.callOriginalMethod for other hook type with text: [" + finalString + "]")
                val currentNonTextViewHashCode =
                    if (userData != null) userData!!.hashCode() else null // Guarda hashcode
                Handler(Looper.getMainLooper()).postDelayed(Runnable {
                    if (originalCallable != null) {
                        try {
                            originalCallable!!.callOriginalMethod(finalString, userData)
                        } catch (t: Throwable) {
                            Log.e(
                                "AllTrans",
                                "Error executing originalCallable.callOriginalMethod",
                                t
                            )
                        } finally {
                            // --- INÍCIO DA REMOÇÃO DO SET (OUTROS TIPOS) ---
                            if (currentNonTextViewHashCode != null) {
                                if (alltrans.Companion.pendingTextViewTranslations.remove(
                                        currentNonTextViewHashCode
                                    )
                                ) {
                                    utils.debugLog("Removed non-TextView userData (" + currentNonTextViewHashCode + ") from pending set after onResponse callback.")
                                }
                            }
                            // --- FIM DA REMOÇÃO DO SET (OUTROS TIPOS) ---
                        }
                    } else {
                        Log.e(
                            "AllTrans",
                            "originalCallable became null before executing callback on main thread."
                        )
                        // --- INÍCIO DA REMOÇÃO DO SET (OUTROS TIPOS - FALHA NO CALLBACK) ---
                        if (currentNonTextViewHashCode != null) {
                            if (alltrans.Companion.pendingTextViewTranslations.remove(
                                    currentNonTextViewHashCode
                                )
                            ) {
                                utils.debugLog("Removed non-TextView userData (" + currentNonTextViewHashCode + ") from pending set after originalCallable became null.")
                            }
                        }
                        // --- FIM DA REMOÇÃO DO SET (OUTROS TIPOS - FALHA NO CALLBACK) ---
                    }
                }, PreferenceList.Delay.toLong())
            } else {
                utils.debugLog("No suitable callback action found for userData type: " + (if (userData != null) userData!!.javaClass.getName() else "null"))
                // --- INÍCIO DA REMOÇÃO DO SET (SEM CALLBACK) ---
                if (textViewHashCode != null) { // Usa hashcode guardado
                    if (alltrans.Companion.pendingTextViewTranslations.remove(textViewHashCode)) {
                        utils.debugLog("Removed TextView (" + textViewHashCode + ") from pending set (no callback action).")
                    }
                } else if (userData != null) {
                    val hashToRemove = userData!!.hashCode()
                    if (alltrans.Companion.pendingTextViewTranslations.remove(hashToRemove)) {
                        utils.debugLog("Removed non-TextView userData (" + hashToRemove + ") from pending set (no callback action).")
                    }
                }
                // --- FIM DA REMOÇÃO DO SET (SEM CALLBACK) ---
            }
            // --- Fim da Lógica de Callback Atualizada ---
        }
    } // Fim do onResponse

    // A assinatura DEVE corresponder exatamente à interface Callback
    override fun onFailure(call: Call, e: IOException) {
        val localOriginalString = stringToBeTrans
        var currentHashCode: Int? = null // Variável para guardar o hashcode
        if (userData != null) {
            currentHashCode = userData!!.hashCode() // Pega o hashcode
        }
        Log.e(
            "AllTrans",
            "Network request failed for: [" + localOriginalString + "] " + Log.getStackTraceString(e)
        )

        try {
            // --- Lógica de Callback Atualizada para Falha ---
            if (userData is TextView) {
                utils.debugLog("Network failure for TextView (" + currentHashCode + "), original text remains.")
            } else if (canCallOriginal && originalCallable != null) {
                utils.debugLog("Calling originalCallable.callOriginalMethod on failure for other hook type with original text: [" + localOriginalString + "]")
                val finalCurrentHashCode = currentHashCode // Final para lambda
                Handler(Looper.getMainLooper()).postDelayed(Runnable {
                    if (originalCallable != null) {
                        try {
                            originalCallable!!.callOriginalMethod(localOriginalString, userData)
                        } catch (t: Throwable) {
                            Log.e(
                                "AllTrans",
                                "Error executing originalCallable.callOriginalMethod on failure",
                                t
                            )
                        } finally {
                            // --- INÍCIO DA REMOÇÃO DO SET (OUTROS TIPOS - FALHA) ---
                            if (finalCurrentHashCode != null) {
                                if (alltrans.Companion.pendingTextViewTranslations.remove(
                                        finalCurrentHashCode
                                    )
                                ) {
                                    utils.debugLog("Removed non-TextView userData (" + finalCurrentHashCode + ") from pending set after onFailure callback.")
                                }
                            }
                            // --- FIM DA REMOÇÃO DO SET (OUTROS TIPOS - FALHA) ---
                        }
                    } else {
                        Log.e(
                            "AllTrans",
                            "originalCallable became null before executing failure callback on main thread."
                        )
                        // --- INÍCIO DA REMOÇÃO DO SET (OUTROS TIPOS - FALHA NO CALLBACK) ---
                        if (finalCurrentHashCode != null) {
                            if (alltrans.Companion.pendingTextViewTranslations.remove(
                                    finalCurrentHashCode
                                )
                            ) {
                                utils.debugLog("Removed non-TextView userData (" + finalCurrentHashCode + ") from pending set after originalCallable became null on failure.")
                            }
                        }
                        // --- FIM DA REMOÇÃO DO SET (OUTROS TIPOS - FALHA NO CALLBACK) ---
                    }
                }, PreferenceList.Delay.toLong())
            } else {
                utils.debugLog("No suitable failure callback action found for userData type: " + (if (userData != null) userData!!.javaClass.getName() else "null"))
                // --- INÍCIO DA REMOÇÃO DO SET (SEM CALLBACK - FALHA) ---
                if (currentHashCode != null) {
                    if (alltrans.Companion.pendingTextViewTranslations.remove(currentHashCode)) {
                        utils.debugLog("Removed userData (" + currentHashCode + ") from pending set (no callback action on failure).")
                    }
                }
                // --- FIM DA REMOÇÃO DO SET (SEM CALLBACK - FALHA) ---
            }
            // --- Fim da Lógica de Callback Atualizada para Falha ---
        } finally {
            // --- INÍCIO DA REMOÇÃO DO SET (GARANTIA EM CASO DE FALHA) ---
            // Remove do set mesmo se a tradução falhar (caso não tenha sido removido no callback)
            if (currentHashCode != null) {
                if (alltrans.Companion.pendingTextViewTranslations.remove(currentHashCode)) {
                    utils.debugLog("Removed userData (" + currentHashCode + ") from pending set in onFailure finally block.")
                }
            }
            // --- FIM DA REMOÇÃO DO SET (GARANTIA EM CASO DE FALHA) ---
        }
    } // Fim do onFailure
} // Fim da classe GetTranslate