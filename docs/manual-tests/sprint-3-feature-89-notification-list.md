# Kịch bản test thủ công — #89 Danh sách thông báo trong app/web

**Nền tảng: Backend, Web Admin, Mobile App.**

## ✅ ĐÃ FIX (2026-08-17) — ĐÃ KHÓA

Quyết định nghiệp vụ (project owner, 2026-08-17): **cần thêm `priority` thật do backend quyết
định.** Gap "deep_link" trong audit gốc đã được xác nhận là SAI/lỗi thời trong đợt nghiên cứu trước
— giữ nguyên (đã hoạt động qua field `metadata`, không cần sửa).

### Thay đổi
- **Migration V103**: `notifications.priority VARCHAR(20) NOT NULL DEFAULT 'normal'`, CHECK
  `IN ('low','normal','high','critical')`.
- `NotificationEventTypeCatalog` — thêm `defaultPriority` cho từng eventType (7 loại):
  `RANDOM_CHECK_SENT=high`, `ROLE_REVOKED=high` (2 loại cần phản hồi/chú ý gấp), còn lại `normal`
  (`EMPLOYEE_INVITED`, `ROLE_ASSIGNED`, `MISSING_CHECKOUT_EMPLOYEE`, `MISSING_CHECKOUT_HR`) hoặc
  `low` (`INVITATION_ACCEPTED` — chỉ mang tính thông báo, không cần hành động).
- `NotificationService.createNotification` — resolve `priority` từ catalog theo `eventType` NGAY
  LÚC TẠO (snapshot, không tính lại sau này — cùng nguyên lý snapshot với các field khác trong hệ
  thống, một sửa đổi catalog trong tương lai không được đổi ngược priority của thông báo đã gửi).
  `GET /notification-event-types` cũng trả về `defaultPriority` để FE/Admin biết trước.
- Web Admin (`NotificationPage.tsx`, `NotificationBell.tsx`) và Mobile App
  (`NotificationItem.tsx`) — thêm badge/chấm màu ưu tiên, CHỈ hiển thị cho `high`/`critical` (đỏ
  "Khẩn cấp" / cam "Quan trọng") để tránh rối giao diện với trường hợp phổ biến `normal`/`low`.

---

## A. Test trên Backend — ✅ PASS, đã test live (2026-08-17)

### 1. ✅ `priority` được lưu đúng theo eventType
- Tạo notification `INVITATION_ACCEPTED` (chấp nhận lời mời).
- **Kết quả thực tế:** `priority=low` — ĐÚNG theo catalog.

### 2. ✅ `GET /notification-event-types` trả về `defaultPriority` cho cả 7 loại
- Kiểm tra response.
- **Kết quả thực tế:** đủ 7 loại, `RANDOM_CHECK_SENT`/`ROLE_REVOKED` = `high`, còn lại `normal`
  hoặc `low` đúng như thiết kế.

### 3. ✅ Filter unread/all, sort, phân trang (không đổi, vẫn đúng)
- Regression `test_notification_inbox.sh` — 16/16 pass.

---

## B. Test trên Web Admin — ✅ PASS, đã test live qua UI thật (Playwright, 2026-08-17)
- Notification `priority=critical` (test fixture, event `ROLE_REVOKED`): hiển thị badge đỏ "Khẩn
  cấp" đúng vị trí cạnh nhãn loại thông báo trên trang `/customer/notifications`.
- Notification `priority=normal` (`MISSING_CHECKOUT_HR`): KHÔNG hiển thị badge — đúng thiết kế
  "chỉ báo động khi cao/khẩn cấp".
- Bell dropdown: đã thêm chấm màu ưu tiên tương tự (chưa chụp ảnh riêng, cùng component pattern đã
  xác nhận qua trang danh sách).

## C. Test trên Mobile App — ✅ PASS, đã test live qua UI thật (Playwright, 2026-08-17)
- Notification `priority=critical` (`ROLE_REVOKED`): hiển thị badge đỏ "Khẩn cấp" đúng cạnh nhãn
  loại thông báo trên màn "Thông báo".

## Regression
`tests/notification/test_notification_inbox.sh` — 16/16 PASS, không regression. `npx tsc --noEmit`
sạch trên cả Web Admin lẫn Mobile App.
