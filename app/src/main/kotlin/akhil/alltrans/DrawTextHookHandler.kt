package akhil.alltrans

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.text.MeasuredText
import android.os.Build
import android.text.SpannableString
import android.text.SpannedString
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Method
import java.nio.CharBuffer

class DrawTextHookHandler : XC_MethodReplacement(), OriginalCallable {
    override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
        val methodHookParam = userData as MethodHookParam
        val myMethod = methodHookParam.method as Method
        myMethod.setAccessible(true)
        val myArgs = methodHookParam.args

        if (myArgs.size != 0 && myArgs[0] != null) {
            if (myArgs[0].javaClass == CharArray::class.java) {
                myArgs[0] = translatedString.toString().toCharArray()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "android.text.AlteredCharSequence" == myArgs[0].javaClass.name) {
                // Tratando AlteredCharSequence sem importar diretamente (já que está depreciado)
                try {
                    // Usando reflexão para evitar importar a classe depreciada diretamente
                    val alteredClass = Class.forName("android.text.AlteredCharSequence")
                    val makeMethod = alteredClass.getMethod("make", CharSequence::class.java, CharArray::class.java, Int::class.java, Int::class.java)
                    myArgs[0] = makeMethod.invoke(null, translatedString, null, 0, 0)
                } catch (e: Exception) {
                    utils.debugLog("Error handling AlteredCharSequence: " + e.message)
                    // Fallback - simplesmente use a string
                    myArgs[0] = translatedString?.toString() ?: ""
                }
            } else if (myArgs[0].javaClass == CharBuffer::class.java) {
                val charBuffer = CharBuffer.allocate((translatedString?.length ?: 0) + 1)
                if (translatedString != null) {
                    charBuffer.append(translatedString)
                }
                myArgs[0] = charBuffer
            } else if (myArgs[0].javaClass == SpannableString::class.java) {
                myArgs[0] = SpannableString(translatedString ?: "")
            } else if (myArgs[0].javaClass == SpannedString::class.java) {
                myArgs[0] = SpannedString(translatedString ?: "")
            } else if (myArgs[0].javaClass == String::class.java) {
                myArgs[0] = translatedString?.toString() ?: ""
            } else if (myArgs[0].javaClass == StringBuffer::class.java) {
                myArgs[0] = StringBuffer(translatedString ?: "")
            } else if (myArgs[0].javaClass == StringBuilder::class.java) {
                myArgs[0] = StringBuilder(translatedString ?: "")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (myArgs[0].javaClass == MeasuredText::class.java) {
                    myArgs[0] =
                        MeasuredText.Builder((translatedString ?: "").toString().toCharArray()).build()
                }
            } else {
                myArgs[0] = translatedString ?: ""
            }
        }

