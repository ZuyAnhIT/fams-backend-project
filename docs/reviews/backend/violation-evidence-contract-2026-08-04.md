# Báo cáo hoàn thiện Violation DTO và explanation evidence — 2026-08-04

## Đã sửa

- `ViolationListResponse`: thêm `resolution`, `affectsAttendance`.
- `ViolationDetailResponse`: thêm `resolution`, `resolutionReason`, `affectsAttendance`.
- `CheckinDetailResponse`: thêm `employeeNote`, `employeePhotoUrl` để HR thực sự xem được giải trình đã lưu.
- Explain violation/check-in hỗ trợ JSON note-only và multipart note + photo trên cùng endpoint.
- Evidence JPEG/PNG/WEBP tối đa 5MB, kiểm tra MIME và magic bytes.
- Evidence lưu private dưới prefix `explanation-evidence/`; không mở bucket policy public như avatar.
- HR đọc ảnh qua endpoint tenant-scoped có permission và `Cache-Control: no-store`.
- Tên object deterministic theo tenant/source/record nên gửi lại giải trình sẽ ghi đè, không tích lũy file rác theo mỗi lần sửa.
- Inbox `GET /me/exceptions` trả `hasExplanation` + `employeeNote`; client phân biệt mục chưa gửi với mục đã gửi đang chờ HR và cho sửa nội dung cũ.
- JSON note-only giữ nguyên evidence đã upload; `photoUrl` tùy ý từ client bị từ chối, tránh đưa URL public/không tin cậy vào hồ sơ audit.

## Xác minh

- Compile Maven từ cây source sạch tạm thời: pass. Build trực tiếp tại repo bị chặn do `api-server/target` cũ thuộc root, không liên quan code.
- Live violation: multipart upload 200; HR protected photo 200; DTO đủ 3 field; URL đúng tenant.
- Live check-in: multipart upload 200; HR detail trả note; protected photo 200.
- File `text/plain` bị chặn 400; đọc ảnh không token bị chặn 401.
- Dữ liệu note/photo của bản ghi seed dùng test đã được trả về null sau kiểm thử.
- Regression `tests/violation/test_hr_violation_detail.sh`: 9/9 pass sau khi đồng bộ fixture với hợp đồng tenant owner bắt buộc và login field `identifier`.

## Lưu ý vận hành

Object test private vẫn tồn tại trong MinIO nhưng không còn DB reference; production nên bổ sung lifecycle rule xóa object orphan/retention theo chính sách pháp lý của doanh nghiệp.
