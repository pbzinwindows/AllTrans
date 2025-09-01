package akhil.alltrans

import android.app.Activity
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.nio.CharBuffer
import java.util.regex.Pattern

class SetTextHookHandler : XC_MethodHook() {

    companion object {
        private const val TAG = "AllTrans:SetTextHook"
        private val NUMERIC_PATTERN: Pattern = Pattern.compile("^[+-]?\\d+([.,]\\d+)?$")
        private val URL_LIKE_PATTERN: Pattern = Pattern.compile("^(https?://\\S+|[^\\s:/]+\\.[^\\s:/]+(/[\\S]*)?)$")
        private val ACRONYM_LIKE_PATTERN: Pattern = Pattern.compile("^[A-Z0-9_\\-.:]{1,10}$")
        private val TIMESTAMP_PATTERN: Pattern = Pattern.compile("^\\d{1,2}:\\d{2}(:\\d{2})?(\\s?(AM|PM))?$", Pattern.CASE_INSENSITIVE)
        private val WHITESPACE_PATTERN: Pattern = Pattern.compile("^\\s*$")
        private const val MIN_LENGTH_FOR_TRANSLATION = 1

        fun isNotWhiteSpace(text: String?): Boolean {
            return !text.isNullOrEmpty() && !WHITESPACE_PATTERN.matcher(text).matches()
        }

        fun shouldSkipTranslation(text: String): Boolean {
            if (text.length < MIN_LENGTH_FOR_TRANSLATION && text.length > 0) {
                Utils.debugLog("$TAG: Skipping (too short, mas MIN_LENGTH é 1): [$text]")
            }

            if (text.length == 1 && !Character.isLetterOrDigit(text[0])) {
                // Utils.debugLog("$TAG: Skipping (single non-alphanumeric char): [$text]")
                // return true; // Descomente se quiser pular símbolos únicos
            }

            if (NUMERIC_PATTERN.matcher(text).matches()) {
                Utils.debugLog("$TAG: Skipping (numeric): [$text]")
                return true
            }
            if (URL_LIKE_PATTERN.matcher(text).matches() && !text.contains(" ")) {
                Utils.debugLog("$TAG: Skipping (URL-like): [$text]")
                return true
            }
            if (ACRONYM_LIKE_PATTERN.matcher(text).matches()) {
                var hasLetter = false
                for (char_acronym in text) {
                    if (Character.isLetter(char_acronym)) {
                        hasLetter = true
                        break
                    }
                }
                if (!hasLetter && text.length <=3) {
                    Utils.debugLog("$TAG: Skipping (acronym-like, no letters, short): [$text]")
                    return true
                }
            }
            if (TIMESTAMP_PATTERN.matcher(text).matches()) {
                Utils.debugLog("$TAG: Skipping (timestamp): [$text]")
                return true
            }
            return false
        }
    }

    private fun modifyArgument(param: XC_MethodHook.MethodHookParam, newText: CharSequence?) {
        if (param.args.isEmpty() || param.args[0] == null || newText == null) {
            Utils.debugLog("$TAG: ModifyArgument - Argumentos inválidos ou newText nulo, não modificando.")
            return
        }
        param.args[0] = when (param.args[0]) {
            is SpannableString -> SpannableString(newText)
            is CharBuffer -> CharBuffer.wrap(newText.toString())
            is String -> newText.toString()
            is StringBuffer -> StringBuffer(newText.toString())
            is StringBuilder -> StringBuilder(newText.toString())
            else -> newText
        }
        Utils.debugLog("$TAG: Argumento modificado com texto: [$newText]")
    }

    private fun isEditableTextView(textView: TextView): Boolean {
        return try {
            val isDefaultEditable = XposedHelpers.callMethod(textView, "getDefaultEditable") as? Boolean ?: false
            val isEnabled = textView.isEnabled
            isDefaultEditable && isEnabled
        } catch (e: Throwable) {
            Utils.debugLog("$TAG: Erro ao verificar se TextView é editável: ${Log.getStackTraceString(e)}")
            false
        }
    }

