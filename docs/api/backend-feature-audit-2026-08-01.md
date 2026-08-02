# Backend Feature Audit — Đối chiếu checklist 99 tính năng (01/08/2026)

> Phương pháp: đối chiếu từng mục checklist với code thật (controller/service/entity/migration) trong `api-server/` (Java/Spring) và `ai-service/` (Python/FastAPI, riêng Face ID). Không dựa vào tài liệu cũ hay suy đoán — mỗi dòng đều có file:line cụ thể. 6 nhóm được audit song song, độc lập.

## Kết quả tổng quan

| Trạng thái | Số lượng | Tỉ lệ |
|---|---|---|
| ✅ Done | 97 | 98% |
| ⚠️ Partial | 2 | 2% |
| ❌ Missing | 0 | 0% |
| **Tổng** | **99** | **100%** |

**2 điểm Partial** (không phải "chưa làm", mà là làm thiếu 1 phần so với mô tả checklist):
- **#31 Ghi audit cho hành động quan trọng** — hạ tầng audit (entity/repository/service/controller) đầy đủ và đã dùng thật cho các sự kiện auth (login, đổi mật khẩu, Google link...), nhưng **chưa gọi tới** ở service tenant, RBAC (tạo/sửa/xóa role, gán/thu hồi role), và subscription/plan — các hành động quản trị quan trọng này hiện không có audit trail.
- **#60 Cấu hình OT và giới hạn giờ** — chỉ có cờ bật/tắt OT (`allowOvertime`) và số phút dung sai check-in sớm/check-out muộn; **không có** field giới hạn số giờ OT tối đa/ngày hoặc /tuần ở bất kỳ đâu trong shift/assignment/attendance module.

**Lưu ý đặc biệt — #98 "Tạo Bull/BullMQ job gửi check"**: hệ thống **không dùng BullMQ** (thư viện Node.js, không tồn tại trong repo — đã grep toàn repo xác nhận không có `package.json`/`bullmq` nào). Thay vào đó là cơ chế tương đương tự viết bằng Java: Redis Sorted Set làm hàng đợi delayed-dispatch (`RandomCheckDispatchQueue`, ZADD/ZRANGEBYSCORE) + `@Scheduled` job Spring poll mỗi 60 giây (`RandomCheckDispatchJob`). Comment trong code tự nhận: *"Java/Spring equivalent of a Bull/BullMQ delayed job queue"*. Tính theo đúng mục đích nghiệp vụ (sinh job trễ, gửi đúng giờ) thì **đã làm đủ** — chỉ khác công nghệ so với chữ nghĩa literal của checklist.

---

## 1. Auth & Account (14/14 Done)

