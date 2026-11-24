package com.music.bitchord.data.feedback

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.Http
import com.music.bitchord.data.listentogether.PartyServers
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The phone's feedback: one [FeedbackCenter] for the process, kept in step with the server when
 * the app comes to the foreground, after the person sends something, and by [FeedbackSyncJob]
 * while an answer may still come.
 *
 * Reports go to the developer's server (the build's default Listen Together server), not to a
 * party server the person typed in: that one is theirs, and feedback is for whoever made the app.
 */
object AndroidFeedback {
    private const val TAG = "Feedback"
    private const val PREFS = "bitchord_feedback"
    private const val KEY_AVAILABLE = "available"
    private const val KEY_ACCOUNT_NAME = "account_name"
    /** Debug builds only: where to send feedback instead (a local test server). */
    private const val KEY_DEBUG_SERVER = "debug_feedback_server"
    private const val FOREGROUND_SYNC_MS = 15L * 60 * 1000

    /** Set on the notification's tap intent: the ticket to open. */
    const val EXTRA_TICKET = "bitchord.feedbackTicket"

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Application
    private lateinit var baseUrl: String
    @Volatile private var instance: FeedbackCenter? = null

    private val _available = MutableStateFlow(false)

    /** Whether the server takes feedback (it lists "feedback" in /api/info). Remembered across starts. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _openTicket = MutableStateFlow<String?>(null)

    /** A ticket a notification asked to open; cleared by [openTicketHandled]. */
    val openTicket: StateFlow<String?> = _openTicket.asStateFlow()

    val center: FeedbackCenter get() = checkNotNull(instance) { "AndroidFeedback.install was not called" }

    private fun prefs(context: Context = app) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Call once from [Application.onCreate]. */
    fun install(application: Application) {
        app = application
        val prefs = prefs(application)
        val debugServer = if (BuildConfig.DEBUG) prefs.getString(KEY_DEBUG_SERVER, null)?.trim().orEmpty() else ""
        baseUrl = debugServer.ifEmpty { BuildConfig.LISTEN_TOGETHER_SERVER.trim().trimEnd('/') }
            .ifEmpty { "https://bitchord.pymp.net" }
        _available.value = prefs.getBoolean(KEY_AVAILABLE, false)
        instance = FeedbackCenter.create(
            baseUrl = baseUrl,
            dir = File(application.filesDir, "feedback"),
            platform = "android",
            appVersion = BuildConfig.VERSION_NAME,
            platformLabel = "Android",
            locale = { Locale.getDefault().toLanguageTag() },
            accountName = {
                com.music.bitchord.data.settings.AppSettings.resolveUserName(
                    prefs.getString(KEY_ACCOUNT_NAME, null),
                )
            },
        )
        FeedbackNotifications.createChannel(application)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch {
                    checkAvailable()
                    runSync(FOREGROUND_SYNC_MS)
                }
            }
        })
    }

    /** Keeps the signed-in account's name for {{nombre}}, so a background notification has it too. */
    fun rememberAccountName(name: String?) {
        val clean = name?.trim().orEmpty()
        val prefs = prefs()
        if (prefs.getString(KEY_ACCOUNT_NAME, "") != clean) prefs.edit().putString(KEY_ACCOUNT_NAME, clean).apply()
    }

    private suspend fun checkAvailable() = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api/info").get().build()
        val body = runCatching {
            val call = Http.client.newCall(request)
            call.timeout().timeout(10, TimeUnit.SECONDS)
            call.execute().use { if (it.isSuccessful) it.body.string() else null }
        }.getOrNull() ?: return@withContext // offline: keep what was known
        val on = "feedback" in PartyServers.parseFeatures(body)
        _available.value = on
        prefs().edit().putBoolean(KEY_AVAILABLE, on).apply()
    }

    /** Sends what is queued and checks for answers, now, without blocking the caller. */
    fun syncSoon() {
        scope.launch { runSync(0) }
    }

    /**
     * One sync: posts the notifications it brings and (re)schedules the background job. Returns
     * whether the server was reachable.
     */
    internal suspend fun runSync(minIntervalMs: Long): Boolean {
        val c = instance ?: return true
        val result = runCatching {
            if (minIntervalMs > 0) c.syncIfDue(minIntervalMs) else c.sync()
        }.onFailure { DebugLog.w(TAG, "feedback sync failed: ${it.message}") }.getOrNull()
        if (result != null) FeedbackNotifications.post(app, result.notices)
        FeedbackSyncJob.schedule(app, c)
        return result?.online ?: false
    }

    /** Reads a notification's tap intent. */
    fun consume(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_TICKET) ?: return
        intent.removeExtra(EXTRA_TICKET)
        _openTicket.value = id
    }

    fun openTicketHandled() {
        _openTicket.value = null
    }

    /** What "attach technical info" sends. Shown to the person before they send it. */
    fun diagnostics(context: Context): FeedbackDiagnostics {
        val dm = context.resources.displayMetrics
        val level = AppSettings.telemetryLevel.value
        return FeedbackDiagnostics(
            deviceModel = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").take(64),
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            screen = "${dm.widthPixels}x${dm.heightPixels}",
            telemetryLevel = level.wire,
            // The install ID only travels when telemetry already sends it with every error report.
            installId = if (level.wire == "full") installId(context) else "",
        )
    }

    private fun installId(context: Context): String =
        context.getSharedPreferences("presence", Context.MODE_PRIVATE).getString("install_id", null)
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            .orEmpty()
}
