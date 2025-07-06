package akhil.alltrans

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class BatchItem(
    val text: String,
    val callbackInfo: CallbackInfo,
    val priority: Int = 0, // For priority handling
    val timestamp: Long = System.currentTimeMillis()
)

class BatchTranslationManager {
    private val queueLock = ReentrantLock()
    private val queue = ConcurrentLinkedQueue<BatchItem>()
    private val currentCharacterCount = AtomicInteger(0)
    private val currentItemCount = AtomicInteger(0)
    private val handler = Handler(Looper.getMainLooper())
    private val processingInProgress = AtomicBoolean(false)
    private val lastProcessTime = AtomicLong(0)

    // Adaptive batching
    private val adaptiveBatchSize = AtomicInteger(DEFAULT_BATCH_SIZE)
    private val averageLatency = AtomicLong(1000L) // Default 1 second

    // Performance monitoring
    private var totalBatchesProcessed = 0
    private var totalItemsProcessed = 0
    private var totalProcessingTime = 0L

    companion object {
        private const val TAG = "BatchTransManager"
        private const val DEFAULT_BATCH_SIZE = 50
        private const val MAX_STRINGS_PER_BATCH = 100
        private const val MAX_CHARS_PER_BATCH = 49000
        private const val BATCH_TIMEOUT_MS = 500L
        private const val ADAPTIVE_TIMEOUT_MIN = 200L
        private const val ADAPTIVE_TIMEOUT_MAX = 2000L
        private const val HIGH_PRIORITY_THRESHOLD = 10 // Items with priority >= 10 are urgent
    }

    fun addString(text: String, userData: Any?, originalCallable: OriginalCallable?, canCallOriginal: Boolean, compositeKey: Int = 0, priority: Int = 0) {
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
        val batchItem = BatchItem(text, callbackInfo, priority)

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

        // Add to queue
        queue.offer(batchItem)
        val newCharCount = currentCharacterCount.addAndGet(text.length)
        val newItemCount = currentItemCount.incrementAndGet()

        Log.d(TAG, "Added to queue: \"${text.take(50)}...\" Queue size: $newItemCount, Chars: $newCharCount, Priority: $priority")

        // Check if immediate processing is needed
        val currentBatchSize = adaptiveBatchSize.get()
        val shouldTriggerImmediately = newItemCount >= currentBatchSize ||
                newCharCount >= MAX_CHARS_PER_BATCH ||
                priority >= HIGH_PRIORITY_THRESHOLD ||
                hasHighPriorityItems()

        if (shouldTriggerImmediately) {
            Log.i(TAG, "Triggering immediate processing. Items: $newItemCount/$currentBatchSize, Chars: $newCharCount/$MAX_CHARS_PER_BATCH, Priority trigger: ${priority >= HIGH_PRIORITY_THRESHOLD}")
            triggerProcessing(true)
        } else {
            // Schedule delayed processing with adaptive timeout
            val timeout = calculateAdaptiveTimeout()
            scheduleDelayedProcessing(timeout)
        }
    }

    private fun hasHighPriorityItems(): Boolean {
        return queue.any { it.priority >= HIGH_PRIORITY_THRESHOLD }
    }

    private fun calculateAdaptiveTimeout(): Long {
        val latency = averageLatency.get()
        return when {
            latency < 500 -> ADAPTIVE_TIMEOUT_MIN
            latency > 3000 -> ADAPTIVE_TIMEOUT_MAX
            else -> (BATCH_TIMEOUT_MS * (latency / 1000.0)).toLong()
        }.coerceIn(ADAPTIVE_TIMEOUT_MIN, ADAPTIVE_TIMEOUT_MAX)
    }

    private fun scheduleDelayedProcessing(delay: Long) {
        queueLock.withLock {
            handler.removeCallbacks(processRunnable)
            if (currentItemCount.get() > 0) {
                Log.d(TAG, "Scheduling batch processing in ${delay}ms")
                handler.postDelayed(processRunnable, delay)
            }
        }
    }

