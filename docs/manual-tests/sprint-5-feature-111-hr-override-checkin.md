# Kịch bản test thủ công — #111 HR override check-in

**Nền tảng: Backend, Web Admin, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "không ghi audit". Đã xác nhận lại qua code hiện tại —
**GAP ĐÃ ĐƯỢC VÁ TỪ TRƯỚC — chính là 1 phần của công việc đã hoàn thành SỚM HƠN TRONG PHIÊN LÀM VIỆC
NÀY (đợt #77-79, tính năng #79 "HR xem chi tiết check-in" đã thêm audit cho đúng endpoint override
này). Không lỗi thời theo nghĩa "audit gốc sai", mà là gap CÓ THẬT lúc 07-22, ĐÃ TỰ ĐỘNG ĐƯỢC GIẢI
QUYẾT nhờ công việc trước đó, giờ chỉ cần XÁC NHẬN LẠI qua test live, không cần sửa thêm:**

- **✅ Kiểm tra quyền đúng 2 lớp:** permission `checkins:list` + site-scope (`siteScopeService.
  isSiteAllowed`) — chỉ người có quyền VÀ đúng site mới override được.
- **✅ ĐÃ CÓ AUDIT LOG:** `recordAudit(tenantId, callerUserId, record.getId(), "checkin_overridden",
  Map.of("oldStatus", ..., "newStatus", ..., "reason", ...))` — gọi ngay sau khi lưu, đầy đủ before/
  after.
- **✅ Cập nhật summary:** gọi `attendanceSummaryService.recomputeForCheckin(record)` ngay sau khi
  override — bảng công phản ánh đúng ngay, không cần đợi job đêm.
- **⚠️ Làm rõ khác biệt nhỏ so với AC (không phải gap):** AC ghi "set status manual_override" nhưng
  status thực tế CHỈ nhận 2 giá trị `valid`/`rejected` (validate bằng `@Pattern`) — KHÔNG có giá trị
  literal "manual_override" nào. Về bản chất chức năng vẫn đúng ý AC (HR quyết định chấp nhận hay
  từ chối 1 check-in đang lỗi), chỉ khác tên gọi.

---

## A. Test trên Backend

### 1. ✅ Override check-in `pending_review` → `valid`
- **Kỳ vọng:** `status=valid`, `attendance_summary` liên quan được tính lại ngay.

### 2. ✅ Override check-in → `rejected`
- **Kỳ vọng:** `status=rejected`.

### 3. Giá trị status không hợp lệ (khác `valid`/`rejected`)
- Thử truyền `status="manual_override"` (đúng literal AC ghi).
- **Kỳ vọng:** bị từ chối 400 — xác nhận đúng 2 giá trị hợp lệ duy nhất.

### 4. ✅ Không có quyền — bị từ chối
- User không có `checkins:list`, hoặc đúng quyền nhưng KHÁC site.
- **Kỳ vọng:** 403 ở cả 2 trường hợp.

### 5. ✅ Xác nhận CÓ ghi audit log
- Override 1 check-in, kiểm tra `audit_logs`.
- **Kỳ vọng:** có bản ghi `checkin_overridden` với đủ `oldStatus`/`newStatus`/`reason`.

---

## ✅ PASS — ĐÃ KHÓA (2026-08-18)

Xác nhận lại qua regression suite thật (`test_override_checkin.sh`, 1 trong 31/31 script PASS
trong đợt test #111-115): audit log `checkin_overridden` vẫn ghi đúng đầy đủ oldStatus/newStatus/
reason, permission 2 lớp (checkins:list + site-scope) hoạt động đúng, attendance summary được
tính lại ngay sau override.

## Ghi chú
**Không có gap cần vá — gap của audit gốc đã tự động được giải quyết nhờ công việc trước đó trong
cùng phiên làm việc.**
