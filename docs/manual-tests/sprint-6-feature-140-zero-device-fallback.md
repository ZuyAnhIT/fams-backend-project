# Kịch bản test thủ công — #140 (follow-up) Email fallback cho nhân viên chưa từng đăng ký thiết bị

**Nền tảng: Backend.**

## Bối cảnh — phát hiện từ ca hỗ trợ khách hàng thật (2026-08-22)
Trong lúc xác minh site "Xây Nhà Tập Thể" (tenant FOFO) đã sẵn sàng test kiểm tra ngẫu nhiên, tôi
kích hoạt thử 1 lượt kiểm tra thủ công (`POST .../scheduled-checks/manual`) cho nhân viên thật của
khách hàng. Nhân viên này đã có Notification trong app (đọc được qua `GET /notifications`) nhưng
`notification_delivery_logs` cho notification đó **rỗng hoàn toàn** — không có dòng FCM, cũng
không có dòng EMAIL_FALLBACK — dù `RANDOM_CHECK_SENT` đã là priority "critical" (đủ điều kiện
fallback theo #140 gốc, 2026-08-19).

**Nguyên nhân**: `UserDeviceService#sendPush` — nhánh `devices.isEmpty()` (nhân viên chưa từng mở
app / chưa từng cấp quyền push nên **0 dòng** trong `user_devices`, khác với trường hợp có 1 thiết
bị nhưng FCM gửi thất bại) return sớm, và trước bản vá này, việc kiểm tra `fallbackEligible` chỉ
nằm ở nhánh phía dưới (áp dụng khi `sent == 0 && !devices.isEmpty()`). Kết quả: nhân viên chưa
từng đăng ký thiết bị nào là trường hợp DUY NHẤT không nhận được cả push lẫn email, im lặng, dù
đây lẽ ra là trường hợp cần fallback nhất (không có kênh nào khác để tới được họ).

## Đã vá
`UserDeviceService.java` — nhánh `devices.isEmpty()` nay cũng gọi `sendEmailFallback(...)` khi
`fallbackEligible=true`, giống hệt logic ở nhánh "có thiết bị nhưng gửi thất bại hết".

## Test

### Trước khi vá (live, dữ liệu FOFO thật)
- Trigger kiểm tra ngẫu nhiên thủ công cho nhân viên 0-thiết-bị → Notification tạo thành công
  trong app, nhưng `notification_delivery_logs` = 0 dòng cho notification đó.

### Sau khi vá (rebuild + redeploy `fams-api`, test lại chính kịch bản trên)
- Cùng thao tác → `notification_delivery_logs` nay có đúng 1 dòng
  `channel=EMAIL_FALLBACK, status=FALLBACK_EMAIL_SENT`.

### Test tự động — `tests/notification/test_fcm_retry_fallback.sh`
Thêm mục 7 (case mới, tách biệt hoàn toàn với case cũ "có 1 thiết bị giả gây FCM fail"): tạo
nhân viên hoàn toàn mới, **không** gọi `POST /me/devices` — xác nhận `user_devices` = 0 dòng, gửi
notification `RANDOM_CHECK_SENT`, kiểm tra: không có dòng FCM nào (đúng — không có thiết bị để
thử), có đúng 1 dòng `EMAIL_FALLBACK` với status hợp lệ, và Notification vẫn hiện trong inbox app.
Kết quả: **15/15 PASS** (11 case cũ + 4 case mới).

## Regression
`test_fcm_retry_fallback.sh` 15/15, `test_fcm_devices.sh` 13/13, `test_manual_check.sh` 16/16,
`test_dispatch_notification.sh` 9/9 — không hồi quy. `tests/run_all.sh` (146 suite toàn dự án)
chạy lại sau khi deploy để xác nhận không ảnh hưởng diện rộng.

## Dữ liệu FOFO thật của khách hàng
2 lượt kiểm tra thủ công tôi kích hoạt để verify (trước và sau khi vá) sẽ tự hết hạn sau 5 phút
theo cơ chế `RandomCheckDispatchJob`/no-response timeout hiện có — không cần khách hàng dọn tay,
chỉ là dữ liệu audit trail bình thường, đã báo cho khách hàng biết để không nhầm lẫn.
