# API Reference: Notifications (In-app + Push)

> Cập nhật theo code đang chạy ngày 2026-08-05, sau audit nghiệp vụ (`docs/reviews/backend/security-notifications-audit-2026-08-05.md`) và bản vá P1 cùng ngày theo phản hồi FE (mục 0). Đây là tài liệu FE **đầu tiên** cho toàn bộ module thông báo — module đã tồn tại và hoạt động đầy đủ từ trước (qua nhiều đợt vá lỗi được ghi lại trong code) nhưng chưa từng được viết thành tài liệu bàn giao.

## 0. Bản vá P1 (cùng ngày 2026-08-05) — trả lời phản hồi FE về danh mục event type

**FE báo cáo**: `GET /me/notification-settings` chỉ trả những dòng user đã từng cấu hình, không có danh mục đầy đủ; backend chưa xác nhận chính thức các event type nào tồn tại (chỉ chắc chắn `RANDOM_CHECK_SENT`); cần catalog/enum chính thức để không phải đoán chuỗi.

**Xác nhận đúng** — kiểm tra lại toàn bộ codebase: `RANDOM_CHECK_SENT` là **event type thật duy nhất** hiện có (constant `RandomCheckEventTypes.RANDOM_CHECK_SENT`, dùng bởi random-check module — module duy nhất hiện tạo notification qua code). Nghiêm trọng hơn: **ví dụ Swagger trên mọi DTO liên quan trước đây ghi `RANDOM_CHECK_DISPATCHED`** — 1 chuỗi CHƯA TỪNG tồn tại thật trong code, khiến tài liệu tự mâu thuẫn với chính hệ thống. Đã sửa:

1. **`GET /api/v1/notification-event-types`** (mới) — catalog chính thức, xem mục 6.1.
2. **`GET /me/notification-settings`** giờ luôn trả về ít nhất toàn bộ catalog (kèm `label`, `customized`) — xem mục 6.2, không còn rỗng với user chưa từng cấu hình.
3. Sửa toàn bộ ví dụ Swagger sai `RANDOM_CHECK_DISPATCHED` → `RANDOM_CHECK_SENT` (đúng giá trị thật).

---

## 0.1 Bản vá 2026-08-06 — Template thông báo giờ thực sự có tác dụng, thêm delivery-log cho admin

**Quan trọng cho FE quản trị (màn "Quản lý template thông báo" nếu đã/đang dựng)**: `POST/PUT/DELETE /api/v1/tenants/{tenantId}/notification-templates` (CRUD template theo `eventType` + `locale`) đã tồn tại từ trước nhưng **trước ngày 2026-08-06 không có tác dụng thật** — sửa/xoá template không ảnh hưởng gì tới nội dung thông báo thực tế gửi đi. Đã sửa: từ nay, nếu tenant có template khớp đúng `eventType` + locale của tenant (`tenants.locale`, mặc định "vi"), title/body của template sẽ **ghi đè** nội dung mặc định của hệ thống. Không có template khớp → dùng nguyên text mặc định, không lỗi.

- FE màn quản lý template: có thể yên tâm rằng lưu template giờ có tác dụng thật ngay lần gửi thông báo tiếp theo — không cần thêm bước "áp dụng"/"kích hoạt" nào khác.
- Biến `{tenVarName}` trong `titleTemplate`/`bodyTemplate` được thay bằng đúng field tương ứng trong `metadata` của thông báo đó (ví dụ với `RANDOM_CHECK_SENT`: `{checkId}`, `{siteId}`, `{expiresAt}`) — field nào không có trong `metadata` sẽ giữ nguyên dạng `{tenBien}` chưa thay, không lỗi, không ẩn đi.
- **Mới**: `GET /api/v1/platform/notifications/delivery-logs` (PLATFORM_ADMIN) — xem lịch sử gửi push/email, lọc theo `status` (`SUCCESS`/`FAILED`/`FALLBACK_EMAIL_SENT`/`FALLBACK_EMAIL_FAILED`), `channel`, khoảng thời gian. Dùng khi cần tra "tại sao user X không nhận được thông báo". `deviceToken` trong response bị che, chỉ hiện 6 ký tự cuối.

---

