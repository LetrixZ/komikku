package eu.kanade.domain.koharu

import android.app.Application
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.ArchivePageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.logcat
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File

/**
 * Manager for pre-translating chapters before reading.
 * This allows users to queue entire chapters for translation.
 * Users must download the chapter first before translating.
 */
class TranslationPreFetchManager(
    private val koharuClient: KoharuClient,
    private val koharuPreferences: KoharuPreferences,
    private val translationStorage: TranslationStorage,
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context: Application by injectLazy()
    private val queueMutex = Mutex()
    private val notifier = TranslationNotifier(context)

    // Track translation state per chapter
    private val _chapterTranslationStates = MutableStateFlow<Map<Long, ChapterTranslationState>>(emptyMap())
    val chapterTranslationStates: StateFlow<Map<Long, ChapterTranslationState>> =
        _chapterTranslationStates.asStateFlow()

    // Queue for pending translations
    private val translationQueue = mutableListOf<TranslationRequest>()
    private var currentTranslationJob: Job? = null
    private var isTranslating = false

    private var archivePageLoader: ArchivePageLoader? = null

    data class TranslationRequest(
        val manga: Manga,
        val chapter: Chapter,
    )

    data class ChapterTranslationState(
        val chapterId: Long,
        val state: State,
        val progress: Int, // 0-100
        val totalPages: Int,
        val translatedPages: Int,
    ) {
        enum class State {
            NOT_TRANSLATED,
            QUEUED,
            TRANSLATING,
            TRANSLATED,
            ERROR,
        }
    }

    /**
     * Check if Koharu is properly configured.
     */
    fun isConfigured(): Boolean {
        val serverUrl = koharuPreferences.koharuServerUrl().get()
        val model = koharuPreferences.koharuLlmModel().get()
        val language = koharuPreferences.koharuTargetLanguage().get()
        return serverUrl.isNotBlank() && model.isNotBlank() && language.isNotBlank()
    }

    /**
     * Start pre-translating a chapter. If another translation is in progress, it will be queued.
     */
    fun startTranslation(manga: Manga, chapter: Chapter) {
        if (!isConfigured()) {
            logcat(LogPriority.ERROR) { "Koharu not configured, cannot start translation" }
            return
        }

        val chapterId = chapter.id ?: return

        // Check if already translating or translated
        val currentState = _chapterTranslationStates.value[chapterId]
        if (currentState?.state == ChapterTranslationState.State.TRANSLATED ||
            currentState?.state == ChapterTranslationState.State.TRANSLATING ||
            currentState?.state == ChapterTranslationState.State.QUEUED
        ) {
            return
        }

        // Initialize state
        updateChapterState(
            ChapterTranslationState(
                chapterId = chapterId,
                state = ChapterTranslationState.State.QUEUED,
                progress = 0,
                totalPages = 0,
                translatedPages = 0,
            ),
        )

        // Add to queue
        scope.launch {
            queueMutex.withLock {
                translationQueue.add(TranslationRequest(manga, chapter))
                logcat { "Queued translation for chapter $chapterId" }
            }
            processQueue()
        }
    }

    /**
     * Process the translation queue. Only one translation can be active at a time.
     */
    private fun processQueue() {
        scope.launch {
            queueMutex.withLock {
                if (isTranslating || translationQueue.isEmpty()) {
                    return@withLock
                }

                isTranslating = true
                val request = translationQueue.removeAt(0)
                val chapterId = request.chapter.id ?: return@launch

                // Update state to translating
                updateChapterState(
                    ChapterTranslationState(
                        chapterId = chapterId,
                        state = ChapterTranslationState.State.TRANSLATING,
                        progress = 0,
                        totalPages = 0,
                        translatedPages = 0,
                    ),
                )

                currentTranslationJob = scope.launch {
                    try {
                        translateChapter(request.manga, request.chapter)
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Translation failed for chapter $chapterId" }
                        notifier.onError(
                            mangaTitle = request.manga.title,
                            chapterName = request.chapter.name,
                            error = e.message,
                        )
                    } finally {
                        queueMutex.withLock {
                            isTranslating = false
                            currentTranslationJob = null
                        }
                        // Process next item in queue
                        processQueue()
                    }
                }
            }
        }
    }

    /**
     * Cancel translation for a chapter.
     */
    fun cancelTranslation(chapterId: Long) {
        scope.launch {
            queueMutex.withLock {
                // Remove from queue if present
                translationQueue.removeAll { it.chapter.id == chapterId }

                // Cancel current job if it's for this chapter
                if (currentTranslationJob?.isActive == true) {
                    val currentChapterId = _chapterTranslationStates.value.entries
                        .find { it.value.state == ChapterTranslationState.State.TRANSLATING }
                        ?.key
                    if (currentChapterId == chapterId) {
                        currentTranslationJob?.cancel()
                        isTranslating = false
                        notifier.dismissProgress()
                    }
                }
            }

            updateChapterState(
                ChapterTranslationState(
                    chapterId = chapterId,
                    state = ChapterTranslationState.State.NOT_TRANSLATED,
                    progress = 0,
                    totalPages = 0,
                    translatedPages = 0,
                ),
            )
        }
    }

    /**
     * Clear all pending translations in the queue.
     */
    fun clearQueue() {
        scope.launch {
            queueMutex.withLock {
                // Cancel all queued translations
                translationQueue.forEach { request ->
                    val chapterId = request.chapter.id ?: return@forEach
                    updateChapterState(
                        ChapterTranslationState(
                            chapterId = chapterId,
                            state = ChapterTranslationState.State.NOT_TRANSLATED,
                            progress = 0,
                            totalPages = 0,
                            translatedPages = 0,
                        ),
                    )
                }
                translationQueue.clear()

                // Cancel current translation if active
                if (currentTranslationJob?.isActive == true) {
                    currentTranslationJob?.cancel()
                    isTranslating = false
                    notifier.dismissProgress()

                    // Update current translating chapter state
                    val currentChapterId = _chapterTranslationStates.value.entries
                        .find { it.value.state == ChapterTranslationState.State.TRANSLATING }
                        ?.key
                    if (currentChapterId != null) {
                        updateChapterState(
                            ChapterTranslationState(
                                chapterId = currentChapterId,
                                state = ChapterTranslationState.State.NOT_TRANSLATED,
                                progress = 0,
                                totalPages = 0,
                                translatedPages = 0,
                            ),
                        )
                    }
                }

                logcat { "Translation queue cleared" }
            }
        }
    }

    /**
     * Delete translated chapter - removes all stored translated images from persistent storage.
     */
    fun deleteTranslation(chapter: Chapter, manga: Manga) {
        scope.launch {
            queueMutex.withLock {
                // Remove from queue if present
                translationQueue.removeAll { it.chapter.id == chapter.id }

                // Cancel current job if it's for this chapter
                if (currentTranslationJob?.isActive == true) {
                    val currentChapterId = _chapterTranslationStates.value.entries
                        .find { it.value.state == ChapterTranslationState.State.TRANSLATING }
                        ?.key
                    if (currentChapterId == chapter.id) {
                        currentTranslationJob?.cancel()
                        isTranslating = false
                        notifier.dismissProgress()
                    }
                }
            }

            // Actually delete the stored translation files
            val source = Injekt.get<SourceManager>().getOrStub(manga.source)
            translationStorage.deleteChapterTranslation(
                source = source,
                mangaTitle = manga.title,
                chapterName = chapter.name,
                chapterScanlator = chapter.scanlator,
            )

            updateChapterState(
                ChapterTranslationState(
                    chapterId = chapter.id,
                    state = ChapterTranslationState.State.NOT_TRANSLATED,
                    progress = 0,
                    totalPages = 0,
                    translatedPages = 0,
                ),
            )
            logcat { "Deleted translation for chapter ${chapter.id}" }
        }
    }

    /**
     * Get translation state for a chapter.
     * If no runtime state exists, checks persistent storage.
     */
    fun getChapterState(chapterId: Long, manga: Manga? = null, chapter: Chapter? = null): ChapterTranslationState {
        // Check runtime state first
        val runtimeState = _chapterTranslationStates.value[chapterId]
        if (runtimeState != null) return runtimeState

        // Check persistent storage if manga/chapter info is available
        if (manga != null && chapter != null) {
            val source = Injekt.get<SourceManager>().getOrStub(manga.source)
            val isTranslated = translationStorage.isChapterTranslated(
                source = source,
                mangaTitle = manga.title,
                chapterName = chapter.name,
                chapterScanlator = chapter.scanlator,
            )
            if (isTranslated) {
                val totalPages = translationStorage.getTranslatedPageCount(
                    source = source,
                    mangaTitle = manga.title,
                    chapterName = chapter.name,
                    chapterScanlator = chapter.scanlator,
                )
                return ChapterTranslationState(
                    chapterId = chapterId,
                    state = ChapterTranslationState.State.TRANSLATED,
                    progress = 100,
                    totalPages = totalPages,
                    translatedPages = totalPages,
                )
            }
        }

        return ChapterTranslationState(
            chapterId = chapterId,
            state = ChapterTranslationState.State.NOT_TRANSLATED,
            progress = 0,
            totalPages = 0,
            translatedPages = 0,
        )
    }

    /**
     * Check if a chapter is fully translated (including persistent storage).
     */
    fun isChapterTranslated(chapterId: Long, manga: Manga? = null, chapter: Chapter? = null): Boolean {
        return getChapterState(chapterId, manga, chapter).state == ChapterTranslationState.State.TRANSLATED
    }

    /**
     * Check if a chapter has translated images stored persistently.
     */
    fun hasStoredTranslation(manga: Manga, chapter: Chapter): Boolean {
        val source = Injekt.get<SourceManager>().getOrStub(manga.source)
        return translationStorage.isChapterTranslated(
            source = source,
            mangaTitle = manga.title,
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
        )
    }

    /**
     * Get the translated page file for a specific page from persistent storage.
     */
    fun getTranslatedPageFile(manga: Manga, chapter: Chapter, pageIndex: Int): UniFile? {
        val source = Injekt.get<SourceManager>().getOrStub(manga.source)
        val result = translationStorage.getTranslatedPageFile(
            source = source,
            mangaTitle = manga.title,
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            pageIndex = pageIndex,
        )
        return result
    }

    private suspend fun translateChapter(manga: Manga, chapter: Chapter) {
        val chapterId = chapter.id
        val serverUrl = koharuPreferences.koharuServerUrl().get()
        val model = koharuPreferences.koharuLlmModel().get()
        val language = koharuPreferences.koharuTargetLanguage().get()
        val paged = koharuPreferences.koharuPaged().get()
        val pipelineTimeoutMs = koharuPreferences.koharuPipelineTimeoutMs().get()

        var translatedCount = 0
        var pages: List<ReaderPage> = emptyList()

        try {
            withIOContext {
                // Get the manga and source to locate downloaded files
                val source = Injekt.get<SourceManager>().getOrStub(manga.source)

                pages = if (source.isLocal()) {
                    // For Local source, load pages directly from local files
                    getPagesFromLocalSource(manga, chapter)
                } else {
                    val chapterPath = downloadProvider.findChapterDir(
                        chapter.name,
                        chapter.scanlator,
                        chapter.url,
                        manga.ogTitle,
                        source,
                    )

                    if (chapterPath?.isFile == true) {
                        getPagesFromArchive(chapterPath)
                    } else {
                        getPagesFromDirectory(source, manga, chapter)
                    }
                }

                // Check if all pages are already translated in storage
                val allAlreadyTranslated = pages.all { page ->
                    val existingFile = translationStorage.getTranslatedPageFile(
                        source = source,
                        mangaTitle = manga.title,
                        chapterName = chapter.name,
                        chapterScanlator = chapter.scanlator,
                        pageIndex = page.index,
                    )
                    existingFile != null && existingFile.exists()
                }

                if (allAlreadyTranslated) {
                    translatedCount = pages.size
                    updateProgress(chapterId, translatedCount, pages.size)
                    notifier.onProgressChange(manga.title, chapter.name, translatedCount, pages.size)
                } else {
                    // Build page data for all pages (Koharu project is per-chapter)
                    val allPageData = pages.map { page ->
                        ChapterPageData(
                            index = page.index,
                            name = "page_${page.index}.png",
                            stream = page.stream ?: throw Exception("Page ${page.index + 1} has no stream"),
                        )
                    }

                    // Translate entire chapter via Koharu
                    val translatedPages = koharuClient.translateChapter(
                        serverUrl = serverUrl,
                        chapterId = chapterId,
                        pages = allPageData,
                        modelId = model,
                        targetLanguage = language,
                        paged = paged,
                        timeoutMs = pipelineTimeoutMs
                    )

                    // Save translated pages to persistent storage
                    for ((index, bytes) in translatedPages) {
                        val tempFile = File.createTempFile("translated_${index}_", ".png")
                        try {
                            tempFile.writeBytes(bytes)
                            translationStorage.saveTranslatedPage(
                                source = source,
                                mangaTitle = manga.title,
                                chapterName = chapter.name,
                                chapterScanlator = chapter.scanlator,
                                pageIndex = index,
                                imageFile = tempFile,
                            )
                            translatedCount++
                            updateProgress(chapterId, translatedCount, pages.size)
                            notifier.onProgressChange(manga.title, chapter.name, translatedCount, pages.size)
                        } finally {
                            tempFile.delete()
                        }
                    }
                }
            }

            // Mark as completed
            updateChapterState(
                ChapterTranslationState(
                    chapterId = chapterId,
                    state = ChapterTranslationState.State.TRANSLATED,
                    progress = 100,
                    totalPages = pages.size,
                    translatedPages = pages.size,
                ),
            )
            notifier.dismissProgress()
        } catch (e: CancellationException) {
            logcat { "Translation cancelled for chapter $chapterId" }
            notifier.dismissProgress()
            koharuClient.cancelCurrentOperation(serverUrl)
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Translation failed for chapter $chapterId: ${e.message}" }
            updateChapterState(
                ChapterTranslationState(
                    chapterId = chapterId,
                    state = ChapterTranslationState.State.ERROR,
                    progress = if (pages.isNotEmpty()) (translatedCount * 100) / pages.size else 0,
                    totalPages = pages.size,
                    translatedPages = translatedCount,
                ),
            )
            notifier.onError(
                mangaTitle = manga.title,
                chapterName = chapter.name,
                error = e.message,
            )
        }
    }

    private suspend fun getPagesFromArchive(file: UniFile): List<ReaderPage> {
        val loader = ArchivePageLoader(file.archiveReader(context)).also { archivePageLoader = it }
        return loader.getPages()
    }

    private fun getPagesFromDirectory(
        source: Source,
        manga: Manga,
        chapter: Chapter,
    ): List<ReaderPage> {
        val pages = downloadManager.buildPageList(source, manga, chapter)
        return pages.map { page ->
            ReaderPage(page.index, page.url, page.imageUrl) {
                context.contentResolver.openInputStream(page.uri ?: Uri.EMPTY)!!
            }.apply {
                status = Page.State.Ready
            }
        }
    }

    /**
     * Load pages from Local source chapter files.
     */
    private suspend fun getPagesFromLocalSource(manga: Manga, chapter: Chapter): List<ReaderPage> {
        val storageManager = Injekt.get<StorageManager>()
        val localSourceDir = storageManager.getLocalSourceDirectory()
            ?: throw Exception("Local source directory not configured")

        // chapter.url is like "MangaName/Chapter_01.cbz" or "MangaName/Chapter_01/"
        val parts = chapter.url.split('/', limit = 2)
        if (parts.size < 2) throw Exception("Invalid chapter URL: ${chapter.url}")

        val mangaDir = localSourceDir.findFile(parts[0])
            ?: throw Exception("Manga directory not found: ${parts[0]}")
        val chapterFile = mangaDir.findFile(parts[1])
            ?: throw Exception("Chapter file not found: ${parts[1]}")

        return if (chapterFile.isFile) {
            getPagesFromArchive(chapterFile)
        } else {
            getPagesFromLocalDirectory(chapterFile)
        }
    }

    /**
     * Load pages from a local directory of image files.
     */
    private fun getPagesFromLocalDirectory(dir: UniFile): List<ReaderPage> {
        val imageFiles = dir.listFiles()
            ?.filter { it.isFile && isImageFile(it.name ?: "") }
            ?.sortedBy { it.name }
            ?: emptyList()

        return imageFiles.mapIndexed { index, file ->
            ReaderPage(index, url = "", imageUrl = null) {
                file.openInputStream()
            }.apply {
                status = Page.State.Ready
            }
        }
    }

    /**
     * Check if a filename is an image file.
     */
    private fun isImageFile(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }

    private fun updateProgress(chapterId: Long, translatedPages: Int, totalPages: Int) {
        val progress = if (totalPages > 0) (translatedPages * 100) / totalPages else 0
        updateChapterState(
            ChapterTranslationState(
                chapterId = chapterId,
                state = ChapterTranslationState.State.TRANSLATING,
                progress = progress,
                totalPages = totalPages,
                translatedPages = translatedPages,
            ),
        )
    }

    private fun updateChapterState(state: ChapterTranslationState) {
        _chapterTranslationStates.value += (state.chapterId to state)
    }

    /**
     * Update the state for a chapter based on persistent storage.
     * Called when checking if translation files exist on disk.
     */
    fun refreshChapterStateFromStorage(manga: Manga, chapter: Chapter) {
        val chapterId = chapter.id ?: return
        val runtimeState = _chapterTranslationStates.value[chapterId]
        // Don't override running states
        if (runtimeState != null && runtimeState.state != ChapterTranslationState.State.NOT_TRANSLATED) {
            return
        }

        val source = Injekt.get<SourceManager>().getOrStub(manga.source)
        val isTranslated = translationStorage.isChapterTranslated(
            source = source,
            mangaTitle = manga.title,
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
        )
        if (isTranslated) {
            val totalPages = translationStorage.getTranslatedPageCount(
                source = source,
                mangaTitle = manga.title,
                chapterName = chapter.name,
                chapterScanlator = chapter.scanlator,
            )
            updateChapterState(
                ChapterTranslationState(
                    chapterId = chapterId,
                    state = ChapterTranslationState.State.TRANSLATED,
                    progress = 100,
                    totalPages = totalPages,
                    translatedPages = totalPages,
                ),
            )
        }
    }

    fun destroy() {
        scope.launch {
            queueMutex.withLock {
                currentTranslationJob?.cancel()
                translationQueue.clear()
                isTranslating = false
                notifier.dismissProgress()
            }
        }
        scope.cancel()
    }
}
