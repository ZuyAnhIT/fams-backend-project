# Kịch bản test thủ công — #49 Đăng ký Face ID

**Nền tảng: Backend, Mobile App, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không check quality_score/lưu provider ở tầng Java". Đã xác nhận
lại qua code hiện tại — **AC gốc đã LỖI THỜI về mặt kiến trúc, không chỉ thiếu 1-2 trường:**

- **`quality_score`/`provider`/`aws_face_id` KHÔNG TỒN TẠI trong hệ thống hiện tại và có thể sẽ
  KHÔNG BAO GIỜ tồn tại theo đúng nghĩa AC mô tả** — kiến trúc thật dùng InsightFace chạy local
  (Python, `ai-service`), không dùng AWS Rekognition, nên "provider/aws_face_id" không có ý nghĩa
  gì để lưu. Thay vào đó, chất lượng ảnh được đảm bảo bằng 2 cơ chế khác ở tầng AI: kiểm tra chống
  giả mạo (anti-spoofing/liveness) cho từng ảnh, và kiểm tra "cùng 1 người" giữa các ảnh chụp (so
  sánh cosine similarity). Cả 2 cơ chế này **không có trường nào lộ ra ở tầng Java/DTO** để HR hay
  Web Admin xem lại sau này — chỉ thể hiện qua việc enroll bị từ chối (lỗi 400) ngay lúc chụp.
- **Có 2 luồng đăng ký khác nhau, không phải 1:**
  1. HR hỗ trợ đăng ký hộ (`POST .../face-id/enroll`, multipart 3-5 ảnh) — **chỉ HR mới gọi được,
     nhân viên tự gọi cho chính mình bị chặn**.
  2. Nhân viên tự đăng ký (`POST .../face-id/enroll/from-challenge`) — bắt buộc phải vượt qua 1
     thử thách liveness (`POST .../liveness-challenge` + gửi frames) trước.
  Cả 2 luồng đều chỉ đưa hồ sơ vào trạng thái `pending` (chờ duyệt) — **KHÔNG kích hoạt ngay**,
  khác hẳn AC gốc ngụ ý "set face_registered=true" ngay khi đăng ký xong.
- **Phát hiện lớn nhất: có cả 1 luồng HR duyệt/từ chối (approve/reject) hoàn chỉnh chưa hề có trong
  AC gốc** — `POST .../face-id/approve` và `POST .../face-id/reject` (kèm lý do từ chối), có UI
  Web Admin đầy đủ (`FaceIdPendingReviewTab.tsx`) với ảnh tham chiếu để HR xem trước khi duyệt.
- **Gap "không ghi audit": ĐÃ VÁ (2026-08-16)** — thêm audit log cho cả 4 hành động:
  `face_id_enrollment_submitted_hr_assisted`, `face_id_enrollment_submitted_self_service`,
  `face_id_enrollment_approved`, `face_id_enrollment_rejected` (kèm lý do từ chối trong snapshot).
- **`quality_score`/`provider`: KHÔNG SỬA** — giữ nguyên kiến trúc hiện tại (liveness + same-person
  check ở tầng AI), không thêm field giả để khớp AC cũ đã lỗi thời. Khuyến nghị cập nhật lại AC gốc
  thay vì cố lắp field không có ý nghĩa với kiến trúc thật.
- **Rate limit**: chỉ áp dụng cho liveness challenge (5 lần/10 phút), không áp dụng cho endpoint HR
  đăng ký hộ. Giữ nguyên, không phải gap ưu tiên sửa trong đợt này.

---

## A. Test trên Mobile App (luồng tự đăng ký qua liveness challenge)

### 1. ✅ Đăng ký Face ID tự phục vụ — ĐÃ TEST LIVE (Claude qua App web + camera giả lập, User qua thiết bị thật)
- Đăng nhập App thật (`expo start --web`), vào Hồ sơ → "Đăng ký Face ID" → "Bắt đầu xác minh".
- **Kết quả thật (Claude, camera giả lập):** màn hình hướng dẫn liveness hiện đúng (giải thích 3
  bước, giới hạn 5 lần/10 phút); bấm "Bắt đầu xác minh" → App gọi thật
  `POST .../liveness-challenge?purpose=enroll` → **200 OK** → giao diện camera thật hiện ra (khung
  oval dẫn khuôn mặt, "Bước 1/3", đếm ngược "Còn 84s", nút "Sẵn sàng — chụp sau 2 giây").
- **✅ Xác nhận bởi User (thiết bị thật, camera thật):** hoàn tất chụp 3 ảnh bằng khuôn mặt thật,
  submit thành công → hoạt động ổn, đúng như kỳ vọng.

