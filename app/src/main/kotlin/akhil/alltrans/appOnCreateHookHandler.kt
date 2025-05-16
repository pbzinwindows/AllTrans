// appOnCreateHookHandler.kt (com correção)
package akhil.alltrans

import android.app.Application
import android.util.Log
import de.robv.android.xposed.XC_MethodHook

// Importar Context
internal class appOnCreateHookHandler : XC_MethodHook() {
    override fun afterHookedMethod(methodHookParam: MethodHookParam) { // Usa afterHookedMethod
        utils.debugLog("AllTrans: in after OnCreate of Application")

        if (methodHookParam.thisObject is Application) {
            val application = methodHookParam.thisObject as Application
            val appContext = application.getApplicationContext()
            if (appContext != null) {
                if (alltrans.context == null) {
                    alltrans.context = appContext
                    utils.debugLog("AllTrans: Application context set successfully from Application.onCreate for package: " + appContext.getPackageName())

                    // --- Chama a inicialização da chave da tag AQUI ---
                    alltrans.initializeTagKeyIfNeeded() // Chama o método estático público

                    // --- Fim da chamada ---

                    // *** NOVA VERIFICAÇÃO PARA EVITAR CHAMADA PARA O PRÓPRIO PACOTE ***
                    val packageName = appContext.getPackageName()
                    if (packageName != "akhil.alltrans") {
                        try {
                            // Verifica se as preferências já foram lidas para evitar duplicação
                            // (Pode ser necessário um flag estático adicional se isso causar problemas)
                            AttachBaseContextHookHandler.readPrefAndHook(appContext)
                        } catch (e: Throwable) {
                            utils.debugLog(
                                "Caught Exception calling readPrefAndHook from appOnCreate " + Log.getStackTraceString(
                                    e
                                )
                            )
                        }
                    } else {
                        utils.debugLog("AllTrans: Skipping readPrefAndHook for own package $packageName in Application.onCreate.")
                    }
                } else {
                    utils.debugLog("AllTrans: Application context already set, skipping assignment in Application.onCreate.")
                    // Mesmo se o contexto já estiver definido, tenta inicializar a chave caso não tenha sido feito
                    alltrans.initializeTagKeyIfNeeded() // Chama o método estático público
                }
            } else {
                utils.debugLog("AllTrans: Could not get application context in Application.onCreate.")
            }

            // Registrar ActivityLifecycleCallbacks (como antes)
            val myActivityLifecycleCallbacks = MyActivityLifecycleCallbacks()
            application.registerActivityLifecycleCallbacks(myActivityLifecycleCallbacks)
        } else {
            utils.debugLog("AllTrans: Hooked object in Application.onCreate is not an Application instance?")
        }
    }
}