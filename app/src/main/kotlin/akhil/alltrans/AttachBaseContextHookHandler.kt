package akhil.alltrans

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.graphics.Paint
import android.graphics.text.MeasuredText
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.TextView.BufferType
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

internal class AttachBaseContextHookHandler : XC_MethodHook() {
    override fun afterHookedMethod(methodHookParam: MethodHookParam) {
        try {
            if (methodHookParam.args == null || methodHookParam.args.size == 0 || (methodHookParam.args[0] !is Context)) {
                utils.debugLog("AllTrans: Invalid args in attachBaseContext hook.")
                return
            }
            val baseContext = methodHookParam.args[0] as Context
            val packageName = baseContext.packageName

            // Verificar se é um serviço do sistema que deve ser ignorado
            if (isSystemServiceToSkip(packageName)) {
                utils.debugLog("AllTrans: Skipping system service: $packageName")
                return
            }

            utils.debugLog("AllTrans: in after attachBaseContext of ContextWrapper for package $packageName")

            // Obter o contexto da aplicação
            var applicationContext = baseContext.applicationContext
            if (applicationContext == null) {
                if (methodHookParam.thisObject is ContextWrapper) {
                    try {
                        applicationContext = (methodHookParam.thisObject as ContextWrapper).applicationContext
                    } catch (e: Exception) {
                        utils.debugLog("AllTrans: Error getting context from thisObject in attachBaseContext.")
                    }
                }
                if (applicationContext == null) {
                    utils.debugLog("AllTrans: returning because application context is null for package $packageName")
                    return
                }
            }

            // Define o contexto global se ainda não estiver definido
            if (alltrans.Companion.context == null) {
                alltrans.Companion.context = applicationContext
                utils.debugLog("AllTrans: Application context set successfully from attachBaseContext for package: $packageName")
            }

            // Sempre tenta inicializar a chave da tag
            alltrans.Companion.initializeTagKeyIfNeeded()

            // Continua com a leitura das preferências e aplicação dos hooks
            readPrefAndHook(applicationContext)
        } catch (e: Throwable) {
            utils.debugLog("Caught Exception in attachBaseContext ${Log.getStackTraceString(e)}")
            XposedBridge.log(e)
        }
    }

    /**
     * Verifica se o pacote é um serviço do sistema que deve ser ignorado
     */
    private fun isSystemServiceToSkip(packageName: String): Boolean {
        return packageName.startsWith("com.android.server.") ||
                packageName.startsWith("com.android.internal.") ||
                packageName.startsWith("com.android.location.") ||
                packageName == "android" ||
                packageName == "system" ||
                packageName == "com.android.systemui" ||
                packageName == "com.android.providers.settings"
    }

