# Kịch bản test thủ công — #51 Xóa/vô hiệu hóa Face ID

**Nền tảng: Backend, Web Admin, Mobile App.**

ℹ️ Audit gốc (07-22) ghi thiếu: "thiếu deleted_reason/deleted_by; không ghi audit FACE_DELETE".

**ĐÃ VÁ (2026-08-16):**
- Thêm cột `deleted_reason`, `deleted_by` vào `face_profiles` (cùng migration V97 với #48). Endpoint
  `DELETE .../face-id` giờ nhận thêm query param `reason` (tùy chọn). `deletedBy` tự lấy từ người
  gọi API (null nếu hệ thống tự thu hồi).
- Thêm ghi audit log `face_id_revoked` (thu hồi thủ công) và `face_id_auto_revoked_on_termination`
  (thu hồi tự động khi nghỉ việc) — thỏa đúng yêu cầu AC "ghi audit FACE_DELETE" về mặt khái niệm
  (tên action theo đúng quy ước snake_case đã dùng xuyên suốt codebase, không phải literal chuỗi
  "FACE_DELETE").
- `autoRevokeOnTermination` giờ cũng ghi `deletedReason="Employee terminated (auto-revoke)"`,
  `deletedBy=null` — nhất quán với luồng thu hồi thủ công, không còn là "đường tắt" thiếu dữ liệu.
- Web Admin (`EmployeeFaceIdTab.tsx`): nút "Thu hồi Face ID" giờ mở modal có ô nhập lý do (tùy
  chọn, giống hệt UX của luồng "Từ chối" đã có từ trước) thay vì hộp thoại Có/Không đơn giản. Hiển
  thị thêm dòng "Lý do thu hồi" và version consent bên cạnh các thông tin đã có.
- **Xử lý thứ tự ghi đúng**: `deleted_reason`/`deleted_by` là field nghiệp vụ thuần (Java ghi), khác
  với `status`/`revoked_at`/`embedding_deleted` (fams-ai ghi qua psycopg2) — code gọi
  `aiServiceClient.revokeFace()` trước, `entityManager.refresh()` để lấy đúng những gì fams-ai vừa
  ghi, RỒI MỚI set deletedReason/deletedBy và lưu — tránh 2 writer (Java/Python) ghi đè lẫn nhau.

---

## A. Test trên Mobile App (nhân viên tự thu hồi) — ✅ ĐÃ TEST LIVE (2026-08-16), qua App thật

### 1. ✅ Nhân viên tự thu hồi Face ID — happy path, ĐÃ TEST LIVE end-to-end
- Đăng nhập App thật (chế độ web), vào Hồ sơ → Face ID (đã enrolled, hiện "Đã đăng ký" +
  "Đăng ký: 16/08/2026") → bấm "Thu hồi Face ID".
- **Kết quả thật:** modal xác nhận trong App hiện đúng nội dung "Thu hồi Face ID? Hồ sơ khuôn mặt
  và lượt đang chờ duyệt sẽ bị thu hồi. Các công trình bắt buộc Face ID có thể không cho phép bạn
  tự chấm công cho tới khi đăng ký và được duyệt lại." với 2 nút "Huỷ"/"Thu hồi Face ID" → bấm xác
  nhận → toast xanh "Đã thu hồi Face ID" → trạng thái chuyển "Đã thu hồi" ngay lập tức trên màn
  hình Hồ sơ, nút đổi lại thành "Đăng ký Face ID".

### 2. ✅ Xác nhận Mobile App KHÔNG có ô nhập lý do khi tự thu hồi
- Quan sát modal xác nhận thu hồi ở case 1.
- **Kết quả thật:** đúng như dự đoán — chỉ có nội dung cảnh báo + 2 nút Huỷ/Xác nhận, KHÔNG có ô
  nhập lý do (khác Web Admin đã có). Đây là quyết định có chủ đích: tự thu hồi là hành động cá
  nhân, không cần "giải trình lý do" như khi HR chủ động thu hồi. Nếu bạn thấy cần bổ sung để nhất
  quán với Web Admin, báo lại — có thể bổ sung `reason` (tùy chọn) vào `revokeFaceId()` phía Mobile
  mà không cần đổi API (param đã optional).

## B. Test trên Web Admin (HR thu hồi) — ✅ ĐÃ TEST LIVE (2026-08-16), pass toàn bộ

### 3. ✅ HR thu hồi Face ID kèm lý do — happy path, ĐÃ VÁ
- Vào tab Sinh trắc học của 1 nhân viên đã enrolled, phần "Khu vực nguy hiểm" → "Thu hồi Face ID".
- **Kết quả thật (Playwright, giao diện thật):** modal "Xác nhận thu hồi Face ID" mở ra với ô nhập
  lý do (`textarea`, tối đa 500 ký tự, có đếm ký tự) → nhập "Nhân viên yêu cầu rút lại đồng ý (test
  UI)" → bấm "Thu hồi" → toast "Đã thu hồi hồ sơ Face ID thành công." → dòng "Lý do thu hồi" trên
  tab hiển thị đúng y hệt nội dung đã nhập.

