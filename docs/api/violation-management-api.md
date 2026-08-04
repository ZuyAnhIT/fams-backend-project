# API Reference: Violation Management (Vi phạm)

> Cập nhật theo code đang chạy ngày 2026-08-04, sau audit nghiệp vụ toàn diện (`docs/reviews/backend/random-check-violation-audit-2026-08-03.md`) và bản vá bổ sung 3 mục tồn đọng cùng ngày. Base path: `/api/v1/tenants/{tenantId}/violations`. Tài liệu này là **nguồn tham khảo chính thức cho FE (Web + App)** khi dựng màn hình liên quan tới vi phạm — đã kiểm tra và sửa đúng nghiệp vụ, khác với `random-check-violation-hr-workflow-review.md` (đó là báo cáo audit, ghi lại quá trình phát hiện/sửa lỗi, không phải spec để dùng trực tiếp).

Bao gồm 2 trong 8 user story hệ thống:
- *Là một hệ thống, tôi muốn tạo vi phạm `no_response` khi check hết hạn để HR có dữ liệu xử lý.*
- *Là một hệ thống, tôi muốn tạo violation theo lỗi location/face/liveness để ghi nhận bất thường kịp thời.*

Và các thao tác HR/nhân viên xử lý vi phạm sau khi được tạo (list, detail, confirm, dismiss, explain, attendance-impact).

---

## 1. Khái niệm nền tảng

### 1.1 Violation được tạo tự động — không có API "tạo violation" cho FE gọi

Đây là điểm khác biệt quan trọng nhất so với các module khác: **không có `POST /violations`**. Violation luôn được hệ thống tự tạo, theo 2 nguồn:

| Nguồn | Khi nào | `violationType` có thể có | Nơi tạo trong code |
|---|---|---|---|
| Random check hết hạn không phản hồi | Job nền quét mỗi 2 phút (`NoResponseViolationService`), hoặc gọi tay `POST /scheduled-checks/process-expired` | `no_response` | `NoResponseViolationService` |
| Random check có phản hồi nhưng fail | Nhân viên gửi `POST /scheduled-checks/{id}/respond`, hoặc callback AI xử lý ảnh xong | `location_fail`, `face_fail`, `liveness_fail` | `CheckResponseService.createViolation()` |
| Check-in/Check-out thường (không phải random check) fail xác thực khuôn mặt tại site yêu cầu Face ID | Callback AI xử lý ảnh check-in xong, trả `faceVerified=false` | `face_fail`, `liveness_fail` | `FaceResultCallbackController` |

**FE không cần và không nên có màn "Tạo vi phạm thủ công"** — đây không phải nhu cầu nghiệp vụ (nếu HR nghi ngờ 1 nhân viên, công cụ đúng là "Kích hoạt kiểm tra thủ công" ở `random-check-ui-guide.md` mục 3.9, để hệ thống tự đánh giá và tự tạo violation nếu thực sự fail — không phải HR tự gõ 1 vi phạm).

### 1.2 Idempotency — không lo trùng lặp

**Đã vá 2026-08-03**: trước đây `createViolation()` có thể bị gọi 2 lần cho cùng 1 `scheduledCheckId` (luồng GPS đồng bộ + callback AI bất đồng bộ), tạo 2 violation trùng. Đã thêm guard `existsByScheduledCheckIdAndViolationType` — mỗi `(scheduledCheckId, violationType)` chỉ có tối đa 1 violation. FE không cần tự dedupe khi hiển thị danh sách.

### 1.3 Vòng đời `resolution` (`resolved` boolean)

```
(mới tạo) resolved=false, resolution=null
        → HR confirm  → resolved=true, resolution="confirmed"
        → HR dismiss  → resolved=true, resolution="dismissed"
```

