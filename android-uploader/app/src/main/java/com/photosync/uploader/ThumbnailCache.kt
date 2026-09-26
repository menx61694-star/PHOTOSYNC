package com.photosync.uploader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Small disk cache for file-list thumbnails.
 *
 * The cache lives inside the app's private cache directory, so it does not
 * create duplicate copies of the original uploaded/downloaded files.
 */
class ThumbnailCache(cacheDir: File) {
    private companion object {
        const val MAX_CACHE_BYTES = 50L * 1024L * 1024L
        const val MAX_CACHE_ITEMS = 500
    }

    private val directory = File(cacheDir, "photosync_thumbnails").apply { mkdirs() }
    private val lock = Any()

    fun get(key: String): Bitmap? {
        val file = cacheFile(key)
        if (!file.isFile || file.length() == 0L) return null
        return try {
            FileInputStream(file).use { input ->
                BitmapFactory.decodeStream(input)
            }.also {
                if (it != null) file.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) {
            null
        }
    }

    fun put(key: String, bitmap: Bitmap): Boolean {
        val target = cacheFile(key)
        val temporary = File(target.parentFile, "${target.name}_${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temporary).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    temporary.delete()
                    return false
                }
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return false
            }
            evictIfNeeded()
            true
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun evictIfNeeded() {
        synchronized(lock) {
            val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
            var totalBytes = files.sumOf { it.length() }
            if (files.size <= MAX_CACHE_ITEMS && totalBytes <= MAX_CACHE_BYTES) return

            val oldestFirst = files.sortedBy { it.lastModified() }
            for (file in oldestFirst) {
                if (files.size <= MAX_CACHE_ITEMS && totalBytes <= MAX_CACHE_BYTES) break
                val length = file.length()
                if (file.delete()) totalBytes -= length
            }
        }
    }

    private fun cacheFile(key: String): File = File(directory, sha256(key) + ".jpg")

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