Bao gồm 5 user story:
- *Hệ thống tạo thông báo in-app cho user để có nền cho các module sau.*
- *Nhân viên dùng app đăng ký FCM token cho thiết bị để nhận thông báo realtime.*
- *Hệ thống gửi push đến thiết bị người dùng để thông báo kịp thời.*
- *Người dùng xem các thông báo trong hộp thư để không bỏ lỡ thông tin quan trọng.*
- *Người dùng đánh dấu một hoặc nhiều thông báo đã đọc để quản lý inbox gọn gàng.*

---

## 1. Khái niệm nền tảng

### 1.1 In-app và Push là 2 kênh độc lập hoàn toàn

Mỗi `eventType` (hiện tại chính thức chỉ có `RANDOM_CHECK_SENT` — xem mục 0 và 6.1) có 2 cờ bật/tắt riêng cho từng user: `inAppEnabled` và `pushEnabled`. **Tắt 1 cái không ảnh hưởng cái kia** — user có thể tắt in-app (không muốn thấy trong hộp thư) nhưng vẫn nhận push, hoặc ngược lại. FE cần dựng 2 toggle riêng biệt cho mỗi loại sự kiện trong màn Cài đặt thông báo, không gộp thành 1 công tắc.

Event type không có setting row nào = **mặc định bật cả 2** (opt-out model, không phải opt-in) — đây là lựa chọn có chủ đích để user không bỏ lỡ thông báo quan trọng chỉ vì chưa từng vào cài đặt.

### 1.2 Push có cơ chế dự phòng qua Email

Nếu **tất cả** thiết bị đã đăng ký của user đều gửi push thất bại (token hết hạn, app gỡ cài đặt...), hệ thống tự động gửi email dự phòng tới địa chỉ email của user (nếu có). FE không cần xử lý gì thêm cho trường hợp này — đây là hành vi backend tự động, chỉ cần biết rằng "gửi push" không có nghĩa là 100% chỉ qua kênh push.

### 1.3 `metadata` — payload có cấu trúc để deep-link

Một số loại thông báo (ví dụ `RANDOM_CHECK_SENT`) kèm `metadata` (object JSON tự do, ví dụ `{"checkId": "...", "siteId": "...", "expiresAt": "..."}`) — dùng để App/Web điều hướng thẳng tới đúng màn hình xử lý thay vì mở danh sách chung rồi để user tự tìm. `metadata` cũng được đưa vào data payload của gói push FCM (đọc được cả khi app đang tắt hoàn toàn, không chỉ khi mở app và đồng bộ `GET /notifications`).

---

## 2. `POST /api/v1/me/devices` — Đăng ký thiết bị nhận push

**User story**: *Nhân viên dùng app muốn đăng ký FCM token cho thiết bị để nhận thông báo realtime.*

Không cần quyền đặc biệt, tự scope theo JWT. **Lưu ý path**: `/api/v1/me/devices`, KHÔNG nằm dưới `/tenants/{tenantId}/...` — vì 1 FCM token là của thiết bị/user, dùng chung cho mọi tenant mà user đó là thành viên (không phải khái niệm theo tenant).

```json
{ "deviceToken": "<FCM token từ Firebase SDK>", "platform": "FCM" }
```
- Gọi lại API này mỗi khi app khởi động hoặc token được Firebase SDK refresh — nếu token đã tồn tại (kể cả của user khác trước đó dùng chung máy), sẽ tự động **chuyển quyền sở hữu** sang user hiện tại.
- `platform`: `FCM` (Android) hoặc `APNS` (iOS) — mặc định `FCM` nếu bỏ trống.

**Hủy đăng ký**: `DELETE /api/v1/me/devices/{deviceToken}` — gọi khi logout để dừng nhận push trên thiết bị đó. Trả `204` kể cả khi token không tồn tại (idempotent).

**UI đề xuất**: gọi `POST /devices` ngay sau khi đăng nhập thành công (App), lấy token mới nhất từ Firebase SDK mỗi lần app foreground. Gọi `DELETE` trong luồng logout.

---

## 3. Gửi push notification (nội bộ hệ thống)

**User story**: *Hệ thống muốn gửi push đến thiết bị người dùng để thông báo kịp thời.*

