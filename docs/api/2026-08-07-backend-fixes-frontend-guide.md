# Bàn giao Frontend: 6 fix backend ngày 07/08/2026

> Đây **không phải** tài liệu API đầy đủ — chi tiết request/response gốc đã có ở các file `docs/api/*-management-api.md` tương ứng. Tài liệu này chỉ liệt kê **đúng phần đã thay đổi** từ đợt xử lý tồn đọng ngày 07/08/2026 (theo `docs/api/backend-feature-audit-2026-08-07.md`), và **Web/App cần làm gì** với từng thay đổi. Tất cả thay đổi bên dưới đều **backward-compatible** — field cũ không đổi tên/kiểu, chỉ thêm field mới hoặc thêm dữ liệu vào API đã có sẵn. Không có breaking change, không bắt buộc phải deploy đồng thời.

## Tóm tắt: việc nào bắt buộc phải làm, việc nào tuỳ chọn

| # | Thay đổi | Bắt buộc làm ở FE? | Nền tảng |
|---|---|---|---|
| 1 | #60 — Giới hạn giờ OT (cảnh báo) | **Nên làm** — 2 việc: form cấu hình ca (Web) + hiển thị cảnh báo trên bảng công (Web+App) | Web (config) + Web/App (hiển thị) |
| 2 | #130 — Toạ độ trên dashboard giám sát | **Tuỳ chọn** — chỉ cần nếu muốn vẽ bản đồ | Web |
| 3 | #31 — Audit log tenant/RBAC/subscription | **Tuỳ chọn** — chỉ ảnh hưởng nếu có màn "Nhật ký hệ thống" | Web (Platform Admin) |
| 4 | #145 — Mở rộng masking | Không cần làm gì | — |
| 5 | #118 — Attendance-impact tự recompute | Không cần làm gì (hành vi ngầm) | — |
| 6 | #113 — Không phải gap thật | Không cần làm gì | — |

---

## 1. #60 — Giới hạn giờ OT tối đa/ngày, /tuần (cảnh báo, không chặn)

**Quyết định nghiệp vụ**: giống Deputy/ADP — đặt giới hạn giờ OT chỉ để **cảnh báo HR**, không chặn nhân viên checkout, không tự trừ lương OT phần vượt. Nhân viên vẫn checkout/tính OT bình thường; hệ thống chỉ gắn cờ để HR biết mà xử lý (nhắc nhở, duyệt bù trừ giờ, v.v. — tuỳ quy trình nội bộ công ty).

### 1.1 Web — Form cấu hình OT của ca (màn Quản lý ca làm việc)

`PUT /api/v1/tenants/{tenantId}/sites/{siteId}/shifts/{shiftId}/ot-config` — request thêm **4 field mới**, đều optional:

```json
{
  "allowOvertime": true,
  "earlyCheckinMinutes": 15,
  "lateCheckoutMinutes": 30,
  "maxOtMinutesPerDay": 120,        // MỚI — số phút OT tối đa/ngày, >= 0, để trống = không đổi
  "clearMaxOtMinutesPerDay": false, // MỚI — true để xoá giới hạn ngày (về "không giới hạn")
  "maxOtMinutesPerWeek": 600,       // MỚI — số phút OT tối đa/tuần (tính theo tuần ISO: Thứ 2 → Chủ Nhật)
  "clearMaxOtMinutesPerWeek": false // MỚI — true để xoá giới hạn tuần
}
```

Response `ShiftResponse` trả thêm:
```json
{
  "...": "...",
  "maxOtMinutesPerDay": 120,   // null = không giới hạn
  "maxOtMinutesPerWeek": 600   // null = không giới hạn
}
```

**Gợi ý UI**: 2 ô input số (phút) cạnh ô `lateCheckoutMinutes` hiện có, có thể để dạng "để trống = không giới hạn" thay vì bắt nhập 0. Nếu muốn hiển thị theo giờ cho dễ đọc, tự quy đổi `/60` ở FE — backend lưu/trả theo **phút** để đồng nhất với `earlyCheckinMinutes`/`lateCheckoutMinutes` đã có.

### 1.2 Web + App — Hiển thị cảnh báo trên bảng công

`AttendanceSummaryResponse` (dùng ở cả `GET /attendance`, `GET /attendance/me`, `GET /attendance/{id}`...) trả thêm **2 field mới**, cùng kiểu/cùng vị trí ý nghĩa với `hasRandomCheckFailure` đã có:

```json
{
  "...": "...",
  "otMinutes": 150,
  "otDailyLimitExceeded": true,   // MỚI — otMinutes hôm nay > maxOtMinutesPerDay của ca
  "otWeeklyLimitExceeded": false  // MỚI — tổng otMinutes tuần này (của nhân viên, gộp mọi site) > maxOtMinutesPerWeek
}
```

**Lưu ý quan trọng cho FE**: 2 field này **chỉ mang tính thông báo**, không ảnh hưởng đến `otMinutes`/lương — không ẩn/khoá bất kỳ nút nào khi `true`, chỉ nên hiện 1 icon/badge cảnh báo (ví dụ badge màu vàng "Vượt giờ OT ngày" cạnh cột OT trong bảng công HR, tương tự cách đang hiển thị `hasRandomCheckFailure`/`missingCheckout`). Nếu ca không đặt giới hạn (`maxOtMinutesPerDay`/`Week` = null), 2 field này luôn `false`.

---

## 2. #130 — Toạ độ trên Dashboard Giám sát (Web, màn Supervisor)

`GET /api/v1/tenants/{tenantId}/dashboard/supervisor` — trả thêm toạ độ, **không đổi field cũ**:

