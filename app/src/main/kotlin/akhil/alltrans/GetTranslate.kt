package akhil.alltrans

import android.os.Bundle // Adicionado para MyResultReceiver, embora não usado diretamente aqui, mantendo consistência se MyResultReceiver fosse interno.
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient // Adicionado para MyResultReceiver, se mockCall fosse mais complexo
import okhttp3.Protocol // Adicionado para MyResultReceiver
import okhttp3.Request // Adicionado para MyResultReceiver
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody // Adicionado para MyResultReceiver
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException

class GetTranslate : Callback {
    private val TAG = "AllTrans:GetTranslate"
    var stringToBeTrans: String? = null
    var originalCallable: OriginalCallable? = null
    var canCallOriginal: Boolean = false
    var userData: Any? = null
    // New fields for batch translation
    var stringsToBeTrans: List<String>? = null
    var callbackDataList: List<CallbackInfo>? = null
    private var translatedString: String? = null // For single translations
    var pendingCompositeKey: Int = 0 // Alterado de Int? para Int com valor padrão 0

    // A assinatura DEVE corresponder exatamente à interface Callback
    override fun onResponse(call: Call, response: Response) {
        val isBatchMicrosoft = PreferenceList.TranslatorProvider == "m" && stringsToBeTrans?.isNotEmpty() == true && callbackDataList?.isNotEmpty() == true
        var responseBodyString: String? = null

        try {
            if (!response.isSuccessful) {
                Utils.debugLog("$TAG: Got response code as : ${response.code}")
                responseBodyString = response.body?.string() ?: "null"
                Utils.debugLog("$TAG: Got error response body as : $responseBodyString")
                // For batch, trigger failure for all; for single, set translatedString to original
                if (isBatchMicrosoft) {
                    handleBatchFailure(callbackDataList!!, "HTTP Error: ${response.code}")
                } else {
                    translatedString = stringToBeTrans // Fallback to original for single
                }
            } else { // Successful response
                responseBodyString = response.body!!.string() // Read once
                if (isBatchMicrosoft) {
                    Utils.debugLog("$TAG: Batch Microsoft response: $responseBodyString")
                    try {
                        val jsonResponseArray = JSONArray(responseBodyString)
                        val toLang = PreferenceList.TranslateToLanguage // Get target language once for batch
                        for (i in 0 until jsonResponseArray.length()) {
                            if (i >= callbackDataList!!.size) {
                                Log.e(TAG, "Mismatch between response array size (${jsonResponseArray.length()}) and callbackDataList size (${callbackDataList!!.size})")
                                break
                            }

                            val item = jsonResponseArray.getJSONObject(i)
                            var currentItemTranslatedText: String = item.getJSONArray("translations").getJSONObject(0).getString("text").orEmpty()
                            val callbackInfo = callbackDataList!![i] // Assuming order is maintained
                            val originalItemString = callbackInfo.originalString

                            if (PreferenceList.TranslateFromLanguage == "auto") {
                                val detectedLang = item.optJSONObject("detectedLanguage")?.optString("language")
                                if (detectedLang != null && detectedLang == toLang) {
                                    Utils.debugLog("$TAG: Batch MS: Auto-detected language ($detectedLang) matches target ($toLang). Using original text: [$originalItemString]")
                                    currentItemTranslatedText = originalItemString ?: "" // Use original string
                                }
                            }

                            val unescapedTranslatedText = Utils.XMLUnescape(currentItemTranslatedText) ?: ""
                            if (originalItemString != null) {
                                cacheTranslation(originalItemString, unescapedTranslatedText) // Use originalItemString for cache key
                            }
                            triggerSuccessCallback(unescapedTranslatedText, callbackInfo)
                        }
                    } catch (e: JSONException) {
                        Log.e(TAG, "Error parsing BATCH Microsoft JSON response: ${Log.getStackTraceString(e)}\nResponse body was: $responseBodyString")
                        handleBatchFailure(callbackDataList!!, "Batch JSON parsing error")
                    }
                    return // Batch processing ends here, individual callbacks handled by triggerSuccessCallback
                } else { // Single translation (Microsoft, Yandex, Google)
                    val localOriginalString = stringToBeTrans
                    Utils.debugLog("$TAG: Single translation response for [$localOriginalString]: $responseBodyString")
                    var singleTranslatedText: String? = localOriginalString
                    try {
                        when (PreferenceList.TranslatorProvider) {
                            "y" -> {
                                responseBodyString?.let { nonNullResponseBody ->
                                    singleTranslatedText = if (nonNullResponseBody.contains("<text>")) {
                                        nonNullResponseBody.substring(nonNullResponseBody.indexOf("<text>") + 6, nonNullResponseBody.lastIndexOf("</text>"))
                                    } else {
                                        Log.w(TAG, "Unexpected Yandex response format: $nonNullResponseBody")
                                        localOriginalString
                                    }
                                } ?: run {
                                    Log.w(TAG, "Yandex response body was null, falling back to original.")
                                    singleTranslatedText = localOriginalString
                                }
                            }
                            "m" -> { // Single Microsoft
                                responseBodyString?.let { nonNullResponseBody ->
                                    try {
                                        val jsonResponseObject = JSONArray(nonNullResponseBody).getJSONObject(0)
                                        singleTranslatedText = jsonResponseObject.getJSONArray("translations").getJSONObject(0).getString("text").orEmpty()

                                        if (PreferenceList.TranslateFromLanguage == "auto") {
                                            val detectedLang = jsonResponseObject.optJSONObject("detectedLanguage")?.optString("language")
                                            val toLang = PreferenceList.TranslateToLanguage
                                            if (detectedLang != null && detectedLang == toLang) {
                                                Utils.debugLog("$TAG: Single MS: Auto-detected language ($detectedLang) matches target ($toLang). Using original text: [$localOriginalString]")
                                                singleTranslatedText = localOriginalString // Use original string
                                            } else {
                                                // Only unescape if not using original string
                                                singleTranslatedText = Utils.XMLUnescape(singleTranslatedText.orEmpty())
                                            }
                                        } else {
                                            // Unescape if not auto-detect or condition not met
                                            singleTranslatedText = Utils.XMLUnescape(singleTranslatedText.orEmpty())
                                        }
                                    } catch (jsonEx: JSONException) {
                                        Log.e(TAG, "Error parsing SINGLE Microsoft JSON: ${Log.getStackTraceString(jsonEx)}\nResponse body was: $nonNullResponseBody")
                                        singleTranslatedText = localOriginalString
                                        // No need to unescape here as it's already original or error
                                    }
                                } ?: run {
                                    Log.w(TAG, "Microsoft (single) response body was null, falling back to original.")
                                    singleTranslatedText = localOriginalString
                                }
                            }
                            else -> { // Google or default
                                singleTranslatedText = responseBodyString // This is safe as singleTranslatedText is String?
                                singleTranslatedText = Utils.XMLUnescape(singleTranslatedText.orEmpty()) // Unescape for Google/default
                            }
                        }
                    } catch (e: Exception) {
                        // This catch block will handle exceptions from XMLUnescape or other unexpected issues
                        Log.e(TAG, "Error processing SINGLE translation response: ${Log.getStackTraceString(e)}\nResponse body might have been: $responseBodyString")
                        singleTranslatedText = localOriginalString // Fallback
                    }
                    translatedString = singleTranslatedText ?: localOriginalString // Ensure translatedString is not null
                    if (translatedString.isNullOrEmpty() && !localOriginalString.isNullOrEmpty()) { // If translation is empty but original wasn't, prefer original
                        Utils.debugLog("$TAG: Translated string is null or empty, but original was not. Using original: [$localOriginalString]")
                        translatedString = localOriginalString
                    } else if (translatedString.isNullOrEmpty()) { // Both translated and original are null/empty
                        Utils.debugLog("$TAG: Translated string is null or empty, and original is also null/empty. Setting to empty string.")
                        translatedString = "" // Fallback to empty string if both are null/empty
                    }

                    if (localOriginalString != null && translatedString != null) {
                        cacheTranslation(localOriginalString, translatedString)
                    } else {
                        Utils.debugLog("$TAG: Skipping cacheTranslation due to null values: original=[$localOriginalString], translated=[$translatedString]")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error processing translation response: ${Log.getStackTraceString(e)}")
            if (isBatchMicrosoft) {
                handleBatchFailure(callbackDataList!!, "Generic error: ${e.message}")
                return // Batch processing ends here
            } else {
                translatedString = stringToBeTrans // Fallback for single
            }
        } finally {
            response.body?.close() // Ensure body is closed
            if (!isBatchMicrosoft) {
                val finalString: String = translatedString ?: stringToBeTrans ?: ""
                Utils.debugLog("$TAG: Final single translation result before callback: [$finalString]")
                triggerSingleCallback(finalString, stringToBeTrans, userData, originalCallable, canCallOriginal)
            }
        }
    }

    private fun cacheTranslation(original: String?, translated: String?) {
        if (original == null || translated == null || !PreferenceList.Caching || translated == original) {
            if (translated == original) Utils.debugLog("$TAG: Skipping cache update for identical translation.")
            return
        }
        Alltrans.cacheAccess.acquireUninterruptibly()
        try {
            Utils.debugLog("$TAG: Putting in cache: [$original] -> [$translated]")
            Alltrans.cache?.let {
                it.put(original, translated)
            } ?: Utils.debugLog("$TAG: Cache object is null, cannot update cache.")
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }
    }

    private fun triggerSuccessCallback(translatedText: String, callbackInfo: CallbackInfo) {
        val keyToRemoveFromPending = callbackInfo.pendingCompositeKey

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (callbackInfo.userData is TextView) {
                    val tv = callbackInfo.userData as TextView
                    if (translatedText != callbackInfo.originalString) {
                        Utils.debugLog("$TAG: Updating TextView (${tv.hashCode()}) with batch translated text: [$translatedText]")
                        tv.text = translatedText
                        tv.setTag(R.id.tag_alltrans_translated_textview, true) // CORREÇÃO ADICIONADA
                    } else {
                        Utils.debugLog("$TAG: Skipping TextView update for (${tv.hashCode()}), batch translated text is same as original: [$translatedText]")
                        // Se o texto é o mesmo (ex: idioma de origem = idioma de destino), ainda marcamos como processado
                        // para evitar que seja enviado para tradução novamente desnecessariamente.
                        tv.setTag(R.id.tag_alltrans_translated_textview, true) // CORREÇÃO ADICIONADA
                    }
                } else if (callbackInfo.canCallOriginal && callbackInfo.originalCallable != null) {
                    Utils.debugLog("$TAG: Calling originalCallable.callOriginalMethod for batch item with text: [$translatedText]")
                    callbackInfo.originalCallable.callOriginalMethod(translatedText, callbackInfo.userData)
                } else {
                    Utils.debugLog("$TAG: No suitable callback action for batch item userData type: ${callbackInfo.userData?.javaClass?.name ?: "null"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in batch success callback for ${callbackInfo.originalString}", e)
            } finally {
                synchronized(Alltrans.pendingTextViewTranslations) {
                    if (Alltrans.pendingTextViewTranslations.remove(keyToRemoveFromPending)) {
                        Utils.debugLog("$TAG: Removed item hash ($keyToRemoveFromPending) from pending set after batch onResponse.")
                    } else {
                        Utils.debugLog("$TAG: Could not remove hash ($keyToRemoveFromPending) from pending set, may already be removed or was never added (e.g. if key was 0 and BatchManager skipped it).")
                    }
                }
            }
        }, PreferenceList.Delay.toLong())
    }

    private fun triggerSingleCallback(finalString: String, originalString: String?, currentLocalUserData: Any?, currentLocalOriginalCallable: OriginalCallable?, currentLocalCanCallOriginal: Boolean) {
        val keyToRemove = pendingCompositeKey

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (currentLocalUserData is TextView) {
                    val tv = currentLocalUserData as TextView // Alterado para val
                    if (finalString != originalString) { // only update if different
                        Utils.debugLog("$TAG: Updating TextView (${tv.hashCode()}) with single translated text: [$finalString]")
                        tv.text = finalString
                        tv.setTag(R.id.tag_alltrans_translated_textview, true) // CORREÇÃO ADICIONADA
                    } else {
                        Utils.debugLog("$TAG: Skipping TextView update for (${tv.hashCode()}), single translated text is same as original: [$finalString]")
                        // Se o texto é o mesmo (ex: idioma de origem = idioma de destino, ou a tradução resultou no mesmo texto),
                        // ainda marcamos como processado para evitar que seja enviado para tradução novamente.
                        tv.setTag(R.id.tag_alltrans_translated_textview, true) // CORREÇÃO ADICIONADA
                    }
                } else if (currentLocalCanCallOriginal && currentLocalOriginalCallable != null) {
                    Utils.debugLog("$TAG: Calling originalCallable.callOriginalMethod for single item with text: [$finalString]")
                    currentLocalOriginalCallable.callOriginalMethod(finalString, currentLocalUserData)
                } else {
                    Utils.debugLog("$TAG: No suitable callback action for single item userData type: ${currentLocalUserData?.javaClass?.name ?: "null"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in single item callback for $originalString", e)
            } finally {
                synchronized(Alltrans.pendingTextViewTranslations) {
                    if (Alltrans.pendingTextViewTranslations.remove(keyToRemove)) {
                        Utils.debugLog("$TAG: Removed item hash ($keyToRemove) from pending set after single onResponse.")
                    } else {
                        Utils.debugLog("$TAG: Could not remove hash ($keyToRemove) from pending set, may already be removed or was never added.")
                    }
                }
            }
        }, PreferenceList.Delay.toLong())
    }

    private fun handleBatchFailure(callbacks: List<CallbackInfo>, reason: String) {
        Utils.debugLog("$TAG: Handling batch failure: $reason")
        callbacks.forEach { cbInfo ->
            val keyToRemoveFromPending = cbInfo.pendingCompositeKey

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (cbInfo.canCallOriginal && cbInfo.originalCallable != null) {
                        Utils.debugLog("$TAG: Calling originalCallable for batch item ${cbInfo.originalString} on failure.")
                        cbInfo.originalCallable.callOriginalMethod(cbInfo.originalString ?: "", cbInfo.userData)
                    } else if (cbInfo.userData is TextView) {
                        Utils.debugLog("$TAG: Network failure for TextView from batch (${(cbInfo.userData as TextView).hashCode()}), original text (${cbInfo.originalString}) remains.")
                        // Mesmo em falha, se o userData é um TextView, é bom remover da pendência.
                        // A tag não será setada pois o texto não mudou.
                    } else {
                        Utils.debugLog("$TAG: No suitable failure callback for batch item ${cbInfo.originalString}, userData: ${cbInfo.userData?.javaClass?.name}")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error executing originalCallable on batch failure for ${cbInfo.originalString}", t)
                } finally {
                    synchronized(Alltrans.pendingTextViewTranslations) {
                        if (Alltrans.pendingTextViewTranslations.remove(keyToRemoveFromPending)) {
                            Utils.debugLog("$TAG: Removed item hash ($keyToRemoveFromPending) from pending set after batch onFailure processing.")
                        } else {
                            Utils.debugLog("$TAG: Could not remove hash ($keyToRemoveFromPending) from pending set after batch failure, may already be removed or was never added.")
                        }
                    }
                }
            }, PreferenceList.Delay.toLong())
        }
    }

    override fun onFailure(call: Call, e: IOException) {
        val isBatchMicrosoft = PreferenceList.TranslatorProvider == "m" && stringsToBeTrans?.isNotEmpty() == true && callbackDataList?.isNotEmpty() == true
        val localOriginalStringForSingle = stringToBeTrans
        val localUserDataForSingle = userData
        val localOriginalCallableForSingle = originalCallable
        val localCanCallOriginalForSingle = canCallOriginal

        Log.e(TAG, "Network request failed for: [${if(isBatchMicrosoft) "BATCH of " + stringsToBeTrans?.size + " items" else localOriginalStringForSingle ?: "Unknown"}] ${Log.getStackTraceString(e)}")

        if (isBatchMicrosoft) {
            handleBatchFailure(callbackDataList!!, "Network Error: ${e.message}")
        } else {
            val keyToRemove = pendingCompositeKey

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (localUserDataForSingle is TextView) {
                        Utils.debugLog("$TAG: Network failure for single TextView (key $keyToRemove), original text remains.")
                    } else if (localCanCallOriginalForSingle && localOriginalCallableForSingle != null) {
                        Utils.debugLog("$TAG: Calling originalCallable.callOriginalMethod on single failure for [$localOriginalStringForSingle]")
                        localOriginalCallableForSingle.callOriginalMethod(localOriginalStringForSingle.orEmpty(), localUserDataForSingle)
                    } else {
                        Utils.debugLog("$TAG: No suitable failure callback for single item [$localOriginalStringForSingle], userData: ${localUserDataForSingle?.javaClass?.name}")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error executing originalCallable on single failure for [$localOriginalStringForSingle]", t)
                } finally {
                    synchronized(Alltrans.pendingTextViewTranslations) {
                        if (Alltrans.pendingTextViewTranslations.remove(keyToRemove)) {
                            Utils.debugLog("$TAG: Removed item hash ($keyToRemove) from pending set after single onFailure.")
                        } else {
                            Utils.debugLog("$TAG: Could not remove hash ($keyToRemove) from pending set after single failure, may already be removed or was never added.")
                        }
                    }
                }
            }, PreferenceList.Delay.toLong())
        }
    }
}