    private fun createCompositeKey(textViewHashCode: Int, text: String): Int {
        return 31 * textViewHashCode + text.hashCode()
    }

    private fun getCachedTranslation(text: String, compositeKey: Int): String? {
        if (!PreferenceList.Caching) {
            Utils.debugLog("$TAG ($compositeKey): Cache está DESABILITADO nas preferências. Pulando busca no cache para: [$text]")
            return null
        }

        var cachedValue: String? = null
        try {
            Alltrans.cacheAccess.acquireUninterruptibly()
            val currentCache = Alltrans.cache
            if (currentCache == null) {
                Utils.debugLog("$TAG ($compositeKey): Objeto Cache é NULO (inesperado, já que PreferenceList.Caching é true). Não foi possível obter tradução para: [$text]")
                return null
            }
            cachedValue = currentCache.get(text)
        } catch (e: Exception) {
            Utils.debugLog("$TAG ($compositeKey): Erro ao acessar cache para: [$text]: ${Log.getStackTraceString(e)}")
            return null
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }

        if (cachedValue != null) {
            if (cachedValue != text) {
                Utils.debugLog("$TAG ($compositeKey): Tradução encontrada no cache para: [$text] -> [$cachedValue]")
                return cachedValue
            } else {
                Utils.debugLog("$TAG ($compositeKey): Valor no cache é IGUAL ao original para: [$text]. Considerado como não traduzido/sem hit útil.")
                return null
            }
        }
        Utils.debugLog("$TAG ($compositeKey): Não encontrado no cache: [$text]")
        return null
    }

