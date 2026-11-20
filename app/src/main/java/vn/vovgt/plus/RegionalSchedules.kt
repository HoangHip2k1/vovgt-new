package vn.vovgt.plus

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Regional schedules transcribed from the two schedule files supplied by the user.
 * Mekong's Wednesday evening title is taken from the second consecutive day block
 * in the supplied text; the recurring weekday mapping follows that sample week.
 */
object RegionalSchedules {
    private fun parse(raw: String): List<Program> = raw.trimIndent()
        .lineSequence()
        .filter { it.isNotBlank() }
        .map { line ->
            val fields = line.split('|', limit = 3)
            require(fields.size == 3) { "Invalid schedule row: $line" }
            Program(fields[0], fields[1], fields[2])
        }
        .toList()

    private const val RELAY_MEKONG = "Tiếp sóng VOV Giao thông TP. Hồ Chí Minh"

    private val mekongBase = parse("""
        00:00|04:00|Tiếp sóng VOV Giao thông TP. Hồ Chí Minh
        04:05|06:00|Tiếp sóng VOV Giao thông TP. Hồ Chí Minh
        06:00|09:00|Bình minh châu thổ
        09:00|10:30|Tiếp sóng VOV Giao thông TP. Hồ Chí Minh
        10:30|12:00|Đứng bóng mặt trời
        12:00|14:30|Tiếp sóng VOV Giao thông TP. Hồ Chí Minh
        14:30|15:15|Ca nhạc
        15:15|16:25|Ca nhạc theo yêu cầu
        16:25|16:30|Nhà chức trách thông báo
        16:30|19:00|Đường về hoàng hôn
        19:00|20:00|Hương lúa trong đêm
        20:00|23:59|Tiếp sóng VOV Giao thông TP. Hồ Chí Minh
    """)

    private val duyenHaiSunday = parse("""
        00:00|06:00|Nhập sóng VOVGT Hà Nội
        06:00|06:35|Nhạc hiệu - Rao sóng
        06:35|06:40|Thời tiết
        06:40|06:50|Bản tin đời sống Duyên Hải
        06:50|06:55|Âm nhạc
        06:55|07:05|Tổng quan giao thông Duyên Hải
        07:05|07:25|Âm nhạc và cập nhật tin nóng
        07:25|07:35|Nhà chức trách thông báo
        07:35|07:45|Quảng bá Duyên Hải + âm nhạc
        07:45|07:55|Giao thông xanh
        07:55|08:15|Âm nhạc và cập nhật tin nóng
        08:15|08:25|Hỏi đáp về đại dương
        08:25|08:30|Âm nhạc
        08:30|08:35|Tiêu điểm an ninh
        08:35|08:45|Âm nhạc và cập nhật tin nóng
        08:45|08:55|Sự việc góc nhìn
        08:55|09:00|Âm nhạc và chào kết
        09:00|09:05|Nhạc hiệu - Rao sóng
        09:05|09:10|Thời tiết
        09:10|09:15|Âm nhạc
        09:15|09:25|Tổng quan giao thông Duyên Hải
        09:25|09:45|Quảng bá Duyên Hải + âm nhạc
        09:45|09:55|Bản tin đời sống Duyên Hải
        09:55|10:00|Âm nhạc và cập nhật tin nóng
        10:00|10:10|Nhà chức trách thông báo
        10:10|10:15|Âm nhạc
        10:15|10:25|Giao thông xanh
        10:25|10:30|Âm nhạc
        10:30|10:40|Tri thức đại dương
        10:40|10:45|Âm nhạc
        10:45|10:55|Thế giới giao thông
        10:55|11:00|Quảng bá Duyên Hải + âm nhạc
        11:00|11:10|Bản tin thể thao
        11:10|11:15|Âm nhạc và cập nhật tin nóng
        11:15|11:25|Sự việc góc nhìn
        11:25|11:30|MC chào kết, dẫn vào âm nhạc cảm xúc
        11:30|12:00|Ca nhạc log khô
        12:00|12:05|Nhạc hiệu - Rao sóng
        12:05|12:10|Thời tiết
        12:10|12:20|Tổng quan giao thông Duyên Hải
        12:20|12:30|Bản tin đời sống Duyên Hải
        12:30|13:30|Ca nhạc
        13:30|14:30|Trên mọi nẻo đường
        14:30|14:40|Nhà chức trách thông báo
        14:40|14:50|Hỏi đáp về đại dương
        14:50|14:55|Quảng bá Duyên Hải + âm nhạc
        14:55|15:00|Chào kết khung
        15:00|16:00|Greatest Hits
        16:00|16:05|Nhạc hiệu - Rao sóng
        16:05|16:10|Thời tiết
        16:10|16:15|Âm nhạc
        16:15|16:25|Tổng quan giao thông Duyên Hải
        16:25|16:30|Quảng bá Duyên Hải + âm nhạc
        16:30|16:45|Bản tin đời sống Duyên Hải
        16:45|17:00|Nhật tin nóng
        17:00|17:10|Nhà chức trách thông báo
        17:10|17:25|Âm nhạc
        17:25|17:35|Quảng bá Duyên Hải + âm nhạc
        17:35|17:40|Tiêu điểm an ninh
        17:40|18:00|Âm nhạc và cập nhật tin nóng
        18:00|18:10|Tri thức đại dương
        18:10|18:15|Âm nhạc
        18:15|18:25|Người cũ cảnh xưa
        18:25|18:30|MC chào kết bằng âm nhạc
        18:30|23:59|Nhập sóng VOVGT Hà Nội
    """)

