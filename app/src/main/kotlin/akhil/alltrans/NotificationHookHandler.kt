package akhil.alltrans

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import androidx.annotation.RequiresApi
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.Arrays
import java.util.Objects

class NotificationHookHandler : XC_MethodReplacement(), OriginalCallable {
    override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
        val notificationHookUserData = userData as NotificationHookUserData
        val methodHookParam = notificationHookUserData.methodHookParam
        val originalString = notificationHookUserData.originalString
        val myMethod = methodHookParam.method as Method
        myMethod.setAccessible(true)
        val myArgs = methodHookParam.args
        val notification = methodHookParam.args[methodHookParam.args.size - 1] as Notification

        if (translatedString != null) {
            changeText(notification, originalString, translatedString.toString())
        }
        try {
            utils.debugLog(
                "In Thread " + Thread.currentThread()
                    .getId() + " Invoking original function " + methodHookParam.method.getName()
            )
            XposedBridge.invokeOriginalMethod(myMethod, methodHookParam.thisObject, myArgs)
        } catch (e: Throwable) {
            Log.e(
                "AllTrans",
                "AllTrans: Got error in invoking method as : " + Log.getStackTraceString(e)
            )
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private fun getAllText(notification: Notification): Array<CharSequence?> {
        val allNotificationText = ArrayList<CharSequence?>()
        if (notification.extras == null) {
            return allNotificationText.toTypedArray<CharSequence?>()
        }
        utils.debugLog(
            "In Thread " + Thread.currentThread()
                .getId() + " and it has extras " + notification.extras
        )

        //        First simple Charsequences
        var charseqs = arrayOf<String?>(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        )
        utils.debugLog(charseqs.contentToString() + "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Fix para o erro de tipo de array
            val newCharseqs = Array<String?>(charseqs.size + 1) { i ->
                if (i < charseqs.size) charseqs[i] else Notification.EXTRA_BIG_TEXT
            }
            charseqs = newCharseqs
            utils.debugLog(charseqs.contentToString() + "")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Fix para o erro de tipo de array
            val newCharseqs2 = Array<String?>(charseqs.size + 2) { i ->
                if (i < charseqs.size) charseqs[i]
                else if (i == charseqs.size) Notification.EXTRA_SELF_DISPLAY_NAME
                else Notification.EXTRA_CONVERSATION_TITLE
            }
            charseqs = newCharseqs2
            utils.debugLog(charseqs.contentToString() + "")
        }
        for (key in charseqs) {
            if (notification.extras.containsKey(key) && notification.extras.getCharSequence(key) != null) {
                utils.debugLog(
                    "Got string " + key + " as " + notification.extras.getCharSequence(
                        key
                    )
                )
                allNotificationText.add(notification.extras.getCharSequence(key))
            }
        }

        //        Then Charsequence Arrays
        val charSeqArr = arrayOf(Notification.EXTRA_TEXT_LINES, Notification.EXTRA_PEOPLE)
        for (key in charSeqArr) {
            if (notification.extras.containsKey(key) && notification.extras.getCharSequenceArray(key) != null) {
                allNotificationText.addAll(
                    Arrays.asList<CharSequence?>(
                        *Objects.requireNonNull<Array<CharSequence?>?>(
                            notification.extras.getCharSequenceArray(key)
                        )
                    )
                )
            }
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//        Then Person style
            if (notification.extras.containsKey(Notification.EXTRA_MESSAGING_PERSON)) {
                val person =
                    notification.extras.getParcelable<Person?>(Notification.EXTRA_MESSAGING_PERSON)
                if (person != null) {
                    allNotificationText.add(person.getName())
                }
            }
            if (notification.extras.containsKey(Notification.EXTRA_PEOPLE_LIST)) {
                val people =
                    notification.extras.getParcelableArrayList<Person?>(Notification.EXTRA_PEOPLE_LIST)
                if (people != null) {
                    for (person in people) {
                        allNotificationText.add(person.getName())
                    }
                }
            }
        }

        //        Then MessagingStyle Arrays
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            var messageArr = arrayOf(Notification.EXTRA_MESSAGES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                messageArr = arrayOf(
                    Notification.EXTRA_MESSAGES,
                    Notification.EXTRA_HISTORIC_MESSAGES
                )
            }
            for (key in messageArr) {
                if (notification.extras.containsKey(key) && notification.extras.getParcelableArray(
                        key
                    ) != null
                ) {
                    val histMessages = notification.extras.getParcelableArray(key)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val messages =
                            Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                                histMessages
                            )
                        for (message in messages) {
                            if (message == null) {
                                continue
                            }
                            if (message.getText() != null) {
                                allNotificationText.add(message.getText())
                            }
                            if (message.getSenderPerson() != null) {
                                allNotificationText.add(message.getSenderPerson()!!.getName())
                            }
                        }
                    }
                }
            }
        }
        //        Not translating Message.setData(), and Person.getURI() as requires URI retrieval
