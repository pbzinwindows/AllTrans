package akhil.alltrans

import android.annotation.SuppressLint
import android.text.AlteredCharSequence
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.SpannedString
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.nio.CharBuffer
import java.util.regex.Pattern

class SetTextHookHandler : XC_MethodHook() {

    companion object {
        private const val TAG = "AllTrans:SetTextHook"

        // Padrões compilados uma única vez para melhor performance
        private val NUMERIC_PATTERN: Pattern = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$")
        private val URL_LIKE_PATTERN: Pattern = Pattern.compile("^(https?://.*|[^\\s]+\\.[^\\s]+)$")
        private val ACRONYM_LIKE_PATTERN: Pattern = Pattern.compile("^[A-Z0-9_\\-:]+$")
        private val TIMESTAMP_PATTERN: Pattern = Pattern.compile("^\\d{1,2}:\\d{2}(:\\d{2})?$")
        private val WHITESPACE_PATTERN: Pattern = Pattern.compile("^\\s*$")

        // Configurações de texto que não devem ser traduzidas
        private const val MIN_LENGTH_FOR_TRANSLATION = 2
        private const val MAX_LENGTH_FOR_ACRONYM = 5
        private const val MIN_LENGTH_FOR_URL = 4

        /**
         * Verifica se o texto não é apenas espaços em branco
         */
        fun isNotWhiteSpace(text: String?): Boolean {
            return !text.isNullOrEmpty() && !WHITESPACE_PATTERN.matcher(text).matches()
        }

        /**
         * Verifica se o texto deve ser ignorado para tradução
         */
        private fun shouldSkipTranslation(text: String): SkipReason? {
            return when {
                text.length < MIN_LENGTH_FOR_TRANSLATION -> SkipReason.TOO_SHORT
                NUMERIC_PATTERN.matcher(text).matches() -> SkipReason.NUMERIC
                text.length > MIN_LENGTH_FOR_URL &&
                        URL_LIKE_PATTERN.matcher(text).matches() &&
                        !text.contains(" ") -> SkipReason.URL_LIKE
                text.length <= MAX_LENGTH_FOR_ACRONYM &&
                        ACRONYM_LIKE_PATTERN.matcher(text).matches() -> SkipReason.ACRONYM
                TIMESTAMP_PATTERN.matcher(text).matches() -> SkipReason.TIMESTAMP
                else -> null
            }
        }

        private enum class SkipReason {
            TOO_SHORT, NUMERIC, URL_LIKE, ACRONYM, TIMESTAMP
        }
    }

    /**
     * Modifica o argumento do método com o novo texto, respeitando o tipo original
     */
    private fun modifyArgument(param: MethodHookParam, newText: CharSequence?) {
        if (param.args.isEmpty() || param.args[0] == null || newText == null) {
            return
        }

        val originalArg = param.args[0]
        val argType = originalArg.javaClass

        try {
            param.args[0] = when (argType) {
                AlteredCharSequence::class.java ->
                    AlteredCharSequence.make(newText, null, 0, 0)
                CharBuffer::class.java ->
                    CharBuffer.wrap(newText.toString())
                SpannableString::class.java ->
                    SpannableString(newText)
                SpannedString::class.java ->
                    SpannedString(newText)
                String::class.java ->
                    newText.toString()
                StringBuffer::class.java ->
                    StringBuffer(newText.toString())
                StringBuilder::class.java ->
                    StringBuilder(newText.toString())
                else -> {
                    // Para CharSequence e tipos desconhecidos
                    if (originalArg is CharSequence) {
                        SpannableStringBuilder(newText)
                    } else {
                        Log.w(TAG, "Tipo de argumento não suportado: ${argType.name}")
                        newText.toString()
                    }
                }
            }

            Utils.debugLog("$TAG: Argumento modificado com texto: [$newText]")

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao modificar argumento do tipo ${argType.name}: [$newText]", e)
            // Fallback para String simples
            try {
                param.args[0] = newText.toString()
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Erro no fallback para String", fallbackException)
            }
        }
    }

    /**
     * Verifica se o TextView é editável
     */
    private fun isEditableTextView(textView: TextView): Boolean {
        return try {
            XposedHelpers.callMethod(textView, "getDefaultEditable") as Boolean
        } catch (e: Throwable) {
            Log.w(TAG, "Erro ao verificar se TextView é editável", e)
            false
        }
    }

    /**
     * Cria uma chave composta única para o TextView e texto
     */
    private fun createCompositeKey(textViewHashCode: Int, text: String): Int {
        return "$textViewHashCode:${text.hashCode()}".hashCode()
    }

