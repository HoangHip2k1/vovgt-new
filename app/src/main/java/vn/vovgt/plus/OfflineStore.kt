package vn.vovgt.plus

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * VOV Media credentials are never read or stored by our UI. Android WebView owns the
 * authentication cookies. OkHttp CookieJar supplies cookies only to matching origins,
 * including after redirects; cookies are NOT sent as global HTTP headers to all CDNs.
 */
@OptIn(UnstableApi::class)
object OfflineStore {
    private lateinit var app: Context
    private val db by lazy { StandaloneDatabaseProvider(app) }
    val cache: SimpleCache by lazy {
        SimpleCache(File(app.filesDir, "vov-offline-cache"), NoOpCacheEvictor(), db)
    }
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val webCookies = CookieManager.getInstance()
            for (cookie in cookies) webCookies.setCookie(url.toString(), cookie.toString())
            webCookies.flush()
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val value = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            return value.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
        }
    }
    private val httpClient by lazy { OkHttpClient.Builder().cookieJar(cookieJar).build() }
    fun init(context: Context) { app = context.applicationContext }
    fun upstream(): DataSource.Factory = OkHttpDataSource.Factory(httpClient)
        .setUserAgent("VOV-Giao-Thong-Plus/1.0 (Android)")
    fun playbackFactory(): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream())
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val manager: DownloadManager by lazy {
        DownloadManager(app, db, cache, upstream(), Executors.newFixedThreadPool(2)).apply {
            maxParallelDownloads = 2
        }
    }
    fun downloads(): List<Download> {
        val cursor = manager.downloadIndex.getDownloads()
        val items = mutableListOf<Download>()
        try { while (cursor.moveToNext()) items += cursor.download } finally { cursor.close() }
        // Running items have the freshest in-memory progress, while DownloadIndex
        // retains completed, failed and stopped items across app restarts.
        val latest = manager.currentDownloads.associateBy { it.request.id }
        return items.map { latest[it.request.id] ?: it }.sortedByDescending { it.startTimeMs }
    }
    fun enqueue(context: Context, url: String, title: String) {
        require(VovData.validHls(url)) { "Chỉ hỗ trợ link HTTPS .m3u8 hợp lệ" }
        require(!VovData.isLive(url)) { "Luồng trực tiếp chỉ hỗ trợ nghe, không tải lưu trữ" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val request = DownloadRequest.Builder(digest, Uri.parse(url))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setData(title.take(200).toByteArray(Charsets.UTF_8))
            .build()
        DownloadService.sendAddDownload(context, VovDownloadService::class.java, request, false)
    }
    fun title(download: Download): String = download.request.data
        .toString(Charsets.UTF_8).ifBlank { "Chương trình VOV" }
    fun remove(context: Context, id: String) = DownloadService.sendRemoveDownload(
        context, VovDownloadService::class.java, id, false
    )
    fun pause(context: Context) = DownloadService.sendPauseDownloads(context, VovDownloadService::class.java, false)
    fun resume(context: Context) = DownloadService.sendResumeDownloads(context, VovDownloadService::class.java, false)
}