        val tempPaint = myArgs[myArgs.size - 1] as Paint
        val tempCanvas = methodHookParam.thisObject as Canvas
        if (myArgs[0] != null) {
            myArgs[myArgs.size - 1] =
                DrawTextHookHandler.Companion.copyPaint(tempPaint, tempCanvas, myArgs[0].toString())
        }
        if (myArgs[1].javaClass == Int::class.javaPrimitiveType || myArgs[1].javaClass == Int::class.java) {
            myArgs[1] = 0
            myArgs[2] = 0
            if (translatedString != null) {
                myArgs[2] = translatedString.length
            }
        }
        if (myArgs.size >= 5 && myArgs[3].javaClass == Int::class.javaPrimitiveType || myArgs[3].javaClass == Int::class.java) {
            myArgs[3] = 0
            myArgs[4] = 0
            if (translatedString != null) {
                myArgs[4] = translatedString.length
            }
        }
        //
        alltrans.Companion.hookAccess.acquireUninterruptibly()
        var unhookedSuccessfully = false
        try {
            // Armazenar uma referência ao callback de hook atual
            val currentHook = alltrans.Companion.drawTextHook

            // Criar um novo callback para restaurar o hook depois
            val restoreHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // Reaplica o hook original após a chamada
                    XposedBridge.hookMethod(myMethod, currentHook)
                }
            }

            // Substituir o hook atual pelo hook de restauração temporário
            XposedBridge.hookMethod(myMethod, restoreHook)
            unhookedSuccessfully = true

            try {
                utils.debugLog(
                    "In Thread " + Thread.currentThread()
                        .getId() + " Invoking original function " + methodHookParam.method.getName() + " and setting text to " + myArgs[0].toString()
                )
                XposedBridge.invokeOriginalMethod(myMethod, methodHookParam.thisObject, myArgs)
            } catch (e: Throwable) {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                utils.debugLog("Got error in invoking method as : " + sw)
                val classTypes = StringBuilder()
                for (myArg in myArgs) {
                    classTypes.append("Class:").append(myArg.javaClass.getCanonicalName())
                        .append("Value:").append(myArg)
                }
                utils.debugLog("Params for above error are - " + classTypes)
            }
        } catch (e: Throwable) {
            utils.debugLog("Cannot unhook drawtext for some reason" + Log.getStackTraceString(e))
        }
        if (!unhookedSuccessfully) {
            // Se não conseguiu substituir o hook, restaure manualmente
            try {
                XposedBridge.hookMethod(methodHookParam.method, alltrans.Companion.drawTextHook)
            } catch (e: Throwable) {
                utils.debugLog("Cannot re-hook drawtext for some reason" + Log.getStackTraceString(e))
            }
        }
        alltrans.Companion.hookAccess.release()
    }

    override fun replaceHookedMethod(methodHookParam: MethodHookParam): Any? {
        try {
            if (methodHookParam.args[0] == null) {
                callOriginalMethod(null, methodHookParam)
                return null
            }
            var stringArgs: String?
            if (methodHookParam.args[0].javaClass == CharArray::class.java) {
                stringArgs = kotlin.text.String((methodHookParam.args[0] as kotlin.CharArray?)!!)
            } else {
                stringArgs = methodHookParam.args[0].toString()
            }
            if (methodHookParam.args[1].javaClass == Int::class.javaPrimitiveType || methodHookParam.args[1].javaClass == Int::class.java) {
                if (methodHookParam.args[0].javaClass == CharArray::class.java) {
                    stringArgs = stringArgs.substring(
                        methodHookParam.args[1] as Int,
                        methodHookParam.args[1] as Int + methodHookParam.args[2] as Int
                    )
                } else {
                    stringArgs = stringArgs.substring(
                        methodHookParam.args[1] as Int,
                        methodHookParam.args[2] as Int
                    )
                }
            }

            if (!SetTextHookHandler.Companion.isNotWhiteSpace(stringArgs)) {
                callOriginalMethod(stringArgs, methodHookParam)
                return null
            }
            utils.debugLog("Canvas: Found string for canvas drawText : " + methodHookParam.args[0].toString())

            utils.debugLog(
                "In Thread " + Thread.currentThread()
                    .getId() + " Recognized non-english string: " + stringArgs
            )
            val getTranslate = GetTranslate()
            getTranslate.stringToBeTrans = stringArgs
            getTranslate.originalCallable = this
            getTranslate.userData = methodHookParam
            getTranslate.canCallOriginal = false

            val getTranslateToken = GetTranslateToken()
            getTranslateToken.getTranslate = getTranslate

            alltrans.Companion.cacheAccess.acquireUninterruptibly()
            try {
                if (PreferenceList.Caching && alltrans.Companion.cache != null && alltrans.Companion.cache?.containsKey(stringArgs) == true) {
                    val translatedString: String? = alltrans.Companion.cache?.get(stringArgs)
                    utils.debugLog(
                        "In Thread " + Thread.currentThread()
                            .getId() + " found string in cache: " + stringArgs + " as " + translatedString
                    )
                    alltrans.Companion.cacheAccess.release()
                    callOriginalMethod(translatedString, methodHookParam)
                    return null
                } else {
                    alltrans.Companion.cacheAccess.release()
                    callOriginalMethod(stringArgs, methodHookParam)
                }
            } finally {
                if (alltrans.Companion.cacheAccess.availablePermits() == 0) {
                    alltrans.Companion.cacheAccess.release()
                }
            }

            getTranslateToken.doAll()
            return null
        } catch (e: Throwable) {
            utils.debugLog("Some Exception in drawText replaceHook - " + Log.getStackTraceString(e))
            return null
        }
    }

    companion object {
        /**
         * Sets the text size for a Paint object so a given string of text will be a
         * given width.
         *
         * @param paint        the Paint to set the text size for
         * @param desiredWidth the desired width
         * @param text         the text that should be that width
         */
        private fun setTextSizeForWidth(
            paint: Paint, originalSize: Float, desiredWidth: Float,
            text: String
        ) {
            // Get the bounds of the text, using our testTextSize.
            var desiredTextSize = originalSize
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            while (bounds.width() > desiredWidth) {
                desiredTextSize -= 1f
                paint.setTextSize(desiredTextSize)
                paint.getTextBounds(text, 0, text.length, bounds)
            }
        }

        private fun copyPaint(paint: Paint, canvas: Canvas, text: String): Paint {
            val myPaint = Paint()
            myPaint.set(paint)
            myPaint.setTextSize(paint.getTextSize())
            myPaint.setColor(paint.getColor())
            DrawTextHookHandler.Companion.setTextSizeForWidth(
                myPaint,
                paint.getTextSize(),
                canvas.getWidth().toFloat(),
                text
            )
            return myPaint
        }
    }
}