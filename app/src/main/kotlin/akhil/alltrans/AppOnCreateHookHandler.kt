package akhil.alltrans

import android.app.Application
// import android.util.Log // Log é usado via XposedBridge ou Utils.debugLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

internal class AppOnCreateHookHandler : XC_MethodHook() {
    override fun afterHookedMethod(methodHookParam: MethodHookParam) {
        Utils.debugLog("AllTrans: in after OnCreate of Application")

        if (methodHookParam.thisObject is Application) {
            val application = methodHookParam.thisObject as Application
            val appContext = application.applicationContext // Use val
            if (appContext != null) {
                if (Alltrans.context == null) {
                    Alltrans.context = appContext
                    Utils.debugLog("AllTrans: Application context set successfully from Application.onCreate for package: " + appContext.packageName)

                    Alltrans.initializeTagKeyIfNeeded()

                    val packageName = appContext.packageName // Use val
                    if (packageName != "akhil.alltrans") {
                        try {
                            AttachBaseContextHookHandler.readPrefAndHook(appContext)
                        } catch (e: Exception) {
                            Utils.debugLog("AllTrans: Error calling readPrefAndHook from AppOnCreateHookHandler for package $packageName: " + e.message)
                            XposedBridge.log(e)
                        }
                    } else {
                        Utils.debugLog("AllTrans: Skipping readPrefAndHook for own package $packageName in Application.onCreate.")
                    }
                } else {
                    Utils.debugLog("AllTrans: Application context already set, skipping assignment in Application.onCreate.")
                    Alltrans.initializeTagKeyIfNeeded()
                }
            } else {
                Utils.debugLog("AllTrans: Could not get application context in Application.onCreate.")
            }

            val myActivityLifecycleCallbacks = MyActivityLifecycleCallbacks() // Use val
            application.registerActivityLifecycleCallbacks(myActivityLifecycleCallbacks)
        } else {
            Utils.debugLog("AllTrans: Hooked object in Application.onCreate is not an Application instance?")
        }
    }
}