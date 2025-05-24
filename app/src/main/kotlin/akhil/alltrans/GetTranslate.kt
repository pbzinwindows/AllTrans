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
import akhil.alltrans.CallbackInfo // Added for batch processing

class GetTranslate : Callback {
    var stringToBeTrans: String? = null
    var originalCallable: OriginalCallable? = null
    var canCallOriginal: Boolean = false
    var userData: Any? = null
    // New fields for batch translation
    var stringsToBeTrans: List<String>? = null
    var callbackDataList: List<CallbackInfo>? = null
    private var translatedString: String? = null // For single translations

    // A assinatura DEVE corresponder exatamente à interface Callback
    override fun onResponse(call: Call, response: Response) {
        val isBatchMicrosoft = PreferenceList.TranslatorProvider == "m" && stringsToBeTrans?.isNotEmpty() == true && callbackDataList?.isNotEmpty() == true
        var responseBodyString: String? = null

        try {
            if (!response.isSuccessful) {
                Utils.debugLog("Got response code as : " + response.code)
                responseBodyString = response.body?.string() ?: "null"
                Utils.debugLog("Got error response body as : $responseBodyString")
                // For batch, trigger failure for all; for single, set translatedString to original
                if (isBatchMicrosoft) {
                    handleBatchFailure(callbackDataList!!, "HTTP Error: ${response.code}")
                } else {
                    translatedString = stringToBeTrans // Fallback to original for single
                }
            } else { // Successful response
                responseBodyString = response.body!!.string() // Read once
                if (isBatchMicrosoft) {
                    Utils.debugLog("Batch Microsoft response: $responseBodyString")
                    try {
                        val jsonResponseArray = JSONArray(responseBodyString)
                        for (i in 0 until jsonResponseArray.length()) {
                            val item = jsonResponseArray.getJSONObject(i)
                            val currentItemTranslatedText: String = item.getJSONArray("translations").getJSONObject(0).getString("text").orEmpty()
                            val callbackInfo = callbackDataList!![i] // Assuming order is maintained

                            val unescapedTranslatedText = Utils.XMLUnescape(currentItemTranslatedText) ?: ""
                            cacheTranslation(callbackInfo.originalString, unescapedTranslatedText)
                            triggerSuccessCallback(unescapedTranslatedText, callbackInfo)
                        }
                    } catch (e: JSONException) {
                        Log.e("AllTrans", "Error parsing BATCH Microsoft JSON response: ${Log.getStackTraceString(e)}\nResponse body was: $responseBodyString")
                        handleBatchFailure(callbackDataList!!, "Batch JSON parsing error")
                    }
                    return // Batch processing ends here, individual callbacks handled by triggerSuccessCallback
                } else { // Single translation (Microsoft, Yandex, Google)
                    val localOriginalString = stringToBeTrans
                    Utils.debugLog("Single translation response for [$localOriginalString]: $responseBodyString")
                    var singleTranslatedText: String? = localOriginalString
                    try {
                        when (PreferenceList.TranslatorProvider) {
                            "y" -> {
                                responseBodyString?.let { nonNullResponseBody ->
                                    singleTranslatedText = if (nonNullResponseBody.contains("<text>")) {
                                        nonNullResponseBody.substring(nonNullResponseBody.indexOf("<text>") + 6, nonNullResponseBody.lastIndexOf("</text>"))
                                    } else {
                                        Log.w("AllTrans", "Unexpected Yandex response format: $nonNullResponseBody")
                                        localOriginalString
                                    }
                                } ?: run {
                                    Log.w("AllTrans", "Yandex response body was null, falling back to original.")
                                    singleTranslatedText = localOriginalString
                                }
                            }
                            "m" -> { // Single Microsoft
                                responseBodyString?.let { nonNullResponseBody ->
                                    try {
                                        singleTranslatedText = JSONArray(nonNullResponseBody).getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text").orEmpty()
                                    } catch (jsonEx: JSONException) {
                                        Log.e("AllTrans", "Error parsing SINGLE Microsoft JSON: ${Log.getStackTraceString(jsonEx)}\nResponse body was: $nonNullResponseBody")
                                        singleTranslatedText = localOriginalString
                                    }
                                } ?: run {
                                    Log.w("AllTrans", "Microsoft (single) response body was null, falling back to original.")
                                    singleTranslatedText = localOriginalString
                                }
                            }
                            else -> { // Google or default
                                singleTranslatedText = responseBodyString // This is safe as singleTranslatedText is String?
                            }
                        }
                        // XMLUnescape should handle null if singleTranslatedText is null
                        singleTranslatedText = Utils.XMLUnescape(singleTranslatedText.orEmpty())
                    } catch (e: Exception) {
                        // This catch block will handle exceptions from XMLUnescape or other unexpected issues
                        Log.e("AllTrans", "Error processing SINGLE translation response: ${Log.getStackTraceString(e)}\nResponse body might have been: $responseBodyString")
                        singleTranslatedText = localOriginalString // Fallback
                    }
                    translatedString = singleTranslatedText ?: localOriginalString // Ensure translatedString is not null
                    if (translatedString.isNullOrEmpty() && !localOriginalString.isNullOrEmpty()) { // If translation is empty but original wasn't, prefer original
                        Utils.debugLog("Translated string is null or empty, but original was not. Using original: [$localOriginalString]")
                        translatedString = localOriginalString
                    } else if (translatedString.isNullOrEmpty()) { // Both translated and original are null/empty
                        Utils.debugLog("Translated string is null or empty, and original is also null/empty. Setting to empty string.")
                        translatedString = "" // Fallback to empty string if both are null/empty
                    }

                    // Null check before calling cacheTranslation (from previous fix)
                    if (localOriginalString != null && translatedString != null) {
                        cacheTranslation(localOriginalString, translatedString)
                    } else {
                        Utils.debugLog("Skipping cacheTranslation due to null values: original=[$localOriginalString], translated=[$translatedString]")
                    }
                    // For single translations, the existing callback logic at the end of the function will be used.
                }
            }
        } catch (e: Throwable) {
            Log.e("AllTrans", "Unexpected error processing translation response: ${Log.getStackTraceString(e)}")
            if (isBatchMicrosoft) {
                handleBatchFailure(callbackDataList!!, "Generic error: ${e.message}")
                return // Batch processing ends here
            } else {
                translatedString = stringToBeTrans // Fallback for single
            }
        } finally {
            response.body?.close() // Ensure body is closed
            // This `finally` block will now primarily handle single translation callbacks.
            // Batch callbacks are handled within the `isBatchMicrosoft` block.
            if (!isBatchMicrosoft) {
                val finalString: String = translatedString ?: stringToBeTrans ?: ""
                Utils.debugLog("Final single translation result before callback: [$finalString]")
                triggerSingleCallback(finalString, stringToBeTrans, userData, originalCallable, canCallOriginal)
            }
        }
    }