- Chỉ chuyển được **1 lần** — confirm/dismiss một violation đã `resolved=true` trả `409 Conflict`. FE nên disable cả 2 nút "Xác nhận"/"Bỏ qua" ngay khi `resolved=true`.
- Không có đường "un-resolve" — nếu HR bấm nhầm, không có API sửa lại. **Gợi ý FE**: hiện dialog xác nhận trước khi confirm/dismiss (đặc biệt dismiss, vì bắt buộc nhập lý do — dễ gõ nhầm rồi submit vội).

### 1.4 `affectsAttendance` — cờ báo cáo, KHÔNG tự động trừ công

Field riêng biệt với `resolution`, có thể set độc lập bất kỳ lúc nào (trước hoặc sau khi resolve). Đây là quyết định nghiệp vụ có chủ đích (xem `docs/reviews/backend/random-check-violation-audit-2026-08-03.md` mục Story 7): hệ thống **không tự động** dùng field này để trừ lương/công — nó chỉ là cờ HR tự đặt để đánh dấu "vi phạm này có ảnh hưởng tới cách tôi tính công ngày đó" cho mục đích báo cáo/tham khảo nội bộ. Nếu muốn thực sự điều chỉnh số giờ công, HR dùng `POST /attendance/{summaryId}/adjust` (xem `attendance-management-api.md`) — 2 thao tác độc lập nhau, **không đồng bộ tự động**.

**FE không nên ngụ ý** (qua UI copy) rằng bật cờ này sẽ tự trừ lương — nên dùng chữ kiểu "Đánh dấu ảnh hưởng chấm công (chỉ mang tính ghi chú, không tự trừ giờ công)".

### 1.5 Đồng bộ với `AttendanceSummary.hasRandomCheckFailure` — tự động, real-time

**Vá 2026-08-03**: khi HR `confirm`/`dismiss` một violation, hệ thống **tự động tính lại** `hasRandomCheckFailure` của đúng ngày/nhân viên/site đó (`AttendanceSummaryService.recomputeIfSummaryExists()`), nếu bản ghi `AttendanceSummary` đã tồn tại. Quy tắc:

- Một ngày chỉ hết bị đánh dấu `hasRandomCheckFailure=true` khi **TOÀN BỘ** violation của ngày đó (do random check gây ra) đã bị `dismissed` — nếu vẫn còn ≥1 violation `confirmed` hoặc chưa xử lý, cờ vẫn giữ `true`.
- FE bảng công (`attendance-ui-permissions-guide.md` mục 3.11) sẽ **tự động** thấy badge cảnh báo biến mất ngay sau khi HR dismiss violation cuối cùng của ngày đó — không cần FE tự gọi thêm API refresh nào ngoài việc load lại trang bảng công.

---

## 2. Ma trận quyền

| Hành động | TENANT_ADMIN / HR_MANAGER | SITE_SUPERVISOR | Nhân viên thường |
|---|---|---|---|
| Xem danh sách vi phạm toàn tenant | ✅ (`violations:list`) | ✅ (site trong phạm vi) | ❌ |
| Xem chi tiết 1 vi phạm | ✅ (`violations:read`) | ✅ (site trong phạm vi) | ❌ |
| Confirm / Dismiss / Sửa `affectsAttendance` | ✅ (`violations:update`) | tuỳ cấu hình role | ❌ |
| Xem vi phạm của chính mình | — | — | ✅ (tự động theo JWT) |
| Gửi giải trình cho vi phạm của chính mình | — | — | ✅ (tự động theo JWT) |

**Đã vá 2026-08-03**: trước đây `HR_MANAGER` (role hệ thống) **thiếu hoàn toàn** 7 quyền `checkins:*`/`randomchecks:*` do lỗi sót trong migration gốc — mọi request của HR Manager vào các module này (bao gồm cả các quyền `violations:*` phụ thuộc gián tiếp qua luồng nghiệp vụ) trả `403`. Đã cấp đủ qua migration `V83`. Nếu FE còn thấy `403` bất thường cho tài khoản HR_MANAGER khi test, xác nhận lại tenant đó đã chạy migration V83 chưa (không phải lỗi FE).

---

## 3. Endpoints — phía HR/Admin

