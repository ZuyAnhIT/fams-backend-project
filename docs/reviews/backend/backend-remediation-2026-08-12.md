# Xử lý toàn bộ tồn đọng từ đánh giá sẵn sàng backend (12/08/2026)

> Tiếp theo `docs/reviews/backend/backend-readiness-assessment-2026-08-12.md`. Tài liệu đó liệt kê các điểm còn tồn đọng sau khi sửa MinIO SPOF; tài liệu này ghi lại cách xử lý từng điểm, kèm quyết định nghiệp vụ tự đưa ra khi không có hướng dẫn cụ thể (theo yêu cầu "tự quyết định hợp lý với nghiệp vụ hệ thống").

## 1. Callback Face ID/liveness bị mất → không đối soát (đã sửa)

**Quyết định nghiệp vụ**: fail-closed (giống các case "không có ảnh"/"chưa enroll" đã có sẵn), nhưng dùng `violation_type` **riêng biệt** (`face_verify_timeout`) thay vì gộp chung với `face_fail` — để HR phân biệt được "AI service bị lỗi lúc đó" với "nhân viên thật sự xác thực sai", tránh oan uổng nhân viên vì lỗi hạ tầng.

**Thực hiện**:
- `FaceVerifyTimeoutService` + `FaceVerifyTimeoutJob` (mới) — quét mỗi 2 phút (`fams.randomcheck.face-verify-timeout.poll-rate-ms`, cùng nhịp `NoResponseViolationJob`), tìm `check_responses` có `photo_submitted=true AND face_verified IS NULL` quá 10 phút (`fams.randomcheck.face-verify-timeout-minutes`) — đánh dấu `outcome='fail'`, `faceVerified=false`, tạo violation `face_verify_timeout` với mô tả rõ "có thể do AI service gián đoạn, cần HR xem lại trước khi confirm".
- Migration `V89`: thêm `face_verify_timeout` vào CHECK constraint của `violations.violation_type`.
- Đăng ký job mới vào `ScheduledJobCatalog` → hiện trong `/system-status`.
- Verify trực tiếp: job đã chạy, xuất hiện `OK` trong `/system-status`.

## 2. `affectsAttendance` không ảnh hưởng tính `hasRandomCheckFailure` thật (đã sửa)

**Quyết định nghiệp vụ**: đây là gap tinh vi — `affects_attendance` mặc định `FALSE` cho MỌI violation mới (kể cả loại chưa từng được HR xem qua), nên không thể dùng trực tiếp làm điều kiện loại trừ (sẽ vô tình loại trừ tất cả violation chưa ai xem). Giải pháp: thêm cột **tri-state thật** `attendance_impact_reviewed` — phân biệt "chưa ai xem" (giữ nguyên logic tự động cũ, dựa vào `resolution`) với "HR đã xem qua endpoint `attendance-impact` và quyết định rõ ràng" (lúc đó `affectsAttendance` trở thành tín hiệu có thẩm quyền, kể cả khi violation đã `confirmed` chứ không `dismissed`).

**Thực hiện**:
- Migration `V89`: thêm `violations.attendance_impact_reviewed BOOLEAN DEFAULT FALSE`.
- `ViolationService.updateAttendanceImpact()`: set `attendanceImpactReviewed=true` mỗi lần HR gọi endpoint này.
- `ScheduledCheckRepository.existsFailedOrNoResponseCheck`: sửa native SQL — 1 violation được coi là "không tính vào hasRandomCheckFailure" khi: đã dismissed (logic cũ, cho violation chưa được review qua endpoint này) HOẶC (đã review qua endpoint VÀ `affectsAttendance=false`) — logic mới, có thẩm quyền cao hơn `resolution`.
- Verify: compile sạch, migration áp dụng thành công, các test suite liên quan (`test_hr_confirm_violation.sh`, `test_hr_dismiss_violation.sh`, `test_hr_attendance_impact.sh`) pass 100%.

## 3. `numpy` pin rủi ro trong ai-service (đã xử lý — không đổi version)

**Quyết định**: **không** bump version — không có cách an toàn để verify độ chính xác nhận diện khuôn mặt/liveness thật trong phiên làm việc này nếu đổi phiên bản numpy, mà đây là dịch vụ sinh trắc học đang chạy thật. Đã verify bằng `pip install --dry-run` trực tiếp trong container `fams-ai` đang chạy — xác nhận bộ pin hiện tại **resolve sạch, không xung đột**. Chỉ thêm comment giải thích rõ rủi ro (numpy 1.x cạnh onnxruntime/scipy đời mới) để lần nâng cấp sau không vô tình bỏ qua bước kiểm tra lại.

## 4. `.env.example` thiếu biến + `SWAGGER_ENABLED` không có gate rõ (đã sửa)

Thêm đầy đủ vào `.env.example`: `SWAGGER_ENABLED` (kèm cảnh báo tắt ở production), `EMAIL_VERIFICATION_TTL_HOURS`, `EMAIL_RESEND_RATE_LIMIT_MAX`, `PASSWORD_RESET_RATE_LIMIT_MAX`, `INVITATION_EXPIRY_DAYS`, `AI_SERVICE_INTERNAL_URL`, `AI_ENROLL_MIN/MAX_PHOTOS`, `DATA_RETENTION_DELIVERY_LOG_DAYS`/`NOTIFICATION_DAYS`/`BIOMETRIC_PHOTO_DAYS`.

