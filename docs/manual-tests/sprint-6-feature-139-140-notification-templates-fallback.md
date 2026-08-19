# Kịch bản test thủ công — #139 Template thông báo & #140 Retry/Fallback

**Nền tảng: Backend, Web Admin, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN cho cả 2 — ghi nhận `renderTemplate()` không được gọi bởi
luồng gửi thật (CRUD thuần túy), và fallback chỉ qua email áp dụng cho mọi loại chứ không riêng
critical.

## 🔍 Phát hiện: #139 audit gốc đã lỗi thời — đã hoạt động đầy đủ, không cần vá
`NotificationService.createNotification` (chốt chặn duy nhất mọi notification đi qua) gọi
`renderTemplateIfExists(...)` TRƯỚC khi gửi — có custom template thì override title/body, không
thì dùng default. Code có sẵn comment giải thích 1 bug trước đây (dùng `renderTemplate()` throwing
gây rollback transaction) đã được fix từ trước bằng bản không-throw. **Không có thay đổi code cho
#139** — chỉ audit lại + test live.

## ✅ #140 — ĐÃ VÁ gap thật: fallback không còn áp dụng cho mọi loại
Retry+backoff (3 lần, 1s/2s exponential, bỏ sớm nếu token dead hẳn) đã đúng từ trước, không sửa.
Gap thật: fallback email trước đây chạy vô điều kiện cho MỌI eventType khi push thất bại toàn bộ
device — sai AC ("fallback... cho priority critical"). Đã thêm tham số `fallbackEligible` vào
`UserDeviceService.sendPush`, `NotificationService.createNotification` chỉ bật fallback khi
`NotificationEventTypeCatalog.defaultPriorityFor(eventType) == "critical"`.

**Phát hiện phụ, đã hỏi người dùng**: hệ thống trước đó không có eventType nào ở mức "critical"
(cao nhất là "high") — áp đúng AC sẽ vô tình tắt hẳn fallback cho MỌI loại (regression). Quyết
định: nâng `RANDOM_CHECK_SENT` từ "high" lên "critical" (thông báo bắt buộc, nhạy cảm thời gian
nhất hệ thống, không được tắt theo #141) — các eventType "high" khác giữ nguyên. FE (Web + Mobile)
đã sẵn xử lý giá trị "critical" (badge đỏ "Khẩn cấp") từ trước, không cần đổi gì.

**SMS fallback**: KHÔNG có trong hệ thống (chưa có SMS provider client nào) — nằm ngoài phạm vi vá
lần này, cần thêm hạ tầng mới. Ghi nhận là hạng mục riêng nếu cần.

---

## A. Test trên Backend (API thật)

### 1. ✅ #139 — Template override đúng nội dung khi gửi thật
- `tests/notification/test_notification_templates.sh`: PASS 29/29 (CRUD, validation, multi-locale,
  409 duplicate, xóa).
- Test bổ sung thủ công qua Web Admin UI (xem mục B) — tạo template thật cho
  `RANDOM_CHECK_SENT`/vi.

### 2. ✅ #140 — Fallback đúng theo priority (case quan trọng nhất)
- Setup: đăng ký fake FCM device (luôn fail) cho 1 nhân viên test.
- Gửi `RANDOM_CHECK_SENT` (nay là priority=critical) → push fail 3/3 device → **có** dòng
  `EMAIL_FALLBACK` / `FALLBACK_EMAIL_SENT` trong `notification_delivery_logs`.
- Gửi `EMPLOYEE_INVITED` (priority=normal) tới cùng kiểu fake device → push fail nhưng **KHÔNG**
  có dòng fallback nào — đúng thiết kế mới, xác nhận qua query DB trực tiếp.
- `tests/notification/test_fcm_retry_fallback.sh` cập nhật dùng `RANDOM_CHECK_SENT` thay eventType
  giả `TEST_RETRY` (không thuộc catalog → priority "normal" → không còn test được đường fallback
  nữa với logic mới) — PASS 11/11.
- Regression toàn bộ suite notification: templates 29/29, settings 24/24, inbox 16/16, mark-read
  13/13, devices 13/13, dispatch 9/9; audit 14/14 — không lỗi.

## B. Test trên Web Admin (Playwright, trình duyệt thật)

### 1. ✅ #139 — CRUD template qua UI thật
- `/customer/settings/notification-templates` → "Tạo template" → modal hiện sẵn event type
  `RANDOM_CHECK_SENT`, chọn ngôn ngữ, nhập tiêu đề/nội dung có placeholder `{checkId}`/`{siteId}`/
  `{expiresAt}`, có khối "Xem trước với dữ liệu mẫu".
- Submit → toast "Đã tạo template. Không cần thao tác kích hoạt thêm." → bảng list cập nhật ngay,
  hiện đúng template vừa tạo (badge "VI", tiêu đề/nội dung đúng).
- Đã xóa template test này ngay sau khi xác nhận (qua API DELETE) để không để lại dữ liệu rác.

## C. Mobile App / Web Admin — Priority "critical" badge
Không cần sửa gì — cả 2 FE đã có sẵn logic hiển thị `priority === 'critical'` (badge đỏ "Khẩn cấp")
từ trước khi #140 được vá (kiểm tra qua source: `NotificationBell.tsx` dòng 101-106 và
`NotificationItem.tsx` dòng 72-87 đều đã liệt kê `'critical'` trong type union và JSX).
**Khuyến nghị bạn tự kiểm tra 1 lần trên UI thật** (đăng nhập, nhận 1 thông báo `RANDOM_CHECK_SENT`
mới) để xác nhận badge đỏ "Khẩn cấp" hiển thị đúng — tôi chưa tạo được 1 luồng random-check thật
đủ để trigger UI đó trong phiên làm việc này (cần thiết bị/site với cấu hình geofence + một nhân
viên đang trong ca).

---

## Ghi chú
`#139`: không sửa code, chỉ audit lại + xác nhận qua test API thật và UI thật.
`#140`: sửa `UserDeviceService.java`, `NotificationService.java`,
`NotificationEventTypeCatalog.java` (RANDOM_CHECK_SENT high→critical), cập nhật
`tests/notification/test_fcm_retry_fallback.sh`. Đã compile sạch, rebuild Docker, redeploy
`fams-api`, test live qua API thật + query DB xác nhận, chạy lại toàn bộ regression liên quan.
