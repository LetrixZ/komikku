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
import okhttp3.RequestBody
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
    private var currentJobId: String? = null

    @Serializable
    data class Project(
        val name: String,
        val pages: List<Page>,
    )

    @Serializable
    data class Page(
        val id: String,
        val label: String,
        val translated: Boolean,
    )

    @Serializable
    data class TranslationModel(
        val provider: String,
        val model: String,
        val name: String,
        val quantizations: List<Quantization>,
    )

    @Serializable
    data class Quantization(
        val id: String,
    )

    @Serializable
    data class Language(
        val tag: String,
        val name: String,
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
        val operation: String,
        val pages: List<String>,
    )

    @Serializable
    data class PipelineResponse(
        val job: String,
    )

    @Serializable
    data class Job(
        val id: String,
        val state: String,
        val completed: Int,
        val total: Int,
        val error: String? = null,
    )

    /**
     * Get the list of available translation models from Koharu.
     * @param serverUrl The base URL of the Koharu server
     * @return List of available models with their supported quantizations
     */
    suspend fun getTranslationModels(serverUrl: String): List<TranslationModel> = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/v1/translation/models"
        val request = Request.Builder().url(url).get().build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to get translation models: ${response.code}")
            }
            val body = response.body.string()
            json.decodeFromString<List<TranslationModel>>(body)
        }
    }

    /**
     * Get the list of available target languages from Koharu.
     * @param serverUrl The base URL of the Koharu server
     * @return List of available target languages
     */
    suspend fun getTargetLanguages(serverUrl: String): List<Language> = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/v1/translation/languages"
        val request = Request.Builder().url(url).get().build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to get languages: ${response.code}")
            }
            val body = response.body.string()
            json.decodeFromString<List<Language>>(body)
        }
    }

    /**
     * Load an existing project.
     * @param serverUrl The base URL of the Koharu server
     * @param projectId The project ID to load
     * @return True if the project was loaded successfully, false if it doesn't exist
     */
    suspend fun loadProject(serverUrl: String, projectId: String): Project? = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/v1/projects/$projectId"
        val request = Request.Builder().url(url).get().build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val responseBody = response.body.string()
                json.decodeFromString<Project>(responseBody)
            } else {
                null
            }
        }
    }

    /**
     * Create a new project.
     * @param serverUrl The base URL of the Koharu server
     * @param projectName The name for the new project
     * @return The created project
     */
    suspend fun createProject(serverUrl: String, projectName: String): CreateProjectResponse = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/v1/projects"
        val body = """{"name":"$projectName"}"""
        val request = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()

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
    suspend fun addPages(serverUrl: String, projectId: String, pages: List<Pair<String, ByteArray>>): List<String> =
        withIOContext {
            val url = "${serverUrl.trimEnd('/')}/v1/projects/$projectId/images"
            val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                for ((filename, bytes) in pages) {
                    addFormDataPart(
                        "images",
                        filename,
                        bytes.toRequestBody("image/png".toMediaType()),
                    )
                }
            }.build()

            val request = Request.Builder().url(url).post(requestBody).build()

            networkHelper.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to add pages: ${response.code}")
                }
                val body = response.body.string()
                val project = json.decodeFromString<Project>(body)
                project.pages.map { it.id }
            }
        }

    /**
     * Run the translation pipeline.
     * @param serverUrl The base URL of the Koharu server
     * @param pageIds Optional list of page IDs to run the pipeline for. If null, runs for all pages.
     * @return The operation ID
     */
    suspend fun runPipeline(
        serverUrl: String,
        projectId: String,
        pageIds: List<String> = listOf(),
    ): String = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/v1/projects/$projectId/pipeline"
        val pipelineRequest = PipelineRequest(
            operation = "full",
            pages = pageIds,
        )
        val body = json.encodeToString(PipelineRequest.serializer(), pipelineRequest)
        val request = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to run pipeline: ${response.code}")
            }
            val responseBody = response.body.string()
            val pipelineResponse = json.decodeFromString<PipelineResponse>(responseBody)
            pipelineResponse.job
        }
    }

    /**
     * Get the status of a job.
     * @param serverUrl The base URL of the Koharu server
     * @param jobId The job ID to check
     * @return The operation status
     */
    suspend fun getJobStatus(serverUrl: String, jobId: String): Job = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/v1/jobs/$jobId"
        val request = Request.Builder().url(url).get().build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to get job: ${response.code}")
            }
            val body = response.body.string()
            json.decodeFromString<Job>(body)
        }
    }

    /**
     * Wait for a pipeline job to complete.
     * @param serverUrl The base URL of the Koharu server
     * @param jobId The job ID to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @param onProgress Optional callback invoked with the job progress (completed, total) on each poll
     * @return True if completed successfully, false if failed or timeout
     */
    suspend fun waitForPipelineCompletion(
        serverUrl: String,
        jobId: String,
        timeoutMs: Long = 1800000,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): Boolean = withIOContext {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val job = getJobStatus(serverUrl, jobId)
            onProgress?.invoke(job.completed, job.total)
            when (job.state) {
                "finished" -> return@withIOContext true

                "failed" -> {
                    logcat { "Pipeline failed: ${job.error}" }
                    return@withIOContext false
                }

                "stopped" -> {
                    logcat { "Pipeline stopped: ${job.error}" }
                    return@withIOContext false
                }
            }
            delay(1000.milliseconds)
        }
        false
    }

    /**
     * Cancel a running job.
     * @param serverUrl The base URL of the Koharu server
     * @param jobId The job ID to cancel
     */
    suspend fun stopJob(serverUrl: String, jobId: String) = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/v1/jobs/$jobId/stop"
        val request = Request.Builder().url(url).post(RequestBody.EMPTY).build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logcat { "Failed to cancel job: ${response.code}" }
            }
        }
    }

    /**
     * Cancel the currently running job, if any.
     * Uses NonCancellable context to ensure the cancellation request is sent
     * even when the calling coroutine is already canceled.
     * @param serverUrl The base URL of the Koharu server
     */
    suspend fun cancelCurrentJob(serverUrl: String) {
        val jobId = currentJobId ?: return
        withContext(NonCancellable) {
            stopJob(serverUrl, jobId)
        }
    }

    /**
     * Export translated images from the current project.
     * Handles multipage (ZIP) responses.
     * @param serverUrl The base URL of the Koharu server
     * @return Map of pageId to image bytes
     */
    suspend fun exportTranslatedPages(
        serverUrl: String,
        projectId: String,
    ): Map<String, ByteArray> = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/v1/projects/$projectId/export.zip"
        val request = Request.Builder().url(url).get().build()

        networkHelper.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to export project: ${response.code}")
            }

            val responseBytes = response.body.bytes()
            parseZipExport(responseBytes)
        }
    }

    /**
     * Parse a ZIP export response and extract page images.
     * ZIP entries have format: {index}_{originalName}.png (e.g. 0001_page-1.png)
     */
    private fun parseZipExport(zipBytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    // Format: {index}_{originalName}.png
                    val underscoreIndex = name.indexOf('_')
                    if (underscoreIndex > 0) {
                        val originalName = name.substring(underscoreIndex + 1)
                        result[originalName] = zis.readBytes()
                    }
                }
                entry = zis.nextEntry
            }
        }
        return result
    }

    private suspend fun setTranslationPreferences(
        serverUrl: String,
        modelId: String,
        modelQuantization: String,
        modelReasoning: Boolean,
        modelVision: Boolean,
        targetLanguage: String,
    ) = withIOContext {
        val url = "${serverUrl.trimEnd('/')}/v1/translation/preferences"
        val body =
            """{"model":{"provider":"local","model":"$modelId","quantization":"$modelQuantization","vision":$modelVision,"reasoning":$modelReasoning},"target_language":"$targetLanguage"}"""
        val request = Request.Builder().url(url).put(body.toRequestBody("application/json".toMediaType())).build()

        networkHelper.client.newCall(request).execute()
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
        modelQuantization: String,
        modelReasoning: Boolean,
        modelVision: Boolean,
        targetLanguage: String,
        timeoutMs: Long,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): Map<Int, ByteArray> = withIOContext {
        val projectId = listOfNotNull(
            chapterId,
            modelId,
            "reasoning".takeIf { modelReasoning },
            "vision".takeIf { modelVision },
            targetLanguage,
        ).joinToString("-")

        val pageLabelToIndex = mutableMapOf<String, Int>()

        val project = loadProject(serverUrl, projectId)

        if (project != null) {
            val pagesNeedingPipeline = mutableListOf<String>()

            for (pageData in pages) {
                val pageName = pageData.name
                val projectPage = project.pages.find { it.label == pageName }
                if (projectPage != null) {
                    pageLabelToIndex[projectPage.label] = pageData.index
                    if (!projectPage.translated) {
                        pagesNeedingPipeline.add(projectPage.id)
                    }
                }
            }

            val missingPages = pages.filter { pageData ->
                project.pages.none { it.label == pageData.name }
            }

            if (missingPages.isNotEmpty()) {
                val newPageIds = addPages(serverUrl, projectId, missingPages.map { it.name to it.stream().readBytes() })
                for ((i, pageId) in newPageIds.withIndex()) {
                    pageLabelToIndex[pageId] = missingPages[i].index
                    pagesNeedingPipeline.add(pageId)
                }
            }

            if (pagesNeedingPipeline.isNotEmpty()) {
                setTranslationPreferences(
                    serverUrl,
                    modelId,
                    modelQuantization,
                    modelReasoning,
                    modelVision,
                    targetLanguage,
                )

                val jobId = runPipeline(serverUrl, projectId, pagesNeedingPipeline)
                currentJobId = jobId
                try {
                    if (!waitForPipelineCompletion(serverUrl, jobId, timeoutMs, onProgress)) {
                        throw IOException("Pipeline failed or timed out")
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        stopJob(serverUrl, jobId)
                    }
                    throw e
                } finally {
                    currentJobId = null
                }
            }
        } else {
            createProject(serverUrl, projectId)

            val pageIds = addPages(serverUrl, projectId, pages.map { it.name to it.stream().readBytes() })
            for ((i, pageId) in pageIds.withIndex()) {
                pageLabelToIndex[pageId] = pages[i].index
            }

            setTranslationPreferences(
                serverUrl,
                modelId,
                modelQuantization,
                modelReasoning,
                modelVision,
                targetLanguage,
            )

            val operationId = runPipeline(serverUrl, projectId)
            currentJobId = operationId
            try {
                if (!waitForPipelineCompletion(serverUrl, operationId, timeoutMs, onProgress)) {
                    throw IOException("Pipeline failed or timed out")
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    stopJob(serverUrl, operationId)
                }
                throw e
            } finally {
                currentJobId = null
            }
        }

        val exportedPages = exportTranslatedPages(serverUrl, projectId)

        val result = mutableMapOf<Int, ByteArray>()
        for ((pageName, bytes) in exportedPages) {
            val index = pageLabelToIndex[pageName]
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
