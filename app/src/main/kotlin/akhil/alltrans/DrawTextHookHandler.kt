package akhil.alltrans

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.text.MeasuredText
import android.os.Build
import android.text.SpannableString
import android.text.SpannedString
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Method
import java.nio.CharBuffer

class DrawTextHookHandler : XC_MethodReplacement(), OriginalCallable {

    companion object {
        private const val TAG = "DrawTextHookHandler"
        private const val ALTERED_CHAR_SEQUENCE_CLASS = "android.text.AlteredCharSequence"
        private const val TEXT_SIZE_REDUCTION_STEP = 1f

        /**
         * Obtém ID da thread de forma compatível (evita deprecation warning)
         */
        private fun getThreadId(): String {
            return try {
                // Para Android/Java mais antigo
                Thread.currentThread().id.toString()
            } catch (e: NoSuchMethodError) {
                // Fallback
                Thread.currentThread().hashCode().toString()
            }
        }

        private fun adjustTextSizeForWidth(
            paint: Paint,
            originalSize: Float,
            maxWidth: Float,
            text: String
        ) {
            if (text.isEmpty()) return

            var currentTextSize = originalSize
            val bounds = Rect()

            paint.getTextBounds(text, 0, text.length, bounds)

            while (bounds.width() > maxWidth && currentTextSize > 1f) {
                currentTextSize -= TEXT_SIZE_REDUCTION_STEP
                paint.textSize = currentTextSize
                paint.getTextBounds(text, 0, text.length, bounds)
            }
        }

        private fun createAdjustedPaint(originalPaint: Paint, canvas: Canvas, text: String): Paint {
            return Paint(originalPaint).apply {
                adjustTextSizeForWidth(
                    this,
                    originalPaint.textSize,
                    canvas.width.toFloat(),
                    text
                )
            }
        }
    }

    override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
        val methodHookParam = userData as? XC_MethodHook.MethodHookParam ?: return
        val method = methodHookParam.method as? Method ?: return

