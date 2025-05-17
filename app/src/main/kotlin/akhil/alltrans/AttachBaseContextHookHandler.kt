// AttachBaseContextHookHandler.kt (com correção)
package akhil.alltrans

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.graphics.Paint
import android.graphics.text.MeasuredText
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.TextView.BufferType
import androidx.core.net.toUri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

internal class AttachBaseContextHookHandler : XC_MethodHook() {
    override fun afterHookedMethod(methodHookParam: MethodHookParam) {
        try {
            if (methodHookParam.args == null || methodHookParam.args.isEmpty() || (methodHookParam.args[0] !is Context)) {
                Utils.debugLog("AllTrans: Invalid args in attachBaseContext hook.")
                return
            }

            // Obter contexto base dos argumentos
            val baseContext = methodHookParam.args[0] as Context
            val packageName = baseContext.packageName

            // Verificar se é um serviço do sistema que deve ser ignorado
            if (isSystemServiceToSkip(packageName)) {
                Utils.debugLog("AllTrans: Skipping system service: $packageName")
                return
            }

            Utils.debugLog("AllTrans: in after attachBaseContext of ContextWrapper for package $packageName")

            // Tentativa progressiva de obter um contexto válido
            var usableContext: Context? = null

            // 1. Tentar primeiro o applicationContext do baseContext
            val appContext = baseContext.applicationContext
            if (appContext != null) {
                usableContext = appContext
                Utils.debugLog("AllTrans: Got application context from baseContext")
            }
            // 2. Se nulo, tentar o thisObject como ContextWrapper
            else if (methodHookParam.thisObject is ContextWrapper) {
                try {
                    val wrapper = methodHookParam.thisObject as ContextWrapper
                    val wrapperAppContext = wrapper.applicationContext
                    if (wrapperAppContext != null) {
                        usableContext = wrapperAppContext
                        Utils.debugLog("AllTrans: Got application context from ContextWrapper.thisObject")
                    }
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Error accessing ContextWrapper.applicationContext: ${e.message}")
                }

                // 3. Se ainda nulo, usar o próprio baseContext como fallback
                if (usableContext == null) {
                    usableContext = baseContext
                    Utils.debugLog("AllTrans: Using baseContext directly as fallback")
                }
            }

            // Se ainda não temos contexto utilizável, log e retornar
            if (usableContext == null) {
                Utils.debugLog("AllTrans: Unable to find a usable context for package $packageName")
                return
            }

            // Define o contexto global se ainda não estiver definido
            if (Alltrans.context == null) {
                Alltrans.context = usableContext
                Utils.debugLog("AllTrans: Application context set successfully from attachBaseContext for package: $packageName")
            }

            // Inicializar a chave da tag
            Alltrans.initializeTagKeyIfNeeded()

            // *** NOVA VERIFICAÇÃO PARA EVITAR CHAMADA PARA O PRÓPRIO PACOTE ***
            if (packageName != "akhil.alltrans") {
                try {
                    readPrefAndHook(usableContext)
                } catch (e: Throwable) {
                    Utils.debugLog("AllTrans: Error in readPrefAndHook from attachBaseContext: ${e.message}")
                    // Não propagar a exceção, permitindo que o aplicativo continue inicializando
                }
            } else {
                Utils.debugLog("AllTrans: Skipping readPrefAndHook for own package $packageName in attachBaseContext.")
                // Para o próprio pacote AllTrans, podemos querer fazer outras inicializações mínimas se necessário,
                // mas NÃO ler preferências via ContentProvider que ele mesmo está tentando publicar.
            }
        } catch (e: Throwable) {
            Utils.debugLog("Caught Exception in attachBaseContext ${Log.getStackTraceString(e)}")
            XposedBridge.log(e)
            // Não propagar a exceção, permitindo que o aplicativo continue inicializando
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
                if (Alltrans.isProxyEnabled) {
                    try {
                        val proxyUri = "content://settings/system/alltransProxyProviderURI/akhil.alltrans.SharedPrefProvider/$packageName".toUri()

                        // Usa Android O (API 26) e superior com FLAGS para operações assíncronas
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            // Definir manualmente as constantes que só existem no Android O ou superior
                            val queryArgSqlSelectionBehavior = "android:queryArgSelectionBehavior"
                            val selectionBehaviorStrict = 0
                            val extraHonoredArgs = "android:honorsExtraArgs"
                            val queryArgAsyncComputation = "android:asyncQuery"

                            val queryArgs = Bundle()
                            queryArgs.putInt(queryArgSqlSelectionBehavior, selectionBehaviorStrict)
                            queryArgs.putBoolean(queryArgAsyncComputation, true)
                            queryArgs.putInt(extraHonoredArgs, 1)

                            cursor = context.contentResolver.query(proxyUri, null, queryArgs, null)
                        } else {
                            // Fallback para API antiga
                            cursor = context.contentResolver.query(proxyUri, null, null, null, null)
                        }

                        if (cursor != null && cursor.moveToFirst()) {
                            val dataColumnIndex = 0
                            if (cursor.columnCount > dataColumnIndex) {
                                globalPref = cursor.getString(dataColumnIndex)
                                if (cursor.moveToNext()) localPref = cursor.getString(dataColumnIndex)
                                Utils.debugLog("Proxy query successful for $packageName.")
                            } else {
                                Utils.debugLog("Proxy query cursor has no columns.")
                            }
                        } else {
                            Utils.debugLog("Proxy query failed or empty for $packageName.")
                        }
                    } catch (e: Exception) {
                        Utils.debugLog("Proxy query exception: ${e.message}")
                    } finally {
                        cursor?.close()
                        cursor = null
                    }
                } else {
                    Utils.debugLog("Proxy is disabled, skipping proxy query.")
                }

                // Se o proxy falhar, tentar conexão direta
                if (globalPref == null) {
                    try {
                        val directUri = "content://akhil.alltrans.SharedPrefProvider/$packageName".toUri()

                        // Usa Android O (API 26) e superior com FLAGS para operações assíncronas
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            // Definir manualmente as constantes que só existem no Android O ou superior
                            val queryArgSqlSelectionBehavior = "android:queryArgSelectionBehavior"
                            val selectionBehaviorStrict = 0
                            val extraHonoredArgs = "android:honorsExtraArgs"
                            val queryArgAsyncComputation = "android:asyncQuery"

                            val queryArgs = Bundle()
                            queryArgs.putInt(queryArgSqlSelectionBehavior, selectionBehaviorStrict)
                            queryArgs.putBoolean(queryArgAsyncComputation, true)
                            queryArgs.putInt(extraHonoredArgs, 1)

                            cursor = context.contentResolver.query(directUri, null, queryArgs, null)
                        } else {
                            // Fallback para API antiga
                            cursor = context.contentResolver.query(directUri, null, null, null, null)
                        }

                        if (cursor != null && cursor.moveToFirst()) {
                            val dataColumnIndex = 0
                            if (cursor.columnCount > dataColumnIndex) {
                                globalPref = cursor.getString(dataColumnIndex)
                                if (cursor.moveToNext()) localPref = cursor.getString(dataColumnIndex)
                                Utils.debugLog("Direct query successful for $packageName.")
                            } else {
                                Utils.debugLog("Direct query cursor has no columns.")
                            }
                        } else {
                            Utils.debugLog("Direct query failed or empty for $packageName.")
                        }
                    } catch (e: Exception) {
                        Utils.debugLog("Direct query exception: ${Log.getStackTraceString(e)}")
                    } finally {
                        cursor?.close()
                    }
                }

