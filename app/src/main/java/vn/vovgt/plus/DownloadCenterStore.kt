package vn.vovgt.plus

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistent queue and event journal for public VOV downloads.
 * VOV Media downloads remain owned by Media3 DownloadManager; this store unifies
 * their presentation without mixing credentials or download engines.
 */
internal object DownloadCenterStore {
    private const val PREFS = "vov_download_center"
    private const val KEY_PUBLIC_QUEUE = "public_queue_v1"
    private const val KEY_JOURNAL = "journal_v1"
    private const val MAX_JOURNAL_ENTRIES = 1000

    data class PublicTask(
        val id: String,
        val url: String,
        val title: String,
        val pageUrl: String,
        val createdAt: Long,
        val updatedAt: Long,
        val status: String,
        val detail: String,
        val progress: Float,
        val location: String,
        val mediaUri: String
    )

    data class JournalEntry(
        val id: String,
        val timestamp: Long,
        val source: String,
        val title: String,
        val action: String,
        val detail: String
    )

    data class StorageSnapshot(
        val publicFileCount: Int,
        val publicBytes: Long,
        val cacheBytes: Long,
        val freeBytes: Long
    )

    @Synchronized
    fun enqueue(context: Context, url: String, title: String, pageUrl: String): PublicTask {
        require(url.startsWith("https://", ignoreCase = true)) { "Chỉ hỗ trợ nội dung HTTPS." }
        val existing = tasks(context).firstOrNull {
            it.url == url && it.status in setOf("queued", "running", "paused")
        }
        if (existing != null) return existing

        val now = System.currentTimeMillis()
        val task = PublicTask(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title.take(180).ifBlank { "Chương trình VOV" },
            pageUrl = pageUrl,
            createdAt = now,
            updatedAt = now,
            status = "queued",
            detail = "Đang chờ trong hàng đợi",
            progress = 0f,
            location = "",
            mediaUri = ""
        )
        writeTasks(context, tasks(context) + task)
        record(context, "VOV Nghe & xem lại", task.title, "queued", "Đã thêm vào hàng đợi tải")
        return task
    }

