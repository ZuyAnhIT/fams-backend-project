# API Reference: Platform System Status (Health Check)

Base path: `/api/v1/platform`
Quyền: `PLATFORM_ADMIN` hoặc `system:read`. Không tenant-scope — đây là dashboard vận hành toàn hệ thống.

## Bản vá 2026-08-06 — sửa 2 điểm P1 FE báo trước khi dựng UI

FE dựng màn System Health báo lại 2 vấn đề **trước khi bắt đầu code**, cả hai đã sửa:

1. **`healthComponents` trước đây trộn `status` dạng chuỗi lẫn với các component dạng object** trong CÙNG 1 map (`{"status":"UP","db":{...},"redis":{...}}`) — client không phân biệt được đâu là tên component, đâu là giá trị scalar. Đã bỏ hẳn key `status` cấp cao nhất khỏi `healthComponents` (dữ liệu này vốn dư thừa — trùng hệt `overallHealth` ở cấp response). Mọi entry còn lại giờ **đồng nhất 1 dạng** `{status, details}`.
2. **`jobs[]` trước đây chỉ trả job đã từng chạy ít nhất 1 lần** — job bị cấu hình sai/chưa từng fire kể từ lúc deploy hoàn toàn biến mất khỏi danh sách, không phân biệt được với "job này không tồn tại". Đã đổi sang **union với catalog đầy đủ mọi `@Scheduled` job trong code** (`ScheduledJobCatalog`) — job chưa từng chạy giờ xuất hiện với `lastStatus: "NEVER_RUN"` thay vì biến mất. Đồng thời bổ sung `expectedNextRunAt`, `staleThresholdMinutes`, `stale`, `lastRunDurationMs` — xem chi tiết mục "jobs[]" bên dưới.

---

## `GET /api/v1/platform/system-status`

```json
{
  "success": true,
  "data": {
    "overallHealth": "UP",
    "healthComponents": {
      "db": { "status": "UP", "details": { "database": "PostgreSQL" } },
      "redis": { "status": "UP", "details": { "version": "7.4.9" } },
      "fcm": { "status": "UP", "details": { "app_name": "[DEFAULT]" } },
      "aiService": { "status": "UP", "details": { "response": "{\"status\":\"ok\"}" } },
      "randomCheckJob": { "status": "UP", "details": { "RandomCheckDispatchJob_status": "OK" } },
      "randomCheckQueue": { "status": "UP", "details": { "dispatch_queue_size": 0, "lag_seconds": 0 } },
      "mail": { "status": "UP", "details": null },
      "diskSpace": { "status": "UP", "details": { "free": 1003492982784 } },
      "ping": { "status": "UP", "details": null }
    },
    "jobs": [
      {
        "jobName": "AttendanceSummaryJob",
        "description": "Nightly catch-up recompute of attendance summaries for every tenant's check-ins from the previous day.",
        "lastStatus": "NEVER_RUN",
        "lastRunAt": null,
        "lastRunDurationMs": null,
        "errorMessage": null,
        "expectedNextRunAt": "2026-08-07T01:00:00Z",
        "staleThresholdMinutes": 1560,
        "stale": false
      },
      {
        "jobName": "RandomCheckDispatchJob",
        "description": "Polls the Redis dispatch queue and sends due random-check notifications.",
        "lastStatus": "OK",
        "lastRunAt": "2026-08-06T15:33:50.862098Z",
        "lastRunDurationMs": 2,
        "errorMessage": null,
        "expectedNextRunAt": "2026-08-06T15:34:50.862098Z",
        "staleThresholdMinutes": 10,
        "stale": false
      }
    ],
    "activeTenantCount": 22,
    "faceVerifyQueueDepth": 0,
    "dispatchQueueDepth": 0,
    "generatedAt": "2026-08-06T14:41:37Z"
  }
}
```

### `healthComponents` — mỗi entry đồng nhất `{status, details}`

| Key | Ý nghĩa |
|---|---|
| `db` | Kết nối PostgreSQL (auto qua Spring Actuator + HikariCP) |
| `redis` | Kết nối Redis + độ trễ hàng đợi |
| `fcm` | Firebase/FCM credentials có khởi tạo thành công không — `DOWN` nếu `FCM_SERVICE_ACCOUNT_JSON` sai/thiếu |
| `aiService` | fams-ai (Face ID, liveness, embeddings) có phản hồi `GET /health` không |
| `randomCheckJob` | `RandomCheckDispatchJob`/`NoResponseViolationJob` có chạy đúng hạn không (stale >10 phút ⇒ `DOWN`) |
| `randomCheckQueue` | Độ trễ hàng đợi dispatch trong Redis (>15 phút ⇒ `DOWN`) |
| `mail`, `diskSpace`, `ping`, `ssl` | Actuator mặc định của Spring Boot |

