package akhil.alltrans

import android.webkit.WebView

object VirtWebViewOnLoad {

    /**
     * Função para escapar strings de forma segura para uso em literais JavaScript.
     * Preserva quebras de linha, tabs, espaços e outros caracteres especiais.
     */
    private fun escapeJsString(value: String?): String {
        if (value == null) {
            return "null"
        }
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

    /**
     * Executa o script JavaScript na WebView.
     */
    private fun myEvaluateJavaScript(webView: WebView, script: String) {
        webView.post {
            webView.evaluateJavascript(script) { result ->
                // Se necessário, você pode adicionar logging extra aqui:
                // Utils.debugLog("Resultado do script: $result")
            }
        }
    }

    /**
     * Marca os nós que contêm o texto original como já traduzidos,
     * impedindo novas substituições futuras.
     */
    private fun markNodeAsTranslated(webView: WebView?, originalText: String?) {
        if (webView == null || originalText == null) return

        Utils.debugLog("VirtWebViewOnLoad: Marcando nós com o texto original [$originalText] como traduzidos na WebView ${webView.hashCode()}")
        val originalEscapedForJS = escapeJsString(originalText)

        val script = """
            var AllTransPlaceholderTypes = { 
                'date': 0, 'datetime-local': 0, 'email': 0, 'month': 0, 
                'number': 0, 'password': 0, 'tel': 0, 'time': 0, 
                'url': 0, 'week': 0, 'search': 0, 'text': 0 
            };

            function allTransGetAllTextNodes(doc) {
                var result = [];
                (function scanSubTree(node) {
                    if (!node) return;
                    if (node.childNodes && node.childNodes.length) {
                        for (var i = 0; i < node.childNodes.length; i++) {
                            scanSubTree(node.childNodes[i]);
                        }
                    } else if (node.nodeType === 3) {
                        if (!node.parentElement || !node.parentElement.hasAttribute('data-Alltrans-translated')) {
                            result.push(node);
                        }
                    }
                })(doc);
                return result;
            }

            function allTransDoReplaceAllFinal(nodes, originalText, translatedText) {
                if (!nodes) return;
                var replacedCount = 0;
                var originalTrimmed = originalText.trim();
                for (var i = 0; i < nodes.length; i++) {
                    var currentNode = nodes[i];
                    try {
                        var textContent = currentNode.textContent;
                        if (textContent && textContent.trim() === originalTrimmed) {
                            currentNode.textContent = translatedText;
                            if (currentNode.parentElement && typeof currentNode.parentElement.setAttribute === 'function') {
                                currentNode.parentElement.setAttribute('data-Alltrans-translated', 'true');
                            }
                            replacedCount++;
                        }
                    } catch (e) { 
                        console.error('AllTrans JS Error replacing node:', e);
                    }
                }
                console.log('AllTrans JS Replace: Finalizado para original: "' + originalText + '", nós substituídos: ' + replacedCount);
            }

            try {
                var originalTextEscaped = $originalEscapedForJS;
                var nodes = allTransGetAllTextNodes(window.document);
                allTransDoReplaceAllFinal(nodes, originalTextEscaped, originalTextEscaped);
            } catch (e) { 
                console.error('AllTrans JS Error ao marcar nós como traduzidos:', e);
            }
        """.trimIndent()

        myEvaluateJavaScript(webView, script)
    }

    /**
     * Função principal para injetar o script de substituição do texto traduzido.
     * Se o texto traduzido for nulo ou igual ao original, apenas marca os nós como traduzidos.
     */
    fun callOriginalMethod(webView: WebView?, originalUnescaped: String?, translatedValue: String?) {
        if (webView == null || originalUnescaped == null || translatedValue == null || translatedValue == originalUnescaped) {
            Utils.debugLog("VirtWebViewOnLoad: Pulando injeção JS em callOriginalMethod - texto traduzido nulo ou igual ao original.")
            markNodeAsTranslated(webView, originalUnescaped)
            return
        }

        Utils.debugLog("VirtWebViewOnLoad: callOriginalMethod - Injetando JS para substituir [$originalUnescaped] por [$translatedValue] na WebView ${webView.hashCode()}")

        val originalEscapedForJS = escapeJsString(originalUnescaped)
        val translatedEscapedForJS = escapeJsString(translatedValue)

        val script = """
            var AllTransPlaceholderTypes = { 
                'date': 0, 'datetime-local': 0, 'email': 0, 'month': 0, 
                'number': 0, 'password': 0, 'tel': 0, 'time': 0, 
                'url': 0, 'week': 0, 'search': 0, 'text': 0 
            };

            function allTransGetAllTextNodes(doc) {
                var result = [];
                (function scanSubTree(node) {
                    if (!node) return;
                    if (node.childNodes && node.childNodes.length) {
                        for (var i = 0; i < node.childNodes.length; i++) {
                            scanSubTree(node.childNodes[i]);
                        }
                    } else if (node.nodeType === 3) {
                        if (!node.parentElement || !node.parentElement.hasAttribute('data-Alltrans-translated')) {
                            result.push(node);
                        }
                    }
                })(doc);
                return result;
            }

            function allTransDoReplaceAllFinal(nodes, originalText, translatedText) {
                if (!nodes) return;
                var replacedCount = 0;
                var originalTrimmed = originalText.trim();
                for (var i = 0; i < nodes.length; i++) {
                    var currentNode = nodes[i];
                    try {
                        var textContent = currentNode.textContent;
                        if (textContent && textContent.trim() === originalTrimmed) {
                            currentNode.textContent = translatedText;
                            if (currentNode.parentElement && typeof currentNode.parentElement.setAttribute === 'function') {
                                currentNode.parentElement.setAttribute('data-Alltrans-translated', 'true');
                            }
                            replacedCount++;
                        }
                    } catch (e) { 
                        console.error('AllTrans JS Error replacing node:', e);
                    }
                }
                console.log('AllTrans JS Replace: Finalizado para original: "' + originalText + '", nós substituídos: ' + replacedCount);
            }

            try {
                var originalToMatch = $originalEscapedForJS;
                var translatedValue = $translatedEscapedForJS;
                var nodes = allTransGetAllTextNodes(window.document);
                allTransDoReplaceAllFinal(nodes, originalToMatch, translatedValue);
            } catch (e) { 
                console.error('AllTrans JS Error ao substituir nós do documento principal:', e);
            }
        """.trimIndent()

        myEvaluateJavaScript(webView, script)
    }
}
