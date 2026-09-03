# #18 — Trang chủ app không hiện ca làm & #19 — Thông báo đẩy ngoài ứng dụng

Ngày: 2026-09-03 · Repos: `fams-backend-project`, `fams-front-app-project`

## #19 (backend) — Phân công không phát sinh thông báo / push

### Vấn đề
Toàn bộ module `assignment` **không gọi** `NotificationService.createNotification` lần nào —
tạo hoặc huỷ một phân công không tạo thông báo in-app, cũng không đẩy push. Nhân viên chỉ biết
mình có ca mới khi tự mở app. (Đối chiếu: `violation` có 4 chỗ gọi, `checkin` có 1.)

### Đã sửa
- **`AssignmentEventTypes`** (mới): `ASSIGNMENT_CREATED_EMPLOYEE`, `ASSIGNMENT_CANCELLED_EMPLOYEE`.
- **`AssignmentNotificationService`** (mới) — theo đúng khuôn mẫu `ViolationNotificationService`:
  best-effort (lỗi thông báo không được rollback phân công), gửi cho `employee.userId`, kèm
  `metadata` `{assignmentId, siteId, shiftId}` để app deep-link. Body có tên công trình + tên ca
  + giờ ca.
- **`AssignmentService`** — gọi `notifyAssignmentCreated(...)` sau `createAssignment`,
  `notifyAssignmentCancelled(...)` sau `cancelAssignment`.
- **`NotificationEventTypeCatalog`** — thêm 2 loại sự kiện (in-app + push mặc định bật; ưu tiên
  `normal` / `high`) để hiện trong màn Cài đặt thông báo.
- **`FcmClient`** — bổ sung `AndroidConfig` (priority HIGH + `channelId "fams-default"` +
  default sound) và `ApnsConfig` (sound + content-available). Trước đây message chỉ có
  `notification` trần → Android 8+ có thể **bỏ qua** vì không có channel; giờ OS luôn hiện
  heads-up kể cả khi app đã đóng.

### Test (E2E thật, backend local + Postgres)
Đăng nhập TENANT_ADMIN `duyanh19102005@gmail.com`, tạo ca `03:00–04:00` tại "Công Ty May FOFO
Cơ Sở 1", phân công cho NV `Hạnh Nguyễn Minh` (`kz.hanhxjnk129@gmail.com`):

```
POST /tenants/{tid}/sites/{site}/assignments  → 200
→ notifications: ASSIGNMENT_CREATED_EMPLOYEE
   title = "Bạn được phân công công trình mới"
   body  = "Bạn vừa được phân công tại Công Ty May FOFO Cơ Sở 1 · ca Ca Test Push 0304 (03:00–04:00)."
   priority = normal
   metadata = {siteId, shiftId, assignmentId}

DELETE .../assignments/{id}  → 204
→ notifications: ASSIGNMENT_CANCELLED_EMPLOYEE
   title = "Phân công công trình đã kết thúc"
   priority = high
```

Log `NotificationService` xác nhận `Creating notification ... eventType=ASSIGNMENT_CREATED_EMPLOYEE`
và đi tiếp vào `userDeviceService.sendPush` (FCM `phone-fams` đã init sẵn). Dữ liệu test đã dọn.

`ApiServerApplicationTests` (context load) — PASS, xác nhận bean `AssignmentNotificationService`
được wire đúng vào `AssignmentService`.

## #19 (app) — xem README bên `fams-front-app-project`

- `index.js` entry mới đăng ký `setBackgroundMessageHandler` trước `expo-router/entry`.
- Kênh Android `fams-default` (khớp `channelId` backend gửi) được tạo lúc đăng ký thiết bị và
  trong background handler.
- `resolveNotificationHref`: `ASSIGNMENT` → `/(tabs)/my-assignments`, `VIOLATION`/`EXPLANATION`
  → `/(tabs)/exceptions`, `ROLE` → `/(tabs)/profile` (trước đây `/exceptions` sai path).

> Push thật chỉ chạy trên **dev build / APK** (Expo Go SDK 54 không có Firebase messaging).
> Toàn bộ luồng đã sẵn sàng để build; cần QA xác nhận trên thiết bị sau khi build.

## #18 (app) — Trang chủ không hiện ca làm hôm nay

`app/(tabs)/home.tsx` chỉ dựa vào `/dashboard/employee`. Khi request đó lỗi (hoặc lệch múi giờ
khi tính "hôm nay"), toàn bộ danh sách ca biến mất dù màn Chấm công (dùng
`/checkin/available-sites`) vẫn có dữ liệu.

**Đã sửa** (app): widget "CA LÀM HÔM NAY" gộp 2 nguồn theo `assignmentId`, hiện **toàn bộ** ca
(không chỉ ca đầu + "+N khác"), chỉ báo lỗi khi **cả hai** nguồn lỗi. Sửa `formatTenantDate`
(one-pass, không phân biệt hoa/thường) — lỗi hiện "03/09/YYYY". Chi tiết + test ở README app.
