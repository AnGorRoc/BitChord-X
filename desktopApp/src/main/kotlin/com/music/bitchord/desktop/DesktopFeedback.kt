package com.music.bitchord.desktop

import com.music.bitchord.data.Http
import com.music.bitchord.data.feedback.FeedbackCenter
import com.music.bitchord.data.feedback.FeedbackDiagnostics
import com.music.bitchord.data.feedback.FeedbackMessageKind
import com.music.bitchord.data.feedback.FeedbackNotice
import com.music.bitchord.data.listentogether.PartyServers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The desktop's feedback: one [FeedbackCenter] for the process, synced at start, every half hour
 * while the app runs, and after the person sends something. The developer's answers are announced
 * through the tray when there is one; the Settings row shows how many are unread either way.
 *
 * Reports go to the developer's server, not to a party server the person typed in. A build can
 * point it elsewhere with -Dbitchord.feedback.server (a local test server).
 */
internal object DesktopFeedback {
    private const val SYNC_EVERY_MS = 30L * 60 * 1000
    private const val DEFAULT_SERVER = "https://bitchord.pymp.net"

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var instance: FeedbackCenter? = null
    private var baseUrl = DEFAULT_SERVER

    private val _available = MutableStateFlow(false)

    /** Whether the server takes feedback (it lists "feedback" in /api/info). */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    val center: FeedbackCenter get() = checkNotNull(instance) { "DesktopFeedback.install was not called" }

    private val appVersion: String
        get() = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("bitchord.version")?.takeIf { it.isNotBlank() }
            ?: "desktop"

    /** Call once at start, before the window. */
    fun install() {
        baseUrl = (System.getProperty("bitchord.feedback.server")?.trim()?.trimEnd('/')).orEmpty()
            .ifEmpty { System.getProperty("bitchord.listentogether.server").orEmpty().trim().trimEnd('/') }
            .takeUnless { it.isEmpty() || it.equals(PartyServers.ORIGINAL_OFFICIAL, ignoreCase = true) }
            ?: DEFAULT_SERVER
        instance = FeedbackCenter.create(
            baseUrl = baseUrl,
            dir = dataDir(),
            platform = "pc",
            appVersion = appVersion,
            platformLabel = "PC",
            locale = { DesktopStrings.resolvedTag().ifBlank { Locale.getDefault().toLanguageTag() } },
            accountName = ::accountName,
        )
        scope.launch {
            checkAvailable()
            while (true) {
                sync(minIntervalMs = SYNC_EVERY_MS - 60_000)
                delay(SYNC_EVERY_MS)
            }
        }
    }

    /** Where the state lives: app data, not a cache, since the outbox must survive. */
    private fun dataDir(): File {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val home = System.getProperty("user.home").orEmpty()
        val base = when {
            os.contains("win") -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it, "BitChord") }
            os.contains("mac") -> File(home, "Library/Application Support/BitChord")
            else -> null
        } ?: File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "bitchord")
        return File(base, "feedback").also { it.mkdirs() }
    }

    /** The signed-in Google account's name, for {{nombre}}. It never leaves the computer. */
    private fun accountName(): String? = runCatching {
        val id = DesktopAccounts.activeAccountId()
        DesktopAccounts.accounts().firstOrNull { it.accountId == id }?.name
    }.getOrNull()

    private fun checkAvailable() {
        val body = runCatching {
            val call = Http.client.newCall(Request.Builder().url("$baseUrl/api/info").get().build())
            call.timeout().timeout(10, TimeUnit.SECONDS)
            call.execute().use { if (it.isSuccessful) it.body.string() else null }
        }.getOrNull() ?: return
        _available.value = "feedback" in PartyServers.parseFeatures(body)
    }

    /** Sends what is queued and checks for answers, without blocking the caller. */
    fun syncSoon() {
        scope.launch { sync(0) }
    }

    private suspend fun sync(minIntervalMs: Long) {
        val c = instance ?: return
        val result = runCatching { if (minIntervalMs > 0) c.syncIfDue(minIntervalMs) else c.sync() }.getOrNull() ?: return
        announce(result.notices)
    }

    /** One tray balloon per ticket with news on it. */
    private fun announce(notices: List<FeedbackNotice>) {
        for ((_, group) in notices.groupBy { it.ticketId }) {
            val title = group.first().ticketTitle
            val messages = group.filter { it.kind != FeedbackMessageKind.STATUS }
            val status = group.lastOrNull { it.kind == FeedbackMessageKind.STATUS }?.text
            val heading = when {
                messages.any { it.kind == FeedbackMessageKind.THANKS } ->
                    DesktopStrings["feedback_notification_thanks", "A thank-you from the developer"]
                messages.isEmpty() -> DesktopStrings["feedback_notification_update", "Update on “%s”"].format(title)
                else -> DesktopStrings["feedback_notification_title", "Reply to “%s”"].format(title)
            }
            val text = messages.joinToString("\n\n") { it.text }
                .ifEmpty { DesktopStrings["feedback_status_changed", "Status: %s"].format(statusLabel(status.orEmpty())) }
            DesktopAwtTray.notify(heading, text)
        }
    }

    /** What "attach technical info" sends. Shown before it is sent. */
    fun diagnostics(): FeedbackDiagnostics {
        val level = DesktopPresence.telemetryLevel.value
        val screen = runCatching {
            val size = java.awt.Toolkit.getDefaultToolkit().screenSize
            "${size.width}x${size.height}"
        }.getOrDefault("")
        return FeedbackDiagnostics(
            deviceModel = System.getProperty("os.name").orEmpty().take(64),
            osVersion = System.getProperty("os.version").orEmpty().take(64),
            abi = System.getProperty("os.arch").orEmpty(),
            screen = screen,
            telemetryLevel = level.wire,
            installId = if (level.wire == "full") DesktopPresence.installId() else "",
        )
    }

    val version: String get() = appVersion

    fun statusLabel(status: String): String = when (status) {
        "new" -> DesktopStrings["feedback_status_new", "Received"]
        "reviewed" -> DesktopStrings["feedback_status_reviewed", "Seen"]
        "need_info" -> DesktopStrings["feedback_status_need_info", "Needs your answer"]
        "in_progress" -> DesktopStrings["feedback_status_in_progress", "In progress"]
        "done" -> DesktopStrings["feedback_status_done", "Done"]
        "wontfix" -> DesktopStrings["feedback_status_wontfix", "Won't be done"]
        "duplicate" -> DesktopStrings["feedback_status_duplicate", "Already reported"]
        else -> status
    }
}
