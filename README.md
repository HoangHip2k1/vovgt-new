# VOV Giao thông+ — Android (Kotlin / Jetpack Compose)

Ứng dụng Android viết mới từ file `VOV Giao Thông.html`, không chỉ là WebView bọc HTML. Giao diện hỗ trợ Material 3, sáng/tối, tiếng Việt, bốn luồng trực tiếp và lưu chương trình HLS offline trong app.

## Tải APK qua GitHub Actions — không cần Android Studio

1. Đăng nhập GitHub → chọn **New repository** → tạo repository, ví dụ `vov-giao-thong-plus`.
2. Giải nén ZIP này. **Đưa toàn bộ các file và thư mục ở bên trong `VOV_Giao_Thong_Plus_Android/` lên THƯ MỤC GỐC của repository**. Đặc biệt `.github/workflows/build-apk.yml` phải nằm đúng vị trí. Bạn có thể dùng **Add file → Upload files** (trình duyệt có thể bỏ qua thư mục ẩn `.github`; hãy kiểm tra sau khi upload, hoặc sử dụng Git/GitHub Desktop để push cả thư mục).
3. Commit vào nhánh `main` hoặc `master`. Vào **Actions** → workflow **Build APK — VOV Giao thông+** → **Run workflow** (hoặc đợi job chạy tự động khi push).
4. Khi job báo xanh, mở job → phía cuối trang **Artifacts** → nhấn `VOV-Giao-Thong-Plus-APK` để tải ZIP chứa `VOV-Giao-Thong-Plus-v1.0.0.apk`.
5. Giải nén artifact và cài APK trên thiết bị Android 8.0 (API 26) trở lên. Android có thể yêu cầu cho phép cài app từ nguồn này và cấp quyền thông báo.

**Lưu ý:** APK trong Actions là **debug APK có chữ ký debug**, chỉ dùng thử/cài cá nhân. Muốn phát hành chính thức, cần thiết lập release signing keystore bằng GitHub Secrets. Đừng commit keystore/mật khẩu.

## Tính năng

- **Lịch phát:** Giữ nguyên dữ liệu 7 ngày của hai kênh Hà Nội và TP.HCM từ HTML đính kèm (294 khung giờ). Duyên Hải chưa có lịch phát lại trong nguồn HTML.
- **Link phát lại:** tạo URL `/replay/<slug>/playlist.m3u8?from=<epoch>&to=<epoch>` với thời gian `Asia/Ho_Chi_Minh`, có nút copy, nghe thử, tải; hoặc tự dán URL HTTPS `.m3u8` để nghe/tải.
- **4 đài trực tiếp:** Hà Nội, TP.HCM, Mekong, Duyên Hải từ đúng các URL đã cung cấp. Nghe chạy dưới `MediaSessionService` nên có thể tiếp tục khi khóa màn hình/chuyển app.
- **Đăng nhập trước tải:** nhúng **trang web chính thức `https://vovmedia.vn/login`** để tự đăng nhập, không thu mật khẩu. Bắt buộc xác nhận phiên trước khi gửi yêu cầu tải. WebView CookieManager chia sẻ cookies với OkHttp qua CookieJar, chỉ gửi cookie đến host phù hợp.
- **Tải HLS offline:** `Media3 DownloadService` + `DownloadManager` + `SimpleCache` (không giới hạn tự xóa) + `PlatformScheduler` giúp tải khi rời app và tiếp tục tải sau gián đoạn mạng tùy chính sách Android.
- **Tiến độ / lịch sử:** thông báo foreground, trạng thái/thanh tiến độ, thông báo khi xong/lỗi, tạm dừng/tiếp tục, xóa, nghe offline.
- **Icon:** logo riêng dạng sóng radio – giao thông – dấu cộng.

## Những giới hạn cần hiểu trước khi dùng

