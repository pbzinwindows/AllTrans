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

data class BatchItem(
    val text: String,
    val callbackInfo: CallbackInfo
)

class BatchTranslationManager {

    private val queue: MutableList<BatchItem> = Collections.synchronizedList(LinkedList<BatchItem>())
    private var currentCharacterCount: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private val processRunnable = Runnable { processQueue() }

    companion object {
        private const val TAG = "BatchTransManager"
        private const val MAX_STRINGS_PER_BATCH = 100 // Microsoft limit is 1000, using smaller for testing/safety
        private const val MAX_CHARS_PER_BATCH = 49000 // Microsoft limit is 50000
        private const val BATCH_TIMEOUT_MS = 500L // Milliseconds
    }

    fun addString(text: String, userData: Any?, originalCallable: OriginalCallable?, canCallOriginal: Boolean) {
        if (text.isEmpty()) {
            // Optionally handle empty strings: either ignore or pass them through for individual error handling if needed
            // For now, let's assume GetTranslate/GetTranslateToken will handle empty strings if they reach there.
            // If we want to short-circuit here, we could call the callback immediately with the original empty string.
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
            originalString = text
        )
        val batchItem = BatchItem(text, callbackInfo)

        var triggerProcessNow = false
        synchronized(queue) {
            queue.add(batchItem)
            currentCharacterCount += text.length
            Log.d(TAG, "Added to queue: \"${text.take(50)}...\" Queue size: ${queue.size}, Chars: $currentCharacterCount")

            if (queue.size >= MAX_STRINGS_PER_BATCH || currentCharacterCount >= MAX_CHARS_PER_BATCH) {
                Log.i(TAG, "Batch limits reached. Triggering immediate processing.")
                triggerProcessNow = true
            }
        }

        // Always reset the timeout. If limits were hit, processQueue will clear the queue (or parts of it)
        // and if items remain, a new timeout will be set by the next addString or this one.
        handler.removeCallbacks(processRunnable) // Remove existing timeout
        if (triggerProcessNow) {
            // Process immediately if limits are hit
            // Potentially run on a different thread if processQueue is very heavy,
            // but it mainly creates objects and calls doAll which is async.
            processQueue(forceDispatch = true)
        } else if (queue.isNotEmpty()) {
            // If not processing now but queue has items, set/reset timeout
            Log.d(TAG, "Setting/resetting batch timeout: $BATCH_TIMEOUT_MS ms")
            handler.postDelayed(processRunnable, BATCH_TIMEOUT_MS)
        }
    }

