# Kịch bản test thủ công — #84 Phát hiện thiếu checkout

**Nền tảng: Backend, Web Admin, Mobile App, Queue/AI/Automation.**

## ✅ ĐÃ FIX (2026-08-17) — ĐÃ KHÓA

Quyết định nghiệp vụ (project owner, 2026-08-17): **cần gửi notification cho cả HR lẫn nhân viên**
khi phát hiện thiếu checkout.

### Thay đổi
- `AttendanceEventTypes.MISSING_CHECKOUT_EMPLOYEE` / `MISSING_CHECKOUT_HR` — 2 event type mới, thêm
  vào `NotificationEventTypeCatalog.ALL` (trước đó chỉ có 5 loại, không loại nào về chấm công).
- `UserRoleRepository.findDistinctActiveHolderIdsOfPermissionInTenant(tenantId, permissionName)` —
  method mới, trả về `Set<UUID>` userId đang giữ 1 quyền trong tenant (trước đó chỉ có bản đếm số
  lượng, không có bản trả UUID thật để gửi thông báo).
- `AttendanceSummaryService.recompute()` — sau khi lưu summary, so sánh `existing.isMissingCheckout()`
  (giá trị TRƯỚC lần lưu này) với giá trị mới: **chỉ gửi thông báo đúng 1 lần tại thời điểm
  false→true** (không gửi lại ở mọi lần tính lại sau đó của cùng 1 ngày đã được gắn cờ — tránh spam
  thông báo khi cron/HR trigger recompute lặp lại). Gửi cho: (a) `employee.userId` (chính nhân viên),
  (b) mọi userId đang giữ quyền `attendance:list` trong tenant (HR/Admin/Supervisor có quyền xem chấm
  công) — trừ trường hợp trùng với chính nhân viên đó.
- `AttendanceSummaryService.adjustSummary()` — bổ sung `AuditLogService.record(...)`, action
  `attendance_summary_adjusted`, ghi đầy đủ old/new value 8 field (totalWorkMinutes, status, late,
  lateMinutes, earlyLeave, earlyLeaveMinutes, otMinutes, missingCheckout) + reason — cùng pattern với
  `unlockAndRecompute` đã có sẵn trước đó.

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-17)

### 1. ✅ Cờ `missingCheckout` set đúng khi trigger recompute cho ngày đã qua
- Check-in không checkout, backdate `check_in_at` về "hôm qua", gọi
  `POST .../attendance/recompute?date=<hôm qua>`.
- **Kết quả thực tế:** `missingCheckout` chuyển từ `false` → `true` đúng — ĐÚNG.

### 2. ✅ Notification được tạo đúng cho CẢ HAI phía tại thời điểm chuyển cờ
- Ngay sau case 1, kiểm tra bảng `notifications`.
- **Kết quả thực tế:**
  - `MISSING_CHECKOUT_EMPLOYEE` → gửi đúng tới `user_id` của nhân viên (title "Quên check-out").
  - `MISSING_CHECKOUT_HR` → gửi đúng tới `user_id` của admin/HR tenant (title "Nhân viên quên
    check-out").
  - Xác nhận ĐÃ FIX gap "không tạo notification" — trước đây KHÔNG có thông báo nào.

### 3. ✅ HR điều chỉnh thủ công (`PATCH .../adjust`) — CÓ ghi audit log
- Gọi `PATCH .../attendance/{summaryId}/adjust` với `totalWorkMinutes` mới + `reason`.
- **Kết quả thực tế:** response 200, VÀ có đúng 1 bản ghi `audit_logs` với
  `action='attendance_summary_adjusted'`, `old_value`/`new_value` chứa đầy đủ 8 field trước/sau +
  reason trong new_value — xác nhận ĐÃ FIX gap "không ghi audit log".

### 4. HR "Mở khóa và tính lại" — vẫn giữ hành vi ghi audit sẵn có (không đổi)
- Không cần test lại — không có thay đổi ở endpoint này trong đợt fix này.

### 5. Ngày hôm nay còn phiên mở — KHÔNG bị đánh dấu thiếu checkout / KHÔNG gửi nhầm thông báo
- Giữ nguyên hành vi cũ (`date.isBefore(LocalDate.now(zone))` guard) — không bị ảnh hưởng bởi fix
  notification (trigger nằm SAU điều kiện này, không thể bắn nhầm cho ngày đang diễn ra).

### 6. Xác nhận KHÔNG có `status=partial`
- Không đổi so với audit gốc — chỉ có `present`/`incomplete`, AC gốc ghi sai tên giá trị.

---

## B. Cần test thủ công thêm (Web Admin / App)
- **Web Admin/App:** chưa xác minh trực quan hộp thư thông báo hiển thị đúng 2 loại thông báo mới —
  cần người dùng tự đăng nhập kiểm tra UI thông báo thực tế (backend đã xác nhận tạo đúng bản ghi
  trong DB qua test trên).
- Chưa test qua cron thật lúc 01:00 UTC (test dùng endpoint `recompute` thủ công tương đương, đã xác
  nhận cùng code path `recompute()` nên hành vi giống hệt).

## Regression
Toàn bộ `tests/attendance/*.sh` (9 suite) — 100% PASS sau fix, không có regression.
