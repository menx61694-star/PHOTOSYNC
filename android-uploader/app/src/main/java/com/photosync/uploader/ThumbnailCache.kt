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
    private val directory = File(cacheDir, "photosync_thumbnails").apply { mkdirs() }

    fun get(key: String): Bitmap? {
        val file = cacheFile(key)
        if (!file.isFile || file.length() == 0L) return null
        return try {
            FileInputStream(file).use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun put(key: String, bitmap: Bitmap): Boolean {
        val target = cacheFile(key)
        val temporary = File(target.parentFile, "${target.name}.tmp")
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
            true
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun cacheFile(key: String): File = File(directory, sha256(key) + ".jpg")

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
