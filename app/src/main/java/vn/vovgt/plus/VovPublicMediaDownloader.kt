package vn.vovgt.plus

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Downloader dedicated to the public VOV archive browser.
 * It is intentionally independent from VOV Media login/session and OfflineStore.
 */
internal object VovPublicMediaDownloader {
    data class Result(val displayName: String, val location: String)

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun download(
        context: Context,
        mediaUrl: String,
        title: String,
        pageUrl: String,
        onProgress: (String) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        require(mediaUrl.startsWith("https://", ignoreCase = true)) {
            "Chỉ hỗ trợ media HTTPS công khai từ trang VOV."
        }
        val lower = mediaUrl.substringBefore('?').lowercase(Locale.ROOT)
        when {
            lower.endsWith(".mp4") -> downloadMp4(context, mediaUrl, title, pageUrl, onProgress)
            lower.endsWith(".m3u8") -> downloadHls(context, mediaUrl, title, pageUrl, onProgress)
            lower.endsWith(".ts") -> throw IllegalArgumentException(
                "Đây thường là một đoạn TS của HLS, không phải video hoàn chỉnh. Hãy tải playlist M3U8 để ghép đủ các đoạn."
            )
            else -> throw IllegalArgumentException("Định dạng này chưa được hỗ trợ tải.")
        }
    }

    private fun request(url: String, pageUrl: String): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            .header("Referer", pageUrl)
            .header("Origin", "https://vov.gov.vn")
            .header("Accept", "*/*")
            .build()

    private fun downloadMp4(
        context: Context, url: String, title: String, pageUrl: String,
        onProgress: (String) -> Unit
    ): Result {
        return client.newCall(request(url, pageUrl)).execute().use { response ->
            if (!response.isSuccessful) error("Máy chủ VOV trả về HTTP ${response.code}.")
            val body = response.body ?: error("Máy chủ không trả dữ liệu.")
            val type = body.contentType()?.toString().orEmpty()
            if (type.contains("text/html", true)) error("Nguồn trả về trang HTML, không phải video MP4.")
            val name = safeName(title, "VOV-video") + ".mp4"
            val saved = writeToDownloads(context, name, "video/mp4") { output ->
                val input = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    VovPublicDownloadControl.awaitIfPaused()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    total += count
                    onProgress("Đang tải MP4: ${formatBytes(total)}")
                }
                if (total < 1024) error("File nhận được quá nhỏ, có thể không phải video hợp lệ.")
            }
            Result(name, saved)
        }
    }

    private fun downloadHls(
        context: Context, playlistUrl: String, title: String, pageUrl: String,
        onProgress: (String) -> Unit
    ): Result {
        var playlist = fetchText(playlistUrl, pageUrl)
        var base = playlistUrl.toHttpUrl()
        if (!playlist.startsWith("#EXTM3U")) error("Đường dẫn M3U8 không trả về playlist HLS hợp lệ.")

        // Master playlist: select the highest-bandwidth public variant.
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val lines = playlist.lines()
            val variants = mutableListOf<Pair<Long, String>>()
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)
                        ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }?.trim()
                    if (next != null) variants += bandwidth to (base.resolve(next)?.toString()
                        ?: error("URL playlist con không hợp lệ."))
                }
            }
            val selected = variants.maxByOrNull { it.first }?.second
                ?: error("Không tìm thấy playlist chất lượng trong M3U8.")
            playlist = fetchText(selected, pageUrl)
            base = selected.toHttpUrl()
            if (!playlist.startsWith("#EXTM3U")) error("Playlist chất lượng không hợp lệ.")
        }

        if (!playlist.contains("#EXT-X-ENDLIST")) {
            error("Đây là luồng trực tiếp hoặc playlist chưa kết thúc; không thể tải thành file hoàn chỉnh.")
        }
        if (playlist.lineSequence().any { it.trim().startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) {
            error("Playlist có mã hóa HLS. Ứng dụng không tải hoặc giải mã nội dung được bảo vệ.")
        }
        if (playlist.contains("#EXT-X-BYTERANGE")) {
            error("Playlist dùng byte-range; hiện chưa hỗ trợ ghép định dạng này.")
        }

        val mapUri = Regex("#EXT-X-MAP:.*URI=\\\"([^\\\"]+)\\\"")
            .find(playlist)?.groupValues?.getOrNull(1)?.let { base.resolve(it)?.toString() }
        val segments = playlist.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { base.resolve(it)?.toString() ?: error("URL phân đoạn HLS không hợp lệ.") }
            .toList()
        if (segments.isEmpty()) error("Playlist không có phân đoạn media.")
        if (segments.size > 20_000) error("Playlist có quá nhiều phân đoạn, đã dừng để tránh tải nhầm nội dung dài bất thường.")

        val isFmp4 = mapUri != null
        val ext = if (isFmp4) "mp4" else "ts"
        val mime = if (isFmp4) "video/mp4" else "video/mp2t"
        val name = safeName(title, "VOV-replay") + ".$ext"
        val saved = writeToDownloads(context, name, mime) { output ->
            if (mapUri != null) {
                onProgress("Đang tải phần khởi tạo video…")
                copyResource(mapUri, pageUrl, output)
            }
            segments.forEachIndexed { index, segment ->
                // Pause safely between segments; preserve playlist order.
                VovPublicDownloadControl.awaitIfPaused()
                copyResource(segment, pageUrl, output)
                onProgress("Đã ghép HLS: ${index + 1}/${segments.size}")
            }
        }
        return Result(name, saved)
    }

    private fun fetchText(url: String, pageUrl: String): String {
        client.newCall(request(url, pageUrl)).execute().use { response ->
            if (!response.isSuccessful) error("Không tải được playlist (HTTP ${response.code}).")
            val type = response.body?.contentType()?.toString().orEmpty()
            if (type.contains("text/html", true)) error("Máy chủ trả HTML thay vì playlist M3U8.")
            return response.body?.string() ?: error("Playlist rỗng.")
        }
    }

    private fun copyResource(url: String, pageUrl: String, output: OutputStream) {
        VovPublicDownloadControl.awaitIfPaused()
        client.newCall(request(url, pageUrl)).execute().use { response ->
            if (!response.isSuccessful) error("Không tải được một phân đoạn HLS (HTTP ${response.code}).")
            val body = response.body ?: error("Phân đoạn HLS rỗng.")
            body.byteStream().use { input -> input.copyTo(output, 64 * 1024) }
        }
    }


    private fun writeToDownloads(
        context: Context, name: String, mime: String, write: (OutputStream) -> Unit
    ): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VOV")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Không tạo được file trong thư mục Download.")
            try {
                val output = context.contentResolver.openOutputStream(uri, "w")
                    ?: error("Không mở được file đích.")
                output.use(write)
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
                return "Download/VOV"
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        }

        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "VOV")
        if (!directory.exists() && !directory.mkdirs()) error("Không tạo được thư mục tải.")
        val file = File(directory, name)
        try {
            file.outputStream().buffered().use(write)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file.absolutePath
    }

    private fun safeName(raw: String, fallback: String): String {
        val clean = raw.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
        return clean.ifBlank { fallback }
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes < 1024 * 1024) "${bytes / 1024} KB"
        else String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

}
