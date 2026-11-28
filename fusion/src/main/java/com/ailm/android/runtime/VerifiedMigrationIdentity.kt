package com.ailm.android.runtime

import java.util.Locale

/** Exact one-to-one binding, independent of donor database IDs and paths. */
internal object VerifiedMigrationIdentity {
    data class Record(val id: Long, val sha: String, val size: Long)
    fun record(id: Any?, sha: Any?, size: Any?): Record? {
        val n = id?.toString()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val digest = sha?.toString()?.trim()?.lowercase(Locale.ROOT) ?: return null
        val bytes = size?.toString()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (!digest.matches(Regex("[0-9a-f]{64}"))) return null
        return Record(n, digest, bytes)
    }

    fun uniqueBindings(source: List<Record>, target: List<Record>): Map<Long, Long> {
        val sourceIds = source.groupingBy { it.id }.eachCount()
        val targetIds = target.groupingBy { it.id }.eachCount()
        val sources = source.groupBy { it.sha to it.size }
        val targets = target.groupBy { it.sha to it.size }
        return sources.mapNotNull { (key, rows) ->
            val from = rows.singleOrNull() ?: return@mapNotNull null
            val to = targets[key]?.singleOrNull() ?: return@mapNotNull null
            if (sourceIds[from.id] != 1 || targetIds[to.id] != 1) return@mapNotNull null
            from.id to to.id
        }.toMap()
    }
}
