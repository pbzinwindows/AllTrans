package akhil.alltrans

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Cache
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
// import java.util.concurrent.Semaphore // Não está sendo usado
import java.util.concurrent.TimeUnit

internal class GetTranslateToken {
    var getTranslate: GetTranslate? = null

    fun doAll() {
        // Usar PreferenceList.effectiveTranslatorProvider para decidir sobre o OkHttpClient
        if (GetTranslateToken.Companion.httpsClient == null && PreferenceList.effectiveTranslatorProvider != "g") {
            synchronized(GetTranslateToken::class.java) {
                if (GetTranslateToken.Companion.httpsClient == null) {
                    val cache: Cache? = GetTranslateToken.Companion.createHttpsClientCache()
                    val builder = OkHttpClient.Builder()
                    if (cache != null) {
                        builder.cache(cache)
                    }
                    builder.connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .writeTimeout(15, TimeUnit.SECONDS)
                    GetTranslateToken.Companion.httpsClient = builder.build()
                    Log.i(TAG,"OkHttpClient initialized " + (if (cache != null) "with" else "without") + " file cache.")
                }
            }
        }
        doInBackground()
    }

    private fun isKeyInvalid(key: String?): Boolean {
        return key.isNullOrBlank() ||
                key.startsWith(DEFAULT_KEY_PLACEHOLDER_PREFIX_PT) ||
                key.startsWith(DEFAULT_KEY_PLACEHOLDER_PREFIX_EN)
    }

    private fun createMockCall(): Call {
        val mockRequest = Request.Builder().url("https://mock.error.call").build()
        return OkHttpClient().newCall(mockRequest)
    }

    private fun handleTranslationFailure(reason: String?, exception: Throwable?) {
        Log.e(TAG,"Translation failed: $reason for text: '${getTranslate?.stringToBeTrans ?: "N/A"}'", exception)
        getTranslate?.let {
            val ioEx = if (exception is IOException) exception else IOException(reason, exception)
            val mockCall = createMockCall()
            it.onFailure(mockCall, ioEx)
        }
    }

