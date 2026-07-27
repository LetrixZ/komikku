package eu.kanade.domain.koharu

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.InputStream
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manager for handling manga page translation via Koharu service.
 * Maintains a queue of pages to translate and processes them one at a time.
 */
class TranslationManager(
    private val context: Context,
    private val koharuClient: KoharuClient,
    private val koharuPreferences: KoharuPreferences,
    private val translationCache: TranslationCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val translationQueue = mutableListOf<TranslationRequest>()
    private val queueMutex = Mutex()
    private var isProcessing = false
    private var processingJob: Job? = null

    private val _translationState = MutableStateFlow<Map<Int, TranslationStatus>>(emptyMap())
    val translationState: StateFlow<Map<Int, TranslationStatus>> = _translationState.asStateFlow()

    private val _isTranslationEnabled = MutableStateFlow(false)
    val isTranslationEnabled: StateFlow<Boolean> = _isTranslationEnabled.asStateFlow()

    data class TranslationRequest(
        val chapterId: Long,
        val pageIndex: Int,
        val imageStream: () -> InputStream,
        val chapter: ReaderChapter,
    )

    sealed class TranslationStatus {
        data object Idle : TranslationStatus()
        data object Queued : TranslationStatus()
        data object Translating : TranslationStatus()
        data class Success(val translatedFile: File) : TranslationStatus()
        data class Error(val message: String) : TranslationStatus()
    }

    /**
     * Enable translation for the current reading session.
     */
    fun enableTranslation() {
        if (!isConfigured()) {
            logcat { "Cannot enable translation: Koharu is not configured" }
            return
        }
        _isTranslationEnabled.value = true
        startProcessing()
    }

    /**
     * Disable translation and clear the queue.
     */
    fun disableTranslation() {
        _isTranslationEnabled.value = false
        processingJob?.cancel()
        processingJob = null
        scope.launch {
            queueMutex.withLock {
                translationQueue.clear()
                _translationState.value = emptyMap()
            }
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
     * Queue a page for translation.
     */
    fun queuePage(chapter: ReaderChapter, page: ReaderPage, imageStream: () -> InputStream) {
        logcat { "Queue page, chapterId=${chapter.chapter.id} pageIndex=${page.index}" }
        if (!_isTranslationEnabled.value) return

        val chapterId = chapter.chapter.id ?: return
        val pageIndex = page.index

        // Check cache first
        val serverUrl = koharuPreferences.koharuServerUrl().get()
        val model = koharuPreferences.koharuLlmModel().get()
        val language = koharuPreferences.koharuTargetLanguage().get()

        val cached = translationCache.getCachedTranslation(chapterId, pageIndex, model, language)
        if (cached != null) {
            logcat { "Page $pageIndex found in cache" }
            scope.launch {
                updateTranslationStatus(pageIndex, TranslationStatus.Success(cached))
            }
            return
        }

        scope.launch {
            queueMutex.withLock {
                // Check if already queued
                if (translationQueue.any { it.pageIndex == pageIndex && it.chapterId == chapterId }) {
                    return@launch
                }

                translationQueue.add(TranslationRequest(chapterId, pageIndex, imageStream, chapter))
                updateTranslationStatusLocked(pageIndex, TranslationStatus.Queued)
                logcat { "Queued page $pageIndex for translation" }
            }
        }
    }

    /**
     * Start processing the translation queue.
     */
    private fun startProcessing() {
        if (processingJob?.isActive == true) return

        processingJob = scope.launch {
            isProcessing = true
            while (_isTranslationEnabled.value) {
                val request = queueMutex.withLock {
                    translationQueue.firstOrNull {
                        _translationState.value[it.pageIndex] is TranslationStatus.Queued
                    }
                } ?: run {
                    delay(500.milliseconds)
                    continue
                }

                translatePage(request)
            }
            isProcessing = false
        }
    }

    /**
     * Translate a single page.
     */
    private suspend fun translatePage(request: TranslationRequest) {
        logcat { "Translate page: chapterId=${request.chapterId} pageIndex=${request.pageIndex}" }
        val pageIndex = request.pageIndex
        updateTranslationStatus(pageIndex, TranslationStatus.Translating)

        try {
            val serverUrl = koharuPreferences.koharuServerUrl().get()
            val model = koharuPreferences.koharuLlmModel().get()
            val language = koharuPreferences.koharuTargetLanguage().get()

            // Create temporary output file
            val outputFile = File(context.cacheDir, "koharu_output_${request.chapterId}_$pageIndex.png")

            withIOContext {
                val success = koharuClient.translatePage(
                    serverUrl = serverUrl,
                    chapterId = request.chapterId,
                    pageIndex = pageIndex,
                    imageStream = request.imageStream,
                    outputFile = outputFile,
                    modelId = model,
                )

                if (success && outputFile.exists()) {
                    // Cache the result
                    val cachedFile = translationCache.cacheTranslation(
                        chapterId = request.chapterId,
                        pageIndex = pageIndex,
                        modelId = model,
                        targetLanguage = language,
                        imageFile = outputFile,
                    )

                    updateTranslationStatus(pageIndex, TranslationStatus.Success(cachedFile))
                    logcat { "Successfully translated page $pageIndex" }

                    // Remove from queue
                    queueMutex.withLock {
                        translationQueue.removeAll { it.pageIndex == pageIndex && it.chapterId == request.chapterId }
                    }
                } else {
                    updateTranslationStatus(pageIndex, TranslationStatus.Error("Translation failed"))
                    logcat { "Translation failed for page $pageIndex" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error translating page $pageIndex" }
            updateTranslationStatus(pageIndex, TranslationStatus.Error(e.message ?: "Unknown error"))
        }
    }

    /**
     * Update the translation status for a page.
     */
    private suspend fun updateTranslationStatus(pageIndex: Int, status: TranslationStatus) {
        queueMutex.withLock {
            updateTranslationStatusLocked(pageIndex, status)
        }
    }

    private fun updateTranslationStatusLocked(pageIndex: Int, status: TranslationStatus) {
        _translationState.value += (pageIndex to status)
    }

    /**
     * Get the translation status for a specific page.
     */
    fun getTranslationStatus(pageIndex: Int): TranslationStatus {
        return _translationState.value[pageIndex] ?: TranslationStatus.Idle
    }

    /**
     * Get the translated image file for a page, if available.
     */
    fun getTranslatedImage(pageIndex: Int): File? {
        val status = _translationState.value[pageIndex]
        return if (status is TranslationStatus.Success) status.translatedFile else null
    }

    fun onReaderClosed() {
        disableTranslation()
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        disableTranslation()
        scope.cancel()
    }
}
