package vn.vovgt.plus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition
import androidx.media3.exoplayer.offline.Download
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors

object AudioExportState {
    private const val PREFS = "vov_audio_exports"

    fun status(context: Context, id: String): String =
        prefs(context).getString("status_$id", "").orEmpty()

    fun uri(context: Context, id: String): Uri? =
        prefs(context).getString("uri_$id", null)?.let(Uri::parse)

    fun error(context: Context, id: String): String =
        prefs(context).getString("error_$id", "").orEmpty()

    fun setStatus(context: Context, id: String, status: String, error: String? = null) {
        prefs(context).edit()
            .putString("status_$id", status)
            .putString("error_$id", error.orEmpty())
            .apply()
    }

    fun setDone(context: Context, id: String, uri: Uri) {
        prefs(context).edit()
            .putString("status_$id", "done")
            .putString("uri_$id", uri.toString())
            .remove("error_$id")
            .apply()
    }

    fun request(
        context: Context,
        download: Download,
        force: Boolean = false,
        recover: Boolean = false
    ) {
        if (download.state != Download.STATE_COMPLETED) return
        val app = context.applicationContext
        val id = download.request.id
        val preferences = prefs(app)
        if (!preferences.getString("uri_$id", null).isNullOrBlank()) return

        val current = preferences.getString("status_$id", "").orEmpty()
        if (!force) {
            if (current == "done" || current == "failed") return
            if (current == "queued" || current == "exporting") {
                if (!recover || AudioExportService.isRunning) return
            }
        }

        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            setStatus(app, id, "failed", "Cần cấp quyền bộ nhớ để lưu file vào thư mục Download.")
            return
        }

        preferences.edit()
            .putString("status_$id", "queued")
            .remove("error_$id")
            .commit()

