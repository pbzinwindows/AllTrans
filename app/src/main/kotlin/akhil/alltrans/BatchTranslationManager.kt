package akhil.alltrans

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Collections
import java.util.LinkedList // Mantido se LinkedList for preferida sobre mutableListOf
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class BatchItem(
    val text: String,
    val callbackInfo: CallbackInfo
)

class BatchTranslationManager {
    private val queueLock = ReentrantLock() // val
    // Usar mutableListOf se LinkedList não for um requisito específico
    private val queue: MutableList<BatchItem> = Collections.synchronizedList(LinkedList<BatchItem>()) // val
    private var currentCharacterCount: Int = 0 // var
    private val handler = Handler(Looper.getMainLooper()) // val
    private val processRunnable = Runnable { processQueue() } // val
    private var processingInProgress = false // var

    companion object {
        private const val TAG = "BatchTransManager"
        private const val MAX_STRINGS_PER_BATCH = 100
        private const val MAX_CHARS_PER_BATCH = 49000
        private const val BATCH_TIMEOUT_MS = 500L
    }

    fun addString(text: String, userData: Any?, originalCallable: OriginalCallable?, canCallOriginal: Boolean, compositeKey: Int = 0) {
        if (text.isEmpty()) {
            Log.w(TAG, "addString called with empty text. Passing to original callable if possible.")
            if (originalCallable != null && canCallOriginal) {
                try {
                    originalCallable.callOriginalMethod(text, userData)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error calling originalCallable for empty string.", t)
                }
            }
            return
        }

        val callbackInfo = CallbackInfo( // val
            userData = userData,
            originalCallable = originalCallable,
            canCallOriginal = canCallOriginal,
            originalString = text,
            pendingCompositeKey = compositeKey
        )
        val batchItem = BatchItem(text, callbackInfo) // val

        if (compositeKey != 0) {
            synchronized(Alltrans.pendingTextViewTranslations) {
                if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) {
                    Log.d(TAG, "Skipping batch addition for text with compositeKey=$compositeKey, already pending")
                    return
                }
                Alltrans.pendingTextViewTranslations.add(compositeKey)
                Log.d(TAG, "Added compositeKey=$compositeKey to pendingTextViewTranslations in BatchManager")
            }
        }

        var triggerProcessNow = false // var

        queueLock.withLock {
            queue.add(batchItem)
            currentCharacterCount += text.length
            Log.d(TAG, "Added to queue: \"${text.take(50)}...\" Queue size: ${queue.size}, Chars: $currentCharacterCount")

            if (queue.size >= MAX_STRINGS_PER_BATCH || currentCharacterCount >= MAX_CHARS_PER_BATCH) {
                Log.i(TAG, "Batch limits reached. Triggering immediate processing.")
                triggerProcessNow = true
            }
        }

        queueLock.withLock {
            handler.removeCallbacks(processRunnable)

            if (triggerProcessNow) {
                if (!processingInProgress) {
                    processingInProgress = true
                    handler.post { processQueue(forceDispatch = true) }
                }
            } else if (queue.isNotEmpty()) {
                Log.d(TAG, "Setting/resetting batch timeout: $BATCH_TIMEOUT_MS ms")
                handler.postDelayed(processRunnable, BATCH_TIMEOUT_MS)
            }
        }
    }

    private fun processQueue(forceDispatch: Boolean = false) {
        queueLock.withLock {
            processingInProgress = true
            handler.removeCallbacks(processRunnable)
        }

        Log.i(TAG, "processQueue called. Force dispatch: $forceDispatch, Current queue size: ${queue.size}, Chars: $currentCharacterCount")

        var continueProcessing = true // var
        while (continueProcessing) {
            val currentBatchItems = mutableListOf<BatchItem>() // val
            val currentBatchStrings = mutableListOf<String>() // val
            val currentBatchCallbacks = mutableListOf<CallbackInfo>() // val
            var batchCharCount = 0 // var
            var hasMoreItems = false // var

            queueLock.withLock {
                if (queue.isEmpty()) {
                    processingInProgress = false
                    continueProcessing = false // Sair do loop while
                    return@withLock // Sair do bloco withLock
                }

                // val itemsToProcess = minOf(MAX_STRINGS_PER_BATCH, queue.size) // Removido, lógica abaixo é mais precisa
                var charCounter = 0 // var
                var itemCounter = 0 // var

                // Determina quantos itens podem ser incluídos neste lote
                // Itera sobre uma cópia ou usa índices para evitar ConcurrentModificationException se a fila for modificada externamente (improvável aqui devido ao lock)
                // Ou, como já estamos dentro de um lock, podemos iterar com segurança
                for (i in 0 until queue.size) {
                    if (itemCounter >= MAX_STRINGS_PER_BATCH) break // Limite de strings por lote
                    val nextItemSize = queue[i].text.length
                    if (charCounter + nextItemSize <= MAX_CHARS_PER_BATCH || itemCounter == 0) { // Garante que pelo menos um item seja processado se ele sozinho exceder MAX_CHARS_PER_BATCH (se itemCounter == 0)
                        charCounter += nextItemSize
                        itemCounter++
                    } else {
                        break // Limite de caracteres atingido
                    }
                }


                if (itemCounter == 0 && queue.isNotEmpty()) { // Se nenhum item couber (ex: item único muito grande)
                    itemCounter = 1 // Processa pelo menos um (a lógica acima com "|| itemCounter == 0" já deve cobrir isso)
                }

                repeat(itemCounter) {
                    if (queue.isNotEmpty()) {
                        val item = queue.removeAt(0) // val
                        currentBatchItems.add(item)
                        currentBatchStrings.add(item.text)
                        currentBatchCallbacks.add(item.callbackInfo)
                        batchCharCount += item.text.length
                        currentCharacterCount -= item.text.length // Reduz a contagem global
                    }
                }
                hasMoreItems = queue.isNotEmpty()
            }

            if (currentBatchItems.isNotEmpty()) {
                Log.i(TAG, "Dispatching batch of ${currentBatchStrings.size} strings, $batchCharCount chars.")

                val getTranslate = GetTranslate().apply { // val
                    this.stringsToBeTrans = currentBatchStrings
                    this.callbackDataList = currentBatchCallbacks
                }

                val getTranslateToken = GetTranslateToken().apply { // val
                    this.getTranslate = getTranslate
                }
                getTranslateToken.doAll()
            }


            if (!hasMoreItems || (!forceDispatch && currentBatchItems.isNotEmpty())) {
                continueProcessing = false
            }
        }

        queueLock.withLock {
            if (queue.isEmpty()) {
                Log.i(TAG, "Queue processed. Now empty.")
                currentCharacterCount = 0
                processingInProgress = false
            } else {
                Log.i(TAG, "Queue partially processed. Remaining: ${queue.size} items, ${currentCharacterCount} chars.")
                if (!handler.hasCallbacks(processRunnable)) { // Apenas reagende se não houver um já pendente
                    handler.postDelayed(processRunnable, BATCH_TIMEOUT_MS)
                }
                // Se não for forçado, e ainda há itens, o processamento pode não ter terminado
                // mas a flag processingInProgress deve ser resetada se o loop while terminou
                // e não era um forceDispatch.
                if (!forceDispatch) { // Se era forceDispatch e ainda há itens, outro handler.post(processQueue(true)) pode ser necessário
                    processingInProgress = false
                }
            }
        }
    }
}