    private fun cacheTranslation(original: String?, translated: String?) {
        if (original == null || translated == null || !PreferenceList.Caching || translated == original) {
            if (translated == original) Utils.debugLog("Skipping cache update for identical translation.")
            return
        }
        Alltrans.Companion.cacheAccess.acquireUninterruptibly()
        try {
            Utils.debugLog("Putting in cache: [$original] -> [$translated]")
            Alltrans.Companion.cache?.let {
                it[original] = translated // Only cache original -> translated mapping
                // Removed problematic line that cached translation as key for itself:
                // it[translated] = translated // This was causing translation loops
            } ?: Utils.debugLog("Cache object is null, cannot update cache.")
        } finally {
            if (Alltrans.Companion.cacheAccess.availablePermits() == 0) {
                Alltrans.Companion.cacheAccess.release()
            }
        }
    }

    private fun triggerSuccessCallback(translatedText: String, callbackInfo: CallbackInfo) {
        val currentHashCode = callbackInfo.userData?.hashCode()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (callbackInfo.userData is TextView) {
                    val tv = callbackInfo.userData
                    if (translatedText != callbackInfo.originalString) {
                        Utils.debugLog("Updating TextView (${tv.hashCode()}) with batch translated text: [$translatedText]")
                        tv.text = translatedText
                    } else {
                        Utils.debugLog("Skipping TextView update for (${tv.hashCode()}), batch translated text is same as original: [$translatedText]")
                    }
                } else if (callbackInfo.canCallOriginal && callbackInfo.originalCallable != null) {
                    Utils.debugLog("Calling originalCallable.callOriginalMethod for batch item with text: [$translatedText]")
                    callbackInfo.originalCallable.callOriginalMethod(translatedText, callbackInfo.userData)
                } else {
                    Utils.debugLog("No suitable callback action for batch item userData type: ${callbackInfo.userData?.javaClass?.name ?: "null"}")
                }
            } catch (e: Exception) {
                Log.e("AllTrans", "Error in batch success callback for ${callbackInfo.originalString}", e)
            } finally {
                currentHashCode?.let {
                    if (Alltrans.Companion.pendingTextViewTranslations.remove(it)) {
                        Utils.debugLog("Removed item hash ($it) from pending set after batch onResponse.")
                    }
                }
            }
        }, PreferenceList.Delay.toLong())
    }

    private fun triggerSingleCallback(finalString: String, originalString: String?, currentLocalUserData: Any?, currentLocalOriginalCallable: OriginalCallable?, currentLocalCanCallOriginal: Boolean) {
        val currentHashCode = currentLocalUserData?.hashCode()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (currentLocalUserData is TextView) {
                    if (finalString != originalString) { // only update if different
                        Utils.debugLog("Updating TextView (${currentLocalUserData.hashCode()}) with single translated text: [$finalString]")
                        currentLocalUserData.text = finalString
                    } else {
                        Utils.debugLog("Skipping TextView update for (${currentLocalUserData.hashCode()}), single translated text is same as original: [$finalString]")
                    }
                } else if (currentLocalCanCallOriginal && currentLocalOriginalCallable != null) {
                    Utils.debugLog("Calling originalCallable.callOriginalMethod for single item with text: [$finalString]")
                    currentLocalOriginalCallable.callOriginalMethod(finalString, currentLocalUserData)
                } else {
                    Utils.debugLog("No suitable callback action for single item userData type: ${currentLocalUserData?.javaClass?.name ?: "null"}")
                }
            } catch (e: Exception) {
                Log.e("AllTrans", "Error in single item callback for $originalString", e)
            } finally {
                currentHashCode?.let {
                    if (Alltrans.Companion.pendingTextViewTranslations.remove(it)) {
                        Utils.debugLog("Removed item hash ($it) from pending set after single onResponse.")
                    }
                }
            }
        }, PreferenceList.Delay.toLong())
    }

    private fun handleBatchFailure(callbacks: List<CallbackInfo>, reason: String) {
        Utils.debugLog("Handling batch failure: $reason")
        callbacks.forEach { cbInfo ->
            val currentHashCode = cbInfo.userData?.hashCode()
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (cbInfo.canCallOriginal && cbInfo.originalCallable != null) {
                        Utils.debugLog("Calling originalCallable for batch item ${cbInfo.originalString} on failure.")
                        cbInfo.originalCallable.callOriginalMethod(cbInfo.originalString ?: "", cbInfo.userData)
                    } else if (cbInfo.userData is TextView) {
                        Utils.debugLog("Network failure for TextView from batch (${cbInfo.userData.hashCode()}), original text (${cbInfo.originalString}) remains.")
                    } else {
                        Utils.debugLog("No suitable failure callback for batch item ${cbInfo.originalString}, userData: ${cbInfo.userData?.javaClass?.name}")
                    }
                } catch (t: Throwable) {
                    Log.e("AllTrans", "Error executing originalCallable on batch failure for ${cbInfo.originalString}", t)
                } finally {
                    currentHashCode?.let {
                        if (Alltrans.Companion.pendingTextViewTranslations.remove(it)) {
                            Utils.debugLog("Removed item hash ($it) from pending set after batch onFailure processing.")
                        }
                    }
                }
            }, PreferenceList.Delay.toLong())
        }
    }

    override fun onFailure(call: Call, e: IOException) {
        val isBatchMicrosoft = PreferenceList.TranslatorProvider == "m" && stringsToBeTrans?.isNotEmpty() == true && callbackDataList?.isNotEmpty() == true
        val localOriginalStringForSingle = stringToBeTrans // Only for single context
        val localUserDataForSingle = userData // Only for single context
        val localOriginalCallableForSingle = originalCallable
        val localCanCallOriginalForSingle = canCallOriginal

        Log.e("AllTrans", "Network request failed for: [${if(isBatchMicrosoft) "BATCH of " + stringsToBeTrans?.size + " items" else localOriginalStringForSingle ?: "Unknown"}] ${Log.getStackTraceString(e)}")

        if (isBatchMicrosoft) {
            handleBatchFailure(callbackDataList!!, "Network Error: ${e.message}")
        } else { // Single translation failure
            val currentHashCode = localUserDataForSingle?.hashCode()
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (localUserDataForSingle is TextView) {
                        Utils.debugLog("Network failure for single TextView ($currentHashCode), original text remains.")
                        // Text remains original, no action needed on TextView text itself
                    } else if (localCanCallOriginalForSingle && localOriginalCallableForSingle != null) {
                        Utils.debugLog("Calling originalCallable.callOriginalMethod on single failure for [$localOriginalStringForSingle]")
                        localOriginalCallableForSingle.callOriginalMethod(localOriginalStringForSingle.orEmpty(), localUserDataForSingle)
                    } else {
                        Utils.debugLog("No suitable failure callback for single item [$localOriginalStringForSingle], userData: ${localUserDataForSingle?.javaClass?.name}")
                    }
                } catch (t: Throwable) {
                    Log.e("AllTrans", "Error executing originalCallable on single failure for [$localOriginalStringForSingle]", t)
                } finally {
                    currentHashCode?.let {
                        if (Alltrans.Companion.pendingTextViewTranslations.remove(it)) {
                            Utils.debugLog("Removed item hash ($it) from pending set after single onFailure.")
                        }
                    }
                }
            }, PreferenceList.Delay.toLong())
        }
    }
}