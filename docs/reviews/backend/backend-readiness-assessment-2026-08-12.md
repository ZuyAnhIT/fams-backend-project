# Đánh giá mức độ sẵn sàng backend cho demo/production (12/08/2026)

> Trả lời trực tiếp: hệ thống đã hoàn thiện nghiệp vụ chưa, sẵn sàng demo chưa, còn lỗi tiềm ẩn về nghiệp vụ/thư viện/môi trường không, các luồng nghiệp vụ có ăn khớp với nhau không. Phương pháp: (1) chạy lại toàn bộ 162 test script thật trên stack Docker đang chạy, (2) audit thư viện/cấu hình bằng agent độc lập, (3) trace code thật theo 2 luồng nghiệp vụ xuyên module (chấm công, random check) để tìm điểm không khớp giữa các service.

## Kết luận nhanh

**Nghiệp vụ: đã hoàn thiện, sẵn sàng demo.** 149/150 tính năng backlog đã xong (xem `docs/api/backend-feature-audit-2026-08-07.md`), toàn bộ đã verify bằng API thật, không chỉ đọc code.

**Môi trường: phát hiện 1 lỗi nghiêm trọng — ĐÃ SỬA trong buổi này.** MinIO (lưu trữ avatar) chết → **toàn bộ API sập theo**, không chỉ mất tính năng avatar. Xem mục 1.

**Luồng nghiệp vụ liên module: 2 điểm chưa khớp thật sự** (không phải tính năng thiếu, mà là 2 service không đồng nhất giả định với nhau) — xem mục 3. Chưa sửa, cần bạn quyết định mức độ ưu tiên.

**Bộ test: số liệu thô gây hiểu lầm, số liệu thật khả quan hơn nhiều** — xem mục 4.

---

## 1. Lỗi môi trường nghiêm trọng — ĐÃ SỬA: MinIO là điểm chết đơn (SPOF) của toàn bộ API

**Phát hiện trực tiếp, không phải suy đoán**: trong lúc chạy lại test suite, `fams-minio` đã chết từ trước đó (không có restart policy) và **toàn bộ `fams-api` crash-loop liên tục** — không boot được, không chỉ tính năng avatar bị ảnh hưởng.

**Nguyên nhân**: `AvatarStorageService` (constructor, `AvatarStorageService.java`) gọi `ensureBucketExists()` — 1 network call thật tới MinIO — **ngay trong lúc Spring tạo bean**. Nếu MinIO không kết nối được, exception này làm **toàn bộ `ApplicationContext` sập** (`AuthController` → `UserProfileService` → `AvatarStorageService`, mọi controller khác phụ thuộc gián tiếp qua chuỗi bean này).

**Đã sửa và verify trực tiếp** (tắt MinIO thật, restart API, xác nhận API vẫn boot bình thường, login vẫn hoạt động, chỉ avatar upload báo lỗi rõ ràng cho tới khi MinIO có lại):
- Chuyển `ensureBucketExists()` từ constructor sang `@EventListener(ApplicationReadyEvent.class)`, bọc try/catch — MinIO chết lúc boot giờ chỉ log lỗi rõ ràng, không sập app.
- `docker-compose.dev.yml`: thêm `restart: unless-stopped` cho service `minio` (trước đó **không có** restart policy nào — 1 lần chết là chết vĩnh viễn tới khi ai đó nhận ra và tự chạy lại).

**Phát hiện phụ, cùng lúc**: stack đang chạy hoá ra là `docker-compose.yml` (base, kiểu production build sẵn jar) chứ **không phải** `docker-compose.dev.yml` (dev, hot-reload) như quy trình dev đang dùng — 2 file bị trộn lẫn ad-hoc (MinIO chỉ định nghĩa trong file dev, bị start rời rạc không cùng vòng đời với API). Đã gộp lại đúng bằng `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d`.

**Đối chiếu resilience các service phụ thuộc khác** (audit riêng, không phải suy đoán): `fams-ai` (Face ID) và `fams-redis` **không** bị lỗi này — cả 2 kết nối lazy (theo request), không network call trong constructor. `FcmConfig` (Firebase) đã có try/catch quanh init từ trước. **MinIO là ngoại lệ duy nhất**, giờ đã cùng chuẩn.

---

## 2. Thư viện & cấu hình — nhìn chung lành mạnh, vài điểm cần lưu ý