| # | Tính năng | Trạng thái | Bằng chứng | Ghi chú |
|---|---|---|---|---|
| 1 | Đăng nhập email/mật khẩu | ✅ Done | `AuthController.java:295-300` `POST /api/v1/auth/login`; `AuthService.login()` L100-238 | Nhận `identifier` là email hoặc phone, tự phân biệt |
| 2 | Đăng nhập bằng số điện thoại OTP | ✅ Done | `AuthController.java:338-344` `POST /auth/otp/verify`; `FirebasePhoneLoginService.java:38-60` | OTP qua Firebase Phone Auth, backend verify Firebase ID token; rate-limit theo IP, không phải khóa theo tài khoản |
| 3 | Đăng nhập Google | ✅ Done | `AuthController.java:635-640` `POST /auth/login/google`; `GoogleLoginService.java:37-60+` | Tự tạo account lần đầu; có thêm link/unlink Google |
| 4 | Đăng xuất khỏi thiết bị hiện tại | ✅ Done | `AuthController.java:352-361` `POST /auth/logout`; `LogoutService.java:44-92` | Blacklist access token (Redis) + thu hồi refresh token |
| 5 | Đăng xuất khỏi tất cả thiết bị | ✅ Done | `AuthController.java:563-572` `POST /auth/logout/all`; `LogoutService.logoutAll()` L72-83 | Có thêm `/logout/others` và logout theo session cụ thể |
| 6 | Đăng ký tài khoản người dùng | ✅ Done | `AuthController.java:163-171,203-212`; `RegisterService.java` | Hỗ trợ cả email+password và phone+OTP |
| 7 | Quên mật khẩu | ✅ Done | `AuthController.java:261-267` `POST /auth/forgot-password`; `PasswordResetService.forgotPassword()` | Response giống nhau dù email có tồn tại hay không (chống dò email) |
| 8 | Đặt lại mật khẩu | ✅ Done | `AuthController.java:276-281` `POST /auth/reset-password`; `PasswordResetService.resetPassword()` | Reset thành công cũng tự mở khóa tài khoản đang bị lock |
| 9 | Đổi mật khẩu | ✅ Done | `AuthController.java:544-555` `POST /auth/change-password`; `ChangePasswordService.changePassword()` | Đổi xong tự đăng xuất khỏi mọi phiên khác |
| 10 | Xem thông tin cá nhân | ✅ Done | `AuthController.java:370-376` `GET /auth/me` | — |
| 11 | Cập nhật hồ sơ cá nhân | ✅ Done | `AuthController.java:388-395` `PATCH /auth/me` + avatar/email-change/phone-change riêng | Đổi email/phone tách thành flow xác nhận 2 bước riêng |
| 12 | Bật TOTP 2FA | ✅ Done | `AuthController.java:702-708,736-743`; `TotpService.java` | QR setup → verify → trả backup codes |
| 13 | Đăng nhập có 2FA | ✅ Done | `AuthController.java:296-300,687-692`; `LoginTotpService.loginWithTotp()` | Pending token Redis TTL 5 phút, one-shot |
| 14 | Khóa tài khoản khi đăng nhập sai | ✅ Done | `AuthService.java:106-137`; `AppConstants: MAX_FAILED_ATTEMPTS=5, LOCK_DURATION_MINUTES=60` | Chỉ áp dụng path email/phone+password, không áp dụng Google/Firebase-phone/TOTP |

## 2. Tenant / Platform / RBAC / Audit (17/18 Done, 1 Partial)

| # | Tính năng | Trạng thái | Bằng chứng | Ghi chú |
|---|---|---|---|---|
| 15 | Tạo tenant mới | ✅ Done | `TenantController.java:132-144` `POST /tenants` | Hỗ trợ self-service lẫn platform tạo hộ |
| 16 | Xem danh sách tenant | ✅ Done | `TenantController.java:73-88` `GET /tenants` | Filter search/status/industry/country |
| 17 | Cập nhật thông tin tenant | ✅ Done | `TenantController.java:158-166` `PATCH /tenants/{id}` | Chỉ owner sửa được |
| 18 | Cấu hình giao diện và định dạng | ✅ Done | `TenantController.java:179-208`; `TenantSettingsResponse.java` | dateFormat/timeFormat/màu brand/logo/locale/timezone |
| 19 | Quản lý IP whitelist | ✅ Done | `TenantController.java:219-288`; enforce thật tại `JwtAuthFilter.java:199-202` | CRUD + enforce runtime thật, không chỉ lưu cấu hình |
| 20 | Quản lý gói dịch vụ | ✅ Done | `PlanController.java:48-110` | Deactivate plan có xử lý migrate tenant đang dùng |
| 21 | Cấu hình giới hạn gói | ✅ Done | `PlanController.java:119-143`; enforce tại `PlanLimitEnforcementService` (dùng trong EmployeeService, SiteService) | Có enforce thật khi tạo nhân viên/site, không chỉ lưu số |
| 22 | Gán subscription cho tenant | ✅ Done | `TenantController.java:319-327,399-407` | 409 nếu đã có subscription active |
| 23 | Seed role và permission hệ thống | ✅ Done | `V13__seed_roles_and_permissions.sql` (~40 permission, 5 role hệ thống) + các migration bổ sung sau | Seed qua Flyway migration, không phải Java seeder |
| 24 | Danh sách role | ✅ Done | `RoleController.java:74-95` `GET /roles` (+ `/roles/me`) | — |
| 25 | Tạo role tùy chỉnh | ✅ Done | `RoleController.java:129-142` `POST /roles` | 409 nếu trùng tên trong tenant |
| 26 | Sửa role và quyền | ✅ Done | `RoleController.java:157-170` `PUT /roles/{id}` | Role hệ thống không sửa được (403) |
| 27 | Xóa hoặc vô hiệu hóa role | ✅ Done | `RoleController.java:180-191` (xóa cứng nếu chưa gán ai) + `isActive` field (vô hiệu hóa mềm) | 2 cơ chế song song |
| 28 | Xem permission theo nhóm | ✅ Done | `PermissionController.java:41-50` `GET /permissions` | Group theo resource |
| 29 | Gán role cho user | ✅ Done | `UserRoleController.java:46-60,74-86` | Có cả tenant-scope và platform-scope |
| 30 | Thu hồi role | ✅ Done | `UserRoleController.java:96-107` `DELETE /user-roles/{id}` | — |
| 31 | Ghi audit cho hành động quan trọng | ⚠️ **Partial** | `AuditLogService.record()` chỉ được gọi từ `AuthService`, `GoogleLoginService`, `AttendanceSummaryService` | **Thiếu**: tenant service, RBAC service (tạo/sửa/xóa role, gán/thu hồi role), subscription/plan service — không grep thấy lời gọi `audit` nào trong 3 module này dù hạ tầng đã đầy đủ |
| 32 | Tạo notification in-app cơ bản | ✅ Done | `NotificationController.java:153-163` `POST /internal/notifications` | Endpoint nội bộ, không cần auth theo thiết kế (network-level restrict) |