    companion object {
        // Função getPreferencesViaProviders melhorada para melhor gestão de recursos
        private fun getPreferencesViaProviders(context: Context?, packageName: String?): Bundle? {
            if (context == null || packageName == null) return null
            val resultBundle = Bundle()
            var globalPref: String? = null
            var localPref: String? = null
            var cursor: Cursor? = null
            val identity = Binder.clearCallingIdentity()

            try {
                // Tentar primeiro via proxy
                if (alltrans.Companion.isProxyEnabled) {
                    try {
                        val proxyUri = Uri.parse("content://settings/system/alltransProxyProviderURI/akhil.alltrans.sharedPrefProvider/$packageName")
                        cursor = context.contentResolver.query(proxyUri, null, null, null, null)
                        if (cursor != null && cursor.moveToFirst()) {
                            val dataColumnIndex = 0
                            if (cursor.columnCount > dataColumnIndex) {
                                globalPref = cursor.getString(dataColumnIndex)
                                if (cursor.moveToNext()) localPref = cursor.getString(dataColumnIndex)
                                utils.debugLog("Proxy query successful for $packageName.")
                            } else {
                                utils.debugLog("Proxy query cursor has no columns.")
                            }
                        } else {
                            utils.debugLog("Proxy query failed or empty for $packageName.")
                        }
                    } catch (e: Exception) {
                        utils.debugLog("Proxy query exception: ${e.message}")
                    } finally {
                        cursor?.close()
                        cursor = null
                    }
                } else {
                    utils.debugLog("Proxy is disabled, skipping proxy query.")
                }

                // Se o proxy falhar, tentar conexão direta
                if (globalPref == null) {
                    try {
                        val directUri = Uri.parse("content://akhil.alltrans.sharedPrefProvider/$packageName")
                        cursor = context.contentResolver.query(directUri, null, null, null, null)
                        if (cursor != null && cursor.moveToFirst()) {
                            val dataColumnIndex = 0
                            if (cursor.columnCount > dataColumnIndex) {
                                globalPref = cursor.getString(dataColumnIndex)
                                if (cursor.moveToNext()) localPref = cursor.getString(dataColumnIndex)
                                utils.debugLog("Direct query successful for $packageName.")
                            } else {
                                utils.debugLog("Direct query cursor has no columns.")
                            }
                        } else {
                            utils.debugLog("Direct query failed or empty for $packageName.")
                        }
                    } catch (e: Exception) {
                        utils.debugLog("Direct query exception: ${Log.getStackTraceString(e)}")
                    } finally {
                        cursor?.close()
                    }
                }

                // Retornar os resultados
                if (globalPref != null) {
                    resultBundle.putString("globalPref", globalPref)
                    resultBundle.putString("localPref", localPref ?: globalPref)
                    return resultBundle
                } else {
                    Log.e("AllTrans", "Failed to get prefs for $packageName")
                    return null
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        // Função readPrefAndHook otimizada
        fun readPrefAndHook(context: Context?) {
            if (context == null) return
            val packageName = context.packageName
            utils.debugLog("readPrefAndHook for $packageName")

            // Verificar se é um aplicativo do sistema que deve ser ignorado
            if (isSystemAppToSkip(context)) {
                utils.debugLog("AllTrans: Skipping system app: $packageName")
                return
            }

            // Obter preferências
            val prefsBundle = getPreferencesViaProviders(context, packageName) ?: return
            val globalPref = prefsBundle.getString("globalPref")
            val localPref = prefsBundle.getString("localPref")
            if (globalPref == null || localPref == null) return

            // Aplicar preferências
            PreferenceList.getPref(globalPref, localPref, packageName)
            utils.Debug = PreferenceList.Debug

            // Verificar se o módulo está habilitado para este pacote
            if (!PreferenceList.Enabled || !PreferenceList.LocalEnabled) {
                utils.debugLog("AllTrans disabled for $packageName")
                return
            }
            utils.debugLog("AllTrans Enabled for $packageName")

            // Gerenciar cache
            manageCache(context)

            // Aplicar hooks baseados nas preferências
            applyTextViewHooks()
            applyWebViewHooks(packageName)
            applyDrawTextHooks()
            applyNotificationHooks()
        }

        /**
         * Verifica se é um aplicativo do sistema que deve ser ignorado
         */
        private fun isSystemAppToSkip(context: Context): Boolean {
            val packageName = context.packageName

            // Lista de pacotes do sistema para ignorar
            val systemSkipList = listOf(
                "com.android.systemui",
                "com.android.settings",
                "com.android.launcher3",
                "com.android.keyguard",
                "com.android.providers",
                "com.android.bluetooth",
                "com.android.phone",
                "com.google.android.webview"
            )

            // Verificar se está na lista de ignorados
            for (skipPkg in systemSkipList) {
                if (packageName.startsWith(skipPkg)) {
                    return true
                }
            }

            // Verificar se é um app do sistema
            try {
                val appInfo = context.applicationInfo
                if ((appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                    // É um app do sistema mas não está na lista branca
                    if (packageName != "akhil.alltrans" && packageName != "com.android.providers.settings") {
                        return true
                    }
                }
            } catch (e: Exception) {
                utils.debugLog("Error checking if system app: ${e.message}")
            }

            return false
        }

        /**
         * Gerencia o cache do AllTrans
         */
        private fun manageCache(context: Context) {
            if (PreferenceList.Caching) {
                clearCacheIfNeeded(context, PreferenceList.CachingTime)
                loadCacheFromDisk(context)
            } else {
                // Limpar o cache se o caching estiver desativado
                alltrans.Companion.cacheAccess.acquireUninterruptibly()
                try {
                    if (alltrans.Companion.cache == null) {
                        alltrans.Companion.cache = HashMap()
                    } else {
                        alltrans.Companion.cache?.clear()
                    }
                    utils.debugLog("Caching disabled, cache cleared.")
                } finally {
                    if (alltrans.Companion.cacheAccess.availablePermits() == 0) {
                        alltrans.Companion.cacheAccess.release()
                    }
                }
            }
        }

        /**
         * Carrega o cache do disco
         */
        private fun loadCacheFromDisk(context: Context) {
            var fis: FileInputStream? = null
            var ois: ObjectInputStream? = null
            try {
                fis = context.openFileInput("AllTransCache")
                ois = ObjectInputStream(fis)
                alltrans.Companion.cacheAccess.acquireUninterruptibly()

                // Criar referência de cache se for nula
                if (alltrans.Companion.cache == null) {
                    alltrans.Companion.cache = HashMap()
                }

                // Limpar e atualizar o cache
                val cacheRef = alltrans.Companion.cache
                cacheRef?.clear()

                // Ler os dados em cache - Com verificação de tipo
                val readObj = ois.readObject()
                if (readObj is HashMap<*, *>) {
                    // Verificação de tipo antes do cast
                    @Suppress("UNCHECKED_CAST")
                    val typedMap = readObj as HashMap<String?, String?>
                    cacheRef?.putAll(typedMap)
                    utils.debugLog("Cache loaded successfully. Size: ${cacheRef?.size ?: 0}")
                } else {
                    utils.debugLog("Cache object is not of type HashMap<String?, String?>")
                }
            } catch (fnf: FileNotFoundException) {
                utils.debugLog("Cache file not found, starting fresh.")
                if (alltrans.Companion.cache == null) alltrans.Companion.cache = HashMap()
            } catch (e: Throwable) {
                utils.debugLog("Error reading cache: ${Log.getStackTraceString(e)}")
                if (alltrans.Companion.cache == null) alltrans.Companion.cache = HashMap()
            } finally {
                try { ois?.close() } catch (ignored: IOException) {}
                try { fis?.close() } catch (ignored: IOException) {}
                if (alltrans.Companion.cacheAccess.availablePermits() == 0) alltrans.Companion.cacheAccess.release()
            }
        }

        /**
         * Aplica hooks aos métodos TextView
         */
        private fun applyTextViewHooks() {
            val setTextHook = SetTextHookHandler()

            if (PreferenceList.SetText) {
                utils.tryHookMethod(
                    TextView::class.java,
                    "setText",
                    CharSequence::class.java,
                    BufferType::class.java,
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    setTextHook
                )
            }

            if (PreferenceList.SetHint) {
                utils.tryHookMethod(
                    TextView::class.java,
                    "setHint",
                    CharSequence::class.java,
                    setTextHook
                )
            }
        }

        /**
         * Aplica hooks aos métodos WebView
         */
        private fun applyWebViewHooks(packageName: String) {
            if (PreferenceList.LoadURL) {
                utils.debugLog("Applying WebView hooks for $packageName")

                // Hook onAttachedToWindow em vez do construtor
                utils.tryHookMethod(
                    WebView::class.java,
                    "onAttachedToWindow",
                    WebViewOnCreateHookHandler()
                )

                // Hook no método setWebViewClient
                utils.tryHookMethod(
                    WebView::class.java,
                    "setWebViewClient",
                    WebViewClient::class.java,
                    WebViewSetClientHookHandler()
                )
            }
        }

        /**
         * Aplica hooks aos métodos DrawText
         */
        private fun applyDrawTextHooks() {
            if (PreferenceList.DrawText && alltrans.Companion.baseRecordingCanvas != null) {
                utils.debugLog("Applying DrawText hooks")
                val canvasClass: Class<*>? = alltrans.Companion.baseRecordingCanvas

                // Hooks básicos de desenho de texto
                utils.tryHookMethod(
                    canvasClass,
                    "drawText",
                    CharArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Paint::class.java,
                    alltrans.Companion.drawTextHook
                )

                utils.tryHookMethod(
                    canvasClass,
                    "drawText",
                    String::class.java,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Paint::class.java,
                    alltrans.Companion.drawTextHook
                )

                utils.tryHookMethod(
                    canvasClass,
                    "drawText",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Paint::class.java,
                    alltrans.Companion.drawTextHook
                )

                utils.tryHookMethod(
                    canvasClass,
                    "drawText",
                    CharSequence::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Paint::class.java,
                    alltrans.Companion.drawTextHook
                )

                // Hooks para API 23+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    utils.tryHookMethod(
                        canvasClass,
                        "drawText",
                        CharArray::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Paint::class.java,
                        alltrans.Companion.drawTextHook
                    )

                    utils.tryHookMethod(
                        canvasClass,
                        "drawTextRun",
                        CharArray::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Paint::class.java,
                        alltrans.Companion.drawTextHook
                    )

                    utils.tryHookMethod(
                        canvasClass,
                        "drawTextRun",
                        CharSequence::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Paint::class.java,
                        alltrans.Companion.drawTextHook
                    )
                }

                // Hooks para API 29+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    utils.tryHookMethod(
                        canvasClass,
                        "drawTextRun",
                        MeasuredText::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Paint::class.java,
                        alltrans.Companion.drawTextHook
                    )
                }
            }
        }

        /**
         * Aplica hooks aos métodos de Notificação
         */
        private fun applyNotificationHooks() {
            if (PreferenceList.Notif) {
                utils.debugLog("Applying Notification hooks")

                // Hook básico da notificação
                XposedBridge.hookAllMethods(
                    NotificationManager::class.java,
                    "notify",
                    alltrans.Companion.notifyHook
                )

                // Hook para API 21+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            NotificationManager::class.java,
                            "notifyAsUser",
                            String::class.java,
                            Int::class.javaPrimitiveType,
                            Notification::class.java,
                            UserHandle::class.java,
                            alltrans.Companion.notifyHook
                        )
                    } catch (t: Throwable) {
                        utils.debugLog("notifyAsUser hook failed (might not exist): ${t.message}")
                    }
                }
            }
        }

        /**
         * Limpa o cache se necessário com base no tempo
         */
        fun clearCacheIfNeeded(context: Context?, cachingTime: Long) {
            if (cachingTime <= 0L || context == null) return

            alltrans.Companion.cacheAccess.acquireUninterruptibly()
            var lastClearTime: Long = 0
            var fis: FileInputStream? = null
            var ois: ObjectInputStream? = null

            try {
                fis = context.openFileInput("AllTransCacheClear")
                ois = ObjectInputStream(fis)
                lastClearTime = ois.readObject() as Long
            } catch (ignored: Throwable) {
                // Se falhar ao ler, assume que nunca limpou antes
            } finally {
                try { ois?.close() } catch (ignored: IOException) {}
                try { fis?.close() } catch (ignored: IOException) {}
            }

            val currentTime = System.currentTimeMillis()
            val timeSinceLastClear = currentTime - lastClearTime

            if (lastClearTime == 0L || timeSinceLastClear > cachingTime) {
                utils.debugLog("Time since last cache clear: ${timeSinceLastClear}ms, clearing cache...")
                var fos: FileOutputStream? = null
                var oos: ObjectOutputStream? = null

                try {
                    // Atualizar o tempo da última limpeza
                    fos = context.openFileOutput("AllTransCacheClear", Context.MODE_PRIVATE)
                    oos = ObjectOutputStream(fos)
                    oos.writeObject(currentTime)

                    // Limpar o arquivo de cache e o objeto cache em memória
                    context.deleteFile("AllTransCache")
                    alltrans.Companion.cache?.clear()
                    utils.debugLog("Cache cleared successfully.")
                } catch (e: Throwable) {
                    Log.e("AllTrans", "Error clearing cache", e)
                } finally {
                    try { oos?.close() } catch (ignored: IOException) {}
                    try { fos?.close() } catch (ignored: IOException) {}
                }
            } else {
                utils.debugLog("No need to clear cache. Time since last clear: ${timeSinceLastClear}ms")
            }

            if (alltrans.Companion.cacheAccess.availablePermits() == 0) {
                alltrans.Companion.cacheAccess.release()
            }
        }
    }
} // Fim da classe AttachBaseContextHookHandler