package akhil.alltrans

import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView

class DynamicTextWatcher(private val textView: TextView) : TextWatcher {
    private var isTranslating = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

    override fun afterTextChanged(s: Editable?) {
        if (isTranslating || s.isNullOrEmpty()) return

        val originalText = s.toString()
        if (!SetTextHookHandler.isNotWhiteSpace(originalText)) return

        // Verifica se o texto já foi traduzido
        val appliedTag = textView.getTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY)
        if (appliedTag == true) {
            textView.setTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY, false)
            return
        }

        // Verifica se o AllTrans está habilitado para este app
        val packageName = textView.context?.packageName
        if (!PreferenceManager.isEnabledForPackage(textView.context, packageName)) return

        // Evita retradução do mesmo texto
        val pendingTag = textView.getTag(Alltrans.ALLTRANS_PENDING_TRANSLATION_TAG_KEY) as? String
        if (pendingTag == originalText) return

        isTranslating = true
        textView.setTag(Alltrans.ALLTRANS_PENDING_TRANSLATION_TAG_KEY, originalText)

        // Solicita tradução
        val compositeKey = 31 * textView.hashCode() + originalText.hashCode()
        val cachedTranslation = Alltrans.cache?.get(originalText)
        if (cachedTranslation != null && cachedTranslation != originalText) {
            applyTranslation(cachedTranslation)
        } else {
            requestTranslation(originalText, compositeKey)
        }
    }

    private fun applyTranslation(translatedText: String) {
        textView.setTag(Alltrans.ALLTRANS_TRANSLATION_APPLIED_TAG_KEY, true)
        textView.text = translatedText
        isTranslating = false
    }

    private fun requestTranslation(text: String, compositeKey: Int) {
        if (PreferenceList.TranslatorProvider == "m" && PreferenceList.CurrentAppMicrosoftBatchEnabled) {
            Alltrans.batchManager.addString(
                text = text,
                userData = textView,
                originalCallable = null,
                canCallOriginal = false,
                compositeKey = compositeKey
            )
        } else {
            val getTranslate = GetTranslate().apply {
                stringToBeTrans = text
                userData = textView
                originalCallable = null
                canCallOriginal = false
                pendingCompositeKey = compositeKey
            }
            GetTranslateToken().apply { this.getTranslate = getTranslate }.doAll()
        }
    }
}