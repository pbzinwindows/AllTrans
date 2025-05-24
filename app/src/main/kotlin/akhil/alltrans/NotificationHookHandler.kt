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
            Utils.debugLog(
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
        Utils.debugLog(
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
        Utils.debugLog(charseqs.contentToString() + "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Fix para o erro de tipo de array
            val newCharseqs = Array<String?>(charseqs.size + 1) { i ->
                if (i < charseqs.size) charseqs[i] else Notification.EXTRA_BIG_TEXT
            }
            charseqs = newCharseqs
            Utils.debugLog(charseqs.contentToString() + "")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Fix para o erro de tipo de array
            val newCharseqs2 = Array<String?>(charseqs.size + 2) { i ->
                if (i < charseqs.size) charseqs[i]
                else if (i == charseqs.size) Notification.EXTRA_SELF_DISPLAY_NAME
                else Notification.EXTRA_CONVERSATION_TITLE
            }
            charseqs = newCharseqs2
            Utils.debugLog(charseqs.contentToString() + "")
        }

        // Substituído forEach por loop tradicional
        for (key in charseqs) {
            if (notification.extras.containsKey(key) && notification.extras.getCharSequence(key) != null) {
                Utils.debugLog(
                    "Got string " + key + " as " + notification.extras.getCharSequence(
                        key
                    )
                )
                allNotificationText.add(notification.extras.getCharSequence(key))
            }
        }

        //        Then Charsequence Arrays
        val charSeqArr = arrayOf(Notification.EXTRA_TEXT_LINES, Notification.EXTRA_PEOPLE)
        // Substituído forEach por loop tradicional
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
                    // Substituído forEach por loop tradicional
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
            // Substituído forEach por loop tradicional
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
                        // Substituído forEach por loop tradicional
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
        Utils.debugLog(
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

        // Substituído forEach por loop tradicional
        for (key in charseqs) {
            if (notification.extras.containsKey(key) && notification.extras.getCharSequence(key) != null) {
                if (notification.extras.getCharSequence(key).toString() == originalString) {
                    notification.extras.putCharSequence(key, translatedString)
                }
            }
        }

        //        Then Charsequence Arrays
        val charSeqArr = arrayOf(Notification.EXTRA_TEXT_LINES, Notification.EXTRA_PEOPLE)
        // Substituído forEach por loop tradicional
        for (key in charSeqArr) {
            if (notification.extras.containsKey(key) && notification.extras.getCharSequenceArray(key) != null) {
                val textList = notification.extras.getCharSequenceArray(key)
                if (textList == null) {
                    continue
                }
                val newTextList = ArrayList<CharSequence?>()
                // Substituído forEach por loop tradicional
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
                    // Substituído forEach por loop tradicional
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
            // Substituído forEach por loop tradicional
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
        Utils.debugLog("Notification : in notificationhook ")
        val notification = methodHookParam.args[methodHookParam.args.size - 1] as Notification
        val userDataOut = NotificationHookUserData(methodHookParam, "")
        callOriginalMethod("", userDataOut)

        val allNotificationTexts = getAllText(notification)

        // Usando índice manual para evitar problemas com continue em lambdas
        var textIndex = 0
        while (textIndex < allNotificationTexts.size) {
            val text = allNotificationTexts[textIndex]
            textIndex++

            if (text == null || !SetTextHookHandler.isNotWhiteSpace(text.toString())) {
                continue
            }
            val stringArgs = text.toString()
            Utils.debugLog(
                "In Thread " + Thread.currentThread()
                    .getId() + " Recognized non-english string: " + stringArgs
            )

            // Criar uma chave composta para esta tradução específica
            val compositeKey = stringArgs.hashCode()

            // Verificar se esta tradução já está pendente
            var shouldSkip = false
            synchronized(Alltrans.pendingTextViewTranslations) {
                if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) {
                    Utils.debugLog("NotificationHookHandler: Skipping translation for [$stringArgs], already pending with key ($compositeKey)")
                    shouldSkip = true
                } else {
                    // Adicionar à lista de pendentes antes de iniciar a tradução
                    Alltrans.pendingTextViewTranslations.add(compositeKey)
                    Utils.debugLog("NotificationHookHandler: Added composite key ($compositeKey) to pending set.")
                }
            }

            if (shouldSkip) {
                continue
            }

            val userData = NotificationHookUserData(methodHookParam, text.toString())

            Alltrans.cacheAccess.acquireUninterruptibly()
            var cacheHit = false
            var translatedStringFromCache: String? = null
            try {
                // Acessa o LruCache. O getter personalizado em Alltrans.kt garante que não seja nulo.
                val cacheRef = Alltrans.cache
                if (PreferenceList.Caching && cacheRef != null) {
                    // LruCache.get(key) retorna null se a chave não for encontrada.
                    // Isso substitui a necessidade de containsKey e depois get.
                    translatedStringFromCache = cacheRef.get(stringArgs)
                    if (translatedStringFromCache != null) {
                        Utils.debugLog(
                            "In Thread " + Thread.currentThread()
                                .getId() + " found string in cache: " + stringArgs + " as " + translatedStringFromCache
                        )
                        cacheHit = true
                    }
                }
            } finally {
                // Garante que o semáforo seja liberado apenas se foi adquirido e ainda não foi liberado.
                // A verificação de availablePermits() == 0 pode não ser a mais robusta se o semáforo
                // pudesse ser liberado em outro lugar dentro do try, mas para este padrão simples é ok.
                if (Alltrans.cacheAccess.availablePermits() == 0) {
                    Alltrans.cacheAccess.release()
                }
            }

            if (cacheHit) {
                // Remover da lista de pendentes pois encontrou no cache
                synchronized(Alltrans.pendingTextViewTranslations) {
                    Alltrans.pendingTextViewTranslations.remove(compositeKey)
                    Utils.debugLog("NotificationHookHandler: Removed composite key ($compositeKey) from pending set after cache hit.")
                }
                callOriginalMethod(translatedStringFromCache, userData)
                continue
            }

            val getTranslate = GetTranslate()
            getTranslate.stringToBeTrans = stringArgs
            getTranslate.originalCallable = this
            getTranslate.userData = userData
            getTranslate.canCallOriginal = true
            getTranslate.pendingCompositeKey = compositeKey  // Fornecer a chave composta para o callback

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
            // Substituído forEach por loop tradicional com índices
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