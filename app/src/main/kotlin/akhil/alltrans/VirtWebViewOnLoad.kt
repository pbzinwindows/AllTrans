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
    init {
        Utils.debugLog("VirtWebViewOnLoad: Instance created for WebView: " + (if (webViewInstance != null) webViewInstance.hashCode() else "null"))
    }

    // Filtros específicos para conteúdo web
    private fun isWebContentToSkip(text: String): Boolean {
        // CSS classes e IDs
        if (text.startsWith(".") || text.startsWith("#")) return true

        // Atributos HTML comuns
        val htmlAttributes = listOf("onclick", "onload", "href", "src", "alt", "title", "class", "id", "style", "data-")
        if (htmlAttributes.any { text.startsWith("$it=") }) return true

        // JavaScript keywords
        val jsKeywords = listOf("function", "var", "let", "const", "return", "if", "else", "for", "while", "document", "window", "console")
        if (jsKeywords.contains(text.lowercase())) return true

        // Meta tags content
        if (text.contains("charset") || text.contains("viewport")) return true

        // URLs e caminhos
        if (text.startsWith("http") || text.startsWith("ftp") || text.startsWith("www.") || text.startsWith("/")) return true

        // Código JavaScript inline
        if (text.contains("function(") || text.contains("return ") || text.contains("console.") || text.contains("document.")) return true

        // JSON-like content
        if ((text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]"))) return true

        // CSS units e cores
        if (text.matches(Regex("^#[0-9A-Fa-f]{3,8}$")) || text.matches(Regex("^\\d+\\.?\\d*(px|em|rem|vh|vw|%|pt)$"))) return true

        // Extensões de arquivo
        if (text.matches(Regex(".*\\.(js|css|html|htm|xml|json|svg|png|jpg|jpeg|gif|webp|ico|pdf)$", RegexOption.IGNORE_CASE))) return true

        // Base64 ou hashes
        if (text.matches(Regex("^[A-Za-z0-9+/]{20,}={0,2}$")) || text.matches(Regex("^[a-f0-9]{8,64}$"))) return true

        return false
    }

    override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
        if (userData !is WebHookUserData2) {
            Utils.debugLog("VirtWebViewOnLoad: Error in callOriginalMethod - userData is not WebHookUserData2")
            return
        }
        val webHookUserData2 = userData
        val webView = webHookUserData2.webView
        if (webView == null) {
            Utils.debugLog("VirtWebViewOnLoad: Error in callOriginalMethod - webView from userData is null")
            return
        }

        val originalUnescaped = webHookUserData2.stringArgs
        val translatedValue = translatedString.toString() // Usa valor não escapado para lógica Java

        if (translatedValue == null || originalUnescaped == null || translatedValue == originalUnescaped) {
            Utils.debugLog("VirtWebViewOnLoad: Skipping JS injection in callOriginalMethod - translated string is null or same as original.")
            markNodeAsTranslated(webView, originalUnescaped)
            return
        }

        Utils.debugLog("VirtWebViewOnLoad: callOriginalMethod - Injecting JS to replace [" + originalUnescaped + "] with [" + translatedValue + "] in WebView " + webView.hashCode())

        // Escapa as strings *apenas* para inserção segura no literal JS
        val originalEscapedForJS = escapeJsString(originalUnescaped)
        val translatedEscapedForJS = escapeJsString(translatedValue)

        // Script JS de Substituição (com marcação data-Alltrans-translated)
        val script = (""
                + "var AllTransPlaceholderTypes = {\n"
                + "    'date': 0, 'datetime-local': 0, 'email': 0, 'month': 0,\n"
                + "    'number': 0, 'password': 0, 'tel': 0, 'time': 0,\n"
                + "    'url': 0, 'week': 0, 'search': 0, 'text': 0\n"
                + "};\n"
                + "var AllTransInputTypes = {\n"
                + "  'button': 0, 'reset': 0, 'submit': 0\n"
                + "};\n"
                + "\n"
                + "function allTransGetAllTextNodes(tempDocument) {\n" // Função de busca inalterada nesta parte
                + "  var result = [];\n"
                + "  var ignore = {'STYLE': 0, 'SCRIPT': 0, 'NOSCRIPT': 0, 'IFRAME': 0, 'OBJECT': 0};\n"
                + "  (function scanSubTree(node) {\n"
                + "    if (!node) return;\n"
                + "    if (node.nodeType === 1 && node.hasAttribute('data-Alltrans-translated')) { return; }\n"
                + "    if (node.tagName && node.tagName in ignore) { return; }\n"
                + "    if (node.tagName && node.tagName.toLowerCase() == 'input' && (node.type in AllTransInputTypes || node.type in AllTransPlaceholderTypes)) {\n"
                + "      if (!node.hasAttribute('data-Alltrans-translated')) { result.push(node); }\n"
                + "    }\n"
                + "    if (node.childNodes && node.childNodes.length) {\n"
                + "      for (var i = 0; i < node.childNodes.length; i++) { scanSubTree(node.childNodes[i]); }\n"
                + "    } else if (node.nodeType == 3) {\n"
                + "      if (!node.parentElement || !node.parentElement.hasAttribute('data-Alltrans-translated')) { result.push(node); }\n"
                + "    }\n"
                + "  })(tempDocument);\n"
                + "  return result;\n"
                + "}\n"
                + "\n" // --- Função JS de Substituição Modificada (com marcação) ---
                + "function allTransDoReplaceAllFinal(all, originalText, translatedText) {\n"
                + "  if (!all) return;\n"
                + "  var replacedCount = 0;\n"
                + "  var originalTrimmed = originalText.trim();\n"
                + "  console.log('AllTrans JS Replace: Target Original (Trimmed): \"' + originalTrimmed + '\"');\n"
                + "  for (var i = 0, max = all.length; i < max; i++) {\n"
                + "    var currentNode = all[i];\n"
                + "    if (!currentNode) continue;\n"
                + "    try {\n"
                + "      var elementToMark = null;\n"
                + "      var currentValue = null;\n"
                + "      if (currentNode.tagName && currentNode.tagName.toLowerCase() == 'input' && currentNode.type in AllTransPlaceholderTypes) {\n"
                + "        currentValue = currentNode.placeholder;\n"
                + "        if (currentValue && currentValue.trim() === originalTrimmed) {\n"
                + "          console.log('AllTrans JS Replace: Match found in placeholder!');\n"
                + "          currentNode.placeholder = translatedText;\n"
                + "          elementToMark = currentNode;\n"
                + "          replacedCount++;\n"
                + "        }\n"
                + "      } else if (currentNode.nodeType == 3 && currentNode.nodeValue) {\n"
                + "        currentValue = currentNode.nodeValue;\n"
                + "        if (currentValue.trim() === originalTrimmed) {\n"
                + "          console.log('AllTrans JS Replace: Match found in text node!');\n"
                + "          currentNode.nodeValue = translatedText;\n"
                + "          elementToMark = currentNode.parentElement;\n"
                + "          replacedCount++;\n"
                + "        }\n"
                + "      } else if (currentNode.tagName && currentNode.tagName.toLowerCase() == 'input' && currentNode.type in AllTransInputTypes) {\n"
                + "        currentValue = currentNode.value;\n"
                + "        if (currentValue && currentValue.trim() === originalTrimmed) {\n"
                + "          console.log('AllTrans JS Replace: Match found in input value!');\n"
                + "          currentNode.value = translatedText;\n"
                + "          elementToMark = currentNode;\n"
                + "          replacedCount++;\n"
                + "        }\n"
                + "      }\n" // --- CORREÇÃO DA ASPAS ---
                + "      // Adiciona o atributo se um elemento foi modificado e é válido\n"
                + "      if (elementToMark && typeof elementToMark.setAttribute === 'function') {\n"
                + "          elementToMark.setAttribute('data-Alltrans-translated', 'true');\n"
                + "      }\n"
                + "    } catch (e) { console.error('AllTrans JS Error replacing node:', e); }\n"
                + "  }\n"
                + "  console.log('AllTrans JS Replace: Finished attempt for original: \"' + originalText + '\", replaced nodes: ' + replacedCount);\n"
                + "}\n"
                + "\n"
                + "var originalToMatch = " + originalEscapedForJS + ";\n" // --- CORREÇÃO DO PONTO E VÍRGULA ---
                + "var translatedValue = " + translatedEscapedForJS + ";\n"
                + "\n"
                + "try {\n"
                + "  for (var j = 0; j < window.frames.length; j++) {\n"
                + "    try {\n"
                + "      var frameDoc = window.frames[j].document;\n"
                + "      if (frameDoc) {\n"
                + "        var allFrameNodes = allTransGetAllTextNodes(frameDoc);\n"
                + "        allTransDoReplaceAllFinal(allFrameNodes, originalToMatch, translatedValue);\n"
                + "      }\n"
                + "    } catch (frameError) { console.warn('AllTrans JS Error accessing frame ' + j + ':', frameError); }\n"
                + "  }\n"
                + "} catch (e) { console.error('AllTrans JS Error iterating frames:', e); }\n"
                + "\n"
                + "try {\n"
                + "  var allMainNodes = allTransGetAllTextNodes(window.document);\n"
                + "  allTransDoReplaceAllFinal(allMainNodes, originalToMatch, translatedValue);\n"
                + "} catch (e) { console.error('AllTrans JS Error replacing main document nodes:', e); }\n") // Ponto e vírgula final opcional

        myEvaluateJavaScript(webView, script)
    }

    // Função auxiliar para escapar strings para uso DENTRO de literais JS
    private fun escapeJsString(value: String?): String {
        if (value == null) {
            return "null"
        }
        val escaped = value.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\u000c", "\\f")
        return "'" + escaped + "'"
    }

    // --- Nova função auxiliar para marcar nós como traduzidos ---
    private fun markNodeAsTranslated(webView: WebView?, originalText: String?) {
        if (webView == null || originalText == null) return
        Utils.debugLog("VirtWebViewOnLoad: Marking nodes with original text [" + originalText + "] as translated in WebView " + webView.hashCode())
        val originalEscapedForJS = escapeJsString(originalText)

        val script = (""
                + "var AllTransPlaceholderTypes = { 'date': 0, 'datetime-local': 0, 'email': 0, 'month': 0, 'number': 0, 'password': 0, 'tel': 0, 'time': 0, 'url': 0, 'week': 0, 'search': 0, 'text': 0 };\n"
                + "var AllTransInputTypes = { 'button': 0, 'reset': 0, 'submit': 0 };\n"
                + "\n"
                + "function allTransGetAllTextNodes(tempDocument) {\n"
                + "  var result = [];\n"
                + "  var ignore = {'STYLE': 0, 'SCRIPT': 0, 'NOSCRIPT': 0, 'IFRAME': 0, 'OBJECT': 0};\n"
                + "  (function scanSubTree(node) {\n"
                + "    if (!node) return;\n"
                + "    if (node.nodeType === 1 && node.hasAttribute('data-Alltrans-translated')) { return; }\n"
                + "    if (node.tagName && node.tagName in ignore) { return; }\n"
                + "    if (node.tagName && node.tagName.toLowerCase() == 'input' && (node.type in AllTransInputTypes || node.type in AllTransPlaceholderTypes)) {\n"
                + "      if (!node.hasAttribute('data-Alltrans-translated')) { result.push(node); }\n"
                + "    }\n"
                + "    if (node.childNodes && node.childNodes.length) {\n"
                + "      for (var i = 0; i < node.childNodes.length; i++) { scanSubTree(node.childNodes[i]); }\n"
                + "    } else if (node.nodeType == 3) {\n"
                + "      if (!node.parentElement || !node.parentElement.hasAttribute('data-Alltrans-translated')) { result.push(node); }\n"
                + "    }\n"
                + "  })(tempDocument);\n"
                + "  return result;\n"
                + "}\n"
                + "\n"
                + "function allTransMarkNodes(all, originalText) {\n"
                + "  if (!all) return;\n"
                + "  var markedCount = 0;\n"
                + "  var originalTrimmed = originalText.trim();\n"
                + "  for (var i = 0, max = all.length; i < max; i++) {\n"
                + "    var currentNode = all[i];\n"
                + "    if (!currentNode) continue;\n"
                + "    try {\n"
                + "      var elementToMark = null;\n"
                + "      var currentValue = null;\n"
                + "      if (currentNode.tagName && currentNode.tagName.toLowerCase() == 'input' && currentNode.type in AllTransPlaceholderTypes) {\n"
                + "        currentValue = currentNode.placeholder;\n"
                + "        if (currentValue && currentValue.trim() === originalTrimmed) { elementToMark = currentNode; }\n"
                + "      } else if (currentNode.nodeType == 3 && currentNode.nodeValue) {\n"
                + "        currentValue = currentNode.nodeValue;\n"
                + "        if (currentValue.trim() === originalTrimmed) { elementToMark = currentNode.parentElement; }\n"
                + "      } else if (currentNode.tagName && currentNode.tagName.toLowerCase() == 'input' && currentNode.type in AllTransInputTypes) {\n"
                + "        currentValue = currentNode.value;\n"
                + "        if (currentValue && currentValue.trim() === originalTrimmed) { elementToMark = currentNode; }\n"
                + "      }\n"
                + "      if (elementToMark && typeof elementToMark.setAttribute === 'function') {\n"
                + "          elementToMark.setAttribute('data-Alltrans-translated', 'true');\n"
                + "          markedCount++;\n"
                + "      }\n"
                + "    } catch (e) { console.error('AllTrans JS Error marking node:', e); }\n"
                + "  }\n"
                + "  console.log('AllTrans JS Mark: Finished attempt for original: \"' + originalText + '\", marked nodes: ' + markedCount);\n"
                + "}\n"
                + "\n"
                + "var originalToMatch = " + originalEscapedForJS + ";\n"
                + "\n"
                + "try {\n"
                + "  for (var j = 0; j < window.frames.length; j++) {\n"
                + "    try {\n"
                + "      var frameDoc = window.frames[j].document;\n"
                + "      if (frameDoc) {\n"
                + "        var allFrameNodes = allTransGetAllTextNodes(frameDoc);\n"
                + "        allTransMarkNodes(allFrameNodes, originalToMatch);\n"
                + "      }\n"
                + "    } catch (frameError) { console.warn('AllTrans JS Error accessing frame ' + j + ' for marking:', frameError); }\n"
                + "  }\n"
                + "} catch (e) { console.error('AllTrans JS Error iterating frames for marking:', e); }\n"
                + "\n"
                + "try {\n"
                + "  var allMainNodes = allTransGetAllTextNodes(window.document);\n"
                + "  allTransMarkNodes(allMainNodes, originalToMatch);\n"
                + "} catch (e) { console.error('AllTrans JS Error marking main document nodes:', e); }\n")

        myEvaluateJavaScript(webView, script)
    }

    // --- Fim da nova função ---
    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    @Throws(Throwable::class)
    fun afterOnLoadMethod(webView: WebView) {
        Utils.debugLog("VirtWebViewOnLoad: afterOnLoadMethod ENTERED for WebView: " + webView.hashCode())
        Utils.debugLog("we are in onPageFinished!")

        val scriptFrames = "console.log(\"AllTrans: Frames is \"+window.frames.length)"
        myEvaluateJavaScript(webView, scriptFrames)

        // Script JS inicial (com filtros melhorados e verificação de marca)
        val script = (""
                + "console.log('AllTrans: Initializing JS...');\n"
                + "\n"
                + "var AllTransPlaceholderTypes = {\n"
                + "    'date': 0, 'datetime-local': 0, 'email': 0, 'month': 0,\n"
                + "    'number': 0, 'password': 0, 'tel': 0, 'time': 0,\n"
                + "    'url': 0, 'week': 0, 'search': 0, 'text': 0\n"
                + "};\n"
                + "var AllTransInputTypes = {\n"
                + "  'button': 0, 'reset': 0, 'submit': 0\n"
                + "};\n"
                + "\n"
                + "var AllTransNumericRegex = /^[+-]?\\d+(\\.\\d+)?$/;\n"
                + "var AllTransUrlLikeRegex = /^(http|https):\\/\\/.*|[^\\s]+\\.[^\\s]+$/;\n"
                + "var AllTransAcronymRegex = /^[A-Z0-9_\\-:]+$/;\n"
                + "var AllTransWebUrlRegex = /^(https?:\\/\\/|ftp:\\/\\/|www\\.|[^\\s:\\/]+\\.[^\\s:\\/]+)/i;\n"
                + "var AllTransCssRegex = /^[.#]?[a-zA-Z][a-zA-Z0-9\\-_]*$/;\n"
                + "var AllTransJsKeywordRegex = /^(function|var|let|const|return|if|else|for|while|document|window|console)$/i;\n"
                + "var AllTransColorRegex = /^#[0-9A-Fa-f]{3,8}$|^rgba?\\(/i;\n"
                + "var AllTransCssUnitRegex = /^\\d+\\.?\\d*(px|em|rem|vh|vw|%|pt)$/i;\n"
                + "var AllTransFileExtRegex = /\\.(js|css|html|htm|xml|json|svg|png|jpg|jpeg|gif|webp|ico|pdf)$/i;\n"
                + "var AllTransBase64Regex = /^[A-Za-z0-9+\\/]{20,}={0,2}$/;\n"
                + "\n"
                + "function allTransGetAllTextNodes(tempDocument) {\n"
                + "    var result = [];\n"
                + "    var ignore = {'STYLE': 0, 'SCRIPT': 0, 'NOSCRIPT': 0, 'IFRAME': 0, 'OBJECT': 0, 'CODE': 0, 'PRE': 0};\n"
                + "    (function scanSubTree(node) {\n"
                + "        if (!node) return;\n"
                + "        if (node.nodeType === 1 && node.hasAttribute('data-Alltrans-translated')) { return; }\n"
                + "        if (node.tagName && node.tagName in ignore) { return; }\n"
                + "        if (node.tagName && node.tagName.toLowerCase() == 'input' && (node.type in AllTransInputTypes || node.type in AllTransPlaceholderTypes)) {\n"
                + "            if (!node.hasAttribute('data-Alltrans-translated')) { result.push(node); }\n"
                + "        }\n"
                + "        if (node.childNodes && node.childNodes.length) {\n"
                + "            for (var i = 0; i < node.childNodes.length; i++) { scanSubTree(node.childNodes[i]); }\n"
                + "        } else if (node.nodeType == 3) {\n"
                + "             if (!node.parentElement || !node.parentElement.hasAttribute('data-Alltrans-translated')) { result.push(node); }\n"
                + "        }\n"
                + "    })(tempDocument);\n"
                + "    return result;\n"
                + "}\n"
                + "\n"
                + "function allTransDoReplaceAll(all) {\n"
                + "    if (!all) return;\n"
                + "    var textsToSend = {};\n"
                + "    for (var i = 0, max = all.length; i < max; i++) {\n"
                + "      var currentNode = all[i];\n"
                + "      if (!currentNode) continue;\n"
                + "      try {\n"
                + "        var text = null;\n"
                + "        var elementToCheck = null;\n"
                + "        if (currentNode.tagName && currentNode.tagName.toLowerCase() == 'input' && currentNode.type in AllTransPlaceholderTypes) {\n"
                + "            text = currentNode.placeholder;\n"
                + "            elementToCheck = currentNode;\n"
                + "        } else if (currentNode.nodeType == 3 && currentNode.nodeValue) {\n"
                + "            text = currentNode.nodeValue;\n"
                + "            elementToCheck = currentNode.parentElement;\n"
                + "        } else if (currentNode.tagName && currentNode.tagName.toLowerCase() == 'input' && currentNode.type in AllTransInputTypes) {\n"
                + "            text = currentNode.value;\n"
                + "            elementToCheck = currentNode;\n"
                + "        }\n"
                + "\n"
                + "        if (text && text.trim() !== '') {\n"
                + "            var trimmedText = text.trim();\n"
                + "            if (elementToCheck && elementToCheck.hasAttribute && elementToCheck.hasAttribute('data-Alltrans-translated')) {\n"
                + "                 continue;\n"
                + "            }\n"
                + "            if (trimmedText.length < 3 && !(elementToCheck && elementToCheck.tagName === 'INPUT')) { continue; }\n"
                + "            if (AllTransNumericRegex.test(trimmedText)) { continue; }\n"
                + "            if (trimmedText.length > 5 && AllTransUrlLikeRegex.test(trimmedText) && trimmedText.indexOf(' ') === -1) { continue; }\n"
                + "            if (trimmedText.length < 6 && AllTransAcronymRegex.test(trimmedText)) { continue; }\n"
                + "            // Filtros específicos para web\n"
                + "            if (AllTransWebUrlRegex.test(trimmedText)) { continue; }\n"
                + "            if (AllTransCssRegex.test(trimmedText)) { continue; }\n"
                + "            if (AllTransJsKeywordRegex.test(trimmedText)) { continue; }\n"
                + "            if (AllTransColorRegex.test(trimmedText)) { continue; }\n"
                + "            if (AllTransCssUnitRegex.test(trimmedText)) { continue; }\n"
                + "            if (AllTransFileExtRegex.test(trimmedText)) { continue; }\n"
                + "            if (AllTransBase64Regex.test(trimmedText)) { continue; }\n"
                + "            if (trimmedText.indexOf('charset') !== -1 || trimmedText.indexOf('viewport') !== -1) { continue; }\n"
                + "            if (trimmedText.indexOf('function(') !== -1 || trimmedText.indexOf('console.') !== -1) { continue; }\n"
                + "            if ((trimmedText.charAt(0) === '{' && trimmedText.charAt(trimmedText.length-1) === '}') ||\n"
                + "                (trimmedText.charAt(0) === '[' && trimmedText.charAt(trimmedText.length-1) === ']')) { continue; }\n"
                + "\n"
                + "            textsToSend[text] = true;\n"
                + "        }\n"
                + "      } catch (e) { console.error('AllTrans JS Error processing node for logging:', e); }\n"
                + "    }\n"
                + "    for (var uniqueText in textsToSend) {\n"
                + "        if (textsToSend.hasOwnProperty(uniqueText)) {\n"
                + "             injectedObject.showLog(uniqueText);\n"
                + "        }\n"
                + "    }\n"
                + "}\n"
                + "\n"
                + "function doAll() {\n"
                + "    console.log('AllTrans JS: doAll() triggered');\n"
                + "    try {\n"
                + "      for (var j = 0; j < window.frames.length; j++) {\n"
                + "        try {\n"
                + "          var frameDoc = window.frames[j].document;\n"
                + "          if (frameDoc) {\n"
                + "            var allFrameNodes = allTransGetAllTextNodes(frameDoc);\n"
                + "            allTransDoReplaceAll(allFrameNodes);\n"
                + "          }\n"
                + "        } catch (frameError) { console.warn('AllTrans JS Error accessing frame ' + j + ' in doAll:', frameError); }\n"
                + "      }\n"
                + "    } catch (e) { console.error('AllTrans JS Error iterating frames in doAll:', e); }\n"
                + "\n"
                + "    try {\n"
                + "      var allMainNodes = allTransGetAllTextNodes(window.document);\n"
                + "      allTransDoReplaceAll(allMainNodes);\n"
                + "    } catch (e) { console.error('AllTrans JS Error processing main document nodes in doAll:', e); }\n"
                + "    window.alreadyTranslating = false;\n"
                + "    console.log('AllTrans JS: doAll() finished');\n"
                + "}\n"
                + "\n"
                + "if (!window.AllTransObserver) {\n"
                + "  console.log('AllTrans JS: Setting up MutationObserver');\n"
                + "  MutationObserver = window.MutationObserver || window.WebKitMutationObserver;\n"
                + "  window.AllTransObserver = new MutationObserver(function(mutations, observer) {\n"
                + "      if (window.AllTransTimeoutId) { clearTimeout(window.AllTransTimeoutId); }\n"
                + "      window.AllTransTimeoutId = setTimeout(doAll, 500);\n"
                + "  });\n"
                + "\n"
                + "  try {\n"
                + "    window.AllTransObserver.observe(document, {\n"
                + "      subtree: true,\n"
                + "      childList: true,\n"
                + "      characterData: true\n"
                + "    });\n"
                + "    console.log('AllTrans JS: MutationObserver started.');\n"
                + "  } catch (e) { console.error('AllTrans JS: Error starting MutationObserver:', e); }\n"
                + "} else { console.log('AllTrans JS: MutationObserver already exists.'); }\n"
                + "\n"
                + "setTimeout(doAll, " + PreferenceList.DelayWebView + ");\n"
                + "console.log('AllTrans JS: Initialized and observer set/checked.');")

        Utils.debugLog("VirtWebViewOnLoad: Injecting main JS script...")
        myEvaluateJavaScript(webView, script)
        Utils.debugLog("VirtWebViewOnLoad: afterOnLoadMethod EXITED for WebView: " + webView.hashCode())
    }

    @Suppress("unused")
    @JavascriptInterface
    fun showLog(stringArgs: String?) {
        if (stringArgs == null || stringArgs.trim { it <= ' ' }.isEmpty()) {
            return
        }

        if (this.webViewInstance == null) {
            Utils.debugLog("VirtWebViewOnLoad: showLog - webViewInstance is null! Aborting translation for [" + stringArgs + "]")
            return
        }

        // Verificar se o AllTrans está habilitado para este app
        val context = this.webViewInstance.context
        val packageName = context?.packageName
        if (!PreferenceManager.isEnabledForPackage(context, packageName)) {
            Utils.debugLog("VirtWebViewOnLoad: AllTrans DESABILITADO para este app ($packageName). Pulando tradução para [$stringArgs]")
            return
        }

        if (!SetTextHookHandler.Companion.isNotWhiteSpace(stringArgs)) {
            return
        }

        // Aplicar filtros específicos para WebView
        if (isWebContentToSkip(stringArgs)) {
            Utils.debugLog("VirtWebViewOnLoad: Skipping web content: [$stringArgs]")
            return
        }

        Utils.debugLog("VirtWebViewOnLoad: showLog received: [" + stringArgs + "] for WebView " + this.webViewInstance.hashCode())

        val currentUserData = WebHookUserData2(this.webViewInstance, stringArgs)
        val getTranslate = GetTranslate()
        getTranslate.stringToBeTrans = stringArgs
        getTranslate.originalCallable = this
        getTranslate.userData = currentUserData
        getTranslate.canCallOriginal = true

        var translationNeeded = true

        if (PreferenceList.Caching) {
            Alltrans.Companion.cacheAccess.acquireUninterruptibly()
            var cachedValue: String? = null
            try {
                val cacheRef = Alltrans.Companion.cache
                if (cacheRef != null) {
                    cachedValue = cacheRef[stringArgs]
                }
            } finally {
                if (Alltrans.Companion.cacheAccess.availablePermits() == 0) {
                    Alltrans.Companion.cacheAccess.release()
                }
            }

            if (cachedValue != null) {
                if (cachedValue != stringArgs) {
                    Utils.debugLog("VirtWebViewOnLoad: showLog - Found different translation in cache: [" + stringArgs + "] -> [" + cachedValue + "]")
                    val finalCachedTranslation: String? = cachedValue
                    Handler(Looper.getMainLooper()).postDelayed(Runnable {
                        callOriginalMethod(
                            finalCachedTranslation!!, currentUserData
                        )
                    }, PreferenceList.Delay.toLong())
                    translationNeeded = false
                } else {
                    Utils.debugLog("VirtWebViewOnLoad: showLog - Found same translation in cache, skipping request: [" + stringArgs + "]")
                    markNodeAsTranslated(this.webViewInstance, stringArgs)
                    translationNeeded = false
                }
            } else {
                Utils.debugLog("VirtWebViewOnLoad: showLog - Not in cache: [" + stringArgs + "]")
                translationNeeded = true
            }
        }

        if (translationNeeded) {
            Utils.debugLog("VirtWebViewOnLoad: showLog - Requesting translation for: [" + stringArgs + "]")
            val getTranslateToken = GetTranslateToken()
            getTranslateToken.getTranslate = getTranslate
            getTranslateToken.doAll()
        }
    }

    @Suppress("unused")
    @JavascriptInterface
    fun WriteHTML(html: String) {
        val contextRef = Alltrans.Companion.context
        if (contextRef == null) {
            Utils.debugLog("VirtWebViewOnLoad: WriteHTML - Context is null, cannot write file.")
            return
        }
        try {
            val fileOutputStream: FileOutputStream = contextRef.openFileOutput(
                "AllTransWebViewDebug.html",
                Context.MODE_PRIVATE
            )
            fileOutputStream.write(html.toByteArray())
            fileOutputStream.close()
            Utils.debugLog("VirtWebViewOnLoad: WriteHTML - Saved HTML snapshot.")
        } catch (e: Throwable) {
            Utils.debugLog("VirtWebViewOnLoad: WriteHTML - Exception while writing HTML: " + e)
        }
    }

    fun myEvaluateJavaScript(webView: WebView?, script: String) {
        if (webView == null) {
            Utils.debugLog("VirtWebViewOnLoad: myEvaluateJavaScript - WebView is null!")
            return
        }
        Utils.debugLog(
            "VirtWebViewOnLoad: Evaluating JS for WebView " + webView.hashCode() + ": " + script.substring(
                0,
                min(script.length.toDouble(), 100.0).toInt()
            ) + "..."
        )

        webView.post(object : Runnable {
            override fun run() {
                try {
                    webView.evaluateJavascript(script, object : ValueCallback<String?> {
                        override fun onReceiveValue(value: String?) {
                            val logValue =
                                if (value == null || "null" == value || value.isEmpty()) "<no return value>" else value
                            // Utils.debugLog("VirtWebViewOnLoad: JS evaluation result: " + logValue); // Comentado para reduzir spam
                        }
                    })
                } catch (e: Exception) {
                    Utils.debugLog("VirtWebViewOnLoad: Exception during evaluateJavascript: " + e.message)
                }
            }
        })
    }
} // Fim da classe VirtWebViewOnLoad

// Classe auxiliar
internal class WebHookUserData2(val webView: WebView?, val stringArgs: String?)