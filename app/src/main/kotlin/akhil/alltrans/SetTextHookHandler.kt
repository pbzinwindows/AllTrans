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

        // Padrões aprimorados
        private val NUMERIC_PATTERN: Pattern = Pattern.compile("^[+-]?\\d+([.,]\\d+)?([%°€$£¥₹]|\\s*(MB|GB|TB|KB|Hz|MHz|GHz|dB|px|dp|sp))?$")
        private val URL_LIKE_PATTERN: Pattern = Pattern.compile("^(https?://\\S+|ftp://\\S+|\\S+\\.\\S+(/\\S*)?|www\\.\\S+)$")
        private val ACRONYM_LIKE_PATTERN: Pattern = Pattern.compile("^[A-Z0-9_\\-.:]{2,8}$")
        private val TIMESTAMP_PATTERN: Pattern = Pattern.compile("^\\d{1,2}:\\d{2}(:\\d{2})?(\\s?(AM|PM))?$", Pattern.CASE_INSENSITIVE)

        // Novos padrões importantes
        private val DATE_PATTERN: Pattern = Pattern.compile("^\\d{1,4}[/-]\\d{1,2}[/-]\\d{1,4}$")
        private val VERSION_PATTERN: Pattern = Pattern.compile("^v?\\d+(\\.\\d+){1,3}(\\s*(beta|alpha|rc|dev)\\d*)?$", Pattern.CASE_INSENSITIVE)
        private val EMAIL_PATTERN: Pattern = Pattern.compile("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$")
        private val PHONE_PATTERN: Pattern = Pattern.compile("^[+]?[\\d\\s\\-()]{7,20}$")
        private val FILE_PATH_PATTERN: Pattern = Pattern.compile("^(/[^/\\s]*)+/?$|^[A-Za-z]:\\\\.*$")
        private val HEX_COLOR_PATTERN: Pattern = Pattern.compile("^#[0-9A-Fa-f]{3,8}$")
        private val COORDINATE_PATTERN: Pattern = Pattern.compile("^[-+]?\\d+\\.\\d+,\\s*[-+]?\\d+\\.\\d+$")
        private val CODE_IDENTIFIER_PATTERN: Pattern = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$")
        private val PROGRAMMING_SYMBOL_PATTERN: Pattern = Pattern.compile("^[{}\\[\\]()<>+=*/%&|^~!@#$]+$")
        private val CURRENCY_PATTERN: Pattern = Pattern.compile("^[€$£¥₹₽¢₩₪]\\s*\\d+([.,]\\d+)?$")
        private val HTML_TAG_PATTERN: Pattern = Pattern.compile("^<[^>]+>$")
        private val CSS_SELECTOR_PATTERN: Pattern = Pattern.compile("^[.#]?[a-zA-Z][a-zA-Z0-9\\-_]*$")

        // Padrões para contextos específicos de Android
        private val ANDROID_RESOURCE_PATTERN: Pattern = Pattern.compile("^@[a-zA-Z]+/[a-zA-Z0-9_]+$")
        private val PACKAGE_NAME_PATTERN: Pattern = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.([a-zA-Z][a-zA-Z0-9_]*))+$")
        private val CLASS_NAME_PATTERN: Pattern = Pattern.compile("^[A-Z][a-zA-Z0-9]*$")

        private val WHITESPACE_PATTERN: Pattern = Pattern.compile("^\\s*$")
        private const val MIN_LENGTH_FOR_TRANSLATION = 2

        fun isNotWhiteSpace(text: String?): Boolean {
            return !text.isNullOrEmpty() && !WHITESPACE_PATTERN.matcher(text).matches()
        }

        fun shouldSkipTranslation(text: String): Boolean {
            // Verifica se é muito curto
            if (text.length < MIN_LENGTH_FOR_TRANSLATION) {
                Utils.debugLog("$TAG: Skipping (too short): [$text]")
                return true
            }

            // Lista de padrões para verificar
            val patterns = mapOf(
                NUMERIC_PATTERN to "numeric",
                URL_LIKE_PATTERN to "URL-like",
                EMAIL_PATTERN to "email",
                PHONE_PATTERN to "phone",
                DATE_PATTERN to "date",
                VERSION_PATTERN to "version",
                FILE_PATH_PATTERN to "file path",
                HEX_COLOR_PATTERN to "hex color",
                COORDINATE_PATTERN to "coordinate",
                PROGRAMMING_SYMBOL_PATTERN to "programming symbol",
                CURRENCY_PATTERN to "currency",
                HTML_TAG_PATTERN to "HTML tag",
                CSS_SELECTOR_PATTERN to "CSS selector",
                ANDROID_RESOURCE_PATTERN to "Android resource",
                PACKAGE_NAME_PATTERN to "package name",
                TIMESTAMP_PATTERN to "timestamp"
            )

            // Verifica cada padrão
            for ((pattern, name) in patterns) {
                if (pattern.matcher(text).matches()) {
                    Utils.debugLog("$TAG: Skipping ($name): [$text]")
                    return true
                }
            }

            // Verifica acrônimos com lógica mais específica
            if (ACRONYM_LIKE_PATTERN.matcher(text).matches()) {
                // Só pula se for realmente um acrônimo (tem letras maiúsculas)
                val hasUpperCase = text.any { it.isUpperCase() }
                if (hasUpperCase) {
                    Utils.debugLog("$TAG: Skipping (acronym): [$text]")
                    return true
                }
            }

            // Verifica se é código/identificador
            if (CODE_IDENTIFIER_PATTERN.matcher(text).matches() && text.length <= 15) {
                // Se contém apenas snake_case ou camelCase, provavelmente é código
                val hasUnderscores = text.contains('_')
                val hasCamelCase = text.any { it.isUpperCase() } && text.any { it.isLowerCase() }
                if (hasUnderscores || hasCamelCase) {
                    Utils.debugLog("$TAG: Skipping (code identifier): [$text]")
                    return true
                }
            }

            // Verifica se é nome de classe Android
            if (CLASS_NAME_PATTERN.matcher(text).matches() && text.length <= 20) {
                Utils.debugLog("$TAG: Skipping (class name): [$text]")
                return true
            }

            return false
        }
    }

    private fun getTextViewContext(textView: TextView): String? {
        return try {
            // Tenta obter ID do recurso para contexto
            val resourceId = textView.id
            if (resourceId != View.NO_ID) {
                val context = textView.context
                val resourceName = context.resources.getResourceEntryName(resourceId)
                resourceName.lowercase()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun shouldSkipBasedOnContext(text: String, context: String?): Boolean {
        context?.let { ctx ->
            // Contextos que geralmente não devem ser traduzidos
            val skipContexts = listOf(
                "username", "email", "password", "url", "api", "key", "token",
                "id", "uuid", "hash", "version", "build", "debug", "log",
                "package", "class", "method", "function", "variable"
            )

            for (skipContext in skipContexts) {
                if (ctx.contains(skipContext)) {
                    Utils.debugLog("$TAG: Skipping based on context [$ctx]: [$text]")
                    return true
                }
            }
        }
        return false
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

        try {
            Alltrans.cacheAccess.acquireUninterruptibly()
            val currentCache = Alltrans.cache
            if (currentCache == null) {
                Utils.debugLog("$TAG ($compositeKey): Objeto Cache é NULO (inesperado, já que PreferenceList.Caching é true). Não foi possível obter tradução para: [$text]")
                return null
            }
            val cachedValue = currentCache.get(text)

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
        } catch (e: Exception) {
            Utils.debugLog("$TAG ($compositeKey): Erro ao acessar cache para: [$text]: ${Log.getStackTraceString(e)}")
            return null
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }
    }

    private fun removePendingTranslation(compositeKey: Int, reason: String = "Activity destruída/finalizando") {
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
            removePendingTranslation(compositeKey)
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

        // Verifica contexto do TextView
        val textViewContext = getTextViewContext(textView)
        if (shouldSkipBasedOnContext(originalText, textViewContext)) {
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