## 3. Invitation / Employee / Workspace / Face ID (19/19 Done)

| # | Tính năng | Trạng thái | Bằng chứng | Ghi chú |
|---|---|---|---|---|
| 33 | Mời nhân viên bằng email | ✅ Done | `EmployeeInvitationController.java:78-86`; gửi mail thật qua `EmailService` | — |
| 34 | Chấp nhận lời mời | ✅ Done | `InvitationPublicController.java:73-79` `POST /invitations/accept` | Public endpoint, token là credential |
| 35 | Hủy lời mời | ✅ Done | `EmployeeInvitationController.java:103-112` `DELETE .../invitations/{id}` | Chỉ hủy được khi còn `pending` |
| 36 | Danh sách nhân viên | ✅ Done | `EmployeeController.java:117-135` `GET /employees` | Phân trang, filter |
| 37 | Xem chi tiết nhân viên | ✅ Done | `EmployeeController.java:236-246` | Gồm cả role/workspace/assignment/Face ID status |
| 38 | Tạo nhân viên thủ công | ✅ Done | `EmployeeController.java:64-74` `POST /employees` | Không cần email invite |
| 39 | Cập nhật nhân viên | ✅ Done | `EmployeeController.java:183-194` `PATCH /employees/{id}` | Partial update |
| 40 | Tạm ngừng/nghỉ việc nhân viên | ✅ Done | `EmployeeController.java:210-221` `PATCH .../status` | status ∈ active/inactive/terminated |
| 41 | Import danh sách nhân viên | ✅ Done | `EmployeeController.java:92-102` `POST /employees/import` | Chỉ hỗ trợ .xlsx, không CSV |
| 42 | Export danh sách nhân viên | ✅ Done | `EmployeeController.java:150-165` `GET /employees/export` | Trả .xlsx |
| 43 | Tạo workspace | ✅ Done | `WorkspaceController.java:62-71` | Hỗ trợ `parentId` phân cấp |
| 44 | Danh sách workspace | ✅ Done | `WorkspaceController.java:89-105,124-134` | Cả list phẳng lẫn tree |
| 45 | Cập nhật workspace | ✅ Done | `WorkspaceController.java:159-169` | Check circular-parent |
| 46 | Gán nhân viên vào workspace | ✅ Done | `WorkspaceMemberController.java:60-71` | 409 nếu đã là member |
| 47 | Chuyển workspace cho nhân viên | ✅ Done | `WorkspaceMemberController.java:125-138` | Atomic remove+add |
| 48 | Ghi nhận đồng ý Face ID | ✅ Done | `FaceIdController.java:58-67` `POST .../face-id/consent` | Chỉ tự nhân viên đồng ý được (kể cả HR có quyền manage cũng không consent hộ) — theo Nghị định 13/2023/NĐ-CP |
| 49 | Đăng ký Face ID | ✅ Done | `FaceIdController.java:137-148` (HR-assisted) + `:167-246` (self-service qua liveness challenge) → `ai-service/app/routers/enroll.py` | Cả 2 luồng đều vào trạng thái "chờ HR duyệt" |
| 50 | HR xem trạng thái Face ID | ✅ Done | `FaceIdController.java:82-90`; `FaceIdReviewController.java:37-45` (hàng chờ duyệt toàn tenant) | Có thêm approve/reject |
| 51 | Xóa/vô hiệu hóa Face ID | ✅ Done | `FaceIdController.java:105-114` `DELETE .../face-id` | Xóa ảnh + embedding phía ai-service |