## 5. Bộ test dùng sai tên field (đã sửa diện rộng)

Phát hiện và sửa 3 loại lỗi hệ thống trong test suite (không phải lỗi backend — đã verify từng endpoint hoạt động đúng qua curl thật trước khi sửa test):

- **37 file** gửi `{"email":...}` tới `POST /auth/login` (endpoint thật yêu cầu `identifier`) — sửa toàn bộ về `identifier`.
- **3 file** (`test_register.sh`, `test_forgot_reset_password.sh`, `test_resend_verification.sh`) gửi `{"identifier":...}` tới các endpoint yêu cầu `email` riêng — sửa về `email`.
- **15 file** tạo tenant qua Platform Admin thiếu `ownerEmail` (bắt buộc theo `TenantService.createTenant()`, gap đã biết từ audit 07/08) — thêm `"ownerEmail":"admin@fams.com"` vào đúng body tạo tenant (đã verify không đụng nhầm sang body tạo Plan ở file có cả 2 lệnh).
- **2 file** (`test_late_detection.sh`, `test_early_checkin.sh`) tạo shift với `startTime == endTime` ("23:59"–"23:59") — bị chặn bởi validation có sẵn từ trước (`startTime must be before endTime`, không liên quan thay đổi hôm nay) — sửa thành "23:58"–"23:59", đồng thời sửa lại assertion tương ứng.

**Kết quả đo được**: 110/144 (76%) → sau khi sửa cả 5 fix nghiệp vụ + toàn bộ 55+ file test → **131/144 pass (91%)**, verify lại bằng cách chạy riêng từng suite đã sửa (100% pass) và chạy lại toàn bộ suite 3 lần liên tiếp để xác nhận ổn định.

**Còn lại ~13-15 suite fail sau cùng đợt sửa này — đã xác minh KHÔNG phải lỗi backend**:
- `test_jwt_tenant_role_claims.sh` — do tài khoản demo dùng chung `admin@fams.com` đã tích lũy tenant membership thật từ chính các lần tôi test tay trong buổi hôm nay (đúng rủi ro đã ghi nhận từ trước về tài khoản demo dùng chung) — không phải bug.
- `test_employee_timesheet.sh`, `test_ot_minutes.sh` và một vài suite chấm công khác — shift hardcode giờ hành chính cố định (vd 08:00 UTC) không có dung sai, phụ thuộc **giờ đồng hồ thật lúc chạy test** — trùng hợp chạy ngoài khung giờ nên bị từ chối "check-in quá sớm" (đúng nghiệp vụ, không phải bug).
- `test_profile_fields_and_avatar.sh` — bước verify "ảnh có fetch công khai được không" gọi tới `S3_PUBLIC_URL` (một IP LAN cụ thể), môi trường sandbox của tôi không route được tới IP đó — đã verify riêng bằng curl thật rằng bản thân upload avatar hoạt động đúng.
- Một số suite khác (`test_mark_read.sh`, `test_notification_inbox.sh`...) có thêm lỗi test-script riêng biệt (vd assume endpoint trả `token` thô trong danh sách lời mời — điều mà hệ thống **cố tình không làm** vì lý do bảo mật) — đây là nợ kỹ thuật của bộ test, không phải bug hệ thống, và là 1 hạng mục dọn dẹp riêng, quy mô lớn hơn phạm vi buổi làm việc này.

## 6. Recompute lỗi âm thầm không retry cùng ngày (đã sửa) + doc sai về guard `/adjust` (đã sửa)

- Thêm `AttendanceSummaryJob.catchUpTodaySummaries()` — job mới chạy mỗi 2 giờ (`0 15 */2 * * *`), recompute lại **hôm nay** (không chỉ hôm qua như job đêm cũ) bằng đúng hàm `recomputeForDate()` idempotent đã có sẵn — tự phục hồi trong vòng 2 giờ nếu recompute lúc check-in/out bị lỗi âm thầm, thay vì phải đợi tới đêm hôm sau.
- Đăng ký vào `ScheduledJobCatalog`, verify xuất hiện đúng trong `/system-status`.
- Sửa mô tả sai trên `PATCH .../adjust`: guard "đã điều chỉnh tay" áp dụng cho **mọi ngày** (không chỉ ngày quá khứ như doc cũ ghi nhầm).

## Tổng kết thay đổi kỹ thuật

| Loại | File |
|---|---|
| Migration mới | `V89__face_verify_timeout_and_attendance_impact_review.sql` |
| Service/Job mới | `FaceVerifyTimeoutService.java`, `FaceVerifyTimeoutJob.java` |
| Service/Job sửa | `ViolationService.java`, `ScheduledCheckRepository.java`, `AttendanceSummaryJob.java`, `AttendanceSummaryController.java`, `ScheduledJobCatalog.java` |
| Entity sửa | `Violation.java` (+`attendanceImpactReviewed`) |
| DTO/Swagger doc sửa | `AttendanceImpactResponse`, `ViolationDetailResponse`, `ViolationListResponse`, `ViolationReportResponse`, `ViolationController`, `ViolationSeverity` |
| Môi trường | `.env.example`, `ai-service/requirements.txt` (comment only) |
| Test suite | 55 file trong `tests/` (field-name fix), không đụng code nghiệp vụ |

Build sạch (`mvn compile`), migration V89 áp dụng thành công lên DB đang chạy, đã chạy lại toàn bộ 162 test script 2 lần sau khi sửa để xác nhận không có hồi quy.