                // Retornar os resultados
                if (globalPref != null) {
                    resultBundle.putString("globalPref", globalPref)
                    resultBundle.putString("localPref", localPref ?: "")
                    return resultBundle
                } else {
                    Log.e("AllTrans", "Failed to get prefs for $packageName")
                    return null
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        // Função readPrefAndHook otimizada com melhor tratamento de erros
        fun readPrefAndHook(context: Context?) {
            if (context == null) {
                Utils.debugLog("AllTrans: readPrefAndHook called with null context")
                return
            }

            val packageName = context.packageName
            Utils.debugLog("readPrefAndHook for $packageName")

            // Verificar se é um aplicativo do sistema que deve ser ignorado
            if (isSystemAppToSkip(context)) {
                Utils.debugLog("AllTrans: Skipping system app: $packageName")
                return
            }

            // Obter preferências
            try {
                val prefsBundle = getPreferencesViaProviders(context, packageName)
                if (prefsBundle == null) {
                    Utils.debugLog("AllTrans: No preferences found for $packageName, skipping hooks")
                    return
                }

                val globalPref = prefsBundle.getString("globalPref")
                val localPref = prefsBundle.getString("localPref")

                if (globalPref.isNullOrEmpty()) {
                    Utils.debugLog("AllTrans: Empty global preferences for $packageName, skipping hooks")
                    return
                }

                // Aplicar preferências
                try {
                    PreferenceList.getPref(globalPref, localPref ?: "", packageName)
                    Utils.Debug = PreferenceList.Debug
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Error applying preferences: ${e.message}")
                    return
                }

                // Verificar se o módulo está habilitado para este pacote
                if (!PreferenceList.Enabled || !PreferenceList.LocalEnabled) {
                    Utils.debugLog("AllTrans disabled for $packageName")
                    return
                }
                Utils.debugLog("AllTrans Enabled for $packageName")

                // Gerenciar cache
                try {
                    manageCache(context)
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Error managing cache: ${e.message}")
                    // Continuar mesmo com erro de cache
                }

                // Aplicar hooks baseados nas preferências
                try {
                    applyTextViewHooks()
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Error applying TextView hooks: ${e.message}")
                }

                try {
                    applyWebViewHooks(packageName)
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Error applying WebView hooks: ${e.message}")
                }

                try {
                    applyDrawTextHooks()
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Error applying DrawText hooks: ${e.message}")
                }

                try {
                    applyNotificationHooks()
                } catch (e: Exception) {
                    Utils.debugLog("AllTrans: Error applying Notification hooks: ${e.message}")
                }

            } catch (e: Exception) {
                Utils.debugLog("AllTrans: Error in readPrefAndHook main flow: ${Log.getStackTraceString(e)}")
            }
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
                Utils.debugLog("Error checking if system app: ${e.message}")
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
                Alltrans.cacheAccess.acquireUninterruptibly()
                try {
                    if (Alltrans.cache == null) {
                        Alltrans.cache = HashMap()
                    } else {
                        Alltrans.cache?.clear()
                    }
                    Utils.debugLog("Caching disabled, cache cleared.")
                } finally {
                    if (Alltrans.cacheAccess.availablePermits() == 0) {
                        Alltrans.cacheAccess.release()
                    }
                }
            }
        }