Đây **không phải** API do FE gọi trực tiếp — push được kích hoạt tự động khi backend tạo notification (mục 4) và user đã bật `pushEnabled` cho loại sự kiện đó. FE chỉ cần:
1. Đăng ký thiết bị đúng (mục 2).
2. Xử lý đúng data payload khi nhận được push (đọc `remoteMessage.data` phía app, có `eventType` + toàn bộ `metadata` — xem mục 1.3).
3. Không cần tự gọi API "gửi push" nào — không tồn tại endpoint như vậy cho client.

**Đã vá 2026-08-05**: endpoint tạo notification nội bộ (`POST /internal/notifications`, dùng bởi các service/job khác, không phải FE) trước đây **không có xác thực gì cả** — bất kỳ ai biết URL đều tạo được thông báo giả cho bất kỳ user nào. Đã yêu cầu header `X-Internal-Secret` (không liên quan tới FE, chỉ ảnh hưởng caller nội bộ).

---

## 4. `GET /api/v1/tenants/{tenantId}/notifications` — Danh sách thông báo

**User story**: *Người dùng muốn xem các thông báo trong hộp thư để không bỏ lỡ thông tin quan trọng.*

Query: `page` (mặc định 0), `size` (mặc định 20, tối đa 100), `unreadOnly` (mặc định `false`).

```json
{
  "items": [
    {
      "id": "uuid", "tenantId": "uuid", "userId": "uuid",
      "eventType": "RANDOM_CHECK_SENT", "title": "Kiểm tra ngẫu nhiên", "body": "...",
      "metadata": { "checkId": "uuid", "siteId": "uuid", "expiresAt": "..." },
      "isRead": false, "readAt": null, "createdAt": "2026-08-05T08:00:00Z"
    }
  ],
  "page": 0, "size": 20, "totalElements": 42, "totalPages": 3,
  "first": true, "last": false,
  "unreadCount": 5
}
```

- `unreadCount`: tổng số chưa đọc của user trong tenant, **không bị ảnh hưởng bởi filter `unreadOnly`/phân trang** — dùng trực tiếp để hiện badge số trên icon chuông, không cần tính lại từ danh sách trên trang hiện tại.
- Sort mặc định: mới nhất trước.

**UI đề xuất**: icon chuông ở header hiện `unreadCount`, click mở dropdown/màn hộp thư gọi API này với `unreadOnly=true` mặc định, có tab/toggle chuyển sang xem tất cả. Mỗi item có `metadata.checkId`/tương tự thì bấm vào điều hướng thẳng theo mục 1.3, không thì mở màn chi tiết chung chung theo `eventType`.

---

## 5. Đánh dấu đã đọc

**User story**: *Người dùng muốn đánh dấu một hoặc nhiều thông báo đã đọc để quản lý inbox gọn gàng.*

3 endpoint theo 3 mức phạm vi:

| Endpoint | Phạm vi | Dùng khi |
|---|---|---|
| `PATCH /notifications/{notificationId}/read` | Đúng 1 thông báo | User bấm vào 1 item để mở/xem chi tiết |
| `PATCH /notifications/read` (**mới, 2026-08-05**) | Nhiều thông báo cụ thể, chọn theo ID | User multi-select trong hộp thư rồi bấm "Đánh dấu đã đọc" cho các mục đã chọn |
| `PATCH /notifications/read-all` | Toàn bộ chưa đọc | Nút "Đánh dấu tất cả đã đọc" |

**Mới (2026-08-05)** — trước đây chỉ có 2 lựa chọn đầu/cuối (1 hoặc tất cả), không có cách nào đánh dấu MỘT NHÓM đã chọn — đây là gap thật so với UX quen thuộc kiểu Gmail/Slack (chọn vài mục rồi mark-read hàng loạt). Endpoint mới:

```json
// PATCH /api/v1/tenants/{tenantId}/notifications/read
{ "notificationIds": ["uuid1", "uuid2", "uuid3"] }
```
```json
// response — dùng chung DTO với read-all
{ "markedCount": 3 }
```

ID không tồn tại, không thuộc về user gọi, hoặc đã đọc rồi sẽ bị bỏ qua âm thầm (không lỗi) — `markedCount` chỉ đếm số thực sự được cập nhật. `400` nếu `notificationIds` rỗng.

