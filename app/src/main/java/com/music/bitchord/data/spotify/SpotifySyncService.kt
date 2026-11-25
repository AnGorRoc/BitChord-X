package com.music.bitchord.data.spotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process up while [SpotifySync] runs, and says so in the shade.
 *
 * Importing a library is minutes of work that a person starts and walks away
 * from; without a foreground service Android takes the process soon after the
 * app leaves the screen, and the import with it. The notification carries the
 * playlist under way, a bar for the whole run and a Cancel; when the run ends
 * it is replaced by one that says how it went.
 *
 * It owns no state: everything is read off [SpotifySync.state], and the
 * service stops itself as soon as the run is no longer [SpotifySyncState.Running].
 */
class SpotifySyncService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promoted first whatever the intent: a service started in the
        // foreground and never promoted takes the app down with it.
        promote()
        if (intent?.action == ACTION_CANCEL) {
            SpotifySync.cancel()
        }
        if (watcher == null) {
            watcher = scope.launch { follow() }
        }
        return START_NOT_STICKY
    }

    /** Mirrors the run into the notification until it ends, then reports the end. */
    private suspend fun follow() {
        SpotifySync.state.collect { state ->
            when (state) {
                is SpotifySyncState.Running -> {
                    notifyProgress(state)
                    // The shade coalesces anything faster, and a playlist of a
                    // thousand matches would otherwise repost a thousand times.
                    delay(PROGRESS_REFRESH_MS)
                }
                is SpotifySyncState.Finished, is SpotifySyncState.Aborted -> {
                    finish(state)
                    return@collect
                }
                SpotifySyncState.Idle -> finish(null)
            }
        }
    }

    private fun finish(state: SpotifySyncState?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (state != null) {
            val manager = getSystemService(NotificationManager::class.java)
            runCatching { manager?.notify(RESULT_NOTIFICATION_ID, buildResult(state)) }
        }
        watcher?.cancel()
        watcher = null
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- Notifications ------------------------------------------------------

    private fun promote() {
        val running = SpotifySync.state.value as? SpotifySyncState.Running
        val notification = buildProgress(running)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // The last run's result is stale once another starts.
        getSystemService(NotificationManager::class.java)?.cancel(RESULT_NOTIFICATION_ID)
    }

    private fun notifyProgress(state: SpotifySyncState.Running) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildProgress(state)) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildProgress(state: SpotifySyncState.Running?): Notification {
        val toYouTube = state?.target is SyncTarget.YouTube
        val title = getString(
            if (toYouTube) R.string.spotify_sync_notification_title_youtube
            else R.string.spotify_sync_notification_title_device,
        )
        val text = state?.let { describe(it) } ?: getString(R.string.spotify_sync_notification_starting)
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, SpotifySyncService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val percent = state?.let { (it.fraction * 100).toInt() } ?: 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // Indeterminate while a playlist's tracks are still being read.
            .setProgress(100, percent, state == null || (state.index == 1 && state.songsTotal == 0))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.cancel), cancel)
            .build()
    }

    /** "Playlist 2 of 5 · Rock · Matching songs: 40 of 120". */
    private fun describe(state: SpotifySyncState.Running): String {
        val step = when {
            state.throttled -> getString(R.string.spotify_sync_phase_throttled)
            state.phase == SyncPhase.READING || state.songsTotal == 0 ->
                getString(R.string.spotify_sync_phase_reading)
            state.phase == SyncPhase.MATCHING ->
                getString(R.string.spotify_sync_phase_matching, state.songsDone, state.songsTotal)
            state.phase == SyncPhase.UPLOADING ->
                getString(R.string.spotify_sync_phase_uploading, state.songsDone, state.songsTotal)
            else -> getString(R.string.spotify_sync_phase_liking, state.songsDone, state.songsTotal)
        }
        return getString(R.string.spotify_sync_notification_text, state.index, state.total, state.playlist.name, step)
    }

    private fun buildResult(state: SpotifySyncState): Notification {
        val (title, text) = when (state) {
            is SpotifySyncState.Finished -> {
                val headline = when {
                    state.cancelled -> getString(R.string.spotify_sync_notification_cancelled)
                    state.failures.isNotEmpty() -> getString(R.string.spotify_sync_notification_partial)
                    else -> getString(R.string.spotify_sync_notification_done)
                }
                val body = buildString {
                    append(
                        if (state.cancelled) {
                            getString(R.string.spotify_sync_cancelled, state.imported)
                        } else {
                            getString(R.string.spotify_sync_done, state.imported, state.added, state.unmatched)
                        },
                    )
                    if (state.liked > 0) {
                        append('\n').append(getString(R.string.spotify_sync_liked_count, state.liked))
                    }
                    if (state.failures.isNotEmpty()) {
                        append('\n').append(getString(R.string.spotify_sync_some_failed, state.failures.size))
                    }
                }
                headline to body
            }
            is SpotifySyncState.Aborted ->
                getString(R.string.spotify_sync_notification_stopped) to errorText(state.kind, state.detail)
            else -> getString(R.string.spotify_sync_notification_done) to ""
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun errorText(kind: SyncErrorKind, detail: String): String = when (kind) {
        SyncErrorKind.SIGNED_OUT -> getString(R.string.spotify_sync_error_signed_out)
        SyncErrorKind.YOUTUBE_SIGNED_OUT -> getString(R.string.spotify_sync_error_youtube_signed_out)
        SyncErrorKind.RATE_LIMITED -> getString(R.string.spotify_sync_error_rate_limited)
        SyncErrorKind.NETWORK -> getString(R.string.spotify_sync_error_network)
        SyncErrorKind.OTHER -> getString(R.string.spotify_sync_error_other, detail)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.spotify_sync_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    private companion object {
        const val CHANNEL_ID = "spotify_import"
        const val NOTIFICATION_ID = 0x5907
        const val RESULT_NOTIFICATION_ID = 0x5908
        const val ACTION_CANCEL = "com.music.bitchord.spotify.CANCEL_IMPORT"
        const val PROGRESS_REFRESH_MS = 400L
    }
}