    private fun triggerProcessing(force: Boolean) {
        if (processingInProgress.compareAndSet(false, true)) {
            // Submit to background thread for processing
            ThreadPoolManager.ioExecutor.submit {
                processQueue(force)
            }
        } else {
            Log.d(TAG, "Processing already in progress, skipping trigger")
        }
    }

    private val processRunnable = Runnable {
        triggerProcessing(false)
    }

    private fun processQueue(forceDispatch: Boolean = false) {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "processQueue started. Force dispatch: $forceDispatch, Current queue size: ${currentItemCount.get()}")

        var batchesInThisRun = 0
        try {
            var continueProcessing = true

            while (continueProcessing) {
                val batch = extractBatch()

                if (batch.isEmpty()) {
                    Log.i(TAG, "No items to process, stopping")
                    continueProcessing = false
                    break
                }

                Log.i(TAG, "Processing batch ${batchesInThisRun + 1} with ${batch.size} items")
                dispatchBatch(batch)
                batchesInThisRun++

                // Update statistics
                totalBatchesProcessed++
                totalItemsProcessed += batch.size

                // Check if we should continue processing
                val remainingItems = currentItemCount.get()
                continueProcessing = forceDispatch && remainingItems > 0 && batchesInThisRun < 5 // Limit to prevent blocking

                if (continueProcessing) {
                    Log.d(TAG, "Continuing processing, remaining items: $remainingItems")
                }
            }

            // Update adaptive batch size based on performance
            updateAdaptiveBatchSize(startTime)

        } finally {
            processingInProgress.set(false)
            lastProcessTime.set(System.currentTimeMillis())

            // Schedule next processing if items remain
            queueLock.withLock {
                handler.removeCallbacks(processRunnable)
                if (currentItemCount.get() > 0) {
                    val timeout = calculateAdaptiveTimeout()
                    Log.d(TAG, "Rescheduling processing for remaining ${currentItemCount.get()} items in ${timeout}ms")
                    handler.postDelayed(processRunnable, timeout)
                }
            }
        }

