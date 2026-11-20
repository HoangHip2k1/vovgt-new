package vn.vovgt.plus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
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
 * Foreground download service used only by the public VOV archive tab.
 * It is independent from VOV Media downloads and authentication.
 */
class VovPublicDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadJob: kotlinx.coroutines.Job? = null
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                VovPublicDownloadControl.pause()
                val title = VovPublicDownloadState.read(applicationContext).title
                VovPublicDownloadState.paused(applicationContext, title)
                updateNotification("Đã tạm dừng: $title", ongoing = true, paused = true)
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                VovPublicDownloadControl.resume()
                val title = VovPublicDownloadState.read(applicationContext).title
                VovPublicDownloadState.resumed(applicationContext, title)
                updateNotification("Đang tiếp tục: $title", ongoing = true)
                return START_NOT_STICKY
            }
        }
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (downloadJob?.isActive == true) {
            updateNotification("Đang có một lượt tải VOV khác chạy.", ongoing = true)
            return START_NOT_STICKY
        }

        val mediaUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Chương trình VOV" }
        val pageUrl = intent.getStringExtra(EXTRA_PAGE).orEmpty().ifBlank {
            "https://vov.gov.vn/nghe-va-xem-lai"
        }
        if (mediaUrl.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            startAsForeground("Đang chuẩn bị tải: $title")
        } catch (error: Exception) {
            // A foreground-service startup exception must not crash the whole app process.
            Log.e(TAG, "Cannot promote VOV download service to foreground", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        VovPublicDownloadControl.resume()
        VovPublicDownloadState.started(applicationContext, title)
        downloadJob = serviceScope.launch {
            try {
                val result = VovPublicMediaDownloader.download(
                    context = applicationContext,
                    mediaUrl = mediaUrl,
                    title = title,
                    pageUrl = pageUrl,
                    onProgress = { message ->
                        VovPublicDownloadState.progress(applicationContext, title, message)
                        updateNotification(message, ongoing = true)
                    }
                )
                val doneMessage = "Đã tải xong: ${result.displayName} • ${result.location}"
                VovPublicDownloadState.completed(applicationContext, title, doneMessage)
                updateNotification(doneMessage, ongoing = false, completed = true)
                notificationManager.cancel(NOTIFICATION_ID)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failure = "Tải VOV thất bại: ${error.message ?: "Lỗi không xác định"}"
                VovPublicDownloadState.failed(applicationContext, title, failure)
                updateNotification(failure, ongoing = false, failed = true)
                notificationManager.cancel(NOTIFICATION_ID)
            } finally {
                // Service lifecycle operations are dispatched on Android's main thread.
                mainHandler.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(message: String) {
        val notification = buildNotification(message, ongoing = true)
        // The service type is declared in AndroidManifest.xml. Using the
        // two-argument overload lets Android apply that manifest type and avoids
        // a runtime type mismatch while still creating a foreground service.
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(
        message: String,
        ongoing: Boolean,
        completed: Boolean = false,
        failed: Boolean = false,
        paused: Boolean = false
    ) {
        val notification = buildNotification(message, ongoing, completed, failed, paused)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        message: String,
        ongoing: Boolean,
        completed: Boolean = false,
        failed: Boolean = false,
        paused: Boolean = false
    ): Notification {
        val openApp = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when {
            completed -> "VOV • Tải hoàn tất"
            failed -> "VOV • Tải thất bại"
            else -> "VOV • Đang tải nội dung"
        }
        val pauseIntent = PendingIntent.getService(this, 1, Intent(this, VovPublicDownloadService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val resumeIntent = PendingIntent.getService(this, 2, Intent(this, VovPublicDownloadService::class.java).setAction(ACTION_RESUME), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle(title)
            .setContentText(message.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .apply {
                if (ongoing && !paused) addAction(android.R.drawable.ic_media_pause, "Tạm dừng", pauseIntent)
                if (paused) addAction(android.R.drawable.ic_media_play, "Tiếp tục", resumeIntent)
            }
            .setAutoCancel(!ongoing)
            .setProgress(0, 0, ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tải nội dung VOV",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị tiến độ tải nội dung công khai từ VOV"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "vn.vovgt.plus.action.START_PUBLIC_VOV_DOWNLOAD"
        const val ACTION_PAUSE = "vn.vovgt.plus.action.PAUSE_PUBLIC_VOV_DOWNLOAD"
        const val ACTION_RESUME = "vn.vovgt.plus.action.RESUME_PUBLIC_VOV_DOWNLOAD"
        const val EXTRA_URL = "public_vov_url"
        const val EXTRA_TITLE = "public_vov_title"
        const val EXTRA_PAGE = "public_vov_page"
        private const val CHANNEL_ID = "vov_public_downloads"
        private const val NOTIFICATION_ID = 7412
        private const val TAG = "VovPublicDownload"
    }
}
