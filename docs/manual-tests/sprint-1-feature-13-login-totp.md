# Kịch bản test thủ công — #13 Đăng nhập có 2FA

Áp dụng cho cả 2 giao diện. Happy path (mã đúng/sai + backup code) đã được test ở kịch bản #1
case 7.4-7.5 — file này đào sâu thêm các case biên chưa cover.

Ghi chú: audit gốc (07-22) ghi "thiếu đường dẫn đăng nhập bằng backup code" — đã xác nhận lại qua
code (`LoginTotpService`/`TotpService.consumeBackupCode`) là **đã có đường dẫn này**, note đó đã
lỗi thời. Không cần test lại việc "có tồn tại backup-code login hay không", chỉ cần test đúng hành
vi của nó (case 3-5 dưới đây).

---

## A. Test trên cả 2 giao diện

### 1. Sai mã TOTP nhiều lần liên tiếp
- Đăng nhập tài khoản đã bật 2FA, tới màn nhập mã, nhập sai liên tục 5-6 lần.
- **Kỳ vọng:** mỗi lần sai báo lỗi rõ ràng, không tự khóa pending-session giữa chừng (khác với
  lockout của #14 — đây là 1 cơ chế riêng). Nếu có rate-limit riêng cho bước này, xác nhận đúng
  thông báo khi vượt giới hạn (nếu không có, không phải bug — chỉ cần not-crash).

### 2. Phiên chờ 2FA (pending token) hết hạn
- Đăng nhập đúng mật khẩu, tới màn nhập mã 2FA, **không nhập gì**, đợi lâu (hoặc kiểm tra TTL của
  pending-token trong Redis — tìm key dạng `totp:pending:*` hoặc tương tự):
  ```bash
  docker exec fams-redis redis-cli KEYS "*totp*pending*"
  ```
  nếu tìm thấy key, set TTL ngắn để mô phỏng hết hạn rồi thử nhập mã đúng.
- **Kỳ vọng:** báo lỗi "phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại từ đầu" — không cho
  vào bằng mã đúng nếu phiên đã hết hạn, và **không** để lộ chi tiết kỹ thuật (stack trace, tên
  key Redis) ra UI.

### 3. Dùng backup code — mỗi mã chỉ dùng được 1 lần
- Đăng nhập bằng 1 trong các mã dự phòng đã lưu từ lúc bật 2FA (kịch bản #1 case 7.5 hoặc #12).
- **Kỳ vọng:** đăng nhập thành công. Đăng xuất, đăng nhập lại, thử dùng **đúng mã dự phòng đó lần
  nữa**.
- **Kỳ vọng lần 2:** bị từ chối — mã đã dùng rồi không dùng lại được.

### 4. Dùng hết toàn bộ 8 mã dự phòng
- Lần lượt dùng hết cả 8 mã dự phòng (mỗi lần đăng nhập dùng 1 mã khác nhau).
- **Kỳ vọng:** cả 8 mã đều dùng được đúng 1 lần. Sau khi dùng hết, đăng nhập lại và thử nhập bất
  kỳ chuỗi 8 ký tự nào không phải mã đã cấp.
- **Kỳ vọng:** bị từ chối, và UI (ở màn Cài đặt bảo mật, không phải màn login) nên có cách cho
  người dùng biết đã dùng hết mã dự phòng / tạo lại bộ mã mới — kiểm tra có tính năng "Tạo lại mã
  dự phòng" không, nếu không có, ghi nhận là gap UX (không chặn tính năng, nhưng nên có).

### 5. TOTP không được bỏ qua khi đăng nhập lại nhanh
- Đăng nhập thành công (qua 2FA) xong đăng xuất ngay, đăng nhập lại ngay lập tức bằng mật khẩu.
- **Kỳ vọng:** vẫn phải qua bước nhập mã 2FA lần nữa — không có "nhớ thiết bị này" nào đang bật
  ngầm khiến 2FA bị bỏ qua ở lần đăng nhập kế tiếp (trừ khi tính năng "ghi nhớ thiết bị" là chủ
  đích đã có, kiểm tra kỹ UI có tùy chọn ghi nhớ không rồi mới kết luận).

---

## Ghi chú
Case 1-5 chưa được tôi tự test qua Playwright. Case 4 đặc biệt tốn thời gian (dùng hết 8 mã) — có
thể làm sau cùng, không phải case chặn để đóng tính năng nếu các case khác đều pass.
