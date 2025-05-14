package akhil.alltrans

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.content.res.XModuleResources
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.webkit.WebView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.ClassNotFoundError
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Semaphore

class alltrans : IXposedHookLoadPackage {
    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // --- Define MODULE_PATH quando nosso próprio pacote é carregado ---
        if (lpparam.packageName == "akhil.alltrans") {
            XposedBridge.log("AllTrans: Detected own package loading. Setting module path only.")

            if (MODULE_PATH == null && lpparam.appInfo != null && lpparam.appInfo.sourceDir != null) {
                MODULE_PATH = lpparam.appInfo.sourceDir // Usa o sourceDir do nosso próprio AppInfo
                XposedBridge.log("AllTrans: Module path set from own package appInfo.sourceDir: " + MODULE_PATH)

                // Não tenta inicializar a chave aqui, isso será feito quando o contexto estiver disponível
                utils.debugLog("AllTrans: Module path set, will initialize tag key when context is available")
            }

            // CORREÇÃO: Não aplicar hooks contextuais ao próprio AllTrans
            utils.debugLog("AllTrans: Skipping all hooks for own package")
            return  // Não aplica nenhum hook ao próprio AllTrans
        }

        // --- Processar outros pacotes ---
        if (lpparam.appInfo == null) {
            XposedBridge.log("AllTrans: Skipping package with null appInfo: " + lpparam.packageName)
            return
        }

