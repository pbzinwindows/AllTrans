/*
 * Copyright 2017 Akhil Kedia
 * This file is part of AllTrans.
 *
 * AllTrans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AllTrans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AllTrans. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package akhil.alltrans

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebView
import java.io.FileOutputStream
import kotlin.math.min

class VirtWebViewOnLoad(private val webViewInstance: WebView?) : OriginalCallable {
    // Controle mais rigoroso de traduções processadas
    private val processedTranslations = mutableMapOf<String, String>() // original -> translated
    private val pendingTranslations = mutableSetOf<String>() // textos sendo traduzidos
    private val webViewId = webViewInstance?.hashCode() ?: 0

    init {
        Utils.debugLog("VirtWebViewOnLoad: Instance created for WebView: $webViewId")
    }

    override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
        if (userData !is WebHookUserData2) {
            Utils.debugLog("VirtWebViewOnLoad: Error - userData is not WebHookUserData2")
            return
        }

        val webView = userData.webView
        if (webView == null) {
            Utils.debugLog("VirtWebViewOnLoad: Error - webView from userData is null")
            return
        }

        val originalText = userData.stringArgs
        val translatedText = translatedString?.toString()

        // Validações básicas
        if (originalText.isNullOrEmpty() || translatedText.isNullOrEmpty()) {
            Utils.debugLog("VirtWebViewOnLoad: Skipping - original or translated text is null/empty")
            markNodeAsTranslated(webView, originalText)
            pendingTranslations.remove(originalText)
            return
        }

        // Se a tradução é igual ao original, apenas marcar como traduzido
        if (translatedText == originalText) {
            Utils.debugLog("VirtWebViewOnLoad: Translation is same as original: [$originalText]")
            markNodeAsTranslated(webView, originalText)
            pendingTranslations.remove(originalText)
            return
        }

        // Verificar se já processamos esta tradução específica
        val existingTranslation = processedTranslations[originalText]
        if (existingTranslation != null) {
            if (existingTranslation == translatedText) {
                Utils.debugLog("VirtWebViewOnLoad: Translation already processed: [$originalText] -> [$translatedText]")
                pendingTranslations.remove(originalText)
                return
            } else {
                Utils.debugLog("VirtWebViewOnLoad: Different translation for same text - Original: [$originalText], Previous: [$existingTranslation], New: [$translatedText]")
            }
        }

        // Verificar duplicação no texto traduzido
        if (translatedText.contains(originalText) && translatedText != originalText) {
            val occurrences = translatedText.split(originalText).size - 1
            if (occurrences > 1) {
                Utils.debugLog("VirtWebViewOnLoad: Detected duplication in translation - Original: [$originalText], Translated: [$translatedText], Occurrences: $occurrences. Using original text.")
                markNodeAsTranslated(webView, originalText)
                pendingTranslations.remove(originalText)
                return
            }
        }

        // Registrar tradução processada
        processedTranslations[originalText] = translatedText
        pendingTranslations.remove(originalText)

        Utils.debugLog("VirtWebViewOnLoad: Applying translation - [$originalText] -> [$translatedText] in WebView $webViewId")

        // Aplicar tradução com script melhorado
        applyTranslation(webView, originalText, translatedText)
    }

    private fun applyTranslation(webView: WebView, originalText: String, translatedText: String) {
        val originalEscaped = escapeJsString(originalText)
        val translatedEscaped = escapeJsString(translatedText)
        val uniqueId = generateUniqueId(originalText)

        val script = """
            (function() {
                var AllTransPlaceholderTypes = {
                    'date': 0, 'datetime-local': 0, 'email': 0, 'month': 0,
                    'number': 0, 'password': 0, 'tel': 0, 'time': 0,
                    'url': 0, 'week': 0, 'search': 0, 'text': 0
                };
                var AllTransInputTypes = {
                    'button': 0, 'reset': 0, 'submit': 0
                };
                
                // Verificar se já foi processado com este ID único
                var processedKey = 'alltrans_${uniqueId}';
                if (window[processedKey]) {
                    console.log('AllTrans: Translation already applied for: ${originalText.take(20)}...');
                    return;
                }
                
                function getAllTextNodes(doc) {
                    var result = [];
                    var ignore = {'STYLE': 0, 'SCRIPT': 0, 'NOSCRIPT': 0, 'IFRAME': 0, 'OBJECT': 0, 'CODE': 0, 'PRE': 0};
                    
                    function scanTree(node) {
                        if (!node) return;
                        
                        // Pular nós já traduzidos
                        if (node.nodeType === 1 && node.hasAttribute('data-alltrans-translated')) return;
                        if (node.tagName && node.tagName in ignore) return;
                        
                        // Input elements
                        if (node.tagName && node.tagName.toLowerCase() === 'input') {
                            if ((node.type in AllTransInputTypes || node.type in AllTransPlaceholderTypes) && 
                                !node.hasAttribute('data-alltrans-translated')) {
                                result.push(node);
                            }
                        }
                        
                        // Processar filhos
                        if (node.childNodes && node.childNodes.length) {
                            for (var i = 0; i < node.childNodes.length; i++) {
                                scanTree(node.childNodes[i]);
                            }
                        } else if (node.nodeType === 3) {
                            // Text nodes
                            if (!node.parentElement || !node.parentElement.hasAttribute('data-alltrans-translated')) {
                                result.push(node);
                            }
                        }
                    }
                    
                    scanTree(doc);
                    return result;
                }
                
                function replaceText(nodes, original, translated) {
                    var replacedCount = 0;
                    var originalTrimmed = original.trim();
                    
                    for (var i = 0; i < nodes.length; i++) {
                        var node = nodes[i];
                        if (!node) continue;
                        
                        try {
                            var replaced = false;
                            var elementToMark = null;
                            
                            // Input placeholder
                            if (node.tagName && node.tagName.toLowerCase() === 'input' && 
                                node.type in AllTransPlaceholderTypes) {
                                if (node.placeholder && node.placeholder.trim() === originalTrimmed) {
                                    node.placeholder = translated;
                                    elementToMark = node;
                                    replaced = true;
                                }
                            }
                            // Input value
                            else if (node.tagName && node.tagName.toLowerCase() === 'input' && 
                                     node.type in AllTransInputTypes) {
                                if (node.value && node.value.trim() === originalTrimmed) {
                                    node.value = translated;
                                    elementToMark = node;
                                    replaced = true;
                                }
                            }
                            // Text node
                            else if (node.nodeType === 3 && node.nodeValue) {
                                if (node.nodeValue.trim() === originalTrimmed) {
                                    node.nodeValue = translated;
                                    elementToMark = node.parentElement;
                                    replaced = true;
                                }
                            }
                            
                            // Marcar como traduzido
                            if (replaced && elementToMark && elementToMark.setAttribute) {
                                elementToMark.setAttribute('data-alltrans-translated', 'true');
                                elementToMark.setAttribute('data-alltrans-original', original);
                                elementToMark.setAttribute('data-alltrans-id', '${uniqueId}');
                                replacedCount++;
                            }
                        } catch (e) {
                            console.error('AllTrans: Error replacing text:', e);
                        }
                    }
                    
                    return replacedCount;
                }
                
                var originalText = $originalEscaped;
                var translatedText = $translatedEscaped;
                var totalReplaced = 0;
                
                // Processar documento principal
                try {
                    var mainNodes = getAllTextNodes(document);
                    totalReplaced += replaceText(mainNodes, originalText, translatedText);
                } catch (e) {
                    console.error('AllTrans: Error processing main document:', e);
                }
                
                // Processar frames
                try {
                    for (var j = 0; j < window.frames.length; j++) {
                        try {
                            var frameDoc = window.frames[j].document;
                            if (frameDoc) {
                                var frameNodes = getAllTextNodes(frameDoc);
                                totalReplaced += replaceText(frameNodes, originalText, translatedText);
                            }
                        } catch (frameError) {
                            console.warn('AllTrans: Error accessing frame ' + j + ':', frameError);
                        }
                    }
                } catch (e) {
                    console.error('AllTrans: Error processing frames:', e);
                }
                
                // Marcar como processado
                if (totalReplaced > 0) {
                    window[processedKey] = true;
                    console.log('AllTrans: Replaced ' + totalReplaced + ' occurrences of: ' + originalText.substring(0, 30) + '...');
                }
            })();
        """.trimIndent()

        myEvaluateJavaScript(webView, script)
    }

    private fun generateUniqueId(text: String): String {
        // Gerar ID único baseado no hash do texto + timestamp
        val hash = text.hashCode().toString(36)
        val timestamp = (System.currentTimeMillis() % 100000).toString(36)
        return "${hash}_${timestamp}"
    }

    private fun escapeJsString(value: String?): String {
        if (value == null) return "null"

        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\u000c", "\\f")

        return "'$escaped'"
    }

    private fun markNodeAsTranslated(webView: WebView?, originalText: String?) {
        if (webView == null || originalText == null) return

        Utils.debugLog("VirtWebViewOnLoad: Marking nodes as translated: [$originalText] in WebView $webViewId")
        val originalEscaped = escapeJsString(originalText)
        val uniqueId = generateUniqueId(originalText)

        val script = """
            (function() {
                var processedKey = 'alltrans_mark_${uniqueId}';
                if (window[processedKey]) return;
                
                function markNodes(doc) {
                    var walker = document.createTreeWalker(doc, NodeFilter.SHOW_ALL);
                    var node;
                    var originalTrimmed = $originalEscaped.trim();
                    var markedCount = 0;
                    
                    while (node = walker.nextNode()) {
                        try {
                            if (node.nodeType === 1 && node.hasAttribute('data-alltrans-translated')) continue;
                            
                            var shouldMark = false;
                            var elementToMark = null;
                            
                            if (node.tagName && node.tagName.toLowerCase() === 'input') {
                                if (node.placeholder && node.placeholder.trim() === originalTrimmed) {
                                    shouldMark = true;
                                    elementToMark = node;
                                } else if (node.value && node.value.trim() === originalTrimmed) {
                                    shouldMark = true;
                                    elementToMark = node;
                                }
                            } else if (node.nodeType === 3 && node.nodeValue && node.nodeValue.trim() === originalTrimmed) {
                                shouldMark = true;
                                elementToMark = node.parentElement;
                            }
                            
                            if (shouldMark && elementToMark && elementToMark.setAttribute) {
                                elementToMark.setAttribute('data-alltrans-translated', 'true');
                                elementToMark.setAttribute('data-alltrans-original', $originalEscaped);
                                elementToMark.setAttribute('data-alltrans-id', '${uniqueId}');
                                markedCount++;
                            }
                        } catch (e) {
                            console.error('AllTrans: Error marking node:', e);
                        }
                    }
                    
                    return markedCount;
                }
                
                var totalMarked = 0;
                
                // Marcar no documento principal
                try {
                    totalMarked += markNodes(document);
                } catch (e) {
                    console.error('AllTrans: Error marking main document:', e);
                }
                
                // Marcar em frames
                try {
                    for (var j = 0; j < window.frames.length; j++) {
                        try {
                            var frameDoc = window.frames[j].document;
                            if (frameDoc) {
                                totalMarked += markNodes(frameDoc);
                            }
                        } catch (frameError) {
                            console.warn('AllTrans: Error marking frame ' + j + ':', frameError);
                        }
                    }
                } catch (e) {
                    console.error('AllTrans: Error marking frames:', e);
                }
                
                if (totalMarked > 0) {
                    window[processedKey] = true;
                    console.log('AllTrans: Marked ' + totalMarked + ' nodes for: ' + $originalEscaped.substring(0, 30) + '...');
                }
            })();
        """.trimIndent()

        myEvaluateJavaScript(webView, script)
    }

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    @Throws(Throwable::class)
    fun afterOnLoadMethod(webView: WebView) {
        Utils.debugLog("VirtWebViewOnLoad: afterOnLoadMethod ENTERED for WebView: $webViewId")

        // Limpar estado quando página carrega
        processedTranslations.clear()
        pendingTranslations.clear()

        val scriptFrames = "console.log('AllTrans: Frames count: ' + window.frames.length)"
        myEvaluateJavaScript(webView, scriptFrames)

        val initScript = """
            console.log('AllTrans: Initializing...');
            
            // Limpar marcadores antigos
            if (window.AllTransProcessedTexts) {
                window.AllTransProcessedTexts.clear();
            }
            window.AllTransProcessedTexts = new Set();
            
            var AllTransPlaceholderTypes = {
                'date': 0, 'datetime-local': 0, 'email': 0, 'month': 0,
                'number': 0, 'password': 0, 'tel': 0, 'time': 0,
                'url': 0, 'week': 0, 'search': 0, 'text': 0
            };
            var AllTransInputTypes = {
                'button': 0, 'reset': 0, 'submit': 0
            };
            
            // Regex patterns para filtrar textos desnecessários
            var AllTransNumericRegex = /^[+-]?\d+(\.\d+)?$/;
            var AllTransUrlLikeRegex = /^(http|https):\/\/.*|[^\s]+\.[^\s]+$/;
            var AllTransAcronymRegex = /^[A-Z0-9_\-:]+$/;
            
            function getAllTextNodes(doc) {
                var result = [];
                var ignore = {'STYLE': 0, 'SCRIPT': 0, 'NOSCRIPT': 0, 'IFRAME': 0, 'OBJECT': 0, 'CODE': 0, 'PRE': 0};
                
                function scanTree(node) {
                    if (!node) return;
                    if (node.nodeType === 1 && node.hasAttribute('data-alltrans-translated')) return;
                    if (node.tagName && node.tagName in ignore) return;
                    
                    if (node.tagName && node.tagName.toLowerCase() === 'input' && 
                        (node.type in AllTransInputTypes || node.type in AllTransPlaceholderTypes)) {
                        if (!node.hasAttribute('data-alltrans-translated')) {
                            result.push(node);
                        }
                    }
                    
                    if (node.childNodes && node.childNodes.length) {
                        for (var i = 0; i < node.childNodes.length; i++) {
                            scanTree(node.childNodes[i]);
                        }
                    } else if (node.nodeType === 3) {
                        if (!node.parentElement || !node.parentElement.hasAttribute('data-alltrans-translated')) {
                            result.push(node);
                        }
                    }
                }
                
                scanTree(doc);
                return result;
            }
            
            function processNodes(nodes) {
                var textsToTranslate = new Set();
                
                for (var i = 0; i < nodes.length; i++) {
                    var node = nodes[i];
                    if (!node) continue;
                    
                    try {
                        var text = null;
                        
                        if (node.tagName && node.tagName.toLowerCase() === 'input') {
                            if (node.type in AllTransPlaceholderTypes) {
                                text = node.placeholder;
                            } else if (node.type in AllTransInputTypes) {
                                text = node.value;
                            }
                        } else if (node.nodeType === 3) {
                            text = node.nodeValue;
                        }
                        
                        if (text && text.trim() !== '') {
                            var trimmed = text.trim();
                            
                            // Verificar se já foi processado
                            if (window.AllTransProcessedTexts.has(trimmed)) continue;
                            
                            // Filtros de qualidade
                            if (trimmed.length < 2) continue;
                            if (AllTransNumericRegex.test(trimmed)) continue;
                            if (trimmed.length > 5 && AllTransUrlLikeRegex.test(trimmed) && trimmed.indexOf(' ') === -1) continue;
                            if (trimmed.length < 6 && AllTransAcronymRegex.test(trimmed)) continue;
                            
                            // Adicionar ao conjunto de processados
                            window.AllTransProcessedTexts.add(trimmed);
                            textsToTranslate.add(text);
                        }
                    } catch (e) {
                        console.error('AllTrans: Error processing node:', e);
                    }
                }
                
                // Enviar textos para tradução
                textsToTranslate.forEach(function(text) {
                    try {
                        injectedObject.showLog(text);
                    } catch (e) {
                        console.error('AllTrans: Error calling showLog:', e);
                    }
                });
            }
            
            function doScan() {
                if (window.AllTransScanning) return;
                window.AllTransScanning = true;
                
                console.log('AllTrans: Starting scan...');
                
                try {
                    // Processar documento principal
                    var mainNodes = getAllTextNodes(document);
                    processNodes(mainNodes);
                    
                    // Processar frames
                    for (var j = 0; j < window.frames.length; j++) {
                        try {
                            var frameDoc = window.frames[j].document;
                            if (frameDoc) {
                                var frameNodes = getAllTextNodes(frameDoc);
                                processNodes(frameNodes);
                            }
                        } catch (frameError) {
                            console.warn('AllTrans: Error scanning frame ' + j + ':', frameError);
                        }
                    }
                } catch (e) {
                    console.error('AllTrans: Error during scan:', e);
                } finally {
                    window.AllTransScanning = false;
                }
                
                console.log('AllTrans: Scan completed');
            }
            
            // Configurar observador de mutações
            if (!window.AllTransObserver) {
                console.log('AllTrans: Setting up MutationObserver');
                
                var MutationObserver = window.MutationObserver || window.WebKitMutationObserver;
                if (MutationObserver) {
                    window.AllTransObserver = new MutationObserver(function(mutations) {
                        if (window.AllTransTimeout) {
                            clearTimeout(window.AllTransTimeout);
                        }
                        window.AllTransTimeout = setTimeout(doScan, 500);
                    });
                    
                    try {
                        window.AllTransObserver.observe(document, {
                            subtree: true,
                            childList: true,
                            characterData: true
                        });
                        console.log('AllTrans: MutationObserver started');
                    } catch (e) {
                        console.error('AllTrans: Error starting MutationObserver:', e);
                    }
                } else {
                    console.warn('AllTrans: MutationObserver not supported');
                }
            }
            
            // Executar scan inicial
            setTimeout(doScan, ${PreferenceList.DelayWebView});
            console.log('AllTrans: Initialization complete');
        """.trimIndent()

        Utils.debugLog("VirtWebViewOnLoad: Injecting initialization script...")
        myEvaluateJavaScript(webView, initScript)
        Utils.debugLog("VirtWebViewOnLoad: afterOnLoadMethod COMPLETED for WebView: $webViewId")
    }

    @Suppress("unused")
    @JavascriptInterface
    fun showLog(stringArgs: String?) {
        if (stringArgs.isNullOrBlank()) return

        if (webViewInstance == null) {
            Utils.debugLog("VirtWebViewOnLoad: showLog - webViewInstance is null for text: [$stringArgs]")
            return
        }

        if (!SetTextHookHandler.Companion.isNotWhiteSpace(stringArgs)) return

        // Verificar se já está sendo processado
        if (pendingTranslations.contains(stringArgs)) {
            Utils.debugLog("VirtWebViewOnLoad: showLog - Text already being processed: [$stringArgs]")
            return
        }

        // Verificar se já foi traduzido
        if (processedTranslations.containsKey(stringArgs)) {
            Utils.debugLog("VirtWebViewOnLoad: showLog - Text already translated: [$stringArgs]")
            return
        }

        Utils.debugLog("VirtWebViewOnLoad: showLog received: [$stringArgs] for WebView $webViewId")

        // Marcar como pendente
        pendingTranslations.add(stringArgs)

        val userData = WebHookUserData2(webViewInstance, stringArgs)
        val getTranslate = GetTranslate().apply {
            stringToBeTrans = stringArgs
            originalCallable = this@VirtWebViewOnLoad
            this.userData = userData
            canCallOriginal = true
        }

        var needsTranslation = true

        // Verificar cache
        if (PreferenceList.Caching) {
            Alltrans.Companion.cacheAccess.acquireUninterruptibly()
            try {
                val cachedValue = Alltrans.Companion.cache?.get(stringArgs)
                if (cachedValue != null) {
                    if (cachedValue != stringArgs) {
                        Utils.debugLog("VirtWebViewOnLoad: Found translation in cache: [$stringArgs] -> [$cachedValue]")
                        Handler(Looper.getMainLooper()).postDelayed({
                            callOriginalMethod(cachedValue, userData)
                        }, PreferenceList.Delay.toLong())
                        needsTranslation = false
                    } else {
                        Utils.debugLog("VirtWebViewOnLoad: Found same text in cache, marking as translated: [$stringArgs]")
                        markNodeAsTranslated(webViewInstance, stringArgs)
                        pendingTranslations.remove(stringArgs)
                        needsTranslation = false
                    }
                }
            } finally {
                if (Alltrans.Companion.cacheAccess.availablePermits() == 0) {
                    Alltrans.Companion.cacheAccess.release()
                }
            }
        }

        if (needsTranslation) {
            Utils.debugLog("VirtWebViewOnLoad: Requesting translation for: [$stringArgs]")
            val token = GetTranslateToken().apply {
                this.getTranslate = getTranslate
            }
            token.doAll()
        }
    }

    @Suppress("unused")
    @JavascriptInterface
    fun WriteHTML(html: String) {
        val context = Alltrans.Companion.context ?: return

        try {
            context.openFileOutput("AllTransWebViewDebug.html", Context.MODE_PRIVATE).use { output ->
                output.write(html.toByteArray())
            }
            Utils.debugLog("VirtWebViewOnLoad: HTML snapshot saved")
        } catch (e: Throwable) {
            Utils.debugLog("VirtWebViewOnLoad: Error writing HTML: $e")
        }
    }

    private fun myEvaluateJavaScript(webView: WebView?, script: String) {
        if (webView == null) {
            Utils.debugLog("VirtWebViewOnLoad: myEvaluateJavaScript - WebView is null!")
            return
        }

        val preview = script.take(100)
        Utils.debugLog("VirtWebViewOnLoad: Evaluating JS for WebView $webViewId: $preview...")

        webView.post {
            try {
                webView.evaluateJavascript(script) { result ->
                    // Log opcional do resultado
                    if (result != null && result != "null" && result.isNotEmpty()) {
                        // Utils.debugLog("VirtWebViewOnLoad: JS result: $result")
                    }
                }
            } catch (e: Exception) {
                Utils.debugLog("VirtWebViewOnLoad: Exception during evaluateJavascript: ${e.message}")
            }
        }
    }
}

// Classe auxiliar
internal class WebHookUserData2(val webView: WebView?, val stringArgs: String?)