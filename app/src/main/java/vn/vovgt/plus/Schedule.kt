package vn.vovgt.plus

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Program(val start: String, val end: String, val title: String)
data class RadioChannel(val id: String, val name: String, val subtitle: String, val url: String)

object VovData {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
 
val channels = listOf(
    RadioChannel("HN", "Hà Nội", "VOV GT Hà Nội", "http://mediard.kythuatvov.vn:30083/"),
    RadioChannel("HCM", "TP. Hồ Chí Minh", "VOV GT TP.HCM", "http://mediard.kythuatvov.vn:30080/"),
    RadioChannel("MK", "Mekong", "VOV GT Mekong", "http://mediard.kythuatvov.vn:9085/"),
    RadioChannel("DH", "Duyên Hải", "VOV GT Duyên Hải", "http://mediard.kythuatvov.vn:9087")
)

    private val replaySlugs = mapOf("HN" to "vov-giao-thong-ha-noi", "HCM" to "vov-giao-thong-tp-hcm", "DH" to "vov-giao-thong-duyen-hai")
    fun programs(context: Context, channel: String, date: LocalDate): List<Program> {
        val json = JSONObject(context.assets.open("schedules.json").bufferedReader().use { it.readText() })
        val regional = if (channel == "MK" || channel == "DH") RegionalSchedules.programs(channel, date) else emptyList()
        if (regional.isNotEmpty()) return regional
        val day = json.optJSONObject(channel)?.optJSONArray((date.dayOfWeek.value % 7).toString()) ?: return emptyList()
        return (0 until day.length()).map { index ->
            val obj = day.getJSONObject(index)
            Program(obj.getString("start"), obj.getString("end"), obj.getString("title"))
        }
    }
    fun link(channel: String, date: LocalDate, program: Program): String {
        val slug = replaySlugs[channel] ?: error("Đài này chưa có lịch phát lại")
        fun epoch(time: String): Long = LocalDateTime.of(date, LocalTime.parse(time))
            .atZone(zone).toEpochSecond()
        val from = epoch(program.start)
        val to = if (program.end == "23:59") {
            // Original site uses 23:59 as last slot endpoint, not midnight.
            epoch("23:59")
        } else epoch(program.end)
        return "https://vovmedia.vn/replay/$slug/playlist.m3u8?from=$from&to=$to"
    }
    fun isFinished(date: LocalDate, p: Program): Boolean {
        val end = LocalDateTime.of(date, LocalTime.parse(p.end)).atZone(zone).toInstant()
        return java.time.Instant.now().isAfter(end)
    }
    fun isOnAir(date: LocalDate, p: Program): Boolean {
        val now = java.time.ZonedDateTime.now(zone)
        if (date != now.toLocalDate()) return false
        return !now.toLocalTime().isBefore(LocalTime.parse(p.start)) && now.toLocalTime().isBefore(LocalTime.parse(p.end))
    }

fun validPlayback(input: String): Boolean = try {
    val u = Uri.parse(input.trim())
    val host = u.host.orEmpty()

    val allowedHttpRadio =
        u.scheme == "http" &&
        host == "mediard.kythuatvov.vn" &&
        u.port in setOf(30083, 30080, 9085, 9087)

    val allowedHttpsHls =
        u.scheme == "https" &&
        host.isNotBlank() &&
        u.path?.endsWith(".m3u8", ignoreCase = true) == true

    (allowedHttpRadio || allowedHttpsHls) &&
        u.userInfoCompat() == null

} catch (_: Exception) {
    false
}

    // Keep the existing download validation: downloads still require HTTPS HLS playlists.
    fun validHls(input: String): Boolean = try {
        val u = Uri.parse(input.trim())
        u.scheme == "https" && !u.host.isNullOrBlank() && u.path?.endsWith(".m3u8") == true &&
            u.userInfoCompat() == null
    } catch (_: Exception) { false }

    private fun Uri.userInfoCompat(): String? = if (encodedAuthority?.contains('@') == true) "userinfo" else null
    fun isHls(input: String): Boolean = Uri.parse(input).path?.endsWith(".m3u8") == true
    fun isLive(input: String): Boolean = Uri.parse(input).path?.contains("/live/") == true ||
        Uri.parse(input).host == "mediard.kythuatvov.vn"
    fun dateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
}
