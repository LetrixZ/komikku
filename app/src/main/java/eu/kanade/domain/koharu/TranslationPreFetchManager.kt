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

    // Track translation state per chapter
    private val _chapterTranslationStates = MutableStateFlow<Map<Long, ChapterTranslationState>>(emptyMap())
    val chapterTranslationStates: StateFlow<Map<Long, ChapterTranslationState>> =
        _chapterTranslationStates.asStateFlow()

    private val translationJobs = mutableMapOf<Long, Job>()

    private var archivePageLoader: ArchivePageLoader? = null

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
     * Start pre-translating a chapter.
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

        // Start translation job
        val job = scope.launch {
            translateChapter(manga, chapter, pages)
        }
        translationJobs[chapterId] = job
    }

    /**
     * Cancel translation for a chapter.
     */
    fun cancelTranslation(chapterId: Long) {
        translationJobs[chapterId]?.cancel()
        translationJobs.remove(chapterId)
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

    /**
     * Delete translated chapter.
     */
    fun deleteTranslation(chapterId: Long) {
        cancelTranslation(chapterId)
        // TODO: Delete cached translations for this chapter
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

        updateChapterState(
            ChapterTranslationState(
                chapterId = chapterId,
                state = ChapterTranslationState.State.TRANSLATING,
                progress = 0,
                totalPages = pages.size,
                translatedPages = 0,
            ),
        )

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
        } catch (e: CancellationException) {
            logcat { "Translation cancelled for chapter $chapterId" }
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
        } finally {
            translationJobs.remove(chapterId)
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
        translationJobs.values.forEach { it.cancel() }
        translationJobs.clear()
        scope.cancel()
    }
}