```json
{
  "supervisedSites": [
    {
      "siteId": "...", "siteName": "...", "expectedToday": 8, "onSiteNow": 5,
      "siteLatitude": 10.7769,    // MỚI — toạ độ tâm site, để vẽ tâm bản đồ
      "siteLongitude": 106.7009,  // MỚI
      "onSiteEmployees": [
        {
          "employeeId": "...", "firstName": "...", "lastName": "...",
          "employeeCode": "...", "checkinId": "...", "checkInAt": "...",
          "checkInLat": 10.7770,   // MỚI — toạ độ LÚC CHECK-IN, không phải vị trí realtime
          "checkInLon": 106.7010   // MỚI
        }
      ]
    }
  ]
}
```

**Quan trọng — không phải live tracking**: `checkInLat`/`checkInLon` là toạ độ **tại thời điểm nhân viên check-in**, không cập nhật liên tục sau đó (hệ thống không có background location tracking, đúng theo thiết kế bảo mật/pin hiện tại — không đổi). Nếu vẽ bản đồ, nên ghi chú rõ trên UI kiểu "Vị trí lúc check-in lúc {checkInAt}" thay vì ngụ ý đang theo dõi trực tiếp, tránh hiểu nhầm với nhân viên/khách hàng.

**Không bắt buộc làm ngay** — chỉ cần nếu có kế hoạch dựng màn bản đồ site cho Supervisor; nếu chưa, không cần đổi gì, field mới không ảnh hưởng phần đang dùng.

---

## 3. #31 — Audit log giờ có thêm cho Tenant/RBAC/Subscription (Web, Platform Admin)

`GET /api/v1/audit-logs` (không đổi contract, request/response params giữ nguyên) — **nếu màn "Nhật ký hệ thống" hiện tại có danh sách cứng các `entityType` để hiện icon/nhãn**, cần bổ sung các giá trị mới sau (trước đây các hành động này **không hề xuất hiện** trong audit log — nếu FE lọc theo whitelist entityType thì các dòng mới sẽ tự động biến mất khỏi danh sách mà không báo lỗi):

| `entityType` mới | Sinh ra khi nào | `action` có thể gặp |
|---|---|---|
| `Tenant` | Tạo/sửa/khoá/mở/hủy tenant | `tenant_created`, `tenant_updated`, `tenant_suspended`, `tenant_reactivated`, `tenant_cancelled` |
| `Role` | Tạo/sửa/xoá role tuỳ chỉnh | `role_created`, `role_updated`, `role_deleted` |
| `UserRole` | Gán/thu hồi role cho user | `role_assigned`, `platform_role_assigned`, `role_revoked` |
| `TenantSubscription` | Gán/sửa subscription của tenant | `subscription_assigned`, `subscription_updated` |
| `Plan` | Tạo/sửa gói dịch vụ | `plan_created`, `plan_updated` |
| `PlanLimits` | Sửa giới hạn gói | `plan_limits_updated` |

**Lưu ý**: `oldValue`/`newValue` với các `entityType` này là JSON object tự do (key/value theo từng loại — ví dụ `Tenant` có `name`/`slug`/`status`/..., `Role` có `name`/`permissions`/...) — nếu FE đang render diff theo kiểu key/value table chung chung (không hardcode field theo entityType cụ thể, đúng khuyến nghị ở `docs/api/audit-log-api.md`) thì **không cần sửa gì**, tự động hoạt động đúng với dữ liệu mới. Chỉ cần sửa nếu FE có danh sách cứng entityType để lọc/hiện icon.

`tenant_suspended` do cron tự động (hết hạn subscription) sẽ có `actorId: null` — nếu FE hiện tên người thực hiện, nên xử lý `actorId = null` thành nhãn "Hệ thống tự động" thay vì để trống/lỗi.

---

## 4. #145, #118, #113 — Không cần Frontend làm gì

- **#145 (masking)**: chỉ mở rộng danh sách field bị che ở tầng ghi audit (`totpSecret`/`backupCodes`/`nationalId`...) — đây là các field **hiện chưa từng được trả về trong bất kỳ response nào**, nên không có gì thay đổi ở phía FE đang thấy. Thuần phòng vệ nội bộ.
- **#118 (attendance-impact tự recompute)**: hành vi ngầm — gọi `PATCH .../violations/{id}/attendance-impact` như cũ, response contract không đổi; chỉ khác là giờ bảng công của ngày đó được cập nhật lại ngay (trước đây phải đợi 1 sự kiện khác mới refresh). Nếu FE có cache bảng công phía client, nên invalidate/refetch bảng công sau khi gọi endpoint này (nếu chưa làm) — tương tự cách đã làm với `confirm`/`dismiss` violation.
- **#113 (giải trình check-in)**: xác nhận lại đúng endpoint hiện có, không đổi gì: gửi giải trình qua `POST /checkin/{id}/explain` (không phải qua module "MyExceptions" — module đó chỉ đọc, dùng để gộp danh sách cần giải trình).

---

## 5. Checklist ngắn cho FE

- [ ] **Web**: thêm 4 field OT limit vào form cấu hình ca (`maxOtMinutesPerDay`/`Week` + 2 cờ clear)
- [ ] **Web + App**: hiện badge cảnh báo khi `otDailyLimitExceeded`/`otWeeklyLimitExceeded = true` trên bảng công
- [ ] **Web**: nếu có màn nhật ký audit — bỏ whitelist entityType cứng (nếu có) hoặc bổ sung 6 loại mới ở mục 3
- [ ] **Web**: (tuỳ chọn) dùng `siteLatitude`/`siteLongitude`/`checkInLat`/`checkInLon` nếu dựng màn bản đồ Supervisor — nhớ ghi rõ "vị trí lúc check-in", không phải theo dõi trực tiếp
- [ ] Không cần đổi gì cho #145/#118/#113
