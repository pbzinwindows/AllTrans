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

            // CORREÇÃO: Verificamos explicitamente se é o AllTrans e saímos imediatamente
            if (packageName == "akhil.alltrans") {
                utils.debugLog("AllTrans: Skipping self hook for AllTrans app")
                return
            }

            if (isSystemServiceToSkip(packageName)) {
                utils.debugLog("AllTrans: Skipping system service: $packageName")
                return
            }

            utils.debugLog("AllTrans: in after attachBaseContext of ContextWrapper for package $packageName")

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

            if (alltrans.Companion.context == null) {
                alltrans.Companion.context = applicationContext
                utils.debugLog("AllTrans: Application context set successfully from attachBaseContext for package: $packageName")
            }

            alltrans.Companion.initializeTagKeyIfNeeded()
            readPrefAndHook(applicationContext)

        } catch (e: Throwable) {
            utils.debugLog("Caught Exception in attachBaseContext ${Log.getStackTraceString(e)}")
            XposedBridge.log(e)
        }
    }

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
        private fun getPreferencesViaProviders(context: Context?, packageName: String?): Bundle? {
            if (context == null || packageName == null) return null

            // CORREÇÃO: Verifica se é o próprio AllTrans e retorna null para evitar auto-tradução
            if (packageName == "akhil.alltrans") {
                utils.debugLog("AllTrans: Skipping preferences lookup for self (AllTrans app)")
                return null
            }

            val resultBundle = Bundle()
            var globalPref: String? = null
            var localPref: String? = null // Pode ser nulo se não houver prefs locais
            var cursor: Cursor? = null
            val identity = Binder.clearCallingIdentity()

            try {
                if (alltrans.Companion.isProxyEnabled) { // Supondo que esta flag exista e funcione
                    try {
                        val proxyUri = Uri.parse("content://settings/system/alltransProxyProviderURI/akhil.alltrans.sharedPrefProvider/$packageName")
                        cursor = context.contentResolver.query(proxyUri, null, null, null, null)
                        if (cursor != null && cursor.moveToFirst()) {
                            globalPref = cursor.getString(0) // Coluna "sharedPreferences" para global
                            if (cursor.moveToNext()) { // Se houver segunda linha, é a local
                                localPref = cursor.getString(0)
                            }
                            utils.debugLog("Proxy query successful for $packageName. Global found: ${globalPref != null}, Local found: ${localPref != null}")
                        } else {
                            utils.debugLog("Proxy query failed or empty for $packageName.")
                        }
                    } catch (e: Exception) {
                        utils.debugLog("Proxy query exception for $packageName: ${e.message}")
                    } finally {
                        cursor?.close()
                        cursor = null
                    }
                } else {
                    utils.debugLog("Proxy is disabled, skipping proxy query for $packageName.")
                }

                // Se o proxy falhar ou estiver desabilitado, tentar conexão direta
                if (globalPref == null) {
                    utils.debugLog("Attempting direct query for $packageName as proxy failed or was skipped.")
                    try {
                        val directUri = Uri.parse("content://akhil.alltrans.sharedPrefProvider/$packageName")
                        cursor = context.contentResolver.query(directUri, null, null, null, null)
                        if (cursor != null && cursor.moveToFirst()) {
                            globalPref = cursor.getString(0)
                            if (cursor.moveToNext()) {
                                localPref = cursor.getString(0)
                            }
                            utils.debugLog("Direct query successful for $packageName. Global found: ${globalPref != null}, Local found: ${localPref != null}")
                        } else {
                            utils.debugLog("Direct query failed or empty for $packageName.")
                        }
                    } catch (e: Exception) {
                        utils.debugLog("Direct query exception for $packageName: ${Log.getStackTraceString(e)}")
                    } finally {
                        cursor?.close()
                    }
                }

                if (globalPref != null) {
                    resultBundle.putString("globalPref", globalPref)
                    if (localPref != null) { // Só adiciona localPref se ele foi encontrado
                        resultBundle.putString("localPref", localPref)
                    }
                    return resultBundle
                } else {
                    Log.e("AllTrans", "Failed to get global prefs for $packageName from any provider method.")
                    return null
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        fun readPrefAndHook(context: Context?) {
            if (context == null) return
            val packageName = context.packageName
            utils.debugLog("readPrefAndHook for $packageName")

            // CORREÇÃO: Verificar se é o próprio AllTrans e sair imediatamente
            if (packageName == "akhil.alltrans") {
                utils.debugLog("AllTrans: Skipping self hook in readPrefAndHook for AllTrans app")
                return
            }

            if (isSystemAppToSkip(context)) {
                utils.debugLog("AllTrans: Skipping system app (readPrefAndHook): $packageName")
                return
            }

            val prefsBundle = getPreferencesViaProviders(context, packageName)
            if (prefsBundle == null) {
                utils.debugLog("AllTrans: Could not get preferences bundle for $packageName. Aborting hooks.")
                return
            }

            val globalPrefJson = prefsBundle.getString("globalPref")
            val localPrefJson = prefsBundle.getString("localPref") // Pode ser nulo

            if (globalPrefJson == null) {
                utils.debugLog("AllTrans: Global preferences JSON is null for $packageName. Aborting hooks.")
                return
            }

            // Popula PreferenceList com as configurações efetivas
            // PreferenceList.getPref agora lida com a lógica de override
            PreferenceList.getPref(globalPrefJson, localPrefJson, packageName)
            utils.Debug = PreferenceList.Debug // Define o debug globalmente em utils

            // A decisão de habilitar a tradução para este app específico
            // agora é encapsulada em PreferenceList.LocalTranslationEnabledForApp.
            // Ela considera o master switch (Enabled), se o app está na lista global (AppSpecificEnabled),
            // e se o override local está ativo com o switch local "Traduzir este app" também ativo.
            if (!PreferenceList.LocalTranslationEnabledForApp) {
                utils.debugLog("AllTrans translation is effectively DISABLED for $packageName based on combined preferences.")
                return
            }
            utils.debugLog("AllTrans translation is effectively ENABLED for $packageName.")

            // Gerencia o cache usando PreferenceList.Caching e PreferenceList.CachingTime (que é sempre local)
            manageCache(context)

            // Aplicar hooks baseados nas preferências efetivas (que já estão em PreferenceList)
            applyTextViewHooks()
            applyWebViewHooks(packageName)
            applyDrawTextHooks()
            applyNotificationHooks()
        }

        private fun isSystemAppToSkip(context: Context): Boolean {
            val packageName = context.packageName

            // CORREÇÃO: Verificar explicitamente se é o AllTrans, independente de ser sistema ou não
            if (packageName == "akhil.alltrans") {
                utils.debugLog("AllTrans: Explicitly skipping AllTrans app in isSystemAppToSkip check")
                return true
            }

            val systemSkipList = listOf(
                "com.android.systemui", "com.android.settings", "com.android.launcher3",
                "com.android.keyguard", "com.android.providers", "com.android.bluetooth",
                "com.android.phone", "com.google.android.webview"
            )
            if (systemSkipList.any { packageName.startsWith(it) }) return true

            try {
                val appInfo = context.applicationInfo
                if ((appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                    // CORREÇÃO: Removido o caso especial para akhil.alltrans, agora é verificado anteriormente
                    return packageName != "com.android.providers.settings"
                }
            } catch (e: Exception) {
                utils.debugLog("Error checking if system app: ${e.message}")
            }
            return false
        }

        private fun manageCache(context: Context) {
            if (PreferenceList.Caching) { // Usa a flag Caching efetiva
                // PreferenceList.CachingTime é o timestamp da última limpeza para ESTE app
                clearCacheIfNeeded(context, PreferenceList.CachingTime)
                loadCacheFromDisk(context)
            } else {
                alltrans.Companion.cacheAccess.acquireUninterruptibly()
                try {
                    alltrans.Companion.cache = alltrans.Companion.cache ?: HashMap()
                    alltrans.Companion.cache?.clear()
                    utils.debugLog("Caching disabled for this app, cache cleared.")
                } finally {
                    if (alltrans.Companion.cacheAccess.availablePermits() == 0) {
                        alltrans.Companion.cacheAccess.release()
                    }
                }
            }
        }

        private fun loadCacheFromDisk(context: Context) {
            var fis: FileInputStream? = null
            var ois: ObjectInputStream? = null
            try {
                fis = context.openFileInput("AllTransCache")
                ois = ObjectInputStream(fis)
                alltrans.Companion.cacheAccess.acquireUninterruptibly()
                alltrans.Companion.cache = alltrans.Companion.cache ?: HashMap()
                val cacheRef = alltrans.Companion.cache
                cacheRef?.clear()
                val readObj = ois.readObject()
                if (readObj is HashMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    cacheRef?.putAll(readObj as HashMap<String?, String?>)
                    utils.debugLog("Cache loaded. Size: ${cacheRef?.size ?: 0}")
                } else {
                    utils.debugLog("Cache object from disk is not a HashMap.")
                }
            } catch (fnf: FileNotFoundException) {
                utils.debugLog("Cache file not found, starting fresh.")
                alltrans.Companion.cache = alltrans.Companion.cache ?: HashMap()
            } catch (eof: java.io.EOFException) {
                utils.debugLog("Cache file is corrupted (EOFException). Deleting and starting fresh.")
                try {
                    context.deleteFile("AllTransCache")
                    utils.debugLog("Corrupted cache file deleted successfully.")
                } catch (e: Exception) {
                    utils.debugLog("Failed to delete corrupted cache file: ${e.message}")
                }
                alltrans.Companion.cache = alltrans.Companion.cache ?: HashMap()
            } catch (e: Throwable) {
                utils.debugLog("Error reading cache: ${Log.getStackTraceString(e)}")
                // Para outras exceções, também tenta excluir o arquivo que pode estar corrompido
                try {
                    context.deleteFile("AllTransCache")
                    utils.debugLog("Potentially corrupted cache file deleted after error.")
                } catch (ex: Exception) {
                    utils.debugLog("Failed to delete cache file after error: ${ex.message}")
                }
                alltrans.Companion.cache = alltrans.Companion.cache ?: HashMap()
            } finally {
                try { ois?.close() } catch (ignored: IOException) {}
                try { fis?.close() } catch (ignored: IOException) {}
                if (alltrans.Companion.cacheAccess.availablePermits() == 0) alltrans.Companion.cacheAccess.release()
            }
        }

        private fun applyTextViewHooks() {
            val setTextHook = SetTextHookHandler() // Supondo que este handler use os campos efetivos de PreferenceList
            if (PreferenceList.SetText) { // Usa o valor efetivo de PreferenceList
                utils.tryHookMethod(TextView::class.java, "setText", CharSequence::class.java, BufferType::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, setTextHook)
            }
            if (PreferenceList.SetHint) { // Usa o valor efetivo de PreferenceList
                utils.tryHookMethod(TextView::class.java, "setHint", CharSequence::class.java, setTextHook)
            }
        }

        private fun applyWebViewHooks(packageName: String) {
            if (PreferenceList.LoadURL) { // Usa o valor efetivo de PreferenceList
                utils.debugLog("Applying WebView hooks for $packageName")
                utils.tryHookMethod(WebView::class.java, "onAttachedToWindow", WebViewOnCreateHookHandler())
                utils.tryHookMethod(WebView::class.java, "setWebViewClient", WebViewClient::class.java, WebViewSetClientHookHandler())
            }
        }

        private fun applyDrawTextHooks() {
            if (PreferenceList.DrawText && alltrans.Companion.baseRecordingCanvas != null) { // Usa o valor efetivo
                utils.debugLog("Applying DrawText hooks")
                val canvasClass: Class<*>? = alltrans.Companion.baseRecordingCanvas
                utils.tryHookMethod(canvasClass, "drawText", CharArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Paint::class.java, alltrans.Companion.drawTextHook)
                utils.tryHookMethod(canvasClass, "drawText", String::class.java, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Paint::class.java, alltrans.Companion.drawTextHook)
                utils.tryHookMethod(canvasClass, "drawText", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Paint::class.java, alltrans.Companion.drawTextHook)
                utils.tryHookMethod(canvasClass, "drawText", CharSequence::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Paint::class.java, alltrans.Companion.drawTextHook)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    utils.tryHookMethod(canvasClass, "drawText", CharArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, Paint::class.java, alltrans.Companion.drawTextHook)
                    utils.tryHookMethod(canvasClass, "drawTextRun", CharArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Paint::class.java, alltrans.Companion.drawTextHook)
                    utils.tryHookMethod(canvasClass, "drawTextRun", CharSequence::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Paint::class.java, alltrans.Companion.drawTextHook)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    utils.tryHookMethod(canvasClass, "drawTextRun", MeasuredText::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Paint::class.java, alltrans.Companion.drawTextHook)
                }
            }
        }

        private fun applyNotificationHooks() {
            if (PreferenceList.Notif) { // Usa o valor efetivo
                utils.debugLog("Applying Notification hooks")
                XposedBridge.hookAllMethods(NotificationManager::class.java, "notify", alltrans.Companion.notifyHook)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        XposedHelpers.findAndHookMethod(NotificationManager::class.java, "notifyAsUser", String::class.java, Int::class.javaPrimitiveType, Notification::class.java, UserHandle::class.java, alltrans.Companion.notifyHook)
                    } catch (t: Throwable) {
                        utils.debugLog("notifyAsUser hook failed (might not exist): ${t.message}")
                    }
                }
            }
        }

        fun clearCacheIfNeeded(context: Context?, lastClearTimeForApp: Long) { // Renomeado para clareza
            // A constante de tempo de caching (ex: 1 dia) viria das prefs globais,
            // mas o *timestamp da última limpeza* (CachingTime) é específico do app.
            val CACHE_EXPIRY_INTERVAL_MS = 24 * 60 * 60 * 1000L // Exemplo: 1 dia

            if (context == null) return
            alltrans.Companion.cacheAccess.acquireUninterruptibly()
            var lastDiskClearTime: Long = 0 // Timestamp da última vez que o ARQUIVO de cache foi limpo
            try {
                context.openFileInput("AllTransCacheClear").use { fis ->
                    ObjectInputStream(fis).use { ois ->
                        lastDiskClearTime = ois.readObject() as Long
                    }
                }
            } catch (ignored: Throwable) { /* Arquivo pode não existir */ }

            val currentTime = System.currentTimeMillis()

            // `lastClearTimeForApp` é o timestamp da última vez que o usuário CLICOU em "Limpar Cache" para este app.
            // Se for mais recente que a última limpeza de disco, respeite o clique do usuário.
            val effectiveLastClearTime = maxOf(lastDiskClearTime, lastClearTimeForApp)

            if (currentTime - effectiveLastClearTime > CACHE_EXPIRY_INTERVAL_MS) {
                utils.debugLog("Cache expired or cleared manually. Time since last effective clear: ${currentTime - effectiveLastClearTime}ms. Clearing cache...")
                try {
                    context.deleteFile("AllTransCache") // Deleta o arquivo de cache
                    context.openFileOutput("AllTransCacheClear", Context.MODE_PRIVATE).use { fos ->
                        ObjectOutputStream(fos).use { oos ->
                            oos.writeObject(currentTime) // Salva novo timestamp de limpeza de disco
                        }
                    }
                    alltrans.Companion.cache?.clear() // Limpa o cache em memória
                    utils.debugLog("Cache cleared successfully.")
                } catch (e: Throwable) {
                    Log.e("AllTrans", "Error clearing cache files", e)
                }
            } else {
                utils.debugLog("No need to clear cache. Time since last effective clear: ${currentTime - effectiveLastClearTime}ms")
            }

            if (alltrans.Companion.cacheAccess.availablePermits() == 0) {
                alltrans.Companion.cacheAccess.release()
            }
        }
    }
}