**UI đề xuất**: hộp thư có checkbox chọn từng item + "chọn tất cả trên trang này", thanh hành động nổi lên khi có ≥1 mục được chọn với nút "Đánh dấu đã đọc" gọi endpoint mới này.

---

## 6. Cài đặt thông báo cá nhân (`/api/v1/me/notification-settings`)

Không thuộc 5 story chính nhưng là điều kiện để 2 toggle ở mục 1.1 hoạt động.

### 6.1 [MỚI, 2026-08-05] `GET /api/v1/notification-event-types` — Catalog chính thức

Không theo tenant (danh mục dùng chung toàn hệ thống). Cần đăng nhập, không yêu cầu quyền đặc biệt.

```json
{
  "data": [
    {
      "eventType": "RANDOM_CHECK_SENT",
      "label": "Kiểm tra ngẫu nhiên",
      "description": "Gửi khi hệ thống yêu cầu bạn phản hồi một lượt kiểm tra ngẫu nhiên (vị trí/khuôn mặt).",
      "defaultInAppEnabled": true,
      "defaultPushEnabled": true
    }
  ]
}
```

Đây là **nguồn duy nhất đáng tin cậy** cho danh sách `eventType` — dùng để dựng màn Cài đặt thông báo (mỗi phần tử → 1 dòng với 2 toggle) mà không cần hardcode chuỗi hay đoán theo dữ liệu đã thấy trong response khác. Hiện tại chỉ có đúng 1 phần tử (`RANDOM_CHECK_SENT`) — **đúng thực tế hệ thống**, không phải catalog thiếu sót; danh sách sẽ tự động dài thêm khi các module khác (chấm công, vi phạm, ca làm...) bắt đầu gửi notification, không cần FE cập nhật gì thêm ngoài gọi lại API này.

### 6.2 [CẬP NHẬT, 2026-08-05] `GET /me/notification-settings` — Luôn trả đủ catalog

**Trước đây**: chỉ trả những dòng user đã từng lưu — user chưa vào Cài đặt bao giờ nhận mảng rỗng, không có cách nào biết có những loại thông báo nào để bật/tắt.

**Đã sửa**: giờ luôn trả **ít nhất** toàn bộ catalog (mục 6.1), cộng thêm bất kỳ `eventType` tuỳ chỉnh nào riêng của tenant mà user đã từng lưu (xem mục 6.3) — không bao giờ trả mảng rỗng.

```json
{
  "data": [
    {
      "id": null,
      "userId": "uuid",
      "eventType": "RANDOM_CHECK_SENT",
      "label": "Kiểm tra ngẫu nhiên",
      "inAppEnabled": true,
      "pushEnabled": true,
      "customized": false,
      "updatedAt": null
    }
  ]
}
```

- `customized: false` — user chưa từng lưu tuỳ chỉnh cho loại này, `inAppEnabled`/`pushEnabled` đang hiện giá trị mặc định hệ thống (không phải lựa chọn thật của user) — `id`/`updatedAt` đều `null` vì chưa có gì được ghi vào DB. FE nên hiện toggle ở đúng trạng thái này nhưng KHÔNG cần đánh dấu gì đặc biệt — chỉ cần biết đây là default để không hiểu nhầm là "user đã tắt".
- `customized: true` — user đã từng bấm lưu, `inAppEnabled`/`pushEnabled` là lựa chọn thật, có `id`/`updatedAt` thật.
- `label`: chỉ có khi `eventType` nằm trong catalog chính thức — `null` nếu là `eventType` tuỳ chỉnh riêng của tenant (mục 6.3), FE fallback hiện thẳng mã `eventType`.

`PUT /me/notification-settings/{eventType}` — sửa 1 loại sự kiện: `{"inAppEnabled": true, "pushEnabled": false}`.
`PUT /me/notification-settings` (bulk) — sửa nhiều loại cùng lúc: `[{"eventType": "...", "inAppEnabled": true, "pushEnabled": true}, ...]`.

### 6.3 Event type tuỳ chỉnh riêng tenant (nâng cao, không bắt buộc FE xử lý ngay)