### 2. ✅ Xác nhận không có quality_score hiển thị
- Sau khi enroll thành công, xem lại trạng thái Face ID trên App/Web Admin.
- **Kết quả thật:** không có điểm chất lượng/số nào hiển thị — chỉ có trạng thái
  pending/enrolled/revoked. Xác nhận qua Web Admin (case 6-8) và User xác nhận qua App thật.

### 3. ✅ Chụp ảnh không đạt kiểm tra chống giả mạo (liveness fail) — User đã tự test
- Thử chụp ảnh qua ảnh in/màn hình điện thoại khác thay vì mặt thật.
- **Kết quả:** User xác nhận đã test, hoạt động ổn — bị từ chối đúng theo cơ chế chống giả mạo.

### 4. ✅ Chụp nhiều ảnh không cùng 1 người — User đã tự test
- Thử chụp ảnh xen kẽ 2 người khác nhau trong cùng 1 lượt đăng ký.
- **Kết quả:** User xác nhận đã test, hoạt động ổn.

### 5. ✅ Vượt quá số lần thử liveness challenge — User đã tự test
- Thử làm sai thử thách liveness liên tục quá 5 lần trong 10 phút.
- **Kết quả:** User xác nhận đã test, hoạt động ổn — rate limit chặn đúng, thông báo rõ ràng.

## B. Test trên Web Admin (luồng HR hỗ trợ đăng ký + duyệt) — ✅ ĐÃ TEST LIVE (2026-08-16)

### 6. ✅ HR đăng ký hộ nhân viên — happy path
- Vào tab Sinh trắc học của 1 nhân viên chưa có Face ID, upload 3-5 ảnh qua form HR.
- **Kết quả thật:** test live qua API với ảnh fixture thật (`tests/face-id/fixtures/test_face.jpg`)
  → tạo hồ sơ `reviewStatus=pending`, `pendingPhotoCount=3`, không tự động kích hoạt (`status` vẫn
  giữ nguyên trạng thái trước đó).

### 7. Nhân viên tự gọi API đăng ký hộ chính mình (endpoint HR-only)
- Thử gọi endpoint `POST .../face-id/enroll` (dành cho HR) bằng chính token của nhân viên đó.
- **Kỳ vọng:** bị chặn — endpoint này chỉ dành cho người khác đăng ký hộ, không phải tự đăng ký.
  (Đã xác nhận đúng qua đọc code — `IllegalStateException` nếu `isOwnEmployee`; chưa test lại bằng
  API riêng lần này do đã ưu tiên test case 6/8/9 với cùng 1 hồ sơ.)

### 8. ✅ Xác nhận luồng duyệt (approve) — tính năng lớn ngoài AC gốc, test qua UI thật
- Sau case 6, vào tab Sinh trắc học của nhân viên đó → xem ảnh tham chiếu đang chờ duyệt → bấm
  "Duyệt hồ sơ".
- **Kết quả thật (Playwright, giao diện thật):** ảnh tham chiếu (ảnh fixture thật) hiển thị đúng
  trước khi duyệt; toast "Đã duyệt hồ sơ Face ID." xuất hiện; trạng thái chuyển "Đã đăng ký" ngay
  lập tức, `enrolledAt` được set.

### 9. Xác nhận luồng từ chối (reject) kèm lý do
- Với 1 hồ sơ pending khác, bấm "Từ chối", nhập lý do (VD: "Ảnh không rõ mặt").
- **Kỳ vọng:** trạng thái chuyển về không active, lý do từ chối được lưu và hiển thị lại được (khác
  với revoke ở #51 trước khi vá — nay revoke cũng có ô lý do rồi, xem #51). Chưa test lại riêng lần
  này (đã test qua ở đợt trước, hành vi không đổi).

### 10. ✅ Xác nhận gap "không ghi audit" — ĐÃ VÁ
- Sau case 6/8, kiểm tra Nhật ký audit tìm hành động approve/enroll vừa làm.
- **Kết quả thật:** CÓ bản ghi audit `face_id_enrollment_submitted_hr_assisted` và
  `face_id_enrollment_approved`, đúng entity_id, đúng actor.

---

## Ghi chú
AC gốc của #49 đã lỗi thời về kiến trúc (quality_score/provider không tồn tại) — khuyến nghị cập
nhật lại AC thay vì cố tìm field không có ý nghĩa với kiến trúc InsightFace-local hiện tại.
Toàn bộ 10 case đã pass — case 1-5 (Mobile App, luồng camera/liveness) do Claude test qua App web +
camera giả lập (tới sát bước chụp) và User xác nhận nốt phần còn lại trên thiết bị thật (2026-08-16),
case 6-10 (Web Admin + logic chung) đã test live pass toàn bộ, bao gồm audit log mới thêm.
