package com.azrael.pixivdumpsync

/** Android-independent projection of the durable transfer ledger.
 * Every state receives its own bounded recent list; a large archive of
 * completed transfers cannot hide active jobs, failures or required actions.
 * Source totals are calculated separately from the durable stores.
 */
internal object NexusQueueRowLimiter {
    private val priority = listOf("ACTIVE", "FAILED", "HELD", "QUEUED", "PAUSED", "DONE")
    fun <T> bounded(rows: List<T>, stateOf: (T) -> String): List<T> =
        priority.flatMap { state ->
            rows.asSequence().filter { stateOf(it) == state }.take(100).toList()
        }
}
