package eu.kanade.domain.koharu

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify

/**
 * Notifier for translation progress and errors.
 */
class TranslationNotifier(private val context: Context) {

    private val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_TRANSLATION_PROGRESS) {
            setColor(ContextCompat.getColor(context, R.color.ic_launcher))
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.komikku))
            setAutoCancel(false)
            setOnlyAlertOnce(true)
        }
    }

    private val errorNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_TRANSLATION_ERROR) {
            setColor(ContextCompat.getColor(context, R.color.ic_launcher))
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.komikku))
            setAutoCancel(true)
        }
    }

    private var isTranslating = false

    /**
     * Shows a notification from this builder.
     */
    private fun NotificationCompat.Builder.show(id: Int) {
        context.notify(id, build())
    }

    /**
     * Dismiss the translation progress notification.
     */
    fun dismissProgress() {
        context.cancelNotification(Notifications.ID_TRANSLATION_PROGRESS)
    }

    /**
     * Called when translation progress changes.
     */
    fun onProgressChange(mangaTitle: String, chapterName: String, translatedPages: Int, totalPages: Int) {
        with(progressNotificationBuilder) {
            if (!isTranslating) {
                setSmallIcon(android.R.drawable.stat_sys_download)
                clearActions()
                setContentIntent(null) // Could open translation settings
                isTranslating = true
            }

            val progressText = "Translating $translatedPages/$totalPages pages"

            setContentTitle("$mangaTitle - $chapterName".chop(30))
            setContentText(progressText)
            setProgress(totalPages, translatedPages, false)
            setOngoing(true)

            show(Notifications.ID_TRANSLATION_PROGRESS)
        }
    }

    /**
     * Resets the state once translations are completed.
     */
    fun onComplete() {
        dismissProgress()
        isTranslating = false
    }

    /**
     * Called when an error occurs during translation.
     */
    fun onError(mangaTitle: String? = null, chapterName: String? = null, error: String? = null) {
        with(errorNotificationBuilder) {
            setContentTitle(
                if (mangaTitle != null && chapterName != null) {
                    "Translation failed: $mangaTitle - $chapterName"
                } else {
                    "Translation failed"
                },
            )
            setContentText(error ?: "Unknown error occurred")
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            clearActions()
            setContentIntent(null)
            setProgress(0, 0, false)

            show(Notifications.ID_TRANSLATION_ERROR)
        }

        isTranslating = false
    }
}
