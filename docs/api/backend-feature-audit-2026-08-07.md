# Backend Feature Audit — Đối chiếu checklist 150 tính năng, phần 2 (07/08/2026)

> Nối tiếp `docs/api/backend-feature-audit-2026-08-01.md` (đã audit mục #1–99, kết quả 97 Done/2 Partial/0 Missing). Tài liệu này audit nốt mục **#100–150** (chưa từng được audit tổng thể trước đây) + **re-verify 2 điểm Partial cũ** (#31, #60) sau các PR merge 2026-08-02 → 2026-08-07 (#57–#65). Phương pháp giống hệt: đối chiếu checklist với code thật (controller/service/entity), file:line cụ thể, 5 agent audit song song độc lập theo nhóm tính năng.

## Kết quả tổng quan (mục #100–150, 51 mục)

| Trạng thái | Số lượng |
|---|---|
| ✅ Done | 47 |
| ⚠️ Partial (lúc audit) → nay còn | 4 → 1 |
| ❌ Missing | 0 |

**Tổng hợp toàn bộ 150 mục (gộp với đợt audit 01/08)**: lúc audit 144 Done/6 Partial; **sau đợt xử lý tồn đọng 2026-08-07: 149 Done, 1 Partial** (chỉ còn #113, và #113 thực ra không phải gap — xem cuối tài liệu).

**4 điểm Partial phát hiện lúc audit (#100–150) — đã xử lý cùng ngày, xem mục "Việc nên làm tiếp theo" cuối tài liệu để biết chi tiết fix**:
- **#113 Nhân viên gửi giải trình check-in lỗi** — không nằm ở `MyExceptionsController` như tên module gợi ý (controller đó chỉ đọc), mà logic gửi giải trình thật nằm ở `CheckinController.java:302-330` (`POST /checkin/{checkinId}/explain`). Chức năng có đủ, chỉ lệch vị trí so với gợi ý — không phải gap nghiệp vụ, không sửa code.
- **#118 Cập nhật ảnh hưởng công** — có 2 cơ chế: `confirmViolation`/`dismissViolation` gọi recompute attendance thật; nhưng endpoint riêng `PATCH /violations/{id}/attendance-impact` chỉ set cờ `affectsAttendance`, **không tự trigger recompute**. → Đã fix.
- **#130 Bản đồ site và vị trí hiện tại** — có đủ dữ liệu rời rạc (toạ độ site, polygon geofence, toạ độ check-in) nhưng **không có 1 endpoint tổng hợp** trả về vị trí hiện tại của toàn bộ nhân viên đang on-site cho 1 site. → Đã fix.
- **#145 Mask dữ liệu nhạy cảm trong audit và API** — đã sửa xong khoảng hở email/phone (`EmployeeDetailResponse`, Excel export, log token mời — xem `docs/reviews/backend/data-masking-permission-health-uat-golive-audit-2026-08-06.md`), nhưng **`national_id` và `totp_secret`/`backupCodes` vẫn chưa nằm trong `PII_KEYS`/`@Masked`**. → Đã fix (phòng vệ ở tầng ghi).

**Re-verify 2 điểm Partial cũ (#1–99) — đã xử lý cùng ngày**:
- **#31 Ghi audit cho hành động quan trọng** — lúc audit vẫn Partial (đã thu hẹp): có audit cho TOTP/MFA, employee create/update, manual random check, nhưng **thiếu**: tenant service, RBAC service, subscription/plan service. → Đã fix.
- **#60 Cấu hình OT và giới hạn giờ** — lúc audit vẫn Partial, không đổi từ đợt trước: chỉ có `allowOvertime` (bật/tắt) + phút dung sai, không có giới hạn giờ OT tối đa/ngày/tuần. → Đã fix (warn-only, theo quyết định người dùng).

---

## 7. Random Check response / Violation / HR override (16/18 Done, 2 Partial)

| # | Tính năng | Trạng thái | Bằng chứng | Ghi chú |
|---|---|---|---|---|
| 100 | Gửi random check notification | ✅ Done | `RandomCheckDispatchService.sendNotification()` L69-102, gọi `notificationService.createNotification`; dispatch bởi `RandomCheckDispatchJob` (poll Redis queue 60s) | — |
| 101 | App hiển thị random check đang chờ | ✅ Done | `ScheduledCheckController.java:261-317` `GET .../scheduled-checks/my-pending` | — |
| 102 | Phản hồi mode chỉ vị trí | ✅ Done | `CheckResponseService.submitResponse` — mode `location` | — |
| 103 | Phản hồi mode vị trí + Face ID | ✅ Done | mode `location_face`, verify Face ID async qua Redis job | — |
| 104 | Phản hồi mode vị trí + Face ID + Liveness | ✅ Done | mode `location_face_liveness`; `applyFaceResult()` L213-246 kiểm liveness độc lập | — |
| 105 | Từ chối phản hồi trễ | ✅ Done | `CheckResponseService.java:93-97` throw `CheckExpiredException` nếu quá `expiresAt` → HTTP 410 | — |
| 106 | Tạo violation khi không phản hồi | ✅ Done | `NoResponseViolationService.processChecks()` L50-83, type `no_response`, idempotent | — |
| 107 | Tạo violation khi fail random check | ✅ Done | `face_fail`/`liveness_fail` từ cả submit đồng bộ (L176-180) và kết quả async (L237-242) | — |
| 108 | HR kích hoạt kiểm tra ngay | ✅ Done | `POST .../scheduled-checks/manual` → `ManualCheckService.trigger`, có đếm số lần/ngày | — |
| 109 | HR xem danh sách scheduled checks | ✅ Done | `GET /scheduled-checks` (list) + `/summary` (đếm theo trạng thái) | — |
| 110 | HR xem chi tiết random check | ✅ Done | `GET /scheduled-checks/{id}` + `GET /{id}/photo` (ảnh selfie nhân viên gửi) | — |
| 111 | HR override check-in | ✅ Done | `PATCH /checkin/{id}/override` → giải quyết `pending_review`, trigger recompute | — |
| 112 | HR chỉnh attendance summary | ✅ Done | `PATCH /attendance/{id}/adjust` + `POST /{id}/unlock-and-recompute` | — |
| 113 | Nhân viên gửi giải trình check-in lỗi | ⚠️ Partial | Logic thật ở `CheckinController.java:302-330` (`POST /checkin/{id}/explain`), không phải `MyExceptionsController` (chỉ đọc, gom `pending_review` + violation chưa xử lý thành 1 inbox) | Có đủ chức năng, chỉ lệch vị trí so với gợi ý tên module |
| 114 | HR xem danh sách vi phạm | ✅ Done | `GET /violations` | — |
| 115 | HR xem chi tiết violation | ✅ Done | `GET /violations/{id}` | — |
| 116 | Xác nhận vi phạm | ✅ Done | `POST /violations/{id}/confirm` → `resolution=confirmed` + recompute attendance | — |
| 117 | Bỏ qua vi phạm | ✅ Done | `POST /violations/{id}/dismiss` → `resolution=dismissed` + recompute attendance | — |
| 118 | Cập nhật ảnh hưởng công | ⚠️ Partial | `PATCH /violations/{id}/attendance-impact` chỉ set cờ `affectsAttendance`, không tự gọi recompute (khác với confirm/dismiss) | Cần bổ sung gọi recompute khi cờ đổi, hoặc làm rõ đây là cờ chờ xử lý sau |

**Extra/hidden phát hiện thêm ở nhóm này** (chưa khai trong checklist gốc): `GET /scheduled-checks/{id}/photo`, `POST /{id}/cancel`, `POST /{id}/dispatch` (dispatch thủ công), `GET /dispatch-queue` (debug hàng đợi Redis); `RandomCheckQueueReconciliationJob` (khôi phục check bị rớt khỏi Redis queue); `ScheduledCheckCancelService.cancelPendingByAssignment` (tự hủy check khi assignment bị hủy); `GET /scheduled-checks/{id}/my-result` (nhân viên tự xem kết quả); flow giải trình riêng cho **violation** (`POST /violations/{id}/explain` + `GET .../explanation-photo`), song song với flow giải trình checkin.

## 8. Dashboard / Report / Search / UX (13/14 Done, 1 Partial)

| # | Tính năng | Trạng thái | Bằng chứng | Ghi chú |
|---|---|---|---|---|
| 119 | Dashboard nhân viên | ✅ Done | `GET .../dashboard/employee` → `EmployeeDashboardService` | — |
| 120 | Dashboard HR | ✅ Done | `GET .../dashboard/hr` → `HrDashboardService` | — |
| 121 | Dashboard giám sát công trình | ✅ Done | `GET .../dashboard/supervisor` → `SupervisorDashboardService` (presence per-site thật) | — |
| 122 | Báo cáo công ngày | ✅ Done | `GET .../reports/attendance/daily` | — |
| 123 | Báo cáo công tháng | ✅ Done | `GET .../reports/attendance/monthly` | — |
| 124 | Export bảng công | ✅ Done | `GET .../reports/attendance/export` (.xlsx), guard 409 nếu dữ liệu chưa sẵn sàng | — |
| 125 | Báo cáo vi phạm theo kỳ | ✅ Done | `GET .../reports/violations` | — |
| 126 | Báo cáo hiện diện theo site | ✅ Done | `GET .../reports/sites/presence` | — |
| 127 | Báo cáo trạng thái Face ID | ✅ Done | `GET .../reports/face-id/enrollment` | — |
| 128 | Tìm kiếm nhanh nhân viên/site/check-in | ✅ Done | `GET .../search?q=` — cả 3 loại, site-scope cho Supervisor, hỗ trợ tra thẳng UUID check-in | — |
| 129 | Thông báo lỗi thân thiện | ✅ Done | `GlobalExceptionHandler` — mọi handler trả cả `message` (kỹ thuật) lẫn `userMessage` (tiếng Việt) | — |
| 130 | Bản đồ site và vị trí hiện tại | ⚠️ Partial | Có toạ độ site (`SiteController`), polygon geofence (`GeofenceController`), toạ độ từng check-in — nhưng không có 1 endpoint tổng hợp "vị trí hiện tại của mọi nhân viên đang on-site" | `SupervisorDashboardResponse.OnSiteEmployee` chưa có field lat/lng |
| 131 | Lưu bộ lọc thường dùng | ✅ Done | `SavedFilterController` CRUD đầy đủ, 409 nếu trùng tên | — |
| 132 | Export danh sách vi phạm | ✅ Done | `GET .../reports/violations/export` | — |

## 9. Platform / Tenant Ops / Audit detail / Notification templates (9/9 Done)

| # | Tính năng | Trạng thái | Bằng chứng | Ghi chú |
|---|---|---|---|---|
| 133 | Khóa/mở tenant | ✅ Done | `POST /tenants/{id}/suspend` + `/reactivate` (chỉ Platform Admin) | Có thêm `/cancel` (vĩnh viễn) ngoài checklist |
| 134 | Xem chi tiết tenant vận hành | ✅ Done | `GET /tenants/{id}/detail` → `TenantDetailService` (subscription, plan, usage thật) | — |
| 135 | Enforce giới hạn gói | ✅ Done | `PlanLimitEnforcementService` gọi thật từ Employee/Site/RandomCheck service, chặn khi vượt quota | — |
| 136 | Xem danh sách audit log | ✅ Done | `GET /audit-logs`, filter đa dạng, tenant-scope enforce (fix 06/08) | — |
| 137 | Xem diff old/new value | ✅ Done | `AuditLogResponse.oldValue/newValue`, mask trước khi ghi DB | — |
| 138 | Trace theo request_id | ✅ Done | `GET /audit-logs?requestId=` → `findByRequestId` | — |
| 139 | Quản lý template thông báo | ✅ Done | CRUD đầy đủ + `NotificationService.createNotification` giờ **thật sự dùng** `renderTemplateIfExists` (gap cũ từ đầu dự án đã fix) | — |
| 140 | Retry và fallback notification | ✅ Done | `FcmClient` retry backoff 3 lần; fallback email khi push fail toàn bộ, ghi log delivery | — |
| 141 | Cấu hình nhận thông báo cá nhân | ✅ Done | `UserNotificationSettingController`, được `NotificationService` tôn trọng thật khi tạo notification | — |

**Extra/hidden**: `POST /tenants/{id}/cancel`, IP whitelist CRUD, `DataRetentionJob` (dọn log/notification/ảnh cũ hàng tuần — chính là #144).

## 10. Cron / Cleanup / Masking / RBAC guard / System status / Docs (9/9 Done, dưới dạng đã ghi chú Partial ở #145)

| # | Tính năng | Trạng thái | Bằng chứng | Ghi chú |
|---|---|---|---|---|
| 142 | Cron refresh attendance nightly | ✅ Done | `AttendanceSummaryJob` cron `0 0 1 * * *` | — |
| 143 | Monitor scheduled check job | ✅ Done | `ScheduledJobCatalog` + `ScheduledJobStatusRepository` + `/system-status` phát hiện job "stale"/"never run" | — |
| 144 | Dọn dữ liệu ảnh và notification cũ | ✅ Done | `DataRetentionJob` cron `0 0 3 * * SUN`, dọn delivery log/notification đã đọc/embedding bị revoke/ảnh check-in cũ | Ngưỡng 30 ngày cho ảnh sinh trắc học là default tạm, cần chốt lại với legal/compliance |
| 145 | Mask dữ liệu nhạy cảm trong audit và API | ⚠️ Partial | `MaskingUtils.PII_KEYS` + `@Masked` che email/phone/token đầy đủ (kể cả Excel export, audit log ghi DB) sau fix 06/08 | **Chưa che** `national_id`, `totp_secret`, `backupCodes` ở bất kỳ DTO/audit map nào |
| 146 | Guard quyền API theo RBAC | ✅ Done (kiến trúc khác literal) | Không phải mọi controller có `@PreAuthorize`, nhưng đã audit riêng (06/08) xác nhận: chặn xuyên-tenant thật sự nằm ở **tầng service**, tự tra quyền theo `tenantId` lấy từ path (không phụ thuộc JWT/`@PreAuthorize`) — pattern nhất quán ở mọi module đã rà | Không phải gap, nhưng là quy ước ngầm quan trọng — module mới bắt buộc phải theo đúng pattern này |
| 147 | Màn hình trạng thái hệ thống | ✅ Done | `GET /platform/system-status` — DB/Redis/FCM/AI service health, job staleness, tenant count | Vừa bổ sung `AiServiceHealthIndicator` (06/08) |
| 148 | Kịch bản kiểm thử end-to-end | ✅ Done | `docs/testing/manual-test-scenarios.md` (958 dòng, ~100 kịch bản) + `tests/` (162 script `.sh`) | Có cả mục B.8 luồng go-live từ tenant mới |
| 149 | Hướng dẫn Admin/HR/Employee | ✅ Done | `docs/user-guides/huong-dan-su-dung-theo-vai-tro.md` | — |
| 150 | Checklist triển khai tenant đầu tiên | ✅ Done | `docs/deployment/go-live-checklist.md`, dẫn nguồn từ sự cố thật đã gặp trong dự án | — |

---

## Việc nên làm tiếp theo

1. ~~**#31 — audit log cho tenant/RBAC/subscription**~~ — **Đã xử lý 2026-08-07**. `auditLogService.record(...)` giờ được gọi từ `TenantService` (create/update/suspend/reactivate/cancel), `RoleService` (create/update/delete), `UserRoleService` (assign/assignPlatform/revoke), `TenantSubscriptionService` (assign/update), `PlanService` (create/update), `PlanLimitsService` (updateLimits) — verify trực tiếp qua `GET /audit-logs?entityType=...`, có đầy đủ oldValue/newValue diff. `SubscriptionExpirationJob` (cron tự-suspend tenant hết hạn) ghi `actorId=null` (system-initiated, cùng convention các cron job khác).
2. ~~**#145 — mở rộng masking**~~ — **Đã xử lý 2026-08-07**: `MaskingUtils.PII_KEYS` thêm `totpSecret`/`backupCodes`/`nationalId`/`identityNumber`/`idNumber`, phòng vệ ở tầng ghi audit (chưa có call site nào thực sự đưa các field này vào audit map hiện tại — `nationalId` còn chưa tồn tại như field thật trong hệ thống — nhưng redact-by-key-name ở tầng ghi nghĩa là chúng không thể lộ nếu sau này ai đó dump nguyên entity vào audit map, cùng triết lý với `docs/reviews/backend/data-masking-permission-health-uat-golive-audit-2026-08-06.md`).
3. ~~**#118 — attendance-impact nên tự trigger recompute**~~ — **Đã xử lý 2026-08-07**: `updateAttendanceImpact()` giờ gọi `attendanceSummaryService.recomputeIfSummaryExists(...)` giống hệt `confirmViolation`/`dismissViolation`.
4. ~~**#130 — cân nhắc 1 endpoint tổng hợp**~~ — **Đã xử lý 2026-08-07**: mở rộng `GET .../dashboard/supervisor` sẵn có (không tạo endpoint mới) — `SiteStatus` thêm `siteLatitude`/`siteLongitude`, `OnSiteEmployee` thêm `checkInLat`/`checkInLon` (vị trí lúc check-in, không phải tracking liên tục — hệ thống không có background location tracking).
5. ~~**#60 — quyết định có cần giới hạn giờ OT tối đa hay không**~~ — **Đã xử lý 2026-08-07**, theo quyết định người dùng (tham khảo Deputy/ADP): thêm `Shift.maxOtMinutesPerDay`/`maxOtMinutesPerWeek` (null = không giới hạn), **chỉ cảnh báo** — không chặn checkout, không cap `otMinutes`, chỉ set `AttendanceSummary.otDailyLimitExceeded`/`otWeeklyLimitExceeded` để HR review. Tuần tính theo ISO week (Thứ 2 - Chủ Nhật), theo employee (không theo site, vì OT tuần là giới hạn cho người, không cho vị trí). Migration `V88__add_shift_ot_hour_limits.sql`.
6. Không cần hành động gì cho #113/#146 — đúng vị trí/kiến trúc khác literal checklist nhưng nghiệp vụ đã đủ.

**Phát hiện phụ trong lúc verify các fix trên (2026-08-07, ngoài phạm vi 6 mục, chưa sửa)**: 16 test script (`tests/**/*.sh`) tạo tenant bằng tài khoản Platform Admin qua `POST /tenants` mà không truyền `ownerEmail`/`ownerUserId` — `TenantService.createTenant()` đã yêu cầu 1 trong 2 field này cho luồng platform-provisioned từ trước (pre-existing, không phải do các thay đổi hôm nay), nên các script đó fail ngay ở bước setup (`SETUP FAILED: tenant`). Xác nhận độc lập với code đúng/sai bằng cách chạy `test_hr_confirm_violation.sh`/`test_hr_dismiss_violation.sh` (hoàn toàn không sửa trong đợt này) — fail giống hệt cùng lý do. Cần cập nhật lại các test script này (thêm `ownerEmail`) ở một đợt riêng.
