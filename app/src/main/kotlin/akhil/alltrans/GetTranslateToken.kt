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
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal class GetTranslateToken {
    var getTranslate: GetTranslate? = null

    fun doAll() {
        if (GetTranslateToken.Companion.httpsClient == null && PreferenceList.TranslatorProvider != "g") {
            synchronized(GetTranslateToken::class.java) {
                if (GetTranslateToken.Companion.httpsClient == null) {
                    val cache: Cache? = GetTranslateToken.Companion.createHttpsClientCache()
                    val builder = OkHttpClient.Builder()
                    if (cache != null) {
                        builder.cache(cache)
                    }
                    builder.connectTimeout(10, TimeUnit.SECONDS)
                    builder.readTimeout(15, TimeUnit.SECONDS)
                    builder.writeTimeout(15, TimeUnit.SECONDS)
                    GetTranslateToken.Companion.httpsClient = builder.build()
                    Log.i(
                        GetTranslateToken.Companion.TAG,
                        "OkHttpClient initialized " + (if (cache != null) "with" else "without") + " file cache."
                    )
                }
            }
        }
        doInBackground()
    }

    private fun isKeyInvalid(key: String?): Boolean {
        return key == null || key.trim { it <= ' ' }
            .isEmpty() || key.startsWith(GetTranslateToken.Companion.DEFAULT_KEY_PLACEHOLDER_PREFIX_PT) || key.startsWith(
            GetTranslateToken.Companion.DEFAULT_KEY_PLACEHOLDER_PREFIX_EN
        )
    }

    // FIX #1: Creating a mock Call object for error handling
    private fun createMockCall(): Call {
        val mockRequest = Request.Builder().url("https://mock.error.call").build()
        return OkHttpClient().newCall(mockRequest)
    }

    private fun handleTranslationFailure(reason: String?, exception: Throwable?) {
        Log.e(
            GetTranslateToken.Companion.TAG,
            "Translation failed: " + reason + " for text: '" + (if (getTranslate != null) getTranslate!!.stringToBeTrans else "N/A") + "'",
            exception
        )
        if (getTranslate != null) {
            val ioEx = if (exception is IOException) exception else IOException(reason, exception)
            // FIX #1: Use a mock Call object instead of null
            val mockCall = createMockCall()
            getTranslate!!.onFailure(mockCall, ioEx)
        } else {
            Log.e(
                GetTranslateToken.Companion.TAG,
                "GetTranslate object is null in handleTranslationFailure."
            )
        }
    }

    private fun queryGoogleProvider(text: String?, fromLang: String?, toLang: String?): String? {
        val context = alltrans.Companion.context
        if (text == null || text.isEmpty() || context == null) {
            if (context == null) {
                Log.e(TAG, "Static context is null in queryGoogleProvider.")
            }
            return text
        }

        var directUri: Uri? = null
        var proxyUri: Uri? = null
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val queryParams =
                "?from=" + URLEncoder.encode(fromLang, "UTF-8") + "&to=" + URLEncoder.encode(
                    toLang,
                    "UTF-8"
                ) + "&text=" + encodedText
            directUri = Uri.parse("content://akhil.alltrans.gtransProvider" + queryParams)
            proxyUri =
                Uri.parse("content://settings/system/alltransProxyProviderURI/akhil.alltrans.gtransProvider" + queryParams)
        } catch (e: UnsupportedEncodingException) {
            Log.e(GetTranslateToken.Companion.TAG, "UTF-8 not supported?!", e)
            return text
        }

        val resolver: ContentResolver = context.getContentResolver()
        var translatedText: String? = null
        var cursor: Cursor? = null
        val identity = Binder.clearCallingIdentity()
        try {
            try {
                utils.debugLog(GetTranslateToken.Companion.TAG + ": Attempting proxy query for Google translation: " + proxyUri)
                cursor = resolver.query(proxyUri, arrayOf<String>("translate"), null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex("translate")
                    if (columnIndex >= 0) {
                        translatedText = cursor.getString(columnIndex)
                        utils.debugLog(GetTranslateToken.Companion.TAG + ": Proxy query successful.")
                    } else {
                        utils.debugLog(GetTranslateToken.Companion.TAG + ": Column 'translate' not found in proxy cursor.")
                    }
                } else {
                    utils.debugLog(GetTranslateToken.Companion.TAG + ": Proxy query failed or returned empty cursor.")
                }
            } catch (e: Exception) {
                utils.debugLog(GetTranslateToken.Companion.TAG + ": Proxy query exception: " + e.message)
            } finally {
                if (cursor != null) {
                    cursor.close()
                    cursor = null
                }
            }

            if (translatedText == null) {
                try {
                    utils.debugLog(GetTranslateToken.Companion.TAG + ": Attempting direct query for Google translation: " + directUri)
                    cursor =
                        resolver.query(directUri, arrayOf<String>("translate"), null, null, null)
                    if (cursor != null && cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndex("translate")
                        if (columnIndex >= 0) {
                            translatedText = cursor.getString(columnIndex)
                            utils.debugLog(GetTranslateToken.Companion.TAG + ": Direct query successful.")
                        } else {
                            utils.debugLog(GetTranslateToken.Companion.TAG + ": Column 'translate' not found in direct cursor.")
                        }
                    } else {
                        utils.debugLog(GetTranslateToken.Companion.TAG + ": Direct query failed or returned empty cursor.")
                    }
                } catch (e: Exception) {
                    utils.debugLog(
                        GetTranslateToken.Companion.TAG + ": Direct query exception: " + Log.getStackTraceString(
                            e
                        )
                    )
                } finally {
                    if (cursor != null) {
                        cursor.close()
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }

        if (translatedText != null && !translatedText.isEmpty()) {
            utils.debugLog(GetTranslateToken.Companion.TAG + ": Google translation successful: [" + text + "] -> [" + translatedText + "]")
            return translatedText
        } else {
            utils.debugLog(GetTranslateToken.Companion.TAG + ": Google translation failed or returned original/empty text for: [" + text + "]")
            return text
        }
    }

    private fun doInBackground() {
        val callback = getTranslate
        if (callback == null || callback.stringToBeTrans == null) {
            Log.e(GetTranslateToken.Companion.TAG, "GetTranslate or string is null. Aborting.")
            return
        }
        val context = alltrans.Companion.context
        if (context == null) {
            Log.e(GetTranslateToken.Companion.TAG, "Static context is null. Aborting.")
            handleTranslationFailure(
                "Static context is null",
                IllegalStateException("Context is null")
            )
            return
        }

        try {
            val provider = PreferenceList.TranslatorProvider
            val textToTranslate = callback.stringToBeTrans
            val fromLang = PreferenceList.TranslateFromLanguage
            val toLang = PreferenceList.TranslateToLanguage

            utils.debugLog(GetTranslateToken.Companion.TAG + ": doInBackground - Provider: " + provider + ", Text: [" + textToTranslate + "]")

            if ("g" == provider) {
                utils.debugLog(GetTranslateToken.Companion.TAG + ": Dispatching Google Provider query to background executor for: [" + textToTranslate + "]")
                GetTranslateToken.Companion.googleQueryExecutor.submit(Runnable {
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
                    val mockCall = OkHttpClient().newCall(mockRequest) // Create the mock call
                    val response = Response.Builder()
                        .request(mockRequest)
                        .code(200).message("OK (from Provider Query)")
                        .protocol(Protocol.HTTP_2)
                        .body((finalResult ?: "").toResponseBody(null))
                        .build()

                    Handler(Looper.getMainLooper()).post(Runnable {
                        getTranslate?.let { currentCallback ->
                            try {
                                currentCallback.onResponse(mockCall, response)
                            } catch (t: Throwable) {
                                Log.e(
                                    GetTranslateToken.Companion.TAG,
                                    "Error executing getTranslate.onResponse in handler",
                                    t
                                )
                                handleTranslationFailure("Error in onResponse callback handler", t)
                            }
                        } ?: Log.w(TAG, "getTranslate became null before executing handler.")
                    })
                })
            } else if ("y" == provider) {
                val httpClient = GetTranslateToken.Companion.httpsClient
                if (httpClient == null) {
                    handleTranslationFailure(
                        "OkHttpClient is null for Yandex",
                        IllegalStateException("httpsClient is null")
                    )
                    return
                }
                utils.debugLog(GetTranslateToken.Companion.TAG + ": Using Yandex Provider")
                if (isKeyInvalid(PreferenceList.SubscriptionKey)) {
                    handleTranslationFailure(
                        "Yandex API key is invalid.",
                        IOException("Invalid Yandex Key")
                    )
                    return
                }
                val baseURL = "https://translate.yandex.net/api/v1.5/tr/translate?"
                val keyURL = "key=" + URLEncoder.encode(PreferenceList.SubscriptionKey, "UTF-8")
                val textURL = "&text=" + URLEncoder.encode(textToTranslate, "UTF-8")
                val languageURL = "&lang=" + URLEncoder.encode(fromLang + "-" + toLang, "UTF-8")
                val fullURL = baseURL + keyURL + textURL + languageURL
                utils.debugLog("Yandex Request URL: " + fullURL)
                val request = Request.Builder().url(fullURL).get().build()
                utils.debugLog("Enqueuing Yandex request for: '" + textToTranslate + "'")
                httpClient.newCall(request).enqueue(callback)
            } else if ("m" == provider) {
                val httpClient = GetTranslateToken.Companion.httpsClient
                if (httpClient == null) {
                    handleTranslationFailure(
                        "OkHttpClient is null for Microsoft",
                        IllegalStateException("httpsClient is null")
                    )
                    return
                }
                utils.debugLog(GetTranslateToken.Companion.TAG + ": Using Microsoft Provider")
                val subscriptionKey = PreferenceList.SubscriptionKey
                if (subscriptionKey == null || isKeyInvalid(subscriptionKey)) {
                    handleTranslationFailure(
                        "Microsoft API key is invalid.",
                        IOException("Invalid Microsoft Key")
                    )
                    return
                }
                val baseURL = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0"
                var languageURL = "&to=" + URLEncoder.encode(toLang, "UTF-8")
                if ("auto" != fromLang) {
                    languageURL += "&from=" + URLEncoder.encode(fromLang, "UTF-8")
                }
                val fullURL = baseURL + languageURL
                val jsonArray = JSONArray()
                val jsonObject = JSONObject()
                jsonObject.put("Text", textToTranslate)
                jsonArray.put(jsonObject)
                val requestBodyJson = jsonArray.toString()
                val body: RequestBody = requestBodyJson.toRequestBody(GetTranslateToken.Companion.JSON_MEDIA_TYPE)

                val requestBuilder = Request.Builder().url(fullURL).post(body)
                    .addHeader("Ocp-Apim-Subscription-Key", subscriptionKey)
                    .addHeader("Content-Type", "application/json; charset=UTF-8")

                // FIX: Ensure the region value is not null before adding the header
                val region = PreferenceList.SubscriptionRegion
                if (!region.isNullOrBlank()) {
                    val trimmedRegion = region.trim()
                    if (trimmedRegion.isNotEmpty()) {
                        utils.debugLog("Adding Microsoft API Region Header: $trimmedRegion")
                        requestBuilder.addHeader("Ocp-Apim-Subscription-Region", trimmedRegion)
                    } else {
                        utils.debugLog("Microsoft API Region is empty after trimming.")
                    }
                } else {
                    utils.debugLog("Microsoft API Region not specified.")
                }

                val request = requestBuilder.build()
                utils.debugLog("Microsoft Request URL: $fullURL")
                utils.debugLog("Microsoft Request Body: $requestBodyJson")
                utils.debugLog("Enqueuing Microsoft request for: '$textToTranslate'")
                httpClient.newCall(request).enqueue(callback)
            } else {
                handleTranslationFailure(
                    "Unknown TranslatorProvider selected: " + provider,
                    IOException("Unknown Provider: " + provider)
                )
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
        private val available = Semaphore(1, true)
        private var httpsClient: OkHttpClient? = null
        private val JSON_MEDIA_TYPE: MediaType? = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_KEY_PLACEHOLDER_PREFIX_PT = "Insira a sua chave"
        private const val DEFAULT_KEY_PLACEHOLDER_PREFIX_EN = "Enter"
        private const val TAG = "AllTrans:GetTranslateToken"
        private val googleQueryExecutor: ExecutorService = Executors.newSingleThreadExecutor()

        private fun createHttpsClientCache(): Cache? {
            val context = alltrans.Companion.context
            if (context == null) {
                Log.e(GetTranslateToken.Companion.TAG, "Context is null in createHttpsClientCache...")
                return null
            }
            val cacheSize = 1024 * 1024
            val cacheDirectory: File = File(context.getCacheDir(), "AllTransHTTPsCache")
            try {
                return Cache(cacheDirectory, cacheSize.toLong())
            } catch (e: Exception) {
                Log.e(GetTranslateToken.Companion.TAG, "Failed to create cache...", e)
                return null
            }
        }
    }
}