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
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.HashSet
import java.util.WeakHashMap
import java.util.concurrent.Semaphore
import android.util.LruCache
import android.util.Log

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
                } catch (e: Exception) { // Catch broader exception as a fallback
                    XposedBridge.log("AllTrans: Failed to hook Settings Provider for package ${lpparam.packageName}: " + e.message)
                    XposedBridge.log(e) // Log the full exception for more details
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
        } catch (e: XposedHelpers.ClassNotFoundError) {
            Utils.debugLog("AllTrans: BaseRecordingCanvas class not found for ${lpparam.packageName}: " + e.message)
        } catch (e: Exception) {
            Utils.debugLog("AllTrans: Error finding BaseRecordingCanvas for ${lpparam.packageName}: " + e.message)
            XposedBridge.log(e)
        }

        // Garantir que o cache seja inicializado corretamente
        Alltrans.cache // Acessa o getter para garantir a inicialização, se ainda não ocorreu.

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
                    Utils.debugLog("AllTrans: WebView.onDetachedFromWindow hook applied successfully via hookAllMethods for $packageName")
                } catch (e: NoSuchMethodError) {
                    Utils.debugLog("AllTrans: NoSuchMethodError while hooking WebView.onDetachedFromWindow via hookAllMethods for $packageName: ${e.message}")
                } catch (e: SecurityException) {
                    Utils.debugLog("AllTrans: SecurityException while hooking WebView.onDetachedFromWindow via hookAllMethods for $packageName: ${e.message}")
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Failed to hook WebView.onDetachedFromWindow via hookAllMethods for $packageName: ${e.message}")
                    XposedBridge.log(e)
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
                        Utils.debugLog("AllTrans: View.onDetachedFromWindow hook applied successfully for WebView in $packageName")
                    } catch (e2: ClassNotFoundException) {
                        Utils.debugLog("AllTrans: View class not found for onDetachedFromWindow hook in $packageName: ${e2.message}")
                    } catch (e2: NoSuchMethodException) {
                        Utils.debugLog("AllTrans: View.onDetachedFromWindow method not found in $packageName: ${e2.message}")
                    } catch (e2: SecurityException) {
                        Utils.debugLog("AllTrans: SecurityException for View.onDetachedFromWindow hook in $packageName: ${e2.message}")
                    } catch (e2: Exception) {
                        Utils.debugLog("AllTrans: Failed all attempts to hook onDetachedFromWindow for $packageName: ${e2.message}")
                        XposedBridge.log(e2)
                    }
                }
            } catch (e: ClassNotFoundException) {
                Utils.debugLog("AllTrans: WebView class not found in $packageName: " + e.message)
            } catch (e: Exception) {
                Utils.debugLog("AllTrans: Error setting up WebView hooks for $packageName: " + e.message)
                XposedBridge.log(e)
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
            XposedBridge.log("AllTrans: SettingsProvider class not found in ${lpparam.packageName}: " + e.message)
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
                        } catch (e: NoSuchMethodException) {
                            XposedBridge.log("AllTrans Proxy Hook: SettingsProvider.getContext() method not found: ${e.message}")
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext': ${fieldEx.message}")
                                XposedBridge.log(fieldEx)
                            }
                        } catch (e: IllegalAccessException) {
                            XposedBridge.log("AllTrans Proxy Hook: IllegalAccessException for SettingsProvider.getContext(): ${e.message}")
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' after IllegalAccess: ${fieldEx.message}")
                                XposedBridge.log(fieldEx)
                            }
                        } catch (e: InvocationTargetException) {
                            XposedBridge.log("AllTrans Proxy Hook: InvocationTargetException for SettingsProvider.getContext(): ${e.message}")
                            XposedBridge.log(e.targetException ?: e)
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' after InvocationTarget: ${fieldEx.message}")
                                XposedBridge.log(fieldEx)
                            }
                        }

                        if (providerContext == null) {
                            XposedBridge.log("AllTrans Proxy Hook: Provider context is null for URI $newUri, cannot execute proxy query.")
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
                            XposedBridge.log("AllTrans Proxy Hook: Querying using simpler signature for URI $newUri.")
                            cursor = providerContext.contentResolver
                                .query(newUri, projection, null, null, null)
                        }
                        param.setResult(cursor)
                        XposedBridge.log(
                            "AllTrans Proxy Hook: Setting query result for URI $newUri (${if (cursor != null) cursor.count.toString() + " rows" else "null"})."
                        )
                    } catch (e: Exception) {
                        XposedBridge.log("AllTrans Proxy Hook: Error during proxy query execution for URI $newUri: ${e.message}")
                        XposedBridge.log(e)
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
                        } catch (e: NoSuchMethodException) {
                            XposedBridge.log("AllTrans Proxy Hook: SettingsProvider.getContext() method not found for call: ${e.message}")
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' for call: ${fieldEx.message}")
                                XposedBridge.log(fieldEx)
                            }
                        } catch (e: IllegalAccessException) {
                            XposedBridge.log("AllTrans Proxy Hook: IllegalAccessException for SettingsProvider.getContext() for call: ${e.message}")
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' after IllegalAccess for call: ${fieldEx.message}")
                                XposedBridge.log(fieldEx)
                            }
                        } catch (e: InvocationTargetException) {
                            XposedBridge.log("AllTrans Proxy Hook: InvocationTargetException for SettingsProvider.getContext() for call: ${e.message}")
                            XposedBridge.log(e.targetException ?: e)
                            try {
                                providerContext = XposedHelpers.getObjectField(
                                    settingsProviderInstance,
                                    "mContext"
                                ) as Context?
                            } catch (fieldEx: Exception) {
                                XposedBridge.log("AllTrans Proxy Hook: Failed to get context from SettingsProvider field 'mContext' after InvocationTarget for call: ${fieldEx.message}")
                                XposedBridge.log(fieldEx)
                            }
                        }
                        if (providerContext == null) {
                            XposedBridge.log("Proxy call: Failed to get context for $originalUri, Method: $originalMethod!")
                            param.setResult(null)
                            return
                        }

                        XposedBridge.log("AllTrans Proxy Hook: Calling actual provider: $originalUri, Method: $originalMethod, Arg: $originalArg")
                        resultBundle = providerContext.contentResolver
                            .call(originalUri, originalMethod, originalArg, extras)
                        param.setResult(resultBundle)
                        XposedBridge.log("AllTrans Proxy Hook: Setting call result for $originalUri, Method: $originalMethod: $resultBundle")
                    } catch (e: Exception) {
                        XposedBridge.log("AllTrans Proxy Hook: Error during proxy call execution for $originalUri, Method: $originalMethod: ${e.message}")
                        XposedBridge.log(e)
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

        @SuppressLint("StaticFieldLeak")
        val drawTextHook: DrawTextHookHandler = DrawTextHookHandler()

        @SuppressLint("StaticFieldLeak")
        val notifyHook: NotificationHookHandler = NotificationHookHandler()

        // Alterado de HashMap para LruCache com inicialização thread-safe
        const val CACHE_MAX_SIZE = 200
        private var _cache: LruCache<String, String>? = null

        var cache: LruCache<String, String>?
            get() {
                if (_cache == null) {
                    synchronized(Alltrans::class.java) {
                        if (_cache == null) {
                            _cache = LruCache(CACHE_MAX_SIZE)
                            Utils.debugLog("AllTrans: LruCache inicializado com tamanho $CACHE_MAX_SIZE")
                        }
                    }
                }
                return _cache
            }
            set(value) {
                synchronized(Alltrans::class.java) {
                    _cache = value
                }
            }

        @SuppressLint("StaticFieldLeak")
        var context: Context? = null
        var baseRecordingCanvas: Class<*>? = null
        var settingsHooked: Boolean = false

        var isProxyEnabled: Boolean = true

        // Melhoria na definição do conjunto de traduções pendentes
        val pendingTextViewTranslations: MutableSet<Int> = Collections.synchronizedSet(
            HashSet<Int>()
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

        // Inicialização lazy thread-safe para o BatchTranslationManager
        val batchManager: BatchTranslationManager by lazy {
            synchronized(Alltrans::class.java) {
                BatchTranslationManager()
            }
        }

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
                        XposedBridge.log("AllTrans: Error getting resource ID for WEBVIEW_HOOK_TAG_KEY, using fallback value. Details: ${t.message}")
                        XposedBridge.log(t)
                        WEBVIEW_HOOK_TAG_KEY = WEBVIEW_HOOK_TAG_KEY_DEFAULT // Ensure fallback is set
                        tagKeyInitialized = true
                    }
                } catch (e: Resources.NotFoundException) {
                    XposedBridge.log("AllTrans: XModuleResources.createInstance failed or resource not found for WEBVIEW_HOOK_TAG_KEY: ${e.message}")
                    XposedBridge.log(e)
                    WEBVIEW_HOOK_TAG_KEY = WEBVIEW_HOOK_TAG_KEY_DEFAULT // Ensure fallback is set
                    tagKeyInitialized = true
                } catch (e: Exception) { // Catch broader exception as a fallback
                    XposedBridge.log("AllTrans: Error initializing XModuleResources or getting tag key: ${e.message}")
                    XposedBridge.log(e)
                    // Marca como inicializado mesmo com erro para evitar tentativas repetidas
                    WEBVIEW_HOOK_TAG_KEY = WEBVIEW_HOOK_TAG_KEY_DEFAULT // Ensure fallback is set
                    tagKeyInitialized = true
                }
            } else if (tagKeyInitialized) {
                Utils.debugLog("AllTrans: Tag key already initialized: $WEBVIEW_HOOK_TAG_KEY")
            } else {
                Utils.debugLog("AllTrans: Cannot initialize tag key yet, missing context ($context) or MODULE_PATH ($MODULE_PATH)")
            }
        }
    }
}