## 4. Site / Geofence / Shift / Assignment (15/16 Done, 1 Partial)

| # | Tính năng | Trạng thái | Bằng chứng | Ghi chú |
|---|---|---|---|---|
| 52 | Tạo công trình | ✅ Done | `SiteController.java:64-73` `POST /sites` | Unique name/code/tenant |
| 53 | Danh sách công trình | ✅ Done | `SiteController.java:92-107` | Phân trang, search/filter |
| 54 | Xem chi tiết công trình | ✅ Done | `SiteController.java:163-172` | Gồm geofence active, shift templates, số assignment |
| 55 | Cập nhật công trình | ✅ Done | `SiteController.java:132-142` | — |
| 56 | Tạo geofence cho công trình | ✅ Done | `GeofenceController.java:61-71`; yêu cầu polygon ≥4 điểm khép kín | Geofence mới thay thế geofence active cũ |
| 57 | Sửa geofence | ✅ Done | `GeofenceController.java:126-136` | Copy-on-write: tạo bản ghi mới, không sửa tại chỗ |
| 58 | Xem lịch sử geofence | ✅ Done | `GeofenceController.java:90-101`; field `status` trên mỗi bản ghi | Lịch sử thật (versioned), không bị ghi đè mất dữ liệu cũ |
| 59 | Tạo ca làm việc | ✅ Done | `ShiftController.java:64-74` | Hỗ trợ `allowOvernight` |
| 60 | Cấu hình OT và giới hạn giờ | ⚠️ **Partial** | `ShiftController.java:166-177` `PUT .../ot-config`; field `allowOvertime`, `earlyCheckinMinutes`, `lateCheckoutMinutes` | Chỉ có cờ bật/tắt OT + phút dung sai; **không có** field giới hạn số giờ OT tối đa (ngày/tuần) — đã grep `maxHours`/`otLimit`/`overtimeLimit` không thấy đâu trong repo |
| 61 | Danh sách ca theo site | ✅ Done | `ShiftController.java:93-105` | — |
| 62 | Cập nhật hoặc ngừng dùng ca | ✅ Done | `ShiftController.java:130-141` (soft-deactivate) + `:199-208` (hard-delete nếu chưa có assignment nào tham chiếu) | 2 cơ chế |
| 63 | Tạo phân công nhân viên vào site | ✅ Done | `AssignmentController.java:155-166` | 409 nếu đã có assignment active trùng employee+site |
| 64 | Danh sách phân công | ✅ Done | `AssignmentController.java:51-69` | Filter status/role/employee/shift |
| 65 | Cập nhật phân công | ✅ Done | `AssignmentController.java:89-100` | Hỗ trợ `clearShift`/`clearEndDate` |
| 66 | Hủy phân công | ✅ Done | `AssignmentController.java:117-128`; tự hủy luôn scheduled check pending liên quan | Soft-status "cancelled", giữ lại để audit |
| 67 | Hiển thị site được phép check-in | ✅ Done | `CheckinController.java:69-74` `GET /checkin/available-sites` | Xử lý đúng theo timezone site-local, kể cả ca đêm |