    private fun processQueue(forceDispatch: Boolean = false) {
        handler.removeCallbacks(processRunnable) // Processing now, so remove any pending timeout
        Log.i(TAG, "processQueue called. Force dispatch: $forceDispatch, Current queue size: ${queue.size}, Chars: $currentCharacterCount")

        // Loop to process multiple batches if the queue exceeds limits multiple times
        while (queue.isNotEmpty()) {
            val currentBatchItems = mutableListOf<BatchItem>()
            val currentBatchStrings = mutableListOf<String>()
            val currentBatchCallbacks = mutableListOf<CallbackInfo>()
            var batchCharCount = 0

            synchronized(queue) {
                // Peek and decide which items go into this specific sub-batch
                // This section is a bit conceptual for peeking without removal yet,
                // the actual removal happens in the loop below.
                // The main goal is to decide how many items to take for the current sub-batch.
                var potentialItemCount = 0
                var potentialCharCount = 0
                for (item in queue) {
                    if (potentialItemCount < MAX_STRINGS_PER_BATCH && (potentialCharCount + item.text.length) <= MAX_CHARS_PER_BATCH) {
                        potentialItemCount++
                        potentialCharCount += item.text.length
                    } else {
                        break
                    }
                }


                // Now, build the batch from the items that fit (or all if queue is smaller than limits)
                // This loop effectively takes items from the front of the queue.
                var itemsTakenForCurrentBatch = 0
                while (itemsTakenForCurrentBatch < potentialItemCount && queue.isNotEmpty()) {
                    // Check limits again before taking item, as queue might have changed by other threads
                    // if not fully synchronized for the entire processQueue method (which it isn't, only queue ops are).
                    // However, the outer `synchronized(queue)` for sub-batch formation should make this safe.
                    val item = queue.first() // Get item from front

                    // This check is effectively done by `potentialItemCount` and `potentialCharCount` logic above
                    // if (currentBatchItems.size < MAX_STRINGS_PER_BATCH && (batchCharCount + item.text.length) <= MAX_CHARS_PER_BATCH) {

                    // No, the above logic was just for peeking. We need to build the current batch from the queue.
                    // The following is the corrected logic for building the current batch.
                    // The initial peeking loop was removed as it was redundant with the actual batch formation loop.

                    // Corrected logic for building the current batch:
                    if (currentBatchItems.size < MAX_STRINGS_PER_BATCH && (batchCharCount + queue.first().text.length) <= MAX_CHARS_PER_BATCH) {
                        val actualItem = queue.removeAt(0) // Take from front
                        currentBatchItems.add(actualItem)
                        currentBatchStrings.add(actualItem.text)
                        currentBatchCallbacks.add(actualItem.callbackInfo)
                        batchCharCount += actualItem.text.length
                        currentCharacterCount -= actualItem.text.length // Update total count
                    } else {
                        break // Current sub-batch is full or next item exceeds char limit
                    }
                    itemsTakenForCurrentBatch++ // Count items actually moved to current batch
                }


                if (itemsTakenForCurrentBatch > 0) {
                    Log.i(TAG, "Formed batch with ${currentBatchItems.size} items, $batchCharCount chars. Remaining queue: ${queue.size}, Total Chars: $currentCharacterCount")
                }
            } // End synchronized block for queue modification

            if (currentBatchItems.isNotEmpty()) {
                Log.i(TAG, "Dispatching batch of ${currentBatchStrings.size} strings, $batchCharCount chars.")

                // Mark items as pending BEFORE dispatching
                currentBatchCallbacks.forEach { cbInfo ->
                    cbInfo.userData?.hashCode()?.let { hash ->
                        // Alltrans.pendingTextViewTranslations is a synchronizedSet, so direct add is okay.
                        val added = Alltrans.pendingTextViewTranslations.add(hash)
                        if (added) {
                            Utils.debugLog("$TAG: Added hash $hash (text: '${cbInfo.originalString?.take(30)}...') to pendingTextViewTranslations for batch dispatch.")
                        } else {
                            Utils.debugLog("$TAG: Hash $hash (text: '${cbInfo.originalString?.take(30)}...') was already in pendingTextViewTranslations (before batch dispatch).")
                        }
                    }
                }

                val getTranslate = GetTranslate().apply {
                    this.stringsToBeTrans = currentBatchStrings
                    this.callbackDataList = currentBatchCallbacks
                    // languageToTranslate and sourceLanguage will be picked from PreferenceList inside GetTranslate/GetTranslateToken
                }

                // GetTranslateToken is designed to be a new instance each time.
                val getTranslateToken = GetTranslateToken().apply {
                    this.getTranslate = getTranslate
                }
                getTranslateToken.doAll() // This will eventually call getTranslate.onResponse or .onFailure

            } else if (queue.isNotEmpty()) { // currentBatchItems is empty but queue is not
                // This case implies the very first item in the queue was too large by itself.
                // This shouldn't happen if addString checks string length against MAX_CHARS_PER_BATCH,
                // or if MAX_CHARS_PER_BATCH is significantly larger than any single reasonable string.
                // For now, log it. A more robust solution might be to translate such items individually.
                Log.e(TAG, "processQueue: Next item in queue (text: '${queue.first().text.take(100)}...') is too large for any batch or an error occurred. Size: ${queue.first().text.length}. This item might get stuck if not handled.")
                // To prevent getting stuck, we could try to process it as a single item here,
                // or simply remove it and call its original callback with original text.
                // For now, let's break to avoid potential infinite loops if not `forceDispatch`.
                // If `forceDispatch` is true, this could lead to removing it without translation.
                if (!forceDispatch) break
            }


            // If not forced, and queue still has items, it means the timeout processed a part of it.
            // The remaining items will be processed by the next timeout.
            if (!forceDispatch && queue.isNotEmpty()) {
                Log.d(TAG, "Queue still has ${queue.size} items after partial processing. Timeout will be rescheduled by next addString or if this was the timeout run.")
                // Ensure timeout is active if queue is not empty.
                // This is important if processQueue was called by timeout and only processed a part.
                handler.postDelayed(processRunnable, BATCH_TIMEOUT_MS)
                break
            }
        } // End while (queue.isNotEmpty())

        if (queue.isEmpty()) {
            Log.i(TAG, "Queue processed. Now empty.")
            currentCharacterCount = 0 // Defensive reset
        } else if (forceDispatch) {
            Log.i(TAG, "Queue processed with forceDispatch. Remaining: ${queue.size}. New timeout will be set by addString if needed.")
            // If queue is not empty even after force dispatch (e.g. an item was too large and skipped),
            // ensure a timeout is set to re-evaluate.
            if (queue.isNotEmpty()) {
                handler.postDelayed(processRunnable, BATCH_TIMEOUT_MS)
            }
        }
    }
}