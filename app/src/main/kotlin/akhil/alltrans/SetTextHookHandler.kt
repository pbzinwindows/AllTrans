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
    private val TAG = "AllTrans:SetTextHook" // Added for consistent logging

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
                Utils.debugLog("$TAG: Hook argument modified directly with text: [" + textToSet + "]")
            } catch (e: Exception) {
                Log.e(
                    "AllTrans",
                    "$TAG: Error modifying argument type " + argType.getName() + " with text [" + newText + "]",
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
                    Utils.debugLog("$TAG: Skipping translation for editable TextView: [" + stringArgs + "]")
                    if (PreferenceList.Scroll) configureScroll(textView)
                    return
                }
                if (!editable && PreferenceList.Scroll) configureScroll(textView)
            } catch (e: Throwable) {
                Log.w("AllTrans", "$TAG: Error checking editable/scroll: " + Log.getStackTraceString(e))
            }
        } else {
            Log.w(
                "AllTrans",
                "$TAG: Hooked object is not a TextView: " + param.thisObject.javaClass.getName()
            )
            return  // Só traduzir TextViews
        }

        val textViewHashCode = textView.hashCode()
        // Check if already pending (even before cache check, as pending implies it's being fetched)
        if (Alltrans.pendingTextViewTranslations.contains(textViewHashCode)) {
            Utils.debugLog("$TAG: Skipping translation for [" + stringArgs + "], already pending for TextView ($textViewHashCode)")
            return // Let original setText proceed, new text will be set by pending callback if different
        }

        // Otimização: Verificar se já está traduzido (e is the same as current text)
        var alreadyTranslatedAndSame = false
        if (PreferenceList.Caching) {
            Alltrans.cacheAccess.acquireUninterruptibly()
            try {
                val cacheRef = Alltrans.cache
                if (cacheRef != null && cacheRef.containsKey(stringArgs) && stringArgs == cacheRef[stringArgs]) {
                    Utils.debugLog("$TAG: Skipping processing, text already appears translated and is identical: [" + stringArgs + "]")
                    alreadyTranslatedAndSame = true
                }
            } finally {
                if (Alltrans.cacheAccess.availablePermits() == 0) {
                    Alltrans.cacheAccess.release()
                }
            }
        }
        if (alreadyTranslatedAndSame) {
            return // Let original setText proceed
        }

        // Pular Numéricos, URLs e Acrônimos
        if (NUMERIC_PATTERN.matcher(stringArgs).matches()) {
            Utils.debugLog("$TAG: Skipping translation for numeric string: [" + stringArgs + "]")
            return
        }
        if (stringArgs.length > 3 && URL_LIKE_PATTERN.matcher(stringArgs).matches() && !stringArgs.contains(" ")) {
            Utils.debugLog("$TAG: Skipping translation for URL-like string: [" + stringArgs + "]")
            return
        }
        if (stringArgs.length < 5 && ACRONYM_LIKE_PATTERN.matcher(stringArgs).matches()) {
            Utils.debugLog("$TAG: Skipping translation for likely acronym/code: [" + stringArgs + "]")
            return
        }

        Utils.debugLog("$TAG: Potential translation target: [" + stringArgs + "] for TextView (" + textViewHashCode + ")")

        var cachedTranslation: String? = null
        var translationRequested = false

        Alltrans.cacheAccess.acquireUninterruptibly()
        try {
            val cacheRef = Alltrans.cache
            if (PreferenceList.Caching && cacheRef != null && cacheRef.containsKey(stringArgs)) {
                cachedTranslation = cacheRef[stringArgs]
                if (cachedTranslation != null && cachedTranslation != stringArgs) {
                    Utils.debugLog("$TAG: Applying cached translation directly to args: [" + stringArgs + "] -> [" + cachedTranslation + "]")
                    modifyArgument(param, cachedTranslation)
                } else {
                    Utils.debugLog("$TAG: Invalid/same translation in cache for: [" + stringArgs + "], proceeding with original or fresh translation.")
                    cachedTranslation = null
                }
            }

            if (cachedTranslation == null && !Alltrans.pendingTextViewTranslations.contains(textViewHashCode)) {
                Utils.debugLog("$TAG: String not cached or invalid/same in cache. Requesting fresh translation for: [" + stringArgs + "] for TextView (" + textViewHashCode + ")")
                translationRequested = true

                if (PreferenceList.TranslatorProvider == "m" && PreferenceList.CurrentAppMicrosoftBatchEnabled) {
                    Utils.debugLog("$TAG: Using BatchTranslationManager for Microsoft provider (batching enabled) for TextView: $textViewHashCode, Text: \"${stringArgs.take(50)}...\"")
                    Alltrans.batchManager.addString(
                        text = stringArgs,
                        userData = textView,
                        originalCallable = null,
                        canCallOriginal = false
                    )
                    // pendingTextViewTranslations.add is handled by BatchTranslationManager for this case
                } else {
                    // Direct translation for non-Microsoft providers OR if MS batching is disabled for this app
                    val reason = if (PreferenceList.TranslatorProvider == "m") "(MS batching disabled)" else "(non-MS provider)"
                    Utils.debugLog("$TAG: Using direct translation $reason for TextView: $textViewHashCode, Text: \"${stringArgs.take(50)}...\"")

                    Alltrans.pendingTextViewTranslations.add(textViewHashCode)
                    Utils.debugLog("$TAG: Added TextView ($textViewHashCode) to pending set for direct translation.")

                    val getTranslate = GetTranslate()
                    getTranslate.stringToBeTrans = stringArgs
                    getTranslate.userData = textView
                    getTranslate.canCallOriginal = false

                    val getTranslateToken = GetTranslateToken()
                    getTranslateToken.getTranslate = getTranslate
                    getTranslateToken.doAll()
                }
            }
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }

        if (translationRequested) {
            Utils.debugLog("$TAG: Proceeding with original text for [" + stringArgs + "] while waiting for callback.")
        } else if (cachedTranslation != null) {
            Utils.debugLog("$TAG: Proceeding with cached (modified) translation for [" + stringArgs + "].")
        }
    }


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
                        val text = v.getText()
                        if (method != null && v.isFocused() && text is Spannable) {
                            return method.onTouchEvent(v, text, event)
                        }
                        return false
                    }
                })
            }
        } catch (e: Throwable) {
            Log.w("AllTrans", "$TAG: Failed to configure scrolling", e)
        }
    }

    companion object {
        private val NUMERIC_PATTERN: Pattern = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$")
        private val URL_LIKE_PATTERN: Pattern =
            Pattern.compile("^(http|https)://.*|[^\\s]+\\.[^\\s]+$")
        private val ACRONYM_LIKE_PATTERN: Pattern = Pattern.compile("^[A-Z0-9_\\-:]+$")

        fun isNotWhiteSpace(abc: String?): Boolean {
            return !(abc == null || "" == abc) && !abc.matches("^\\s*$".toRegex())
        }
    }
}