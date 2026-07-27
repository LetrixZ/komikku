package eu.kanade.domain.koharu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Cache manager for translated manga pages.
 * Stores translated images on disk to avoid re-translating the same pages.
 */
class TranslationCache(
    private val context: Context,
) {

    private val cacheDir: File
        get() = File(context.cacheDir, "koharu_translations").also {
            if (!it.exists()) {
                it.mkdirs()
            }
        }

    /**
     * Generate a cache key for a translated page.
     * @param chapterId The chapter ID
     * @param pageIndex The page index
     * @param modelId The LLM model used for translation
     * @param targetLanguage The target language code
     * @return The cache key
     */
    private fun generateCacheKey(
        chapterId: Long,
        pageIndex: Int,
        modelId: String,
        targetLanguage: String,
    ): String {
        val input = "$chapterId-$pageIndex-$modelId-$targetLanguage"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Check if a translated page exists in cache.
     * @param chapterId The chapter ID
     * @param pageIndex The page index
     * @param modelId The LLM model used for translation
     * @param targetLanguage The target language code
     * @return The cached file if it exists, null otherwise
     */
    fun getCachedTranslation(
        chapterId: Long,
        pageIndex: Int,
        modelId: String,
        targetLanguage: String,
    ): File? {
        val key = generateCacheKey(chapterId, pageIndex, modelId, targetLanguage)
        val cachedFile = File(cacheDir, "$key.png")
        return if (cachedFile.exists()) cachedFile else null
    }

    /**
     * Save a translated image to cache.
     * @param chapterId The chapter ID
     * @param pageIndex The page index
     * @param modelId The LLM model used for translation
     * @param targetLanguage The target language code
     * @param imageFile The translated image file to cache
     * @return The cached file
     */
    suspend fun cacheTranslation(
        chapterId: Long,
        pageIndex: Int,
        modelId: String,
        targetLanguage: String,
        imageFile: File,
    ): File = withContext(Dispatchers.IO) {
        val key = generateCacheKey(chapterId, pageIndex, modelId, targetLanguage)
        val cachedFile = File(cacheDir, "$key.png")

        imageFile.copyTo(cachedFile, overwrite = true)

        logcat { "Cached translation for chapter $chapterId, page $pageIndex" }
        cachedFile
    }

    /**
     * Clear all cached translations.
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { it.delete() }
        logcat { "Translation cache cleared" }
    }

    /**
     * Get the total size of the translation cache in bytes.
     */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Get the human-readable cache size.
     */
    fun getCacheSizeFormatted(): String {
        val bytes = getCacheSize()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}
