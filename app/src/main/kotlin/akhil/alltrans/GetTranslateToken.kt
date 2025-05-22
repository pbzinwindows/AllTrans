package akhil.alltrans

import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import okhttp3.Cache
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class GetTranslateToken {
    var getTranslate: GetTranslate? = null

    fun doAll() {
        if (httpsClient == null && PreferenceList.TranslatorProvider != "g") {
            synchronized(GetTranslateToken::class.java) {
                if (httpsClient == null) {
                    val cache = createHttpsClientCache()
                    val builder = OkHttpClient.Builder()
                    cache?.let { builder.cache(it) }
                    builder.connectTimeout(10, TimeUnit.SECONDS)
                    builder.readTimeout(15, TimeUnit.SECONDS)
                    builder.writeTimeout(15, TimeUnit.SECONDS)
                    httpsClient = builder.build()
                    Log.i(
                        TAG,
                        "OkHttpClient initialized ${if (cache != null) "with" else "without"} file cache."
                    )
                }
            }
        }
        doInBackground()
    }

    private fun isKeyInvalid(key: String?): Boolean {
        return key.isNullOrEmpty() || key.startsWith(DEFAULT_KEY_PLACEHOLDER_PREFIX_PT) || key.startsWith(
            DEFAULT_KEY_PLACEHOLDER_PREFIX_EN
        )
    }

    // FIX #1: Creating a mock Call object for error handling
    private fun createMockCall(): Call {
        val mockRequest = Request.Builder().url("https://mock.error.call").build()
        return OkHttpClient().newCall(mockRequest)
    }

    private fun handleTranslationFailure(reason: String?, exception: Throwable?) {
        Log.e(
            TAG,
            "Translation failed: $reason for text: '${getTranslate?.stringToBeTrans ?: "N/A"}'",
            exception
        )
        getTranslate?.let {
            val ioEx = if (exception is IOException) exception else IOException(reason, exception)
            val mockCall = createMockCall()
            it.onFailure(mockCall, ioEx)
        } ?: Log.e(TAG, "GetTranslate object is null in handleTranslationFailure.")
    }

    private fun queryGoogleProvider(text: String?, fromLang: String?, toLang: String?): String? {
        val context = Alltrans.context
        if (text.isNullOrEmpty() || context == null) {
            if (context == null) {
                Log.e(TAG, "Static context is null in queryGoogleProvider.")
            }
            return text
        }

        val directUri: Uri
        val proxyUri: Uri
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val queryParams =
                "?from=${URLEncoder.encode(fromLang ?: "auto", "UTF-8")}&to=${URLEncoder.encode(
                    toLang ?: "en",
                    "UTF-8"
                )}&text=$encodedText"
            directUri = "content://akhil.alltrans.GtransProvider$queryParams".toUri()
            proxyUri =
                "content://settings/system/alltransProxyProviderURI/akhil.alltrans.GtransProvider$queryParams".toUri()
        } catch (e: UnsupportedEncodingException) {
            Log.e(TAG, "UTF-8 not supported?!", e)
            return text
        }

        val resolver = context.contentResolver
        var translatedText: String? = null
        var cursor: Cursor? = null
        val identity = Binder.clearCallingIdentity()

        try {
            try {
                Utils.debugLog("$TAG: Attempting proxy query for Google translation: $proxyUri")

                // Usa Android O (API 26) e superior com FLAGS para operações assíncronas
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Definir manualmente as constantes que só existem no Android O ou superior
                    val queryArgSelectionBehavior = "android:queryArgSelectionBehavior"
                    val selectionBehaviorStrict = 0
                    val extraHonoredArgs = "android:honorsExtraArgs"
                    val queryArgAsyncComputation = "android:asyncQuery"

                    val queryArgs = Bundle()
                    queryArgs.putInt(queryArgSelectionBehavior, selectionBehaviorStrict)
                    queryArgs.putBoolean(queryArgAsyncComputation, true)
                    queryArgs.putInt(extraHonoredArgs, 1)

                    cursor = resolver.query(proxyUri, arrayOf("translate"), queryArgs, null)
                } else {
                    // Fallback para API antiga
                    cursor = resolver.query(proxyUri, arrayOf("translate"), null, null, null)
                }

                if (cursor?.moveToFirst() == true) {
                    val columnIndex = cursor.getColumnIndex("translate")
                    if (columnIndex >= 0) {
                        translatedText = cursor.getString(columnIndex)
                        Utils.debugLog("$TAG: Proxy query successful.")
                    } else {
                        Utils.debugLog("$TAG: Column 'translate' not found in proxy cursor.")
                    }
                } else {
                    Utils.debugLog("$TAG: Proxy query failed or returned empty cursor.")
                }
            } catch (e: Exception) {
                Utils.debugLog("$TAG: Proxy query exception: ${e.message}")
            } finally {
                cursor?.close()
                cursor = null
            }

            if (translatedText == null) {
                try {
                    Utils.debugLog("$TAG: Attempting direct query for Google translation: $directUri")

                    // Usa Android O (API 26) e superior com FLAGS para operações assíncronas
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // Definir manualmente as constantes que só existem no Android O ou superior
                        val queryArgSelectionBehavior = "android:queryArgSelectionBehavior"
                        val selectionBehaviorStrict = 0
                        val extraHonoredArgs = "android:honorsExtraArgs"
                        val queryArgAsyncComputation = "android:asyncQuery"

                        val queryArgs = Bundle()
                        queryArgs.putInt(queryArgSelectionBehavior, selectionBehaviorStrict)
                        queryArgs.putBoolean(queryArgAsyncComputation, true)
                        queryArgs.putInt(extraHonoredArgs, 1)

                        cursor = resolver.query(directUri, arrayOf("translate"), queryArgs, null)
                    } else {
                        // Fallback para API antiga
                        cursor = resolver.query(directUri, arrayOf("translate"), null, null, null)
                    }

                    if (cursor?.moveToFirst() == true) {
                        val columnIndex = cursor.getColumnIndex("translate")
                        if (columnIndex >= 0) {
                            translatedText = cursor.getString(columnIndex)
                            Utils.debugLog("$TAG: Direct query successful.")
                        } else {
                            Utils.debugLog("$TAG: Column 'translate' not found in direct cursor.")
                        }
                    } else {
                        Utils.debugLog("$TAG: Direct query failed or returned empty cursor.")
                    }
                } catch (e: Exception) {
                    Utils.debugLog("$TAG: Direct query exception: ${Log.getStackTraceString(e)}")
                } finally {
                    cursor?.close()
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }

        return if (!translatedText.isNullOrEmpty()) {
            Utils.debugLog("$TAG: Google translation successful: [$text] -> [$translatedText]")
            translatedText
        } else {
            Utils.debugLog("$TAG: Google translation failed or returned original/empty text for: [$text]")
            text
        }
    }

    private fun doInBackground() {
        val callback = getTranslate ?: run {
            Log.e(TAG, "GetTranslate is null. Aborting.")
            return
        }

        // Check if we're in batch mode - batch is ONLY supported for Microsoft
        val isBatchMode = callback.stringsToBeTrans?.isNotEmpty() == true

        // Clear out batch data if we're not using Microsoft
        if (isBatchMode && PreferenceList.TranslatorProvider != "m") {
            Log.w(TAG, "Batch translation requested but provider is ${PreferenceList.TranslatorProvider}, not Microsoft. Converting to single item.")

            // Take just the first item if there are any
            if (callback.stringsToBeTrans?.isNotEmpty() == true && callback.callbackDataList?.isNotEmpty() == true) {
                callback.stringToBeTrans = callback.stringsToBeTrans!![0]
                val callbackInfo = callback.callbackDataList!![0]
                callback.userData = callbackInfo.userData
                callback.originalCallable = callbackInfo.originalCallable
                callback.canCallOriginal = callbackInfo.canCallOriginal
            }

            // Clear the batch lists
            callback.stringsToBeTrans = null
            callback.callbackDataList = null
        }

        // Re-check batch mode after potential conversion to single item
        val isActuallyBatchMode = callback.stringsToBeTrans?.isNotEmpty() == true && PreferenceList.TranslatorProvider == "m"

        val textToTranslate = if (!isActuallyBatchMode) {
            callback.stringToBeTrans ?: run {
                Log.e(TAG, "String to be translated is null. Aborting.")
                return
            }
        } else {
            // In batch mode, we don't need a single text to translate as we'll process the list
            "BATCH_MODE" // This is just a placeholder
        }

        // Verificamos apenas se o contexto existe, sem criar variável não utilizada
        if (Alltrans.context == null) {
            Log.e(TAG, "Static context is null. Aborting.")
            // Não estamos criando exceção que não será lançada - apenas passando mensagem de erro
            handleTranslationFailure(
                "Static context is null",
                null
            )
            return
        }

        try {
            val provider = PreferenceList.TranslatorProvider
            val fromLang = PreferenceList.TranslateFromLanguage
            val toLang = PreferenceList.TranslateToLanguage

            if (isActuallyBatchMode) {
                Utils.debugLog("$TAG: doInBackground - Provider: $provider, Batch mode with ${callback.stringsToBeTrans?.size} texts")
            } else {
                Utils.debugLog("$TAG: doInBackground - Provider: $provider, Single text mode: [$textToTranslate]")
            }

            when (provider) {
                "g" -> {
                    // Google provider doesn't support batch mode, always use single mode
                    Utils.debugLog("$TAG: Dispatching Google Provider query to background executor for: [$textToTranslate]")
                    googleQueryExecutor.submit {
                        var result: String? = textToTranslate
                        try {
                            result = queryGoogleProvider(
                                textToTranslate,
                                fromLang,
                                toLang
                            )
                        } catch (t: Throwable) {
                            handleTranslationFailure(
                                "Error executing Google Provider query in background",
                                t
                            )
                        }

                        val finalResult = result
                        val mockRequest =
                            Request.Builder().url("https://mock.google.translate.query").build()
                        val mockCall = OkHttpClient().newCall(mockRequest)
                        val response = Response.Builder()
                            .request(mockRequest)
                            .code(200).message("OK (from Provider Query)")
                            .protocol(Protocol.HTTP_2)
                            .body((finalResult ?: "").toResponseBody(null))
                            .build()

                        Handler(Looper.getMainLooper()).post {
                            getTranslate?.let { currentCallback ->
                                try {
                                    currentCallback.onResponse(mockCall, response)
                                } catch (t: Throwable) {
                                    Log.e(
                                        TAG,
                                        "Error executing getTranslate.onResponse in handler",
                                        t
                                    )
                                    handleTranslationFailure("Error in onResponse callback handler", t)
                                }
                            } ?: Log.w(TAG, "getTranslate became null before executing handler.")
                        }
                    }
                }
                "y" -> {
                    // Yandex doesn't support batch mode, ensure we're using single mode
                    val httpClient = httpsClient ?: run {
                        handleTranslationFailure(
                            "OkHttpClient is null for Yandex",
                            null
                        )
                        return
                    }

                    Utils.debugLog("$TAG: Using Yandex Provider")

                    val key = PreferenceList.SubscriptionKey ?: ""
                    if (isKeyInvalid(key)) {
                        handleTranslationFailure(
                            "Yandex API key is invalid.",
                            null
                        )
                        return
                    }

                    val baseURL = "https://translate.yandex.net/api/v1.5/tr/translate?"
                    val keyURL = "key=${URLEncoder.encode(key, "UTF-8")}"
                    val textURL = "&text=${URLEncoder.encode(textToTranslate, "UTF-8")}"
                    val fromLangSafe = fromLang ?: "auto"
                    val toLangSafe = toLang ?: "en"
                    val languageURL = "&lang=${URLEncoder.encode("$fromLangSafe-$toLangSafe", "UTF-8")}"
                    val fullURL = baseURL + keyURL + textURL + languageURL

                    Utils.debugLog("Yandex Request URL: $fullURL")
                    val request = Request.Builder().url(fullURL).get().build()
                    Utils.debugLog("Enqueuing Yandex request for: '$textToTranslate'")
                    httpClient.newCall(request).enqueue(callback)
                }
                "m" -> {
                    val httpClient = httpsClient ?: run {
                        handleTranslationFailure(
                            "OkHttpClient is null for Microsoft",
                            null
                        )
                        return
                    }

                    Utils.debugLog("$TAG: Using Microsoft Provider")

                    val key = PreferenceList.SubscriptionKey ?: ""
                    if (isKeyInvalid(key)) {
                        handleTranslationFailure(
                            "Microsoft API key is invalid.",
                            null
                        )
                        return
                    }

                    val baseURL = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0"
                    val fromLangSafe = fromLang ?: "auto"
                    val toLangSafe = toLang ?: "en"
                    val languageURL = "&to=${URLEncoder.encode(toLangSafe, "UTF-8")}" +
                            if (fromLangSafe != "auto") "&from=${URLEncoder.encode(fromLangSafe, "UTF-8")}" else ""

                    val fullURL = baseURL + languageURL
                    val requestBodyJson: String

                    // Only Microsoft supports true batch mode
                    if (isActuallyBatchMode && PreferenceList.CurrentAppMicrosoftBatchEnabled) {
                        // Batch Microsoft Request
                        val batchStrings = callback.stringsToBeTrans ?: emptyList()
                        Utils.debugLog("$TAG: Preparing batch Microsoft request for ${batchStrings.size} strings.")
                        val jsonArray = JSONArray()
                        batchStrings.forEach { text ->
                            val jsonObject = JSONObject()
                            jsonObject.put("Text", text)
                            jsonArray.put(jsonObject)
                        }
                        requestBodyJson = jsonArray.toString()
                    } else {
                        // Single Microsoft Request
                        Utils.debugLog("$TAG: Preparing single Microsoft request for: '$textToTranslate'")
                        val jsonArray = JSONArray()
                        val jsonObject = JSONObject()
                        jsonObject.put("Text", textToTranslate) // textToTranslate is already checked for nullity
                        jsonArray.put(jsonObject)
                        requestBodyJson = jsonArray.toString()
                    }

                    val body = requestBodyJson.toRequestBody(JSON_MEDIA_TYPE)
                    val requestBuilder = Request.Builder().url(fullURL).post(body)
                        .addHeader("Ocp-Apim-Subscription-Key", key)
                        .addHeader("Content-Type", "application/json; charset=UTF-8")

                    // Adicionar header de região se disponível
                    val region = PreferenceList.SubscriptionRegion
                    if (!region.isNullOrBlank()) {
                        val trimmedRegion = region.trim()
                        Utils.debugLog("Adding Microsoft API Region Header: $trimmedRegion")
                        requestBuilder.addHeader("Ocp-Apim-Subscription-Region", trimmedRegion)
                    } else {
                        Utils.debugLog("Microsoft API Region not specified.")
                    }

                    val request = requestBuilder.build()
                    Utils.debugLog("Microsoft Request URL: $fullURL")
                    Utils.debugLog("Microsoft Request Body: $requestBodyJson")

                    // Log different messages based on the mode
                    val logText = if (isActuallyBatchMode && PreferenceList.CurrentAppMicrosoftBatchEnabled) {
                        "batch of ${callback.stringsToBeTrans?.size ?: 0} strings"
                    } else {
                        "'$textToTranslate'"
                    }
                    Utils.debugLog("Enqueuing Microsoft request for: $logText")
                    httpClient.newCall(request).enqueue(callback)
                }
                else -> {
                    handleTranslationFailure(
                        "Unknown TranslatorProvider selected: $provider",
                        null
                    )
                }
            }
        } catch (e: IOException) {
            handleTranslationFailure("Error preparing translation request", e)
        } catch (e: JSONException) {
            handleTranslationFailure("Error preparing translation request", e)
        } catch (e: IllegalArgumentException) {
            handleTranslationFailure("Error preparing translation request", e)
        } catch (t: Throwable) {
            handleTranslationFailure("Unexpected error during translation request preparation", t)
        }
    }

    companion object {
        private var httpsClient: OkHttpClient? = null
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_KEY_PLACEHOLDER_PREFIX_PT = "Insira a sua chave"
        private const val DEFAULT_KEY_PLACEHOLDER_PREFIX_EN = "Enter"
        private const val TAG = "AllTrans:GetTranslateToken"
        private val googleQueryExecutor: ExecutorService = Executors.newSingleThreadExecutor()

        private fun createHttpsClientCache(): Cache? {
            val context = Alltrans.context ?: run {
                Log.e(TAG, "Context is null in createHttpsClientCache...")
                return null
            }

            val cacheSize = 1024 * 1024
            val cacheDirectory = File(context.cacheDir, "AllTransHTTPsCache")

            return try {
                Cache(cacheDirectory, cacheSize.toLong())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create cache...", e)
                null
            }
        }
    }
}