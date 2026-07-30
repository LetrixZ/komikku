package eu.kanade.domain.koharu

import tachiyomi.core.common.preference.PreferenceStore

class KoharuPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun koharuEnabled() = preferenceStore.getBoolean("pref_koharu_enabled", false)

    fun koharuServerUrl() = preferenceStore.getString("pref_koharu_server_url", "http://127.0.0.1:4000")

    fun koharuLlmModel() = preferenceStore.getString("pref_koharu_llm_model", "")

    fun koharuTargetLanguage() = preferenceStore.getString("pref_koharu_target_language", "")

    fun koharuPaged() = preferenceStore.getBoolean("pref_koharu_paged", false)

    fun koharuPipelineTimeoutMs() = preferenceStore.getLong("pref_koharu_pipeline_timeout_ms", 600000)
}
