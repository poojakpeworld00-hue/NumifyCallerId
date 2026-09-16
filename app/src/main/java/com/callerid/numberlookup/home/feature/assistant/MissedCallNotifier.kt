package com.callerid.numberlookup.home.feature.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.repository.ContactRepository
import com.callerid.numberlookup.home.repository.SettingsRepository
import com.callerid.numberlookup.home.repository.assistant.AiFeatureConfig

/**
 * The missed-call notification, with Call back and Reply actions.
 *
 * Separate from the post-call callback screen on purpose. That screen only
 * appears while the device is locked or asleep, and a missed call is precisely
 * the thing a user comes back to later — so this is a normal, dismissible
 * notification that survives until they deal with it.
 *
 * Reply opens [SmartReplyActivity] rather than sending anything: the app holds
 * no SMS permission, and does not want one.
 */
object MissedCallNotifier {

    private const val CHANNEL_ID = "missed_call_ai"

    /**
     * One notification per number, so three missed calls from the same caller
     * update a single entry instead of stacking three identical ones.
     */
    fun notificationId(number: String): Int =
        NOTIFICATION_BASE + number.filter(Char::isDigit).takeLast(6).hashCode() and 0xFFFF

    /** Posts the notification. A no-op while the assistant is switched off. */
    fun show(context: Context, number: String) {
        if (!AiFeatureConfig.isEnabled(context)) return
        if (!SettingsRepository(context).aiSmartReplyEnabled) return
        if (number.isBlank()) return
        if (NotificationManagerCompat.from(context).areNotificationsEnabled().not()) return

        createChannel(context)

        val name = runCatching { ContactRepository(context).lookupNameByNumber(number) }.getOrNull()
        val title = name?.takeIf(String::isNotBlank) ?: number

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_missed_title))
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(replyIntent(context, number, name))
            .addAction(0, context.getString(R.string.notif_action_callback), dialIntent(context, number))
            .addAction(0, context.getString(R.string.notif_action_reply), replyIntent(context, number, name))

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(notificationId(number), builder.build())
        }
    }

    /**
     * ACTION_DIAL, not ACTION_CALL: dial only opens the keypad pre-filled and
     * needs no permission, and calling straight from a notification tap is not
     * something a user expects anyway.
     */
    private fun dialIntent(context: Context, number: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId(number),
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun replyIntent(context: Context, number: String, name: String?): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId(number) + 1,
            SmartReplyActivity.newIntent(context, number, name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // DEFAULT, not HIGH: a missed call has already happened, so this should
        // sit in the shade rather than interrupt whatever the user is doing now.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_missed),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }

    private const val NOTIFICATION_BASE = 4100
}
