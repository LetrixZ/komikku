package eu.kanade.domain.koharu

import tachiyomi.core.common.preference.PreferenceStore

class KoharuPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun koharuEnabled() = preferenceStore.getBoolean("pref_koharu_enabled", false)

    fun koharuServerUrl() = preferenceStore.getString("pref_koharu_server_url", "http://127.0.0.1:4000")

    fun koharuTranslationModel() = preferenceStore.getString("pref_koharu_translation_model", "")

    fun koharuModelQuantization() = preferenceStore.getString("pref_koharu_translation_model_quantiazation", "")

    fun koharuTargetLanguage() = preferenceStore.getString("pref_koharu_target_language", "")

    fun koharuPipelineTimeoutMs() = preferenceStore.getLong("pref_koharu_pipeline_timeout_ms", 600000)
}