//        Not translating Actions as that will mean translating RemoteInputs which will mean
        return allNotificationText.toTypedArray<CharSequence?>()
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private fun changeText(
        notification: Notification,
        originalString: String?,
        translatedString: String?
    ) {
        utils.debugLog(
            "In Thread " + Thread.currentThread()
                .getId() + " and it has extras " + notification.extras
        )

        //        First simple Charsequences
        var charseqs = arrayOf<String?>(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Fix para o erro de tipo de array
            val newCharseqs = Array<String?>(charseqs.size + 1) { i ->
                if (i < charseqs.size) charseqs[i] else Notification.EXTRA_BIG_TEXT
            }
            charseqs = newCharseqs
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Fix para o erro de tipo de array
            val newCharseqs2 = Array<String?>(charseqs.size + 2) { i ->
                if (i < charseqs.size) charseqs[i]
                else if (i == charseqs.size) Notification.EXTRA_SELF_DISPLAY_NAME
                else Notification.EXTRA_CONVERSATION_TITLE
            }
            charseqs = newCharseqs2
        }
        for (key in charseqs) {
            if (notification.extras.containsKey(key) && notification.extras.getCharSequence(key) != null) {
                if (notification.extras.getCharSequence(key).toString() == originalString) {
                    notification.extras.putCharSequence(key, translatedString)
                }
            }
        }

        //        Then Charsequence Arrays
        val charSeqArr = arrayOf(Notification.EXTRA_TEXT_LINES, Notification.EXTRA_PEOPLE)
        for (key in charSeqArr) {
            if (notification.extras.containsKey(key) && notification.extras.getCharSequenceArray(key) != null) {
                val textList = notification.extras.getCharSequenceArray(key)
                if (textList == null) {
                    continue
                }
                val newTextList = ArrayList<CharSequence?>()
                for (charSequence in textList) {
                    if (charSequence.toString() == originalString) {
                        newTextList.add(translatedString)
                    } else {
                        newTextList.add(charSequence)
                    }
                }
                notification.extras.putCharSequenceArray(
                    key,
                    newTextList.toTypedArray<CharSequence?>()
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            //        Then Person style
            if (notification.extras.containsKey(Notification.EXTRA_MESSAGING_PERSON)) {
                val person =
                    notification.extras.getParcelable<Person?>(Notification.EXTRA_MESSAGING_PERSON)
                if (person != null) {
                    if (person.getName() === originalString) {
                        val newPerson = person.toBuilder().setName(translatedString).build()
                        notification.extras.putParcelable(
                            Notification.EXTRA_MESSAGING_PERSON,
                            newPerson
                        )
                    }
                }
            }
            if (notification.extras.containsKey(Notification.EXTRA_PEOPLE_LIST)) {
                val people =
                    notification.extras.getParcelableArrayList<Person?>(Notification.EXTRA_PEOPLE_LIST)
                val newPeople = ArrayList<Person?>()
                if (people != null) {
                    for (person in people) {
                        if (person.getName() === originalString) {
                            val newPerson = person.toBuilder().setName(translatedString).build()
                            newPeople.add(newPerson)
                        } else {
                            newPeople.add(person)
                        }
                    }
                    notification.extras.putParcelableArrayList(
                        Notification.EXTRA_PEOPLE_LIST,
                        newPeople
                    )
                }
            }
        }

        //        Then MessagingStyle Arrays
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            var messageArr = arrayOf(Notification.EXTRA_MESSAGES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                messageArr = arrayOf(
                    Notification.EXTRA_MESSAGES,
                    Notification.EXTRA_HISTORIC_MESSAGES
                )
            }
            for (key in messageArr) {
                if (notification.extras.containsKey(key) && notification.extras.getParcelableArray(
                        key
                    ) != null
                ) {
                    val histMessages = notification.extras.getParcelableArray(key)
                    val newmessages: Array<Parcelable?>? =
                        getMessagesFromBundleArray2(histMessages, originalString, translatedString)
                    notification.extras.putParcelableArray(key, newmessages)
                }
            }
        }
        //        Not translating Message.setData(), and Person.getURI() as requires URI retrieval
//        Not translating Actions as that will mean translating RemoteInputs which will mean
    }

    @Throws(Throwable::class)
    override fun replaceHookedMethod(methodHookParam: MethodHookParam): Any? {
        utils.debugLog("Notification : in notificationhook ")
        val notification = methodHookParam.args[methodHookParam.args.size - 1] as Notification
        val userDataOut = NotificationHookUserData(methodHookParam, "")
        callOriginalMethod("", userDataOut)

        val allNotificationTexts = getAllText(notification)

        for (text in allNotificationTexts) {
            if (text == null || !SetTextHookHandler.Companion.isNotWhiteSpace(text.toString())) {
                continue
            }
            val stringArgs = text.toString()
            utils.debugLog(
                "In Thread " + Thread.currentThread()
                    .getId() + " Recognized non-english string: " + stringArgs
            )
            val userData = NotificationHookUserData(methodHookParam, text.toString())

            alltrans.Companion.cacheAccess.acquireUninterruptibly()
            try {
                if (PreferenceList.Caching && alltrans.Companion.cache != null && alltrans.Companion.cache?.containsKey(stringArgs) == true) {
                    val translatedString: String? = alltrans.Companion.cache?.get(stringArgs)
                    utils.debugLog(
                        "In Thread " + Thread.currentThread()
                            .getId() + " found string in cache: " + stringArgs + " as " + translatedString
                    )
                    alltrans.Companion.cacheAccess.release()
                    callOriginalMethod(translatedString, userData)
                    continue
                } else {
                    alltrans.Companion.cacheAccess.release()
                }
            } finally {
                if (alltrans.Companion.cacheAccess.availablePermits() == 0) {
                    alltrans.Companion.cacheAccess.release()
                }
            }

            val getTranslate = GetTranslate()
            getTranslate.stringToBeTrans = stringArgs
            getTranslate.originalCallable = this
            getTranslate.userData = userData
            getTranslate.canCallOriginal = true
            val getTranslateToken = GetTranslateToken()
            getTranslateToken.getTranslate = getTranslate
            getTranslateToken.doAll()
        }
        return null
    }

    companion object {
        fun getMessagesFromBundleArray2(
            bundles: Array<Parcelable?>?,
            originalString: String?,
            translatedString: String?
        ): Array<Parcelable?>? {
            if (bundles == null) {
                return null
            }
            for (i in bundles.indices) {
                val bundle = bundles[i] as Bundle?
                if (bundle == null) {
                    continue
                }
                if (bundle.containsKey("text") && bundle.getCharSequence("text") != null &&
                    bundle.getCharSequence("text").toString() == originalString
                ) {
                    bundle.putCharSequence("text", translatedString)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val senderPerson = bundle.getParcelable<Person?>("sender_person")
                    if (senderPerson != null && senderPerson.getName() === originalString) {
                        val newPerson = senderPerson.toBuilder().setName(translatedString).build()
                        bundle.putParcelable("sender_person", newPerson)
                    }
                }
                val senderName = bundle.getCharSequence("sender")
                if (senderName != null && senderName == originalString) {
                    bundle.putCharSequence("sender", translatedString)
                }
                bundles[i] = bundle
            }
            return bundles
        }
    }
}

internal class NotificationHookUserData(
    val methodHookParam: MethodHookParam,
    val originalString: String?
)