        /**
         * Carrega o cache do disco com melhor tratamento de erros
         */
        private fun loadCacheFromDisk(context: Context) {
            var fis: FileInputStream? = null
            var ois: ObjectInputStream? = null
            var semaphoreAcquired = false

            // Certifica-se de que o cache esteja inicializado mesmo antes de tentar ler
            if (Alltrans.cache == null) {
                Alltrans.cache = HashMap()
            }

            try {
                // Verifica se o arquivo tem tamanho válido antes de tentar ler
                val cacheFile = File(context.filesDir, "AllTransCache")
                if (!cacheFile.exists() || cacheFile.length() == 0L) {
                    Utils.debugLog("Cache file not found or empty, starting fresh.")
                    return
                }

                // Abrir o arquivo e obter o stream
                try {
                    fis = context.openFileInput("AllTransCache")
                } catch (fnf: FileNotFoundException) {
                    Utils.debugLog("Cache file not found, starting fresh.")
                    return
                }

                // Tenta adquirir o semáforo antes de qualquer operação de leitura
                Alltrans.cacheAccess.acquireUninterruptibly()
                semaphoreAcquired = true

                // Limpar o cache existente
                val cacheRef = Alltrans.cache
                cacheRef?.clear()

                try {
                    // Tenta criar o ObjectInputStream com verificação de validez
                    ois = ObjectInputStream(fis)

                    // Ler os dados com verificações adicionais de segurança
                    val readObj = ois.readObject()
                    if (readObj is HashMap<*, *>) {
                        // Verificação de tipo antes do cast
                        @Suppress("UNCHECKED_CAST")
                        val typedMap = readObj as HashMap<String?, String?>
                        cacheRef?.putAll(typedMap)
                        Utils.debugLog("Cache loaded successfully. Size: ${cacheRef?.size ?: 0}")
                    } else {
                        Utils.debugLog("Cache object is not of type HashMap<String?, String?>")
                    }
                } catch (e: ClassNotFoundException) {
                    Utils.debugLog("Cache format incompatible: ${e.message}")
                    // Deleta o arquivo incompatível
                    context.deleteFile("AllTransCache")
                } catch (e: EOFException) {
                    Utils.debugLog("Cache file corrupt (EOF): ${e.message}")
                    // Deleta o arquivo corrompido
                    context.deleteFile("AllTransCache")
                } catch (e: IOException) {
                    Utils.debugLog("IO error reading cache: ${e.message}")
                    // Deleta o arquivo potencialmente corrompido
                    context.deleteFile("AllTransCache")
                }
            } catch (e: Throwable) {
                Utils.debugLog("Error reading cache: ${e.message}")
                // Tenta deletar o arquivo potencialmente corrompido
                try {
                    context.deleteFile("AllTransCache")
                    Utils.debugLog("Deleted potentially corrupted cache file.")
                } catch (ignored: Exception) {}
            } finally {
                // Garantir que os recursos sejam fechados corretamente
                try { ois?.close() } catch (ignored: IOException) {}
                try { fis?.close() } catch (ignored: IOException) {}

                // Libera o semáforo apenas se ele foi adquirido
                if (semaphoreAcquired && Alltrans.cacheAccess.availablePermits() == 0) {
                    Alltrans.cacheAccess.release()
                }
            }
        }