1. **Đăng nhập VOV Media:** Website chính thức có form login, nhưng không công bố trong tài liệu đã kiểm tra cơ chế API dành cho app bên thứ ba. App chỉ có thể xác nhận trình duyệt đã rời trang `/login` và có cookie, **không thể khẳng định người dùng đã được backend cấp quyền**. Media3 sẽ yêu cầu tài nguyên theo chính sách máy chủ; nếu website dùng token lưu trong `localStorage`, yêu cầu xác thực khác, DRM, hoặc chỉ hỗ trợ trình duyệt, việc tải/phát lại có thể bị từ chối. **Ứng dụng không bypass DRM/paywall/đăng nhập.** Phải thử trên điện thoại với tài khoản hợp lệ.
2. **Tải HLS không phải xuất MP3:** bản tải lưu thành **cache HLS riêng của ứng dụng**, có thể nghe trong tab Đã tải khi mất mạng; không có tệp MP3 trong thư mục `Downloads`. Không chuyển đổi/ghép thành MP3 trong phiên bản này. Xóa app sẽ xóa cache.
3. **Luồng trực tiếp:** chỉ nghe; không tải vô hạn live HLS làm file. Nội dung phát lại chỉ tải khi kết thúc theo lịch địa phương. Chưa xác thực URL hoặc nội dung thực tế với máy chủ bằng tài khoản người dùng, nên không bảo đảm tất cả URL hoạt động.
4. **Quyền tải:** Chỉ dùng trong phạm vi quyền nghe và lưu trữ được VOV Media cho phép. Một số chương trình không tồn tại hoặc không còn được lưu trên máy chủ.
5. **Tải nền:** Android có thể dừng tải khi người dùng dùng **Force stop**, tắt máy, giới hạn pin rất chặt, mất mạng hoặc cookie hết hạn. Android 15+ hạn chế thời lượng foreground service `dataSync`; đây không phải dịch vụ không giới hạn.
6. **Các URL HTTPS hợp lệ ngoài VOV:** ứng dụng chấp nhận dán HLS khác; bản phát HLS dạng live không có kết thúc sẽ không tải offline. App không gửi cookie của VOV Media cho các domain khác.

## Công nghệ và cấu trúc

- Kotlin 2.2.21, Android Gradle Plugin 8.11.1, Gradle 8.13, JDK 17, `compileSdk 36`, Android API 26+.
- Jetpack Compose Material3 và Media3 1.11.1 (HLS / Session / Offline).
- `app/src/main/assets/schedules.json`: lịch 7 ngày xuất tự động từ HTML.
- `MainActivity.kt`: UI / tabs / lịch / login WebView / playback controller.
- `Schedule.kt`: dữ liệu, timezone, URL và validate.
- `OfflineStore.kt`: tải offline, cache, chia sẻ session cookies theo host.
- `VovServices.kt`: audio service, DownloadService, scheduler, thông báo.
- `.github/workflows/build-apk.yml`: build APK trên GitHub Ubuntu runner.

## Điều cần kiểm tra khi build và chạy

- Workflow build xuất hiện dưới Actions; nếu chưa xuất hiện, kiểm tra `.github/workflows/build-apk.yml` đã được commit đúng thư mục.
- Nếu GitHub Actions báo `Failed to find Platform SDK with path: platforms;android-36`, chạy lại workflow; file workflow đã có bước `sdkmanager`.
- Bật quyền thông báo trên Android 13+ để thấy cập nhật tiến độ.
- Vào Tài khoản → Đăng nhập → đăng nhập thật trên VOV Media → bấm Xác nhận → quay về Lịch phát và tải một chương trình **đã phát xong**.
- Dùng tab Đã tải để kiểm tra trạng thái và nghe offline khi tải xong. Kiểm thử bằng cách tắt Wi-Fi/dữ liệu di động sau khi tải hoàn tất.

**Trạng thái phân phối:** Repo mã nguồn đã chuẩn bị cho GitHub Actions; trong môi trường tạo dự án này không có Android SDK/Gradle và không có kết nối Internet từ container để chạy build. Vì vậy **chưa có APK được biên dịch/kiểm thử thực tế**. GitHub Actions sẽ thực hiện bước build; nếu có lỗi Gradle/API, cần kiểm tra log và hiệu chỉnh.

**Tên ứng dụng:** `VOV Giao thông+` là tên bản dựng cá nhân theo yêu cầu, **không tuyên bố đây là ứng dụng chính thức của VOV**.