    /**
     * Verifica se a tradução já existe no cache e é diferente do texto original
     */
    private fun getCachedTranslation(text: String): String? {
        if (!PreferenceList.Caching) return null

        return try {
            Alltrans.cacheAccess.acquireUninterruptibly()
            val cache = Alltrans.cache
            val cachedValue = cache?.get(text)

            // Retorna apenas se a tradução é diferente do texto original
            if (cachedValue != null && cachedValue != text) {
                Utils.debugLog("$TAG: Tradução encontrada no cache: [$text] -> [$cachedValue]")
                cachedValue
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao acessar cache", e)
            null
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }
    }

    /**
     * Remove a chave da lista de pendências
     */
    private fun removePendingTranslation(compositeKey: Int, reason: String) {
        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.remove(compositeKey)) {
                Utils.debugLog("$TAG: Chave composta ($compositeKey) removida das pendências: $reason")
            }
        }
    }

    /**
     * Adiciona à lista de pendências
     */
    private fun addPendingTranslation(compositeKey: Int): Boolean {
        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) {
                Utils.debugLog("$TAG: Tradução já pendente para chave ($compositeKey)")
                return false
            }
            Alltrans.pendingTextViewTranslations.add(compositeKey)
            Utils.debugLog("$TAG: Chave ($compositeKey) adicionada às pendências")
            return true
        }
    }

    /**
     * Solicita tradução usando o método apropriado (batch ou direto)
     */
    private fun requestTranslation(text: String, textView: TextView, compositeKey: Int) {
        if (PreferenceList.TranslatorProvider == "m" && PreferenceList.CurrentAppMicrosoftBatchEnabled) {
            Utils.debugLog("$TAG: Usando BatchTranslationManager para Microsoft")
            Alltrans.batchManager.addString(
                text = text,
                userData = textView,
                originalCallable = null,
                canCallOriginal = false,
                compositeKey = compositeKey
            )
        } else {
            val reason = if (PreferenceList.TranslatorProvider == "m")
                "(MS batching desabilitado)" else "(provedor não-MS)"
            Utils.debugLog("$TAG: Usando tradução direta $reason")

            val getTranslate = GetTranslate().apply {
                stringToBeTrans = text
                userData = textView
                canCallOriginal = false
                pendingCompositeKey = compositeKey
            }

            val getTranslateToken = GetTranslateToken().apply {
                this.getTranslate = getTranslate
            }
            getTranslateToken.doAll()
        }
    }

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        // Validações iniciais
        if (param.args.isEmpty() || param.args[0] == null || param.args[0] !is CharSequence) {
            return
        }

        val originalText = param.args[0].toString()
        if (!isNotWhiteSpace(originalText)) {
            return
        }

        // Verificar se é TextView
        val textView = param.thisObject as? TextView ?: run {
            Log.w(TAG, "Objeto não é TextView: ${param.thisObject.javaClass.name}")
            return
        }

        // Verificar se é editável (pular se for, exceto hints)
        if (isEditableTextView(textView) && param.method.name != "setHint") {
            Utils.debugLog("$TAG: Pulando TextView editável: [$originalText]")
            if (PreferenceList.Scroll) configureScroll(textView)
            return
        }

        // Configurar scroll se necessário
        if (PreferenceList.Scroll) configureScroll(textView)

        // Verificar padrões que devem ser ignorados
        shouldSkipTranslation(originalText)?.let { skipReason ->
            Utils.debugLog("$TAG: Pulando tradução ($skipReason): [$originalText]")
            return
        }

        val compositeKey = createCompositeKey(textView.hashCode(), originalText)

        // Verificar se já está pendente
        if (!addPendingTranslation(compositeKey)) {
            return // Já está pendente
        }

        // Verificar cache primeiro
        getCachedTranslation(originalText)?.let { cachedTranslation ->
            Utils.debugLog("$TAG: Aplicando tradução do cache: [$originalText] -> [$cachedTranslation]")
            modifyArgument(param, cachedTranslation)
            removePendingTranslation(compositeKey, "tradução do cache aplicada")
            return
        }

        // Solicitar nova tradução
        Utils.debugLog("$TAG: Solicitando tradução para: [$originalText]")
        requestTranslation(originalText, textView, compositeKey)

        Utils.debugLog("$TAG: Prosseguindo com texto original enquanto aguarda callback")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configureScroll(textView: TextView) {
        try {
            textView.apply {
                ellipsize = TextUtils.TruncateAt.MARQUEE
                isSelected = true
                marqueeRepeatLimit = -1

                // Só configurar se ainda não tem movimento configurado
                if (movementMethod == null) {
                    isVerticalScrollBarEnabled = true
                    movementMethod = ScrollingMovementMethod()

                    setOnTouchListener { view, event ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        val textView = view as TextView
                        val method = textView.movementMethod
                        val text = textView.text

                        when {
                            method == null -> false
                            !textView.isFocused -> false
                            text !is Spannable -> false
                            else -> method.onTouchEvent(textView, text, event)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Falha ao configurar scroll", e)
        }
    }
}