# VOV Giao thông+ — Android (Kotlin / Jetpack Compose)

Ứng dụng Android dùng Kotlin, Jetpack Compose Material 3 và Media3. Repository này là nhánh phát triển riêng; mã nguồn gốc tại [HoangHip2k1/vovgt](https://github.com/HoangHip2k1/vovgt) không được sửa đổi.

## Build APK bằng GitHub Actions

1. Mở tab **Actions** của repository.
2. Chọn workflow **Build VOVGT Android APK**.
3. Push commit lên nhánh `main` để tự chạy build, hoặc chọn **Run workflow** để chạy thủ công.
4. Khi workflow hoàn tất với trạng thái **Success**, mở run tương ứng và tải artifact **VOV-GT-Android-APK**.
5. Giải nén ZIP và cài `app-debug.apk` trên thiết bị Android 8.0 (API 26) trở lên.

Workflow hiện tại: `.github/workflows/import-source.yml`. Build dùng JDK 17, Android SDK 36 và Gradle 8.13.

**Lưu ý phát hành:** APK trong Actions là debug APK, phù hợp để thử nghiệm/cài cá nhân. Muốn phát hành chính thức cần thiết lập release signing keystore qua GitHub Secrets. Không commit keystore hoặc mật khẩu vào repository.

## Điều hướng chính

Thanh điều hướng dưới cùng có 6 tab độc lập:

- **Trực tiếp:** 4 kênh Hà Nội, TP.HCM, Mekong và Duyên Hải.
- **Lịch phát:** lịch chương trình, tạo link replay, nghe thử và tải.
- **VOV:** trình duyệt nội dung công khai VOV Nghe và xem lại.
- **Tải xuống:** hàng đợi, tiến độ, quản lý file, bộ nhớ và nghe offline.
- **Nhật ký:** lịch sử sự kiện tải xuống có thời gian, nguồn, trạng thái và chi tiết.
- **Tài khoản:** đăng nhập VOV Media trong WebView.

Các tab đã mở được giữ trong composition để trạng thái giao diện và WebView không bị tạo lại khi chuyển tab. Trình phát nền hoạt động độc lập với việc chuyển tab.

## Tính năng tải xuống

### VOV Media

- Dùng Media3 `DownloadManager` và `DownloadService`.
- Hỗ trợ hàng đợi nhiều chương trình, tối đa 2 tác vụ tải song song.
- Tạm dừng / tiếp tục toàn bộ hàng đợi, thử lại tác vụ lỗi, xóa bản tải.
- Nghe HLS offline trong ứng dụng.
- Có thể xuất bản tải sang M4A trong thư mục `Download/VOV Giao Thong+`.

### VOV Nghe và xem lại

- Hàng đợi được lưu bền vững trên thiết bị và xử lý tuần tự.
- Hỗ trợ nhiều chương trình chờ tải, tạm dừng, tiếp tục, hủy và thử lại.
- Có tiến độ tải; MP4 dùng byte đã tải/tổng byte nếu máy chủ cung cấp Content-Length.
- Lưu file công khai vào `Download/VOV` và hỗ trợ mở/nghe file đã tải trong ứng dụng.
- Tác vụ đang chạy được đưa lại vào hàng đợi nếu dịch vụ bị gián đoạn; quá trình tải sẽ bắt đầu lại từ đầu, không phải tiếp tục từ byte/segment đã tải trước đó.

### Storage Manager và Nhật ký

- Theo dõi dung lượng file công khai, file M4A đã xuất, cache HLS offline và dung lượng trống.
- Có thao tác dọn các bản tải cũ hơn 30 ngày, kèm hộp thoại xác nhận.
- Nhật ký ghi lại các sự kiện xếp hàng, bắt đầu, tạm dừng, tiếp tục, thử lại, hoàn tất, thất bại, hủy, xóa và dọn bộ nhớ.
- Nhật ký lưu tối đa 1.000 sự kiện gần nhất trên thiết bị; xóa nhật ký không xóa file tải.

## Các giới hạn cần biết

1. **Đăng nhập VOV Media:** ứng dụng dùng WebView và CookieManager; không lưu mật khẩu. Việc xác nhận phiên trên giao diện không bảo đảm máy chủ cấp quyền tải. Quyền truy cập cuối cùng do VOV Media quyết định.
2. **HLS công khai:** chỉ tải playlist đã kết thúc, không mã hóa và không dùng BYTERANGE. Luồng live hoặc nội dung được bảo vệ không được tải/giải mã.
3. **Tạm dừng / tiếp tục:** VOV Media dùng cơ chế tải offline của Media3. VOV công khai tạm dừng an toàn theo chunk/segment; nếu tiến trình bị hệ điều hành kết thúc, tác vụ có thể phải tải lại từ đầu.
4. **Tải nền:** Android có thể giới hạn foreground service theo phiên bản hệ điều hành, pin, mạng, force-stop hoặc chính sách hệ thống. Không có cam kết tải vô hạn khi người dùng force-stop ứng dụng.
5. **Nguồn nội dung:** chỉ tải nội dung mà người dùng có quyền nghe/lưu và máy chủ cho phép. Ứng dụng không bypass DRM, paywall hoặc cơ chế xác thực.
6. **Dữ liệu lịch:** lịch phát tĩnh có thể không phản ánh thay đổi mới nhất từ đài; cần kiểm tra với nguồn lịch chính thức khi cần độ chính xác thời gian thực.

## Công nghệ và cấu trúc

- Kotlin 2.2.21, Android Gradle Plugin 8.11.1, Gradle 8.13, JDK 17.
- `compileSdk 36`, `minSdk 26`, Jetpack Compose Material 3.
- AndroidX Media3 1.11.1: ExoPlayer, HLS, MediaSession, DownloadManager, Transformer.
- OkHttp 4.12.0.
- `MainActivity.kt`: giao diện, 6 tab, trình phát, WebView đăng nhập và WebView VOV.
- `OfflineStore.kt`: Media3 download index, cache, cookie jar.
- `DownloadCenterStore.kt`: hàng đợi VOV công khai, nhật ký sự kiện, thống kê và dọn file.
- `VovPublicDownloadService.kt`: foreground service xử lý tuần tự hàng đợi VOV công khai.
- `VovPublicMediaDownloader.kt`: tải MP4/HLS công khai.
- `VovServices.kt`: Application, Media3 DownloadService, scheduler và AudioService.
- `AudioExportService.kt`: xuất bản tải HLS sang M4A.

## Kiểm thử trước khi phát hành

- Nghe thử cả 4 kênh trực tiếp, khóa màn hình và chuyển tab.
- Mở lịch phát, tạo link replay, thử nghe và tải một chương trình đã kết thúc.
- Đăng nhập VOV Media bằng tài khoản hợp lệ và kiểm tra tải nội dung được cấp quyền.
- Thêm nhiều nội dung VOV công khai vào hàng đợi; thử tạm dừng, tiếp tục, hủy và thử lại.
- Tắt mạng trong khi tải, mở lại ứng dụng và kiểm tra trạng thái khôi phục.
- Tắt Wi-Fi/dữ liệu di động để xác minh nghe offline.
- Kiểm tra Nhật ký, Storage Manager và thao tác dọn file cũ trên thiết bị thật.

**Tên ứng dụng:** VOV Giao thông+ là tên ứng dụng Android độc lập; repository này không tuyên bố là ứng dụng chính thức của VOV.