### 3.1 Danh sách vi phạm

`GET /violations` — quyền `violations:list`.

Query params: `employeeId`, `siteId`, `violationType` (`no_response`|`location_fail`|`face_fail`|`liveness_fail`), `resolved` (`true`/`false`), `from`/`to` (ISO date, theo `checkDate`), `scheduledCheckId`, `sortBy` (mặc định `checkDate`), `sortDir` (`asc`/`desc`, mặc định `desc`), `page`, `size` (mặc định 20, tối đa 100 — server tự cắt nếu gửi lớn hơn).

**Mới (2026-08-03) — `scheduledCheckId`**: lọc chính xác "violation nào sinh ra từ đúng lượt kiểm tra này" — dùng khi HR đang xem chi tiết 1 `ScheduledCheck` (mục 3.8 của `random-check-ui-guide.md`) và cần tra cứu nhanh violation liên quan, thay vì phải lọc theo `employeeId` + khoảng ngày rồi tự đoán dòng nào đúng.

```json
// ViolationListResponse — mỗi item trong mảng data.content
{
  "id": "uuid",
  "employeeId": "uuid",
  "siteId": "uuid",
  "violationType": "no_response",
  "checkDate": "2026-07-04",
  "description": "...",
  "resolved": false,
  "resolvedAt": null,
  "employeeNote": null,
  "employeePhotoUrl": null,
  "createdAt": "2026-07-04T08:05:00Z"
}
```

**UI đề xuất**: bảng "Danh sách vi phạm" với cột Nhân viên, Site, Loại vi phạm (badge màu: đỏ=`no_response`, cam=`location_fail`, tím=`face_fail`/`liveness_fail`), Ngày, Trạng thái (badge "Chưa xử lý"/"Đã xác nhận"/"Đã bỏ qua"), có giải trình từ nhân viên hay chưa (icon nếu `employeeNote != null`). Bộ lọc theo site/nhân viên/loại/trạng thái/khoảng ngày. Click 1 dòng → mở chi tiết (mục 3.3).

### 3.2 Liên kết 2 chiều với Scheduled Check

Khi đang xem chi tiết 1 `ScheduledCheck` (`GET /scheduled-checks/{checkId}`, xem `random-check-ui-guide.md` mục 3.8), response giờ có sẵn field **`violations[]`** nhúng trực tiếp — **không cần gọi thêm API riêng**:

```json
{
  "...": "...các field khác của ScheduledCheckDetailResponse...",
  "violations": [
    {
      "id": "uuid",
      "violationType": "no_response",
      "resolved": false,
      "resolution": null,
      "description": "..."
    }
  ]
}
```

Chỉ dùng `GET /violations?scheduledCheckId=...` (mục 3.1) khi cần **đầy đủ** thông tin violation (employeeNote, employeePhotoUrl, createdAt...) mà bản tóm tắt nhúng sẵn không có — ví dụ khi HR bấm "Xem đầy đủ vi phạm này" từ trong modal chi tiết check.

### 3.3 Chi tiết 1 vi phạm

`GET /violations/{violationId}` — quyền `violations:read`. `404` nếu không tồn tại trong tenant.

```json
// ViolationDetailResponse
{
  "id": "uuid",
  "tenantId": "uuid",
  "employeeId": "uuid",
  "siteId": "uuid",
  "scheduledCheckId": "uuid | null",
  "checkResponseId": "uuid | null",
  "checkinId": "uuid | null",
  "violationType": "face_fail",
  "checkDate": "2026-07-04",
  "description": "...",
  "resolved": false,
  "resolvedAt": null,
  "resolvedBy": null,
  "employeeNote": null,
  "employeePhotoUrl": null,
  "createdAt": "2026-07-04T08:05:00Z",
  "scheduledCheck": {
    "id": "uuid", "scheduledAt": "...", "expiresAt": "...", "status": "responded", "checkIndex": 1
  },
  "checkResponse": {
    "id": "uuid", "respondedAt": "...", "latitude": 21.03, "longitude": 105.85,
    "accuracyMeters": 12.5, "faceImageUrl": "...", "livenessScore": 0.12,
    "locationVerified": true, "faceVerified": false, "livenessVerified": null,
    "outcome": "fail", "failureReason": "face_fail"
  }
}
```

