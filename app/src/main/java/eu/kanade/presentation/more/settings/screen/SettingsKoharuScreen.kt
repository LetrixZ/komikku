package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.koharu.KoharuClient
import eu.kanade.domain.koharu.KoharuPreferences
import eu.kanade.domain.koharu.TranslationCache
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsKoharuScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = SettingsKoharuScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_koharu_translation

    @Composable
    override fun RowScope.AppBarAction() {
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val koharuPreferences = remember { Injekt.get<KoharuPreferences>() }
        val koharuClient = remember { Injekt.get<KoharuClient>() }
        val translationCache = remember { Injekt.get<TranslationCache>() }
        val scope = rememberCoroutineScope()

        val serverUrlPref = koharuPreferences.koharuServerUrl()
        val llmModelPref = koharuPreferences.koharuLlmModel()
        val targetLanguagePref = koharuPreferences.koharuTargetLanguage()

        val serverUrl by serverUrlPref.changes().collectAsState(initial = serverUrlPref.get())
        val llmModel by llmModelPref.changes().collectAsState(initial = llmModelPref.get())

        var isLoadingModels by remember { mutableStateOf(false) }
        var availableModels by remember { mutableStateOf<List<KoharuClient.LocalModel>>(emptyList()) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var showClearCacheDialog by remember { mutableStateOf(false) }
        var cacheSize by remember { mutableStateOf(translationCache.getCacheSizeFormatted()) }

        // Fetch models when entering the screen
        remember {
            if (serverUrl.isNotBlank()) {
                scope.launch {
                    isLoadingModels = true
                    try {
                        val models = koharuClient.getLlmCatalog(serverUrl)
                        availableModels = models
                        isLoadingModels = false
                    } catch (e: Exception) {
                        errorMessage = e.message
                        isLoadingModels = false
                    }
                }
            }
            null
        }

        // Build model entries
        val modelEntries = availableModels.associate { model ->
            model.name to model.name
        }.toImmutableMap()

        // Build language entries for the selected model
        val selectedModel = availableModels.find { it.name == llmModel }
        val languageEntries = selectedModel?.languages?.associate { lang ->
            lang to lang
        }?.toImmutableMap() ?: persistentMapOf()

        return persistentListOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_koharu_configuration),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = serverUrlPref,
                        title = stringResource(KMR.strings.pref_koharu_server_url),
                        subtitle = stringResource(KMR.strings.pref_koharu_server_url_summary),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = llmModelPref,
                        entries = modelEntries,
                        title = stringResource(KMR.strings.pref_koharu_llm_model),
                        subtitle = if (isLoadingModels) {
                            stringResource(KMR.strings.pref_koharu_fetching_models)
                        } else if (modelEntries.isEmpty()) {
                            errorMessage ?: stringResource(KMR.strings.koharu_no_models_found)
                        } else {
                            "%s"
                        },
                        enabled = modelEntries.isNotEmpty() && !isLoadingModels,
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = targetLanguagePref,
                        entries = languageEntries,
                        title = stringResource(KMR.strings.pref_koharu_target_language),
                        subtitle = if (languageEntries.isEmpty()) {
                            stringResource(KMR.strings.pref_koharu_select_model_first)
                        } else {
                            "%s"
                        },
                        enabled = languageEntries.isNotEmpty(),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KMR.strings.pref_koharu_clear_cache),
                        subtitle = stringResource(KMR.strings.pref_koharu_clear_cache_summary, cacheSize),
                        onClick = { showClearCacheDialog = true },
                    ),
                ),
            ),
        ).also {
            if (showClearCacheDialog) {
                ClearCacheDialog(
                    context = context,
                    translationCache = translationCache,
                    onDismiss = { showClearCacheDialog = false },
                    onCacheCleared = {
                        cacheSize = translationCache.getCacheSizeFormatted()
                    },
                )
            }
        }
    }
}

@Composable
private fun ClearCacheDialog(
    context: Context,
    translationCache: TranslationCache,
    onDismiss: () -> Unit,
    onCacheCleared: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val cacheClearedMessage = stringResource(KMR.strings.pref_koharu_cache_cleared)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(KMR.strings.pref_koharu_clear_cache_confirm_title))
        },
        text = {
            Text(stringResource(KMR.strings.pref_koharu_clear_cache_confirm_message))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        translationCache.clearCache()
                        onCacheCleared()
                        onDismiss()
                        android.widget.Toast.makeText(
                            context,
                            cacheClearedMessage,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            ) {
                Text(stringResource(tachiyomi.i18n.MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(tachiyomi.i18n.MR.strings.action_cancel))
            }
        },
    )
}