`NotificationTemplate.eventType` là chuỗi tự do theo từng tenant (`POST .../tenants/{tenantId}/notification-templates`) — về lý thuyết 1 tenant có thể tự định nghĩa loại sự kiện riêng ngoài catalog hệ thống. `GET /me/notification-settings` vẫn hiện đúng nếu user đã lưu tuỳ chỉnh cho loại này (không bị catalog mới che mất), chỉ là không có `label` sẵn. Trường hợp này hiếm và không phải trọng tâm của bản vá — nêu ra để FE không bất ngờ nếu gặp 1 dòng `label: null` trong response.

---

## 7. Quản lý template thông báo (Admin — `/api/v1/tenants/{tenantId}/notification-templates`)

Màn hình Admin cấu hình title/body theo `eventType` + ngôn ngữ. Từ 2026-08-06, lưu template ở đây **có tác dụng thật ngay lần gửi tiếp theo** (xem mục 0.1) — không cần bước "kích hoạt" nào khác.

**Quyền**: `PLATFORM_ADMIN` hoặc quyền `notifications:manage`/`tenant:admin` trong tenant. HR_MANAGER thường KHÔNG có quyền này mặc định — nếu Admin/Owner của tenant thấy 403, kiểm tra lại role được gán quyền `notifications:manage`.

### 7.1 `POST /api/v1/tenants/{tenantId}/notification-templates` — Tạo template

```json
{
  "eventType": "RANDOM_CHECK_SENT",
  "locale": "vi",
  "titleTemplate": "Kiểm tra vị trí — {siteName}",
  "bodyTemplate": "Vui lòng phản hồi trước {expiresAt}."
}
```
- `eventType`, `titleTemplate`, `bodyTemplate` bắt buộc. `locale` mặc định `"vi"` nếu bỏ trống.
- Cặp `eventType + locale` phải **duy nhất trong tenant** — trùng trả `409`.
- `{tenBien}` trong template sẽ được thay bằng đúng field cùng tên trong `metadata` của thông báo lúc gửi thật (ví dụ với `RANDOM_CHECK_SENT`: `checkId`, `siteId`, `expiresAt`) — field nào không có trong `metadata` sẽ giữ nguyên `{tenBien}` chưa thay, không lỗi. **FE nên hiện gợi ý rõ những biến khả dụng cho từng `eventType`** (hiện tại backend không tự liệt kê — chỉ hỗ trợ 1 `eventType` thật là `RANDOM_CHECK_SENT` với 3 biến trên, xem mục 6.1 catalog).
- Trả `201` + object template (có `id`, `createdAt`, `updatedAt`).

### 7.2 `GET /api/v1/tenants/{tenantId}/notification-templates` — Danh sách (phân trang)

Trả `Page<NotificationTemplateResponse>` chuẩn Spring (`content`, `page`, `size`, `totalElements`...).

### 7.3 `GET /{templateId}` — Chi tiết 1 template

### 7.4 `PUT /{templateId}` — Cập nhật

Tất cả field đều optional — chỉ field nào gửi lên mới bị đổi. Đổi `eventType`/`locale` mà trùng với template khác trong cùng tenant → `409`.

```json
{ "titleTemplate": "Tiêu đề mới", "bodyTemplate": "Nội dung mới" }
```

### 7.5 `DELETE /{templateId}` — Xoá mềm

Sau khi xoá, thông báo cho `eventType`/`locale` đó tự động quay về dùng text mặc định của hệ thống — không lỗi, không gián đoạn.

### 7.6 Mã lỗi riêng của module template

| HTTP | Khi nào |
|---|---|
| 400 | Thiếu `eventType`/`titleTemplate`/`bodyTemplate` khi tạo |
| 403 | Thiếu quyền `notifications:manage`/`tenant:admin` |
| 404 | `templateId` không tồn tại hoặc không thuộc tenant trong path |
| 409 | Trùng `eventType + locale` trong cùng tenant |

---

## 8. Delivery log — theo dõi gửi thất bại (Platform Admin — `GET /api/v1/platform/notifications/delivery-logs`)

Mới thêm 2026-08-06. Dùng cho màn/dashboard vận hành nội bộ (Platform Admin), **không phải màn hình cho tenant/HR** — endpoint không nhận `tenantId`, xem được delivery log của mọi tenant.

**Quyền**: `PLATFORM_ADMIN` (hoặc `system:read`).