**Phân biệt nguồn gốc violation qua 3 field loại trừ lẫn nhau**: đúng 1 trong 3 field `scheduledCheckId` / `checkinId` sẽ khác `null` (không bao giờ cả 2 cùng có giá trị) — cho biết violation này đến từ random check hay từ 1 lượt check-in thường bị fail xác thực khuôn mặt. `scheduledCheck` (object) chỉ có khi `scheduledCheckId != null`; `checkResponse` (object) chỉ có khi nhân viên **đã thực sự phản hồi** random check đó (violation `no_response` sẽ không có object này vì đúng nghĩa là "không phản hồi").

**UI đề xuất**: modal/trang chi tiết chia 2 khu vực — (1) thông tin vi phạm + trạng thái xử lý + nút Confirm/Dismiss, (2) bằng chứng — nếu có `checkResponse`, hiện bản đồ mini với toạ độ, ảnh khuôn mặt (nếu `faceImageUrl` có), điểm liveness; nếu có `scheduledCheck`, hiện thời gian được lên lịch/hết hạn để đối chiếu.

### 3.4 Confirm — xác nhận vi phạm đúng

`POST /violations/{violationId}/confirm` — quyền `violations:update`. `409` nếu đã resolved.

```json
// request (optional, có thể gửi body rỗng {})
{ "note": "Xác nhận sau khi xem log GPS — nhân viên rõ ràng ở ngoài geofence." }
```
```json
// response — ViolationActionResponse
{ "id": "uuid", "resolution": "confirmed", "resolutionReason": "...", "resolved": true, "resolvedAt": "...", "resolvedBy": "uuid" }
```

**UI đề xuất**: nút "Xác nhận vi phạm" (màu đỏ/cam) — mở dialog nhỏ có ô `note` tuỳ chọn (không bắt buộc, khác với dismiss), xác nhận xong disable cả 2 nút.

### 3.5 Dismiss — bỏ qua vi phạm (false positive)

`POST /violations/{violationId}/dismiss` — quyền `violations:update`. **`reason` bắt buộc** — `400` nếu để trống. `409` nếu đã resolved.

```json
// request — BẮT BUỘC
{ "reason": "Nhân viên đang làm việc tại địa điểm phụ đã được quản lý xác nhận trước." }
```
```json
// response — ViolationActionResponse
{ "id": "uuid", "resolution": "dismissed", "resolutionReason": "...", "resolved": true, "resolvedAt": "...", "resolvedBy": "uuid" }
```

**UI đề xuất**: nút "Bỏ qua" (màu xám/trung tính) — dialog **bắt buộc** ô lý do (validate client trước khi cho submit, server cũng chặn `400` nếu trống — lý do là để mọi quyết định dismiss đều có audit trail, tránh HR bỏ qua vi phạm tuỳ tiện không giải trình). Sau khi dismiss thành công, nếu đây là violation cuối cùng chưa xử lý của ngày đó, nhắc nhở nhẹ (không bắt buộc UI) rằng badge cảnh báo trên bảng công ngày đó sẽ tự biến mất (mục 1.5).

### 3.6 Cập nhật cờ `affectsAttendance`

`PATCH /violations/{violationId}/attendance-impact` — quyền `violations:update`. Có thể gọi bất kỳ lúc nào, kể cả trước/sau khi resolve.

```json
{ "affectsAttendance": true }
```
```json
// response — AttendanceImpactResponse
{ "id": "uuid", "affectsAttendance": true }
```

**UI đề xuất**: 1 toggle switch trong màn chi tiết vi phạm, có tooltip nhắc rõ đây chỉ là cờ ghi chú (mục 1.4) — **không** đặt cạnh nút Confirm/Dismiss để tránh HR hiểu nhầm đây là 1 phần của quy trình resolve.

