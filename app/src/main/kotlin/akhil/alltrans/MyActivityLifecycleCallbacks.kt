package akhil.alltrans

import android.app.Activity
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.os.Bundle
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectOutputStream

internal class MyActivityLifecycleCallbacks : ActivityLifecycleCallbacks {
    // ... (outros métodos como antes) ...
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityStopped(activity: Activity) {}


    override fun onActivityDestroyed(activity: Activity) {
        if (PreferenceList.Caching && alltrans.Companion.cache != null && alltrans.Companion.cache?.isEmpty() == false) {
            val appContext = activity.getApplicationContext()
            if (appContext == null) {
                utils.debugLog("Cannot save cache on destroy: application context is null.")
                return
            }

            var fileOutputStream: FileOutputStream? = null
            var objectOutputStream: ObjectOutputStream? = null
            alltrans.Companion.cacheAccess.acquireUninterruptibly()
            try {
                utils.debugLog("Trying to write cache on activity destroy...")
                fileOutputStream = appContext.openFileOutput("AllTransCache", Context.MODE_PRIVATE)
                objectOutputStream = ObjectOutputStream(fileOutputStream)

                // Use safe call with let to handle null case
                alltrans.Companion.cache?.let { cache ->
                    // Salvar uma cópia do HashMap (agora compila)
                    objectOutputStream.writeObject(HashMap<String?, String?>(cache))
                    utils.debugLog("Cache saved successfully on activity destroy.")
                } ?: run {
                    utils.debugLog("Cache is null, nothing to save.")
                }

            } catch (e: Throwable) {
                utils.debugLog(
                    "Got error saving cache in onActivityDestroyed: " + Log.getStackTraceString(
                        e
                    )
                )
            } finally {
                try {
                    if (objectOutputStream != null) objectOutputStream.close()
                } catch (ignored: IOException) {
                }
                try {
                    if (fileOutputStream != null) fileOutputStream.close()
                } catch (ignored: IOException) {
                }
                if (alltrans.Companion.cacheAccess.availablePermits() == 0) alltrans.Companion.cacheAccess.release()
            }
        }
    }
}