**Query params** (đều optional): `status` (`SUCCESS`/`FAILED`/`FALLBACK_EMAIL_SENT`/`FALLBACK_EMAIL_FAILED`), `channel` (`FCM`/`EMAIL_FALLBACK`), `from`/`to` (ISO-8601), `page`, `size` (mặc định 20, tối đa 200).

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": "…",
        "notificationId": "…",
        "deviceToken": "…n-0001",
        "channel": "FCM",
        "attemptNumber": 2,
        "status": "FAILED",
        "errorMessage": "Device token unregistered",
        "createdAt": "2026-08-06T04:13:56Z"
      }
    ],
    "page": 0, "size": 20, "totalElements": 79, "totalPages": 4
  }
}
```

- `deviceToken` bị **che bớt**, chỉ hiện 6 ký tự cuối — không dùng để định danh thiết bị chính xác, chỉ để đối chiếu nhanh.
- `notificationId` có thể `null` (trường hợp push-only, user đã tắt in-app cho `eventType` đó).
- **UI đề xuất**: bảng lọc theo `status=FAILED` để tra nhanh "tại sao user X không nhận được thông báo" khi có khiếu nại — không cần dựng ngay nếu chưa có nhu cầu vận hành cấp thiết, đây là công cụ hỗ trợ ops chứ không phải tính năng end-user.

---

## 9. Mã lỗi cần xử lý (module gửi/nhận thông báo)

| HTTP | Khi nào | FE nên làm gì |
|---|---|---|
| 400 | `deviceToken` trống; `notificationIds` rỗng khi mark-read hàng loạt | Validate client trước khi submit |
| 401 | Chưa đăng nhập / token hết hạn | Redirect đăng nhập lại |
| 404 | `PATCH /{notificationId}/read` — không tồn tại hoặc không thuộc về user gọi (cố ý trả `404` thay vì `403` để tránh lộ thông tin notification của người khác tồn tại hay không) | Hiện lỗi chung, không phân biệt "không có quyền" với "không tồn tại" |

---

## 10. Checklist bàn giao frontend

- [ ] **App — bắt buộc**: gọi `POST /me/devices` sau đăng nhập + mỗi lần token FCM refresh; `DELETE` khi logout.
- [ ] **App — bắt buộc**: xử lý push nhận được kể cả khi app tắt hoàn toàn — đọc `remoteMessage.data` (không chỉ chờ mở app rồi gọi `GET /notifications`).
- [ ] **Web/App — bắt buộc, chưa có màn hình**: hộp thư thông báo dùng `GET /notifications`, badge `unreadCount` trên icon chuông.
- [ ] **Web/App — bắt buộc**: hỗ trợ multi-select + mark-read hàng loạt qua `PATCH /notifications/read` (endpoint mới) thay vì chỉ có "1 cái" hoặc "tất cả".
- [ ] **Web/App**: màn Cài đặt thông báo — 2 toggle độc lập (in-app/push) cho mỗi loại sự kiện, dùng `GET`/`PUT /me/notification-settings`.
- [ ] **Web/App — mới (bản vá P1)**: dựng màn Cài đặt thông báo dựa trên `GET /me/notification-settings` (giờ luôn đủ catalog) thay vì tự hardcode danh sách `eventType` hay chỉ hiện các loại đã thấy xuất hiện trong response — không cần gọi thêm `GET /notification-event-types` riêng trừ khi cần catalog ở màn khác (ví dụ Web HR quản lý template).
- [ ] **Web/App**: item thông báo có `metadata` thì điều hướng thẳng theo dữ liệu đó, không mở màn danh sách chung.
- [ ] **Web Admin — mới (2026-08-06)**: dựng màn "Quản lý template thông báo" (mục 7) — CRUD `eventType` + `locale` + title/body, hiện gợi ý biến `{tenBien}` khả dụng theo `eventType`. Xác nhận với backend role nào trong tenant được gán quyền `notifications:manage` trước khi ẩn/hiện màn này theo role.
- [ ] **Web Ops/Platform Admin — mới (2026-08-06), không khẩn cấp**: nếu có dashboard vận hành nội bộ, cân nhắc thêm bảng xem delivery log (mục 8) để tra cứu khi có khiếu nại "không nhận được thông báo" — không phải màn hình end-user.