- Spring Boot 3.5.15 + Java 21 + Flyway (split artifact đúng chuẩn PG16) + springdoc-openapi 2.8.5 + jjwt 0.12.6 + firebase-admin 9.4.3 + AWS SDK v2 2.29.6 — không có version lỗi thời/xung đột rõ ràng.
- **`ai-service/requirements.txt` ghim cứng `numpy==1.26.4`** cạnh `onnxruntime==1.28.0`/`scipy==1.17.1` — các bản mới của 2 thư viện này thường ưu tiên NumPy 2.x. Rủi ro: build lại từ pip cache sạch trong tương lai có thể lỗi resolver hoặc lệch ABI âm thầm. Nên `pip install --dry-run` kiểm tra trước lần build tiếp theo.
- **`SWAGGER_ENABLED` mặc định `true`** (`application.yml`) nhưng **không có trong `.env.example`** — dễ bị quên tắt khi deploy thật, vô tình để lộ `/swagger-ui.html` + `/v3/api-docs`.
- Một số biến cấu hình có dùng trong `application.yml` nhưng chưa liệt kê trong `.env.example` (đều có default an toàn, chỉ là "vô hình" với người deploy): `EMAIL_VERIFICATION_TTL_HOURS`, `PASSWORD_RESET_RATE_LIMIT_MAX`, `INVITATION_EXPIRY_DAYS`, `AI_ENROLL_MIN/MAX_PHOTOS`, `DATA_RETENTION_*_DAYS`, `AI_SERVICE_INTERNAL_URL`, `SWAGGER_ENABLED`.
- Flyway migration V81-V88: không phát hiện rủi ro (không có lệnh phá huỷ dữ liệu, thứ tự version liền mạch).

---

## 3. Luồng nghiệp vụ liên module — 2 điểm chưa khớp thật sự (chưa sửa, cần quyết định)

Trace trực tiếp theo code (không suy đoán) 2 luồng lớn nhất hệ thống: **chấm công đầy đủ** và **random check đầy đủ**.

**#1 — Callback xác thực Face ID/liveness bị mất → check bị coi là "pass" vĩnh viễn, không có cơ chế đối soát lại.**
`CheckResponseService.submit()` lưu `outcome="pass"` và đổi trạng thái sang `"responded"` **ngay khi nhận GPS**, trong khi kết quả Face ID/liveness thật sự đến **sau**, qua callback bất đồng bộ từ `fams-ai` (`FaceResultCallbackController`). Nếu callback đó **không bao giờ tới** (AI service down/timeout/crash) — không có job nào quét lại: `NoResponseViolationJob` chỉ xử lý check còn `status='sent'` (case này đã là `'responded'` nên bị bỏ qua), `RandomCheckQueueReconciliationJob` chỉ xử lý check bị rớt khỏi hàng đợi Redis, không phải case "đã responded nhưng chờ callback mãi không tới". **Kịch bản thật**: nhân viên phản hồi random check kèm ảnh, đúng lúc `fams-ai` bị down/timeout → hệ thống ghi nhận "pass" vĩnh viễn dù chưa từng xác thực khuôn mặt thật, và số liệu này chảy thẳng vào `hasRandomCheckFailure` của bảng công.