---

## 4. Endpoints — phía nhân viên (self-service, App + Web)

### 4.1 Xem vi phạm của chính mình

`GET /violations/my` — không cần quyền đặc biệt, tự scope theo JWT (giống pattern `/scheduled-checks/my-pending` đã quen thuộc).

Query: `resolved` (optional — `false` để chỉ xem vi phạm đang chờ HR xử lý, bỏ trống để xem toàn bộ lịch sử), `page`, `size`.

`404` nếu user gọi API không có `Employee` record trong tenant đó (ví dụ tài khoản chỉ có role platform admin, không phải nhân viên thực).

### 4.2 [MỚI 2026-08-04] Inbox gộp — "cần tôi giải thích" (checkin + violation trong 1 danh sách)

`GET /me/exceptions` (path riêng, KHÔNG nằm dưới `/violations`, xem chi tiết dưới) — gộp **phần đọc** của `GET /violations/my?resolved=false` và `GET /checkin/history?status=pending_review` thành 1 danh sách duy nhất, sort theo `createdAt` mới nhất trước.

**Bối cảnh vì sao có endpoint này**: nhân viên có 2 loại "việc cần giải thích" hoàn toàn khác nhau về bản chất dữ liệu (1 lượt check-in bị đánh dấu chờ duyệt, và 1 violation từ random check) — trước đây phải xem ở 2 màn/API riêng. Endpoint này **không thay thế** 2 API gốc, chỉ gộp phần hiển thị để FE dựng được 1 màn "Hộp thư cần xử lý" duy nhất thay vì 2 tab riêng biệt.

```
GET /api/v1/tenants/{tenantId}/me/exceptions?size=50
```
Query: `size` (mặc định 50, tối đa 100 — áp dụng cho **tổng** số item trả về sau khi gộp, không phải per-source).

```json
{
  "success": true,
  "data": [
    {
      "id": "uuid",
      "sourceType": "violation",
      "reasonType": "no_response",
      "date": "2026-07-29",
      "description": "...",
      "explainEndpoint": "/api/v1/tenants/{tenantId}/violations/{id}/explain",
      "hasExplanation": true,
      "employeeNote": "Điện thoại mất mạng tại công trình",
      "createdAt": "2026-07-29T08:00:00Z"
    },
    {
      "id": "uuid",
      "sourceType": "checkin",
      "reasonType": "pending_review",
      "date": "2026-07-13",
      "description": "Check-out recorded, but needs HR review...",
      "explainEndpoint": "/api/v1/tenants/{tenantId}/checkin/{id}/explain",
      "hasExplanation": false,
      "employeeNote": null,
      "createdAt": "2026-07-13T06:12:00Z"
    }
  ]
}
```

**Cách FE dùng field `explainEndpoint`**: đây là URL đầy đủ, đúng theo `sourceType` của item đó — **không tự suy luận endpoint theo `sourceType` ở client**, dùng thẳng giá trị server trả về để `POST` giải trình. Cả hai nguồn hỗ trợ JSON (chỉ note) và multipart (note + photo), xem mục 4.3.

`hasExplanation=true` nghĩa là nhân viên đã gửi nhưng HR chưa xử lý; item vẫn nằm trong inbox. FE phải hiện trạng thái “Đã giải trình · chờ HR”, nạp `employeeNote` vào form và đổi CTA thành “Cập nhật giải trình”, thay vì làm người dùng tưởng lần gửi trước thất bại.

**UI đề xuất (Web + App)**: 1 màn "Hộp thư cần giải thích" (hoặc gộp vào Trang chủ nhân viên dạng banner số lượng), mỗi item hiện icon phân biệt theo `sourceType` (vd. 📍 cho checkin, ⚠️ cho violation), ngày, mô tả ngắn, trạng thái giải trình và form nhập `note` + upload ảnh tuỳ chọn, submit tới đúng `explainEndpoint`.

