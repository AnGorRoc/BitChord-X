package com.music.bitchord.data.feedback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog
import kotlinx.coroutines.launch

/**
 * The developer's answers as notifications, one per ticket. A reply can be answered from the
 * notification itself, and a "done" ticket can be confirmed or reopened from it, without opening
 * the app.
 */
object FeedbackNotifications {
    private const val TAG = "Feedback"
    private const val CHANNEL_ID = "feedback_replies"
    internal const val ACTION_REPLY = "com.music.bitchord.feedback.REPLY"
    internal const val ACTION_RESOLVED = "com.music.bitchord.feedback.RESOLVED"
    internal const val ACTION_UNRESOLVED = "com.music.bitchord.feedback.UNRESOLVED"
    internal const val EXTRA_TICKET = "ticket"
    internal const val KEY_REPLY = "feedback_reply"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.feedback_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.feedback_notification_channel_detail) },
        )
    }

    private fun notificationId(ticketId: String) = ("feedback:$ticketId").hashCode()

    /** Posts [notices], one notification per ticket with everything new on it. */
    fun post(context: Context, notices: List<FeedbackNotice>) {
        if (notices.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        for ((ticketId, group) in notices.groupBy { it.ticketId }) {
            val title = group.first().ticketTitle
            val messages = group.filter { it.kind != FeedbackMessageKind.STATUS }
            val status = group.lastOrNull { it.kind == FeedbackMessageKind.STATUS }?.text
            val text = messages.joinToString("\n\n") { it.text }
                .ifEmpty { context.getString(R.string.feedback_status_changed, statusLabel(context, status.orEmpty())) }

            // Explicit and with no action, like the widget's: a launcher intent can be satisfied by
            // bringing the task forward without onNewIntent, and the ticket would never open.
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AndroidFeedback.EXTRA_TICKET, ticketId)
                .let { PendingIntent.getActivity(context, notificationId(ticketId), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(
                    when {
                        messages.any { it.kind == FeedbackMessageKind.THANKS } -> context.getString(R.string.feedback_notification_thanks)
                        messages.isEmpty() -> context.getString(R.string.feedback_notification_update, title)
                        else -> context.getString(R.string.feedback_notification_title, title)
                    },
                )
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSubText(status?.let { statusLabel(context, it) })
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)

            if (status == FeedbackStatus.DONE) {
                builder.addAction(0, context.getString(R.string.feedback_resolved_yes), action(context, ACTION_RESOLVED, ticketId, mutable = false))
                builder.addAction(0, context.getString(R.string.feedback_resolved_no), action(context, ACTION_UNRESOLVED, ticketId, mutable = false))
            } else if (status == null || !FeedbackStatus.isClosed(status)) {
                val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.feedback_reply_hint)).build()
                builder.addAction(
                    NotificationCompat.Action.Builder(0, context.getString(R.string.feedback_notification_reply), action(context, ACTION_REPLY, ticketId, mutable = true))
                        .addRemoteInput(input)
                        .setAllowGeneratedReplies(false)
                        .build(),
                )
            }
            runCatching { manager.notify(notificationId(ticketId), builder.build()) }
                .onFailure { DebugLog.w(TAG, "feedback notification not posted: ${it.message}") }
        }
    }

    /** Replaces a ticket's notification with a short confirmation that goes away by itself. */
    internal fun confirm(context: Context, ticketId: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(text)
            .setTimeoutAfter(4_000)
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(notificationId(ticketId), n) }
    }

    private fun action(context: Context, action: String, ticketId: String, mutable: Boolean): PendingIntent {
        val intent = Intent(context, FeedbackActionReceiver::class.java).setAction(action).putExtra(EXTRA_TICKET, ticketId)
        // RemoteInput fills the reply into the intent, which only a mutable PendingIntent allows.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (action + ticketId).hashCode(), intent, flags)
    }

    fun statusLabel(context: Context, status: String): String = context.getString(
        when (status) {
            FeedbackStatus.NEW -> R.string.feedback_status_new
            FeedbackStatus.REVIEWED -> R.string.feedback_status_reviewed
            FeedbackStatus.NEED_INFO -> R.string.feedback_status_need_info
            FeedbackStatus.IN_PROGRESS -> R.string.feedback_status_in_progress
            FeedbackStatus.DONE -> R.string.feedback_status_done
            FeedbackStatus.WONTFIX -> R.string.feedback_status_wontfix
            FeedbackStatus.DUPLICATE -> R.string.feedback_status_duplicate
            else -> R.string.feedback_status_reviewed
        },
    )
}

/** The notification's actions: a reply typed into it, or "solved" / "still happening". */
class FeedbackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ticketId = intent.getStringExtra(FeedbackNotifications.EXTRA_TICKET) ?: return
        val pending = goAsync()
        AndroidFeedback.scope.launch {
            try {
                val c = AndroidFeedback.center
                when (intent.action) {
                    FeedbackNotifications.ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(FeedbackNotifications.KEY_REPLY)?.toString()?.trim()
                        if (!text.isNullOrEmpty()) {
                            c.reply(ticketId, text)
                            FeedbackNotifications.confirm(context, ticketId, context.getString(R.string.feedback_notification_replied))
                        }
                    }
                    FeedbackNotifications.ACTION_RESOLVED -> {
                        c.rate(ticketId, resolved = true)
                        FeedbackNotifications.confirm(context, ticketId, context.getString(R.string.feedback_thanks_for_confirming))
                    }
                    FeedbackNotifications.ACTION_UNRESOLVED -> {
                        c.rate(ticketId, resolved = false)
                        FeedbackNotifications.confirm(context, ticketId, context.getString(R.string.feedback_reopened))
                    }
                }
                AndroidFeedback.runSync(0)
            } finally {
                pending.finish()
            }
        }
    }
}