    private val duyenHaiWeekday = parse("""
        00:00|06:00|Nhập sóng VOVGT Hà Nội
        06:00|06:05|Nhạc hiệu - Rao sóng
        06:05|06:10|Thời tiết
        06:10|06:15|Bản tin đời sống Duyên Hải
        06:15|06:20|Âm nhạc
        06:20|06:30|Âm nhạc và cập nhật tin nóng
        06:30|06:40|Tổng quan giao thông Duyên Hải
        06:40|06:50|Âm nhạc và cập nhật tin nóng
        06:50|06:55|Nhà chức trách thông báo
        06:55|07:00|Quảng bá Duyên Hải + âm nhạc
        07:00|07:10|Cẩm nang cao tốc
        07:10|07:15|Quảng bá Duyên Hải + âm nhạc
        07:15|07:25|Hỏi đáp về đại dương
        07:25|07:45|Âm nhạc và cập nhật tin nóng
        07:45|07:50|Giao thông xanh
        07:50|08:05|Quảng bá Duyên Hải + âm nhạc
        08:05|08:10|Thế giới giao thông
        08:10|08:20|Âm nhạc và cập nhật tin nóng
        08:20|08:25|Gõ cửa sàn phát mới
        08:25|08:45|Âm nhạc và cập nhật tin nóng
        08:45|08:55|Chuyện hôm nay
        08:55|09:00|Âm nhạc và chào kết
        09:00|09:05|Nhạc hiệu - Rao sóng
        09:05|09:10|Thời tiết
        09:10|09:15|Âm nhạc
        09:15|09:25|Tổng quan giao thông Duyên Hải
        09:25|09:45|Âm nhạc và cập nhật tin nóng
        09:45|09:55|Bản tin đời sống Duyên Hải
        09:55|10:00|Quảng bá Duyên Hải + âm nhạc
        10:00|10:10|Nhà chức trách thông báo
        10:10|10:15|Âm nhạc
        10:15|10:25|Giao thông xanh
        10:25|10:30|Âm nhạc
        10:30|10:40|Tri thức đại dương
        10:40|10:45|Âm nhạc
        10:45|10:55|Thế giới giao thông
        10:55|11:00|Quảng bá Duyên Hải + âm nhạc
        11:00|11:10|Bản tin thể thao
        11:10|11:15|Âm nhạc và cập nhật tin nóng
        11:15|11:25|Chuyện hôm nay
        11:25|11:30|MC chào kết, dẫn vào âm nhạc
        11:30|12:00|Ca nhạc log khô
        12:00|12:05|Nhạc hiệu - Rao sóng
        12:05|12:10|Thời tiết
        12:10|12:20|Tổng quan giao thông Duyên Hải
        12:20|12:30|Bản tin đời sống Duyên Hải
        12:30|13:30|Ca nhạc
        13:30|14:30|Trên mọi nẻo đường
        14:30|14:40|Nhà chức trách thông báo
        14:40|14:50|Hỏi đáp về đại dương
        14:50|14:55|Quảng bá Duyên Hải + âm nhạc
        14:55|15:00|Chào kết khung
        15:00|16:00|Greatest Hits
        16:00|16:05|Nhạc hiệu - Rao sóng
        16:05|16:10|Thời tiết
        16:10|16:15|Âm nhạc
        16:15|16:25|Tổng quan giao thông Duyên Hải
        16:25|16:30|Quảng bá Duyên Hải + âm nhạc
        16:30|16:40|Bản tin đời sống Duyên Hải
        16:40|16:45|Âm nhạc
        16:45|16:55|Nhà chức trách thông báo
        16:55|17:00|Quảng bá Duyên Hải + âm nhạc
        17:00|17:10|Giao thông xanh
        17:10|17:30|Âm nhạc và cập nhật tin nóng
        17:30|17:40|Chuyện hôm nay
        17:40|17:45|Quảng bá Duyên Hải + âm nhạc
        17:45|18:00|Âm nhạc và cập nhật tin nóng
        18:00|18:10|Bản tin Tài chính kinh doanh
        18:10|18:30|Âm nhạc và cập nhật tin nóng
        18:30|18:40|Tri thức đại dương
        18:40|18:45|Âm nhạc
        18:45|18:50|Đối thoại
        18:50|19:00|Cẩm nang cao tốc
        19:00|19:05|MC chào kết
        19:05|20:00|60 phút
        20:00|23:59|Nhập sóng VOVGT Hà Nội
    """)

    fun programs(channel: String, date: LocalDate): List<Program> = when (channel) {
        "MK" -> when (date) {
            LocalDate.of(2026, 11, 16) -> mekongBase
            LocalDate.of(2026, 11, 17) -> mekongBase.map {
                if (it.start == "19:00") it.copy(title = "Hương lúa trong đêm (phát lại)") else it
            }
            // 18/11/2026 is absent from the supplied Mekong schedule; do not infer it.
            LocalDate.of(2026, 11, 19) -> mekongBase.map {
                if (it.start == "19:00") it.copy(title = "Hương lúa trong đêm (phát lại)") else it
            }
            LocalDate.of(2026, 11, 20) -> mekongBase
            LocalDate.of(2026, 11, 21) -> mekongBase.map {
                if (it.start == "19:00") it.copy(title = RELAY_MEKONG) else it
            }
            else -> emptyList()
        }
        "DH" -> if (date.dayOfWeek == DayOfWeek.SUNDAY) duyenHaiSunday else duyenHaiWeekday
        else -> emptyList()
    }
}