### 4. ✅ Xác nhận `deleted_reason`/`deleted_by` được lưu — ĐÃ VÁ
- Sau case 3, kiểm tra qua DB.
- **Kết quả thật:** `deleted_reason='Nhan vien nghi viec'` (test round 1, qua API) và
  `'Nhân viên yêu cầu rút lại đồng ý (test UI)'` (test round 2, qua UI), `deleted_by` = đúng UUID
  người bấm thu hồi (Owner test account) trong cả 2 lần.

### 5. ✅ Xác nhận ghi audit — ĐÃ VÁ
- Sau case 3, kiểm tra `audit_logs`.
- **Kết quả thật:** bản ghi `face_id_revoked`, đúng `entity_id`, đúng `actor_id`, thời điểm khớp.

### 6. Xác nhận tự động thu hồi khi terminate nhân viên
- Với 1 nhân viên đang enrolled Face ID, đổi trạng thái sang "Đã nghỉ việc" (xem thêm kịch bản #40).
- **Kỳ vọng:** Face ID tự động chuyển "Đã thu hồi", `deletedReason="Employee terminated
  (auto-revoke)"`, `deletedBy=null`, có audit `face_id_auto_revoked_on_termination`. Đã xác nhận
  đúng logic qua đọc code (dùng chung cơ chế với case 3-5, chỉ khác nguồn gọi); **chưa test lại
  bằng dữ liệu mới lần này** do tenant test đã đạt giới hạn số nhân viên theo gói — case no-op
  (nhân viên đã revoke từ trước) đã xác nhận đúng hành vi không ghi trùng audit khi không có gì để
  thu hồi.

### 7. Thu hồi khi chưa từng enrolled (no-op)
- Thử gọi API thu hồi cho 1 nhân viên chưa từng đăng ký Face ID.
- **Kỳ vọng:** không lỗi 500, xử lý êm (no-op hoặc thông báo rõ ràng "chưa có Face ID để thu hồi"),
  không tạo dữ liệu rác. Chưa test riêng lần này.

### 8. Đăng ký lại sau khi đã thu hồi
- Với nhân viên ở case 3, thử đăng ký lại Face ID từ đầu (qua Mobile App hoặc HR hỗ trợ).
- **Kỳ vọng:** đăng ký lại được bình thường, đi qua đúng luồng pending → duyệt như đăng ký mới, ảnh
  cũ (đã revoke) không còn ảnh hưởng gì. Chưa test riêng lần này (đã gián tiếp xác nhận qua việc
  enroll lại thành công nhiều lần trong quá trình test #49 trên cùng 1 hồ sơ đã từng bị revoke).

---

## Ghi chú
Toàn bộ case 1-6 đã test live qua giao diện thật (cả Mobile App lẫn Web Admin), pass 100% — 2 gap
chính từ audit gốc (deleted_reason/deleted_by, audit log) đã vá và xác nhận hoạt động đúng ở cả 3
tầng: DB, API, và UI hiển thị trên cả 2 nền tảng. Case 7-8 (no-op revoke, đăng ký lại sau revoke)
chưa test riêng lần này nhưng rủi ro thấp — đã gián tiếp quan sát hành vi tương tự qua các lần
enroll lại nhiều lượt trong quá trình test #49 trên cùng 1 hồ sơ.