## 5. Check-in / Check-out / Attendance (19/19 Done)

| # | Tính năng | Trạng thái | Bằng chứng | Ghi chú |
|---|---|---|---|---|
| 68 | Check-in GPS cơ bản | ✅ Done | `CheckinController.java:107-115`; geofence check bằng PostGIS | — |
| 69 | Check-in có Face ID | ✅ Done | `CheckinService.enforceCheckinPolicy` L1028-1094; verify async qua ai-service | Policy tùy site/shift: `gps_only`/`gps_face`/`gps_face_liveness` |
| 70 | Check-in có liveness | ✅ Done | Policy `gps_face_liveness` bắt buộc challenge `passed`, còn hạn, đúng site (L1047-1093) | Tiêu thụ atomically (`consumeIfPassed`) |
| 71 | Kiểm tra check-in sớm | ✅ Done | `CheckinService.validateCheckinWindow` L487-513; áp dụng cả cho offline sync | `CHECKIN_TOO_EARLY`/`CHECKIN_TOO_LATE` |
| 72 | Check-out GPS | ✅ Done | `CheckinController.submitCheckout` L380-389 | Ngoài geofence → escalate `pending_review` |
| 73 | Kiểm tra check-out muộn | ✅ Done | Gộp trong `computeWorkMinutes` (cap theo `lateCheckoutMinutes`) | Không phải cờ "late checkout" riêng biệt, mà gộp vào tính work-minutes/OT |
| 74 | Tính work_minutes cho cặp check-in/out | ✅ Done | `CheckinService.computeWorkMinutes` L421-477 | Có tính ca đêm, cap OT |
| 75 | Check-in offline và đồng bộ | ✅ Done | `POST /checkin/sync`; `OfflineSyncService` — idempotent qua `clientNonce`, phát hiện overlap | `gps_face_liveness` luôn bị escalate khi sync offline vì không chứng minh lại được |
| 76 | Hiển thị kết quả check-in/out | ✅ Done | `CheckinController.getCheckinResult`; `resolveDisplayMessage` sinh message tiếng Việt | — |
| 77 | Nhân viên xem lịch sử chấm công | ✅ Done | `GET /checkin/history` | Tự scope theo employee đang đăng nhập |
| 78 | HR xem danh sách check-in | ✅ Done | `GET /checkin` (list) | Filter employee/site/status/date, site-scope enforced |
| 79 | HR xem chi tiết check-in | ✅ Done | `GET /checkin/{id}/detail` | Đầy đủ bằng chứng: face verify result, override audit |
| 80 | Tự động tạo attendance summary | ✅ Done | `AttendanceSummaryJob` cron `0 0 1 * * *` (đêm) + recompute real-time mỗi lần checkin/checkout | Idempotent upsert |
| 81 | Tính đi muộn | ✅ Done | `AttendanceSummaryService.recompute` — "Task 81" block | So sánh check-in đầu tiên với giờ ca (snapshot lúc check-in) |
| 82 | Tính về sớm | ✅ Done | "Task 82" block | Chỉ tính khi mọi session đã đóng |
| 83 | Tính OT | ✅ Done | "Task 83" block | Chỉ tính nếu `allowOvertime=true` |
| 84 | Phát hiện thiếu checkout | ✅ Done | "Task 84" block; field `missingCheckout` | Cũng tổng hợp theo tháng |
| 85 | Nhân viên xem bảng công ngày/tháng | ✅ Done | `GET /attendance/me` + `/attendance/me/monthly` | — |
| 86 | HR xem bảng công tổng hợp | ✅ Done | `GET /attendance` + `/attendance/monthly` (aggregate DB-level) | Có thêm adjust/unlock-and-recompute cho HR sửa tay |

