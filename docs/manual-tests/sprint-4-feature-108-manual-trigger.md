# Kịch bản test thủ công — #108 HR kích hoạt kiểm tra ngay

**Nền tảng: Backend, Web Admin, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "trigger_type dùng sentinel ngầm không phải field rõ ràng;
không ghi audit". Đã xác nhận lại qua code hiện tại — **1 trong 2 điểm đã SAI/lỗi thời, 1 điểm vẫn
đúng:**

- **✅ CẢI CHÍNH: "không ghi audit" — SAI, đã lỗi thời.** `ManualCheckService.trigger()` GỌI ĐÚNG
  `AuditLogService.record(...)` với action `manual_random_check_triggered`, kèm payload đầy đủ
  (employeeId/siteId/checkMode/reason/số lần đã kích hoạt trong ngày). Đây chính là ví dụ đã dùng
  làm CHUẨN THAM CHIẾU khi phát hiện #99 (hủy scheduled check) thiếu audit — module này BIẾT CÁCH
  làm đúng, chỉ áp dụng thiếu ở #99, không phải thiếu ở #108.
- **❌ GAP thật (xác nhận đúng, KHÔNG lỗi thời): `trigger_type` KHÔNG PHẢI field rõ ràng** — tìm
  kiếm toàn bộ backend không có cột/field nào tên `trigger_type`/`triggerType`. Phân biệt thủ công
  vs tự động CHỈ SUY LUẬN GIÁN TIẾP qua `checkIndex <= 0` — comment code còn ghi rõ:
  "Use check_index = 0 as a sentinel for manual checks". Không có giá trị literal `"manual_hr"`
  nào được lưu như AC mô tả.

---

## A. Test trên Backend

### 1. ✅ HR kích hoạt kiểm tra ngay cho 1 nhân viên active tại site
- **Kỳ vọng:** tạo `scheduled_check` với `scheduledAt≈now`, gửi notification ngay (không chờ job
  poll).

### 2. ✅ Chỉ chọn được nhân viên ACTIVE tại site
- Thử kích hoạt cho nhân viên không còn assignment active tại site đó.
- **Kỳ vọng:** bị từ chối hoặc không hiển thị trong danh sách chọn.

### 3. ✅ CẢI CHÍNH: xác nhận CÓ ghi audit log (khác audit gốc)
- Kích hoạt kiểm tra thủ công, kiểm tra `audit_logs`.
- **Kỳ vọng theo code hiện tại:** CÓ bản ghi `manual_random_check_triggered` đầy đủ thông tin —
  khác với audit gốc ghi "không có", cần cải chính khi báo cáo.

### 4. ❌ Xác nhận gap "trigger_type không phải field rõ ràng"
- Kiểm tra response/DB của check vừa tạo thủ công.
- **Kỳ vọng theo code hiện tại:** không có field `triggerType`, chỉ có thể suy luận gián tiếp qua
  `checkIndex=0` — xác nhận đúng gap. Ảnh hưởng thực tế: #109 (danh sách) không thể lọc theo
  "trigger_type" vì không có field này để lọc (xem #109).

---

## ✅ ĐÃ VÁ (2026-08-18) — ĐÃ KHÓA

### Case 4 — gap `trigger_type`: ĐÃ VÁ, KHÔNG CẦN MIGRATION
Phát hiện `ScheduledCheck.triggeredBy` (UUID, nullable) đã tồn tại sẵn trên entity từ trước — set
đúng cho check thủ công (`ManualCheckService`), luôn NULL cho check hệ thống tự sinh
(`ScheduledCheckGeneratorService` không set field này). Không cần thêm cột mới — chỉ cần:
- `ScheduledCheckResponse`/`ScheduledCheckDetailResponse`: thêm field derive
  `triggerType = triggeredBy != null ? "manual_hr" : "auto"`.
- `ScheduledCheckController.list()`: thêm query param `?triggerType=auto|manual_hr`, truyền
  xuống `ScheduledCheckRepository.findByTenantWithFilters(...)` (JPQL điều kiện theo
  `triggeredBy IS NULL`/`IS NOT NULL`).

### Test live — ✅ PASS (2026-08-18)
Gọi `POST .../scheduled-checks/manual` qua API thật: response trả đúng `triggerType=manual_hr`,
`manualReason` đúng giá trị đã gửi.

## Ghi chú
**Đã cải chính 1 phát hiện sai trong audit gốc (case 3) — tránh báo cáo nhầm là thiếu audit.**
Regression: 26/26. Case 1-2 rủi ro fail thấp, đã có `test_manual_check.sh` phủ.
