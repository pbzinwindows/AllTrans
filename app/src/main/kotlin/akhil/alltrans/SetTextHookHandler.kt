package akhil.alltrans

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.text.AlteredCharSequence
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.SpannedString
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.nio.CharBuffer
import java.util.regex.Pattern

class SetTextHookHandler : XC_MethodHook() {

    companion object {
        private const val TAG = "AllTrans:SetTextHook"

        private val NUMERIC_PATTERN: Pattern = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$")
        private val URL_LIKE_PATTERN: Pattern = Pattern.compile("^(https?://.*|[^\\s]+\\.[^\\s]+)$")
        private val ACRONYM_LIKE_PATTERN: Pattern = Pattern.compile("^[A-Z0-9_\\-:]+$")
        private val TIMESTAMP_PATTERN: Pattern = Pattern.compile("^\\d{1,2}:\\d{2}(:\\d{2})?$")
        private val WHITESPACE_PATTERN: Pattern = Pattern.compile("^\\s*$")

        private const val MIN_LENGTH_FOR_TRANSLATION = 2
        private const val MAX_LENGTH_FOR_ACRONYM = 5
        private const val MIN_LENGTH_FOR_URL = 4

        fun isNotWhiteSpace(text: String?): Boolean {
            return !text.isNullOrEmpty() && !WHITESPACE_PATTERN.matcher(text).matches()
        }

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
                    if (originalArg is CharSequence) {
                        SpannableStringBuilder(newText)
                    } else {
                        Log.w(TAG, "Tipo de argumento não suportado: ${argType.name}")
                        newText.toString()
                    }
                }
            }
            Utils.debugLog("$TAG: Argumento modificado com texto: [$newText] para método ${param.method.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao modificar argumento do tipo ${argType.name}: [$newText]", e)
            try {
                param.args[0] = newText.toString()
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Erro no fallback para String", fallbackException)
            }
        }
    }

    private fun isEditableTextView(textView: TextView): Boolean {
        return try {
            val inputType = textView.inputType
            (android.text.InputType.TYPE_NULL != inputType && android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != inputType) || textView.isFocusableInTouchMode()
        } catch (e: Throwable) {
            Log.w(TAG, "Erro ao verificar se TextView é editável, assumindo não editável.", e)
            false
        }
    }

    private fun createCompositeKey(textViewHashCode: Int, text: String): Int {
        return "${System.identityHashCode(textViewHashCode)}:${text.hashCode()}".hashCode()
    }

    private fun getCachedTranslation(text: String): String? {
        if (!PreferenceList.Caching) return null

        return try {
            Alltrans.cacheAccess.acquireUninterruptibly()
            val cache = Alltrans.cache
            val cachedValue = cache?.get(text)

            if (cachedValue != null && cachedValue != text) {
                Utils.debugLog("$TAG: Tradução encontrada no cache: [$text] -> [$cachedValue]")
                cachedValue
            } else {
                if (cachedValue == text) Utils.debugLog("$TAG: Cache hit, mas tradução é igual ao original: [$text]")
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

    private fun removePendingTranslation(compositeKey: Int, reason: String) {
        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.remove(compositeKey)) {
                Utils.debugLog("$TAG: Chave composta ($compositeKey) removida das pendências: $reason")
            }
        }
    }

    private fun addPendingTranslation(compositeKey: Int): Boolean {
        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) {
                return false
            }
            Alltrans.pendingTextViewTranslations.add(compositeKey)
            Utils.debugLog("$TAG: Chave ($compositeKey) adicionada às pendências")
            return true
        }
    }

    private fun requestTranslation(text: String, textView: TextView, compositeKey: Int) {
        if (PreferenceList.TranslatorProvider == "m" && PreferenceList.CurrentAppMicrosoftBatchEnabled) {
            Utils.debugLog("$TAG: Usando BatchTranslationManager para Microsoft para texto '[${text.take(50)}...]' (Chave: $compositeKey)")
            Alltrans.batchManager.addString(
                text = text,
                userData = textView,
                originalCallable = null,
                canCallOriginal = false,
                compositeKey = compositeKey
            )
        } else {
            val reason = if (PreferenceList.TranslatorProvider == "m")
                "(MS batching desabilitado para este app)" else "(provedor não-MS: ${PreferenceList.TranslatorProvider})"
            Utils.debugLog("$TAG: Usando tradução direta $reason para texto '[${text.take(50)}...]' (Chave: $compositeKey)")

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
        val textView = param.thisObject as? TextView ?: return

        val newTextBeingSetArg = param.args.getOrNull(0)
        val newTextBeingSet = newTextBeingSetArg?.toString()

        if (!isNotWhiteSpace(newTextBeingSet)) {
            if (PreferenceList.Scroll && param.method.name != "setHint") configureScroll(textView)
            return
        }

        val isAlreadyTranslatedByAllTrans = textView.getTag(R.id.tag_alltrans_translated_textview) as? Boolean ?: false

        if (isAlreadyTranslatedByAllTrans) {
            val currentTextInTextView = textView.text?.toString()
            if (newTextBeingSet == currentTextInTextView) {
                Utils.debugLog("$TAG: TextView (${System.identityHashCode(textView)}) já marcado como traduzido e texto é o mesmo ('${newTextBeingSet?.take(50)}...'), pulando hook.")
                return
            } else {
                Utils.debugLog("$TAG: TextView (${System.identityHashCode(textView)}) marcado, mas texto NOVO ('${newTextBeingSet?.take(50)}...') diferente do atual ('${currentTextInTextView?.take(50)}...') sendo setado. Removendo tag.")
                textView.setTag(R.id.tag_alltrans_translated_textview, null)
            }
        }

        if (newTextBeingSetArg !is CharSequence) {
            return
        }

        val originalText = newTextBeingSet!!

        if (isEditableTextView(textView) && param.method.name != "setHint") {
            Utils.debugLog("$TAG: Pulando TextView editável: [$originalText]")
            if (PreferenceList.Scroll) configureScroll(textView)
            return
        }

        if (PreferenceList.Scroll && param.method.name != "setHint") configureScroll(textView)

        shouldSkipTranslation(originalText)?.let { skipReason ->
            Utils.debugLog("$TAG: Pulando tradução ($skipReason): [$originalText]")
            return
        }

        val compositeKey = createCompositeKey(System.identityHashCode(textView), originalText)

        if (!addPendingTranslation(compositeKey)) {
            Utils.debugLog("$TAG: Tradução para chave ($compositeKey) referente ao texto '[${originalText.take(50)}...]' já está pendente, pulando request duplicado.")
            return
        }

        // CORREÇÃO PRINCIPAL: Cache hit agora modifica o argumento diretamente e bloqueia o método original
        getCachedTranslation(originalText)?.let { cachedTranslation ->
            Utils.debugLog("$TAG: Cache hit para chave ($compositeKey). Texto original: [$originalText], Traduzido do cache: [$cachedTranslation]")

            // Modificar o argumento diretamente com a tradução do cache
            modifyArgument(param, cachedTranslation)

            // Marcar o TextView como já traduzido para evitar loops
            textView.setTag(R.id.tag_alltrans_translated_textview, true)

            // Remover da lista de pendências
            removePendingTranslation(compositeKey, "cache hit - tradução aplicada diretamente")

            Utils.debugLog("$TAG: Cache hit aplicado diretamente ao argumento: [$cachedTranslation] para TextView ${System.identityHashCode(textView)}")
            return
        }

        Utils.debugLog("$TAG: Solicitando nova tradução para: [${originalText.take(50)}...] (Chave: $compositeKey)")
        requestTranslation(originalText, textView, compositeKey)

        Utils.debugLog("$TAG: beforeHookedMethod para '${param.method.name}' prosseguindo com texto original ('${originalText.take(50)}...') enquanto aguarda callback para chave $compositeKey.")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configureScroll(textView: TextView) {
        try {
            if (!isEditableTextView(textView) || textView is android.widget.Button) {
                if (PreferenceList.Scroll) {
                    textView.apply {
                        ellipsize = TextUtils.TruncateAt.MARQUEE
                        isSelected = true
                        marqueeRepeatLimit = -1

                        if (movementMethod == null || movementMethod !is ScrollingMovementMethod) {
                            isVerticalScrollBarEnabled = true
                            movementMethod = ScrollingMovementMethod.getInstance()

                            setOnTouchListener { view, event ->
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                                val tv = view as TextView
                                val currentMovementMethod = tv.movementMethod
                                val currentText = tv.text

                                when {
                                    currentMovementMethod == null -> false
                                    !tv.isFocused -> false
                                    currentText !is Spannable -> false
                                    else -> currentMovementMethod.onTouchEvent(tv, currentText, event)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Falha ao configurar scroll", e)
        }
    }
}