        /**
         * Aplica hooks aos métodos TextView
         */
        private fun applyTextViewHooks() {
            val setTextHook = SetTextHookHandler()

            if (PreferenceList.SetText) {
                Utils.tryHookMethod(
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
                Utils.tryHookMethod(
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
                Utils.debugLog("Applying WebView hooks for $packageName")

                // Hook onAttachedToWindow em vez do construtor
                Utils.tryHookMethod(
                    WebView::class.java,
                    "onAttachedToWindow",
                    WebViewOnCreateHookHandler()
                )

                // Hook no método setWebViewClient
                Utils.tryHookMethod(
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
            if (PreferenceList.DrawText && Alltrans.baseRecordingCanvas != null) {
                Utils.debugLog("Applying DrawText hooks")
                val canvasClass: Class<*>? = Alltrans.baseRecordingCanvas

                // Hooks básicos de desenho de texto
                Utils.tryHookMethod(
                    canvasClass,
                    "drawText",
                    CharArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Paint::class.java,
                    Alltrans.drawTextHook
                )

                Utils.tryHookMethod(
                    canvasClass,
                    "drawText",
                    String::class.java,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Paint::class.java,
                    Alltrans.drawTextHook
                )

                Utils.tryHookMethod(
                    canvasClass,
                    "drawText",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Paint::class.java,
                    Alltrans.drawTextHook
                )

                Utils.tryHookMethod(
                    canvasClass,
                    "drawText",
                    CharSequence::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Paint::class.java,
                    Alltrans.drawTextHook
                )

                // Hooks para API 23+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Utils.tryHookMethod(
                        canvasClass,
                        "drawText",
                        CharArray::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Paint::class.java,
                        Alltrans.drawTextHook
                    )

                    Utils.tryHookMethod(
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
                        Alltrans.drawTextHook
                    )

                    Utils.tryHookMethod(
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
                        Alltrans.drawTextHook
                    )
                }

                Utils.tryHookMethod(
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
                    Alltrans.drawTextHook
                )
            }
        }

        /**
         * Aplica hooks aos métodos de Notificação
         */
        private fun applyNotificationHooks() {
            if (PreferenceList.Notif) {
                Utils.debugLog("Applying Notification hooks")

                // Hook básico da notificação
                XposedBridge.hookAllMethods(
                    NotificationManager::class.java,
                    "notify",
                    Alltrans.notifyHook
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
                            Alltrans.notifyHook
                        )
                    } catch (t: Throwable) {
                        Utils.debugLog("notifyAsUser hook failed (might not exist): ${t.message}")
                    }
                }
            }
        }

        /**
         * Limpa o cache se necessário com base no tempo
         */
        private fun clearCacheIfNeeded(context: Context?, cachingTime: Long) {
            if (cachingTime <= 0L || context == null) return

            Alltrans.cacheAccess.acquireUninterruptibly()
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
                Utils.debugLog("Time since last cache clear: ${timeSinceLastClear}ms, clearing cache...")
                var fos: FileOutputStream? = null
                var oos: ObjectOutputStream? = null

                try {
                    // Atualizar o tempo da última limpeza
                    fos = context.openFileOutput("AllTransCacheClear", Context.MODE_PRIVATE)
                    oos = ObjectOutputStream(fos)
                    oos.writeObject(currentTime)

                    // Limpar o arquivo de cache e o objeto cache em memória
                    context.deleteFile("AllTransCache")
                    Alltrans.cache?.clear()
                    Utils.debugLog("Cache cleared successfully.")
                } catch (e: Throwable) {
                    Log.e("AllTrans", "Error clearing cache", e)
                } finally {
                    try { oos?.close() } catch (ignored: IOException) {}
                    try { fos?.close() } catch (ignored: IOException) {}
                }
            } else {
                Utils.debugLog("No need to clear cache. Time since last clear: ${timeSinceLastClear}ms")
            }

            if (Alltrans.cacheAccess.availablePermits() == 0) {
                Alltrans.cacheAccess.release()
            }
        }
    }
}