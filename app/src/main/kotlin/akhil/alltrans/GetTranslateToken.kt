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
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class GetTranslateToken {
    var getTranslate: GetTranslate? = null

    fun doAll() {
        if (httpsClient == null && PreferenceList.TranslatorProvider != "g") {
            synchronized(GetTranslateToken::class.java) {
                if (httpsClient == null) {
                    httpsClient = createOptimizedHttpClient()
                    Log.i(
                        TAG,
                        "Optimized OkHttpClient initialized with connection pooling and HTTP/2 support."
                    )
                }
            }
        }

        // Check for duplicate requests
        val callback = getTranslate ?: run {
            Log.e(TAG, "GetTranslate is null. Aborting.")
            return
        }

        val text = callback.stringToBeTrans
        val isBatchMode = callback.stringsToBeTrans?.isNotEmpty() == true

        if (!isBatchMode && !text.isNullOrEmpty()) {
            val deduplicationKey = createDeduplicationKey(text)
            val existingFuture = activeRequests[deduplicationKey]
            if (existingFuture != null && !existingFuture.isDone) {
                Utils.debugLog("$TAG: Deduplicating request for: [$text]")
                existingFuture.thenAccept { result ->
                    handleDuplicateResult(callback, result)
                }
                return
            }
        }

        // Submit task to appropriate executor
        val task = TranslationTask(callback)
        val future = when (PreferenceList.TranslatorProvider) {
            "g" -> CompletableFuture.supplyAsync({ task.call() }, googleQueryExecutor)
            else -> CompletableFuture.supplyAsync({ task.call() }, networkExecutor)
        }

        // Store future for deduplication if not batch mode
        if (!isBatchMode && !text.isNullOrEmpty()) {
            val deduplicationKey = createDeduplicationKey(text)
            activeRequests[deduplicationKey] = future
            future.whenComplete { _, _ ->
                activeRequests.remove(deduplicationKey)
            }
        }
    }

    private fun createOptimizedHttpClient(): OkHttpClient {
        val cache = createHttpsClientCache()

        // Optimized dispatcher
        val dispatcher = Dispatcher().apply {
            maxRequests = 32 // Increased from default 64
            maxRequestsPerHost = 12 // Increased from default 5
        }

        // Optimized connection pool
        val connectionPool = ConnectionPool(
            maxIdleConnections = 10, // Increased from default 5
            keepAliveDuration = 60L, // Increased from default 5 minutes
            TimeUnit.SECONDS
        )

        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .cache(cache)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .connectTimeout(8, TimeUnit.SECONDS) // Slightly reduced
            .readTimeout(12, TimeUnit.SECONDS) // Slightly reduced
            .writeTimeout(12, TimeUnit.SECONDS) // Slightly reduced
            .callTimeout(30, TimeUnit.SECONDS) // Overall timeout
            // Connection keep-alive optimization
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Connection", "keep-alive")
                    .header("Keep-Alive", "timeout=60, max=100")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private fun createDeduplicationKey(text: String): String {
        return "${PreferenceList.TranslateFromLanguage}-${PreferenceList.TranslateToLanguage}-${text.hashCode()}"
    }

    private fun handleDuplicateResult(callback: GetTranslate, result: String?) {
        val mockRequest = Request.Builder().url("https://mock.deduplicated.call").build()
        val mockCall = (httpsClient ?: createOptimizedHttpClient()).newCall(mockRequest)
        val response = Response.Builder()
            .request(mockRequest)
            .protocol(Protocol.HTTP_2)
            .code(200)
            .message("OK (Deduplicated)")
            .body((result ?: "").toResponseBody(null))
            .build()

        Handler(Looper.getMainLooper()).post {
            callback.onResponse(mockCall, response)
        }
    }

    private class TranslationTask(private val callback: GetTranslate?) : java.util.concurrent.Callable<String?> {
        override fun call(): String? {
            val getTranslateToken = GetTranslateToken()
            getTranslateToken.getTranslate = callback
            getTranslateToken.doInBackground()
            return null // Return handled via callback
        }
    }

    private fun isKeyInvalid(key: String?): Boolean {
        return key.isNullOrEmpty() || key.startsWith(DEFAULT_KEY_PLACEHOLDER_PREFIX_PT) || key.startsWith(
            DEFAULT_KEY_PLACEHOLDER_PREFIX_EN
        )
    }

    private fun createMockCall(): Call {
        val mockRequest = Request.Builder().url("https://mock.error.call").build()
        return (httpsClient ?: createOptimizedHttpClient()).newCall(mockRequest)
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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

        val isBatchMode = callback.stringsToBeTrans?.isNotEmpty() == true

        if (isBatchMode && PreferenceList.TranslatorProvider != "m") {
            Log.w(TAG, "Batch translation requested but provider is ${PreferenceList.TranslatorProvider}, not Microsoft. Converting to single item.")

            if (callback.stringsToBeTrans?.isNotEmpty() == true && callback.callbackDataList?.isNotEmpty() == true) {
                callback.stringToBeTrans = callback.stringsToBeTrans!![0]
                val callbackInfo = callback.callbackDataList!![0]
                callback.userData = callbackInfo.userData
                callback.originalCallable = callbackInfo.originalCallable
                callback.canCallOriginal = callbackInfo.canCallOriginal
            }

            callback.stringsToBeTrans = null
            callback.callbackDataList = null
        }

        val isActuallyBatchMode = callback.stringsToBeTrans?.isNotEmpty() == true && PreferenceList.TranslatorProvider == "m"

        val textToTranslate = if (!isActuallyBatchMode) {
            callback.stringToBeTrans ?: run {
                Log.e(TAG, "String to be translated is null. Aborting.")
                return
            }
        } else {
            "BATCH_MODE"
        }

        if (Alltrans.context == null) {
            Log.e(TAG, "Static context is null. Aborting.")
            handleTranslationFailure("Static context is null", null)
            return
        }

        try {
            val provider = PreferenceList.TranslatorProvider
            val fromLang = PreferenceList.TranslateFromLanguage
            val toLang = PreferenceList.TranslateToLanguage

            if (fromLang != "auto" && fromLang == toLang) {
                Log.i(TAG, "Skipping translation: Source and Target languages are identical ($fromLang).")

                val mockRequest = Request.Builder().url("https://mock.identical.language.skip").build()
                val mockCall = (httpsClient ?: createOptimizedHttpClient()).newCall(mockRequest)
                val responseBody = (textToTranslate ?: "").toResponseBody("text/plain".toMediaTypeOrNull())
                val mockResponse = Response.Builder()
                    .request(mockRequest)
                    .protocol(Protocol.HTTP_2)
                    .code(200)
                    .message("OK (Skipped - Source and Target languages are identical)")
                    .body(responseBody)
                    .build()

                Handler(Looper.getMainLooper()).post {
                    callback.onResponse(mockCall, mockResponse)
                }
                return
            }

            if (isActuallyBatchMode) {
                Utils.debugLog("$TAG: doInBackground - Provider: $provider, Batch mode with ${callback.stringsToBeTrans?.size} texts")
            } else {
                Utils.debugLog("$TAG: doInBackground - Provider: $provider, Single text mode: [$textToTranslate]")
            }

            when (provider) {
                "g" -> {
                    Utils.debugLog("$TAG: Processing Google Provider query for: [$textToTranslate]")
                    var result: String? = textToTranslate
                    try {
                        result = queryGoogleProvider(textToTranslate, fromLang, toLang)
                    } catch (t: Throwable) {
                        handleTranslationFailure("Error executing Google Provider query", t)
                    }

                    var finalResult = result
                    var responseCode = 200
                    var responseMessage = "OK (from Provider Query)"

                    if (finalResult == GtransProvider.MLKIT_MODEL_UNAVAILABLE_ERROR) {
                        Utils.debugLog("$TAG: ML Kit Model Unavailable Error received from GtransProvider for text: [$textToTranslate]")
                        val errorDisplayMessage = Alltrans.context?.getString(R.string.mlkit_model_unavailable_user_message) ?: "ML Kit translation model not downloaded. Please download it via Model Management."
                        finalResult = errorDisplayMessage
                        responseMessage = "ML Kit Model Unavailable"
                    }

                    val mockRequest = Request.Builder().url("https://mock.google.translate.query").build()
                    val mockCall = (httpsClient ?: createOptimizedHttpClient()).newCall(mockRequest)
                    val response = Response.Builder()
                        .request(mockRequest)
                        .code(responseCode).message(responseMessage)
                        .protocol(Protocol.HTTP_2)
                        .body((finalResult ?: textToTranslate ?: "").toResponseBody(null))
                        .build()

                    Handler(Looper.getMainLooper()).post {
                        getTranslate?.let { currentCallback ->
                            try {
                                currentCallback.onResponse(mockCall, response)
                            } catch (t: Throwable) {
                                Log.e(TAG, "Error executing getTranslate.onResponse in handler for text: [$textToTranslate]", t)
                                handleTranslationFailure("Error in onResponse callback handler for text: [$textToTranslate]", t)
                            }
                        } ?: Log.w(TAG, "getTranslate became null before executing handler for text: [$textToTranslate].")
                    }
                }
                "y" -> {
                    val httpClient = httpsClient ?: run {
                        handleTranslationFailure("OkHttpClient is null for Yandex", null)
                        return
                    }

                    Utils.debugLog("$TAG: Using Yandex Provider")

                    val key = PreferenceList.SubscriptionKey ?: ""
                    if (isKeyInvalid(key)) {
                        handleTranslationFailure("Yandex API key is invalid.", null)
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
                        handleTranslationFailure("OkHttpClient is null for Microsoft", null)
                        return
                    }

                    Utils.debugLog("$TAG: Using Microsoft Provider")

                    val key = PreferenceList.SubscriptionKey ?: ""
                    if (isKeyInvalid(key)) {
                        handleTranslationFailure("Microsoft API key is invalid.", null)
                        return
                    }

                    val baseURL = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0"
                    val fromLangSafe = fromLang ?: "auto"
                    val toLangSafe = toLang ?: "en"
                    val languageURL = "&to=${URLEncoder.encode(toLangSafe, "UTF-8")}" +
                            if (fromLangSafe != "auto") "&from=${URLEncoder.encode(fromLangSafe, "UTF-8")}" else ""

                    val fullURL = baseURL + languageURL
                    val requestBodyJson: String

                    if (isActuallyBatchMode && PreferenceList.CurrentAppMicrosoftBatchEnabled) {
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
                        Utils.debugLog("$TAG: Preparing single Microsoft request for: '$textToTranslate'")
                        val jsonArray = JSONArray()
                        val jsonObject = JSONObject()
                        jsonObject.put("Text", textToTranslate)
                        jsonArray.put(jsonObject)
                        requestBodyJson = jsonArray.toString()
                    }

                    val body = requestBodyJson.toRequestBody(JSON_MEDIA_TYPE)
                    val requestBuilder = Request.Builder().url(fullURL).post(body)
                        .addHeader("Ocp-Apim-Subscription-Key", key)
                        .addHeader("Content-Type", "application/json; charset=UTF-8")

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

                    val logText = if (isActuallyBatchMode && PreferenceList.CurrentAppMicrosoftBatchEnabled) {
                        "batch of ${callback.stringsToBeTrans?.size ?: 0} strings"
                    } else {
                        "'$textToTranslate'"
                    }
                    Utils.debugLog("Enqueuing Microsoft request for: $logText")
                    httpClient.newCall(request).enqueue(callback)
                }
                else -> {
                    handleTranslationFailure("Unknown TranslatorProvider selected: $provider", null)
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
        private val CPU_COUNT = Runtime.getRuntime().availableProcessors()
        private val CORE_POOL_SIZE = maxOf(2, minOf(CPU_COUNT - 1, 4))
        private val MAX_POOL_SIZE = CPU_COUNT * 2 + 1
        private val KEEP_ALIVE_TIME = 60L

        // Optimized thread pools
        private val networkExecutor: ExecutorService = ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE_TIME, TimeUnit.SECONDS,
            LinkedBlockingQueue(200),
            OptimizedThreadFactory("AllTrans-Network")
        ).apply {
            allowCoreThreadTimeOut(true)
        }

        private val googleQueryExecutor: ExecutorService = ThreadPoolExecutor(
            2, // Smaller pool for Google queries (usually faster)
            4,
            KEEP_ALIVE_TIME, TimeUnit.SECONDS,
            LinkedBlockingQueue(100),
            OptimizedThreadFactory("AllTrans-Google")
        ).apply {
            allowCoreThreadTimeOut(true)
        }

        // Optimized I/O executor for file operations
        private val ioExecutor: ExecutorService = ThreadPoolExecutor(
            1,
            3,
            KEEP_ALIVE_TIME, TimeUnit.SECONDS,
            LinkedBlockingQueue(50),
            OptimizedThreadFactory("AllTrans-IO")
        ).apply {
            allowCoreThreadTimeOut(true)
        }

        // Request deduplication
        private val activeRequests = ConcurrentHashMap<String, CompletableFuture<String?>>()

        private var httpsClient: OkHttpClient? = null
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_KEY_PLACEHOLDER_PREFIX_PT = "Insira a sua chave"
        private const val DEFAULT_KEY_PLACEHOLDER_PREFIX_EN = "Enter"
        private const val TAG = "AllTrans:GetTranslateToken"

        private fun createHttpsClientCache(): Cache? {
            val context = Alltrans.context ?: run {
                Log.e(TAG, "Context is null in createHttpsClientCache...")
                return null
            }

            val cacheSize = 2 * 1024 * 1024 // Increased to 2MB
            val cacheDirectory = File(context.cacheDir, "AllTransHTTPsCache")

            return try {
                Cache(cacheDirectory, cacheSize.toLong())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create cache...", e)
                null
            }
        }

        // Submit I/O tasks to dedicated executor
        fun submitIoTask(task: Runnable) {
            ioExecutor.submit(task)
        }

        // Submit I/O tasks that return values
        fun <T> submitIoTask(task: java.util.concurrent.Callable<T>): CompletableFuture<T> {
            return CompletableFuture.supplyAsync({
                try {
                    task.call()
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }, ioExecutor)
        }

        // Get executor stats for monitoring
        fun getExecutorStats(): String {
            val networkStats = if (networkExecutor is ThreadPoolExecutor) {
                "Network: ${networkExecutor.activeCount}/${networkExecutor.poolSize}, Queue: ${networkExecutor.queue.size}"
            } else "Network: N/A"

            val googleStats = if (googleQueryExecutor is ThreadPoolExecutor) {
                "Google: ${googleQueryExecutor.activeCount}/${googleQueryExecutor.poolSize}, Queue: ${googleQueryExecutor.queue.size}"
            } else "Google: N/A"

            val ioStats = if (ioExecutor is ThreadPoolExecutor) {
                "I/O: ${ioExecutor.activeCount}/${ioExecutor.poolSize}, Queue: ${ioExecutor.queue.size}"
            } else "I/O: N/A"

            return "$networkStats | $googleStats | $ioStats"
        }

        // Cleanup method for shutdown
        fun shutdown() {
            Utils.debugLog("$TAG: Shutting down thread pools...")

            listOf(networkExecutor, googleQueryExecutor, ioExecutor).forEach { executor ->
                executor.shutdown()
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow()
                    }
                } catch (e: InterruptedException) {
                    executor.shutdownNow()
                    Thread.currentThread().interrupt()
                }
            }

            httpsClient?.dispatcher?.executorService?.shutdown()
            activeRequests.clear()

            Utils.debugLog("$TAG: Thread pools shutdown complete.")
        }
    }

    // Optimized thread factory
    private class OptimizedThreadFactory(private val namePrefix: String) : ThreadFactory {
        private val threadNumber = AtomicInteger(1)

        override fun newThread(r: Runnable): Thread {
            return Thread(r, "$namePrefix-${threadNumber.getAndIncrement()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1 // Slightly lower priority
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, ex ->
                    Log.e(TAG, "Uncaught exception in thread ${thread.name}", ex)
                }
            }
        }
    }
}