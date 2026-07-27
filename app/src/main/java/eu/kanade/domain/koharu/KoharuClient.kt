package eu.kanade.domain.koharu

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Client for Koharu manga translation service API.
 * Handles all communication with the self-hosted Koharu server.
 */
class KoharuClient(
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val json: Json = Injekt.get(),
) {

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
    data class ProjectsResponse(
        val projects: List<Project> = emptyList(),
    )

    @Serializable
    data class Project(
        val id: String,
        val name: String,
        val updatedAtMs: Long = 0,
    )

    @Serializable
    data class SceneResponse(
        val scene: Scene? = null,
    )

    @Serializable
    data class Scene(
        val pages: Map<String, PageData>? = null,
    )

    @Serializable
    data class PageData(
        val id: String,
        val name: String,
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
        val defaultFont: String? = null,
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

    @Serializable
    data class ExportRequest(
        val format: String,
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
     * Check if a project exists by its ID.
     * @param serverUrl The base URL of the Koharu server
     * @param projectId The project ID to check
     * @return True if the project exists, false otherwise
     */
    suspend fun projectExists(serverUrl: String, projectId: String): Boolean = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/projects"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to list projects: ${response.code}")
            }
            val body = response.body.string()
            val projects = json.decodeFromString<ProjectsResponse>(body)
            projects.projects.any { it.id == projectId }
        }
    }

    /**
     * Load an existing project.
     * @param serverUrl The base URL of the Koharu server
     * @param projectId The project ID to load
     */
    suspend fun loadProject(serverUrl: String, projectId: String) = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/projects/current"
        val body = """{"id":"$projectId"}"""
        val request = Request.Builder()
            .url(url)
            .put(body.toRequestBody("application/json".toMediaType()))
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to load project: ${response.code}")
            }
        }
    }

    /**
     * Check if the current project has any pages.
     * @param serverUrl The base URL of the Koharu server
     * @return True if the project has pages, false otherwise
     */
    suspend fun projectHasPages(serverUrl: String): Boolean = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/scene.json"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@use false
            }
            val body = response.body.string()
            val scene = json.decodeFromString<SceneResponse>(body)
            scene.scene?.pages?.isNotEmpty() == true
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
     * Add a page image to the current project.
     * @param serverUrl The base URL of the Koharu server
     * @param imageStream The image stream to upload
     * @return List of page IDs that were added
     */
    suspend fun addPage(serverUrl: String, imageStream: () -> InputStream): List<String> = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/pages"
        val imageBytes = imageStream().readBytes()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "page",
                "page.png",
                imageBytes.toRequestBody("image/png".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to add page: ${response.code}")
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
            delay(500)
        }
        false
    }

    /**
     * Run the translation pipeline.
     * @param serverUrl The base URL of the Koharu server
     * @return The operation ID
     */
    suspend fun runPipeline(serverUrl: String): String = withIOContext {
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
            defaultFont = "CCMeanwhile-Regular",
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
        timeoutMs: Long = 300000,
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
            delay(1000)
        }
        false
    }

    /**
     * Export the translated image from the current project.
     * @param serverUrl The base URL of the Koharu server
     * @param outputFile The file to save the exported image to
     * @return True if export succeeded, false otherwise
     */
    suspend fun exportTranslatedImage(serverUrl: String, outputFile: File): Boolean = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/api/v1/projects/current/export"
        val body = """{"format":"rendered"}"""
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (response.code == 400) {
                // Retry after 1 second
                delay(1000)
                return@withIOContext exportTranslatedImage(serverUrl, outputFile)
            }
            if (!response.isSuccessful) {
                logcat { "Failed to export image: ${response.code}" }
                return@withIOContext false
            }
            response.body.byteStream().use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        }
    }

    /**
     * Translate a single page image.
     * This is the main method that orchestrates the entire translation process.
     * @param serverUrl The base URL of the Koharu server
     * @param chapterId The chapter ID
     * @param pageIndex The page index
     * @param imageStream The image stream to translate
     * @param outputFile The file to save the translated image to
     * @param modelId The LLM model ID to use
     * @return True if translation succeeded, false otherwise
     */
    suspend fun translatePage(
        serverUrl: String,
        chapterId: Long,
        pageIndex: Int,
        imageStream: () -> InputStream,
        outputFile: File,
        modelId: String,
    ): Boolean = withIOContext {
        val projectId = "$chapterId-$pageIndex"

        try {
            // Step 0: Check if project exists
            val exists = projectExists(serverUrl, projectId)
            if (exists) {
                // Load existing project
                loadProject(serverUrl, projectId)

                // Check if it has pages
                val hasPages = projectHasPages(serverUrl)
                if (!hasPages) {
                    // No pages, add the page
                    addPage(serverUrl, imageStream)
                }
                // If has pages, skip to LLM loading
            } else {
                // Step 1: Create new project
                createProject(serverUrl, projectId)
                // Project is automatically loaded

                // Step 2: Add the page
                addPage(serverUrl, imageStream)
            }

            // Step 3: Load LLM
            val llmState = getLlmState(serverUrl)
            if (llmState.target?.modelId != modelId) {
                loadLlm(serverUrl, modelId)
                if (!waitForLlmReady(serverUrl)) {
                    logcat { "LLM failed to load" }
                    return@withIOContext false
                }
            }

            // Step 4: Run pipeline
            val operationId = runPipeline(serverUrl)

            // Step 5: Wait for completion
            if (!waitForPipelineCompletion(serverUrl, operationId)) {
                logcat { "Pipeline failed or timed out" }
                return@withIOContext false
            }

            // Step 6: Export translated image
            if (!exportTranslatedImage(serverUrl, outputFile)) {
                logcat { "Failed to export translated image" }
                return@withIOContext false
            }

            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Translation failed" }
            false
        }
    }
}