        if ((lpparam.appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
            // Permite hookar apenas o Settings Provider
            if ("com.android.providers.settings" != lpparam.packageName) {
                XposedBridge.log("AllTrans: Skipping system app: " + lpparam.packageName)
                return
            }
        }

        // Hook do Settings Provider
        if ("com.android.providers.settings" == lpparam.packageName) {
            XposedBridge.log("AllTrans: Hooking Settings Provider for proxy functionality.")
            if (!settingsHooked) {
                try {
                    hookSettingsProviderMethods(lpparam)
                    settingsHooked = true
                } catch (t: Throwable) {
                    XposedBridge.log("AllTrans: Failed to hook Settings Provider!")
                    XposedBridge.log(t)
                }
            } else {
                XposedBridge.log("AllTrans: Settings Provider already hooked.")
            }
            return // Retorna após processar o hook do Settings Provider
        }

        XposedBridge.log("AllTrans: Processing package: " + lpparam.packageName)

        // Encontrar BaseRecordingCanvas (como antes)
        try {
            if (baseRecordingCanvas == null) {
                baseRecordingCanvas = XposedHelpers.findClass(
                    "android.graphics.BaseRecordingCanvas",
                    lpparam.classLoader
                )
                XposedBridge.log("AllTrans: Found BaseRecordingCanvas.")
            }
        } catch (e: ClassNotFoundError) {
            utils.debugLog("AllTrans: BaseRecordingCanvas not found - might be using different Android version.")
        } catch (t: Throwable) {
            utils.debugLog("AllTrans: Error finding BaseRecordingCanvas: " + t.message)
        }

        // Inicializar cache se necessário
        if (cache == null) {
            cache = HashMap<String?, String?>() // Mantendo a nulidade original do seu código
            utils.debugLog("AllTrans: Cache initialized")
        }

        // Aplica hooks para obter contexto e iniciar a lógica principal
        utils.tryHookMethod(Application::class.java, "onCreate", appOnCreateHookHandler())
        utils.tryHookMethod(
            ContextWrapper::class.java,
            "attachBaseContext",
            Context::class.java,
            AttachBaseContextHookHandler()
        )

        // Hook de limpeza da WebView - com verificações de segurança
        val packageName = lpparam.packageName
// Pula o hook de WebView no system_server ou processos do sistema
        val isSystemPackage = packageName.startsWith("android.") ||
                packageName.startsWith("com.android.") ||
                packageName == "system" ||
                packageName == "com.google.android.webview"

        if (!isSystemPackage) {
            try {
                // Carrega a classe WebView de forma segura
                val webViewClass = lpparam.classLoader.loadClass("android.webkit.WebView")
                utils.debugLog("AllTrans: WebView class found in " + packageName)

                // Hook de onDetachedFromWindow para limpeza de instâncias
                // Tenta encontrar o método com a assinatura correta antes de tentar o hook
                try {
                    // Tenta encontrar o método na hierarquia de classes
                    var foundMethod = false
                    try {
                        webViewClass.getDeclaredMethod("onDetachedFromWindow")
                        foundMethod = true
                    } catch (e: NoSuchMethodException) {
                        utils.debugLog("AllTrans: Direct onDetachedFromWindow method not found, checking View class")
                        // Se não encontrou diretamente, verifica na classe pai (View)
                        try {
                            val viewClass = Class.forName("android.view.View", false, lpparam.classLoader)
                            viewClass.getDeclaredMethod("onDetachedFromWindow")
                            foundMethod = true
                            utils.debugLog("AllTrans: Using View.onDetachedFromWindow instead")
                        } catch (e2: Exception) {
                            utils.debugLog("AllTrans: Could not find onDetachedFromWindow in View hierarchy: ${e2.message}")
                        }
                    }

                    if (foundMethod) {
                        // Agora que confirmamos que o método existe, tenta fazer o hook
                        XposedHelpers.findAndHookMethod(
                            webViewClass,
                            "onDetachedFromWindow",
                            object : XC_MethodHook() {
                                @Throws(Throwable::class)
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val webView = param.thisObject as? WebView
                                    if (webView != null) {
                                        // Usar a instância correta do mapa (não nulável se definido assim no companion)
                                        val removedInstance = webViewHookInstances.remove(webView)
                                        if (removedInstance != null) {
                                            utils.debugLog("AllTrans: Cleaned up WebView instance on detach: " + webView.hashCode())
                                        }
                                    }
                                }
                            }
                        )
                    } else {
                        // Fallback: tenta hookar o destroy() como alternativa
                        utils.debugLog("AllTrans: Failed to hook onDetachedFromWindow, trying destroy() as fallback")
                        utils.tryHookMethod(
                            webViewClass,
                            "destroy",
                            object : XC_MethodHook() {
                                @Throws(Throwable::class)
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val webView = param.thisObject as? WebView
                                    if (webView != null) {
                                        val removedInstance = webViewHookInstances.remove(webView)
                                        if (removedInstance != null) {
                                            utils.debugLog("AllTrans: Cleaned up WebView instance on destroy: " + webView.hashCode())
                                        }
                                    }
                                }
                            }
                        )
                    }
                } catch (e: Throwable) {
                    utils.debugLog("AllTrans: Failed to set up any WebView cleanup hooks: " + e.message)
                }
            } catch (e: ClassNotFoundException) {
                utils.debugLog("AllTrans: WebView class not found in " + packageName)
            } catch (e: Throwable) { // Captura outras exceções durante a configuração do hook da WebView
                utils.debugLog("AllTrans: Error setting up WebView hooks for $packageName: " + e.message)
            }
        } else {
            utils.debugLog("AllTrans: Skipping WebView hook for system package: " + packageName)
        }
    }

    @Throws(Throwable::class)
    private fun hookSettingsProviderMethods(lpparam: LoadPackageParam) {
        var clsSet: Class<*>? = null
        try {
            clsSet = Class.forName(
                "com.android.providers.settings.SettingsProvider",
                false,
                lpparam.classLoader
            )
        } catch (e: ClassNotFoundException) {
            XposedBridge.log("AllTrans: SettingsProvider class not found!")
            return
        }
        // Usar clsSet?.let para segurança
        clsSet?.let {
            hookSettingsQuery(it)
            hookSettingsCall(it)
        }
    }

    @Throws(Throwable::class)
    private fun hookSettingsQuery(clsSet: Class<*>) {
        XposedBridge.log("AllTrans: Trying to hook SettingsProvider.query method.")
        var mQuery: Method? = null
        // Tentar encontrar a assinatura do método de forma mais robusta
        val querySignatures = listOf(
            arrayOf<Class<*>>(Uri::class.java, Array<String>::class.java, Bundle::class.java, CancellationSignal::class.java),
            arrayOf<Class<*>>(Uri::class.java, Array<String>::class.java, String::class.java, Array<String>::class.java, String::class.java, CancellationSignal::class.java),
            arrayOf<Class<*>>(Uri::class.java, Array<String>::class.java, String::class.java, Array<String>::class.java, String::class.java)
        )

        for (sig in querySignatures) {
            try {
                mQuery = clsSet.getDeclaredMethod("query", *sig)
                XposedBridge.log("AllTrans: Found SettingsProvider.query with signature: (${sig.joinToString { it.name }})")
                break // Encontrou, sair do loop
            } catch (e: NoSuchMethodException) {
                // Continuar tentando a próxima assinatura
            }
        }

        if (mQuery == null) {
            XposedBridge.log("AllTrans: Could not find any known SettingsProvider.query method signature!")
            return
        }

        // Usar utils.tryHookMethod para consistência no log de sucesso/falha do hook
        utils.tryHookMethod(clsSet, "query", *(mQuery.parameterTypes), object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args == null || param.args.isEmpty() || param.args[0] !is Uri) return
                val uri = param.args[0] as Uri
                val uriString = uri.toString()
                if (uriString.startsWith("content://settings/system/alltransProxyProviderURI/")) {
                    XposedBridge.log("AllTrans Proxy Hook: Intercepted query URI: $uriString")
                    val originalProviderUriString = uriString.replaceFirst(
                        "content://settings/system/alltransProxyProviderURI/".toRegex(),
                        "content://"
                    )
                    XposedBridge.log("AllTrans Proxy Hook: Rewritten query URI: $originalProviderUriString")
                    val newUri = Uri.parse(originalProviderUriString)
                    if (newUri == null) {
                        param.result = null
                        return
                    }

                    val ident = Binder.clearCallingIdentity()
                    var cursor: Cursor? = null
                    var providerContext: Context? = null
                    try {
                        val settingsProviderInstance = param.thisObject
                        // Tentativa simplificada de obter contexto
                        providerContext = try {
                            settingsProviderInstance.javaClass.getMethod("getContext").invoke(settingsProviderInstance) as? Context
                        } catch (e: Exception) { // Captura NoSuchMethod, IllegalAccess, InvocationTarget
                            try {
                                XposedHelpers.getObjectField(settingsProviderInstance, "mContext") as? Context
                            } catch (e_field: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance: ${e_field.message}")
                                null
                            }
                        }

                        if (providerContext == null) {
                            XposedBridge.log("AllTrans Proxy Hook: Provider context is null, cannot execute proxy query.")
                            param.result = null
                            return
                        }

                        val projectionArg = param.args[1]
                        @Suppress("UNCHECKED_CAST")
                        val projection = if (projectionArg is Array<*>) projectionArg as? Array<String?> else null

                        val paramTypes = (param.method as Method).parameterTypes
                        var cancellationSignal: CancellationSignal? = null
                        param.args.filterIsInstance<CancellationSignal>().firstOrNull()?.let {
                            cancellationSignal = it
                        }

                        if (paramTypes.size > 2 && paramTypes[2] == Bundle::class.java) {
                            val queryArgsBundle = param.args[2] as? Bundle // Nome mais descritivo
                            // Validação de queryArgsBundle não é estritamente necessária aqui se pode ser nulo para o query
                            XposedBridge.log("AllTrans Proxy Hook: Querying using Bundle signature.")
                            cursor = providerContext.contentResolver.query(newUri, projection, queryArgsBundle, cancellationSignal)
                        } else if (paramTypes.size > 4 && paramTypes[2] == String::class.java) { // Checa tipo do terceiro arg
                            val selection = param.args[2] as? String
                            val selectionArgsArg = param.args[3]
                            @Suppress("UNCHECKED_CAST")
                            val selectionArgsArray = if (selectionArgsArg is Array<*>) selectionArgsArg as? Array<String?> else null
                            val sortOrder = param.args[4] as? String
                            XposedBridge.log("AllTrans Proxy Hook: Querying using String[] signature.")
                            cursor = providerContext.contentResolver.query(newUri, projection, selection, selectionArgsArray, sortOrder, cancellationSignal)
                        } else {
                            XposedBridge.log("AllTrans Proxy Hook: Querying using simpler signature (no Bundle/detailed args).")
                            cursor = providerContext.contentResolver.query(newUri, projection, null, null, null, cancellationSignal)
                        }
                        param.result = cursor
                        XposedBridge.log("AllTrans Proxy Hook: Setting query result (${cursor?.count ?: "null"} rows).")
                    } catch (ex: Throwable) {
                        XposedBridge.log("AllTrans Proxy Hook: Error during proxy query execution!")
                        XposedBridge.log(ex)
                        param.result = null
                        cursor?.close() // Garante fechar o cursor em caso de erro
                    } finally {
                        Binder.restoreCallingIdentity(ident)
                    }
                }
            }
        })
    }

    @Throws(Throwable::class)
    private fun hookSettingsCall(clsSet: Class<*>) {
        XposedBridge.log("AllTrans: Trying to hook SettingsProvider.call method.")
        var mCall: Method? = null
        val callSignatures = listOf(
            arrayOf<Class<*>>(String::class.java, String::class.java, String::class.java, Bundle::class.java),
            arrayOf<Class<*>>(String::class.java, String::class.java, Bundle::class.java)
        )
        for (sig in callSignatures) {
            try {
                mCall = clsSet.getDeclaredMethod("call", *sig)
                XposedBridge.log("AllTrans: Found SettingsProvider.call with signature: (${sig.joinToString { it.name }})")
                break
            } catch (e: NoSuchMethodException) {
                // Continuar
            }
        }

        if (mCall == null) {
            XposedBridge.log("AllTrans: Could not find any known SettingsProvider.call method signature!")
            return
        }

        utils.tryHookMethod(clsSet, "call", *(mCall.parameterTypes), object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                // authority não é usado, então não precisamos dele
                // var authority: String? = null
                val methodToCall: String?
                // val argForCall: String? // Não usado diretamente
                val extrasBundle: Bundle?

                val paramTypes = (param.method as Method).parameterTypes
                if (paramTypes.size == 4 && paramTypes[0] == String::class.java) { // Assinatura (authority, method, arg, extras)
                    // authority = param.args[0] as? String
                    methodToCall = param.args[1] as? String
                    // argForCall = param.args[2] as? String
                    extrasBundle = param.args[3] as? Bundle
                    if (PreferenceList.Debug || "alltransProxyCall" == methodToCall) {
                        XposedBridge.log("AllTrans Proxy Hook: Matched call signature (String, String, String, Bundle)")
                    }
                } else if (paramTypes.size == 3 && paramTypes[0] == String::class.java) { // Assinatura (method, arg, extras)
                    methodToCall = param.args[0] as? String
                    // argForCall = param.args[1] as? String
                    extrasBundle = param.args[2] as? Bundle
                    if (PreferenceList.Debug || "alltransProxyCall" == methodToCall) {
                        XposedBridge.log("AllTrans Proxy Hook: Matched call signature (String, String, Bundle)")
                    }
                } else {
                    XposedBridge.log("AllTrans Proxy Hook: Unexpected SettingsProvider.call signature.")
                    return
                }

                if ("alltransProxyCall" == methodToCall) {
                    if (extrasBundle == null) {
                        XposedBridge.log("Proxy call: extrasBundle is null!")
                        param.result = null; return
                    }

                    val originalUriString = extrasBundle.getString("alltransOriginalUri")
                    val originalMethodName = extrasBundle.getString("alltransOriginalMethod")
                    val originalArgValue = extrasBundle.getString("alltransOriginalArg") // Pode ser nulo

                    if (originalUriString == null || originalMethodName == null) {
                        XposedBridge.log("Proxy call: original URI/Method missing in extras!")
                        param.result = null; return
                    }
                    val originalUri = Uri.parse(originalUriString) ?: run {
                        XposedBridge.log("Proxy call: Failed to parse original URI from extras!")
                        param.result = null; return
                    }

                    // Remover chaves do AllTrans do Bundle antes de passar adiante
                    extrasBundle.remove("alltransOriginalUri")
                    extrasBundle.remove("alltransOriginalMethod")
                    extrasBundle.remove("alltransOriginalArg")

                    XposedBridge.log("AllTrans Proxy Hook: Intercepted call for $originalUri, Method: $originalMethodName, Arg: $originalArgValue")

                    val ident = Binder.clearCallingIdentity()
                    var providerContext: Context? = null
                    try {
                        val settingsProviderInstance = param.thisObject
                        providerContext = try {
                            settingsProviderInstance.javaClass.getMethod("getContext").invoke(settingsProviderInstance) as? Context
                        } catch (e: Exception) {
                            try { XposedHelpers.getObjectField(settingsProviderInstance, "mContext") as? Context }
                            catch (e_field: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context (call): ${e_field.message}")
                                null
                            }
                        }
                        if (providerContext == null) {
                            XposedBridge.log("Proxy call: Failed to get provider context for actual call!")
                            param.result = null; return
                        }

                        XposedBridge.log("AllTrans Proxy Hook: Calling actual provider: $originalUri, Method: $originalMethodName, Arg: $originalArgValue")
                        val resultBundle = providerContext.contentResolver.call(originalUri, originalMethodName, originalArgValue, extrasBundle)
                        param.result = resultBundle
                        XposedBridge.log("AllTrans Proxy Hook: Setting call result: $resultBundle")
                    } catch (ex: Throwable) {
                        XposedBridge.log("AllTrans Proxy Hook: Error during proxy call execution!")
                        XposedBridge.log(ex)
                        param.result = null
                    } finally {
                        Binder.restoreCallingIdentity(ident)
                    }
                }
            }
        })
    }

    companion object {
        val cacheAccess: Semaphore = Semaphore(1, true)
        val hookAccess: Semaphore = Semaphore(1, true) // Se não for usado, pode ser removido.

        @SuppressLint("StaticFieldLeak") // Contexto é ApplicationContext, geralmente seguro
        val drawTextHook: DrawTextHookHandler = DrawTextHookHandler()

        @SuppressLint("StaticFieldLeak")
        val notifyHook: NotificationHookHandler = NotificationHookHandler()

        var cache: HashMap<String?, String?>? = null // Mantendo nulável como no seu original

        @SuppressLint("StaticFieldLeak")
        var context: Context? = null
        var baseRecordingCanvas: Class<*>? = null
        var settingsHooked: Boolean = false

        var isProxyEnabled: Boolean = true // Assumindo que isso é controlado de alguma forma

        val pendingTextViewTranslations: MutableSet<Int> = Collections.synchronizedSet(HashSet<Int>())

        var WEBVIEW_HOOK_TAG_KEY: Int = 0x7f080001 // Fallback arbitrário se R.id não puder ser usado
        private var tagKeyInitialized = false
        private var MODULE_PATH: String? = null

        // Chaves e valores não nuláveis se essa for a intenção
        val webViewHookInstances: MutableMap<WebView, VirtWebViewOnLoad> =
            Collections.synchronizedMap(WeakHashMap<WebView, VirtWebViewOnLoad>())

        @Synchronized
        fun initializeTagKeyIfNeeded() {
            if (tagKeyInitialized) {
                utils.debugLog("AllTrans: Tag key already initialized: $WEBVIEW_HOOK_TAG_KEY (0x${Integer.toHexString(WEBVIEW_HOOK_TAG_KEY)})")
                return
            }
            if (context == null || MODULE_PATH == null) {
                utils.debugLog("AllTrans: Cannot initialize tag key yet. Context: ${context != null}, MODULE_PATH: ${MODULE_PATH != null}")
                return
            }

            try {
                XposedBridge.log("AllTrans: Attempting to initialize WEBVIEW_HOOK_TAG_KEY...")
                val modRes: Resources = XModuleResources.createInstance(MODULE_PATH, null)
                val appPackageName = "akhil.alltrans" // Seu nome de pacote

                val resourceId = modRes.getIdentifier("tag_alltrans_webview_hook", "id", appPackageName)

                if (resourceId != 0) {
                    WEBVIEW_HOOK_TAG_KEY = resourceId
                    tagKeyInitialized = true
                    XposedBridge.log(
                        "AllTrans: Successfully initialized WEBVIEW_HOOK_TAG_KEY to: $WEBVIEW_HOOK_TAG_KEY (0x${Integer.toHexString(WEBVIEW_HOOK_TAG_KEY)}) from module resources."
                    )
                } else {
                    // Se não encontrar, WEBVIEW_HOOK_TAG_KEY manterá o valor de fallback.
                    // Marcar como inicializado para não tentar de novo.
                    tagKeyInitialized = true
                    XposedBridge.log("AllTrans: Could not find resource ID 'tag_alltrans_webview_hook' in module. Using fallback: $WEBVIEW_HOOK_TAG_KEY (0x${Integer.toHexString(WEBVIEW_HOOK_TAG_KEY)})")
                }
            } catch (t: Throwable) {
                XposedBridge.log("AllTrans: Error initializing WEBVIEW_HOOK_TAG_KEY from XModuleResources. Using fallback.")
                XposedBridge.log(t)
                tagKeyInitialized = true // Evitar tentativas repetidas em caso de erro
            }
        }
    }
}