### 4.3 Gửi giải trình cho 1 vi phạm

`POST /violations/{violationId}/explain` — không cần quyền đặc biệt, chỉ cần violation đó thuộc đúng nhân viên gọi API (`403` nếu không phải, `404` nếu không tồn tại hoặc user không có Employee record).

Hai content type được giữ song song:

- `application/json`: `{ "note": "..." }` — giải trình không có ảnh, tương thích client cũ.
- `multipart/form-data`: field text `note` + file `photo` — JPEG/PNG/WEBP, tối đa 5MB. Đây là cách bắt buộc khi có ảnh; backend kiểm tra MIME + magic bytes, lưu private trong S3/MinIO rồi ghi explanation nguyên tử.

Không upload ảnh giải trình qua endpoint avatar và không gửi URL public từ client.
Field JSON `photoUrl` cũ đã deprecated và bị từ chối `400`; cập nhật note bằng JSON sẽ giữ nguyên evidence private đã upload trước đó.

```json
// response — ExplanationResponse
{ "id": "uuid", "employeeNote": "...", "employeePhotoUrl": "...", "updatedAt": "..." }
```

`employeePhotoUrl` của ảnh do FAMS quản lý là API URL tenant-scoped. HR tải ảnh với Bearer token qua `GET /violations/{violationId}/explanation-photo` (quyền `violations:read`, `Cache-Control: no-store`); object storage không public prefix evidence.

Có thể gọi lại nhiều lần để **ghi đè** giải trình cũ (không tích luỹ lịch sử nhiều lần giải trình) — nếu cần UI "sửa giải trình đã gửi", chỉ cần gọi lại đúng endpoint này với nội dung mới.

---

## 5. Mã lỗi cần xử lý

| HTTP | `errorCode`/tình huống | FE nên làm gì |
|---|---|---|
| 400 | `reason` trống khi dismiss; `note` trống khi explain; `affectsAttendance` thiếu | Validate client trước khi submit theo đúng field bắt buộc ở từng mục |
| 401 | Chưa đăng nhập / token hết hạn | Redirect đăng nhập lại |
| 403 | Thiếu quyền `violations:list`/`:read`/`:update` (HR), hoặc violation không thuộc về nhân viên gọi `explain` | Ẩn nút/menu nếu biết trước không có quyền; với explain, không nên xảy ra ở luồng UI bình thường (chỉ hiện nút giải thích cho vi phạm của chính mình) |
| 404 | Violation không tồn tại trong tenant, hoặc user không có Employee record (khi gọi `/violations/my`, `/me/exceptions`, `/explain`) | Hiện empty-state phù hợp, không phải lỗi đỏ chung chung |
| 409 | Confirm/Dismiss 1 violation đã `resolved=true` | Disable nút Confirm/Dismiss ngay khi `resolved=true` trên UI, tránh gửi request thừa |

---

## 6. Checklist bàn giao frontend

- [x] **Web**: màn "Danh sách vi phạm" (mục 3.1) có bộ lọc và permission `violations:list`.
- [x] **Web**: modal "Chi tiết vi phạm" có bằng chứng, Confirm/Dismiss và lý do audit bắt buộc.
- [x] **Web**: toggle `affectsAttendance` tách biệt khỏi Confirm/Dismiss và giải thích rõ không tự trừ công.
- [x] **Web**: chi tiết `ScheduledCheck` dùng `violations[]` nhúng sẵn và điều hướng theo `scheduledCheckId`.
- [x] **App + Web**: màn "Hộp thư cần giải thích" dùng `GET /me/exceptions`, hỗ trợ trạng thái đã gửi, sửa nội dung và evidence private.
- [ ] **App + Web**: màn "Vi phạm của tôi" riêng (nếu muốn tách biệt khỏi inbox gộp) dùng `GET /violations/my` (mục 4.1).
- [x] **App + Web**: ảnh giải trình dùng multipart `photo`; không dùng `photoUrl` hoặc nhầm với `employeePhotoBase64` của random check.
