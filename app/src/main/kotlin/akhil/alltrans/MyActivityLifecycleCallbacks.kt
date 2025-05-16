package akhil.alltrans

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.os.Bundle
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectOutputStream
import java.nio.channels.FileChannel

internal class MyActivityLifecycleCallbacks : ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityStopped(activity: Activity) {}

    override fun onActivityDestroyed(activity: Activity) {
        if (PreferenceList.Caching && alltrans.cache != null && alltrans.cache?.isEmpty() == false) {
            val appContext = activity.applicationContext

            alltrans.cacheAccess.acquireUninterruptibly()
            try {
                utils.debugLog("Trying to write cache on activity destroy...")

                // Escrever em um arquivo temporário primeiro para evitar corrupção
                val tempFile = File(appContext.cacheDir, "AllTransCache.tmp")
                val finalFile = File(appContext.filesDir, "AllTransCache")

                try {
                    FileOutputStream(tempFile).use { fileOut ->
                        ObjectOutputStream(fileOut).use { objectOut ->
                            // Use safe call com let para tratar caso nulo
                            alltrans.cache?.let { cache ->
                                if (cache.isNotEmpty()) {
                                    // Salvar uma cópia do HashMap
                                    objectOut.writeObject(HashMap(cache))
                                    objectOut.flush()
                                    fileOut.flush()

                                    utils.debugLog("Cache written to temp file. Size: ${cache.size}")
                                } else {
                                    utils.debugLog("Cache is empty, nothing to save.")
                                }
                            } ?: utils.debugLog("Cache is null, nothing to save.")
                        }
                    }

                    // Arquivo temporário escrito com sucesso, agora move para o local permanente
                    if (tempFile.renameTo(finalFile)) {
                        utils.debugLog("Cache saved successfully on activity destroy. Size: ${alltrans.cache?.size ?: 0}")
                    } else {
                        // Se renomear falhar, tenta copiar o conteúdo
                        copyFile(tempFile, finalFile)
                        utils.debugLog("Cache saved via copy on activity destroy. Size: ${alltrans.cache?.size ?: 0}")
                    }
                } catch (e: IOException) {
                    utils.debugLog("Error saving cache file: ${Log.getStackTraceString(e)}")
                    try { tempFile.delete() } catch (ignored: Exception) {}
                }
            } catch (e: Throwable) {
                utils.debugLog(
                    "Got error saving cache in onActivityDestroyed: " + Log.getStackTraceString(e)
                )
            } finally {
                if (alltrans.cacheAccess.availablePermits() == 0) {
                    alltrans.cacheAccess.release()
                }
            }
        }
    }

    /**
     * Copia o conteúdo de um arquivo para outro
     */
    private fun copyFile(source: File, dest: File) {
        try {
            FileInputStream(source).use { inputStream ->
                FileOutputStream(dest).use { outputStream ->
                    val sourceChannel = inputStream.channel
                    val destChannel = outputStream.channel
                    destChannel.transferFrom(sourceChannel, 0, sourceChannel.size())
                }
            }
            // Tenta excluir o arquivo temporário após a cópia bem-sucedida
            try { source.delete() } catch (ignored: Exception) {}
        } catch (e: IOException) {
            utils.debugLog("Error copying cache file: ${Log.getStackTraceString(e)}")
        }
    }
}