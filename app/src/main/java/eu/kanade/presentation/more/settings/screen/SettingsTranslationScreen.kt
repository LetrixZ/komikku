package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import eu.kanade.domain.koharu.KoharuClient
import eu.kanade.domain.koharu.KoharuClientV1
import eu.kanade.domain.koharu.KoharuPreferences
import eu.kanade.domain.koharu.TranslationStorage
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsTranslationScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = SettingsTranslationScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_koharu_translation

    @Composable
    override fun RowScope.AppBarAction() {
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current

        val koharuPreferences = remember { Injekt.get<KoharuPreferences>() }
        val koharuClient = remember { Injekt.get<KoharuClient>() }
        val koharuClientV1 = remember { Injekt.get<KoharuClientV1>() }
        val translationStorage = remember { Injekt.get<TranslationStorage>() }
        val scope = rememberCoroutineScope()

        val serverUrlPref = koharuPreferences.koharuServerUrl()
        val translationModelPref = koharuPreferences.koharuTranslationModel()
        val modelQuantizationPref = koharuPreferences.koharuModelQuantization()
        val modelReasoningPerf = koharuPreferences.koharuModelReasoning()
        val modelVisionPerf = koharuPreferences.koharuModelVision()
        val targetLanguagePref = koharuPreferences.koharuTargetLanguage()
        val useOldClientPref = koharuPreferences.koharuUseOldClient()
        val useOldClient by useOldClientPref.changes().collectAsState(initial = useOldClientPref.get())

        val serverUrl by serverUrlPref.changes().collectAsState(initial = serverUrlPref.get())
        val translationModel by translationModelPref.changes().collectAsState(initial = translationModelPref.get())
        val modelQuantization by modelQuantizationPref.changes().collectAsState(initial = modelQuantizationPref.get())
        val targetLanguage by targetLanguagePref.changes().collectAsState(initial = targetLanguagePref.get())

        var isLoadingModels by remember { mutableStateOf(false) }
        var availableModels by remember { mutableStateOf<List<KoharuClient.TranslationModel>>(emptyList()) }
        var modelsErrorMessage by remember { mutableStateOf<String?>(null) }

        var isLoadingV1Models by remember { mutableStateOf(false) }
        var availableV1Models by remember { mutableStateOf<List<KoharuClientV1.LocalModel>>(emptyList()) }
        var v1ModelsErrorMessage by remember { mutableStateOf<String?>(null) }

        var isLoadingLanguages by remember { mutableStateOf(false) }
        var availableLanguages by remember { mutableStateOf<List<KoharuClient.Language>>(emptyList()) }
        var languagesErrorMessage by remember { mutableStateOf<String?>(null) }

        var showClearCacheDialog by remember { mutableStateOf(false) }
        var storageSize by remember { mutableStateOf(translationStorage.getTotalSizeFormatted()) }

        remember(useOldClient) {
            if (serverUrl.isNotBlank()) {
                scope.launch {
                    if (useOldClient) {
                        isLoadingV1Models = true
                        try {
                            availableV1Models = koharuClientV1.getLlmCatalog(serverUrl)
                            isLoadingV1Models = false
                        } catch (e: Exception) {
                            v1ModelsErrorMessage = e.message
                            isLoadingV1Models = false
                        }
                    } else {
                        isLoadingModels = true
                        try {
                            availableModels = koharuClient.getTranslationModels(serverUrl)
                            isLoadingModels = false
                        } catch (e: Exception) {
                            modelsErrorMessage = e.message
                            isLoadingModels = false
                        }
                    }
                }
            }
            null
        }

        remember(useOldClient) {
            if (!useOldClient && serverUrl.isNotBlank()) {
                scope.launch {
                    isLoadingLanguages = true
                    try {
                        val languages = koharuClient.getTargetLanguages(serverUrl)
                        availableLanguages = languages
                        isLoadingLanguages = false
                    } catch (e: Exception) {
                        languagesErrorMessage = e.message
                        isLoadingLanguages = false
                    }
                }
            }
            null
        }

        val modelEntries = if (useOldClient) {
            availableV1Models.associate { it.name to it.name }.toImmutableMap()
        } else {
            availableModels.associate {
                it.model to it.name
            }.toImmutableMap()
        }

        val isLoadingModelsForMode = if (useOldClient) isLoadingV1Models else isLoadingModels
        val modelsErrorMessageForMode = if (useOldClient) v1ModelsErrorMessage else modelsErrorMessage

        val selectedModel = availableModels.find { it.model == translationModel }
        val selectedV1Model = availableV1Models.find { it.name == translationModel }
        val modelQuantizationEntries = selectedModel?.quantizations?.associate {
            it.id to it.id
        }?.toImmutableMap() ?: persistentMapOf()

        val languageEntries = if (useOldClient) {
            selectedV1Model?.languages?.associate { it to it }?.toImmutableMap() ?: persistentMapOf()
        } else {
            availableLanguages.associate {
                it.tag to it.name
            }.toImmutableMap()
        }

        val configItems = mutableListOf<Preference.PreferenceItem<out Any, out Any>>(
            Preference.PreferenceItem.SwitchPreference(
                preference = useOldClientPref,
                title = stringResource(KMR.strings.pref_koharu_use_old_client),
                subtitle = stringResource(KMR.strings.pref_koharu_use_old_client_summary),
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = serverUrlPref,
                title = stringResource(KMR.strings.pref_koharu_server_url),
                subtitle = if (serverUrl.isEmpty()) {
                    stringResource(KMR.strings.pref_koharu_server_url_summary)
                } else {
                    "%s"
                },
            ),
            Preference.PreferenceItem.ListPreference(
                preference = translationModelPref,
                entries = modelEntries,
                title = stringResource(KMR.strings.pref_koharu_translation_model),
                subtitle = if (isLoadingModelsForMode) {
                    stringResource(KMR.strings.pref_koharu_fetching_models)
                } else if (modelEntries.isEmpty()) {
                    modelsErrorMessageForMode ?: stringResource(KMR.strings.koharu_no_models_found)
                } else if (translationModel.isEmpty()) {
                    stringResource(KMR.strings.pref_koharu_select_model)
                } else {
                    "%s"
                },
                enabled = !isLoadingModelsForMode,
                onValueChanged = {
                    translationModelPref.set(it)
                    modelQuantizationPref.set("")
                    if (useOldClient) {
                        val newModel = availableV1Models.find { model -> model.name == it }
                        val currentLanguage = targetLanguagePref.get()
                        if (newModel == null || newModel.languages.none { language -> language == currentLanguage }) {
                            targetLanguagePref.set("")
                        }
                    }
                    true
                },
            ),
        )
        if (!useOldClient) {
            configItems.add(
                Preference.PreferenceItem.ListPreference(
                    preference = modelQuantizationPref,
                    entries = modelQuantizationEntries,
                    title = stringResource(KMR.strings.pref_koharu_translation_model_quantization),
                    subtitle = if (isLoadingModelsForMode) {
                        stringResource(KMR.strings.pref_koharu_fetching_models)
                    } else if (modelQuantizationEntries.isEmpty()) {
                        modelsErrorMessageForMode ?: stringResource(KMR.strings.koharu_no_model_quantizations_found)
                    } else if (modelQuantization.isEmpty()) {
                        stringResource(KMR.strings.pref_koharu_select_model_quantization)
                    } else {
                        "%s"
                    },
                    enabled = !isLoadingModelsForMode,
                ),
            )
            configItems.add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = modelReasoningPerf,
                    title = stringResource(KMR.strings.pref_koharu_model_reasoning),
                    subtitle = stringResource(KMR.strings.pref_koharu_model_reasoning_summary),
                ),
            )
            configItems.add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = modelVisionPerf,
                    title = stringResource(KMR.strings.pref_koharu_model_vision),
                    subtitle = stringResource(KMR.strings.pref_koharu_model_vision_summary),
                ),
            )
        }
        configItems.add(
            Preference.PreferenceItem.ListPreference(
                preference = targetLanguagePref,
                entries = languageEntries,
                title = stringResource(KMR.strings.pref_koharu_target_language),
                subtitle = if (useOldClient) {
                    when {
                        selectedV1Model == null -> stringResource(KMR.strings.pref_koharu_select_model)
                        languageEntries.isEmpty() -> languagesErrorMessage ?: stringResource(KMR.strings.koharu_no_languages_found)
                        targetLanguage.isEmpty() -> stringResource(KMR.strings.pref_koharu_select_target_language)
                        else -> "%s"
                    }
                } else if (isLoadingLanguages) {
                    stringResource(KMR.strings.koharu_fetching_languages)
                } else if (languageEntries.isEmpty()) {
                    languagesErrorMessage ?: stringResource(KMR.strings.koharu_no_languages_found)
                } else if (targetLanguage.isEmpty()) {
                    stringResource(KMR.strings.pref_koharu_select_target_language)
                } else {
                    "%s"
                },
                enabled = if (useOldClient) selectedV1Model != null else !isLoadingLanguages,
            ),
        )

        return persistentListOf(
            Preference.PreferenceGroup(
                title = stringResource(KMR.strings.pref_koharu_configuration),
                preferenceItems = configItems.toPersistentList(),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KMR.strings.pref_koharu_clear_cache),
                subtitle = stringResource(KMR.strings.pref_koharu_clear_cache_summary, storageSize),
                onClick = { showClearCacheDialog = true },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KMR.strings.pref_koharu_about),
                onClick = {
                    uriHandler.openUri(
                        "https://koharu.rs",
                    )
                },
            ),
        ).also {
            if (showClearCacheDialog) {
                ClearCacheDialog(
                    context = context,
                    translationStorage = translationStorage,
                    onDismiss = { showClearCacheDialog = false },
                    onCacheCleared = {
                        storageSize = translationStorage.getTotalSizeFormatted()
                    },
                )
            }
        }
    }
}

@Composable
private fun ClearCacheDialog(
    context: Context,
    translationStorage: TranslationStorage,
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
                        translationStorage.clearAll()
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
