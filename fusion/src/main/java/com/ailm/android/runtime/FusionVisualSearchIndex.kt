package com.ailm.android.runtime

import android.database.sqlite.SQLiteDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Compact visual descriptor derived exclusively from the existing WD encoder. */
internal object FusionVisualSimilarity {
    fun quantize(raw: FloatArray): ByteArray? {
        val normalized = FusionVisualLinearMath.normalise(raw) ?: return null
        val scale = normalized.maxOf { abs(it) }
        if (scale <= 0f) return null
        return ByteArray(normalized.size) {
            (normalized[it] / scale * 127f).roundToInt().coerceIn(-127,127).toByte()
        }
    }

    fun normSquared(a: ByteArray): Int = a.sumOf { it.toInt() * it.toInt() }

    fun score(a: ByteArray, b: ByteArray, an: Int = normSquared(a)): Double? {
        if (a.size != FusionVisualLinearMath.FEATURE_WIDTH || b.size != a.size || an <= 0) return null
        var dot = 0
        var bn = 0
        for (i in a.indices) {
            val x = a[i].toInt()
            val y = b[i].toInt()
            dot += x * y
            bn += y * y
        }
        if (bn == 0) return null
        return (dot / sqrt(an.toDouble() * bn)).coerceIn(-1.0,1.0)
    }
}

internal class FusionVisualSearchIndex(private val db: SQLiteDatabase) {
    data class Match(val imageId: Int, val score: Double)
    data class Result(val indexed: Boolean, val compared: Int, val matches: List<Match>)
    fun store(imageId: Int, source: String, encoder: String, raw: FloatArray) {
        val feature = FusionVisualSimilarity.quantize(raw) ?: return
        // Fingerprint validation and insert occur in the same SQL statement.
        db.execSQL("""INSERT OR REPLACE INTO fusion_visual_search_index
            (image_id,source_hash,encoder_hash,feature,updated_ms)
            SELECT image_id,sha256,?,?,? FROM fusion_image_fingerprints
            WHERE image_id=? AND sha256=?""".trimIndent(),
            arrayOf(encoder, feature, System.currentTimeMillis(), imageId, source))
    }

    /** Recover still-valid whole-image vectors from the old training cache,
     * without requesting additional inference or changing its eviction policy. */
    fun recoverCached() {
        val rows = mutableListOf<Triple<Int, Pair<String,String>, ByteArray>>()
        db.rawQuery("""SELECT c.image_id,c.source_hash,c.encoder_hash,c.feature
            FROM fusion_visual_feature_cache c
            JOIN fusion_image_fingerprints f ON f.image_id=c.image_id AND f.sha256=c.source_hash
            LEFT JOIN fusion_visual_search_index s ON s.image_id=c.image_id
            WHERE c.subject_index=-1 AND s.image_id IS NULL
            LIMIT 2048""".trimIndent(),null).use { cur ->
            while(cur.moveToNext()) rows += Triple(cur.getInt(0),
                cur.getString(1) to cur.getString(2),cur.getBlob(3))
        }
        if(rows.isEmpty())return
        db.beginTransactionNonExclusive()
        try {
            for ((id, hashes, bytes) in rows) {
                if(bytes.size != FusionVisualLinearMath.FEATURE_WIDTH*4)continue
                val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val raw=FloatArray(FusionVisualLinearMath.FEATURE_WIDTH){buffer.float}
                store(id, hashes.first, hashes.second, raw)
            }
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }

    /** Stream only same-encoder, active, fingerprint-consistent records.
     * Memory is bounded by the requested result count; no fake fallback. */
    fun similar(imageId:Int, limit:Int):Result {
        recoverCached()
        var encoder:String?=null
        var query:ByteArray?=null
        val join="""FROM fusion_visual_search_index s
            JOIN fusion_image_fingerprints f ON f.image_id=s.image_id AND f.sha256=s.source_hash
            JOIN images i ON i.image_id=s.image_id
            WHERE i.active=1 AND f.source_size_bytes=i.size_bytes""".trimIndent()
        db.rawQuery("SELECT s.encoder_hash,s.feature "+join+" AND s.image_id=?",arrayOf(imageId.toString())).use {
            if(it.moveToFirst()){encoder=it.getString(0);query=it.getBlob(1)}
        }
        val model=encoder?:return Result(false,0,emptyList())
        val vector=query?:return Result(false,0,emptyList())
        val norm=FusionVisualSimilarity.normSquared(vector)
        if(vector.size!=FusionVisualLinearMath.FEATURE_WIDTH||norm==0)return Result(false,0,emptyList())
        val count=limit.coerceIn(1,100)
        val order=compareBy<Match>{it.score}.thenByDescending{it.imageId}
        val best=PriorityQueue(count,order)
        var compared=0
        db.rawQuery("SELECT s.image_id,s.feature "+join+" AND s.encoder_hash=? AND s.image_id<>?",
            arrayOf(model,imageId.toString())).use {cur->
            while(cur.moveToNext()){
                if(Thread.currentThread().isInterrupted)throw InterruptedException("Visual search interrupted")
                val score=FusionVisualSimilarity.score(vector,cur.getBlob(1),norm)?:continue
                compared++
                val match=Match(cur.getInt(0),score)
                if(best.size<count)best.add(match)
                else if(order.compare(match,best.peek())>0){best.poll();best.add(match)}
            }
        }
        return Result(true,compared,best.toList().sortedWith(order.reversed()))
    }
}
