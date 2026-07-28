package eu.kanade.domain.koharu

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.storage.DiskUtil
import logcat.logcat
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileInputStream

/**
 * Persistent storage for Koharu translated images.
 * Stores translated page images on disk so they survive cache clears
 * and can be toggled between original/translated in the reader.
 *
 * Directory structure:
 * {storageDir}/translations/koharu/{sourceName}/{mangaTitle}/{chapterPrefix}/page_{index}.png
 */
class TranslationStorage(
    private val context: Context,
    private val storageManager: StorageManager = Injekt.get(),
) {

    /**
     * Get the base translations directory for Koharu.
     */
    private fun getBaseDir(): UniFile? {
        return storageManager.getTranslationsDirectory()
    }

    /**
     * Generate a sanitized directory name for a source.
     */
    private fun getSourceDirName(source: Source): String {
        return DiskUtil.buildValidFilename(source.toString())
    }

    /**
     * Generate a sanitized directory name for a manga.
     */
    private fun getMangaDirName(mangaTitle: String): String {
        return DiskUtil.buildValidFilename(mangaTitle)
    }

    /**
     * Generate a sanitized file/prefix name for a chapter.
     */
    private fun getChapterPrefix(chapterName: String, chapterScanlator: String?): String {
        val name = DiskUtil.buildValidFilename(chapterName)
        return if (!chapterScanlator.isNullOrBlank()) {
            "${DiskUtil.buildValidFilename(chapterScanlator)}_$name"
        } else {
            name
        }
    }

    /**
     * Get the directory for a specific chapter's translations.
     * Returns null if it doesn't exist.
     */
    private fun getChapterDir(
        source: Source,
        mangaTitle: String,
        chapterName: String,
        chapterScanlator: String?,
    ): UniFile? {
        val baseDir = getBaseDir() ?: return null
        val sourceDirName = getSourceDirName(source)
        val mangaDirName = getMangaDirName(mangaTitle)
        val chapterPrefix = getChapterPrefix(chapterName, chapterScanlator)
        val sourceDir = baseDir.findFile(sourceDirName) ?: return null
        val mangaDir = sourceDir.findFile(mangaDirName) ?: return null
        return mangaDir.findFile(chapterPrefix)
    }

    /**
     * Get or create the directory for a specific chapter's translations.
     */
    private fun getOrCreateChapterDir(
        source: Source,
        mangaTitle: String,
        chapterName: String,
        chapterScanlator: String?,
    ): UniFile? {
        val baseDir = getBaseDir() ?: return null
        val sourceDirName = getSourceDirName(source)
        val mangaDirName = getMangaDirName(mangaTitle)
        val chapterPrefix = getChapterPrefix(chapterName, chapterScanlator)
        val sourceDir = baseDir.findFile(sourceDirName) ?: baseDir.createDirectory(sourceDirName) ?: return null
        val mangaDir = sourceDir.findFile(mangaDirName) ?: sourceDir.createDirectory(mangaDirName) ?: return null
        return mangaDir.findFile(chapterPrefix) ?: mangaDir.createDirectory(chapterPrefix)
    }

    /**
     * Check if a chapter has all its pages translated.
     */
    fun isChapterTranslated(
        source: Source,
        mangaTitle: String,
        chapterName: String,
        chapterScanlator: String?,
        totalPages: Int = -1,
    ): Boolean {
        val dir = getChapterDir(source, mangaTitle, chapterName, chapterScanlator) ?: return false
        if (totalPages <= 0) return dir.exists() && (dir.listFiles()?.isNotEmpty() == true)
        val files = dir.listFiles() ?: return false
        // Check if we have at least as many translated pages as total pages
        val pageFiles = files.filter { it.name?.startsWith("page_") == true }
        return pageFiles.size >= totalPages
    }

    /**
     * Get the translated image file for a specific page, if it exists.
     */
    fun getTranslatedPageFile(
        source: Source,
        mangaTitle: String,
        chapterName: String,
        chapterScanlator: String?,
        pageIndex: Int,
    ): UniFile? {
        val dir = getChapterDir(source, mangaTitle, chapterName, chapterScanlator) ?: return null
        val pageFileName = "page_$pageIndex.png"
        return dir.findFile(pageFileName)
    }

    /**
     * Save a translated page image to persistent storage.
     */
    suspend fun saveTranslatedPage(
        source: Source,
        mangaTitle: String,
        chapterName: String,
        chapterScanlator: String?,
        pageIndex: Int,
        imageFile: File,
    ) = withIOContext {
        val dir = getOrCreateChapterDir(source, mangaTitle, chapterName, chapterScanlator) ?: return@withIOContext
        val pageFileName = "page_$pageIndex.png"
        val existing = dir.findFile(pageFileName)
        existing?.delete()

        val destFile = dir.createFile(pageFileName) ?: return@withIOContext
        FileInputStream(imageFile).use { input ->
            destFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        logcat { "Saved translated page: $mangaTitle/$chapterName/page_$pageIndex.png" }
    }

    /**
     * Delete all translated pages for a chapter.
     */
    suspend fun deleteChapterTranslation(
        source: Source,
        mangaTitle: String,
        chapterName: String,
        chapterScanlator: String?,
    ) = withIOContext {
        val dir = getChapterDir(source, mangaTitle, chapterName, chapterScanlator)
        if (dir != null) {
            deleteDirectoryRecursive(dir)
            logcat { "Deleted translations for chapter: $mangaTitle/$chapterName" }
        }
    }

    /**
     * Delete all translations for a manga.
     */
    suspend fun deleteMangaTranslation(
        source: Source,
        mangaTitle: String,
    ) = withIOContext {
        val baseDir = getBaseDir() ?: return@withIOContext
        val sourceDirName = getSourceDirName(source)
        val mangaDirName = getMangaDirName(mangaTitle)
        val sourceDir = baseDir.findFile(sourceDirName) ?: return@withIOContext
        val dir = sourceDir.findFile(mangaDirName)
        if (dir != null) {
            deleteDirectoryRecursive(dir)
            logcat { "Deleted all translations for manga: $mangaTitle" }
        }
    }

    /**
     * Get count of translated pages for a chapter.
     */
    fun getTranslatedPageCount(
        source: Source,
        mangaTitle: String,
        chapterName: String,
        chapterScanlator: String?,
    ): Int {
        val dir = getChapterDir(source, mangaTitle, chapterName, chapterScanlator) ?: return 0
        return dir.listFiles()?.count { it.name?.startsWith("page_") == true } ?: 0
    }

    /**
     * Recursively delete a directory and its contents.
     */
    private fun deleteDirectoryRecursive(dir: UniFile) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleteDirectoryRecursive(file)
            }
            file.delete()
        }
        dir.delete()
    }

    /**
     * Get total size of all translated files.
     */
    fun getTotalSize(): Long {
        val baseDir = getBaseDir() ?: return 0L
        return calculateDirectorySize(baseDir)
    }

    /**
     * Get the human-readable cache size.
     */
    fun getTotalSizeFormatted(): String {
        val bytes = getTotalSize()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    private fun calculateDirectorySize(dir: UniFile): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                size += calculateDirectorySize(file)
            } else {
                size += file.length()
            }
        }
        return size
    }

    /**
     * Clear all Koharu translations.
     */
    suspend fun clearAll() = withIOContext {
        val baseDir = getBaseDir()
        baseDir?.listFiles()?.forEach { dir ->
            deleteDirectoryRecursive(dir)
        }
        logcat { "All Koharu translations cleared" }
    }
}
