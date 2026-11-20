package vn.vovgt.plus

import android.Manifest
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Intent
import android.app.DownloadManager
import android.os.Environment
import android.webkit.WebChromeClient
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.exoplayer.offline.Download
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private val Cyan = Color(0xFF11C5DD)
private val Deep = Color(0xFF0B1630)
private val Royal = Color(0xFF157CF1)
private val Pale = Color(0xFFE7F8FB)
private val Background = Color(0xFFF5F8FC)
private val TextDark = Color(0xFF192944)
private val TextMuted = Color(0xFF69798C)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val playbackController = mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        setContent { VovPlusApp(playbackController.value) }
    }
    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, AudioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try { playbackController.value = future.get() }
            catch (_: Exception) { playbackController.value = null }
        }, ContextCompat.getMainExecutor(this))
    }
    override fun onStop() {
        playbackController.value = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }
}

private enum class Tab(val label: String) { LIVE("Trực tiếp"), SCHEDULE("Lịch phát"), VOV("VOV"), DOWNLOADS("Tải xuống"), JOURNAL("Nhật ký"), ACCOUNT("Tài khoản") }
private data class QueuedMedia(val url: String, val title: String)

@Composable
private fun VovPlusApp(player: MediaController?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vov_settings", Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", false)) }
    var confirmedSession by remember { mutableStateOf(prefs.getBoolean("session_confirmed", false)) }
    var selectedTab by remember { mutableStateOf(Tab.SCHEDULE) }
    var visitedTabs by remember { mutableStateOf(setOf(Tab.SCHEDULE)) }
    val selectTab: (Tab) -> Unit = { tab ->
        visitedTabs = visitedTabs + tab
        selectedTab = tab
    }
    var pending by remember { mutableStateOf<QueuedMedia?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val report: (String) -> Unit = { scope.launch { snackbar.showSnackbar(it) } }
    val download: (String, String) -> Unit = { url, title ->
        when {
            !VovData.validHls(url) -> report("Đường dẫn tải không hợp lệ. Chỉ hỗ trợ HTTPS .m3u8")
            VovData.isLive(url) -> report("Kênh trực tiếp chỉ hỗ trợ nghe. Hãy tải chương trình phát lại.")
            !confirmedSession -> {
                pending = QueuedMedia(url, title)
                selectTab(Tab.ACCOUNT)
                report("Hãy đăng nhập VOV Media trước khi tải.")
            }
            else -> try {
                OfflineStore.enqueue(context, url, title)
                selectTab(Tab.DOWNLOADS)
                report("Đã thêm vào danh sách tải. Bạn có thể thoát màn hình ứng dụng.")
            } catch (ex: Exception) { report(ex.message ?: "Không thể bắt đầu tải") }
        }
    }
    val listen: (String, String) -> Unit = { url, title ->
        val parsedUri = Uri.parse(url)
        val isLocal = parsedUri.scheme == "content" || parsedUri.scheme == "file"
        if (!isLocal && !VovData.validPlayback(url)) report("Link phát không hợp lệ")
        else if (player == null) report("Đang kết nối bộ phát âm thanh, thử lại sau.")
        else try {
            val mimeType = when {
                url.substringBefore('?').endsWith(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
                url.substringBefore('?').endsWith(".mp4", true) -> MimeTypes.VIDEO_MP4
                url.substringBefore('?').endsWith(".ts", true) -> MimeTypes.VIDEO_MP2T
                else -> MimeTypes.AUDIO_MPEG
            }
            val media = MediaItem.Builder().setUri(parsedUri).setMediaId(url)
                .setMimeType(mimeType)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("VOV Giao thông+").build())
                .build()
            player.setMediaItem(media)
            player.prepare()
            player.play()
            report("Đang mở: $title")
        } catch (ex: Exception) { report(ex.message ?: "Không thể phát luồng âm thanh") }
    }
    val colors = if (dark) darkColorScheme(
        primary = Cyan, onPrimary = Deep, background = Color(0xFF101827),
        surface = Color(0xFF1A2638), onSurface = Color(0xFFEAF1FC),
        onBackground = Color(0xFFEAF1FC), secondary = Cyan
    ) else lightColorScheme(
        primary = Royal, secondary = Cyan, background = Background,
        surface = Color.White, onSurface = TextDark, onBackground = TextDark
    )
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Deep)
                        .padding(start = 20.dp, end = 14.dp, top = 18.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(46.dp).background(Cyan, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Radio, contentDescription = null, tint = Deep, modifier = Modifier.size(29.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("VOV GIAO THÔNG+", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                        Text("RADIO  •  REPLAY  •  OFFLINE", color = Cyan, fontSize = 10.sp, letterSpacing = 1.3.sp)
                    }
                    IconButton(onClick = { dark = !dark; prefs.edit().putBoolean("dark", dark).apply() }) {
                        Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Chuyển chế độ sáng tối", tint = Color.White)
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { tab ->
                        val icon = when (tab) {
                            Tab.SCHEDULE -> Icons.Default.EventNote
                            Tab.LIVE -> Icons.Default.Podcasts
                            Tab.DOWNLOADS -> Icons.Default.Download
                            Tab.JOURNAL -> Icons.Default.History
                            Tab.VOV -> Icons.Default.Public
                            Tab.ACCOUNT -> Icons.Default.PersonOutline
                        }
                        NavigationBarItem(
                            selected = selectedTab == tab, onClick = { selectTab(tab) },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(tab.label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                Box(Modifier.weight(1f)) {
                    Tab.entries.filter { it in visitedTabs }.forEach { tab ->
                        Box(Modifier.then(if (selectedTab == tab) Modifier.fillMaxSize() else Modifier.size(0.dp))) {
                            when (tab) {
                        Tab.SCHEDULE -> ScheduleScreen(
                            onListen = listen, onDownload = download, report = report,
                            onCopy = { url ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("VOV replay URL", url))
                                report("Đã sao chép link phát lại")
                            }
                        )
                        Tab.LIVE -> LiveScreen(onListen = listen)
                        Tab.DOWNLOADS -> DownloadsScreen(onListen = listen, report = report, active = selectedTab == tab)
                        Tab.JOURNAL -> JournalScreen(active = selectedTab == tab)
                        Tab.VOV -> VovArchiveBrowserScreen(report = report)
                        Tab.ACCOUNT -> AccountScreen(
                            confirmed = confirmedSession,
                            onConfirmed = {
                                confirmedSession = true
                                prefs.edit().putBoolean("session_confirmed", true).apply()
                                report("Đã ghi nhận phiên VOV Media. Quyền tải sẽ được máy chủ kiểm tra.")
                                pending?.let { item ->
                                    pending = null
                                    download(item.url, item.title)
                                }
                            },
                            onLogout = {
                                confirmedSession = false
                                prefs.edit().putBoolean("session_confirmed", false).apply()
                                CookieManager.getInstance().removeAllCookies(null)
                                CookieManager.getInstance().flush()
                                report("Đã xóa phiên đăng nhập trên thiết bị")
                            }, report = report
                        )
                            }
                        }
                    }
                }
                if (player != null) PlayerStrip(player)
            }
        }
    }
}

@Composable
private fun VovArchiveBrowserScreen(report: (String) -> Unit) {
    val context = LocalContext.current
    var detected by remember { mutableStateOf("") }
    var mediaKind by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("VOV – Nghe và xem lại") }
    var pageUrl by remember { mutableStateOf("https://vov.gov.vn/nghe-va-xem-lai") }
    var progress by remember { mutableStateOf("") }
    var webError by remember { mutableStateOf("") }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageTitle = view?.title?.take(100)?.ifBlank { "VOV – Nghe và xem lại" }
                        ?: "VOV – Nghe và xem lại"
                    pageUrl = url ?: pageUrl
                    webError = ""
                }
                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        webError = error?.description?.toString() ?: "Không tải được trang VOV."
                    }
                }
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val url = request?.url?.toString().orEmpty()
                    val path = request?.url?.encodedPath?.lowercase().orEmpty()
                    val kind = when {
                        path.endsWith(".mp4") -> "MP4"
                        path.endsWith(".m3u8") -> "M3U8"
                        else -> ""
                    }
                    // Ignore .ts: these are commonly individual HLS chunks, not complete videos.
                    if (url.startsWith("https://") && kind.isNotEmpty()) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            detected = url
                            mediaKind = kind
                            progress = ""
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            loadUrl("https://vov.gov.vn/nghe-va-xem-lai")
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxWidth().background(Deep).padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).background(Cyan, RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Podcasts, contentDescription = null, tint = Deep)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("VOV NGHE & XEM LẠI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Nguồn nội dung chính thức • Không cần đăng nhập", color = Color(0xFFB8D9E8), fontSize = 11.sp)
                }
                IconButton(onClick = {
                    detected = ""; mediaKind = ""; progress = ""; webError = ""
                    webView.reload()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Tải lại", tint = Color.White)
                }
            }
        }

        if (detected.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Royal)
                        Spacer(Modifier.width(8.dp))
                        Text("Đã tìm thấy nội dung có thể tải", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Surface(color = Pale, shape = RoundedCornerShape(8.dp)) {
                            Text(mediaKind, color = Deep, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                        }
                    }
                    Text(pageTitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(context, VovPublicDownloadService::class.java).apply {
                                    action = VovPublicDownloadService.ACTION_ENQUEUE
                                    putExtra(VovPublicDownloadService.EXTRA_URL, detected)
                                    putExtra(VovPublicDownloadService.EXTRA_TITLE, pageTitle)
                                    putExtra(VovPublicDownloadService.EXTRA_PAGE, pageUrl)
                                }
                                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                                progress = "Đã bắt đầu tải nền. Bạn có thể chuyển sang tab khác; tiến trình hiển thị trên thông báo."
                                report("Đã bắt đầu tải nội dung VOV ở chế độ nền.")
                            } catch (e: Exception) {
                                progress = e.message ?: "Không thể bắt đầu tải nền."
                                report(progress)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tải nền vào Download/VOV")
                    }
                    if (progress.isNotBlank()) {
                        Text(progress, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "Không hiển thị TS riêng lẻ vì đó thường chỉ là một đoạn nhỏ của video HLS.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Royal)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "Mở một chương trình và nhấn phát. Khi tìm thấy MP4 hoặc playlist M3U8 công khai, nút tải sẽ xuất hiện. Tải tiếp tục khi bạn chuyển tab.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (webError.isNotBlank()) {
            Text("Không mở được trang VOV: $webError", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }
        AndroidView(factory = { webView }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        shadowElevation = 1.dp, content = { Column(Modifier.padding(16.dp), content = content) })
}

@Composable
private fun ChannelChips(selected: String, onSelected: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        VovData.channels
            .forEach { ch ->
                FilterChip(selected = selected == ch.id, onClick = { onSelected(ch.id) },
                    label = { Text(ch.name, maxLines = 1, fontSize = 12.sp) })
            }
    }
}

@Composable
private fun ScheduleScreen(
    onListen: (String, String) -> Unit,
    onDownload: (String, String) -> Unit,
    onCopy: (String) -> Unit,
    report: (String) -> Unit
) {
    val context = LocalContext.current
    var ch by remember { mutableStateOf("HN") }
    var date by remember { mutableStateOf(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))) }
    var pasted by remember { mutableStateOf("") }
    val programs = remember(ch, date) { VovData.programs(context, ch, date) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            SectionTitle("Lịch phát & nghe lại", "Chọn chương trình để nghe thử hoặc lưu offline")
            Spacer(Modifier.height(14.dp))
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("CHỌN KHU VỰC", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                ChannelChips(ch) { ch = it }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) },
                        date.year, date.monthValue - 1, date.dayOfMonth).show()
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ngày ${VovData.dateLabel(date)}")
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
            }
            Spacer(Modifier.height(12.dp))
            GlassCard {
                Text("Dán link phát lại hoặc link .m3u8", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pasted, onValueChange = { pasted = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://…/playlist.m3u8") },
                    minLines = 1, maxLines = 3, textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onListen(pasted.trim(), "Nghe thử link đã dán") }, Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Text("Nghe thử")
                    }
                    Button(onClick = { onDownload(pasted.trim(), "Phát lại từ link đã dán") }, Modifier.weight(1f)) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Text("Tải xuống")
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("CHƯƠNG TRÌNH (${programs.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
        }
        if (programs.isEmpty()) {
            item {
                GlassCard {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(7.dp))
                    Text("Chưa có lịch phát lại cho khu vực này", fontWeight = FontWeight.Bold)
                    Text("Bạn vẫn có thể nghe trực tiếp ở tab Trực tiếp.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(programs, key = { "${it.start}-${it.title}" }) { program ->
                val replayAvailable = ch != "MK"
                val url = remember(ch, date, program) { if (replayAvailable) VovData.link(ch, date, program) else "" }
                ProgramCard(program = program, isLive = VovData.isOnAir(date, program),
                    finished = VovData.isFinished(date, program), canReplay = replayAvailable,
                    onCopy = { onCopy(url) },
                    onPlay = { onListen(url, program.title) },
                    onDownload = { onDownload(url, program.title) })
            }
        }
        item {
            Text("Lịch phát lấy từ HTML bạn cung cấp; đây không phải kiểm tra file trên máy chủ. " +
                "Chương trình đã phát có thể không còn được VOV Media cung cấp.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProgramCard(
    program: Program, isLive: Boolean, finished: Boolean, canReplay: Boolean = true,
    onCopy: () -> Unit, onPlay: () -> Unit, onDownload: () -> Unit
) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(
                if (isLive) Color(0xFFFFE8E8) else MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(if (isLive) Icons.Default.GraphicEq else Icons.Default.Headphones,
                    contentDescription = null, tint = if (isLive) Color(0xFFD83948) else MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(program.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp)
                Spacer(Modifier.height(3.dp))
                Text("${program.start} – ${if (program.end == "23:59") "23:59" else program.end}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (isLive) {
                Surface(color = Color(0xFFFFE1E3), shape = RoundedCornerShape(8.dp)) {
                    Text("● LIVE", color = Color(0xFFC02632), fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 10.sp)
                }
            } else if (finished) {
                Text("Đã phát", color = Color(0xFF1BA977), fontSize = 11.sp)
            } else {
                Text("Sắp phát", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCopy, enabled = canReplay, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Sao chép link", modifier = Modifier.size(19.dp))
            }
            OutlinedButton(onClick = onPlay, modifier = Modifier.weight(1f), enabled = finished && canReplay) {
                Icon(Icons.Default.PlayCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Nghe thử", fontSize = 12.sp)
            }
            Button(onClick = onDownload, modifier = Modifier.weight(1f), enabled = finished && canReplay) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (canReplay) "Tải" else "Chưa có link", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun LiveScreen(onListen: (String, String) -> Unit) {
    val gradient = Brush.linearGradient(listOf(Color(0xFF11285B), Color(0xFF0A9FC4)))
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Box(Modifier.fillMaxWidth().background(gradient, RoundedCornerShape(22.dp)).padding(23.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sensors, contentDescription = null, tint = Cyan)
                        Spacer(Modifier.width(8.dp))
                        Text("ĐANG PHÁT SÓNG", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Lắng nghe mọi nẻo đường", color = Color.White, fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold)
                    Text("4 kênh phát thanh trực tiếp · HLS / MP3", color = Color.White.copy(alpha = .83f))
                }
            }
            Spacer(Modifier.height(16.dp))
            SectionTitle("Chọn đài phát thanh", "Nhấn nghe để bắt đầu, có thể phát khi khóa màn hình")
        }
        items(VovData.channels, key = { it.id }) { channel ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(61.dp).background(MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(19.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(channel.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(channel.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("● PHÁT TRỰC TIẾP", fontSize = 10.sp, color = Color(0xFFE04C53),
                            fontWeight = FontWeight.Bold)
                    }
                    FilledIconButton(onClick = { onListen(channel.url, channel.subtitle) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Nghe ${channel.name}")
                    }
                }
            }
        }
        item { Text("Lưu ý: nghe trực tiếp không đồng nghĩa ghi lại toàn bộ luồng phát sóng. " +
            "Tính năng tải dành cho nội dung phát lại đã kết thúc.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DownloadsScreen(onListen: (String, String) -> Unit, report: (String) -> Unit, active: Boolean) {
    val context = LocalContext.current
    var mediaDownloads by remember { mutableStateOf<List<Download>>(emptyList()) }
    var publicTasks by remember { mutableStateOf(DownloadCenterStore.tasks(context)) }
    var storage by remember { mutableStateOf(DownloadCenterStore.storage(context)) }
    var lastQueueStartAttempt by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        val manager = OfflineStore.manager
        val listener = object : androidx.media3.exoplayer.offline.DownloadManager.Listener {
            override fun onDownloadChanged(manager: androidx.media3.exoplayer.offline.DownloadManager, download: Download, finalException: Exception?) {
                mediaDownloads = OfflineStore.downloads()
            }
            override fun onDownloadRemoved(manager: androidx.media3.exoplayer.offline.DownloadManager, download: Download) {
                mediaDownloads = OfflineStore.downloads()
            }
        }
        manager.addListener(listener)
        onDispose { manager.removeListener(listener) }
    }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val queued = DownloadCenterStore.tasks(context).any { it.status == "queued" }
        if (queued) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, VovPublicDownloadService::class.java).setAction(VovPublicDownloadService.ACTION_PROCESS_QUEUE)
                )
            } catch (_: Exception) { }
        }
        while (true) {
            try {
                mediaDownloads = OfflineStore.downloads()
                val queueSnapshot = DownloadCenterStore.tasks(context)
                publicTasks = queueSnapshot
                storage = DownloadCenterStore.storage(context)
                val now = System.currentTimeMillis()
                if (queueSnapshot.any { it.status == "queued" } &&
                    queueSnapshot.none { it.status == "running" || it.status == "paused" } &&
                    now - lastQueueStartAttempt > 5_000L
                ) {
                    lastQueueStartAttempt = now
                    try {
                        androidx.core.content.ContextCompat.startForegroundService(
                            context,
                            Intent(context, VovPublicDownloadService::class.java)
                                .setAction(VovPublicDownloadService.ACTION_PROCESS_QUEUE)
                        )
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            delay(1000)
        }
    }

    fun sendPublicAction(action: String, taskId: String? = null) {
        val intent = Intent(context, VovPublicDownloadService::class.java).setAction(action)
        if (taskId != null) intent.putExtra(VovPublicDownloadService.EXTRA_TASK_ID, taskId)
        try {
            if (action == VovPublicDownloadService.ACTION_PAUSE ||
                action == VovPublicDownloadService.ACTION_CANCEL ||
                action == VovPublicDownloadService.ACTION_REMOVE
            ) {
                context.startService(intent)
            } else {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
        } catch (error: Exception) {
            report(error.message ?: "Không thể điều khiển tác vụ tải")
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionTitle("Quản lý tải xuống", "Hàng đợi, nội dung offline và dung lượng lưu trữ")
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Storage Manager", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${storage.publicFileCount} file", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Tệp đã tải công khai", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatDownloadBytes(storage.publicBytes), fontWeight = FontWeight.SemiBold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Cache offline", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatDownloadBytes(storage.cacheBytes), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    LinearProgressIndicator(
                        progress = {
                            val used = storage.publicBytes + storage.cacheBytes
                            if (storage.freeBytes + used > 0L) (used.toFloat() / (storage.freeBytes + used).toFloat()).coerceIn(0f, 1f) else 0f
                        },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                    Text("Bộ nhớ còn trống: ${formatDownloadBytes(storage.freeBytes)}", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = {
                            val cutoff = System.currentTimeMillis() - 30L * 86_400_000L
                            val oldMedia = mediaDownloads.filter {
                                it.state == Download.STATE_COMPLETED && it.startTimeMs in 1 until cutoff
                            }
                            oldMedia.forEach { OfflineStore.remove(context, it.request.id) }
                            val removedFiles = DownloadCenterStore.deleteOldFiles(context, 30)
                            val oldTasks = publicTasks.filter {
                                it.status == "completed" && it.updatedAt in 1 until cutoff
                            }
                            oldTasks.forEach { DownloadCenterStore.removeTask(context, it.id) }
                            DownloadCenterStore.record(
                                context, "Storage Manager", "Dọn dẹp bộ nhớ", "cleanup",
                                "Đã xóa ${oldMedia.size + removedFiles} tệp/nội dung cũ hơn 30 ngày"
                            )
                            report("Đã yêu cầu dọn ${oldMedia.size + removedFiles} nội dung cũ hơn 30 ngày")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Xóa nội dung cũ hơn 30 ngày")
                    }
                }
            }
        }

        item {
            val waiting = publicTasks.count { it.status == "queued" }
            val active = publicTasks.count { it.status == "running" || it.status == "paused" }
            SectionTitle("Download Queue", "${active} đang xử lý · ${waiting} đang chờ")
        }

        if (publicTasks.isEmpty()) {
            item {
                GlassCard {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CloudDownload, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("Hàng đợi VOV đang trống", fontWeight = FontWeight.Bold)
                        Text("Thêm chương trình từ tab VOV để tải nhiều nội dung lần lượt.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }

        items(publicTasks.sortedWith(compareBy<DownloadCenterStore.PublicTask> {
            when (it.status) {
                "running" -> 0
                "paused" -> 1
                "queued" -> 2
                "failed" -> 3
                "completed" -> 4
                else -> 5
            }
        }.thenBy { it.createdAt }), key = { it.id }) { task ->
            val statusLabel = when (task.status) {
                "queued" -> "Đang chờ"
                "running" -> "Đang tải"
                "paused" -> "Đã tạm dừng"
                "completed" -> "Đã tải xong"
                "failed" -> "Tải thất bại"
                "cancelled" -> "Đã hủy"
                else -> task.status
            }
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (task.status) {
                            "completed" -> Icons.Default.CheckCircle
                            "failed" -> Icons.Default.ErrorOutline
                            "paused" -> Icons.Default.PauseCircle
                            else -> Icons.Default.Download
                        },
                        null,
                        tint = when (task.status) {
                            "completed" -> Color(0xFF15B78D)
                            "failed" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(task.title, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text("VOV Nghe & xem lại · $statusLabel", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (task.status == "running" && task.progress >= 0f) {
                        Text("${(task.progress * 100).toInt()}%", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (task.status == "running" || task.status == "paused") {
                    LinearProgressIndicator(
                        progress = { task.progress.coerceAtLeast(0f).coerceAtMost(1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                }
                if (task.detail.isNotBlank()) {
                    Text(task.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (task.status) {
                        "running" -> TextButton(onClick = { sendPublicAction(VovPublicDownloadService.ACTION_PAUSE) }) {
                            Icon(Icons.Default.Pause, null); Text("Tạm dừng")
                        }
                        "paused" -> TextButton(onClick = { sendPublicAction(VovPublicDownloadService.ACTION_RESUME) }) {
                            Icon(Icons.Default.PlayArrow, null); Text("Tiếp tục")
                        }
                        "failed", "cancelled" -> TextButton(onClick = { sendPublicAction(VovPublicDownloadService.ACTION_RETRY, task.id) }) {
                            Icon(Icons.Default.Refresh, null); Text("Thử lại")
                        }
                        "completed" -> if (task.mediaUri.isNotBlank()) {
                            TextButton(onClick = { onListen(task.mediaUri, task.title) }) {
                                Icon(Icons.Default.PlayArrow, null); Text("Nghe offline")
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (task.status != "running" && task.status != "paused") {
                        TextButton(onClick = {
                            if (task.status == "queued") sendPublicAction(VovPublicDownloadService.ACTION_CANCEL, task.id)
                            else {
                                DownloadCenterStore.removeTask(context, task.id)
                                publicTasks = DownloadCenterStore.tasks(context)
                            }
                        }) {
                            Icon(Icons.Default.DeleteOutline, null, Modifier.size(17.dp))
                            Text(if (task.status == "queued") "Hủy" else "Xóa")
                        }
                    } else {
                        TextButton(onClick = { sendPublicAction(VovPublicDownloadService.ACTION_CANCEL, task.id) }) {
                            Icon(Icons.Default.Close, null, Modifier.size(17.dp)); Text("Hủy")
                        }
                    }
                }
            }
        }

        item {
            SectionTitle("VOV Media", "Tải HLS bằng Media3 · hỗ trợ nhiều tác vụ song song")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { OfflineStore.pause(context); report("Đã tạm dừng hàng đợi VOV Media") }) {
                    Icon(Icons.Default.Pause, null, Modifier.size(17.dp)); Text("Tạm dừng")
                }
                OutlinedButton(onClick = { OfflineStore.resume(context); report("Đã tiếp tục hàng đợi VOV Media") }) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(17.dp)); Text("Tiếp tục")
                }
            }
        }

        if (mediaDownloads.isEmpty()) {
            item {
                GlassCard {
                    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Chưa có nội dung VOV Media", fontWeight = FontWeight.Bold)
                        Text("Chọn chương trình trong tab Lịch phát để lưu offline.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }

        items(mediaDownloads, key = { "media-${it.request.id}" }) { d ->
            val completed = d.state == Download.STATE_COMPLETED
            val label = when (d.state) {
                Download.STATE_COMPLETED -> "Đã hoàn tất · nghe offline"
                Download.STATE_DOWNLOADING -> "Đang tải"
                Download.STATE_QUEUED -> "Đang chờ"
                Download.STATE_STOPPED -> "Đã tạm dừng"
                Download.STATE_FAILED -> "Tải lỗi · kiểm tra quyền truy cập / kết nối"
                Download.STATE_REMOVING -> "Đang xóa"
                Download.STATE_RESTARTING -> "Đang tải lại"
                else -> "Đang chuẩn bị"
            }
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (completed) Icons.Default.CheckCircle else Icons.Default.Download,
                        null, tint = if (completed) Color(0xFF15B78D) else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(OfflineStore.title(d), fontWeight = FontWeight.Bold, maxLines = 2)
                        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (completed) {
                            val exportStatus = AudioExportState.status(context, d.request.id)
                            val exportLabel = when (exportStatus) {
                                "queued" -> "Đang chờ xuất file M4A…"
                                "exporting" -> "Đang tạo file M4A…"
                                "done" -> "Đã lưu: Download/VOV Giao Thong+"
                                "failed" -> "Xuất M4A lỗi: ${AudioExportState.error(context, d.request.id)}"
                                else -> "Đang chờ xuất file M4A"
                            }
                            Text(exportLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(if (completed) "100%" else if (d.percentDownloaded >= 0) "${d.percentDownloaded.toInt()}%" else "…",
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(9.dp))
                LinearProgressIndicator(
                    progress = { if (completed) 1f else (d.percentDownloaded.coerceAtLeast(0f) / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (completed) "${formatDownloadBytes(d.bytesDownloaded)} đã lưu · Hoàn tất"
                    else if (d.contentLength > 0L) "${formatDownloadBytes(d.bytesDownloaded)} / ${formatDownloadBytes(d.contentLength)} · ${if (d.percentDownloaded >= 0) String.format("%.1f", d.percentDownloaded) + "%" else "Đang tính"}"
                    else "${formatDownloadBytes(d.bytesDownloaded)} đã tải · ${if (d.percentDownloaded >= 0) String.format("%.1f", d.percentDownloaded) + "%" else "Đang tính"}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (completed) {
                        TextButton(onClick = { onListen(d.request.uri.toString(), OfflineStore.title(d)) }) {
                            Icon(Icons.Default.PlayArrow, null); Text("Nghe offline")
                        }
                        val exportedUri = AudioExportState.uri(context, d.request.id)
                        if (exportedUri != null) {
                            TextButton(onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(exportedUri, "audio/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    })
                                } catch (_: Exception) { report("Không tìm thấy ứng dụng mở file M4A") }
                            }) {
                                Icon(Icons.Default.FolderOpen, null); Text("Mở file")
                            }
                        } else if (AudioExportState.status(context, d.request.id) !in setOf("queued", "exporting")) {
                            TextButton(onClick = {
                                AudioExportState.request(context, d, force = true)
                                report("Đã yêu cầu xuất file M4A")
                            }) {
                                Icon(Icons.Default.SaveAlt, null); Text("Xuất M4A")
                            }
                        }
                    }
                    if (d.state == Download.STATE_FAILED) {
                        TextButton(onClick = {
                            try { OfflineStore.enqueue(context, d.request.uri.toString(), OfflineStore.title(d)) }
                            catch (e: Exception) { report(e.message ?: "Không thể tải lại") }
                        }) { Icon(Icons.Default.Refresh, null); Text("Thử lại") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { OfflineStore.remove(context, d.request.id); report("Đã yêu cầu xóa bản tải") }) {
                        Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp)); Text("Xóa")
                    }
                }
            }
        }
        item {
            Text(
                "Nội dung đã tải được lưu riêng theo từng nguồn. HLS VOV Media phát từ bộ nhớ offline trong ứng dụng; MP4/TS công khai được lưu trong Download/VOV.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun JournalScreen(active: Boolean) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(DownloadCenterStore.journal(context)) }
    var filter by remember { mutableStateOf("all") }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            entries = DownloadCenterStore.journal(context)
            delay(1200)
        }
    }

    val visibleEntries = entries.filter {
        filter == "all" ||
            (filter == "media" && it.source == "VOV Media") ||
            (filter == "public" && it.source == "VOV Nghe & xem lại")
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("Nhật ký tải xuống", "Lưu chi tiết các sự kiện tải, tạm dừng, thử lại và hoàn tất")
                }
                IconButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Xóa nhật ký")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("Tất cả") })
                FilterChip(selected = filter == "media", onClick = { filter = "media" }, label = { Text("VOV Media") })
                FilterChip(selected = filter == "public", onClick = { filter = "public" }, label = { Text("VOV công khai") })
            }
        }

        if (visibleEntries.isEmpty()) {
            item {
                GlassCard {
                    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.History, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("Chưa có hoạt động nào", fontWeight = FontWeight.Bold)
                        Text("Các sự kiện tải xuống sẽ được ghi lại tại đây.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        items(visibleEntries, key = { it.id }) { entry ->
            val actionLabel = when (entry.action) {
                "queued" -> "Đã xếp hàng"
                "started" -> "Bắt đầu tải"
                "paused" -> "Tạm dừng"
                "resumed" -> "Tiếp tục"
                "completed" -> "Hoàn tất"
                "failed" -> "Thất bại"
                "cancelled" -> "Đã hủy"
                "retry" -> "Thử tải lại"
                "retrying" -> "Đang thử lại"
                "removing" -> "Đang xóa"
                else -> entry.action
            }
            val tint = when (entry.action) {
                "completed" -> Color(0xFF15B78D)
                "failed" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            GlassCard {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        when (entry.action) {
                            "completed" -> Icons.Default.CheckCircle
                            "failed" -> Icons.Default.ErrorOutline
                            "paused" -> Icons.Default.PauseCircle
                            "cancelled", "removing" -> Icons.Default.DeleteOutline
                            else -> Icons.Default.History
                        },
                        null, tint = tint, modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text("$actionLabel · ${entry.source}", fontSize = 11.sp, color = tint, fontWeight = FontWeight.Medium)
                        Text(entry.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale("vi", "VN"))
                                .format(java.util.Date(entry.timestamp)),
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Xóa toàn bộ nhật ký?") },
            text = { Text("Thao tác này chỉ xóa lịch sử sự kiện, không xóa các file đã tải.") },
            confirmButton = {
                TextButton(onClick = {
                    DownloadCenterStore.clearJournal(context)
                    entries = emptyList()
                    confirmClear = false
                }) { Text("Xóa nhật ký") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Giữ lại") } }
        )
    }
}

@Composable
private fun AccountScreen(confirmed: Boolean, onConfirmed: () -> Unit, onLogout: () -> Unit, report: (String) -> Unit) {
    val context = LocalContext.current
    var browserOpen by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf("https://vovmedia.vn/login") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Tài khoản VOV Media", "Đăng nhập trên trang chính thức để truy cập nội dung phát lại")
        Spacer(Modifier.height(16.dp))
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (confirmed) Icons.Default.VerifiedUser else Icons.Default.Lock, null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(if (confirmed) "Đã ghi nhận phiên đăng nhập" else "Cần đăng nhập trước khi tải",
                        fontWeight = FontWeight.Bold)
                    Text(if (confirmed) "Phiên có thể hết hạn; VOV Media quyết định quyền truy cập."
                        else "Ứng dụng không đọc hoặc lưu mật khẩu của bạn.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { browserOpen = !browserOpen }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Login, null)
                Spacer(Modifier.width(8.dp))
                Text(if (browserOpen) "Đóng trang đăng nhập" else "Mở VOV Media để đăng nhập")
            }
            if (confirmed) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { browserOpen = false; onLogout() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Đăng xuất và xóa cookie")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (browserOpen) {
            GlassCard {
                Text("vovmedia.vn/login", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Hãy đăng nhập trong khung website dưới đây, rồi nhấn Xác nhận.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                AndroidView(factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val uri = request?.url ?: return true
                                val host = uri.host.orEmpty()
                                return if (uri.scheme == "https" && (host == "vovmedia.vn" || host.endsWith(".vovmedia.vn"))) {
                                    false
                                } else {
                                    // Never load untrusted websites inside the credential WebView.
                                    if (uri.scheme == "https") {
                                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                    }
                                    true
                                }
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (url != null) currentUrl = url
                            }
                        }
                        loadUrl("https://vovmedia.vn/login")
                    }
                }, modifier = Modifier.fillMaxWidth().height(370.dp))
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    val host = Uri.parse(currentUrl).host.orEmpty()
                    val cookies = CookieManager.getInstance().getCookie("https://vovmedia.vn")
                    if (host != "vovmedia.vn" || currentUrl.substringBefore('?').endsWith("/login")) {
                        report("Hãy hoàn thành đăng nhập và rời trang /login trước khi xác nhận.")
                    } else if (cookies.isNullOrBlank()) {
                        report("Không tìm thấy cookie phiên trên VOV Media. Hãy thử đăng nhập lại.")
                    } else {
                        CookieManager.getInstance().flush()
                        browserOpen = false
                        onConfirmed()
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Check, null)
                    Text(" Xác nhận đã đăng nhập")
                }
                Text("Lưu ý: đây là xác nhận phiên trình duyệt, chưa phải xác thực tài khoản qua API. " +
                    "Nếu máy chủ trả lỗi 401/403, hãy đăng nhập lại.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassCard {
            Text("Quyền riêng tư", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text("• Tài khoản và mật khẩu nhập trực tiếp trên website VOV Media.\n" +
                "• Ứng dụng không thu thập mật khẩu. Cookie do WebView quản lý.\n" +
                "• Link phát lại và quyền truy cập do hệ thống VOV Media kiểm soát.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlayerStrip(player: MediaController) {
    val context = LocalContext.current
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var label by remember(player) { mutableStateOf(player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()) }
    var error by remember(player) { mutableStateOf<String?>(null) }
    var expanded by remember(player) { mutableStateOf(false) }
    var position by remember(player) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var duration by remember(player) { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var timerEnd by remember { mutableLongStateOf(context.getSharedPreferences("vov_sleep_timer", Context.MODE_PRIVATE).getLong("end", 0L)) }
    var remaining by remember { mutableLongStateOf((timerEnd - System.currentTimeMillis()).coerceAtLeast(0L)) }
    var showTimer by remember { mutableStateOf(false) }
    var timerMinutes by remember { mutableFloatStateOf(30f) }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }
    LaunchedEffect(timerEnd) {
        while (timerEnd > 0L) {
            val left = timerEnd - System.currentTimeMillis()
            remaining = left.coerceAtLeast(0L)
            if (left <= 0L) {
                player.pause()
                timerEnd = 0L
                context.getSharedPreferences("vov_sleep_timer", Context.MODE_PRIVATE).edit().remove("end").apply()
                break
            }
            delay(1000)
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                label = mediaItem?.mediaMetadata?.title?.toString().orEmpty()
                error = null
                position = 0L
                duration = player.duration.coerceAtLeast(0L)
            }
            override fun onPlayerError(playerError: androidx.media3.common.PlaybackException) {
                error = "Không phát được: ${playerError.errorCodeName}. Kiểm tra mạng / quyền truy cập."
            }
            override fun onPlaybackStateChanged(state: Int) {
                duration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    if (label.isNotBlank() || error != null) {
        Surface(color = Deep, shadowElevation = 7.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(Cyan, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Deep)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (error == null) label else "Lỗi phát âm thanh", color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(error ?: if (playing) "Đang phát" else "Tạm dừng",
                            color = Cyan, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = "Mở rộng trình phát", tint = Color.White)
                    }
                    IconButton(onClick = { if (playing) player.pause() else player.play() }) {
                        Icon(if (playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                            contentDescription = if (playing) "Tạm dừng" else "Phát", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = { player.stop(); player.clearMediaItems(); label = ""; error = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Dừng nghe", tint = Color.White)
                    }
                }
                if (expanded) {
                    Spacer(Modifier.height(7.dp))
                    Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (duration > 0L && duration < 24L * 60L * 60L * 1000L) {
                        Slider(
                            value = position.coerceAtMost(duration).toFloat(),
                            onValueChange = { position = it.toLong() },
                            onValueChangeFinished = { player.seekTo(position.coerceIn(0L, duration)) },
                            valueRange = 0f..duration.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatPlayerTime(position), color = Color.White.copy(alpha = .8f), fontSize = 11.sp)
                            Text(formatPlayerTime(duration), color = Color.White.copy(alpha = .8f), fontSize = 11.sp)
                        }
                    } else {
                        Text("Luồng trực tiếp · không hỗ trợ tua", color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { player.seekBack() }, enabled = duration > 0L) {
                            Icon(Icons.Default.Replay10, null, tint = Color.White)
                        }
                        IconButton(onClick = { if (playing) player.pause() else player.play() }) {
                            Icon(if (playing) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                                null, tint = Cyan, modifier = Modifier.size(42.dp))
                        }
                        IconButton(onClick = { player.seekForward() }, enabled = duration > 0L) {
                            Icon(Icons.Default.Forward10, null, tint = Color.White)
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = .2f))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bedtime, tint = Cyan, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Hẹn giờ tắt", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(if (timerEnd > 0L) "Còn lại ${formatPlayerTime(remaining)}" else "Chưa đặt hẹn giờ",
                                color = Cyan, fontSize = 11.sp)
                        }
                        TextButton(onClick = { showTimer = true }) { Text("Đặt giờ", color = Cyan) }
                        if (timerEnd > 0L) {
                            TextButton(onClick = {
                                timerEnd = 0L
                                context.getSharedPreferences("vov_sleep_timer", Context.MODE_PRIVATE).edit().remove("end").apply()
                                context.startService(Intent(context, AudioService::class.java).apply {
                                    action = AudioService.ACTION_CANCEL_SLEEP_TIMER
                                })
                            }) { Text("Hủy", color = Color.White) }
                        }
                    }
                }
            }
        }
    }
    if (showTimer) {
        AlertDialog(
            onDismissRequest = { showTimer = false },
            title = { Text("Hẹn giờ tắt phát") },
            text = {
                Column {
                    Text("Ứng dụng sẽ tự dừng phát sau thời gian đã chọn.")
                    Spacer(Modifier.height(12.dp))
                    Text("${timerMinutes.toInt()} phút", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Slider(value = timerMinutes, onValueChange = { timerMinutes = it },
                        valueRange = 5f..180f, steps = 34)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("5 phút", style = MaterialTheme.typography.labelSmall)
                        Text("180 phút", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    timerEnd = System.currentTimeMillis() + timerMinutes.toLong() * 60_000L
                    context.getSharedPreferences("vov_sleep_timer", Context.MODE_PRIVATE).edit()
                        .putLong("end", timerEnd).apply()
                    context.startService(Intent(context, AudioService::class.java).apply {
                        action = AudioService.ACTION_SET_SLEEP_TIMER
                        putExtra(AudioService.EXTRA_DELAY_MS, timerMinutes.toLong() * 60_000L)
                    })
                    showTimer = false
                }) { Text("Bắt đầu đếm ngược") }
            },
            dismissButton = { TextButton(onClick = { showTimer = false }) { Text("Đóng") } }
        )
    }
}

private fun formatPlayerTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}


private fun formatDownloadBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 * 1024.0 -> String.format("%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024.0 * 1024.0 -> String.format("%.1f MB", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format("%.0f KB", value / 1024.0)
        else -> "$bytes B"
    }
}
