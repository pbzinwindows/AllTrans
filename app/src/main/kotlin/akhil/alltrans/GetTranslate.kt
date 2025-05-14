package akhil.alltrans

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.core.view.ViewCompat
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

    override fun onResponse(call: Call, response: Response) {
        val localOriginalString = stringToBeTrans
        var textViewRef: TextView? = null
        var textViewHashCode: Int? = null

        // CORREÇÃO AQUI: Cast explícito e seguro
        if (userData is TextView) {
            textViewRef = userData as TextView // Cast explícito após a verificação de tipo
            textViewHashCode = textViewRef.hashCode()
        }
        // O restante da lógica de onResponse continua como na versão anterior
        // ... (copie o restante do método onResponse da minha resposta anterior aqui)
        try {
            if (!response.isSuccessful) {
                utils.debugLog("Got response code as : ${response.code}")
                translatedString = localOriginalString
                try {
                    val errorBody = response.body?.string() ?: "null_body"
                    utils.debugLog("Got error response body as : $errorBody")
                } catch (ignored: Exception) {
                } finally {
                    response.body?.close()
                }
            } else {
                var resultTextFromResponse: String? = null
                try {
                    val responseBody = response.body ?: throw IOException("Response body is null")
                    resultTextFromResponse = responseBody.string()
                    utils.debugLog(
                        "In Thread ${Thread.currentThread().id} In GetTranslate, for: [$localOriginalString] got response as $resultTextFromResponse"
                    )

                    when (PreferenceList.effectiveTranslatorProvider) {
                        "y" -> {
                            if (resultTextFromResponse.contains("<text>")) {
                                translatedString = resultTextFromResponse.substring(
                                    resultTextFromResponse.indexOf("<text>") + 6,
                                    resultTextFromResponse.lastIndexOf("</text>")
                                )
                            } else {
                                Log.w("AllTrans", "Unexpected Yandex response format: $resultTextFromResponse")
                                translatedString = localOriginalString
                            }
                        }
                        "m" -> {
                            try {
                                translatedString =
                                    JSONArray(resultTextFromResponse).getJSONObject(0).getJSONArray("translations")
                                        .getJSONObject(0).getString("text")
                            } catch (jsonEx: JSONException) {
                                Log.e("AllTrans", "Error parsing Microsoft JSON response: ${Log.getStackTraceString(jsonEx)}\nResponse body was: $resultTextFromResponse")
                                translatedString = localOriginalString
                            }
                        }
                        else -> {
                            translatedString = resultTextFromResponse
                        }
                    }
                    translatedString = utils.XMLUnescape(translatedString)
                } catch (e: Exception) {
                    Log.e("AllTrans", "Error parsing translation response: ${Log.getStackTraceString(e)}\nResponse body (if available): $resultTextFromResponse")
                    translatedString = localOriginalString
                }

                if (translatedString.isNullOrEmpty()) {
                    utils.debugLog("Translation result is null or empty after parsing, using original.")
                    translatedString = localOriginalString
                }

                if (PreferenceList.Caching && translatedString != null && (translatedString != localOriginalString)) {
                    val finalTranslated = translatedString
                    alltrans.Companion.cacheAccess.acquireUninterruptibly()
                    try {
                        utils.debugLog("Putting in cache: [$localOriginalString] -> [$finalTranslated]")
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
            Log.e("AllTrans", "Unexpected error processing translation response: ${Log.getStackTraceString(e)}")
            translatedString = localOriginalString
            response.body?.close()
        } finally {
            val finalString: String = translatedString ?: localOriginalString ?: ""
            utils.debugLog("Final translation result before callback: [$finalString]")

            val currentHandler = Handler(Looper.getMainLooper())
            val delayMillis = (PreferenceList.Delay).toLong()

            val finalTextViewRef = textViewRef
            if (finalTextViewRef != null) {
                currentHandler.postDelayed({
                    try {
                        if (ViewCompat.isAttachedToWindow(finalTextViewRef) && finalString != localOriginalString) {
                            utils.debugLog("Updating TextView ($textViewHashCode) with different translated text: [$finalString]")
                            finalTextViewRef.text = finalString
                        } else if (ViewCompat.isAttachedToWindow(finalTextViewRef)) {
                            utils.debugLog("Skipping TextView update for ($textViewHashCode), translated text is same or view not ready for update: [$finalString]")
                        } else {
                            utils.debugLog("Skipping TextView update, view no longer attached ($textViewHashCode).")
                        }
                    } catch (e: Exception) {
                        Log.e("AllTrans", "Error setting text on TextView from callback", e)
                    } finally {
                        if (textViewHashCode != null) {
                            if (alltrans.Companion.pendingTextViewTranslations.remove(textViewHashCode)) {
                                utils.debugLog("Removed TextView ($textViewHashCode) from pending set after onResponse.")
                            }
                        }
                    }
                }, delayMillis)
            } else if (canCallOriginal && originalCallable != null) {
                utils.debugLog("Calling originalCallable.callOriginalMethod for other hook type with text: [$finalString]")
                val currentNonTextViewHashCode = userData?.hashCode()
                currentHandler.postDelayed({
                    originalCallable?.let { oc ->
                        try {
                            oc.callOriginalMethod(finalString, userData)
                        } catch (t: Throwable) {
                            Log.e("AllTrans", "Error executing originalCallable.callOriginalMethod", t)
                        } finally {
                            if (currentNonTextViewHashCode != null) {
                                if (alltrans.Companion.pendingTextViewTranslations.remove(currentNonTextViewHashCode)) {
                                    utils.debugLog("Removed non-TextView userData ($currentNonTextViewHashCode) from pending set after onResponse callback.")
                                }
                            }
                        }
                    } ?: run {
                        Log.e("AllTrans", "originalCallable became null before executing callback.")
                        if (currentNonTextViewHashCode != null) {
                            alltrans.Companion.pendingTextViewTranslations.remove(currentNonTextViewHashCode)
                        }
                    }
                }, delayMillis)
            } else {
                utils.debugLog("No suitable callback action found for userData type: ${userData?.javaClass?.name ?: "null"}")
                val hashToRemove = userData?.hashCode()
                if (hashToRemove != null) {
                    if (alltrans.Companion.pendingTextViewTranslations.remove(hashToRemove)) {
                        utils.debugLog("Removed userData ($hashToRemove) from pending set (no callback action).")
                    }
                }
            }
        }
    }

    override fun onFailure(call: Call, e: IOException) {
        val localOriginalString = stringToBeTrans
        var currentHashCode: Int? = null

        if (userData is TextView) {
            currentHashCode = userData.hashCode() // Cast implícito aqui após a verificação
        } else {
            currentHashCode = userData?.hashCode()
        }

        Log.e("AllTrans", "Network request failed for: [$localOriginalString] ${Log.getStackTraceString(e)}")

        try {
            if (userData is TextView) {
                utils.debugLog("Network failure for TextView ($currentHashCode), original text remains.")
            } else if (canCallOriginal && originalCallable != null) {
                utils.debugLog("Calling originalCallable.callOriginalMethod on failure with original text: [$localOriginalString]")
                Handler(Looper.getMainLooper()).postDelayed({
                    originalCallable?.let { oc ->
                        try {
                            oc.callOriginalMethod(localOriginalString, userData)
                        } catch (t: Throwable) {
                            Log.e("AllTrans", "Error executing originalCallable.callOriginalMethod on failure", t)
                        }
                        // A limpeza do pending set é feita no finally externo
                    } ?: Log.e("AllTrans", "originalCallable became null before executing failure callback.")
                }, (PreferenceList.Delay).toLong())
            } else {
                utils.debugLog("No suitable failure callback action found for userData type: ${userData?.javaClass?.name ?: "null"}")
            }
        } finally {
            if (currentHashCode != null) {
                if (alltrans.Companion.pendingTextViewTranslations.remove(currentHashCode)) {
                    utils.debugLog("Removed userData ($currentHashCode) from pending set in onFailure finally block.")
                }
            }
        }
    }
}