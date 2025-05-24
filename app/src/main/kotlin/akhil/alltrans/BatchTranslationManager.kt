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

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class BatchItem(
    val text: String,
    val callbackInfo: CallbackInfo
)

class BatchTranslationManager {
    // Usando ReentrantLock para melhor controle sobre a sincronização
    private val queueLock = ReentrantLock()
    private val queue: MutableList<BatchItem> = Collections.synchronizedList(LinkedList<BatchItem>())
    private var currentCharacterCount: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private val processRunnable = Runnable { processQueue() }
    private var processingInProgress = false

    companion object {
        private const val TAG = "BatchTransManager"
        private const val MAX_STRINGS_PER_BATCH = 100 // Microsoft limit is 1000, using smaller for testing/safety
        private const val MAX_CHARS_PER_BATCH = 49000 // Microsoft limit is 50000
        private const val BATCH_TIMEOUT_MS = 500L // Milliseconds
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

        val callbackInfo = CallbackInfo(
            userData = userData,
            originalCallable = originalCallable,
            canCallOriginal = canCallOriginal,
            originalString = text,
            pendingCompositeKey = compositeKey
        )
        val batchItem = BatchItem(text, callbackInfo)

        // Verifica se a tradução já está pendente antes de adicionar
        if (compositeKey != 0) {
            synchronized(Alltrans.pendingTextViewTranslations) {
                if (Alltrans.pendingTextViewTranslations.contains(compositeKey)) {
                    Log.d(TAG, "Skipping batch addition for text with compositeKey=$compositeKey, already pending")
                    return
                }
                // Adiciona à lista de pendentes se não estiver lá
                Alltrans.pendingTextViewTranslations.add(compositeKey)
                Log.d(TAG, "Added compositeKey=$compositeKey to pendingTextViewTranslations in BatchManager")
            }
        }

        var triggerProcessNow = false

        queueLock.withLock {
            queue.add(batchItem)
            currentCharacterCount += text.length
            Log.d(TAG, "Added to queue: \"${text.take(50)}...\" Queue size: ${queue.size}, Chars: $currentCharacterCount")

            if (queue.size >= MAX_STRINGS_PER_BATCH || currentCharacterCount >= MAX_CHARS_PER_BATCH) {
                Log.i(TAG, "Batch limits reached. Triggering immediate processing.")
                triggerProcessNow = true
            }
        }

        // Gerenciamento do timeout e processamento
        queueLock.withLock {
            handler.removeCallbacks(processRunnable) // Remove existing timeout

            if (triggerProcessNow) {
                // Se já estiver processando, não inicie outro processamento
                if (!processingInProgress) {
                    processingInProgress = true
                    // Process immediately if limits are hit
                    handler.post { processQueue(forceDispatch = true) }
                }
            } else if (queue.isNotEmpty()) {
                // Se não estiver processando agora, agende um novo timeout
                Log.d(TAG, "Setting/resetting batch timeout: $BATCH_TIMEOUT_MS ms")
                handler.postDelayed(processRunnable, BATCH_TIMEOUT_MS)
            }
        }
    }

    private fun processQueue(forceDispatch: Boolean = false) {
        queueLock.withLock {
            processingInProgress = true
            handler.removeCallbacks(processRunnable) // Processing now, so remove any pending timeout
        }

        Log.i(TAG, "processQueue called. Force dispatch: $forceDispatch, Current queue size: ${queue.size}, Chars: $currentCharacterCount")

        // Loop to process multiple batches if the queue exceeds limits multiple times
        var continueProcessing = true
        while (continueProcessing) {
            val currentBatchItems = mutableListOf<BatchItem>()
            val currentBatchStrings = mutableListOf<String>()
            val currentBatchCallbacks = mutableListOf<CallbackInfo>()
            var batchCharCount = 0
            var hasMoreItems = false

            // Extrair informações da fila sob o lock
            queueLock.withLock {
                // Caso especial: Se a fila estiver vazia, não há nada para processar
                if (queue.isEmpty()) {
                    processingInProgress = false
                    // Em vez de usar break, definimos a flag para sair do loop
                    continueProcessing = false
                    return@withLock
                }

                // Determinar quantos itens podem ser incluídos neste lote
                val itemsToProcess = minOf(MAX_STRINGS_PER_BATCH, queue.size)
                var charCounter = 0
                var itemCounter = 0

                // Primeiro, verifique quantos itens podem ser incluídos neste lote
                for (i in 0 until itemsToProcess) {
                    val nextItemSize = queue[i].text.length
                    if (charCounter + nextItemSize <= MAX_CHARS_PER_BATCH) {
                        charCounter += nextItemSize
                        itemCounter++
                    } else {
                        break
                    }
                }

                // Se nenhum item couber no lote (um único item muito grande), pegue pelo menos um item
                if (itemCounter == 0 && queue.isNotEmpty()) {
                    itemCounter = 1
                }

                // Agora extraia os itens da fila para o lote atual
                repeat(itemCounter) {
                    if (queue.isNotEmpty()) {
                        val item = queue.removeAt(0)
                        currentBatchItems.add(item)
                        currentBatchStrings.add(item.text)
                        currentBatchCallbacks.add(item.callbackInfo)
                        batchCharCount += item.text.length
                        currentCharacterCount -= item.text.length
                    }
                }

                hasMoreItems = queue.isNotEmpty()
            }

            if (currentBatchItems.isNotEmpty()) {
                Log.i(TAG, "Dispatching batch of ${currentBatchStrings.size} strings, $batchCharCount chars.")

                val getTranslate = GetTranslate().apply {
                    this.stringsToBeTrans = currentBatchStrings
                    this.callbackDataList = currentBatchCallbacks
                    // Não precisamos passar pendingCompositeKey aqui porque cada item em callbackDataList
                    // já tem seu próprio pendingCompositeKey
                }

                val getTranslateToken = GetTranslateToken().apply {
                    this.getTranslate = getTranslate
                }
                getTranslateToken.doAll() // This will eventually call getTranslate.onResponse or .onFailure
            }

            // Se não há mais itens ou não estamos forçando o despacho, saia do loop
            if (!hasMoreItems || (!forceDispatch && currentBatchItems.isNotEmpty())) {
                continueProcessing = false
            }
        }

        queueLock.withLock {
            if (queue.isEmpty()) {
                Log.i(TAG, "Queue processed. Now empty.")
                currentCharacterCount = 0 // Defensive reset
                processingInProgress = false
            } else {
                Log.i(TAG, "Queue partially processed. Remaining: ${queue.size} items, ${currentCharacterCount} chars.")

                // Se ainda houver itens na fila, agende um novo processamento
                if (!handler.hasCallbacks(processRunnable)) {
                    handler.postDelayed(processRunnable, BATCH_TIMEOUT_MS)
                }

                // Só marque como não processando se não for um despacho forçado
                if (!forceDispatch) {
                    processingInProgress = false
                }
            }
        }
    }
}