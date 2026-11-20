package vn.vovgt.plus

import android.content.Context

/**
 * Small persistent status bridge between the VOV public-download service
 * and the shared download-history screen. Does not store VOV Media credentials.
 */
internal object VovPublicDownloadState {
    private const val PREFS = "vov_public_download_state"
    private const val KEY_ACTIVE = "active"
    private const val KEY_TITLE = "title"
    private const val KEY_STATUS = "status"
    private const val KEY_DETAIL = "detail"
    private const val KEY_PROGRESS = "progress"
    private const val KEY_UPDATED = "updated"

    data class Snapshot(
        val active: Boolean,
        val title: String,
        val status: String,
        val detail: String,
        val progress: Float,
        val updatedAt: Long
    )

    fun read(context: Context): Snapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            active = p.getBoolean(KEY_ACTIVE, false),
            title = p.getString(KEY_TITLE, "Nội dung VOV") ?: "Nội dung VOV",
            status = p.getString(KEY_STATUS, "idle") ?: "idle",
            detail = p.getString(KEY_DETAIL, "") ?: "",
            progress = p.getFloat(KEY_PROGRESS, -1f),
            updatedAt = p.getLong(KEY_UPDATED, 0L)
        )
    }

    fun started(context: Context, title: String) {
        write(context, true, title, "running", "Đang chuẩn bị tải…", -1f)
    }

    fun progress(context: Context, title: String, detail: String) {
        val fraction = Regex("""(\d+)\s*/\s*(\d+)""").find(detail)?.let {
            val current = it.groupValues[1].toFloatOrNull() ?: 0f
            val total = it.groupValues[2].toFloatOrNull() ?: 0f
            if (total > 0f) (current / total).coerceIn(0f, 0.99f) else -1f
        } ?: -1f
        write(context, true, title, "running", detail, fraction)
    }


    fun paused(context: Context, title: String) {
        val current = read(context)
        write(context, true, title, "paused", current.detail.ifBlank { "Đã tạm dừng" }, current.progress)
    }

    fun resumed(context: Context, title: String) {
        val current = read(context)
        write(context, true, title, "running", current.detail.ifBlank { "Đang tiếp tục tải…" }, current.progress)
    }

    fun completed(context: Context, title: String, detail: String) {
        write(context, false, title, "completed", detail, 1f)
    }

    fun failed(context: Context, title: String, detail: String) {
        write(context, false, title, "failed", detail, -1f)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun write(
        context: Context,
        active: Boolean,
        title: String,
        status: String,
        detail: String,
        progress: Float
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, active)
            .putString(KEY_TITLE, title)
            .putString(KEY_STATUS, status)
            .putString(KEY_DETAIL, detail)
            .putFloat(KEY_PROGRESS, progress)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }
}


/** Pause gate shared by the VOV public downloader and its foreground service. */
internal object VovPublicDownloadControl {
    private val monitor = java.lang.Object()
    @Volatile private var paused = false

    fun pause() {
        synchronized(monitor) { paused = true }
    }

    fun resume() {
        synchronized(monitor) {
            paused = false
            monitor.notifyAll()
        }
    }

    fun awaitIfPaused() {
        synchronized(monitor) {
            while (paused) {
                try {
                    monitor.wait()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw kotlinx.coroutines.CancellationException("VOV download interrupted")
                }
            }
        }
    }
}
