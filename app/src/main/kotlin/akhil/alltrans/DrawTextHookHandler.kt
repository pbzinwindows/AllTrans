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

/**
 * Handler responsável por interceptar e substituir chamadas de drawText do Canvas,
 * permitindo tradução de texto em tempo real.
 */
class DrawTextHookHandler : XC_MethodReplacement(), OriginalCallable {

    companion object {
        private const val TAG = "DrawTextHookHandler"
        private const val ALTERED_CHAR_SEQUENCE_CLASS = "android.text.AlteredCharSequence"
        private const val TEXT_SIZE_REDUCTION_STEP = 1f

        /**
         * Ajusta o tamanho do texto para caber numa largura específica
         */
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

        /**
         * Cria uma cópia do Paint com ajustes para o novo texto
         */
        private fun createAdjustedPaint(originalPaint: Paint, canvas: Canvas, text: String): Paint {
            return Paint(originalPaint).apply {
                textSize = originalPaint.textSize
                color = originalPaint.color
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
        val methodHookParam = userData as? MethodHookParam ?: return
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

    /**
     * Atualiza o argumento de texto baseado no tipo
     */
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

    /**
     * Manipula tipos especiais de texto (AlteredCharSequence, MeasuredText)
     */
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
                Int::class.java,
                Int::class.java
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

    /**
     * Atualiza o argumento Paint
     */
    private fun updatePaintArgument(
        args: Array<Any?>,
        methodHookParam: MethodHookParam,
        translatedString: CharSequence?
    ) {
        if (args.isEmpty()) return

        val paintIndex = args.size - 1
        val originalPaint = args[paintIndex] as? Paint ?: return
        val canvas = methodHookParam.thisObject as? Canvas ?: return
        val text = args[0]?.toString() ?: ""

        args[paintIndex] = createAdjustedPaint(originalPaint, canvas, text)
    }

    /**
     * Atualiza argumentos de índice para texto completo
     */
    private fun updateIndexArguments(args: Array<Any?>, translatedString: CharSequence?) {
        val textLength = translatedString?.length ?: 0

        // Para argumentos de índice simples (start, end)
        if (args.size >= 3 && isIntegerType(args[1]) && isIntegerType(args[2])) {
            args[1] = 0
            args[2] = textLength
        }

        // Para argumentos de índice estendidos
        if (args.size >= 5 && isIntegerType(args[3]) && isIntegerType(args[4])) {
            args[3] = 0
            args[4] = textLength
        }
    }

    private fun isIntegerType(obj: Any?): Boolean {
        return obj?.javaClass in setOf<Class<*>?>(Int::class.javaPrimitiveType, Int::class.java)
    }

    /**
     * Invoca o método original de forma segura com controle de hook
     */
    private fun invokeOriginalMethodSafely(
        method: Method,
        methodHookParam: MethodHookParam,
        args: Array<Any?>
    ) {
        val hookAccess = Alltrans.hookAccess ?: return

        hookAccess.acquireUninterruptibly()
        try {
            val currentHook = Alltrans.drawTextHook
            val restoreHook = createRestoreHook(method, currentHook)

            XposedBridge.hookMethod(method, restoreHook)

            logMethodInvocation(methodHookParam, args)
            XposedBridge.invokeOriginalMethod(method, methodHookParam.thisObject, args)

        } catch (e: Throwable) {
            Utils.debugLog("$TAG: Erro ao invocar método: ${e.message}")
            logMethodError(e, args)
        } finally {
            hookAccess.release()
        }
    }

    private fun createRestoreHook(method: Method, originalHook: XC_MethodHook?): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                originalHook?.let { XposedBridge.hookMethod(method, it) }
            }
        }
    }

    override fun replaceHookedMethod(methodHookParam: MethodHookParam): Any? {
        return try {
            processDrawTextHook(methodHookParam)
        } catch (e: Throwable) {
            Utils.debugLog("$TAG: Exceção em replaceHook: ${Log.getStackTraceString(e)}")
            null
        }
    }

    private fun processDrawTextHook(methodHookParam: MethodHookParam): Any? {
        if (methodHookParam.args[0] == null) {
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

        Utils.debugLog("$TAG: Texto encontrado para drawText: $extractedText")

        return handleTranslation(extractedText, methodHookParam)
    }

    private fun extractTextFromArgs(args: Array<Any?>): String? {
        val firstArg = args[0] ?: return null

        var text = when (firstArg::class.java) {
            CharArray::class.java -> String(firstArg as CharArray)
            else -> firstArg.toString()
        }

        // Aplicar substring se necessário
        if (args.size >= 3 && isIntegerType(args[1])) {
            val start = args[1] as Int
            val endOrCount = args[2] as Int

            text = if (firstArg::class.java == CharArray::class.java) {
                text.substring(start, start + endOrCount)
            } else {
                text.substring(start, endOrCount)
            }
        }

        return text
    }

    private fun handleTranslation(text: String, methodHookParam: MethodHookParam): Any? {
        Utils.debugLog("$TAG: Thread ${Thread.currentThread().id} - Texto não-inglês: $text")

        // Verificar cache primeiro
        if (tryGetFromCache(text, methodHookParam)) {
            return null
        }

        // Processar tradução
        val getTranslate = GetTranslate().apply {
            stringToBeTrans = text
            originalCallable = this@DrawTextHookHandler
            userData = methodHookParam
            canCallOriginal = false
        }

        GetTranslateToken().apply {
            this.getTranslate = getTranslate
        }.doAll()

        return null
    }

    private fun tryGetFromCache(text: String, methodHookParam: MethodHookParam): Boolean {
        if (!PreferenceList.Caching || Alltrans.cache == null) {
            callOriginalMethod(text, methodHookParam)
            return false
        }

        val cacheAccess = Alltrans.cacheAccess ?: return false

        cacheAccess.acquireUninterruptibly()
        return try {
            val cachedTranslation = Alltrans.cache?.get(text)
            if (cachedTranslation != null) {
                Utils.debugLog("$TAG: Thread ${Thread.currentThread().id} - Encontrado no cache: $text -> $cachedTranslation")
                callOriginalMethod(cachedTranslation, methodHookParam)
                true
            } else {
                callOriginalMethod(text, methodHookParam)
                false
            }
        } finally {
            cacheAccess.release()
        }
    }

    // Métodos de logging
    private fun logMethodInvocation(methodHookParam: MethodHookParam, args: Array<Any?>) {
        Utils.debugLog(
            "$TAG: Thread ${Thread.currentThread().id} - Invocando ${methodHookParam.method.name} " +
                    "com texto: ${args[0]}"
        )
    }

    private fun logMethodError(error: Throwable, args: Array<Any?>) {
        val stackTrace = StringWriter().apply {
            error.printStackTrace(PrintWriter(this))
        }.toString()

        Utils.debugLog("$TAG: Erro ao invocar método: $stackTrace")

        val argsInfo = args.joinToString(", ") { arg ->
            "Classe: ${arg?.javaClass?.canonicalName}, Valor: $arg"
        }
        Utils.debugLog("$TAG: Parâmetros: $argsInfo")
    }

    private fun logException(exception: Exception) {
        Utils.debugLog("$TAG: ${Log.getStackTraceString(exception)}")
    }
}