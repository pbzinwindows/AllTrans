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
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class GetTranslate : Callback {
    private val TAG = "AllTrans:GetTranslate"
    var stringToBeTrans: String? = null
    var originalCallable: OriginalCallable? = null
    var canCallOriginal: Boolean = false
    var userData: Any? = null
    var stringsToBeTrans: List<String>? = null
    var callbackDataList: List<CallbackInfo>? = null
    private var translatedString: String? = null
    var pendingCompositeKey: Int = 0

    // Thread safety for response handling
    private val responseHandled = AtomicBoolean(false)
    private val failureHandled = AtomicBoolean(false)

    override fun onResponse(call: Call, response: Response) {
        // Prevent duplicate response handling
        if (!responseHandled.compareAndSet(false, true)) {
            Utils.debugLog("$TAG: Response already handled for call: ${call.request().url}")
            response.body?.close()
            return
        }

        val startTime = System.currentTimeMillis()
        val isBatchMicrosoft = PreferenceList.TranslatorProvider == "m" &&
                stringsToBeTrans?.isNotEmpty() == true &&
                callbackDataList?.isNotEmpty() == true
        var responseBodyString: String? = null

        try {
            if (!response.isSuccessful) {
                Utils.debugLog("$TAG: Got response code as : ${response.code}")
                responseBodyString = response.body?.string() ?: "null"
                Utils.debugLog("$TAG: Got error response body as : $responseBodyString")
                if (isBatchMicrosoft) {
                    handleBatchFailure(callbackDataList!!, "HTTP Error: ${response.code}")
                } else {
                    translatedString = stringToBeTrans
                }
            } else {
                responseBodyString = response.body!!.string()
                if (isBatchMicrosoft) {
                    Utils.debugLog("$TAG: Batch Microsoft response: $responseBodyString")
                    processBatchResponse(responseBodyString)
                    return
                } else {
                    processSingleResponse(responseBodyString)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error processing translation response: ${Log.getStackTraceString(e)}")
            if (isBatchMicrosoft) {
                handleBatchFailure(callbackDataList!!, "Generic error: ${e.message}")
                return
            } else {
                translatedString = stringToBeTrans
            }
        } finally {
            response.body?.close()
            if (!isBatchMicrosoft) {
                val finalString: String = translatedString ?: stringToBeTrans ?: ""
                val processingTime = System.currentTimeMillis() - startTime
                Utils.debugLog("$TAG: Single response processed in ${processingTime}ms: [$finalString]")
                triggerSingleCallback(finalString, stringToBeTrans, userData, originalCallable, canCallOriginal, pendingCompositeKey)
            }
        }
    }

    private fun processBatchResponse(responseBodyString: String) {
        try {
            val jsonResponseArray = JSONArray(responseBodyString)
            val toLang = PreferenceList.TranslateToLanguage

            // Process in parallel for large batches
            val callbackList = callbackDataList!!
            if (callbackList.size > 10) {
                processBatchResponseParallel(jsonResponseArray, callbackList, toLang)
            } else {
                processBatchResponseSequential(jsonResponseArray, callbackList, toLang)
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing BATCH Microsoft JSON response: ${Log.getStackTraceString(e)}\nResponse body was: $responseBodyString")
            handleBatchFailure(callbackDataList!!, "Batch JSON parsing error")
        }
    }

    private fun processBatchResponseSequential(jsonResponseArray: JSONArray, callbackList: List<CallbackInfo>, toLang: String?) {
        for (i in 0 until jsonResponseArray.length()) {
            if (i >= callbackList.size) {
                Log.e(TAG, "Mismatch between response array size (${jsonResponseArray.length()}) and callbackDataList size (${callbackList.size})")
                break
            }

            processBatchItem(jsonResponseArray.getJSONObject(i), callbackList[i], toLang)
        }
    }

    private fun processBatchResponseParallel(jsonResponseArray: JSONArray, callbackList: List<CallbackInfo>, toLang: String?) {
        val tasks = mutableListOf<Runnable>()

        for (i in 0 until minOf(jsonResponseArray.length(), callbackList.size)) {
            val item = jsonResponseArray.getJSONObject(i)
            val callbackInfo = callbackList[i]

            tasks.add {
                processBatchItem(item, callbackInfo, toLang)
            }
        }

        // Execute tasks in parallel using the network executor
        GetTranslateToken.submitIoTask {
            tasks.parallelStream().forEach { it.run() }
        }
    }

    private fun processBatchItem(item: org.json.JSONObject, callbackInfo: CallbackInfo, toLang: String?) {
        try {
            var currentItemTranslatedText: String = item.getJSONArray("translations").getJSONObject(0).getString("text").orEmpty()
            val originalItemString = callbackInfo.originalString

            if (PreferenceList.TranslateFromLanguage == "auto") {
                val detectedLang = item.optJSONObject("detectedLanguage")?.optString("language")
                if (detectedLang != null && detectedLang == toLang) {
                    Utils.debugLog("$TAG: Batch MS: Auto-detected language ($detectedLang) matches target ($toLang). Using original text: [$originalItemString]")
                    currentItemTranslatedText = originalItemString ?: ""
                }
            }

            val unescapedTranslatedText = Utils.XMLUnescape(currentItemTranslatedText) ?: ""
            if (originalItemString != null) {
                cacheTranslation(originalItemString, unescapedTranslatedText)
            }
            triggerSuccessCallback(unescapedTranslatedText, callbackInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing batch item for: ${callbackInfo.originalString}", e)
            triggerSuccessCallback(callbackInfo.originalString ?: "", callbackInfo)
        }
    }

    private fun processSingleResponse(responseBodyString: String) {
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
                "m" -> {
                    responseBodyString?.let { nonNullResponseBody ->
                        try {
                            val jsonResponseObject = JSONArray(nonNullResponseBody).getJSONObject(0)
                            singleTranslatedText = jsonResponseObject.getJSONArray("translations").getJSONObject(0).getString("text").orEmpty()

                            if (PreferenceList.TranslateFromLanguage == "auto") {
                                val detectedLang = jsonResponseObject.optJSONObject("detectedLanguage")?.optString("language")
                                val toLang = PreferenceList.TranslateToLanguage
                                if (detectedLang != null && detectedLang == toLang) {
                                    Utils.debugLog("$TAG: Single MS: Auto-detected language ($detectedLang) matches target ($toLang). Using original text: [$localOriginalString]")
                                    singleTranslatedText = localOriginalString
                                } else {
                                    singleTranslatedText = Utils.XMLUnescape(singleTranslatedText.orEmpty())
                                }
                            } else {
                                singleTranslatedText = Utils.XMLUnescape(singleTranslatedText.orEmpty())
                            }
                        } catch (jsonEx: JSONException) {
                            Log.e(TAG, "Error parsing SINGLE Microsoft JSON: ${Log.getStackTraceString(jsonEx)}\nResponse body was: $nonNullResponseBody")
                            singleTranslatedText = localOriginalString
                        }
                    } ?: run {
                        Log.w(TAG, "Microsoft (single) response body was null, falling back to original.")
                        singleTranslatedText = localOriginalString
                    }
                }
                else -> {
                    singleTranslatedText = responseBodyString
                    singleTranslatedText = Utils.XMLUnescape(singleTranslatedText.orEmpty())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SINGLE translation response: ${Log.getStackTraceString(e)}\nResponse body might have been: $responseBodyString")
            singleTranslatedText = localOriginalString
        }

        translatedString = singleTranslatedText ?: localOriginalString
        if (translatedString.isNullOrEmpty() && !localOriginalString.isNullOrEmpty()) {
            Utils.debugLog("$TAG: Translated string is null or empty, but original was not. Using original: [$localOriginalString]")
            translatedString = localOriginalString
        } else if (translatedString.isNullOrEmpty()) {
            Utils.debugLog("$TAG: Translated string is null or empty, and original is also null/empty. Setting to empty string.")
            translatedString = ""
        }

        if (localOriginalString != null && translatedString != null) {
            // Cache update asynchronously
            GetTranslateToken.submitIoTask {
                cacheTranslation(localOriginalString, translatedString)
            }
        } else {
            Utils.debugLog("$TAG: Skipping cacheTranslation due to null values: original=[$localOriginalString], translated=[$translatedString]")
        }
    }

    private fun cacheTranslation(original: String?, translated: String?) {
        if (original == null || translated == null || !PreferenceList.Caching) {
            if (!PreferenceList.Caching) Utils.debugLog("$TAG: Skipping cache update for [$original] -> [$translated] because Caching is disabled.")
            return
        }
        if (translated == original) {
            Utils.debugLog("$TAG: Skipping cache update for identical translation: [$original]")
            return
        }

        Alltrans.cacheAccess.acquireUninterruptibly()
        try {
            Alltrans.cache?.let {
                Utils.debugLog("$TAG: Putting in cache: [$original] -> [$translated]")
                it.put(original, translated)
            } ?: Utils.debugLog("$TAG: Cache object is null, cannot update cache for [$original].")
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }
    }

    private fun triggerSuccessCallback(translatedText: String, callbackInfo: CallbackInfo) {
        val keyToRemoveFromPending = callbackInfo.pendingCompositeKey
        val delay = PreferenceList.Delay.toLong()

        val task = Runnable {
            try {
                val weakView = callbackInfo.userData as? WeakReference<*>
                val view = weakView?.get()

                if (view is TextView) {
                    val tv = view
                    if (translatedText != callbackInfo.originalString || !tv.text.toString().equals(translatedText)) {
                        Utils.debugLog("$TAG: Batch: Updating TextView (${tv.hashCode()}) with key ($keyToRemoveFromPending) with translated text: [$translatedText]")
                        tv.setTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY, true)
                        tv.text = translatedText
                    } else {
                        Utils.debugLog("$TAG: Batch: Skipping TextView update for (${tv.hashCode()}), key ($keyToRemoveFromPending), translated text is same as original or already applied: [$translatedText]")
                    }
                } else if (view == null) {
                    Utils.debugLog("$TAG: Batch: TextView was garbage collected. Aborting translation update for key ($keyToRemoveFromPending).")
                } else if (callbackInfo.canCallOriginal && callbackInfo.originalCallable != null) {
                    Utils.debugLog("$TAG: Batch: Calling originalCallable.callOriginalMethod for key ($keyToRemoveFromPending) with text: [$translatedText]")
                    callbackInfo.originalCallable.callOriginalMethod(translatedText, callbackInfo.userData)
                } else {
                    Utils.debugLog("$TAG: Batch: No suitable callback action for key ($keyToRemoveFromPending), userData type: ${callbackInfo.userData?.javaClass?.name ?: "null"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in batch success callback for key ($keyToRemoveFromPending), original: ${callbackInfo.originalString}", e)
            } finally {
                synchronized(Alltrans.pendingTextViewTranslations) {
                    if (Alltrans.pendingTextViewTranslations.remove(keyToRemoveFromPending)) {
                        Utils.debugLog("$TAG: Batch: Removed composite key ($keyToRemoveFromPending) from pending set after onResponse.")
                    }
                }
            }
        }

        if (delay > 0) {
            Handler(Looper.getMainLooper()).postDelayed(task, delay)
        } else {
            Handler(Looper.getMainLooper()).post(task)
        }
    }

    private fun triggerSingleCallback(finalString: String, originalString: String?, currentLocalUserData: Any?, currentLocalOriginalCallable: OriginalCallable?, currentLocalCanCallOriginal: Boolean, keyToRemove: Int) {
        val delay = PreferenceList.Delay.toLong()

        val task = Runnable {
            try {
                val weakView = currentLocalUserData as? WeakReference<*>
                val view = weakView?.get()

                if (view is TextView) {
                    val tv = view
                    if (finalString != originalString || !tv.text.toString().equals(finalString)) {
                        Utils.debugLog("$TAG: Single: Updating TextView (${tv.hashCode()}) with key ($keyToRemove) with translated text: [$finalString]")
                        tv.setTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY, true)
                        tv.text = finalString
                    } else {
                        Utils.debugLog("$TAG: Single: Skipping TextView update for (${tv.hashCode()}), key ($keyToRemove), translated text is same or already applied: [$finalString]")
                    }
                } else if (view == null) {
                    Utils.debugLog("$TAG: Single: TextView was garbage collected. Aborting translation update for key ($keyToRemove).")
                } else if (currentLocalCanCallOriginal && currentLocalOriginalCallable != null) {
                    Utils.debugLog("$TAG: Single: Calling originalCallable.callOriginalMethod for key ($keyToRemove) with text: [$finalString]")
                    currentLocalOriginalCallable.callOriginalMethod(finalString, currentLocalUserData)
                } else {
                    Utils.debugLog("$TAG: Single: No suitable callback action for key ($keyToRemove), userData type: ${currentLocalUserData?.javaClass?.name ?: "null"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in single item callback for key ($keyToRemove), original: $originalString", e)
            } finally {
                synchronized(Alltrans.pendingTextViewTranslations) {
                    if (Alltrans.pendingTextViewTranslations.remove(keyToRemove)) {
                        Utils.debugLog("$TAG: Single: Removed composite key ($keyToRemove) from pending set after onResponse.")
                    }
                }
            }
        }

        if (delay > 0) {
            Handler(Looper.getMainLooper()).postDelayed(task, delay)
        } else {
            Handler(Looper.getMainLooper()).post(task)
        }
    }

    private fun handleBatchFailure(callbacks: List<CallbackInfo>, reason: String) {
        Utils.debugLog("$TAG: Handling batch failure: $reason")
        val delay = PreferenceList.Delay.toLong()

        callbacks.forEach { cbInfo ->
            val keyToRemoveFromPending = cbInfo.pendingCompositeKey
            val task = Runnable {
                try {
                    if (cbInfo.canCallOriginal && cbInfo.originalCallable != null) {
                        Utils.debugLog("$TAG: Batch: Calling originalCallable for item ${cbInfo.originalString} (key $keyToRemoveFromPending) on failure.")
                        cbInfo.originalCallable.callOriginalMethod(cbInfo.originalString ?: "", cbInfo.userData)
                    } else if (cbInfo.userData is TextView) {
                        Utils.debugLog("$TAG: Batch: Network failure for TextView (key $keyToRemoveFromPending), original text (${cbInfo.originalString}) remains.")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error executing originalCallable on batch failure for key ($keyToRemoveFromPending), original: ${cbInfo.originalString}", t)
                } finally {
                    synchronized(Alltrans.pendingTextViewTranslations) {
                        if (Alltrans.pendingTextViewTranslations.remove(keyToRemoveFromPending)) {
                            Utils.debugLog("$TAG: Batch: Removed composite key ($keyToRemoveFromPending) from pending set after onFailure.")
                        }
                    }
                }
            }

            if (delay > 0) {
                Handler(Looper.getMainLooper()).postDelayed(task, delay)
            } else {
                Handler(Looper.getMainLooper()).post(task)
            }
        }
    }

    override fun onFailure(call: Call, e: IOException) {
        // Prevent duplicate failure handling
        if (!failureHandled.compareAndSet(false, true)) {
            Utils.debugLog("$TAG: Failure already handled for call: ${call.request().url}")
            return
        }

        val isBatchMicrosoft = PreferenceList.TranslatorProvider == "m" &&
                stringsToBeTrans?.isNotEmpty() == true &&
                callbackDataList?.isNotEmpty() == true

        Log.e(TAG, "Network request failed for: [${if(isBatchMicrosoft) "BATCH of " + (stringsToBeTrans?.size ?: 0) + " items" else stringToBeTrans ?: "Unknown Single Item"}] Reason: ${Log.getStackTraceString(e)}")

        if (isBatchMicrosoft) {
            handleBatchFailure(callbackDataList!!, "Network Error: ${e.message}")
        } else {
            val keyToRemove = pendingCompositeKey
            val localOriginalStringForSingle = stringToBeTrans
            val localUserDataForSingle = userData
            val localOriginalCallableForSingle = originalCallable
            val localCanCallOriginalForSingle = canCallOriginal
            val delay = PreferenceList.Delay.toLong()

            val task = Runnable {
                try {
                    if (localUserDataForSingle is TextView) {
                        Utils.debugLog("$TAG: Single: Network failure for TextView (key $keyToRemove), original text ($localOriginalStringForSingle) remains.")
                    } else if (localCanCallOriginalForSingle && localOriginalCallableForSingle != null) {
                        Utils.debugLog("$TAG: Single: Calling originalCallable.callOriginalMethod on failure for key ($keyToRemove), text: [$localOriginalStringForSingle]")
                        localOriginalCallableForSingle.callOriginalMethod(localOriginalStringForSingle.orEmpty(), localUserDataForSingle)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error executing originalCallable on single failure for key ($keyToRemove), original: [$localOriginalStringForSingle]", t)
                } finally {
                    synchronized(Alltrans.pendingTextViewTranslations) {
                        if (Alltrans.pendingTextViewTranslations.remove(keyToRemove)) {
                            Utils.debugLog("$TAG: Single: Removed composite key ($keyToRemove) from pending set after onFailure.")
                        }
                    }
                }
            }

            if (delay > 0) {
                Handler(Looper.getMainLooper()).postDelayed(task, delay)
            } else {
                Handler(Looper.getMainLooper()).post(task)
            }
        }
    }
}