`details` có thể là `null` khi component không có chi tiết bổ sung (ví dụ `ping`). `overallHealth` (cấp response, KHÔNG nằm trong `healthComponents`) là `DOWN` nếu bất kỳ component nào `DOWN` — dùng làm badge tổng quan, nhưng FE nên hiển thị chi tiết từng `healthComponents` để biết đúng cái gì đang lỗi.

### `jobs[]` — mọi job đã biết trong hệ thống, kể cả chưa từng chạy

Danh sách cố định 7 job hiện có (`AttendanceSummaryJob`, `RandomCheckSchedulerJob`, `RandomCheckDispatchJob`, `NoResponseViolationJob`, `RandomCheckQueueReconciliationJob`, `DataRetentionJob`, `SubscriptionExpirationJob`) — luôn đủ 7 phần tử bất kể job đã chạy hay chưa.

| Field | Ý nghĩa |
|---|---|
| `lastStatus` | `OK`, `ERROR`, hoặc **`NEVER_RUN`** (mới) — job có tồn tại trong code nhưng chưa từng ghi nhận lượt chạy nào (mới deploy, hoặc bị cấu hình sai khiến `@Scheduled` không kích hoạt) |
| `lastRunAt` | `null` nếu `NEVER_RUN` |
| `lastRunDurationMs` | **Mới** — thời gian chạy của lần gần nhất (ms). `null` nếu chưa từng chạy |
| `expectedNextRunAt` | **Mới** — thời điểm dự kiến chạy tiếp theo. Chính xác tuyệt đối với job chạy theo cron (tính từ chính biểu thức cron, không phụ thuộc lịch sử); với job chạy theo chu kỳ cố định (fixed-rate), là `lastRunAt + chu kỳ` — `null` nếu job đó chưa từng chạy (không có mốc để cộng) |
| `staleThresholdMinutes` | **Mới** — ngưỡng phút dùng để tính `stale`, khác nhau theo từng job (job chạy mỗi phút có ngưỡng vài phút; job chạy hàng đêm có ngưỡng >24h) |
| `stale` | **Mới** — `true` khi `lastStatus=OK` NHƯNG đã lâu không chạy lại (quá `staleThresholdMinutes`) — phân biệt "khỏe" với "đã âm thầm ngừng chạy dù lần cuối chạy thành công", khác với `ERROR` (job chạy nhưng báo lỗi) |

Một job `ERROR` **tự động gửi cảnh báo đẩy tới mọi Platform Admin** ngay lúc lỗi xảy ra — dashboard dùng để xem lại/xác nhận, không phải kênh cảnh báo chính. `NEVER_RUN`/`stale` hiện **không** kích hoạt cảnh báo đẩy tự động — chỉ hiển thị trên dashboard, cần Platform Admin chủ động xem.

### Các trường khác

- `activeTenantCount` — số tenant `status=active`, không tính tenant bị suspend/cancelled.
- `faceVerifyQueueDepth` / `dispatchQueueDepth` — độ sâu 2 hàng đợi Redis — tăng bất thường là dấu hiệu worker xử lý chậm hoặc bị nghẽn.

---

## FE integration notes

- **Không cần polling nhanh** — poll mỗi 30-60s là đủ cho 1 dashboard giám sát (job `ERROR` đã có cảnh báo đẩy riêng, không phụ thuộc FE polling để phát hiện).
- Đây là màn hình dành cho Platform Admin, **không phải** màn hình cho Company Admin/HR của từng tenant.
- **UI đề xuất cho `jobs[]`**: 4 trạng thái hiển thị khác nhau — `OK` (xanh), `ERROR` (đỏ), `NEVER_RUN` (xám/cảnh báo — "chưa từng chạy, kiểm tra cấu hình"), `OK` nhưng `stale:true` (vàng — "chạy được nhưng đã lâu không thấy, có thể bị treo"). Đừng gộp `NEVER_RUN` và `stale` thành cùng 1 kiểu hiển thị — ý nghĩa vận hành khác nhau (chưa từng cấu hình đúng vs. từng chạy rồi ngừng).
- Dùng `docs/deployment/go-live-checklist.md` mục 4 làm checklist tham chiếu: mọi `healthComponents` phải `UP` và mọi job **không phải `NEVER_RUN`** (trừ job hàng tuần/hàng đêm chưa tới giờ chạy lần đầu) trước khi bàn giao tenant mới cho khách hàng.