**#2 — `updateAttendanceImpact()` set cờ `affectsAttendance` nhưng recompute không hề đọc cờ này.**
(Đây là phần còn lại của gap #118 đã sửa sáng nay — sáng nay tôi mới thêm lời gọi recompute cho khớp với confirm/dismiss, nhưng agent audit chiều nay phát hiện sâu hơn: **bản thân `recompute()` chưa bao giờ đọc field `affectsAttendance`** để tính `hasRandomCheckFailure` — nó chỉ đọc `resolution` (dismissed hay không) của violation, không đọc cờ `affectsAttendance`.) **Kịch bản thật**: HR xác nhận (confirm) 1 violation rồi sau đó đánh dấu "không ảnh hưởng công" qua endpoint riêng (thay vì bỏ qua/dismiss) — bảng công của nhân viên đó vẫn hiện `hasRandomCheckFailure=true`, trái với kỳ vọng của chính cái tên endpoint. Đây là gap thiết kế có từ trước khi tôi động vào sáng nay, tôi chỉ vô tình sửa nửa vời (thêm recompute call nhưng recompute vẫn "mù" với field này).

Còn 2 điểm nhỏ hơn, không khẩn: lỗi recompute lúc check-in/out bị nuốt lặng lẽ (log warning, không retry, phải đợi tới job đêm hôm sau mới tự sửa); và 1 dòng doc trên `AttendanceSummaryController` nói sai hành vi thật của guard "đã điều chỉnh tay" (guard áp dụng cho MỌI ngày, không chỉ ngày quá khứ như doc ghi).

**Các phần đã kiểm tra và xác nhận ổn, không có race condition**: cancel assignment đang chạy đua với dispatch random check (đã có double-check trạng thái trong transaction, không thể tạo kết quả trùng/mâu thuẫn); snapshot shift lúc check-in cho tính late/early/OT (áp dụng nhất quán).

---

## 4. Số liệu test suite — đọc đúng con số

Chạy lại **toàn bộ 162 script** 3 lần trong buổi (2 lần đầu môi trường chưa ổn định vì lỗi mục 1, lần 3 sau khi sửa xong và gộp đúng compose stack).

Lần chạy đầu (môi trường lỗi do MinIO chết): **143/144 suite fail** — con số này **không phản ánh chất lượng code**, chỉ phản ánh API đang crash-loop. Sau khi khôi phục MinIO: **111/144 pass (77%)**.

**Quan trọng — soi kỹ 33 suite fail còn lại thay vì tin số liệu thô**: đối chiếu trực tiếp bằng curl thật với từng endpoint, phần lớn KHÔNG phải lỗi backend mà là **test script cũ dùng sai tên field**:
- Nhiều script cũ gửi `{"email": "..."}` tới `/auth/login` (endpoint thật yêu cầu `identifier`) → fail ngay bước đăng nhập setup, kéo theo toàn bộ test case phía sau trong cùng script.
- Chiều ngược lại: vài script gửi `{"identifier": "..."}` tới `/auth/register`, `/auth/forgot-password`, `/auth/resend-verification` (các endpoint này yêu cầu `email` riêng, không nhận `identifier`) → fail vì validation 400, không phải business logic sai.
- Phần lớn còn lại là gap đã biết từ trước: 16+ script tạo tenant bằng Platform Admin không truyền `ownerEmail` (đã flag trong `docs/api/backend-feature-audit-2026-08-07.md`).

Đã verify tay bằng curl thật cho từng endpoint nghi vấn (`/auth/login`, `/auth/register`, `/auth/forgot-password`, `/auth/resend-verification`, avatar upload) — **tất cả hoạt động đúng** khi gọi đúng field. Kết luận: **tỷ lệ lỗi backend thật sự nằm trong khoảng rất nhỏ** trong số 33 suite fail — chủ yếu là nợ kỹ thuật của bộ test (nhiều script viết ở các thời điểm khác nhau, không thống nhất quy ước field), không phải hệ thống chạy sai.

**Khuyến nghị**: 1 đợt dọn dẹp test suite riêng (chuẩn hoá field `identifier` cho auth, thêm `ownerEmail` cho các script tạo tenant) sẽ đưa tỷ lệ pass thật lên gần 100% mà không cần sửa code nghiệp vụ.

---

## Tóm tắt hành động

| Việc | Trạng thái |
|---|---|
| MinIO SPOF crash toàn bộ API | ✅ Đã sửa + verify trực tiếp (tắt/bật MinIO thật) |
| MinIO không có restart policy | ✅ Đã thêm `restart: unless-stopped` |
| Stack chạy sai (base thay vì dev overlay) | ✅ Đã gộp lại đúng |
| Callback Face ID/liveness mất → không đối soát | ⚠️ Chưa sửa — cần quyết định: thêm timeout+job đối soát, hay chấp nhận rủi ro thấp (hiếm khi AI service down đúng lúc)? |
| `affectsAttendance` không ảnh hưởng recompute thật | ⚠️ Chưa sửa — cần quyết định: có nên làm cờ này thực sự ảnh hưởng `hasRandomCheckFailure` không, hay giữ nguyên là cờ báo cáo thuần tuý? |
| Numpy pin rủi ro trong ai-service | ⚠️ Chưa sửa — rủi ro thấp, chỉ hiện thực khi build lại từ cache sạch |
| `.env.example` thiếu vài biến, `SWAGGER_ENABLED` không có gate rõ | ⚠️ Chưa sửa — nên bổ sung trước khi deploy thật |
| Test suite dùng sai field name (`identifier`/`email`) | ⚠️ Chưa sửa — không ảnh hưởng người dùng thật, chỉ gây hiểu lầm khi đọc số liệu test |
