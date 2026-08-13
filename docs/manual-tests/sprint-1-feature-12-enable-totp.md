# Kịch bản test thủ công — #12 Bật TOTP 2FA

Áp dụng cho cả 2 giao diện. Nếu bạn đã test case 7 ở kịch bản #1
(`sprint-1-feature-01-login.md`) thì bước bật 2FA + backup code đã được test happy-path rồi —
file này **đào sâu thêm**, đặc biệt các điểm mới của contract 2026-08-12 (otpauthUri, expiresAt,
chặn bật trùng) hiện đang nằm trong working tree, chưa merge (xem PR `feature/totp-setup-contract-update`).

---

## A. Test trên cả 2 giao diện

### 1. Bật 2FA — happy path (nếu chưa test qua #1 case 7)
- Đăng nhập, vào Cài đặt bảo mật → Bật xác thực 2 lớp.
- **Kỳ vọng:** hiện mã QR để quét (hoặc mã nhập tay `manualEntryKey`), quét bằng app
  Authenticator thật (Google Authenticator/Authy), nhập mã 6 số để xác nhận → bật thành công, hiện
  đúng **8 mã dự phòng** — phải yêu cầu lưu lại rõ ràng (mã chỉ hiện 1 lần).

### 2. QR render client-side, không phải iframe (contract mới)
- Khi màn hình bật 2FA hiện ra, mở DevTools (Web) → Elements → tìm thẻ hiển thị QR.
- **Kỳ vọng:** QR được vẽ bằng `<canvas>`/`<svg>` (thư viện QR JS phía client, dựng từ field
  `otpauthUri`), **không** phải `<iframe src=".../totp/qr?token=...">`. Nếu vẫn thấy iframe, đó là
  frontend chưa cập nhật theo contract mới — báo lại.

### 3. Bật 2FA khi đã bật rồi — phải bị chặn (409, hành vi mới)
- Với 1 tài khoản đã bật TOTP (từ case 1), thử vào lại màn "Bật 2FA" một lần nữa (nếu UI cho vào
  được) hoặc gọi trực tiếp:
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/auth/totp/setup \
    -H "Authorization: Bearer <token của tài khoản đã bật 2FA>" -w "\nHTTP:%{http_code}\n"
  ```
- **Kỳ vọng:** HTTP 409, thông báo "TOTP is already enabled for this account" (hoặc bản dịch tiếng
  Việt tương ứng nếu FE đã xử lý). UI phải hiện thông báo rõ ràng, không phải lỗi trắng.

### 4. Gọi bật 2FA 2 lần liên tiếp (chưa xác nhận lần nào) — chỉ 1 secret còn hiệu lực
- Với tài khoản CHƯA bật 2FA: vào màn bật 2FA, lấy `setupToken`/mã QR lần 1 (không nhập mã xác
  nhận vội), sau đó thoát ra vào lại (hoặc bấm "Lấy mã QR mới") để lấy `setupToken`/QR lần 2.
- Thử nhập mã xác nhận sinh ra từ QR **lần 1** (secret cũ).
- **Kỳ vọng:** bị từ chối (secret lần 1 đã bị vô hiệu khi gọi setup lần 2) — chỉ mã từ QR **lần 2**
  mới xác nhận được. Đây là hành vi bảo mật mới, tránh 2 secret cùng tồn tại.

### 5. Phiên setup hết hạn (10 phút)
- Lấy `setupToken` (từ case 1 hoặc network tab), không hoàn tất, đợi hơn 10 phút hoặc set TTL tay:
  ```bash
  docker exec fams-redis redis-cli KEYS "totp:setup:*"
  docker exec fams-redis redis-cli EXPIRE "totp:setup:<setupToken>" 1
  ```
  đợi 2 giây, thử xác nhận mã.
- **Kỳ vọng:** báo lỗi "phiên thiết lập đã hết hạn", không xác nhận được — nếu UI hiển thị
  `expiresAt` dạng đếm ngược, kiểm tra đúng hết hạn tại thời điểm đó.

### 6. Nhập sai mã xác nhận
- Ở bước xác nhận bật 2FA, nhập 1 mã 6 số sai bất kỳ.
- **Kỳ vọng:** báo lỗi rõ ràng, chưa bật 2FA, được thử lại (không tự hủy phiên setup).

---

## Ghi chú
- Case 3/4 dựa trên code mới nhất trong working tree (chưa push/merge) — nếu bạn test trước khi
  PR `feature/totp-setup-contract-update` được merge, code vẫn chạy đúng vì dev server chạy trực
  tiếp từ source, không cần đợi merge.
- Case 1 nếu đã pass ở kịch bản #1 case 7, không cần lặp lại — chỉ cần chạy case 2-6.
