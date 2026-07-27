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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File

/**
 * Manager for pre-translating chapters before reading.
 * This allows users to queue entire chapters for translation.
 */
class TranslationPreFetchManager(
    private val translationManager: TranslationManager,
    private val koharuClient: KoharuClient,
    private val koharuPreferences: KoharuPreferences,
    private val translationCache: TranslationCache,
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
        val pages: List<Page>,
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
     * Start pre-translating a chapter. If another translation is in progress, it will be queued.
     */
    fun startTranslation(manga: Manga, chapter: Chapter, pages: List<Page>) {
        if (!translationManager.isConfigured()) {
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
                totalPages = pages.size,
                translatedPages = 0,
            ),
        )

        // Add to queue
        scope.launch {
            queueMutex.withLock {
                translationQueue.add(TranslationRequest(manga, chapter, pages))
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
                        totalPages = request.pages.size,
                        translatedPages = 0,
                    ),
                )

                currentTranslationJob = scope.launch {
                    try {
                        translateChapter(request.manga, request.chapter, request.pages)
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
     * Called when entering the reader to prevent conflicts with in-reader translation.
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
     * Delete translated chapter.
     */
    fun deleteTranslation(chapterId: Long) {
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
        // TODO: Delete cached translations for this chapter
    }

    /**
     * Get translation state for a chapter.
     */
    fun getChapterState(chapterId: Long): ChapterTranslationState {
        return _chapterTranslationStates.value[chapterId] ?: ChapterTranslationState(
            chapterId = chapterId,
            state = ChapterTranslationState.State.NOT_TRANSLATED,
            progress = 0,
            totalPages = 0,
            translatedPages = 0,
        )
    }

    /**
     * Check if a chapter is fully translated.
     */
    fun isChapterTranslated(chapterId: Long): Boolean {
        return _chapterTranslationStates.value[chapterId]?.state == ChapterTranslationState.State.TRANSLATED
    }

    private suspend fun translateChapter(manga: Manga, chapter: Chapter, pages: List<Page>) {
        val chapterId = chapter.id ?: return
        val serverUrl = koharuPreferences.koharuServerUrl().get()
        val model = koharuPreferences.koharuLlmModel().get()
        val language = koharuPreferences.koharuTargetLanguage().get()

        var translatedCount = 0

        try {
            withIOContext {
                // Get the manga and source to locate downloaded files
                val source = Injekt.get<SourceManager>().getOrStub(manga.source)

                val chapterPath = downloadProvider.findChapterDir(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.ogTitle,
                    source,
                )

                val pages = if (chapterPath?.isFile == true) {
                    getPagesFromArchive(chapterPath)
                } else {
                    getPagesFromDirectory(source, manga, chapter)
                }

                for ((index, page) in pages.withIndex()) {
                    // Check if already cached
                    val cached = translationCache.getCachedTranslation(
                        chapterId = chapterId,
                        pageIndex = index,
                        modelId = model,
                        targetLanguage = language,
                    )

                    if (cached != null) {
                        translatedCount++
                        updateProgress(chapterId, translatedCount, pages.size)
                        notifier.onProgressChange(manga.title, chapter.name, translatedCount, pages.size)
                        continue
                    }

                    // Get the actual image file from the downloaded chapter
                    val imageStream = page.stream ?: throw Exception("Page ${index + 1} has no stream")

                    val outputFile = File.createTempFile("translated_${index}_", ".png")

                    val success = koharuClient.translatePage(
                        serverUrl = serverUrl,
                        chapterId = chapterId,
                        pageIndex = index,
                        imageStream = imageStream,
                        outputFile = outputFile,
                        modelId = model,
                    )

                    if (success && outputFile.exists()) {
                        // Cache the translated image
                        translationCache.cacheTranslation(
                            chapterId = chapterId,
                            pageIndex = index,
                            modelId = model,
                            targetLanguage = language,
                            imageFile = outputFile,
                        )
                        translatedCount++
                        updateProgress(chapterId, translatedCount, pages.size)
                        notifier.onProgressChange(manga.title, chapter.name, translatedCount, pages.size)
                    } else {
                        throw Exception("Translation failed for page ${index + 1}")
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
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Translation failed for chapter $chapterId: ${e.message}" }
            updateChapterState(
                ChapterTranslationState(
                    chapterId = chapterId,
                    state = ChapterTranslationState.State.ERROR,
                    progress = (translatedCount * 100) / pages.size,
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
