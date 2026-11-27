package com.asterion.videoflow

import android.content.Context
import com.asterion.nexus.NexusRegistry
import java.io.File
import java.net.URI
import java.util.UUID

/** Actual native Downloader original -> verified bytes -> persistent Nexus registry. */
object NexusDirectAcquisition {
    @JvmStatic
    fun acquire(context: Context, url: String): NexusRegistry.Item =
        acquire(context,url,null)

    @JvmStatic
    fun acquire(context: Context, url: String, referer: String?): NexusRegistry.Item {
        val uri = URI(url)
        require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null) {
            "A selected HTTPS media original is required"
        }
        require(NativeMediaDownloader.isDirectMediaUrl(url)) {
            "This selected URL is not a direct supported video original"
        }
        val privateStage = File(context.cacheDir, "nexus-acq-" + UUID.randomUUID().toString())
        check(privateStage.mkdirs()) { "Private transfer staging unavailable" }
        val registry = NexusRegistry(context)
        registry.job(url, "QUEUED", 0L, null)
        try {
            registry.job(url, "RECEIVING", 0L, null)
            val media = NativeMediaDownloader.download(url, referer, privateStage,
                filenameStem = "selected-original")
            val receipt = File(privateStage, media.name + ".receipt")
            check(NativeMediaReceipt.matches(media, receipt)) {
                "Downloader transfer receipt does not match the original bytes"
            }
            registry.job(url, "BYTES_COMPLETE", media.length(), null)
            val result = media.inputStream().use { input ->
                registry.ingest(input, URI(url).path.substringAfterLast('/').ifBlank { media.name },
                    "video/" + when(media.extension.lowercase()) {
                        "mp4", "m4v" -> "mp4"
                        "webm" -> "webm"
                        "mov" -> "quicktime"
                        "ts" -> "mp2t"
                        else -> "x-matroska"
                    }, "downloader-direct", "owner", url, media.length(), null)
            }
            registry.job(url, "VERIFIED", result.length, null)
            registry.job(url, "INDEXED", result.length, null)
            com.asterion.nexus.NexusCloudPublication.enqueueIfConnected(context,result)
            return result
        } catch (problem: Exception) {
            registry.job(url, "FAILED", 0L, "ACQUISITION_ERROR")
            throw problem
        } finally {
            privateStage.deleteRecursively()
        }
    }
}
