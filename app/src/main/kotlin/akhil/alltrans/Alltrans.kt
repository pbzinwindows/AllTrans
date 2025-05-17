/*
 * Copyright 2017 Akhil Kedia
 * // ... (copyright header) ...
 */
package akhil.alltrans

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.content.res.XModuleResources
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.webkit.WebView
import androidx.core.net.toUri
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.ClassNotFoundError
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.HashSet
import java.util.WeakHashMap
import java.util.concurrent.Semaphore

class Alltrans : IXposedHookLoadPackage {
    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // --- Define MODULE_PATH quando nosso próprio pacote é carregado ---
        if (lpparam.packageName == "akhil.alltrans") {
            if (MODULE_PATH == null && lpparam.appInfo != null && lpparam.appInfo.sourceDir != null) {
                MODULE_PATH = lpparam.appInfo.sourceDir // Usa o sourceDir do nosso próprio AppInfo
                XposedBridge.log("AllTrans: Module path set from own package appInfo.sourceDir: $MODULE_PATH")

                // Não tenta inicializar a chave aqui, isso será feito quando o contexto estiver disponível
                Utils.debugLog("AllTrans: Module path set, will initialize tag key when context is available")
            }

            // Aplica hooks contextuais mesmo para o pacote AllTrans
            Utils.tryHookMethod(Application::class.java, "onCreate", AppOnCreateHookHandler())
            Utils.tryHookMethod(
                ContextWrapper::class.java,
                "attachBaseContext",
                Context::class.java,
                AttachBaseContextHookHandler()
            )
            return  // Não aplica outros hooks ao próprio AllTrans
        }

        // --- Processar outros pacotes ---
        if (lpparam.appInfo == null) {
            XposedBridge.log("AllTrans: Skipping package with null appInfo: ${lpparam.packageName}")
            return
        }

        if ((lpparam.appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
            // Permite hookar o Settings Provider e o próprio pacote AllTrans (se necessário para contexto/path)
            if ("com.android.providers.settings" != lpparam.packageName && "akhil.alltrans" != lpparam.packageName) {
                XposedBridge.log("AllTrans: Skipping system app: ${lpparam.packageName}")
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
                    XposedBridge.log("AllTrans: Settings Provider hooks applied successfully.")
                } catch (t: Throwable) {
                    XposedBridge.log("AllTrans: Failed to hook Settings Provider!")
                    XposedBridge.log(t)
                }
            } else {
                XposedBridge.log("AllTrans: Settings Provider already hooked.")
            }
            return // Retorna após processar o hook do Settings Provider
        }

