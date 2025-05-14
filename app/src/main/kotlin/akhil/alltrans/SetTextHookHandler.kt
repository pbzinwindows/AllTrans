@file:Suppress("DEPRECATION")

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
    // Função auxiliar para modificar o argumento no param
    private fun modifyArgument(param: MethodHookParam, newText: CharSequence?) {
        if (param.args.size > 0 && param.args[0] != null && newText != null) {
            val argType: Class<*> = param.args[0].javaClass
            var textToSet: CharSequence? = newText
            try {
                if (argType == AlteredCharSequence::class.java) {
                    // Using null-safe operator ?: to ensure non-null value
                    textToSet = AlteredCharSequence.make(textToSet ?: "", null, 0, 0)
                } else if (argType == CharBuffer::class.java) {
                    textToSet = CharBuffer.wrap(textToSet.toString())
                } else if (argType == SpannableString::class.java) {
                    textToSet = SpannableString(textToSet.toString())
                } else if (argType == SpannedString::class.java) {
                    textToSet = SpannedString(textToSet.toString())
                } else if (argType == String::class.java) {
                    textToSet = textToSet.toString()
                } else if (argType == StringBuffer::class.java) {
                    textToSet = StringBuffer(textToSet.toString())
                } else if (argType == StringBuilder::class.java) {
                    textToSet = StringBuilder(textToSet.toString())
                } else if (param.args[0] is CharSequence) {
                    textToSet = SpannableStringBuilder(textToSet.toString())
                } else {
                    Log.w(
                        "AllTrans",
                        "Unsupported argument type in modifyArgument: " + argType.getName()
                    )
                    textToSet = textToSet.toString()
                }
                param.args[0] = textToSet
                utils.debugLog("Hook argument modified directly with text: [" + textToSet + "]")
            } catch (e: Exception) {
                Log.e(
                    "AllTrans",
                    "Error modifying argument type " + argType.getName() + " with text [" + newText + "]",
                    e
                )
                try {
                    param.args[0] = newText.toString()
                } catch (ignored: Exception) {
                }
            }
        } else if (param.args.size > 0) {
            param.args[0] = newText
        }
    }


    @Throws(Throwable::class)
    override fun beforeHookedMethod(param: MethodHookParam) {
        // Verificações Iniciais
        if (param.args == null || param.args.size == 0 || param.args[0] == null || (param.args[0] !is CharSequence)) {
            return
        }
        val originalCharSequence = param.args[0] as CharSequence
        val stringArgs = originalCharSequence.toString()
        if (!isNotWhiteSpace(stringArgs)) {
            return
        }

        var textView: TextView? = null
        if (param.thisObject is TextView) {
            textView = param.thisObject as TextView
            try {
                val editable = XposedHelpers.callMethod(textView, "getDefaultEditable") as Boolean
                if (editable && param.method.getName() != "setHint") {
                    utils.debugLog("Skipping translation for editable TextView: [" + stringArgs + "]")
                    if (PreferenceList.Scroll) configureScroll(textView)
                    return
                }
                if (!editable && PreferenceList.Scroll) configureScroll(textView)
            } catch (e: Throwable) {
                Log.w("AllTrans", "Error checking editable/scroll: " + Log.getStackTraceString(e))
            }
        } else {
            Log.w(
                "AllTrans",
                "Hooked object is not a TextView: " + param.thisObject.javaClass.getName()
            )
            return  // Só traduzir TextViews
        }

        // --- INÍCIO DA VERIFICAÇÃO DE PENDÊNCIA ---
        val textViewHashCode = textView.hashCode()
        if (alltrans.Companion.pendingTextViewTranslations.contains(textViewHashCode)) {
            utils.debugLog("Skipping translation request for [" + stringArgs + "], already pending for TextView (" + textViewHashCode + ")")
            // Não retorna, apenas evita nova requisição. O setText original prossegue.
        }


        // --- FIM DA VERIFICAÇÃO DE PENDÊNCIA ---


        // Otimização: Verificar se já está traduzido
        var alreadyTranslated = false
        if (PreferenceList.Caching) {
            alltrans.Companion.cacheAccess.acquireUninterruptibly()
            try {
                val cacheRef = alltrans.Companion.cache
                if (cacheRef != null &&  // Verificar se cache não é nulo
                    cacheRef.containsKey(stringArgs) &&
                    stringArgs == cacheRef[stringArgs]
                ) {
                    utils.debugLog("Skipping processing, text already appears translated: [" + stringArgs + "]")
                    alreadyTranslated = true
                }
            } finally {
                if (alltrans.Companion.cacheAccess.availablePermits() == 0) {
                    alltrans.Companion.cacheAccess.release()
                }
            }
        }
        if (alreadyTranslated) {
            return
        }

        // Pular Numéricos, URLs e Acrônimos
        if (SetTextHookHandler.Companion.NUMERIC_PATTERN.matcher(stringArgs).matches()) {
            utils.debugLog("Skipping translation for numeric string: [" + stringArgs + "]")
            return
        }
        if (stringArgs.length > 3 && SetTextHookHandler.Companion.URL_LIKE_PATTERN.matcher(
                stringArgs
            ).matches() && !stringArgs.contains(" ")
        ) {
            utils.debugLog("Skipping translation for URL-like string: [" + stringArgs + "]")
            return
        }
        if (stringArgs.length < 5 && SetTextHookHandler.Companion.ACRONYM_LIKE_PATTERN.matcher(
                stringArgs
            ).matches()
        ) {
            utils.debugLog("Skipping translation for likely acronym/code: [" + stringArgs + "]")
            return
        }

        // --- Processar Tradução ---
        utils.debugLog("Potential translation target: [" + stringArgs + "] for TextView (" + textViewHashCode + ")")

        var cachedTranslation: String? = null
        var translationRequested = false

        alltrans.Companion.cacheAccess.acquireUninterruptibly()
        try {
            val cacheRef = alltrans.Companion.cache
            if (PreferenceList.Caching && cacheRef != null && cacheRef.containsKey(stringArgs)) {
                cachedTranslation = cacheRef[stringArgs]
                if (cachedTranslation != null && cachedTranslation != stringArgs) {
                    utils.debugLog("Applying cached translation directly to args: [" + stringArgs + "] -> [" + cachedTranslation + "]")
                    // Modifica args e deixa original prosseguir
                    modifyArgument(param, cachedTranslation)
                } else {
                    utils.debugLog("Invalid/same translation in cache for: [" + stringArgs + "], proceeding with original.")
                    cachedTranslation = null // Não usar cache
                }
            }
            // Se NÃO encontrou tradução válida no cache E NÃO está pendente
            // --- MODIFICADO PARA INCLUIR VERIFICAÇÃO DE PENDÊNCIA ---
            if (cachedTranslation == null && !alltrans.Companion.pendingTextViewTranslations.contains(
                    textViewHashCode
                )
            ) {
                // --- FIM DA MODIFICAÇÃO ---
                utils.debugLog("String not cached or invalid, requesting translation for: [" + stringArgs + "]")
                translationRequested = true // Marcar que a tradução foi requisitada

                // --- INÍCIO DA ADIÇÃO AO SET DE PENDENTES ---
                // Adiciona ao set ANTES de iniciar a tarefa assíncrona
                alltrans.Companion.pendingTextViewTranslations.add(textViewHashCode)
                utils.debugLog("Added TextView (" + textViewHashCode + ") to pending set.")

                // --- FIM DA ADIÇÃO AO SET DE PENDENTES ---
                val getTranslate = GetTranslate()
                getTranslate.stringToBeTrans = stringArgs
                getTranslate.userData = textView // Passar o TextView
                getTranslate.canCallOriginal = false // Callback não usará OriginalCallable

                val getTranslateToken = GetTranslateToken()
                getTranslateToken.getTranslate = getTranslate
                getTranslateToken.doAll()
            }
        } finally {
            if (alltrans.Companion.cacheAccess.availablePermits() == 0) {
                alltrans.Companion.cacheAccess.release()
            }
        }

        // --- Lógica de Execução do Método Original ---
        if (translationRequested) {
            // Se uma tradução foi requisitada (cache miss):
            // 1. Deixar o método original executar AGORA com o TEXTO ORIGINAL.
            // 2. O callback (GetTranslate.onResponse) irá chamar textView.setText() DEPOIS.
            utils.debugLog("Proceeding with original text for [" + stringArgs + "] while waiting for callback.")
            // Não precisamos fazer nada aqui, apenas NÃO chamar param.setResult(null).
            // O fluxo normal do Xposed chamará o método original.
        } else if (cachedTranslation != null) { // Verifica se veio do cache
            // Se uma tradução VEIO DO CACHE (cachedTranslation != null):
            // 1. Os argumentos já foram modificados por modifyArgument().
            // 2. Deixar o método original executar AGORA com os argumentos MODIFICADOS.
            utils.debugLog("Proceeding with cached translation for [" + stringArgs + "].")
            // Também não fazemos nada aqui, Xposed chama o original.
        }
        // Se a string foi pulada por ser numérica/URL/etc, ou "already translated", ou "pending"
        // o método também já retornou antes ou simplesmente não iniciou nova tradução,
        // deixando o original executar.
    }


    // Função auxiliar para configurar o scroll (sem alterações)
    @SuppressLint("ClickableViewAccessibility")
    private fun configureScroll(textView: TextView) {
        try {
            textView.setEllipsize(TextUtils.TruncateAt.MARQUEE)
            textView.setSelected(true)
            textView.setMarqueeRepeatLimit(-1)
            val alreadyScrolling = textView.getMovementMethod()
            if (alreadyScrolling == null) {
                textView.setVerticalScrollBarEnabled(true)
                textView.setMovementMethod(ScrollingMovementMethod())
                textView.setOnTouchListener(object : OnTouchListener {
                    override fun onTouch(v: View, event: MotionEvent?): Boolean {
                        v.getParent().requestDisallowInterceptTouchEvent(true)
                        val method = (v as TextView).getMovementMethod()
                        // Adicionado check para evitar crash se o texto não for Spannable
                        val text = v.getText()
                        if (method != null && v.isFocused() && text is Spannable) {
                            return method.onTouchEvent(v, text, event)
                        }
                        return false
                    }
                })
            }
        } catch (e: Throwable) {
            Log.w("AllTrans", "Failed to configure scrolling", e)
        }
    }

    companion object {
        private val NUMERIC_PATTERN: Pattern = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$")
        private val URL_LIKE_PATTERN: Pattern =
            Pattern.compile("^(http|https)://.*|[^\\s]+\\.[^\\s]+$")

        // Padrão para verificar se a string é apenas maiúsculas/números/símbolos comuns e curta
        private val ACRONYM_LIKE_PATTERN: Pattern = Pattern.compile("^[A-Z0-9_\\-:]+$")


        fun isNotWhiteSpace(abc: String?): Boolean {
            return !(abc == null || "" == abc) && !abc.matches("^\\s*$".toRegex())
        }
    }
}