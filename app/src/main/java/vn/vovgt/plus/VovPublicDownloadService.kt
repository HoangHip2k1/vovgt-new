package vn.vovgt.plus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sequential foreground worker for public VOV archive downloads.
 * Queue metadata and journal entries survive process restarts.
 */
class VovPublicDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadJob: kotlinx.coroutines.Job? = null
    private var currentTaskId: String? = null
    private var lastStartId: Int = 0
    @Volatile private var shuttingDown: Boolean = false
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_PAUSE -> {
                val task = DownloadCenterStore.current(applicationContext)
                if (task != null) {
                    VovPublicDownloadControl.pause()
                    DownloadCenterStore.update(applicationContext, task.id, status = "paused", detail = "Đã tạm dừng")
                    VovPublicDownloadState.paused(applicationContext, task.title)
                    DownloadCenterStore.record(applicationContext, "VOV Nghe & xem lại", task.title, "paused", "Người dùng tạm dừng tải")
                    updateNotification("Đã tạm dừng: ${task.title}", ongoing = true, paused = true)
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                VovPublicDownloadControl.resume()
                val pausedTask = DownloadCenterStore.current(applicationContext)
                if (pausedTask != null) {
                    DownloadCenterStore.update(applicationContext, pausedTask.id, status = "running", detail = "Đang tiếp tục tải…")
                    VovPublicDownloadState.resumed(applicationContext, pausedTask.title)
                    DownloadCenterStore.record(applicationContext, "VOV Nghe & xem lại", pausedTask.title, "resumed", "Tiếp tục tải")
                    if (downloadJob?.isCompleted != false) {
                        currentTaskId = pausedTask.id
                        try {
                            startAsForeground("Đang tiếp tục: ${pausedTask.title}")
                            runTask(pausedTask.copy(status = "running"), startId)
                        } catch (error: Exception) {
                            val detail = "Không thể tiếp tục tải: ${error.message}"
                            DownloadCenterStore.update(applicationContext, pausedTask.id, status = "failed", detail = detail)
                            DownloadCenterStore.record(applicationContext, "VOV Nghe & xem lại", pausedTask.title, "failed", detail)
                            startNext(startId)
                        }
                    } else {
                        updateNotification("Đang tiếp tục tải: ${pausedTask.title}", ongoing = true)
                    }
                } else if (downloadJob?.isCompleted != false) {
                    startNext(startId)
                }
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
                cancelTask(id)
                if (downloadJob?.isCompleted != false) startNext(startId)
                return START_NOT_STICKY
            }
            ACTION_RETRY -> {
                val id = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
                if (id.isNotBlank()) DownloadCenterStore.retry(applicationContext, id)
                if (downloadJob?.isCompleted != false) startNext(startId)
                return START_NOT_STICKY
            }
            ACTION_REMOVE -> {
                val id = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
                if (id.isNotBlank()) DownloadCenterStore.removeTask(applicationContext, id)
                return START_NOT_STICKY
            }
            ACTION_ENQUEUE, ACTION_START -> {
                val mediaUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Chương trình VOV" }
                val pageUrl = intent.getStringExtra(EXTRA_PAGE).orEmpty().ifBlank { "https://vov.gov.vn/nghe-va-xem-lai" }
                if (mediaUrl.isNotBlank()) {
                    val task = DownloadCenterStore.enqueue(applicationContext, mediaUrl, title, pageUrl)
                    if (downloadJob?.isCompleted != false) startNext(startId)
                    else updateNotification("Đã xếp hàng: ${task.title}", ongoing = true)
                }
                return START_NOT_STICKY
            }
            ACTION_PROCESS_QUEUE -> {
                if (downloadJob?.isCompleted != false) startNext(startId)
                return START_NOT_STICKY
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun startNext(startId: Int) {
        if (downloadJob?.isCompleted == false) return
        val task = DownloadCenterStore.nextQueued(applicationContext)
        if (task == null) {
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return
        }
        currentTaskId = task.id
        try {
            startAsForeground("Đang chuẩn bị tải: ${task.title}")
        } catch (error: Exception) {
            Log.e(TAG, "Cannot promote VOV download service to foreground", error)
            val detail = "Không thể khởi chạy dịch vụ tải: ${error.message}"
            DownloadCenterStore.update(applicationContext, task.id, status = "failed", detail = detail)
            DownloadCenterStore.record(applicationContext, "VOV Nghe & xem lại", task.title, "failed", detail)
            currentTaskId = null
            startNext(startId)
            return
        }
        runTask(task, startId)
    }

    private fun runTask(task: DownloadCenterStore.PublicTask, startId: Int) {
        currentTaskId = task.id
        VovPublicDownloadControl.resume()
        VovPublicDownloadState.started(applicationContext, task.title)
        DownloadCenterStore.record(applicationContext, "VOV Nghe & xem lại", task.title, "started", "Bắt đầu tải nội dung")
        downloadJob = serviceScope.launch {
            try {
                val result = VovPublicMediaDownloader.download(
                    applicationContext, task.url, task.title, task.pageUrl
                ) { message ->
                    DownloadCenterStore.update(applicationContext, task.id, detail = message, progress = progressFrom(message))
                    VovPublicDownloadState.progress(applicationContext, task.title, message)
                    updateNotification(message, ongoing = true)
                }
                val detail = "Đã tải xong: ${result.displayName} • ${result.location}"
                DownloadCenterStore.update(applicationContext, task.id, status = "completed", detail = detail, progress = 1f, location = result.location, mediaUri = result.mediaUri)
                DownloadCenterStore.record(applicationContext, "VOV Nghe & xem lại", task.title, "completed", detail)
                VovPublicDownloadState.completed(applicationContext, task.title, detail)
                updateNotification(detail, ongoing = false, completed = true)
            } catch (cancelled: CancellationException) {
                val latest = DownloadCenterStore.tasks(applicationContext).firstOrNull { it.id == task.id }
                if (latest?.status == "running" || latest?.status == "paused") {
                    DownloadCenterStore.update(applicationContext, task.id, status = "queued", detail = "Tác vụ bị gián đoạn; sẽ thử lại khi dịch vụ hoạt động")
                }
                throw cancelled
            } catch (error: Exception) {
                val latest = DownloadCenterStore.tasks(applicationContext).firstOrNull { it.id == task.id }
                if (latest?.status != "cancelled" && !shuttingDown) {
                    val detail = "Tải VOV thất bại: ${error.message ?: "Lỗi không xác định"}"
                    DownloadCenterStore.update(applicationContext, task.id, status = "failed", detail = detail, progress = -1f)
                    DownloadCenterStore.record(applicationContext, "VOV Nghe & xem lại", task.title, "failed", detail)
                    VovPublicDownloadState.failed(applicationContext, task.title, detail)
                    updateNotification(detail, ongoing = false, failed = true)
                }
            } finally {
                mainHandler.post {
                    // A newly enqueued task may already have started while this cancelled job was unwinding.
                    if (!shuttingDown && currentTaskId == task.id) {
                        downloadJob = null
                        currentTaskId = null
                        val next = DownloadCenterStore.nextQueued(applicationContext)
                        if (next != null) runTask(next, lastStartId)
                        else {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf(lastStartId)
                        }
                    }
                }
            }
        }
    }

    private fun cancelTask(id: String) {
        if (id.isBlank()) return
        val current = DownloadCenterStore.current(applicationContext)
        if (current?.id == id) {
            DownloadCenterStore.cancel(applicationContext, id)
            VovPublicDownloadControl.resume()
            VovPublicMediaDownloader.cancelCurrent()
            downloadJob?.cancel()
            VovPublicDownloadState.failed(applicationContext, current.title, "Đã hủy tải")
        } else {
            DownloadCenterStore.cancel(applicationContext, id)
        }
    }

    private fun progressFrom(message: String): Float {
        val match = Regex("""(\d+)\s*/\s*(\d+)""").find(message) ?: return -1f
        val current = match.groupValues[1].toFloatOrNull() ?: return -1f
        val total = match.groupValues[2].toFloatOrNull() ?: return -1f
        return if (total > 0f) (current / total).coerceIn(0f, 0.99f) else -1f
    }

    private fun startAsForeground(message: String) {
        startForeground(NOTIFICATION_ID, buildNotification(message, ongoing = true))
    }

    private fun updateNotification(
        message: String,
        ongoing: Boolean,
        completed: Boolean = false,
        failed: Boolean = false,
        paused: Boolean = false
    ) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(message, ongoing, completed, failed, paused))
    }

    private fun buildNotification(
        message: String,
        ongoing: Boolean,
        completed: Boolean = false,
        failed: Boolean = false,
        paused: Boolean = false
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this, 1, Intent(this, VovPublicDownloadService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val resumeIntent = PendingIntent.getService(
            this, 2, Intent(this, VovPublicDownloadService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this, 3,
            Intent(this, VovPublicDownloadService::class.java).setAction(ACTION_CANCEL)
                .putExtra(EXTRA_TASK_ID, currentTaskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when {
            completed -> "VOV • Tải hoàn tất"
            failed -> "VOV • Tải thất bại"
            paused -> "VOV • Đã tạm dừng"
            else -> "VOV • Đang tải nội dung"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle(title)
            .setContentText(message.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply {
                if (ongoing && !paused) addAction(android.R.drawable.ic_media_pause, "Tạm dừng", pauseIntent)
                if (paused) addAction(android.R.drawable.ic_media_play, "Tiếp tục", resumeIntent)
                if (ongoing) addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hủy", cancelIntent)
            }
            .setAutoCancel(!ongoing)
            .setProgress(0, 0, ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Tải nội dung VOV", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Hiển thị tiến độ tải nội dung công khai từ VOV"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shuttingDown = true
        DownloadCenterStore.current(applicationContext)?.let { task ->
            if (task.status == "running") {
                DownloadCenterStore.update(
                    applicationContext,
                    task.id,
                    status = "queued",
                    detail = "Tác vụ bị gián đoạn; sẽ tiếp tục khi mở lại mục Tải xuống"
                )
            }
        }
        VovPublicDownloadControl.resume()
        VovPublicMediaDownloader.cancelCurrent()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "vn.vovgt.plus.action.START_PUBLIC_VOV_DOWNLOAD"
        const val ACTION_ENQUEUE = "vn.vovgt.plus.action.ENQUEUE_PUBLIC_VOV_DOWNLOAD"
        const val ACTION_PROCESS_QUEUE = "vn.vovgt.plus.action.PROCESS_PUBLIC_VOV_QUEUE"
        const val ACTION_PAUSE = "vn.vovgt.plus.action.PAUSE_PUBLIC_VOV_DOWNLOAD"
        const val ACTION_RESUME = "vn.vovgt.plus.action.RESUME_PUBLIC_VOV_DOWNLOAD"
        const val ACTION_CANCEL = "vn.vovgt.plus.action.CANCEL_PUBLIC_VOV_DOWNLOAD"
        const val ACTION_RETRY = "vn.vovgt.plus.action.RETRY_PUBLIC_VOV_DOWNLOAD"
        const val ACTION_REMOVE = "vn.vovgt.plus.action.REMOVE_PUBLIC_VOV_DOWNLOAD"
        const val EXTRA_URL = "public_vov_url"
        const val EXTRA_TITLE = "public_vov_title"
        const val EXTRA_PAGE = "public_vov_page"
        const val EXTRA_TASK_ID = "public_vov_task_id"
        private const val CHANNEL_ID = "vov_public_downloads"
        private const val NOTIFICATION_ID = 7412
        private const val TAG = "VovPublicDownload"
    }
}