    @Synchronized
    fun tasks(context: Context): List<PublicTask> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PUBLIC_QUEUE, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        PublicTask(
                            id = item.optString("id"),
                            url = item.optString("url"),
                            title = item.optString("title", "Chương trình VOV"),
                            pageUrl = item.optString("pageUrl"),
                            createdAt = item.optLong("createdAt"),
                            updatedAt = item.optLong("updatedAt"),
                            status = item.optString("status", "queued"),
                            detail = item.optString("detail"),
                            progress = item.optDouble("progress", 0.0).toFloat(),
                            location = item.optString("location"),
                            mediaUri = item.optString("mediaUri")
                        )
                    )
                }
            }.sortedBy { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun nextQueued(context: Context): PublicTask? {
        val current = tasks(context)
        if (current.any { it.status == "running" || it.status == "paused" }) return null
        val next = current.firstOrNull { it.status == "queued" } ?: return null
        update(context, next.id, status = "running", detail = "Đang chuẩn bị tải…", progress = 0f)
        return tasks(context).firstOrNull { it.id == next.id }
    }

    @Synchronized
    fun current(context: Context): PublicTask? =
        tasks(context).firstOrNull { it.status == "running" || it.status == "paused" }

    @Synchronized
    fun update(
        context: Context,
        id: String,
        status: String? = null,
        detail: String? = null,
        progress: Float? = null,
        location: String? = null,
        mediaUri: String? = null
    ) {
        val updated = tasks(context).map { task ->
            if (task.id != id) task else task.copy(
                updatedAt = System.currentTimeMillis(),
                status = status ?: task.status,
                detail = detail ?: task.detail,
                progress = progress ?: task.progress,
                location = location ?: task.location,
                mediaUri = mediaUri ?: task.mediaUri
            )
        }
        writeTasks(context, updated)
    }

    @Synchronized
    fun cancel(context: Context, id: String) {
        val task = tasks(context).firstOrNull { it.id == id } ?: return
        update(context, id, status = "cancelled", detail = "Đã hủy tải")
        record(context, "VOV Nghe & xem lại", task.title, "cancelled", "Người dùng đã hủy tác vụ")
    }

    @Synchronized
    fun retry(context: Context, id: String) {
        val task = tasks(context).firstOrNull { it.id == id } ?: return
        update(context, id, status = "queued", detail = "Đã đưa lại vào hàng đợi", progress = 0f, location = "", mediaUri = "")
        record(context, "VOV Nghe & xem lại", task.title, "retry", "Đã yêu cầu tải lại")
    }

    @Synchronized
    fun removeTask(context: Context, id: String) {
        writeTasks(context, tasks(context).filterNot { it.id == id })
    }

    @Synchronized
    fun recoverInterrupted(context: Context) {
        val recovered = tasks(context).map { task ->
            if (task.status == "running") task.copy(
                status = "queued",
                detail = "Đã khôi phục sau khi ứng dụng dừng",
                updatedAt = System.currentTimeMillis()
            ) else task
        }
        writeTasks(context, recovered)
    }

    @Synchronized
    fun record(
        context: Context,
        source: String,
        title: String,
        action: String,
        detail: String
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(KEY_JOURNAL, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val next = JSONArray()
        next.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("timestamp", System.currentTimeMillis())
                .put("source", source)
                .put("title", title.take(180))
                .put("action", action)
                .put("detail", detail.take(500))
        )
        val start = (old.length() - (MAX_JOURNAL_ENTRIES - 1)).coerceAtLeast(0)
        for (i in start until old.length()) next.put(old.getJSONObject(i))
        prefs.edit().putString(KEY_JOURNAL, next.toString()).apply()
    }

    @Synchronized
    fun journal(context: Context): List<JournalEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JOURNAL, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        JournalEntry(
                            id = item.optString("id"),
                            timestamp = item.optLong("timestamp"),
                            source = item.optString("source"),
                            title = item.optString("title"),
                            action = item.optString("action"),
                            detail = item.optString("detail")
                        )
                    )
                }
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clearJournal(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_JOURNAL).apply()
    }

    fun storage(context: Context): StorageSnapshot {
        var count = 0
        var publicBytes = 0L
        if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE
            )
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(Environment.DIRECTORY_DOWNLOADS + "/VOV/"),
                null
            )?.use { cursor ->
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    count++
                    publicBytes += cursor.getLong(sizeColumn).coerceAtLeast(0L)
                }
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "VOV")
            dir.walkTopDown().filter { it.isFile }.forEach {
                count++
                publicBytes += it.length()
            }
        }
        val cacheDir = File(context.filesDir, "vov-offline-cache")
        val cacheBytes = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return StorageSnapshot(
            publicFileCount = count,
            publicBytes = publicBytes,
            cacheBytes = cacheBytes,
            freeBytes = context.filesDir.usableSpace
        )
    }

    fun deleteOldFiles(context: Context, olderThanDays: Int = 30): Int {
        val cutoffSeconds = (System.currentTimeMillis() - olderThanDays.coerceAtLeast(1) * 86_400_000L) / 1000L
        var deleted = 0
        if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DATE_MODIFIED} < ?"
            val ids = mutableListOf<Long>()
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(Environment.DIRECTORY_DOWNLOADS + "/VOV/", cutoffSeconds.toString()),
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) ids += cursor.getLong(idColumn)
            }
            ids.forEach { id ->
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                if (context.contentResolver.delete(uri, null, null) > 0) deleted++
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "VOV")
            dir.walkTopDown().filter { it.isFile && it.lastModified() / 1000L < cutoffSeconds }
                .forEach { if (it.delete()) deleted++ }
        }
        return deleted
    }

    private fun writeTasks(context: Context, tasks: List<PublicTask>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("url", task.url)
                    .put("title", task.title)
                    .put("pageUrl", task.pageUrl)
                    .put("createdAt", task.createdAt)
                    .put("updatedAt", task.updatedAt)
                    .put("status", task.status)
                    .put("detail", task.detail)
                    .put("progress", task.progress.toDouble())
                    .put("location", task.location)
                    .put("mediaUri", task.mediaUri)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PUBLIC_QUEUE, array.toString()).apply()
    }
}