        val intent = Intent(app, AudioExportService::class.java)
            .putExtra(AudioExportService.EXTRA_ID, id)
            .putExtra(AudioExportService.EXTRA_URL, download.request.uri.toString())
            .putExtra(AudioExportService.EXTRA_TITLE, OfflineStore.title(download))
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (exception: Exception) {
            setStatus(app, id, "failed", exception.message ?: "Không thể khởi động tiến trình xuất file.")
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

@UnstableApi
class AudioExportService : Service() {
    private data class Task(val id: String, val url: String, val title: String)

    private val queue = ArrayDeque<Task>()
    private val queuedIds = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var activeTask: Task? = null
    private var transformer: Transformer? = null
    private var tempOutput: File? = null
    private var latestStartId = 0

    companion object {
        const val EXTRA_ID = "download_id"
        const val EXTRA_URL = "download_url"
        const val EXTRA_TITLE = "download_title"
        private const val CHANNEL_ID = "vov_status"
        private const val FOREGROUND_NOTIFICATION_ID = 9101

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureNotificationChannel()
        startForeground(FOREGROUND_NOTIFICATION_ID, notification("Đang chuẩn bị tạo file M4A…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val id = intent?.getStringExtra(EXTRA_ID)
        val url = intent?.getStringExtra(EXTRA_URL)
        val title = intent?.getStringExtra(EXTRA_TITLE)
        if (!id.isNullOrBlank() && !url.isNullOrBlank() && !title.isNullOrBlank()) {
            if (queuedIds.add(id)) queue.addLast(Task(id, url, title))
        }
        processNext()
        return START_REDELIVER_INTENT
    }

    private fun processNext() {
        if (activeTask != null) return
        val task = queue.pollFirst()
        if (task == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(latestStartId)
            return
        }

        activeTask = task
        AudioExportState.setStatus(this, task.id, "exporting")
        updateForeground("Đang chuyển đổi: ${task.title}")

        val output = File(cacheDir, "vov-audio-${task.id}.mp4")
        output.delete()
        tempOutput = output

        try {
            val mediaItem = MediaItem.Builder()
                .setUri(task.url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(OfflineStore.playbackFactory())
            val bitmapLoader = DataSourceBitmapLoader.Builder(this)
                .setDataSourceFactory(OfflineStore.upstream())
                .build()
            val assetLoaderFactory = DefaultAssetLoaderFactory(
                this,
                DefaultDecoderFactory.Builder(this).build(),
                Clock.DEFAULT,
                mediaSourceFactory,
                bitmapLoader
            )
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    ioExecutor.execute {
                        try {
                            val savedUri = publishToDownloads(output, task)
                            output.delete()
                            AudioExportState.setDone(this@AudioExportService, task.id, savedUri)
                            mainHandler.post {
                                showResult(task, true, "Đã lưu trong Download/VOV Giao Thong+")
                                finishTask(task)
                            }
                        } catch (exception: Exception) {
                            mainHandler.post {
                                failTask(task, exception.message ?: "Không thể ghi file vào thư mục Download.")
                            }
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    failTask(task, exportException.message ?: "Không thể chuyển đổi luồng âm thanh.")
                }
            }

            transformer = Transformer.Builder(this)
                .setAssetLoaderFactory(assetLoaderFactory)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)
                .build()

            val audioOnlyItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .build()
            transformer?.start(audioOnlyItem, output.absolutePath)
        } catch (exception: Exception) {
            failTask(task, exception.message ?: "Không thể bắt đầu chuyển đổi âm thanh.")
        }
    }

    private fun publishToDownloads(source: File, task: Task): Uri {
        if (!source.exists() || source.length() <= 0L) {
            error("Bộ chuyển đổi không tạo được dữ liệu âm thanh.")
        }
        val fileName = safeFileName(task.title, task.id)

        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/VOV Giao Thong+"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val destination = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Không thể tạo file trong thư mục Download.")
            try {
                val output = contentResolver.openOutputStream(destination, "w")
                    ?: error("Không thể mở file đích để ghi.")
                output.use { stream ->
                    FileInputStream(source).use { input -> input.copyTo(stream) }
                }
                val completed = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(destination, completed, null, null)
                destination
            } catch (exception: Exception) {
                contentResolver.delete(destination, null, null)
                throw exception
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                error("Chưa được cấp quyền ghi bộ nhớ.")
            }
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VOV Giao Thong+"
            )
            if (!directory.exists() && !directory.mkdirs()) {
                error("Không thể tạo thư mục Download/VOV Giao Thong+.")
            }
            val destinationFile = File(directory, fileName)
            FileInputStream(source).use { input ->
                FileOutputStream(destinationFile, false).use { output -> input.copyTo(output) }
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, destinationFile.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.TITLE, task.title)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: Uri.fromFile(destinationFile)
        }
    }

    private fun safeFileName(title: String, id: String): String {
        val base = title.trim().map { character ->
            if (character.isLetterOrDigit() || character in " -_().") character else '_'
        }.joinToString("").trim().take(90).ifBlank { "Chuong trinh VOV" }
        return "$base-${id.take(8)}.m4a"
    }

    private fun failTask(task: Task, message: String) {
        tempOutput?.delete()
        AudioExportState.setStatus(this, task.id, "failed", message.take(240))
        showResult(task, false, message.take(180))
        finishTask(task)
    }

    private fun finishTask(task: Task) {
        queuedIds.remove(task.id)
        activeTask = null
        transformer = null
        tempOutput = null
        processNext()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Kết quả tải xuống", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun notification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle("VOV Giao thông+ · Xuất file M4A")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun updateForeground(message: String) {
        startForeground(FOREGROUND_NOTIFICATION_ID, notification(message))
    }

    private fun showResult(task: Task, success: Boolean, message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val openApp = PendingIntent.getActivity(
            this,
            task.id.hashCode(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val result = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle(if (success) "Đã lưu file M4A" else "Chưa xuất được file M4A")
            .setContentText("${task.title} · $message")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(task.id.hashCode() and 0x7fffffff, result)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        ioExecutor.shutdown()
        super.onDestroy()
    }
}
