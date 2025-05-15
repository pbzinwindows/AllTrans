/*
 * Copyright 2017 Akhil Kedia
 * // ... (copyright header) ...
 */
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
            if (MODULE_PATH == null && lpparam.appInfo != null && lpparam.appInfo.sourceDir != null) {
                MODULE_PATH = lpparam.appInfo.sourceDir // Usa o sourceDir do nosso próprio AppInfo
                XposedBridge.log("AllTrans: Module path set from own package appInfo.sourceDir: " + MODULE_PATH)

                // Não tenta inicializar a chave aqui, isso será feito quando o contexto estiver disponível
                utils.debugLog("AllTrans: Module path set, will initialize tag key when context is available")
            }

            // Aplica hooks contextuais mesmo para o pacote AllTrans
            utils.tryHookMethod(Application::class.java, "onCreate", appOnCreateHookHandler())
            utils.tryHookMethod(
                ContextWrapper::class.java,
                "attachBaseContext",
                Context::class.java,
                AttachBaseContextHookHandler()
            )
            return  // Não aplica outros hooks ao próprio AllTrans
        }

        // --- Processar outros pacotes ---
        if (lpparam.appInfo == null) {
            XposedBridge.log("AllTrans: Skipping package with null appInfo: " + lpparam.packageName)
            return
        }

        if ((lpparam.appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
            // Permite hookar o Settings Provider e o próprio pacote AllTrans (se necessário para contexto/path)
            if ("com.android.providers.settings" != lpparam.packageName && "akhil.alltrans" != lpparam.packageName) {
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
            cache = HashMap<String?, String?>()
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
                try {
                    utils.tryHookMethod(
                        webViewClass,
                        "onDetachedFromWindow",
                        object : XC_MethodHook() {
                            @Throws(Throwable::class)
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val webView = param.thisObject as? WebView
                                if (webView != null) {
                                    val removedInstance = webViewHookInstances.remove(webView)
                                    if (removedInstance != null) {
                                        utils.debugLog("AllTrans: Cleaned up WebView instance on detach: " + webView.hashCode())
                                    }
                                }
                            }
                        }
                    )
                    utils.debugLog("AllTrans: WebView.onDetachedFromWindow hook applied successfully")
                } catch (e: Throwable) {
                    utils.debugLog("AllTrans: Failed to hook WebView.onDetachedFromWindow: " + e.message)
                }
            } catch (e: ClassNotFoundException) {
                utils.debugLog("AllTrans: WebView class not found in " + packageName)
            } catch (e: Throwable) {
                utils.debugLog("AllTrans: Error setting up WebView hooks: " + e.message)
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
        if (clsSet != null) {
            hookSettingsQuery(clsSet)
            hookSettingsCall(clsSet)
        }
    }

    @Throws(Throwable::class)
    private fun hookSettingsQuery(clsSet: Class<*>) {
        XposedBridge.log("AllTrans: Trying to hook SettingsProvider.query method.")
        var mQuery: Method? = null
        try {
            mQuery = clsSet.getDeclaredMethod(
                "query",
                Uri::class.java,
                Array<String>::class.java,
                Bundle::class.java,
                CancellationSignal::class.java
            )
            XposedBridge.log("AllTrans: Found SettingsProvider.query (Uri, String[], Bundle, CancellationSignal) method.")
        } catch (e1: NoSuchMethodException) {
            XposedBridge.log("AllTrans: Query signature with Bundle not found, trying (Uri, String[], String, String[], String, CancellationSignal)...")
            try {
                mQuery = clsSet.getDeclaredMethod(
                    "query",
                    Uri::class.java,
                    Array<String>::class.java,
                    String::class.java,
                    Array<String>::class.java,
                    String::class.java,
                    CancellationSignal::class.java
                )
                XposedBridge.log("AllTrans: Found SettingsProvider.query (Uri, String[], String, String[], String, CancellationSignal) method.")
            } catch (e2: NoSuchMethodException) {
                XposedBridge.log("AllTrans: Query signature with CancellationSignal not found, trying (Uri, String[], String, String[], String)...")
                try {
                    mQuery = clsSet.getDeclaredMethod(
                        "query",
                        Uri::class.java,
                        Array<String>::class.java,
                        String::class.java,
                        Array<String>::class.java,
                        String::class.java
                    )
                    XposedBridge.log("AllTrans: Found SettingsProvider.query (Uri, String[], String, String[], String) method.")
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
                if (param.args == null || param.args.size == 0 || (param.args[0] !is Uri)) return
                val uri = param.args[0] as Uri
                val uriString = uri.toString()
                if (uriString.startsWith("content://settings/system/alltransProxyProviderURI/")) {
                    XposedBridge.log("AllTrans Proxy Hook: Intercepted query URI: " + uriString)
                    val originalProviderUriString = uriString.replaceFirst(
                        "content://settings/system/alltransProxyProviderURI/".toRegex(),
                        "content://"
                    )
                    XposedBridge.log("AllTrans Proxy Hook: Rewritten query URI: " + originalProviderUriString)
                    val new_uri = Uri.parse(originalProviderUriString)
                    if (new_uri == null) {
                        param.setResult(null)
                        return
                    }

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
                        } catch (e_reflect: NoSuchMethodException) {
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (e_field: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance.")
                            }
                        } catch (e_reflect: IllegalAccessException) {
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (e_field: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance.")
                            }
                        } catch (e_reflect: InvocationTargetException) {
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (e_field: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance.")
                            }
                        }

                        if (providerContext == null) {
                            XposedBridge.log("AllTrans Proxy Hook: Provider context is null, cannot execute proxy query.")
                            param.setResult(null)
                            return
                        }

                        // Fix for the first warning - safely cast projection
                        val projectionArg = param.args[1]
                        val projection = if (projectionArg is Array<*>) {
                            @Suppress("UNCHECKED_CAST")
                            projectionArg as? Array<String?>
                        } else null

                        var queryArgs: Bundle? = null
                        var selection: String? = null
                        var selectionArgs: Array<String?>? = null
                        var sortOrder: String? = null
                        val paramTypes = (param.method as Method).getParameterTypes()
                        var cancellationSignal: CancellationSignal? = null

                        for (arg in param.args) {
                            if (arg is CancellationSignal) {
                                cancellationSignal = arg as CancellationSignal?
                                break
                            }
                        }

                        if (paramTypes.size > 2 && paramTypes[2] == Bundle::class.java) {
                            queryArgs = param.args[2] as Bundle?
                            if (queryArgs != null) {
                                selection =
                                    queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
                                selectionArgs =
                                    queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
                                sortOrder =
                                    queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
                            }
                            XposedBridge.log("AllTrans Proxy Hook: Querying using Bundle signature.")
                            cursor = providerContext.getContentResolver()
                                .query(new_uri, projection, queryArgs, cancellationSignal)
                        } else if (paramTypes.size > 4) {
                            selection = param.args[2] as String?

                            // Fix for the second warning - safely cast selectionArgs
                            val selectionArgsArg = param.args[3]
                            selectionArgs = if (selectionArgsArg is Array<*>) {
                                @Suppress("UNCHECKED_CAST")
                                selectionArgsArg as? Array<String?>
                            } else null

                            sortOrder = param.args[4] as String?
                            XposedBridge.log("AllTrans Proxy Hook: Querying using String signature.")
                            cursor = providerContext.getContentResolver().query(
                                new_uri,
                                projection,
                                selection,
                                selectionArgs,
                                sortOrder,
                                cancellationSignal
                            )
                        } else {
                            XposedBridge.log("AllTrans Proxy Hook: Querying using simpler signature.")
                            cursor = providerContext.getContentResolver()
                                .query(new_uri, projection, null, null, null)
                        }
                        param.setResult(cursor)
                        XposedBridge.log(
                            "AllTrans Proxy Hook: Setting query result (" + (if (cursor != null) cursor.getCount()
                                .toString() + " rows" else "null") + ")."
                        )
                    } catch (ex: Throwable) {
                        XposedBridge.log("AllTrans Proxy Hook: Error during proxy query execution!")
                        XposedBridge.log(ex)
                        param.setResult(null)
                        if (cursor != null && !cursor.isClosed()) cursor.close()
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
        try {
            mCall = clsSet.getDeclaredMethod(
                "call",
                String::class.java,
                String::class.java,
                String::class.java,
                Bundle::class.java
            )
            XposedBridge.log("AllTrans: Found SettingsProvider.call (String, String, String, Bundle) method.")
        } catch (e1: NoSuchMethodException) {
            XposedBridge.log("AllTrans: Call signature (String, String, String, Bundle) not found, trying (String, String, Bundle)...")
            try {
                mCall = clsSet.getDeclaredMethod(
                    "call",
                    String::class.java,
                    String::class.java,
                    Bundle::class.java
                )
                XposedBridge.log("AllTrans: Found SettingsProvider.call (String, String, Bundle) method.")
            } catch (e2: NoSuchMethodException) {
                XposedBridge.log("AllTrans: Could not find any known SettingsProvider.call method signature!")
                return
            }
        }

        XposedBridge.log("AllTrans: Applying hook to SettingsProvider.call method.")
        XposedBridge.hookMethod(mCall, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                var authority: String? = null
                val method: String?
                val arg: String?
                val extras: Bundle?
                var originalUri: Uri? = null

                val paramTypes = (param.method as Method).getParameterTypes()
                if (paramTypes.size == 4 && paramTypes[0] == String::class.java && paramTypes[1] == String::class.java && paramTypes[2] == String::class.java) {
                    authority = param.args[0] as String?
                    method = param.args[1] as String?
                    arg = param.args[2] as String?
                    extras = param.args[3] as Bundle?
                    // Log apenas em debug ou quando for método relevante para AllTrans
                    if (PreferenceList.Debug || "alltransProxyCall" == method) {
                        XposedBridge.log("AllTrans Proxy Hook: Matched call signature (String, String, String, Bundle)")
                    }
                } else if (paramTypes.size == 3 && paramTypes[0] == String::class.java && paramTypes[1] == String::class.java) {
                    method = param.args[0] as String?
                    arg = param.args[1] as String?
                    extras = param.args[2] as Bundle?
                    // Log apenas em debug ou quando for método relevante para AllTrans
                    if (PreferenceList.Debug || "alltransProxyCall" == method) {
                        XposedBridge.log("AllTrans Proxy Hook: Matched call signature (String, String, Bundle)")
                    }
                } else {
                    XposedBridge.log("AllTrans Proxy Hook: Unexpected SettingsProvider.call signature.")
                    return
                }

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
                    originalUri = Uri.parse(originalUriString)
                    if (originalUri == null) {
                        XposedBridge.log("Proxy call: Failed to parse original URI!")
                        param.setResult(null)
                        return
                    }

                    extras.remove("alltransOriginalUri")
                    extras.remove("alltransOriginalMethod")
                    extras.remove("alltransOriginalArg")

                    XposedBridge.log("AllTrans Proxy Hook: Intercepted call for " + originalUri + ", Method: " + originalMethod + ", Arg: " + originalArg)

                    val ident = Binder.clearCallingIdentity()
                    var resultBundle: Bundle? = null
                    var providerContext: Context? = null
                    try {
                        val settingsProviderInstance = param.thisObject
                        try {
                            val mGetContext =
                                settingsProviderInstance.javaClass.getMethod("getContext")
                            providerContext =
                                mGetContext.invoke(settingsProviderInstance) as Context?
                        } catch (e_reflect: NoSuchMethodException) {
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (e_field: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance.")
                            }
                        } catch (e_reflect: IllegalAccessException) {
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (e_field: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance.")
                            }
                        } catch (e_reflect: InvocationTargetException) {
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (e_field: Throwable) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider instance.")
                            }
                        }
                        if (providerContext == null) {
                            XposedBridge.log("Proxy call: Failed to get context!")
                            param.setResult(null)
                            return
                        }

                        XposedBridge.log("AllTrans Proxy Hook: Calling actual provider: " + originalUri + ", Method: " + originalMethod + ", Arg: " + originalArg)
                        resultBundle = providerContext.getContentResolver()
                            .call(originalUri, originalMethod, originalArg, extras)
                        param.setResult(resultBundle)
                        XposedBridge.log("AllTrans Proxy Hook: Setting call result: " + resultBundle)
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

        var androidVersion15OrAbove: Boolean = Build.VERSION.SDK_INT >= 35
        var isProxyEnabled: Boolean = true

        val pendingTextViewTranslations: MutableSet<Int?> = Collections.synchronizedSet<Int?>(
            HashSet<Int?>()
        )

        // Inicialização com um valor padrão, que será substituído quando o recurso estiver disponível
        var WEBVIEW_HOOK_TAG_KEY: Int = 0x7f080001 // Valor arbitrário não-zero como fallback
        private var tagKeyInitialized = false
        private var MODULE_PATH: String? = null

        val webViewHookInstances: MutableMap<WebView?, VirtWebViewOnLoad?> =
            Collections.synchronizedMap<WebView?, VirtWebViewOnLoad?>(
                WeakHashMap<WebView?, VirtWebViewOnLoad?>()
            )

        // Método estático auxiliar para inicializar a chave da tag
        @Synchronized
        fun initializeTagKeyIfNeeded() {
            if (!tagKeyInitialized && context != null && MODULE_PATH != null) {
                try {
                    XposedBridge.log("AllTrans: Attempting to initialize tag key...")
                    val modRes: Resources = XModuleResources.createInstance(MODULE_PATH, null)
                    val packageName = "akhil.alltrans"

                    try {
                        // Tenta obter o ID diretamente do módulo de recursos
                        WEBVIEW_HOOK_TAG_KEY = modRes.getIdentifier("tag_alltrans_webview_hook", "id", packageName)

                        if (WEBVIEW_HOOK_TAG_KEY != 0) {
                            tagKeyInitialized = true
                            XposedBridge.log(
                                "AllTrans: Successfully initialized WEBVIEW_HOOK_TAG_KEY to: " + WEBVIEW_HOOK_TAG_KEY + " (0x" + Integer.toHexString(
                                    WEBVIEW_HOOK_TAG_KEY
                                ) + ")"
                            )
                        } else {
                            // Se falhar, usa o valor padrão e marca como inicializado para evitar novas tentativas
                            XposedBridge.log("AllTrans: Could not find resource ID, using fallback value for tag key")
                            tagKeyInitialized = true
                        }
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
                utils.debugLog("AllTrans: Tag key already initialized: " + WEBVIEW_HOOK_TAG_KEY)
            } else {
                utils.debugLog("AllTrans: Cannot initialize tag key yet, missing context or MODULE_PATH")
            }
        }
    }
}