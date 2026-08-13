# Kịch bản test thủ công — #4 Đăng xuất khỏi thiết bị hiện tại

Áp dụng cho cả 2 giao diện: Web Admin và Mobile App.

⚠️ **Phát hiện khi rà code trước khi viết kịch bản** (chưa sửa): `LogoutService.logout()` không
gọi `auditLogService.record(...)` — hành động đăng xuất **không** được ghi audit log, dù Acceptance
Criteria không yêu cầu rõ điều này cho #4 (chỉ #5 yêu cầu "giữ lại audit"), nên đây không hẳn là
gap nhưng vẫn nêu ra để bạn biết khi kiểm tra `GET /audit-logs` sẽ không thấy sự kiện LOGOUT.

---

## A. Test trên cả 2 giao diện

### 1. Đăng xuất — happy path
- Đăng nhập `admin@fams.com` / `Admin@1234`, vào bất kỳ trang nào, bấm "Đăng xuất".
- **Kỳ vọng:** quay về màn hình đăng nhập ngay lập tức, không có bước xác nhận thừa gây khó chịu
  (hoặc nếu có hộp thoại xác nhận, phải rõ ràng "Bạn có chắc muốn đăng xuất?").

### 2. Refresh token bị revoke thật (không chỉ xóa token phía client)
- Trước khi đăng xuất, lưu lại refresh token hiện tại (Web: mở DevTools → Application →
  Cookies/LocalStorage tùy nơi lưu; Mobile: xem qua log network hoặc `AsyncStorage`).
- Đăng xuất, sau đó gọi thử API refresh token bằng token vừa lưu:
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/auth/refresh-token \
    -H "Content-Type: application/json" -d '{"refreshToken":"<token đã lưu>"}' -w "\nHTTP:%{http_code}\n"
  ```
- **Kỳ vọng:** trả về lỗi 401 (token đã bị revoke), không cấp access token mới.

### 3. Token/local state được dọn sạch phía client
- Sau khi đăng xuất, thử bấm nút "Back" của trình duyệt/điện thoại quay lại trang trước.
- **Kỳ vọng:** hoặc tự động bị đá về màn login (do gọi API bằng token đã hết hạn → 401 → redirect),
  không hiển thị lại dữ liệu cũ đã cache mà không xác thực lại được.

### 4. Đăng xuất không ảnh hưởng thiết bị khác
- Đăng nhập cùng `admin@fams.com` trên **2 thiết bị/trình duyệt khác nhau** (VD: Chrome thường +
  Chrome ẩn danh, hoặc Web + Mobile).
- Đăng xuất ở thiết bị A.
- **Kỳ vọng:** thiết bị B vẫn đang đăng nhập bình thường, không bị đá ra (đây là điểm khác biệt
  với #5 "đăng xuất tất cả thiết bị" — batch test tiếp theo).

---

## Ghi chú
Case 1-4 tôi **chưa** tự test qua Playwright ở phiên trước — kịch bản này viết mới hoàn toàn dựa
theo code (`LogoutService.logout`, `test_logout.sh`), cần bạn tự chạy qua UI thật.
