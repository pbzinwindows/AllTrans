package akhil.alltrans

import android.app.Activity
import kotlin.text.isNotEmpty // Adicionado import
import kotlin.text.isNullOrEmpty // Adicionado import
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectOutputStream

internal class MyActivityLifecycleCallbacks : ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityStopped(activity: Activity) {}

    override fun onActivityDestroyed(activity: Activity) {
        if (PreferenceList.Caching && (Alltrans.cache?.size() ?: 0) > 0) {
            val appContext = activity.applicationContext
            if (appContext == null) {
                Utils.debugLog("onActivityDestroyed: ApplicationContext is null for ${activity.componentName?.className}. Cannot save cache.")
                return
            }

            val packageName = appContext.packageName ?: "unknown_package"
            Utils.debugLog("onActivityDestroyed for $packageName: Attempting to save cache.")

            Alltrans.cacheAccess.acquireUninterruptibly()
            var tempFile: File? = null

            try {
                val currentCacheInMemory = Alltrans.cache
                // Correção para LruCache: snapshot().isNullOrEmpty() ou size() == 0
                // As LruCache.size() é um método, e snapshot().isEmpty() também é um método.
                if (currentCacheInMemory == null || currentCacheInMemory.size() == 0 || (currentCacheInMemory.snapshot()?.isEmpty() ?: true) ) {
                    Utils.debugLog("onActivityDestroyed for $packageName: Cache is null or empty before saving. Nothing to save.")
                    return
                }

                // currentCacheInMemory é LruCache, então .size() é correto.
                // hashMapToSave é HashMap, então .size (propriedade) é correto.
                Utils.debugLog("onActivityDestroyed for $packageName: Cache has ${currentCacheInMemory.size()} items. Preparing to write.")

                tempFile = File(appContext.cacheDir, "AllTransCache.tmp")
                val finalFile = File(appContext.filesDir, "AllTransCache")

                Utils.debugLog("onActivityDestroyed for $packageName: Temp file: ${tempFile.absolutePath}, Final file: ${finalFile.absolutePath}")

                try {
                    Utils.debugLog("onActivityDestroyed for $packageName: Temp file exists before write: ${tempFile.exists()}")
                    FileOutputStream(tempFile).use { fileOut ->
                        ObjectOutputStream(fileOut).use { objectOut ->
                            val cacheSnapshot = currentCacheInMemory.snapshot()
                            val hashMapToSave = HashMap(cacheSnapshot)
                            objectOut.writeObject(hashMapToSave)
                            objectOut.flush()
                            // Correção: HashMap.size é uma propriedade. Usar .size para HashMap.
                            Utils.debugLog("onActivityDestroyed for $packageName: LruCache snapshot (as HashMap) written to temp file '${tempFile.name}'. Size: ${hashMapToSave.size}")
                        }
                    }
                    Utils.debugLog("onActivityDestroyed for $packageName: Temp file exists after write: ${tempFile.exists()}, size: ${tempFile.length()}")

                } catch (e: IOException) {
                    Utils.debugLog("onActivityDestroyed for $packageName: IOException during temp file write: ${Log.getStackTraceString(e)}")
                    return
                }

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    Utils.debugLog("onActivityDestroyed for $packageName: Temp file write appears to have failed (file non-existent or empty). Aborting save.")
                    if(tempFile.exists()) tempFile.delete()
                    return
                }

                Utils.debugLog("onActivityDestroyed for $packageName: Final file exists before potential delete: ${finalFile.exists()}")
                if (finalFile.exists()) {
                    val deletedOldFinal = finalFile.delete()
                    Utils.debugLog("onActivityDestroyed for $packageName: Deletion of existing finalFile ('${finalFile.name}') successful: $deletedOldFinal")
                    if (!deletedOldFinal) {
                        Utils.debugLog("onActivityDestroyed for $packageName: WARNING - Failed to delete existing final cache file. Rename/Copy might fail or lead to issues.")
                    }
                }

                Utils.debugLog("onActivityDestroyed for $packageName: Attempting to rename '${tempFile.name}' to '${finalFile.name}'")
                if (tempFile.renameTo(finalFile)) {
                    // Correção: LruCache.size() é um método.
                    Utils.debugLog("onActivityDestroyed for $packageName: Cache saved successfully via rename. Final file size: ${finalFile.length()}. Cache items: ${currentCacheInMemory.size()}")
                } else {
                    Utils.debugLog("onActivityDestroyed for $packageName: Rename failed. Attempting copy from '${tempFile.name}' to '${finalFile.name}'.")
                    // Correção: LruCache.size() é um método.
                    copyFile(tempFile, finalFile, packageName, currentCacheInMemory.size())
                }

            } catch (t: Throwable) {
                Utils.debugLog("onActivityDestroyed for $packageName: Unexpected error saving cache: ${Log.getStackTraceString(t)}")
            } finally {
                if (Alltrans.cacheAccess.availablePermits() == 0) {
                    Alltrans.cacheAccess.release()
                    Utils.debugLog("onActivityDestroyed for $packageName: Cache access semaphore released.")
                }
                tempFile?.let {
                    if (it.exists()) {
                        val deleted = it.delete()
                        Utils.debugLog("onActivityDestroyed for $packageName: Cleanup - Temp file '${it.name}' deleted: $deleted")
                    }
                }
            }
        } else {
            val appName = activity.componentName?.className ?: "UnknownActivity"
            val reason = when {
                !PreferenceList.Caching -> "caching is disabled"
                // Correção para LruCache: snapshot().isEmpty() ou size() == 0
                // A linha abaixo já usa .size() que é o correto para LruCache e snapshot().isEmpty() que é correto para Map.
                // Adicionada verificação para currentCacheInMemory.size() == 0 explicitamente.
                (Alltrans.cache == null || Alltrans.cache?.size() == 0 || (Alltrans.cache?.snapshot()?.isEmpty() ?: true)) -> "cache object is null or empty"
                else -> "unknown reason (cache not empty but main condition failed)"
            }
            Utils.debugLog("onActivityDestroyed for $appName: Cache not saved because $reason.")
        }
    }

    private fun copyFile(source: File, dest: File, packageName: String, originalCacheSize: Int) {
        try {
            val sourceSize = source.length()
            val bytesCopied: Long // Declaração sem inicialização

            FileInputStream(source).use { inputStream ->
                FileOutputStream(dest).use { outputStream ->
                    val sourceChannel = inputStream.channel
                    val destChannel = outputStream.channel
                    bytesCopied = destChannel.transferFrom(sourceChannel, 0, sourceSize) // Atribuição
                    Utils.debugLog("onActivityDestroyed for $packageName: Bytes copied from temp to final: $bytesCopied. Original source size: $sourceSize")
                }
            }

            // A variável bytesCopied está agora inicializada e pode ser usada
            if (bytesCopied == sourceSize && dest.exists() && dest.length() == sourceSize) {
                Utils.debugLog("onActivityDestroyed for $packageName: Cache saved successfully via copy. Final file size: ${dest.length()}. Cache items: $originalCacheSize")
                val deletedTemp = source.delete()
                Utils.debugLog("onActivityDestroyed for $packageName: Temp file '${source.name}' deleted after copy: $deletedTemp")
            } else {
                Utils.debugLog("onActivityDestroyed for $packageName: WARNING - Copy operation failed or was incomplete. Source size: $sourceSize, Bytes copied: $bytesCopied, Dest exists: ${dest.exists()}, Dest size: ${dest.length()}. Temp file not deleted.")
                if (dest.exists()) dest.delete()
            }

        } catch (e: IOException) {
            Utils.debugLog("onActivityDestroyed for $packageName: IOException during copyFile from '${source.path}' to '${dest.path}': ${Log.getStackTraceString(e)}")
            if (dest.exists()) dest.delete()
        }
    }
}