    private fun removePendingTranslation(compositeKey: Int, reason: String) {
        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.remove(compositeKey)) {
                Utils.debugLog("$TAG: Chave composta ($compositeKey) removida das pendências: $reason")
            }
        }
    }

    private fun addPendingTranslation(compositeKey: Int, text: String): Boolean {
        synchronized(Alltrans.pendingTextViewTranslations) {
            if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) {
                Utils.debugLog("$TAG: Tradução JÁ PENDENTE para chave ($compositeKey), texto: [$text]")
                return false
            }
            Alltrans.pendingTextViewTranslations.add(compositeKey)
            Utils.debugLog("$TAG: Chave ($compositeKey) ADICIONADA às pendências para texto: [$text]")
            return true
        }
    }

    private fun requestTranslation(text: String, textView: TextView, compositeKey: Int) {
        val context = textView.context
        if (context is Activity && (context.isFinishing || context.isDestroyed)) {
            Utils.debugLog("$TAG ($compositeKey): Activity destruída ou finalizando para TextView ${textView.hashCode()}, cancelando requisição para: [$text]")
            removePendingTranslation(compositeKey, "Activity destruída/finalizando")
            return
        }

        Utils.debugLog("$TAG ($compositeKey): Solicitando tradução para TextView ${textView.hashCode()}, Texto: [$text]")

        if (PreferenceList.TranslatorProvider == "m" && PreferenceList.CurrentAppMicrosoftBatchEnabled) {
            Utils.debugLog("$TAG ($compositeKey): Usando BatchTranslationManager para Microsoft para: [$text]")
            Alltrans.batchManager.addString(
                text = text,
                userData = textView,
                originalCallable = null,
                canCallOriginal = false,
                compositeKey = compositeKey
            )
        } else {
            Utils.debugLog("$TAG ($compositeKey): Usando GetTranslate direto para: [$text]")
            val getTranslate = GetTranslate().apply {
                stringToBeTrans = text
                userData = textView
                originalCallable = null
                canCallOriginal = false
                pendingCompositeKey = compositeKey
            }
            GetTranslateToken().apply {
                this.getTranslate = getTranslate
            }.doAll()
        }
    }

    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
        val methodName = param.method.name
        val textView = param.thisObject as? TextView ?: return

        // Verifica a tag para ignorar o setText chamado pelo próprio AllTrans
        val appliedByAllTransTag = textView.getTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY)
        if (appliedByAllTransTag != null && appliedByAllTransTag is Boolean && appliedByAllTransTag == true) {
            textView.setTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY, false) // Reseta a tag
            Utils.debugLog("$TAG: [${textView.hashCode()}] $methodName ignorado pois foi aplicado pelo AllTrans.")
            return
        }

        if (param.args.isEmpty() || param.args[0] == null || param.args[0] !is CharSequence) {
            return
        }

        val originalTextCS = param.args[0] as CharSequence
        val originalText = originalTextCS.toString()

        val textViewIdHex = try { "@${Integer.toHexString(textView.id)}" } catch (e: Exception) { "" }
        val logPrefix = "$TAG: [${textView.hashCode()}$textViewIdHex] $methodName"

        Utils.debugLog("$logPrefix - Texto Original: [${originalText.replace("\n", "\\n")}]")

        // Verificar se o AllTrans está habilitado para este app em tempo real
        val packageName = textView.context?.packageName
        if (!PreferenceManager.isEnabledForPackage(textView.context, packageName)) {
            Utils.debugLog("$logPrefix - AllTrans DESABILITADO para este app ($packageName). Pulando.")
            return
        }

        if (methodName == "setHint" && !PreferenceList.SetHint) {
            Utils.debugLog("$logPrefix - Tradução de Hint DESABILITADA. Pulando.")
            return
        }
        if (methodName == "setText" && !PreferenceList.SetText) {
            Utils.debugLog("$logPrefix - Tradução de Texto DESABILITADA. Pulando.")
            return
        }

        if (!isNotWhiteSpace(originalText)) {
            return
        }

        if (isEditableTextView(textView) && methodName != "setHint") {
            Utils.debugLog("$logPrefix - É um TextView editável (não Hint). Pulando tradução, aplicando scroll se habilitado.")
            if (PreferenceList.Scroll) {
                configureScroll(textView)
            }
            return
        }

        if (PreferenceList.Scroll) {
            configureScroll(textView)
        }

        if (shouldSkipTranslation(originalText)) {
            return
        }

        val compositeKey = createCompositeKey(textView.hashCode(), originalText)
        Utils.debugLog("$logPrefix - Chave Composta: $compositeKey para texto: [$originalText]")

        val cachedTranslation = getCachedTranslation(originalText, compositeKey)
        if (cachedTranslation != null) {
            Utils.debugLog("$logPrefix - Aplicando tradução do CACHE: [$originalText] -> [$cachedTranslation]")

            textView.setTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY, true) // Marca antes de modificar
            modifyArgument(param, cachedTranslation)
            return
        }

        if (!addPendingTranslation(compositeKey, originalText)) {
            Utils.debugLog("$logPrefix - Tradução já está pendente para este texto/textview. Pulando nova requisição.")
            return
        }

        // Marcar a TextView com o texto original para o qual estamos solicitando a tradução
        textView.setTag(Alltrans.ALLTRANS_PENDING_TRANSLATION_TAG_KEY, originalText)
        Utils.debugLog("$logPrefix - Marcando TextView ${textView.hashCode()} com o texto pendente: [$originalText]")

        requestTranslation(originalText, textView, compositeKey)
    }

    private fun configureScroll(textView: TextView) {
        try {
            if (textView.movementMethod == null && !textView.isSingleLine) {
                textView.isVerticalScrollBarEnabled = true
                textView.movementMethod = ScrollingMovementMethod.getInstance()
                textView.scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            } else if (textView.isSingleLine) {
                textView.ellipsize = TextUtils.TruncateAt.MARQUEE
                textView.marqueeRepeatLimit = -1
                textView.isFocusable = true
                textView.isFocusableInTouchMode = true
                textView.isSelected = true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Falha ao configurar scroll para TextView ${textView.hashCode()}", e)
        }
    }
}