## 6. Notification & Random Check (13/13 Done)

| # | Tính năng | Trạng thái | Bằng chứng | Ghi chú |
|---|---|---|---|---|
| 87 | Đăng ký thiết bị nhận push | ✅ Done | `UserDeviceController.java:53-62` `POST /me/devices` | Upsert token, reassign nếu đăng ký lại ở user khác |
| 88 | Gửi push notification | ✅ Done | `UserDeviceService.sendPush()` → `FcmClient.sendToToken` | Retry + delivery log + fallback email nếu FCM fail toàn bộ |
| 89 | Danh sách thông báo trong app/web | ✅ Done | `GET /notifications` | Phân trang, `unreadOnly`, `unreadCount` |
| 90 | Đánh dấu đã đọc | ✅ Done | `PATCH .../read` + `.../read-all` | Cả đơn lẻ và mark-all |
| 91 | Tạo cấu hình random check mặc định tenant | ✅ Done | `POST .../random-check-configs/tenant-default` | 1/tenant, 409 nếu đã có |
| 92 | Tạo cấu hình override theo site | ✅ Done | `POST .../random-check-configs/sites/{siteId}` + endpoint `effective` resolve | 1/site, 409 nếu đã có |
| 93 | Cấu hình số lần và khung giờ check | ✅ Done | field `checksPerShift`, `minIntervalMinutes`, `allowedStartTime/EndTime`, `responseWindowSeconds` | — |
| 94 | Cấu hình mode kiểm tra | ✅ Done | field `checkMode`; `PUT .../check-mode` | `location_only`/`location_face`/`location_face_liveness` |
| 95 | Cấu hình áp dụng theo vai trò | ✅ Done | field `applicableRoles`; `PUT .../applicable-roles` | Rỗng = áp dụng mọi vai trò |
| 96 | Tự động sinh scheduled checks đầu ca | ✅ Done | `RandomCheckSchedulerJob` cron `0 1 0 * * *` (00:01 hàng ngày) → `ScheduledCheckGeneratorService` | Sinh trước mọi ca trong ngày, không phải trigger đúng lúc từng ca bắt đầu nhưng đảm bảo sẵn sàng trước ca |
| 97 | Snapshot config khi sinh check | ✅ Done | `buildSnapshot(config)` lưu vào `ScheduledCheck.configSnapshot` | Sửa config sau này không ảnh hưởng check đã sinh |
| 98 | Tạo Bull/BullMQ job gửi check | ✅ Done (khác công nghệ) | `RandomCheckDispatchQueue` (Redis Sorted Set) + `RandomCheckDispatchJob` (`@Scheduled` poll 60s) | **Không dùng BullMQ thật** (không có Node.js trong repo) — tự implement tương đương bằng Java, xem ghi chú đầu tài liệu |
| 99 | Hủy scheduled check | ✅ Done | `POST .../scheduled-checks/{id}/cancel`; cũng tự hủy khi assignment bị hủy | Xóa khỏi Redis queue để không bị dispatch nữa |

---

## Việc nên làm tiếp theo (rút ra từ audit này)

1. **Bổ sung audit log cho tenant/RBAC/subscription service** (#31) — hạ tầng đã sẵn (`AuditLogService.record()`), chỉ cần thêm lời gọi ở các service tương ứng. Rủi ro compliance nếu để lâu — hành động như "xóa role", "gán subscription" hiện không truy vết được ai làm.
2. **Quyết định có cần giới hạn giờ OT tối đa hay không** (#60) — nếu cần, phải thêm field mới + logic enforce trong `AttendanceSummaryService`, hiện chưa có ở đâu.
3. Không cần hành động gì cho #98 (Bull/BullMQ) — chỉ là khác biệt thuật ngữ, hệ thống hiện tại đã đáp ứng đúng mục đích nghiệp vụ.