    private fun queryGoogleProvider(text: String?, fromLang: String?, toLang: String?): String? {
        val context = alltrans.Companion.context
        if (text.isNullOrEmpty() || context == null) {
            if (context == null) Log.e(TAG, "Static context is null in queryGoogleProvider.")
            return text
        }
        // ... (resto da lógica do queryGoogleProvider como estava, ela não usa chaves de API)
        var directUri: Uri? = null
        var proxyUri: Uri? = null
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val queryParams = "?from=${URLEncoder.encode(fromLang, "UTF-8")}&to=${URLEncoder.encode(toLang, "UTF-8")}&text=$encodedText"
            directUri = Uri.parse("content://akhil.alltrans.gtransProvider$queryParams")
            proxyUri = Uri.parse("content://settings/system/alltransProxyProviderURI/akhil.alltrans.gtransProvider$queryParams")
        } catch (e: UnsupportedEncodingException) {
            Log.e(TAG, "UTF-8 not supported?!", e)
            return text
        }
        val resolver: ContentResolver = context.contentResolver
        var translatedText: String? = null
        var cursor: Cursor? = null
        val identity = Binder.clearCallingIdentity()
        try {
            try {
                utils.debugLog("$TAG: Attempting proxy query for Google translation: $proxyUri")
                cursor = resolver.query(proxyUri, arrayOf("translate"), null, null, null)
                if (cursor?.moveToFirst() == true) {
                    val columnIndex = cursor.getColumnIndex("translate")
                    if (columnIndex >= 0) translatedText = cursor.getString(columnIndex)
                }
            } catch (e: Exception) {
                utils.debugLog("$TAG: Proxy query exception: ${e.message}")
            } finally {
                cursor?.close()
                cursor = null
            }
            if (translatedText == null) {
                try {
                    utils.debugLog("$TAG: Attempting direct query for Google translation: $directUri")
                    cursor = resolver.query(directUri, arrayOf("translate"), null, null, null)
                    if (cursor?.moveToFirst() == true) {
                        val columnIndex = cursor.getColumnIndex("translate")
                        if (columnIndex >= 0) translatedText = cursor.getString(columnIndex)
                    }
                } catch (e: Exception) {
                    utils.debugLog("$TAG: Direct query exception: ${Log.getStackTraceString(e)}")
                } finally {
                    cursor?.close()
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return if (!translatedText.isNullOrEmpty()) translatedText else text
    }


    private fun doInBackground() {
        val callback = getTranslate
        if (callback?.stringToBeTrans == null) {
            Log.e(TAG, "GetTranslate or string to translate is null. Aborting.")
            return
        }
        val context = alltrans.Companion.context
        if (context == null) {
            Log.e(TAG, "Static context is null. Aborting.")
            handleTranslationFailure("Static context is null", IllegalStateException("Context is null"))
            return
        }

        try {
            // USA AS PREFERÊNCIAS EFETIVAS DA NOSSA ESTRUTURA PreferenceList
            val provider = PreferenceList.effectiveTranslatorProvider
            val textToTranslate = callback.stringToBeTrans!! // Non-null devido à verificação acima
            val fromLang = PreferenceList.TranslateFromLanguage // Já é o efetivo
            val toLang = PreferenceList.TranslateToLanguage     // Já é o efetivo
            val subscriptionKey = PreferenceList.effectiveSubscriptionKey
            val region = PreferenceList.effectiveSubscriptionRegion

            utils.debugLog("$TAG: doInBackground - Effective Provider: '$provider', Key Set: ${!subscriptionKey.isNullOrBlank()}, Region Set: ${!region.isNullOrBlank()}, From: '$fromLang', To: '$toLang', Text: [$textToTranslate]")

            when (provider) {
                "g" -> {
                    utils.debugLog("$TAG: Dispatching Google Provider query for: [$textToTranslate]")
                    googleQueryExecutor.submit {
                        var result: String? = textToTranslate
                        try {
                            result = queryGoogleProvider(textToTranslate, fromLang, toLang)
                        } catch (t: Throwable) {
                            handleTranslationFailure("Error executing Google Provider query", t)
                        }
                        val finalResult = result
                        val mockRequest = Request.Builder().url("https://mock.google.provider").build()
                        val mockCall = OkHttpClient().newCall(mockRequest)
                        val response = Response.Builder()
                            .request(mockRequest).code(200).message("OK (from Provider Query)")
                            .protocol(Protocol.HTTP_2).body((finalResult ?: "").toResponseBody(null))
                            .build()
                        Handler(Looper.getMainLooper()).post { getTranslate?.onResponse(mockCall, response) }
                    }
                }
                "y" -> {
                    val httpClient = httpsClient ?: run {
                        handleTranslationFailure("OkHttpClient is null for Yandex", IllegalStateException("httpsClient is null for Yandex"))
                        return
                    }
                    utils.debugLog("$TAG: Using Yandex Provider. Key: '${subscriptionKey?.take(5)}...'")
                    if (isKeyInvalid(subscriptionKey)) {
                        handleTranslationFailure("Yandex API key is invalid or not set.", IOException("Invalid Yandex Key"))
                        return
                    }
                    val baseURL = "https://translate.yandex.net/api/v1.5/tr/translate?"
                    val keyURL = "key=" + URLEncoder.encode(subscriptionKey, "UTF-8")
                    val textURL = "&text=" + URLEncoder.encode(textToTranslate, "UTF-8")
                    val languageURL = "&lang=" + URLEncoder.encode("$fromLang-$toLang", "UTF-8")
                    val fullURL = baseURL + keyURL + textURL + languageURL
                    val request = Request.Builder().url(fullURL).get().build()
                    httpClient.newCall(request).enqueue(callback)
                }
                "m" -> {
                    val httpClient = httpsClient ?: run {
                        handleTranslationFailure("OkHttpClient is null for Microsoft", IllegalStateException("httpsClient is null for Microsoft"))
                        return
                    }
                    utils.debugLog("$TAG: Using Microsoft Provider. Key: '${subscriptionKey?.take(5)}...', Region: '$region'")
                    if (isKeyInvalid(subscriptionKey)) {
                        handleTranslationFailure("Microsoft API key is invalid or not set.", IOException("Invalid Microsoft Key"))
                        return
                    }
                    val baseURL = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0"
                    var languageURL = "&to=" + URLEncoder.encode(toLang, "UTF-8")
                    if (fromLang != "auto") {
                        languageURL += "&from=" + URLEncoder.encode(fromLang, "UTF-8")
                    }
                    val fullURL = baseURL + languageURL
                    val jsonArray = JSONArray().apply { put(JSONObject().apply { put("Text", textToTranslate) }) }
                    val requestBodyJson = jsonArray.toString()
                    val body: RequestBody = requestBodyJson.toRequestBody(JSON_MEDIA_TYPE)
                    val requestBuilder = Request.Builder().url(fullURL).post(body)
                        .addHeader("Ocp-Apim-Subscription-Key", subscriptionKey!!) // Not null due to isKeyInvalid
                        .addHeader("Content-Type", "application/json; charset=UTF-8")
                    if (!region.isNullOrBlank()) {
                        requestBuilder.addHeader("Ocp-Apim-Subscription-Region", region.trim())
                    }
                    val request = requestBuilder.build()
                    httpClient.newCall(request).enqueue(callback)
                }
                else -> {
                    handleTranslationFailure("Unknown effective TranslatorProvider: '$provider'", IOException("Unknown Provider: $provider"))
                }
            }
        } catch (e: Exception) { // Captura genérica para erros como URLEncoderException
            handleTranslationFailure("Error preparing/dispatching translation request", e)
        }
    }

    companion object {
        private var httpsClient: OkHttpClient? = null
        private val JSON_MEDIA_TYPE: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()
        private const val DEFAULT_KEY_PLACEHOLDER_PREFIX_PT = "Insira a sua chave"
        private const val DEFAULT_KEY_PLACEHOLDER_PREFIX_EN = "Enter" // Para checagem de chave default
        private const val TAG = "AllTrans:GetTranslateToken"
        private val googleQueryExecutor: ExecutorService = Executors.newSingleThreadExecutor()

        private fun createHttpsClientCache(): Cache? {
            val context = alltrans.Companion.context ?: return null
            val cacheSize = 10 * 1024 * 1024 // 10MB
            val cacheDirectory = File(context.cacheDir, "AllTransHTTPsCache")
            return try {
                Cache(cacheDirectory, cacheSize.toLong())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create HTTP cache", e)
                null
            }
        }
    }
}