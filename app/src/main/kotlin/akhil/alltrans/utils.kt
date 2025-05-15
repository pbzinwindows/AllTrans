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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import de.robv.android.xposed.XposedHelpers
import java.io.StringWriter
import kotlin.math.min

internal object utils {
    var Debug: Boolean = true

    fun isExpModuleActive(context: Context): Boolean {
        requireNotNull(context) { "context must not be null!!" }
        try {
            val contentResolver = context.getContentResolver()
            val uri = Uri.parse("content://me.weishu.exposed.CP/")
            var result: Bundle?
            try {
                result = contentResolver.call(uri, "active", null, null)
            } catch (e: RuntimeException) {
                try {
                    val intent = Intent("me.weishu.exp.ACTION_ACTIVE")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e1: Throwable) {
                    return false
                }
                result = contentResolver.call(uri, "active", null, null)
            }
            if (result == null) return false
            return result.getBoolean("active", false)
        } catch (ignored: Throwable) {
        }
        return false
    }

    fun getExpApps(context: Context): MutableList<String?> {
        val result: Bundle?
        try {
            result = context.getContentResolver().call(
                Uri.parse("content://me.weishu.exposed.CP/"), "apps", null, null
            )
        } catch (e: Throwable) {
            return mutableListOf<String?>()
        }
        if (result == null) return mutableListOf<String?>()
        val list: MutableList<String?>? = result.getStringArrayList("apps")
        return if (list != null) list else mutableListOf<String?>()
    }

    fun debugLog(str: String?) {
        var str = str
        if (!Debug) return
        if (str == null) str = "null"
        val logTag = "AllTrans"
        val maxLogSize = 4000

        if (str.length > maxLogSize) {
            var chunkCount = (str.length / maxLogSize)
            if (str.length % maxLogSize != 0) chunkCount++
            for (i in 0..<chunkCount) {
                val start = i * maxLogSize
                val end = min(((i + 1) * maxLogSize).toDouble(), str.length.toDouble()).toInt()
                Log.i(
                    logTag,
                    "Chunk " + (i + 1) + "/" + chunkCount + ": " + str.substring(start, end)
                )
            }
        } else {
            Log.i(logTag, str)
        }
    }

    fun tryHookMethod(
        clazz: Class<*>?,
        methodName: String?,
        vararg parameterTypesAndCallback: Any?
    ) {
        val className = if (clazz != null) clazz.getName() else "null Class"
        val methodDesc = className + "#" + methodName
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypesAndCallback)
            Log.i("AllTransHook", "Successfully hooked: " + methodDesc)
        } catch (e: Throwable) {
            Log.e("AllTransHookError", "Cannot hook method: " + methodDesc, e)
        }
    }

    fun tryHookMethod(
        className: String?,
        classLoader: ClassLoader?,
        methodName: String?,
        vararg parameterTypesAndCallback: Any?
    ) {
        val methodDesc = className + "#" + methodName
        try {
            XposedHelpers.findAndHookMethod(
                className,
                classLoader,
                methodName,
                *parameterTypesAndCallback
            )
            Log.i("AllTransHook", "Successfully hooked: " + methodDesc)
        } catch (e: Throwable) {
            Log.e("AllTransHookError", "Cannot hook method: " + methodDesc, e)
        }
    }

    // Método corrigido de XML unescape
    fun XMLUnescape(s: String?): String? {
        if (s == null) return null
        var retVal: String? = s
        retVal = retVal!!.replace("&amp;", "&")
        retVal = retVal.replace("&quot;", "\"")
        retVal = retVal.replace("&apos;", "'")
        retVal = retVal.replace("&lt;", "<")
        retVal = retVal.replace("&gt;", ">")
        retVal = retVal.replace("&#13;", "\r")
        retVal = retVal.replace("&#10;", "\n")
        return retVal
    }

    fun javaScriptEscape(str: String?): String? {
        if (str == null) return null
        val writer = StringWriter(str.length * 2)
        val sz = str.length

        for (i in 0..<sz) {
            val ch = str.get(i)
            if (ch.code > 0xfff) {
                writer.write("\\u" + hex(ch))
            } else if (ch.code > 0xff) {
                writer.write("\\u0" + hex(ch))
            } else if (ch.code > 0x7f) {
                writer.write("\\u00" + hex(ch))
            } else if (ch.code < 32) {
                when (ch) {
                    '\b' -> writer.write("\\b")
                    '\n' -> writer.write("\\n")
                    '\t' -> writer.write("\\t")
                    '\u000C' -> writer.write("\\f") // Fixed escape sequence
                    '\r' -> writer.write("\\r")
                    else -> if (ch.code > 0xf) {
                        writer.write("\\u00" + hex(ch))
                    } else {
                        writer.write("\\u000" + hex(ch))
                    }
                }
            } else {
                when (ch) {
                    '\'' -> writer.write("\\'")
                    '"' -> writer.write("\\\"")
                    '\\' -> writer.write("\\\\")
                    '/' -> writer.write("\\/")
                    else -> writer.write(ch.code)
                }
            }
        }
        return writer.toString()
    }

    private fun hex(ch: Char): String {
        return Integer.toHexString(ch.code).uppercase()
    }
}