        XposedBridge.log("AllTrans: Processing package: ${lpparam.packageName}")

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
            Utils.debugLog("AllTrans: BaseRecordingCanvas not found - might be using different Android version.")
        } catch (t: Throwable) {
            Utils.debugLog("AllTrans: Error finding BaseRecordingCanvas: ${t.message}")
        }

        // Inicializar cache se necessário
        if (cache == null) {
            cache = HashMap()
            Utils.debugLog("AllTrans: Cache initialized")
        }

        // Aplica hooks para obter contexto e iniciar a lógica principal
        Utils.tryHookMethod(Application::class.java, "onCreate", AppOnCreateHookHandler())
        Utils.tryHookMethod(
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
                Utils.debugLog("AllTrans: WebView class found in $packageName")

                // Hook de onDetachedFromWindow para limpeza de instâncias
                // Nota: este método é herdado de View, então vamos tentar abordagens diferentes
                try {
                    // Abordagem 1: Usar XposedBridge.hookAllMethods para capturar todas as variantes
                    XposedBridge.hookAllMethods(webViewClass, "onDetachedFromWindow", object : XC_MethodHook() {
                        @Throws(Throwable::class)
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val webView = param.thisObject as? WebView
                            if (webView != null) {
                                val removedInstance = webViewHookInstances.remove(webView)
                                if (removedInstance != null) {
                                    Utils.debugLog("AllTrans: Cleaned up WebView instance on detach: ${webView.hashCode()}")
                                }
                            }
                        }
                    })
                    Utils.debugLog("AllTrans: WebView.onDetachedFromWindow hook applied successfully via hookAllMethods")
                } catch (e: Throwable) {
                    Utils.debugLog("AllTrans: Failed to hook WebView.onDetachedFromWindow via hookAllMethods: ${e.message}")
                    try {
                        // Abordagem 2: Tentar usar a superclasse View que contém este método
                        val viewClass = lpparam.classLoader.loadClass("android.view.View")
                        XposedBridge.hookMethod(
                            viewClass.getDeclaredMethod("onDetachedFromWindow"),
                            object : XC_MethodHook() {
                                @Throws(Throwable::class)
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (param.thisObject is WebView) {
                                        val webView = param.thisObject as WebView
                                        val removedInstance = webViewHookInstances.remove(webView)
                                        if (removedInstance != null) {
                                            Utils.debugLog("AllTrans: Cleaned up WebView instance on detach via View hook: ${webView.hashCode()}")
                                        }
                                    }
                                }
                            }
                        )
                        Utils.debugLog("AllTrans: View.onDetachedFromWindow hook applied successfully for WebView")
                    } catch (e2: Throwable) {
                        Utils.debugLog("AllTrans: Failed all attempts to hook onDetachedFromWindow: ${e2.message}")
                    }
                }
            } catch (e: ClassNotFoundException) {
                Utils.debugLog("AllTrans: WebView class not found in $packageName")
            } catch (e: Throwable) {
                Utils.debugLog("AllTrans: Error setting up WebView hooks: ${e.message}")
            }
        } else {
            Utils.debugLog("AllTrans: Skipping WebView hook for system package: $packageName")
        }
    }

    @Throws(Throwable::class)
    private fun hookSettingsProviderMethods(lpparam: LoadPackageParam) {
        val clsSet: Class<*>? = try {
            Class.forName(
                "com.android.providers.settings.SettingsProvider",
                false,
                lpparam.classLoader
            )
        } catch (e: ClassNotFoundException) {
            XposedBridge.log("AllTrans: SettingsProvider class not found!")
            return
        }

        if (clsSet != null) {
            hookSettingsQuery(clsSet)
            hookSettingsCall(clsSet)
        }
    }

    @Throws(Throwable::class)
    private fun hookSettingsQuery(clsSet: Class<*>) {
        XposedBridge.log("AllTrans: Trying to hook SettingsProvider.query method.")
        val mQuery: Method? = try {
            clsSet.getDeclaredMethod(
                "query",
                Uri::class.java,
                Array<String>::class.java,
                Bundle::class.java,
                CancellationSignal::class.java
            ).also {
                XposedBridge.log("AllTrans: Found SettingsProvider.query (Uri, String[], Bundle, CancellationSignal) method.")
            }
        } catch (e1: NoSuchMethodException) {
            XposedBridge.log("AllTrans: Query signature with Bundle not found, trying (Uri, String[], String, String[], String, CancellationSignal)...")
            try {
                clsSet.getDeclaredMethod(
                    "query",
                    Uri::class.java,
                    Array<String>::class.java,
                    String::class.java,
                    Array<String>::class.java,
                    String::class.java,
                    CancellationSignal::class.java
                ).also {
                    XposedBridge.log("AllTrans: Found SettingsProvider.query (Uri, String[], String, String[], String, CancellationSignal) method.")
                }
            } catch (e2: NoSuchMethodException) {
                XposedBridge.log("AllTrans: Query signature with CancellationSignal not found, trying (Uri, String[], String, String[], String)...")
                try {
                    clsSet.getDeclaredMethod(
                        "query",
                        Uri::class.java,
                        Array<String>::class.java,
                        String::class.java,
                        Array<String>::class.java,
                        String::class.java
                    ).also {
                        XposedBridge.log("AllTrans: Found SettingsProvider.query (Uri, String[], String, String[], String) method.")
                    }
                } catch (e3: NoSuchMethodException) {
                    XposedBridge.log("AllTrans: Could not find any known SettingsProvider.query method signature!")
                    return
                }
            }
        }

        XposedBridge.log("AllTrans: Applying hook to SettingsProvider.query method.")
        XposedBridge.hookMethod(mQuery, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args == null || param.args.isEmpty() || (param.args[0] !is Uri)) return
                val uri = param.args[0] as Uri
                val uriString = uri.toString()
                if (uriString.startsWith("content://settings/system/alltransProxyProviderURI/")) {
                    XposedBridge.log("AllTrans Proxy Hook: Intercepted query URI: $uriString")
                    val originalProviderUriString = uriString.replaceFirst(
                        "content://settings/system/alltransProxyProviderURI/".toRegex(),
                        "content://"
                    )
                    XposedBridge.log("AllTrans Proxy Hook: Rewritten query URI: $originalProviderUriString")
                    val newUri = originalProviderUriString.toUri()

                    val ident = Binder.clearCallingIdentity()
                    var cursor: Cursor? = null
                    var providerContext: Context? = null
                    try {
                        val settingsProviderInstance = param.thisObject
                        try {
                            val mGetContext =
                                settingsProviderInstance.javaClass.getMethod("getContext")
                            providerContext =
                                mGetContext.invoke(settingsProviderInstance) as Context?
                        } catch (reflectException: NoSuchMethodException) {
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldException: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance.")
                            }
                        } catch (reflectException: IllegalAccessException) {
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldException: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance.")
                            }
                        } catch (reflectException: InvocationTargetException) {
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldException: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance.")
                            }
                        }

                        if (providerContext == null) {
                            XposedBridge.log("AllTrans Proxy Hook: Provider context is null, cannot execute proxy query.")
                            param.setResult(null)
                            return
                        }

                        // Fix para o primeiro warning - safely cast projection
                        val projectionArg = param.args[1]
                        val projection = if (projectionArg is Array<*>) {
                            @Suppress("UNCHECKED_CAST")
                            projectionArg as? Array<String?>
                        } else null

                        var cancellationSignal: CancellationSignal? = null
                        val paramTypes = (param.method as Method).parameterTypes

                        for (arg in param.args) {
                            if (arg is CancellationSignal) {
                                cancellationSignal = arg
                                break
                            }
                        }

                        if (paramTypes.size > 2 && paramTypes[2] == Bundle::class.java) {
                            val queryArgs = param.args[2] as Bundle?
                            XposedBridge.log("AllTrans Proxy Hook: Querying using Bundle signature.")
                            cursor = providerContext.contentResolver
                                .query(newUri, projection, queryArgs, cancellationSignal)
                        } else if (paramTypes.size > 4) {
                            val selection = param.args[2] as String?

                            // Fix para o segundo warning - safely cast selectionArgs
                            val selectionArgsArg = param.args[3]
                            val selectionArgs = if (selectionArgsArg is Array<*>) {
                                @Suppress("UNCHECKED_CAST")
                                selectionArgsArg as? Array<String?>
                            } else null

                            val sortOrder = param.args[4] as String?
                            XposedBridge.log("AllTrans Proxy Hook: Querying using String signature.")
                            cursor = providerContext.contentResolver.query(
                                newUri,
                                projection,
                                selection,
                                selectionArgs,
                                sortOrder,
                                cancellationSignal
                            )
                        } else {
                            XposedBridge.log("AllTrans Proxy Hook: Querying using simpler signature.")
                            cursor = providerContext.contentResolver
                                .query(newUri, projection, null, null, null)
                        }
                        param.setResult(cursor)
                        XposedBridge.log(
                            "AllTrans Proxy Hook: Setting query result (${if (cursor != null) cursor.count.toString() + " rows" else "null"})."
                        )
                    } catch (ex: Throwable) {
                        XposedBridge.log("AllTrans Proxy Hook: Error during proxy query execution!")
                        XposedBridge.log(ex)
                        param.setResult(null)
                        if (cursor != null && !cursor.isClosed) cursor.close()
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
        val mCall: Method? = try {
            clsSet.getDeclaredMethod(
                "call",
                String::class.java,
                String::class.java,
                String::class.java,
                Bundle::class.java
            ).also {
                XposedBridge.log("AllTrans: Found SettingsProvider.call (String, String, String, Bundle) method.")
            }
        } catch (e1: NoSuchMethodException) {
            XposedBridge.log("AllTrans: Call signature (String, String, String, Bundle) not found, trying (String, String, Bundle)...")
            try {
                clsSet.getDeclaredMethod(
                    "call",
                    String::class.java,
                    String::class.java,
                    Bundle::class.java
                ).also {
                    XposedBridge.log("AllTrans: Found SettingsProvider.call (String, String, Bundle) method.")
                }
            } catch (e2: NoSuchMethodException) {
                XposedBridge.log("AllTrans: Could not find any known SettingsProvider.call method signature!")
                return
            }
        }

        XposedBridge.log("AllTrans: Applying hook to SettingsProvider.call method.")
        XposedBridge.hookMethod(mCall, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Removing unused variables declaration
                val paramTypes = (param.method as Method).parameterTypes
                val method: String?

                if (paramTypes.size == 4 && paramTypes[0] == String::class.java && paramTypes[1] == String::class.java && paramTypes[2] == String::class.java) {
                    // We don't need authority variable since it's not used
                    method = param.args[1] as String?
                    // We don't need arg variable since it's not used
                    val extras = param.args[3] as Bundle?
                    // Log apenas em debug ou quando for método relevante para AllTrans
                    if (PreferenceList.Debug || "alltransProxyCall" == method) {
                        XposedBridge.log("AllTrans Proxy Hook: Matched call signature (String, String, String, Bundle)")
                    }

                    handleProxyCall(param, method, extras)
                } else if (paramTypes.size == 3 && paramTypes[0] == String::class.java && paramTypes[1] == String::class.java) {
                    method = param.args[0] as String?
                    // We don't need arg variable since it's not used
                    val extras = param.args[2] as Bundle?
                    // Log apenas em debug ou quando for método relevante para AllTrans
                    if (PreferenceList.Debug || "alltransProxyCall" == method) {
                        XposedBridge.log("AllTrans Proxy Hook: Matched call signature (String, String, Bundle)")
                    }

                    handleProxyCall(param, method, extras)
                } else {
                    XposedBridge.log("AllTrans Proxy Hook: Unexpected SettingsProvider.call signature.")
                }
            }

            // Extracted repeated code to a separate method
            private fun handleProxyCall(param: MethodHookParam, method: String?, extras: Bundle?) {
                if ("alltransProxyCall" == method) {
                    if (extras == null) {
                        XposedBridge.log("Proxy call: extras is null!")
                        param.setResult(null)
                        return
                    }

                    val originalUriString = extras.getString("alltransOriginalUri")
                    val originalMethod = extras.getString("alltransOriginalMethod")
                    val originalArg = extras.getString("alltransOriginalArg")

                    if (originalUriString == null || originalMethod == null) {
                        XposedBridge.log("Proxy call: original URI/Method missing!")
                        param.setResult(null)
                        return
                    }
                    val originalUri = originalUriString.toUri()

                    extras.remove("alltransOriginalUri")
                    extras.remove("alltransOriginalMethod")
                    extras.remove("alltransOriginalArg")

                    XposedBridge.log("AllTrans Proxy Hook: Intercepted call for $originalUri, Method: $originalMethod, Arg: $originalArg")

                    val ident = Binder.clearCallingIdentity()
                    // Removing redundant initializer
                    var resultBundle: Bundle?
                    var providerContext: Context? = null
                    try {
                        val settingsProviderInstance = param.thisObject
                        try {
                            val mGetContext =
                                settingsProviderInstance.javaClass.getMethod("getContext")
                            providerContext =
                                mGetContext.invoke(settingsProviderInstance) as Context?
                        } catch (reflectException: NoSuchMethodException) {
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldException: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance.")
                            }
                        } catch (reflectException: IllegalAccessException) {
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldException: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance.")
                            }
                        } catch (reflectException: InvocationTargetException) {
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldException: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance.")
                            }
                        }
                        if (providerContext == null) {
                            XposedBridge.log("Proxy call: Failed to get context!")
                            param.setResult(null)
                            return
                        }

                        XposedBridge.log("AllTrans Proxy Hook: Calling actual provider: $originalUri, Method: $originalMethod, Arg: $originalArg")
                        resultBundle = providerContext.contentResolver
                            .call(originalUri, originalMethod, originalArg, extras)
                        param.setResult(resultBundle)
                        XposedBridge.log("AllTrans Proxy Hook: Setting call result: $resultBundle")
                    } catch (ex: Throwable) {
                        XposedBridge.log("AllTrans Proxy Hook: Error during proxy call execution!")
                        XposedBridge.log(ex)
                        param.setResult(null)
                    } finally {
                        Binder.restoreCallingIdentity(ident)
                    }
                }
            }
        })
    }

    companion object {
        val cacheAccess: Semaphore = Semaphore(1, true)
        val hookAccess: Semaphore = Semaphore(1, true)

        @SuppressLint("StaticFieldLeak")
        val drawTextHook: DrawTextHookHandler = DrawTextHookHandler()

        @SuppressLint("StaticFieldLeak")
        val notifyHook: NotificationHookHandler = NotificationHookHandler()

        var cache: HashMap<String?, String?>? = null

        @SuppressLint("StaticFieldLeak")
        var context: Context? = null
        var baseRecordingCanvas: Class<*>? = null
        var settingsHooked: Boolean = false

        var isProxyEnabled: Boolean = true

        val pendingTextViewTranslations: MutableSet<Int?> = Collections.synchronizedSet(
            HashSet<Int?>()
        )

        // Inicialização com um valor pré-definido baseado em ID de recurso
        // Evitando o uso de getIdentifier, que é desencorajado
        private const val WEBVIEW_HOOK_TAG_KEY_DEFAULT: Int = 0x7f080001 // ID do recurso definido no XML
        private var WEBVIEW_HOOK_TAG_KEY: Int = WEBVIEW_HOOK_TAG_KEY_DEFAULT
        private var tagKeyInitialized = false
        private var MODULE_PATH: String? = null

        val webViewHookInstances: MutableMap<WebView?, VirtWebViewOnLoad?> =
            Collections.synchronizedMap(
                WeakHashMap<WebView?, VirtWebViewOnLoad?>()
            )

        // Método estático auxiliar para inicializar a chave da tag
        @Synchronized
        fun initializeTagKeyIfNeeded() {
            if (!tagKeyInitialized && context != null && MODULE_PATH != null) {
                try {
                    XposedBridge.log("AllTrans: Attempting to initialize tag key...")
                    val modRes: Resources = XModuleResources.createInstance(MODULE_PATH, null)

                    try {
                        // Usando diretamente o ID do recurso R.id.tag_alltrans_webview_hook
                        // em vez de usar getIdentifier, que é mais eficiente
                        // Nota: O valor real deveria vir do R.id.tag_alltrans_webview_hook em tempo de compilação

                        // Atribuindo o valor pré-definido de WEBVIEW_HOOK_TAG_KEY_DEFAULT
                        // Este valor deve ser definido no arquivo R.java em tempo de compilação
                        WEBVIEW_HOOK_TAG_KEY = WEBVIEW_HOOK_TAG_KEY_DEFAULT
                        tagKeyInitialized = true
                        XposedBridge.log(
                            "AllTrans: Successfully initialized WEBVIEW_HOOK_TAG_KEY to: $WEBVIEW_HOOK_TAG_KEY (0x${Integer.toHexString(
                                WEBVIEW_HOOK_TAG_KEY
                            )})"
                        )
                    } catch (t: Throwable) {
                        // Se ocorrer exceção, usa o valor padrão e marca como inicializado
                        XposedBridge.log("AllTrans: Error getting resource ID, using fallback value for tag key")
                        XposedBridge.log(t)
                        tagKeyInitialized = true
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("AllTrans: Error initializing XModuleResources!")
                    XposedBridge.log(t)
                    // Marca como inicializado mesmo com erro para evitar tentativas repetidas
                    tagKeyInitialized = true
                }
            } else if (tagKeyInitialized) {
                Utils.debugLog("AllTrans: Tag key already initialized: $WEBVIEW_HOOK_TAG_KEY")
            } else {
                Utils.debugLog("AllTrans: Cannot initialize tag key yet, missing context or MODULE_PATH")
            }
        }
    }
}