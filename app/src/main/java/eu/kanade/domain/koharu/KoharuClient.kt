package eu.kanade.domain.koharu

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.io.InputStream
import kotlin.time.Duration.Companion.milliseconds

/**
 * Client for Koharu manga translation service API.
 * Handles all communication with the self-hosted Koharu server.
 */
class KoharuClient(
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val json: Json = Injekt.get(),
) {

    @Volatile
    private var currentOperationId: String? = null

    /**
     * Data classes for API responses
     */
    @Serializable
    data class LlmCatalogResponse(
        val localModels: List<LocalModel> = emptyList(),
    )

    @Serializable
    data class LocalModel(
        val name: String,
        val languages: List<String> = emptyList(),
    )

    @Serializable
    data class SceneResponse(
        val scene: SceneData? = null,
    )

    @Serializable
    data class SceneData(
        val pages: Map<String, ScenePage> = emptyMap(),
    )

    @Serializable
    data class ScenePage(
        val id: String,
        val name: String,
        val nodes: Map<String, SceneNode> = emptyMap(),
    )

    @Serializable
    data class SceneNode(
        val kind: NodeKind,
    )

    @Serializable
    data class NodeKind(
        val image: ImageNodeData? = null,
    )

    @Serializable
    data class ImageNodeData(
        val role: String,
    )

    @Serializable
    data class LlmState(
        val status: String,
        val target: LlmTarget? = null,
    )

    @Serializable
    data class LlmTarget(
        val kind: String,
        val modelId: String,
        val providerId: String? = null,
    )

    @Serializable
    data class CreateProjectResponse(
        val name: String,
    )

    @Serializable
    data class AddPagesResponse(
        val pages: List<String>,
    )

    @Serializable
    data class PipelineRequest(
        val steps: List<String>,
        val targetLanguage: String,
        val paged: Boolean = false,
        val defaultFont: String? = null,
        val pages: List<String>? = null,
    )

    @Serializable
    data class PipelineResponse(
        val operationId: String,
    )

    @Serializable
    data class OperationsResponse(
        val operations: List<Operation> = emptyList(),
    )

    @Serializable
    data class Operation(
        val id: String,
        val kind: String,
        val status: String,
        val error: String? = null,
    )

    /**
     * Get the list of available LLM models from Koharu.
     * @param serverUrl The base URL of the Koharu server
     * @return List of available models with their supported languages
     */
    suspend fun getLlmCatalog(serverUrl: String): List<LocalModel> = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/llm/catalog"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to get LLM catalog: ${response.code}")
            }
            val body = response.body.string()
            val catalog = json.decodeFromString<LlmCatalogResponse>(body)
            catalog.localModels
        }
    }

    /**
     * Load an existing project.
     * @param serverUrl The base URL of the Koharu server
     * @param projectId The project ID to load
     * @return True if the project was loaded successfully, false if it doesn't exist
     */
    suspend fun loadProject(serverUrl: String, projectId: String): Boolean = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/projects/current"
        val body = """{"id":"$projectId"}"""
        val request = Request.Builder()
            .url(url)
            .put(body.toRequestBody("application/json".toMediaType()))
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }

    /**
     * Create a new project.
     * @param serverUrl The base URL of the Koharu server
     * @param projectName The name for the new project
     * @return The created project
     */
    suspend fun createProject(serverUrl: String, projectName: String): CreateProjectResponse = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/projects"
        val body = """{"name":"$projectName"}"""
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to create project: ${response.code}")
            }
            val responseBody = response.body.string()
            json.decodeFromString<CreateProjectResponse>(responseBody)
        }
    }

    /**
     * Add multiple page images to the current project in a single multipart request.
     * @param serverUrl The base URL of the Koharu server
     * @param pages List of pairs (filename, image bytes)
     * @return List of page IDs that were added, in the same order as input
     */
    suspend fun addPages(serverUrl: String, pages: List<Pair<String, ByteArray>>): List<String> = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/pages"
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                for ((filename, bytes) in pages) {
                    addFormDataPart(
                        "page",
                        filename,
                        bytes.toRequestBody("image/png".toMediaType()),
                    )
                }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to add pages: ${response.code}")
            }
            val body = response.body.string()
            val addPagesResponse = json.decodeFromString<AddPagesResponse>(body)
            addPagesResponse.pages
        }
    }

    /**
     * Get the current LLM state.
     * @param serverUrl The base URL of the Koharu server
     * @return The current LLM state
     */
    suspend fun getLlmState(serverUrl: String): LlmState = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/llm/current"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to get LLM state: ${response.code}")
            }
            val body = response.body.string()
            json.decodeFromString<LlmState>(body)
        }
    }

    /**
     * Load an LLM model.
     * @param serverUrl The base URL of the Koharu server
     * @param modelId The model ID to load
     */
    suspend fun loadLlm(serverUrl: String, modelId: String) = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/llm/current"
        val body = """{"target":{"kind":"local","modelId":"$modelId"}}"""
        val request = Request.Builder()
            .url(url)
            .put(body.toRequestBody("application/json".toMediaType()))
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to load LLM: ${response.code}")
            }
        }
    }

    /**
     * Wait for the LLM to be ready.
     * @param serverUrl The base URL of the Koharu server
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return True if the LLM is ready, false if timeout
     */
    suspend fun waitForLlmReady(serverUrl: String, timeoutMs: Long = 60000): Boolean = withIOContext {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val state = getLlmState(serverUrl)
            if (state.status == "ready") {
                return@withIOContext true
            }
            if (state.status == "error") {
                logcat { "LLM error: ${state.target?.modelId}" }
                return@withIOContext false
            }
            delay(500.milliseconds)
        }
        false
    }

    /**
     * Run the translation pipeline.
     * @param serverUrl The base URL of the Koharu server
     * @param targetLanguage The target language for translation
     * @param pageIds Optional list of page IDs to run the pipeline for. If null, runs for all pages.
     * @return The operation ID
     */
    suspend fun runPipeline(
        serverUrl: String,
        targetLanguage: String,
        paged: Boolean = false,
        pageIds: List<String>? = null,
    ): String = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/pipelines"
        val pipelineRequest = PipelineRequest(
            steps = listOf(
                "pp-doclayout-v3",
                "yuzumarker-font-detection",
                "comic-text-detector-seg",
                "speech-bubble-segmentation",
                "paddle-ocr-vl-1.6",
                "llm",
                "lama-manga",
                "koharu-renderer",
            ),
            targetLanguage = targetLanguage,
            paged = paged,
            defaultFont = "CCMeanwhile-Regular", // TODO: Allow to customize the font
            pages = pageIds,
        )
        val body = json.encodeToString(PipelineRequest.serializer(), pipelineRequest)
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to run pipeline: ${response.code}")
            }
            val responseBody = response.body.string()
            val pipelineResponse = json.decodeFromString<PipelineResponse>(responseBody)
            pipelineResponse.operationId
        }
    }

    /**
     * Get the status of an operation.
     * @param serverUrl The base URL of the Koharu server
     * @param operationId The operation ID to check
     * @return The operation status
     */
    suspend fun getOperationStatus(serverUrl: String, operationId: String): Operation = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/operations"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to get operations: ${response.code}")
            }
            val body = response.body.string()
            val operations = json.decodeFromString<OperationsResponse>(body)
            operations.operations.find { it.id == operationId }
                ?: throw IOException("Operation not found: $operationId")
        }
    }

    /**
     * Wait for a pipeline operation to complete.
     * @param serverUrl The base URL of the Koharu server
     * @param operationId The operation ID to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return True if completed successfully, false if failed or timeout
     */
    suspend fun waitForPipelineCompletion(
        serverUrl: String,
        operationId: String,
        timeoutMs: Long = 1800000,
    ): Boolean = withIOContext {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val operation = getOperationStatus(serverUrl, operationId)
            when (operation.status) {
                "completed" -> return@withIOContext true
                "completed_with_errors" -> {
                    logcat { "Pipeline completed with errors: ${operation.error}" }
                    return@withIOContext false
                }

                "failed" -> {
                    logcat { "Pipeline failed: ${operation.error}" }
                    return@withIOContext false
                }
            }
            delay(1000.milliseconds)
        }
        false
    }

    /**
     * Cancel a running operation.
     * @param serverUrl The base URL of the Koharu server
     * @param operationId The operation ID to cancel
     */
    suspend fun cancelOperation(serverUrl: String, operationId: String) = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/operations/$operationId"
        val request = Request.Builder()
            .url(url)
            .delete()
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logcat { "Failed to cancel operation: ${response.code}" }
            }
        }
    }

    /**
     * Cancel the currently running operation, if any.
     * Uses NonCancellable context to ensure the cancellation request is sent
     * even when the calling coroutine is already cancelled.
     * @param serverUrl The base URL of the Koharu server
     */
    suspend fun cancelCurrentOperation(serverUrl: String) {
        val operationId = currentOperationId ?: return
        withContext(NonCancellable) {
            cancelOperation(serverUrl, operationId)
        }
    }

    /**
     * Export translated images from the current project.
     * Handles both single-image (direct image data) and multi-page (ZIP) responses.
     * @param serverUrl The base URL of the Koharu server
     * @param expectedPageIds The page IDs we expect in the export, used for single-image fallback
     * @return Map of pageId to image bytes
     */
    suspend fun exportTranslatedPages(
        serverUrl: String,
        expectedPageIds: Set<String>,
    ): Map<String, ByteArray> = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/projects/current/export"
        val body = """{"format":"rendered"}"""
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to export project: ${response.code}")
            }

            val contentType = response.header("content-type") ?: ""
            val responseBytes = response.body.bytes()

            if (contentType.contains("zip") || contentType.contains("application/zip")) {
                // Multi-page response: ZIP file
                parseZipExport(responseBytes)
            } else {
                // Single image response
                val pageId = expectedPageIds.firstOrNull()
                    ?: throw IOException("No expected page IDs for single-image export")
                mapOf(pageId to responseBytes)
            }
        }
    }

    /**
     * Parse a ZIP export response and extract page images.
     * ZIP entries have format: page-{pageNumber}-{pageId}.png
     */
    private fun parseZipExport(zipBytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    // Format: page-{pageNumber}-{pageId}.png
                    val nameWithoutExt = name.removeSuffix(".png")
                    val afterPrefix = nameWithoutExt.removePrefix("page-")
                    val dashIndex = afterPrefix.indexOf('-')
                    if (dashIndex > 0) {
                        val pageId = afterPrefix.substring(dashIndex + 1)
                        result[pageId] = zis.readBytes()
                    }
                }
                entry = zis.nextEntry
            }
        }
        return result
    }

    /**
     * Ensure the specified LLM model is loaded and ready.
     * Loads the model if it's not currently loaded.
     */
    private suspend fun ensureLlmLoaded(serverUrl: String, modelId: String) {
        val llmState = getLlmState(serverUrl)
        if (llmState.status != "ready" || llmState.target?.modelId != modelId) {
            loadLlm(serverUrl, modelId)
            if (!waitForLlmReady(serverUrl)) {
                throw IOException("LLM failed to load: $modelId")
            }
        }
    }

    /**
     * Get the current scene data.
     * @param serverUrl The base URL of the Koharu server
     * @return The scene data, or null if not available
     */
    private suspend fun getScene(serverUrl: String): SceneData? = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/scene.json"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@use null
            }
            val body = response.body.string()
            val sceneResponse = json.decodeFromString<SceneResponse>(body)
            sceneResponse.scene
        }
    }

    /**
     * Translate an entire chapter using a single Koharu project.
     * Creates or reuses a project named {chapterId}-{modelId}-{targetLanguage}.
     * @param serverUrl The base URL of the Koharu server
     * @param chapterId The chapter ID
     * @param pages List of page data (index, name, stream) for all pages in the chapter
     * @param modelId The LLM model ID to use
     * @param targetLanguage The target language for translation
     * @return Map of page index to translated image bytes
     */
    suspend fun translateChapter(
        serverUrl: String,
        chapterId: Long,
        pages: List<ChapterPageData>,
        modelId: String,
        targetLanguage: String,
        paged: Boolean,
        timeoutMs: Long,
    ): Map<Int, ByteArray> = withIOContext {
        val projectId = "$chapterId-$modelId-$targetLanguage"

        // pageId -> pageIndex mapping
        val pageIdToIndex = mutableMapOf<String, Int>()

        // Try to load existing project
        val projectLoaded = loadProject(serverUrl, projectId)

        if (projectLoaded) {
            // Get scene to check current state
            val scene = getScene(serverUrl)
            val scenePages = scene?.pages ?: emptyMap()

            val pagesNeedingPipeline = mutableListOf<String>()

            // Match our pages to scene pages by name
            for (pageData in pages) {
                val pageName = pageData.name
                val scenePage = scenePages.values.find { it.name == pageName }

                if (scenePage != null) {
                    pageIdToIndex[scenePage.id] = pageData.index
                    // Check if it has a rendered image node
                    val hasRendered = scenePage.nodes.values.any {
                        it.kind.image?.role == "rendered"
                    }
                    if (!hasRendered) {
                        pagesNeedingPipeline.add(scenePage.id)
                    }
                }
            }

            // Upload pages that are missing from the scene
            val missingPages = pages.filter { pageData ->
                scenePages.values.none { it.name == pageData.name }
            }

            if (missingPages.isNotEmpty()) {
                val newPageIds = addPages(serverUrl, missingPages.map { it.name to it.stream().readBytes() })
                for ((i, pageId) in newPageIds.withIndex()) {
                    pageIdToIndex[pageId] = missingPages[i].index
                    pagesNeedingPipeline.add(pageId)
                }
            }

            // Run pipeline for pages that need it
            if (pagesNeedingPipeline.isNotEmpty()) {
                ensureLlmLoaded(serverUrl, modelId)
                val operationId = runPipeline(serverUrl, targetLanguage, paged, pagesNeedingPipeline)
                currentOperationId = operationId
                try {
                    if (!waitForPipelineCompletion(serverUrl, operationId, timeoutMs)) {
                        throw IOException("Pipeline failed or timed out")
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        cancelOperation(serverUrl, operationId)
                    }
                    throw e
                } finally {
                    currentOperationId = null
                }
            }
        } else {
            // Create new project
            createProject(serverUrl, projectId)
            // Project is automatically loaded

            // Upload all pages
            val pageIds = addPages(serverUrl, pages.map { it.name to it.stream().readBytes() })
            for ((i, pageId) in pageIds.withIndex()) {
                pageIdToIndex[pageId] = pages[i].index
            }

            // Load LLM
            ensureLlmLoaded(serverUrl, modelId)

            // Run pipeline for all pages (no pageIds filter)
            val operationId = runPipeline(serverUrl, targetLanguage, paged)
            currentOperationId = operationId
            try {
                if (!waitForPipelineCompletion(serverUrl, operationId, timeoutMs)) {
                    throw IOException("Pipeline failed or timed out")
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    cancelOperation(serverUrl, operationId)
                }
                throw e
            } finally {
                currentOperationId = null
            }
        }

        // Export translated pages
        val exportedPages = exportTranslatedPages(serverUrl, pageIdToIndex.keys)

        // Map pageIds back to page indices
        val result = mutableMapOf<Int, ByteArray>()
        for ((pageId, bytes) in exportedPages) {
            val index = pageIdToIndex[pageId]
            if (index != null) {
                result[index] = bytes
            }
        }

        result
    }
}

data class ChapterPageData(
    val index: Int,
    val name: String,
    val stream: () -> InputStream,
)
