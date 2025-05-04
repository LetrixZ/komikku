package eu.kanade.tachiyomi.ui.libraryUpdateError

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.libraryUpdateError.LibraryUpdateErrorScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob.Companion.ERROR_LOG_HELP_URL
import eu.kanade.tachiyomi.ui.browse.migration.advanced.design.PreMigrationScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.UnsortedPreferences
import tachiyomi.domain.libraryUpdateErrorMessage.model.LibraryUpdateErrorMessage
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LibraryUpdateErrorScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { LibraryUpdateErrorScreenModel() }
        val state by screenModel.state.collectAsState()

        LibraryUpdateErrorScreen(
            state = state,
            onClick = { item ->
                PreMigrationScreen.navigateToMigration(
                    Injekt.get<UnsortedPreferences>().skipPreMigration().get(),
                    navigator,
                    listOf(item.error.mangaId),
                )
            },
            onClickCover = { item -> navigator.push(MangaScreen(item.error.mangaId)) },
            onMultiMigrateClicked = {
                PreMigrationScreen.navigateToMigration(
                    Injekt.get<UnsortedPreferences>().skipPreMigration().get(),
                    navigator,
                    state.selected.map { it.error.mangaId },
                )
            },
            onSelectAll = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
            onErrorSelected = screenModel::toggleSelection,
            onExportErrors = {
                if (state.items.isNotEmpty()) {
                    exportErrorFile(context, errors = state.items, messages = state.messages)
                }
            },
            navigateUp = navigator::pop,
        )
    }

    private fun exportErrorFile(
        context: Context,
        errors: List<LibraryUpdateErrorItem>,
        messages: List<LibraryUpdateErrorMessage>,
    ) {
        val sourceManager = Injekt.get<SourceManager>()

        val file = context.createFileInCacheDir("mihon_update_errors.txt")
        file.bufferedWriter().use { out ->
            out.write(context.stringResource(MR.strings.library_errors_help, ERROR_LOG_HELP_URL) + "\n\n")

            // Error file format:
            // ! Error
            //   # Source
            //     - Manga
            val errorMap = errors.groupBy { it.error.messageId }
            errorMap.forEach { (messageId, errors) ->
                val message = messages.find { it.id == messageId }
                out.write("\n! ${message!!.message}\n")
                errors.groupBy { it.error.mangaSource }.forEach { source, errors ->
                    val source = sourceManager.getOrStub(source)
                    out.write("  # $source\n")
                    errors.forEach {
                        out.write("    - ${it.error.mangaTitle}\n")
                    }
                }
            }
        }

        // Intent that opens the error log file in an external viewer
        val intent = Intent().apply {
            action = Intent.ACTION_VIEW
            setDataAndType(file.getUriCompat(context), "text/plain")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

}
