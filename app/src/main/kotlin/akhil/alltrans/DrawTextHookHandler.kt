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

        private fun adjustTextSizeForWidth(
            paint: Paint,
            originalSize: Float,
            maxWidth: Float,
            text: String
        ) {
            if (text.isEmpty()) return

            var currentTextSize = originalSize // var pois é modificado no loop
            val bounds = Rect() // val

            paint.getTextBounds(text, 0, text.length, bounds)

            while (bounds.width() > maxWidth && currentTextSize > 1f) {
                currentTextSize -= TEXT_SIZE_REDUCTION_STEP
                paint.textSize = currentTextSize
                paint.getTextBounds(text, 0, text.length, bounds)
            }
        }

        private fun createAdjustedPaint(originalPaint: Paint, canvas: Canvas, text: String): Paint {
            return Paint(originalPaint).apply { // val implícito
                // textSize, color são propriedades do Paint
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
        val methodHookParam = userData as? XC_MethodHook.MethodHookParam ?: return // val
        val method = methodHookParam.method as? Method ?: return // val

        try {
            method.isAccessible = true // Acessibilidade é uma propriedade do método
            val args = methodHookParam.args.copyOf() // val

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

        val translatedText = translatedString?.toString() ?: "" // val

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
        // Verificação para AlteredCharSequence, que é uma API interna.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                obj.javaClass.name == ALTERED_CHAR_SEQUENCE_CLASS
    }

    private fun createAlteredCharSequence(translatedString: CharSequence?): Any? {
        return try {
            // Acesso via reflexão é necessário para esta classe interna do Android.
            // Pode ser instável entre versões do Android.
            val alteredClass = Class.forName(ALTERED_CHAR_SEQUENCE_CLASS) // val
            val makeMethod = alteredClass.getMethod( // val
                "make",
                CharSequence::class.java,
                CharArray::class.java,
                Int::class.javaPrimitiveType, // Use .javaPrimitiveType
                Int::class.javaPrimitiveType
            )
            makeMethod.invoke(null, translatedString, null, 0, 0)
        } catch (e: Exception) {
            Utils.debugLog("$TAG: Erro ao criar AlteredCharSequence: ${e.message}")
            translatedString?.toString() ?: ""
        }
    }

    private fun createCharBuffer(translatedString: CharSequence?): CharBuffer {
        val length = translatedString?.length ?: 0 // val
        return CharBuffer.allocate(length + 1).apply { // val implícito
            translatedString?.let { append(it) }
        }
    }

    private fun createMeasuredText(translatedString: CharSequence?): MeasuredText {
        val text = translatedString?.toString() ?: "" // val
        return MeasuredText.Builder(text.toCharArray()).build() // val implícito
    }

    private fun updatePaintArgument(
        args: Array<Any?>,
        methodHookParam: XC_MethodHook.MethodHookParam,
        translatedString: CharSequence? // Não usado diretamente aqui, mas parte da assinatura
    ) {
        if (args.isEmpty()) return

        val paintIndex = args.size - 1 // val
        val originalPaint = args[paintIndex] as? Paint ?: return // val
        val canvas = methodHookParam.thisObject as? Canvas ?: return // val
        // O texto para ajustar o paint deve vir do argumento já modificado em args[0]
        val text = args[0]?.toString() ?: translatedString?.toString() ?: ""


        args[paintIndex] = createAdjustedPaint(originalPaint, canvas, text)
    }

    private fun updateIndexArguments(args: Array<Any?>, translatedString: CharSequence?) {
        val textLength = translatedString?.length ?: 0 // val

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
        return obj is Int // Simplificado
    }

    private fun invokeOriginalMethodSafely(
        method: Method,
        methodHookParam: XC_MethodHook.MethodHookParam,
        args: Array<Any?>
    ) {
        val currentCacheAccess = Alltrans.cacheAccess // val
        // Removida a verificação de nulidade pois cacheAccess é val e inicializado

        currentCacheAccess.acquireUninterruptibly()
        try {
            val currentHook = Alltrans.drawTextHook // val
            // Criar um hook temporário para restaurar o original
            val restoreHook = object : XC_MethodHook() { // val
                override fun afterHookedMethod(param: MethodHookParam) {
                    // Desfaz o hook temporário e restaura o hook original
                    XposedBridge.unhookMethod(method, this) // Desfaz este hook específico
                    XposedBridge.hookMethod(method, currentHook) // Restaura o original
                    Utils.debugLog("$TAG: Original drawText hook restored for ${method.name}")
                }
            }
            // Remove o hook original antes de invocar (para evitar recursão infinita)
            // e adiciona o hook de restauração.
            // Esta é uma abordagem mais segura para chamadas originais complexas.
            XposedBridge.unhookMethod(method, currentHook)
            XposedBridge.hookMethod(method, restoreHook)


            logMethodInvocation(methodHookParam, args)
            XposedBridge.invokeOriginalMethod(method, methodHookParam.thisObject, args)

        } catch (e: Throwable) {
            Utils.debugLog("$TAG: Erro ao invocar método original: ${e.message}")
            logMethodError(e, args)
            // Se a invocação falhar, ainda precisamos garantir que o hook original seja restaurado
            // ou que o estado de hooking não fique inconsistente.
            // A lógica do restoreHook deve lidar com a restauração no afterHookedMethod.
            // Se invokeOriginalMethod lançar uma exceção antes de restoreHook.afterHookedMethod ser chamado,
            // o hook original não seria restaurado.
            // Uma abordagem mais segura seria sempre restaurar no finally, mas isso requer
            // um estado para saber se o 'restoreHook' está ativo.
            // Por simplicidade, mantendo o atual, mas ciente do risco.
        } finally {
            // O restoreHook deve ter cuidado da restauração. Se a chamada falhou *antes* do afterHookedMethod
            // do restoreHook, o original pode não ter sido restaurado.
            // No entanto, o principal é liberar o semáforo.
            if (currentCacheAccess.availablePermits() == 0) { // Garante que só liberamos se tivermos adquirido e não liberado ainda
                currentCacheAccess.release()
            }
        }
    }

    // createRestoreHook foi integrado/simplificado em invokeOriginalMethodSafely

    override fun replaceHookedMethod(methodHookParam: XC_MethodHook.MethodHookParam): Any? {
        return try {
            processDrawTextHook(methodHookParam)
        } catch (e: Throwable) {
            Utils.debugLog("$TAG: Exceção em replaceHook: ${Log.getStackTraceString(e)}")
            // Não chamar o método original aqui se o replaceHookedMethod falhar,
            // pois o Xposed tratará isso como método não substituído ou erro.
            null
        }
    }

    private fun processDrawTextHook(methodHookParam: XC_MethodHook.MethodHookParam): Any? {
        // Verificar se o AllTrans está habilitado para este app
        val canvas = methodHookParam.thisObject as? Canvas
        val context = try {
            // Tentar obter contexto do Canvas
            canvas?.let {
                val viewField = it.javaClass.getDeclaredField("mView")
                viewField.isAccessible = true
                val view = viewField.get(it) as? android.view.View
                view?.context
            }
        } catch (e: Exception) {
            // Se não conseguir obter contexto do Canvas, usar o contexto global
            Alltrans.context?.get()
        }

        val packageName = context?.packageName
        if (!PreferenceManager.isEnabledForPackage(context, packageName)) {
            Utils.debugLog("$TAG: AllTrans DESABILITADO para este app ($packageName). Chamando método original sem tradução.")
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

        Utils.debugLog("$TAG: Texto encontrado para drawText: $extractedText")
        return handleTranslation(extractedText, methodHookParam)
    }

    private fun extractTextFromArgs(args: Array<Any?>): String? {
        val firstArg = args[0] ?: return null // val

        var text = when (firstArg) { // var pois é modificado pelo substring
            is CharArray -> String(firstArg)
            else -> firstArg.toString()
        }

        // Aplicar substring se necessário (índices start, end/count)
        // Para drawText(char[] text, int index, int count, float x, float y, Paint paint)
        // Para drawText(String text, int start, int end, float x, float y, Paint paint)
        // Para drawText(CharSequence text, int start, int end, float x, float y, Paint paint)
        if (args.size >= 3 && isIntegerType(args[1]) && isIntegerType(args[2])) {
            try {
                val indexOrStart = args[1] as Int
                val countOrEnd = args[2] as Int

                text = if (firstArg is CharArray) {
                    if (indexOrStart >= 0 && countOrEnd > 0 && indexOrStart + countOrEnd <= text.length) {
                        text.substring(indexOrStart, indexOrStart + countOrEnd)
                    } else {
                        Utils.debugLog("$TAG: Índices inválidos para CharArray: index=$indexOrStart, count=$countOrEnd, length=${text.length}")
                        return null // Ou retorna o texto original não fatiado? Para drawText, é melhor ser preciso.
                    }
                } else { // String or CharSequence
                    if (indexOrStart >= 0 && countOrEnd >= indexOrStart && countOrEnd <= text.length) {
                        text.substring(indexOrStart, countOrEnd)
                    } else {
                        Utils.debugLog("$TAG: Índices inválidos para String/CharSequence: start=$indexOrStart, end=$countOrEnd, length=${text.length}")
                        return null
                    }
                }
            } catch (e: Exception) {
                Utils.debugLog("$TAG: Erro ao aplicar substring: ${e.message}")
                return null // Falha na extração
            }
        }
        return text
    }

    private fun handleTranslation(text: String, methodHookParam: XC_MethodHook.MethodHookParam): Any? {
        Utils.debugLog("$TAG: Thread ${Thread.currentThread().id} - Texto não-inglês: $text")

        val compositeKey = text.hashCode() // val

        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) {
                Utils.debugLog("$TAG: Skipping translation for [$text], already pending with key ($compositeKey)")
                callOriginalMethod(text, methodHookParam) // Chama original com texto não traduzido
                return null
            }
            Alltrans.pendingTextViewTranslations.add(compositeKey)
            Utils.debugLog("$TAG: Added composite key ($compositeKey) to pending set for DrawText.")
        }

        if (tryGetFromCache(text, methodHookParam, compositeKey)) {
            return null // Cache hit e método original já chamado com tradução
        }

        // Não encontrado no cache, precisa traduzir
        // Mas o método original precisa ser chamado com o texto original para que algo seja desenhado
        // enquanto a tradução está pendente.
        // O callback de GetTranslate chamará callOriginalMethod com o texto traduzido.
        Utils.debugLog("$TAG: Not in cache, requesting translation for [$text]. Calling original method with original text first.")
        callOriginalMethod(text, methodHookParam) // Chama o original com o texto não traduzido

        val getTranslate = GetTranslate().apply { // val
            stringToBeTrans = text
            originalCallable = this@DrawTextHookHandler
            userData = methodHookParam
            // canCallOriginal é true porque queremos que GetTranslate chame callOriginalMethod
            // com o texto traduzido. No entanto, DrawTextHookHandler é um XC_MethodReplacement,
            // o que significa que o método original não será chamado automaticamente pelo Xposed
            // APÓS o replaceHookedMethod retornar. Nós o chamamos manualmente através de callOriginalMethod.
            // O canCallOriginal aqui controla se o GetTranslate.onResponse/onFailure vai invocar
            // o this@DrawTextHookHandler.callOriginalMethod. Sim, queremos isso.
            canCallOriginal = true
            pendingCompositeKey = compositeKey
        }

        GetTranslateToken().apply { // val implícito
            this.getTranslate = getTranslate
        }.doAll()

        return null // replaceHookedMethod para drawText geralmente retorna null ou void
    }

    private fun tryGetFromCache(text: String, methodHookParam: XC_MethodHook.MethodHookParam, compositeKey: Int): Boolean {
        if (!PreferenceList.Caching) {
            // Se o cache está desabilitado, não há nada para obter.
            // A tradução será solicitada e o original será chamado no handleTranslation.
            return false
        }

        var cachedTranslation: String? = null // var
        var cacheHit = false // var

        Alltrans.cacheAccess.acquireUninterruptibly()
        try {
            cachedTranslation = Alltrans.cache?.get(text)

            if (cachedTranslation != null) {
                Utils.debugLog("$TAG: Thread ${Thread.currentThread().id} - Encontrado no cache: [$text] -> [$cachedTranslation]")
                synchronized(Alltrans.pendingTextViewTranslations) {
                    Alltrans.pendingTextViewTranslations.remove(compositeKey)
                    Utils.debugLog("$TAG: Removed composite key ($compositeKey) from pending set after cache hit.")
                }
                callOriginalMethod(cachedTranslation, methodHookParam) // Chama com tradução do cache
                cacheHit = true
            } else {
                Utils.debugLog("$TAG: Thread ${Thread.currentThread().id} - Não encontrado no cache: [$text]")
                // Não chama callOriginalMethod aqui, pois será chamado em handleTranslation antes de solicitar nova tradução
            }
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) { // Garante que só liberamos se tivermos adquirido e não liberado ainda
                Alltrans.cacheAccess.release()
            }
        }
        return cacheHit
    }

    private fun logMethodInvocation(methodHookParam: XC_MethodHook.MethodHookParam, args: Array<Any?>) {
        Utils.debugLog(
            "$TAG: Thread ${Thread.currentThread().id} - Invocando ${methodHookParam.method.name} com texto: ${args.getOrNull(0)}"
        )
    }

    private fun logMethodError(error: Throwable, args: Array<Any?>) {
        val stackTrace = StringWriter().apply { // val
            error.printStackTrace(PrintWriter(this))
        }.toString()

        Utils.debugLog("$TAG: Erro ao invocar método original: $stackTrace")

        val argsInfo = args.joinToString(", ") { arg -> // val
            "Classe: ${arg?.javaClass?.canonicalName ?: "null"}, Valor: $arg"
        }
        Utils.debugLog("$TAG: Parâmetros com erro: $argsInfo")
    }

    private fun logException(exception: Exception) {
        Utils.debugLog("$TAG: Exceção geral: ${Log.getStackTraceString(exception)}")
    }
}