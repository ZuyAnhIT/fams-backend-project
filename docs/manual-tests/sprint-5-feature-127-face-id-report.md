# Kịch bản test thủ công — #127 Báo cáo trạng thái Face ID

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — bằng chứng: `getFaceIdEnrollmentReport`,
`test_face_id_report.sh`; thiếu: không có quality score; không có endpoint export. Audit lại code
hiện tại (2026-08-18) xác nhận đúng cả 2 điểm, và phát hiện thêm 1 gap thứ 3 audit gốc bỏ sót:

- **❌ GAP #1 (đã xác nhận đúng): không có endpoint export** danh sách chưa đăng ký.
- **❌ GAP #2 (đã xác nhận đúng, nhưng hóa ra dễ vá hơn tưởng): không có quality score.** Audit kỹ
  hơn phát hiện `fams-ai` **thực sự đã tính** điểm anti-spoof (`antispoof_score`) cho từng ảnh lúc
  enroll (cả luồng chụp ảnh thường lẫn active-liveness) nhưng **vứt bỏ hoàn toàn — không lưu,
  không trả về**. Không phải "chưa có dữ liệu", mà là "có dữ liệu nhưng đang bỏ phí".
- **❌ GAP #3 (audit gốc bỏ sót): thiếu filter `siteId`** — mọi report endpoint khác trong cùng
  module (daily/monthly attendance, violations) đều có `siteId`, riêng Face ID report chỉ có
  `departmentId` (workspace), không có site.
- **✅ Enrollment date: đã có sẵn đầy đủ** (`enrolledAt` cả backend lẫn frontend) — audit gốc
  không nói rõ điểm này còn thiếu, có thể đã bị hiểu nhầm là gap; xác nhận KHÔNG phải gap.

## ✅ ĐÃ VÁ (2026-08-18)

- **Quality score — đã hỏi người dùng, chọn "làm ngay":**
  - `ai-service/app/routers/enroll.py`: cả 2 luồng enroll (`/enroll` chụp ảnh thường,
    `/enroll-from-challenge` active-liveness) giờ tính và lưu `pending_quality_score` = điểm
    anti-spoof THẤP NHẤT trong batch ảnh (ảnh yếu nhất quyết định độ tin cậy chung, không lấy
    trung bình). `/enroll/{id}/approve` promote `pending_quality_score` → `quality_score` (cùng
    pattern với `embedding`/`pending_embedding` đã có sẵn). Reject/revoke đều dọn sạch cả 2 cột.
  - Migration `V106`: thêm `face_profiles.quality_score`, `face_profiles.pending_quality_score`.
  - `FaceProfile` entity (Java) map thêm 2 cột này — an toàn vì đây là điểm số vô hướng, không
    phải vector biometric thô (khác với `embedding` cố tình không map).
  - `FaceIdReportRow` thêm field `qualityScore`. Web Admin thêm cột "Chất lượng" (hiển thị %).
- **Export not-enrolled:** `GET /reports/face-id/enrollment/export` — Excel danh sách nhân viên
  chưa đăng ký, tái dùng logic filter của endpoint chính (không trùng lặp code), có audit log
  `EXPORT_FACE_ID_NOT_ENROLLED`. Web Admin thêm nút "Xuất DS chưa đăng ký".
- **siteId filter:** thêm vào cả `GET /reports/face-id/enrollment` lẫn endpoint export, cùng
  pattern assignment-linkage đã dùng cho site-scope. Web Admin thêm dropdown công trình.

---

## A. Test trên Backend

### 1. ✅✅✅ (Case quan trọng nhất) Quality score chảy đúng end-to-end từ DB → API
- Setup: cấp consent Face ID cho 1 nhân viên (`POST .../face-id/consent`), simulate enroll
  approved với `quality_score=0.91` (do môi trường test không có ảnh khuôn mặt thật để chạy full
  luồng AI, set trực tiếp DB mô phỏng kết quả `approve`).
- **Kỳ vọng — xác nhận đúng qua live API call:** `GET /reports/face-id/enrollment` trả đúng
  `qualityScore=0.91` cho nhân viên đó, `qualityScore=null` cho nhân viên chưa đăng ký.

### 2. ✅✅ `siteId` filter hoạt động đúng
- **Kỳ vọng — xác nhận qua live API call:** filter `siteId=X` chỉ trả nhân viên có assignment tại
  site X (`totalEmployees` giảm đúng theo phạm vi site).

### 3. ✅ Export not-enrolled trả file hợp lệ
- **Kỳ vọng — xác nhận qua live API call:** HTTP 200, file `.xlsx` hợp lệ, danh sách đúng nhân
  viên `faceIdStatus=not_enrolled` trong phạm vi filter.

## B. Test trên Web Admin

### 4. ✅✅ ĐÃ TEST LIVE qua Playwright thật — dropdown công trình, cột "Chất lượng", nút Export
- Trang Quản lý Face ID: xác nhận qua DOM thật dropdown `aria-label="Lọc báo cáo theo công trình"`
  hiện đúng cạnh dropdown phòng ban, cột "Chất lượng" có trong bảng. Bấm "Xuất DS chưa đăng ký" →
  xác nhận qua `page.waitForEvent('download')` file thật được tải về thành công.

---

## Ghi chú
Cross-service: sửa cả `ai-service` (Python/FastAPI, hot-reload qua uvicorn `--reload`, không cần
rebuild container riêng) lẫn Java backend trong cùng đợt. Migration `V106` áp dụng chung cho cả
#127 (quality score) và #130 (hide polygon). `tsc --noEmit` phía Web Admin sạch.