        try {
            method.isAccessible = true
            val args = methodHookParam.args.copyOf()

            updateTextArgument(args, translatedString)
            updatePaintArgument(args, methodHookParam, translatedString)
            updateIndexArguments(args, translatedString)

            invokeOriginalMethodSafely(method, methodHookParam, args)

        } catch (e: Exception) {
            Utils.debugLog("$TAG: Erro ao chamar método original: ${e.message}")
            logException(e)
        }
    }

    private fun updateTextArgument(args: Array<Any?>, translatedString: CharSequence?) {
        if (args.isEmpty() || args[0] == null) return

        val translatedText = translatedString?.toString() ?: ""

        args[0] = when (args[0]!!::class.java) {
            CharArray::class.java -> translatedText.toCharArray()
            CharBuffer::class.java -> createCharBuffer(translatedString)
            SpannableString::class.java -> SpannableString(translatedText)
            SpannedString::class.java -> SpannedString(translatedText)
            String::class.java -> translatedText
            StringBuffer::class.java -> StringBuffer(translatedText)
            StringBuilder::class.java -> StringBuilder(translatedText)
            else -> handleSpecialTextTypes(args[0]!!, translatedString) ?: translatedText
        }
    }

    private fun handleSpecialTextTypes(originalArg: Any, translatedString: CharSequence?): Any? {
        return when {
            isAlteredCharSequence(originalArg) -> createAlteredCharSequence(translatedString)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && originalArg is MeasuredText ->
                createMeasuredText(translatedString)
            else -> null
        }
    }

    private fun isAlteredCharSequence(obj: Any): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                obj.javaClass.name == ALTERED_CHAR_SEQUENCE_CLASS
    }

    private fun createAlteredCharSequence(translatedString: CharSequence?): Any? {
        return try {
            val alteredClass = Class.forName(ALTERED_CHAR_SEQUENCE_CLASS)
            val makeMethod = alteredClass.getMethod(
                "make",
                CharSequence::class.java,
                CharArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            makeMethod.invoke(null, translatedString, null, 0, 0)
        } catch (e: Exception) {
            Utils.debugLog("$TAG: Erro ao criar AlteredCharSequence: ${e.message}")
            translatedString?.toString() ?: ""
        }
    }

    private fun createCharBuffer(translatedString: CharSequence?): CharBuffer {
        val length = translatedString?.length ?: 0
        return CharBuffer.allocate(length + 1).apply {
            translatedString?.let { append(it) }
        }
    }

    private fun createMeasuredText(translatedString: CharSequence?): MeasuredText {
        val text = translatedString?.toString() ?: ""
        return MeasuredText.Builder(text.toCharArray()).build()
    }

    private fun updatePaintArgument(
        args: Array<Any?>,
        methodHookParam: XC_MethodHook.MethodHookParam,
        translatedString: CharSequence?
    ) {
        if (args.isEmpty()) return

        val paintIndex = args.size - 1
        val originalPaint = args[paintIndex] as? Paint ?: return
        val canvas = methodHookParam.thisObject as? Canvas ?: return
        val text = args[0]?.toString() ?: translatedString?.toString() ?: ""

        args[paintIndex] = createAdjustedPaint(originalPaint, canvas, text)
    }

    private fun updateIndexArguments(args: Array<Any?>, translatedString: CharSequence?) {
        val textLength = translatedString?.length ?: 0

        if (args.size >= 3 && isIntegerType(args[1]) && isIntegerType(args[2])) {
            args[1] = 0
            args[2] = textLength
        }

        if (args.size >= 5 && isIntegerType(args[3]) && isIntegerType(args[4])) {
            args[3] = 0
            args[4] = textLength
        }
    }

    private fun isIntegerType(obj: Any?): Boolean {
        return obj is Int
    }

    /**
     * Invoca o método original de forma segura, gerenciando hooks temporariamente
     * CORRIGIDO: Não usa unhookMethod (depreciado), usa callback para desabilitar recursão
     */
    private fun invokeOriginalMethodSafely(
        method: Method,
        methodHookParam: XC_MethodHook.MethodHookParam,
        args: Array<Any?>
    ) {
        val currentCacheAccess = Alltrans.cacheAccess

        currentCacheAccess.acquireUninterruptibly()
        try {
            logMethodInvocation(methodHookParam, args)

            // ✅ CORREÇÃO: Usar invokeOriginalMethod diretamente
            // O Xposed já gerencia a não-recursão internamente quando usamos XC_MethodReplacement
            XposedBridge.invokeOriginalMethod(method, methodHookParam.thisObject, args)

        } catch (e: Throwable) {
            Utils.debugLog("$TAG: Erro ao invocar método original: ${e.message}")
            logMethodError(e, args)
        } finally {
            if (currentCacheAccess.availablePermits() == 0) {
                currentCacheAccess.release()
            }
        }
    }

    override fun replaceHookedMethod(methodHookParam: XC_MethodHook.MethodHookParam): Any? {
        return try {
            processDrawTextHook(methodHookParam)
        } catch (e: Throwable) {
            Utils.debugLog("$TAG: Exceção em replaceHook: ${Log.getStackTraceString(e)}")
            null
        }
    }

    private fun processDrawTextHook(methodHookParam: XC_MethodHook.MethodHookParam): Any? {
        // Verificar se o AllTrans está habilitado para este app
        val canvas = methodHookParam.thisObject as? Canvas
        val context = try {
            canvas?.let {
                val viewField = it.javaClass.getDeclaredField("mView")
                viewField.isAccessible = true
                val view = viewField.get(it) as? android.view.View
                view?.context
            }
        } catch (e: Exception) {
            Alltrans.context
        }

        val packageName = context?.packageName
        if (!PreferenceManager.isEnabledForPackage(context, packageName)) {
            Utils.debugLog("$TAG: AllTrans desabilitado para $packageName")
            callOriginalMethod(null, methodHookParam)
            return null
        }

        if (methodHookParam.args.isEmpty() || methodHookParam.args[0] == null) {
            callOriginalMethod(null, methodHookParam)
            return null
        }

        val extractedText = extractTextFromArgs(methodHookParam.args) ?: run {
            callOriginalMethod(null, methodHookParam)
            return null
        }

        if (!SetTextHookHandler.isNotWhiteSpace(extractedText)) {
            callOriginalMethod(extractedText, methodHookParam)
            return null
        }

        // ✅ Verificar se deve pular tradução
        if (SetTextHookHandler.shouldSkipTranslation(extractedText)) {
            Utils.debugLog("$TAG: Texto ignorado por regras de skip: [$extractedText]")
            callOriginalMethod(extractedText, methodHookParam)
            return null
        }

        Utils.debugLog("$TAG: Texto encontrado para drawText: $extractedText")
        return handleTranslation(extractedText, methodHookParam)
    }

    private fun extractTextFromArgs(args: Array<Any?>): String? {
        val firstArg = args[0] ?: return null

        var text = when (firstArg) {
            is CharArray -> String(firstArg)
            else -> firstArg.toString()
        }

        // Aplicar substring se necessário
        if (args.size >= 3 && isIntegerType(args[1]) && isIntegerType(args[2])) {
            try {
                val indexOrStart = args[1] as Int
                val countOrEnd = args[2] as Int

                text = if (firstArg is CharArray) {
                    if (indexOrStart >= 0 && countOrEnd > 0 && indexOrStart + countOrEnd <= text.length) {
                        text.substring(indexOrStart, indexOrStart + countOrEnd)
                    } else {
                        Utils.debugLog("$TAG: Índices inválidos para CharArray")
                        return null
                    }
                } else {
                    if (indexOrStart >= 0 && countOrEnd >= indexOrStart && countOrEnd <= text.length) {
                        text.substring(indexOrStart, countOrEnd)
                    } else {
                        Utils.debugLog("$TAG: Índices inválidos para String/CharSequence")
                        return null
                    }
                }
            } catch (e: Exception) {
                Utils.debugLog("$TAG: Erro ao aplicar substring: ${e.message}")
                return null
            }
        }
        return text
    }

    private fun handleTranslation(text: String, methodHookParam: XC_MethodHook.MethodHookParam): Any? {
        val threadId = getThreadId() // ✅ Usando função compatível
        Utils.debugLog("$TAG: Thread $threadId - Processando: $text")

        val compositeKey = text.hashCode()

        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) {
                Utils.debugLog("$TAG: Tradução já pendente para [$text], key ($compositeKey)")
                callOriginalMethod(text, methodHookParam)
                return null
            }
            Alltrans.pendingTextViewTranslations.add(compositeKey)
            Utils.debugLog("$TAG: Adicionado key ($compositeKey) para DrawText")
        }

        if (tryGetFromCache(text, methodHookParam, compositeKey)) {
            return null
        }

        Utils.debugLog("$TAG: Não está no cache, solicitando tradução para [$text]")
        callOriginalMethod(text, methodHookParam)

        // Verificar provider de tradução e usar batch se aplicável
        if (PreferenceList.TranslatorProvider == "m" && PreferenceList.CurrentAppMicrosoftBatchEnabled) {
            // Usar batch manager do Microsoft
            Alltrans.batchManager.addString(
                text = text,
                userData = methodHookParam,
                originalCallable = this@DrawTextHookHandler,
                canCallOriginal = true,
                compositeKey = compositeKey
            )
        } else {
            // Tradução individual
            val getTranslate = GetTranslate().apply {
                stringToBeTrans = text
                originalCallable = this@DrawTextHookHandler
                userData = methodHookParam
                canCallOriginal = true
                pendingCompositeKey = compositeKey
            }

            GetTranslateToken().apply {
                this.getTranslate = getTranslate
            }.doAll()
        }

        return null
    }

    private fun tryGetFromCache(
        text: String,
        methodHookParam: XC_MethodHook.MethodHookParam,
        compositeKey: Int
    ): Boolean {
        if (!PreferenceList.Caching) {
            return false
        }

        var cachedTranslation: String? = null
        var cacheHit = false

        Alltrans.cacheAccess.acquireUninterruptibly()
        try {
            cachedTranslation = Alltrans.cache?.get(text)

            if (cachedTranslation != null && cachedTranslation != text) {
                val threadId = getThreadId() // ✅ Usando função compatível
                Utils.debugLog("$TAG: Thread $threadId - Cache hit: [$text] -> [$cachedTranslation]")

                synchronized(Alltrans.pendingTextViewTranslations) {
                    Alltrans.pendingTextViewTranslations.remove(compositeKey)
                    Utils.debugLog("$TAG: Removido key ($compositeKey) após cache hit")
                }

                callOriginalMethod(cachedTranslation, methodHookParam)
                cacheHit = true
            } else if (cachedTranslation == SetTextHookHandler.NO_TRANSLATION_MARKER) {
                // ✅ Suporte ao marcador de "não traduzir"
                val threadId = getThreadId()
                Utils.debugLog("$TAG: Thread $threadId - Cache indica não traduzir: [$text]")

                synchronized(Alltrans.pendingTextViewTranslations) {
                    Alltrans.pendingTextViewTranslations.remove(compositeKey)
                }

                callOriginalMethod(text, methodHookParam) // Mantém original
                cacheHit = true
            } else {
                val threadId = getThreadId() // ✅ Usando função compatível
                Utils.debugLog("$TAG: Thread $threadId - Cache miss: [$text]")
            }
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }
        return cacheHit
    }

    private fun logMethodInvocation(methodHookParam: XC_MethodHook.MethodHookParam, args: Array<Any?>) {
        val threadId = getThreadId() // ✅ Usando função compatível
        Utils.debugLog(
            "$TAG: Thread $threadId - Invocando ${methodHookParam.method.name} com texto: ${args.getOrNull(0)}"
        )
    }

    private fun logMethodError(error: Throwable, args: Array<Any?>) {
        val stackTrace = StringWriter().apply {
            error.printStackTrace(PrintWriter(this))
        }.toString()

        Utils.debugLog("$TAG: Erro ao invocar método original: $stackTrace")

        val argsInfo = args.joinToString(", ") { arg ->
            "Classe: ${arg?.javaClass?.canonicalName ?: "null"}, Valor: $arg"
        }
        Utils.debugLog("$TAG: Parâmetros com erro: $argsInfo")
    }

    private fun logException(exception: Exception) {
        Utils.debugLog("$TAG: Exceção geral: ${Log.getStackTraceString(exception)}")
    }
}