# Gọn Launcher – màn hình chính siêu nhẹ cho tivi Coocaa (Android TV)

## Launcher có gì
- Đồng hồ lớn và ngày tháng ở trên cùng, lưới app 5 cột ô to, điều khiển hoàn toàn bằng remote.
- App dành cho TV hiện banner ngang, app điện thoại hiện icon.
- **Giữ nút OK** (hoặc bấm nút Menu) trên một app để: đưa lên đầu, dời trái/phải, ẩn app, xem thông tin app.
- Ô **Cài đặt** và ô **App đã ẩn** luôn nằm cuối lưới.
- Không quảng cáo, không chạy ngầm, không cần mạng. APK chỉ khoảng 1 MB.

## 1. Build APK bằng GitHub
1. Tạo repo mới trên GitHub (ví dụ `GonLauncher`), tải toàn bộ thư mục này lên, **giữ nguyên thư mục `.github`**.
2. Vào tab **Actions** và đợi mục "Build APK" chạy xong (dấu tick xanh, khoảng 3–5 phút).
3. Bấm vào lần chạy đó, kéo xuống mục **Artifacts** và tải `GonLauncher-apk` về (đây là file zip, bên trong có file `.apk`).

## 2. Cài lên tivi
- Chép file APK vào USB, cắm vào tivi rồi mở bằng app quản lý file. Nếu tivi hỏi thì bật quyền "Cài app không rõ nguồn gốc".
- Hoặc gửi file qua app **Send Files to TV** (cài trên cả điện thoại và tivi).

Sau khi cài, bấm nút **Home**. Nếu tivi hỏi chọn ứng dụng màn hình chính thì chọn **Gọn Launcher → Luôn luôn**. Nếu tivi không hỏi, làm tiếp bước 3.

## 3. Tắt launcher gốc và app rác (không cần root, bật lại được)
Máy Windows 7 cài ADB khá rắc rối, nên dùng **điện thoại Android** cho nhanh:

1. Trên tivi: vào *Cài đặt → Thiết bị/Hệ thống → Giới thiệu*, bấm 7 lần vào **Bản dựng (Build)** để mở Tùy chọn nhà phát triển. Sau đó vào *Tùy chọn nhà phát triển* và bật **Gỡ lỗi USB / Gỡ lỗi ADB**.
2. Xem địa chỉ IP của tivi trong *Cài đặt → Mạng*.
3. Trên điện thoại (cùng mạng Wi‑Fi với tivi): cài app **Bugjaeger** hoặc **Remote ADB Shell** từ CH Play. Kết nối tới `IP-tivi:5555`, rồi trên tivi bấm **Cho phép** khi được hỏi.
4. Chạy lệnh dưới đây để tìm tên launcher gốc:
   ```
   pm list packages | grep -i launcher
   ```
   Thường sẽ là `com.google.android.tvlauncher` (Android TV) hoặc `com.google.android.apps.tv.launcherx` (Google TV). Coocaa có thể có thêm một gói riêng.
5. **Chỉ khi Gọn Launcher đã cài xong và mở được**, mới tắt launcher gốc:
   ```
   pm disable-user --user 0 com.google.android.tvlauncher
   ```
   (thay bằng đúng tên gói vừa tìm được ở bước 4)
6. Tắt gợi ý nội dung chạy ngầm (nếu tivi có gói này):
   ```
   pm disable-user --user 0 com.google.android.tvrecommendations
   ```
7. Giảm hiệu ứng chuyển cảnh để tivi phản hồi nhanh hơn:
   ```
   settings put global window_animation_scale 0.5
   settings put global transition_animation_scale 0.5
   settings put global animator_duration_scale 0.5
   ```

**Bật lại launcher gốc** khi cần (ví dụ trước khi mang tivi đi bảo hành):
```
pm enable com.google.android.tvlauncher
```

⚠️ Không tắt các gói có chữ `settings`, `systemui`, `inputmethod`, `packageinstaller` hoặc các gói hệ thống mà mình không rõ là gì.
