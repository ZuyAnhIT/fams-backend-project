# Nhật ký kiểm thử thủ công (Manual QA Log)

Đây là **nguồn sự thật duy nhất** cho câu hỏi: "tính năng X đã được người dùng tự tay test qua
giao diện thật (Web Admin / Mobile App) chưa, kết quả thế nào?" — tách biệt với checkbox `[x]`
trong `docs/BACKLOG.md` (checkbox đó chỉ phản ánh trạng thái **code/backend đã audit xong**,
không đồng nghĩa đã có người test tay qua UI thật).

**Dùng file này khi nào:**
- Trước khi sửa code của bất kỳ tính năng nào đang ở trạng thái ✅ **ĐÃ KHÓA** bên dưới — đọc kỹ
  phạm vi đã test để không vô tình gây hồi quy. Nếu bắt buộc phải sửa, phải note lại + đề nghị
  test lại đúng các case liên quan sau khi sửa.
- Sau mỗi lần bạn (chủ dự án) test xong một tính năng — báo kết quả (pass toàn bộ / pass một
  phần kèm case nào chưa test hoặc fail) — tôi cập nhật bảng bên dưới và đóng mục tương ứng.

## Chú giải trạng thái

| Ký hiệu | Ý nghĩa |
|---|---|
| ✅ **PASS — ĐÃ KHÓA** | Toàn bộ case trong kịch bản test đã pass qua UI thật. Coi là xong, tránh sửa lại trừ khi có yêu cầu mới; nếu sửa, phải test lại. |
| 🟡 **PASS MỘT PHẦN** | Một số case đã pass, còn case khác chưa test hoặc đang bị chặn (VD: thiếu môi trường/thiết bị). Chưa khóa — vẫn có thể còn thay đổi. |
| 🔴 **FAIL — CÓ BUG** | Test ra lỗi thật, đang chờ sửa. |
| ⬜ **CHƯA TEST** | Chưa ai test qua UI thật. |

---

## Bảng tổng hợp

| # | Tính năng | Web Admin | Mobile App | Trạng thái chung | Ngày | Ghi chú tồn đọng |
|---|---|---|---|---|---|---|
| 1 | Đăng nhập email/mật khẩu | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 2 | Đăng nhập bằng SĐT/OTP | ✅ Pass (Phần A) | ⬜ Chưa test | 🟡 **PASS MỘT PHẦN** | 2026-08-13 | App: chưa test luồng OTP thật (Phần B — cần cài bản EAS dev-client lên máy thật) |
| 3 | Đăng nhập Google | ⬜ Chưa test | ⬜ Chưa test | ⬜ **CHƯA TEST** | — | Toàn bộ 9 case trong kịch bản chưa chạy; đặc biệt case 4 (bỏ qua 2FA) và case 5 (không có invite-only gate) là phát hiện nghiệp vụ cần xác nhận khi test |

---

## Chi tiết

### #1 — Đăng nhập email/mật khẩu — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-01-login.md` (mục A→D, case 1-13).
- Kết quả: toàn bộ case đã chạy và pass trên cả Web Admin lẫn Mobile App.
- **Khóa từ 2026-08-13** — không sửa lại luồng login/register/forgot-password/2FA liên quan trừ
  khi có yêu cầu tính năng mới; nếu bắt buộc chạm vào, phải test lại toàn bộ case ở file trên.

### #2 — Đăng nhập bằng SĐT/OTP — 🟡 PASS MỘT PHẦN (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-02-phone-otp-login.md`.
- **Đã pass:** Phần A (case 1-5) — backend trả 503/429 đúng khi test rate-limit, Web Admin hiện
  đúng thông báo, không crash.
- **Còn tồn đọng — CHƯA TEST:** Phần B (case 6-10) trên **Mobile App** — luồng OTP thật qua
  Firebase (dù đã có Firebase project + config), do Mobile App bắt buộc phải cài bản build
  `eas build --profile development` lên thiết bị thật/simulator mới test được (không dùng được
  Expo Go/Expo Web cho tính năng này). **Chưa khóa** tính năng này — cần hoàn tất bước build EAS
  rồi test case 6-10 trước khi đóng.
- Việc cần làm tiếp: chạy `eas build --profile development --platform android` (hoặc `ios`), cài
  lên máy, rồi test case 6 (happy path), 7 (sai OTP), 8 (đăng ký mới bằng SĐT), 9 (OTP + 2FA), 10
  (rate limit thực tế).

### #3 — Đăng nhập Google — ⬜ CHƯA TEST (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-03-google-login.md`.
- Chưa có case nào được chạy qua UI thật (cả Web Admin lẫn Mobile App).
- **Ưu tiên khi test:** case 4 (Google login có bỏ qua TOTP/2FA không — hiện code chủ đích bỏ
  qua) và case 5 (email Google hoàn toàn mới có bị chặn "chưa được mời" không — hiện code
  **không** chặn, tự tạo tài khoản mới luôn) — 2 case này xác nhận lại 2 phát hiện lệch so với
  Acceptance Criteria gốc, cần bạn quyết định có phải sửa không.

---

## Quy ước cập nhật file này (cho các phiên làm việc sau)

1. Mỗi khi user báo "test xong tính năng #N" kèm kết quả (pass toàn bộ / pass một phần / fail),
   cập nhật đúng dòng trong bảng tổng hợp + viết chi tiết vào mục "Chi tiết" tương ứng.
2. Chỉ đánh dấu ✅ **PASS — ĐÃ KHÓA** khi **toàn bộ** case trong kịch bản test (`sprint-*-feature-*.md`
   tương ứng) đã được xác nhận pass qua UI thật trên **tất cả** nền tảng liên quan (Web/App theo
   đúng cột "Nền tảng" ghi trong `docs/BACKLOG.md`).
3. Nếu chỉ pass một phần hoặc bị chặn bởi môi trường (thiếu thiết bị, thiếu Firebase, v.v.), dùng
   🟡 và ghi rõ case nào còn thiếu — không tự ý khóa sớm.
4. Khi 1 tính năng đã ✅ **ĐÃ KHÓA** mà sau này cần sửa (bug mới phát sinh, thay đổi nghiệp vụ),
   phải: (a) ghi rõ lý do sửa vào mục Chi tiết, (b) hạ trạng thái xuống 🟡 hoặc 🔴 cho tới khi
   test lại xong.