        val processingTime = System.currentTimeMillis() - startTime
        totalProcessingTime += processingTime
        Log.i(TAG, "processQueue completed in ${processingTime}ms. Batches processed: $batchesInThisRun")
    }

    private fun extractBatch(): List<BatchItem> {
        val batch = mutableListOf<BatchItem>()
        val maxBatchSize = adaptiveBatchSize.get()
        var batchCharCount = 0
        var batchItemCount = 0

        // Prioritize high-priority items
        val highPriorityItems = mutableListOf<BatchItem>()
        val normalItems = mutableListOf<BatchItem>()

        // Extract items from queue
        var item = queue.poll()
        while (item != null && batchItemCount < MAX_STRINGS_PER_BATCH) {
            if (item.priority >= HIGH_PRIORITY_THRESHOLD) {
                highPriorityItems.add(item)
            } else {
                normalItems.add(item)
            }

            currentItemCount.decrementAndGet()
            currentCharacterCount.addAndGet(-item.text.length)

            item = queue.poll()
        }

        // Build batch starting with high priority items
        val allItems = highPriorityItems + normalItems

        for (batchItem in allItems) {
            val itemLength = batchItem.text.length

            // Check if adding this item would exceed limits
            if (batchItemCount >= maxBatchSize ||
                batchCharCount + itemLength > MAX_CHARS_PER_BATCH) {

                // Put item back in queue if batch is not empty
                if (batch.isNotEmpty()) {
                    queue.offer(batchItem)
                    currentItemCount.incrementAndGet()
                    currentCharacterCount.addAndGet(itemLength)
                    break
                }
                // If batch is empty, include at least one item even if it's large
            }

            batch.add(batchItem)
            batchCharCount += itemLength
            batchItemCount++
        }

        Log.d(TAG, "Extracted batch: ${batch.size} items, $batchCharCount chars")
        return batch
    }

    private fun dispatchBatch(batch: List<BatchItem>) {
        if (batch.isEmpty()) return

        val startTime = System.currentTimeMillis()
        val batchStrings = batch.map { it.text }
        val batchCallbacks = batch.map { it.callbackInfo }

        Log.i(TAG, "Dispatching batch of ${batchStrings.size} strings, ${batchStrings.sumOf { it.length }} chars.")

        val getTranslate = GetTranslate().apply {
            this.stringsToBeTrans = batchStrings
            this.callbackDataList = batchCallbacks
        }

        val getTranslateToken = GetTranslateToken().apply {
            this.getTranslate = getTranslate
        }

        try {
            getTranslateToken.doAll()

            // Update latency tracking
            val latency = System.currentTimeMillis() - startTime
            updateAverageLatency(latency)

        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching batch", e)
            // Handle failure by calling original methods
            handleBatchDispatchFailure(batchCallbacks)
        }
    }

    private fun updateAverageLatency(newLatency: Long) {
        val currentAvg = averageLatency.get()
        // Simple exponential moving average
        val newAvg = (currentAvg * 0.7 + newLatency * 0.3).toLong()
        averageLatency.set(newAvg)

        Log.d(TAG, "Updated average latency: ${newAvg}ms (from ${currentAvg}ms, new sample: ${newLatency}ms)")
    }

    private fun updateAdaptiveBatchSize(processStartTime: Long) {
        val processingTime = System.currentTimeMillis() - processStartTime
        val currentSize = adaptiveBatchSize.get()
        val avgLatency = averageLatency.get()

        val newSize = when {
            // If processing is fast and latency is low, increase batch size
            processingTime < 100 && avgLatency < 800 -> minOf(MAX_STRINGS_PER_BATCH, currentSize + 5)
            // If processing is slow or latency is high, decrease batch size
            processingTime > 500 || avgLatency > 2000 -> maxOf(10, currentSize - 5)
            else -> currentSize
        }

        if (newSize != currentSize) {
            adaptiveBatchSize.set(newSize)
            Log.i(TAG, "Adaptive batch size changed: $currentSize -> $newSize (processing: ${processingTime}ms, latency: ${avgLatency}ms)")
        }
    }

    private fun handleBatchDispatchFailure(callbacks: List<CallbackInfo>) {
        Log.w(TAG, "Handling batch dispatch failure for ${callbacks.size} items")
        callbacks.forEach { callbackInfo ->
            try {
                if (callbackInfo.originalCallable != null && callbackInfo.canCallOriginal) {
                    callbackInfo.originalCallable.callOriginalMethod(
                        callbackInfo.originalString ?: "",
                        callbackInfo.userData
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in failure callback for: ${callbackInfo.originalString}", e)
            } finally {
                // Remove from pending
                synchronized(Alltrans.pendingTextViewTranslations) {
                    Alltrans.pendingTextViewTranslations.remove(callbackInfo.pendingCompositeKey)
                }
            }
        }
    }

    // Public method to get statistics
    fun getStatistics(): String {
        val queueSize = currentItemCount.get()
        val queueChars = currentCharacterCount.get()
        val avgLatency = averageLatency.get()
        val currentBatchSize = adaptiveBatchSize.get()
        val avgProcessingTime = if (totalBatchesProcessed > 0) totalProcessingTime / totalBatchesProcessed else 0

        return "Queue: $queueSize items ($queueChars chars) | " +
                "Batches: $totalBatchesProcessed | Items: $totalItemsProcessed | " +
                "Avg Latency: ${avgLatency}ms | Batch Size: $currentBatchSize | " +
                "Avg Processing: ${avgProcessingTime}ms"
    }

    // Method to force immediate processing (useful for testing or urgent situations)
    fun forceProcess() {
        Log.i(TAG, "Force processing requested")
        triggerProcessing(true)
    }

    // Method to clear the queue (useful for cleanup)
    fun clearQueue() {
        Log.i(TAG, "Clearing queue")
        queue.clear()
        currentItemCount.set(0)
        currentCharacterCount.set(0)
        handler.removeCallbacks(processRunnable)
    }
}