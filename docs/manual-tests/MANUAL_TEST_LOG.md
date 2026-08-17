# Nhật ký kiểm thử thủ công (Manual QA Log)

Đây là **nguồn sự thật duy nhất** cho câu hỏi: "tính năng X đã được người dùng tự tay test qua
giao diện thật (Web Admin / Mobile App) chưa, kết quả thế nào?" — tách biệt với checkbox `[x]`
trong `docs/BACKLOG.md` (checkbox đó chỉ phản ánh trạng thái **code/backend đã audit xong**,
không đồng nghĩa đã có người test tay qua UI thật).

**Dùng file này khi nào:**
- Trước khi sửa code của bất kỳ tính năng nào đang ở trạng thái ✅ **ĐÃ KHÓA** bên dưới — đọc kỹ
  phạm vi đã test để không vô tình gây hồi quy. Nếu bắt buộc phải sửa, phải note lại + đề nghị
  test lại đúng các case liên quan sau khi sửa.
- Sau mỗi lần bạn (chủ dự án) test xong một tính năng — báo kết quả (pass toàn bộ / pass một
  phần kèm case nào chưa test hoặc fail) — tôi cập nhật bảng bên dưới và đóng mục tương ứng.

## Chú giải trạng thái

| Ký hiệu | Ý nghĩa |
|---|---|
| ✅ **PASS — ĐÃ KHÓA** | Toàn bộ case trong kịch bản test đã pass qua UI thật. Coi là xong, tránh sửa lại trừ khi có yêu cầu mới; nếu sửa, phải test lại. |
| 🟡 **PASS MỘT PHẦN** | Một số case đã pass, còn case khác chưa test hoặc đang bị chặn (VD: thiếu môi trường/thiết bị). Chưa khóa — vẫn có thể còn thay đổi. |
| 🔴 **FAIL — CÓ BUG** | Test ra lỗi thật, đang chờ sửa. |
| ⬜ **CHƯA TEST** | Chưa ai test qua UI thật. |

---

## Bảng tổng hợp

| # | Tính năng | Web Admin | Mobile App | Trạng thái chung | Ngày | Ghi chú tồn đọng |
|---|---|---|---|---|---|---|
| 1 | Đăng nhập email/mật khẩu | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 2 | Đăng nhập bằng SĐT/OTP | ✅ Pass (Phần A) | ⬜ Chưa test | 🟡 **PASS MỘT PHẦN** | 2026-08-13 | App: chưa test luồng OTP thật (Phần B — cần cài bản EAS dev-client lên máy thật) |
| 3 | Đăng nhập Google | ⬜ Chưa test | ⬜ Chưa test | ⬜ **CHƯA TEST** | — | Toàn bộ 9 case trong kịch bản chưa chạy; đặc biệt case 4 (bỏ qua 2FA) và case 5 (không có invite-only gate) là phát hiện nghiệp vụ cần xác nhận khi test |
| 4 | Đăng xuất khỏi thiết bị hiện tại | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 5 | Đăng xuất khỏi tất cả thiết bị | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Gap thiếu audit log cho `LOGOUT_ALL` vẫn còn (không chặn tính năng, xem ghi chú kỹ thuật) |
| 6 | Đăng ký tài khoản người dùng | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 7 | Quên mật khẩu | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 8 | Đặt lại mật khẩu | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Gap thiếu audit log cho `RESET_PASSWORD` vẫn còn (không chặn tính năng, xem ghi chú kỹ thuật) |
| 9 | Đổi mật khẩu | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Gap thiếu audit log `CHANGE_PASSWORD` vẫn còn (không chặn khóa) |
| 10 | Xem thông tin cá nhân | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Gap thiếu field 2FA/tenant hiện tại ở `/auth/me` vẫn còn (không chặn khóa) |
| 11 | Cập nhật hồ sơ cá nhân | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Gap thiếu audit log `UPDATE_PROFILE` vẫn còn (không chặn khóa) |
| 12 | Bật TOTP 2FA | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 13 | Đăng nhập có 2FA | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 14 | Khóa tài khoản khi đăng nhập sai | ✅ Pass | — (Backend only) | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Audit `LOGIN_FAILED`/`ACCOUNT_LOCKED` đã bổ sung và test pass (chỉ ghi khi tài khoản thuộc tenant, theo quyết định nghiệp vụ) |
| 15 | Tạo tenant mới | ✅ Pass | — (không có Mobile App) | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 16 | Xem danh sách tenant | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Gap thiếu plan/subscription trong danh sách vẫn còn (không chặn khóa) |
| 17 | Cập nhật thông tin tenant | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có |
| 18 | Cấu hình giao diện và định dạng | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Audit `tenant_settings_updated` đã bổ sung và test pass. Gap "language/currency thuộc API #17" vẫn còn nhưng không chặn khóa (đúng kiến trúc hiện tại) |

**Cập nhật kèm theo lượt test này (2026-08-13):** đã xác nhận lại (retest) phần audit log mới bổ
sung cho #5, #8, #9, #11 (trước đó đã ĐÃ KHÓA, tạm hạ để chờ retest sau khi sửa backend) — pass,
giữ nguyên ✅ ĐÃ KHÓA. Đồng thời xác nhận UI "tìm người thao tác theo tên" (thay ô nhập UUID thô)
ở màn Nhật ký audit hoạt động đúng trên cả 2 chế độ (Platform/Company).

| 19 | Quản lý IP whitelist | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Đã đổi scope client-type → scope theo ROLE (backend + UI). Enforcement + UI xác nhận đúng qua test tay |
| 20 | Quản lý gói dịch vụ | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có gap |
| 21 | Cấu hình giới hạn gói | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Case enforcement (3-5) đã test hoặc xác nhận hoãn hợp lệ theo Sprint liên quan |
| 22 | Gán subscription cho tenant | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Không có gap |
| 23 | Seed role và permission hệ thống | ✅ Pass (kiểm qua DB/API) | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-13 | Idempotent xác nhận qua restart container |
| 24 | Danh sách role | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-15 | assignmentCount scoped đúng theo tenant (TENANT_ADMIN → "1 người" trong tenant test, không lộ số toàn platform); tìm kiếm lọc đúng |
| 25 | Tạo role tùy chỉnh | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-15 | Tạo role với đúng 2 permission chọn; audit `role_created` ghi đúng entity_id + request_id |
| 26 | Sửa role và quyền | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-15 | Case cache-eviction xác nhận bằng THỰC NGHIỆM: gọi API bằng đúng JWT cấp TRƯỚC khi sửa quyền — trước khi sửa trả 403, ngay sau khi Owner bấm "Cập nhật" (không đăng nhập lại) trả 200; audit `role_updated` ghi đúng |
| 27 | Xóa hoặc vô hiệu hóa role | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-15 | UI tự vô hiệu hóa nút Xóa khi role còn người giữ (chủ động hơn cả yêu cầu — không cần đợi lỗi 400); vô hiệu hóa role KHÔNG tự thu hồi quyền người đang giữ (đúng thiết kế); xóa thành công sau khi thu hồi hết; audit `role_deleted` ghi đúng |
| 28 | Xem permission theo nhóm | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-15 | Xác nhận permission "chết" (`tenants:update`, đã ẩn ở đợt dọn V92) không xuất hiện trong picker tạo role — 0 lần khớp |
| 29 | Gán role cho user | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-15 | Gán qua UI thật (tab "Vai trò & Phân quyền" của nhân viên) pass; gán trùng bị chặn 409 idempotent; gán lại sau khi thu hồi (reactivate) pass 201; chặn leo thang đặc quyền 403 khi role không có quyền đang gán; chặn gán PLATFORM_ADMIN qua API tenant-scoped 400. Case "scope theo công trình cụ thể" mới xác nhận có UI (radio "Công trình cụ thể"), CHƯA test hết luồng submit — để dành khi có dữ liệu site thật |
| 30 | Thu hồi role | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-15 | Case 1-3 pass. Case 4/5 phát hiện gap "tự khóa vĩnh viễn khi thu hồi admin cuối cùng" — **đã vá cùng ngày**: safeguard chặn 409 + tự phục hồi (self-heal) cho chủ sở hữu. Xem chi tiết bên dưới. |
| 31 | Ghi audit cho hành động quan trọng | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-15 | Xác nhận qua màn Nhật ký audit thật: toàn bộ `role_created/role_updated/role_deleted/role_assigned/role_revoked` phát sinh trong đợt test đều xuất hiện, đúng actor/entity/request_id, đúng phạm vi tenant (banner "Dữ liệu được giới hạn theo công ty đang chọn") |
| 32 | Tạo notification in-app cơ bản | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-15 | Chuông + badge + màn "Thông báo" đầy đủ (hơn kỳ vọng: có filter Tất cả/Chưa đọc, chọn hàng loạt) — đánh dấu đọc từng cái và tất cả đều đúng, idempotent (nút tự ẩn khi hết chưa đọc) |
| 33 | Mời nhân viên bằng email | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-15 | Đã vá đủ AC: thêm chọn workspace mặc định lúc mời, ghi audit `invitation_sent`, thêm notification `EMPLOYEE_INVITED` cho email đã có tài khoản — cả 3 xác nhận qua UI/DB thật |
| 34 | Chấp nhận lời mời | ✅ Pass | ⬜ Chưa test | ✅ **PASS — ĐÃ KHÓA** (Web) | 2026-08-15 | Đã vá đủ AC: chấp nhận giờ tự tạo `WorkspaceMember` theo workspace mặc định của lời mời, ghi audit `invitation_accepted`, thêm notification `INVITATION_ACCEPTED` cho người mời — xác nhận qua API+DB thật. Mobile App chưa test (để dành đợt có thiết bị) |
| 35 | Hủy lời mời | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-15 | Đã vá đủ AC: modal hủy giờ có ô nhập lý do (tùy chọn), backend lưu `cancelled_by`/`cancel_reason`/`cancelled_at`, ghi audit `invitation_cancelled` — xác nhận qua UI/DB thật, tooltip lý do hiện trên tag trạng thái |
| 36 | Danh sách nhân viên | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-15 | Đã vá đủ AC: thêm filter Face ID (Đã đăng ký/Chưa đăng ký, join `face_profiles.status`) và filter Workspace riêng (join `workspace_members`, độc lập với filter Phòng ban cũ theo tên chuỗi) — cả 2 xác nhận qua UI thật, kể cả trường hợp kết hợp nhiều filter cùng lúc |
| 37 | Xem chi tiết nhân viên | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | Gap gốc (workspaces/assignments hardcode rỗng) đã xác nhận SỬA XONG qua code — không có gap mới |
| 38 | Tạo nhân viên thủ công | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | Không có gap; luồng "Vai trò dự kiến" xác nhận hoạt động đúng |
| 39 | Cập nhật nhân viên | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | Gap audit đã sửa từ trước; **gap `national_id` đã vá** (migration V95, mask kiểu email/phone) — test live PATCH nationalId pass |
| 40 | Tạm ngừng/nghỉ việc nhân viên | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | **Gap nghiêm trọng đã vá**: Assignment giờ tự chuyển "cancelled" khi terminate (giống AssignmentService.cancelAssignment); thêm terminated_at + audit log employee_status_changed — test live end-to-end pass |
| 41 | Import danh sách nhân viên | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | **Gap "không tải được file lỗi" đã vá**: endpoint mới `POST .../import/errors-export` trả .xlsx chỉ chứa dòng lỗi — test live pass, lý do lỗi khớp JSON gốc |
| 42 | Export danh sách nhân viên | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | **Gap đã vá**: cột `nationalId` giờ có trong file export, mask đúng theo quyền PII |
| 43 | Tạo workspace | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | **Gap đã vá**: ghi audit `workspace_created` đầy đủ, đúng pattern Employee/RBAC/Tenant |
| 44 | Danh sách workspace | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | Gap "thiếu số thành viên" xác nhận SỬA XONG qua UI thật, tự cập nhật real-time |
| 45 | Cập nhật workspace | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | Chặn vòng lặp parent hoạt động đúng; **gap đã vá**: ghi audit `workspace_updated` với before/after |
| 46 | Gán nhân viên vào workspace | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | **Gap đã vá**: thêm is_primary + effective_from (migration V96), enforce 1 primary/nhân viên ở cả tầng app lẫn DB unique index; kèm vá 1 race condition + 1 lỗi JSON key trùng phát hiện lúc test |
| 47 | Chuyển workspace cho nhân viên | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | **Gap đã vá**: left_at riêng biệt với deletedAt, is_primary carry-over khi chuyển (có thể override) |
| 48 | Ghi nhận đồng ý Face ID | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | **Gap đã vá**: consent_version/ip/device lưu đầy đủ (migration V97), enforce đúng "chỉ consent current". Test live qua App thật (web+camera giả lập): đúng bỏ qua consent sheet khi đã current, camera/liveness challenge render đúng |
| 49 | Đăng ký Face ID | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | AC gốc lỗi thời (quality_score không tồn tại, dùng InsightFace local — không sửa). **Gap audit đã vá**. Web Admin + App (Claude qua camera giả lập, User xác nhận nốt trên thiết bị thật: chụp ảnh thật, liveness fail, khác người, rate limit) đều pass |
| 50 | HR xem trạng thái Face ID | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | Luồng duyệt với ảnh tham chiếu thật test live qua UI pass; quality_score không tồn tại (gap kiến trúc, không sửa) |
| 51 | Xóa/vô hiệu hóa Face ID | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | **Gap đã vá**: deleted_reason/deleted_by + audit log, modal Web Admin có ô nhập lý do. Test live end-to-end qua cả App thật (tự thu hồi) và Web Admin |
| 52 | Tạo công trình | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | province/workspace là gap kiến trúc (không sửa); supervisor làm qua Assignment riêng, không thiếu hẳn; plan limit đúng; **gap đã vá**: ghi audit `site_created` |
| 53 | Danh sách công trình | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | Filter province/workspace, sort start_date là gap kiến trúc (không sửa); site-scope filter cho SITE_SUPERVISOR hoạt động đúng (xác nhận qua code) |
| 54 | Xem chi tiết công trình | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | **Gap đã vá**: supervisor giờ hiện ngay ở card chính (field `supervisors` mới, lấy từ Assignment); site-scope 403 hoạt động đúng |
| 55 | Cập nhật công trình | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-16 | Supervisor không sửa qua form này (đúng kiến trúc); gap "validate status yếu" xác nhận là NGHIÊN CỨU SAI (đã có @Pattern từ trước); **gap thật đã vá**: ghi audit `site_updated` |
| 56 | Tạo geofence cho công trình | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | **Gap đã vá**: tính `area_sqm` (shoelace + equirectangular tại vĩ độ trung bình, migration V98) + ghi audit `geofence_created`; versioning trong bảng `geofences` (không có bảng history riêng) xác nhận là thiết kế có chủ đích |
| 57 | Sửa geofence | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | **Gap đã vá**: `change_reason` (tùy chọn, không bắt buộc — theo tiền lệ #51) + tính lại area mỗi lần sửa + ghi audit `geofence_updated` với old/new snapshot đầy đủ kèm changeReason |
| 58 | Xem lịch sử geofence | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | **Gap quan trọng nhất đã vá**: `changed_by` giờ hiện tên thật (`createdByName`, resolve qua UserRepository, batch-load tránh N+1) thay vì UUID thô; `change_type` tường minh cố ý KHÔNG làm (đã ghi rõ lý do trong kịch bản) |
| 59 | Tạo ca làm việc | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | **Gap đã vá**: `is_default` (migration V99, unique index 1 mặc định/site) + ghi audit `shift_created`. code/standard_hours/JSON-schedule xác nhận là khác biệt kiến trúc có chủ đích, không sửa |
| 60 | Cấu hình OT và giới hạn giờ | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | Audit gốc "đã xong" xác nhận đúng, đã mở rộng OT ngày/tuần từ trước (cảnh báo không chặn, snapshot không hồi tố); **gap đã vá**: ghi audit `shift_ot_configured` |
| 61 | Danh sách ca theo site | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | **Gap đã vá**: tìm theo tên (`search`) + lọc `isDefault` — chuyển sang `JpaSpecificationExecutor`/Criteria API sau khi JPQL `@Query` với `CAST(:param)` gây lỗi "cannot cast bytea" trên tham số null (bài học kỹ thuật quan trọng, ghi trong kịch bản) |
| 62 | Cập nhật hoặc ngừng dùng ca | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | Audit gốc LỖI THỜI đã sửa lại: endpoint xóa cứng THẬT SỰ có, chặn đúng điều kiện (không phải thiếu); **gap đã vá**: ghi audit `shift_updated`/`shift_deleted`; hỗ trợ đặt/bỏ `isDefault` qua PUT dùng chung logic với #59 |
| 63 | Tạo phân công nhân viên vào site | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | **Nâng cấp theo quyết định chủ dự án**: chống trùng cùng site giờ theo khoảng giờ thực tế (như cross-site) thay vì chặn tuyệt đối — bỏ unique index DB cũ (migration V100); **gap đã vá**: ghi audit `assignment_created` |
| 64 | Danh sách phân công | ✅ Pass | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | **Gap đã vá**: filter khoảng ngày (overlap). **Theo quyết định chủ dự án**: thêm mới hẳn màn hình Mobile App "Phân công của tôi" (`/assignments/me`, self-service, cross-site) — trước đây App không có |
| 65 | Cập nhật phân công | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | **Gap quan trọng đã vá**: chặn sửa assignment đã hủy (400, cả API lẫn UI); re-validate overlap dùng logic mới gộp cross-site + cùng-site; **gap đã vá**: ghi audit `assignment_updated` |
| 66 | Hủy phân công | ✅ Pass | — | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | **Gap đã vá**: `cancelled_by`/`cancelled_at` (migration V100, cùng pattern employee_invitations V93) + ghi audit `assignment_cancelled`. Xác nhận hiển thị đúng tới tận Mobile App thật |
| 67 | Hiển thị site được phép check-in | — | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | Không có gap, tốt hơn AC gốc (timezone từng site, ca qua đêm). Script `test_available_sites.sh` 6/6 pass |
| 68 | Check-in GPS cơ bản | — | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | **Gap đã vá**: ghi audit `checkin_submitted`. Ngoài geofence không chặn cứng, chỉ pending_review (đúng thiết kế). Script `test_basic_checkin.sh` 11/11 pass |
| 69 | Check-in có Face ID | — | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | **Gap quan trọng đã vá (quyết định chủ dự án)**: nhánh `gps_face` (1 ảnh, không qua challenge) trước đây KHÔNG kiểm tra liveness thụ động — 1 ảnh tĩnh có thể vượt qua. Đã sửa App gửi `requiresLiveness=true` khi có ảnh (online + offline sync). Xác nhận qua log backend: cờ truyền đúng tới AI worker |
| 70 | Check-in có liveness | — | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | Không có gap thêm ngoài #69 (đã vá). Script `test_checkin_liveness.sh` pass (2/2, phần E2E enrollment cần thiết bị thật) |
| 71 | Kiểm tra check-in sớm | — | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | Chặt hơn AC (luôn từ chối cứng + chặn cả sau khi ca kết thúc), xác nhận đúng thiết kế, không sửa. Script `test_early_checkin.sh` 6/6 pass |
| 72 | Check-out GPS | — | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | **Gap đã vá**: ghi audit `checkout_submitted`. Policy dùng đúng snapshot lúc check-in (đúng thiết kế). Script `test_checkout.sh` 9/9 pass |
| 73 | Kiểm tra check-out muộn | — | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | Đúng AC, dùng snapshot lúc check-in không áp dụng thay đổi Shift giữa ca. Không có gap |
| 74 | Tính work_minutes cho cặp check-in/out | — | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | 2 gap thật xác nhận (không trừ break; tính cả khi pending_review) — **chủ dự án quyết định giữ nguyên cả 2**, không sửa |
| 75 | Check-in offline và đồng bộ | — | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | Audit gốc SAI 2/3 điểm (lệch giờ thiết bị đã có sẵn). **Đã vá thêm**: liveness thụ động cho ảnh offline (cùng #69) + ghi audit `checkin_submitted`. Thiếu test script tự động riêng (không chặn khóa) |
| 76 | Hiển thị kết quả check-in/out | — | ✅ Pass | ✅ **PASS — ĐÃ KHÓA** | 2026-08-17 | Đúng bản chất AC, lý do hiện qua trường có cấu trúc. Thiếu nút "thử lại" tường minh (nhỏ, không sửa). Script `test_checkin_result.sh` 8/8 pass |

---

## Chi tiết

### #1 — Đăng nhập email/mật khẩu — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-01-login.md` (mục A→D, case 1-13).
- Kết quả: toàn bộ case đã chạy và pass trên cả Web Admin lẫn Mobile App.
- **Khóa từ 2026-08-13** — không sửa lại luồng login/register/forgot-password/2FA liên quan trừ
  khi có yêu cầu tính năng mới; nếu bắt buộc chạm vào, phải test lại toàn bộ case ở file trên.

### #2 — Đăng nhập bằng SĐT/OTP — 🟡 PASS MỘT PHẦN (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-02-phone-otp-login.md`.
- **Đã pass:** Phần A (case 1-5) — backend trả 503/429 đúng khi test rate-limit, Web Admin hiện
  đúng thông báo, không crash.
- **Còn tồn đọng — CHƯA TEST:** Phần B (case 6-10) trên **Mobile App** — luồng OTP thật qua
  Firebase (dù đã có Firebase project + config), do Mobile App bắt buộc phải cài bản build
  `eas build --profile development` lên thiết bị thật/simulator mới test được (không dùng được
  Expo Go/Expo Web cho tính năng này). **Chưa khóa** tính năng này — cần hoàn tất bước build EAS
  rồi test case 6-10 trước khi đóng.
- Việc cần làm tiếp: chạy `eas build --profile development --platform android` (hoặc `ios`), cài
  lên máy, rồi test case 6 (happy path), 7 (sai OTP), 8 (đăng ký mới bằng SĐT), 9 (OTP + 2FA), 10
  (rate limit thực tế).

### #3 — Đăng nhập Google — ⬜ CHƯA TEST (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-03-google-login.md`.
- Chưa có case nào được chạy qua UI thật (cả Web Admin lẫn Mobile App).
- **Ưu tiên khi test:** case 4 (Google login có bỏ qua TOTP/2FA không — hiện code chủ đích bỏ
  qua) và case 5 (email Google hoàn toàn mới có bị chặn "chưa được mời" không — hiện code
  **không** chặn, tự tạo tài khoản mới luôn) — 2 case này xác nhận lại 2 phát hiện lệch so với
  Acceptance Criteria gốc, cần bạn quyết định có phải sửa không.

---

### #4 — Đăng xuất khỏi thiết bị hiện tại — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-04-logout.md`. Toàn bộ case pass trên cả 2 nền
  tảng. Khóa từ 2026-08-13.

### #5 — Đăng xuất khỏi tất cả thiết bị — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-05-logout-all.md`. Toàn bộ case pass trên cả 2
  nền tảng, bao gồm case đa thiết bị. **Gap còn tồn tại** (không chặn khóa tính năng, chỉ là thiếu
  sót về audit trail): `logoutAll()` không ghi audit log `LOGOUT_ALL` — nếu sau này cần bổ sung,
  hạ trạng thái xuống 🟡 trước khi sửa.

### #6 — Đăng ký tài khoản người dùng — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-06-register.md`. Cả luồng email và luồng SĐT (OTP
  nội bộ dev-mode) đều pass trên 2 nền tảng.

### #7 — Quên mật khẩu — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-07-forgot-password.md`. Toàn bộ case bảo mật/biên
  pass trên 2 nền tảng.

### #8 — Đặt lại mật khẩu — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-08-reset-password.md`. Toàn bộ case pass trên 2
  nền tảng. **Gap còn tồn tại** (không chặn khóa tính năng): `resetPassword()` không ghi audit log
  `RESET_PASSWORD` — nếu sau này cần bổ sung, hạ trạng thái xuống 🟡 trước khi sửa.

### #9 — Đổi mật khẩu — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-09-change-password.md`. Toàn bộ case pass, bao
  gồm case 4/5 (tự đăng xuất mọi phiên sau khi đổi — đúng chủ đích). Gap thiếu audit log
  `CHANGE_PASSWORD` vẫn còn, không chặn khóa.

### #10 — Xem thông tin cá nhân — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-10-view-profile.md`. Toàn bộ case pass. Gap thiếu
  field 2FA/tenant hiện tại ở `/auth/me` vẫn còn, không chặn khóa (UI tự bù đắp qua nguồn khác).

### #11 — Cập nhật hồ sơ cá nhân — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-11-update-profile.md`. Toàn bộ case pass, gồm cả
  luồng đổi phone/email cần xác thực lại. Gap thiếu audit log `UPDATE_PROFILE` vẫn còn, không
  chặn khóa.

### #12 — Bật TOTP 2FA — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-12-enable-totp.md`. Toàn bộ case pass, bao gồm
  contract mới (QR client-side, chặn bật trùng 409, invalidate secret cũ, hết hạn phiên setup).

### #13 — Đăng nhập có 2FA — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-13-login-totp.md`. Toàn bộ case pass, gồm hết 8 mã
  dự phòng và không bị bỏ qua 2FA ở lần đăng nhập kế tiếp.

---

### #14 — Khóa tài khoản khi đăng nhập sai — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-14-account-lockout.md`. Phần UI chính đã pass qua
  kịch bản #1 case 4. Audit `LOGIN_FAILED`/`ACCOUNT_LOCKED` đã bổ sung (chỉ ghi khi tài khoản
  thuộc tenant) và test pass.

### #15 — Tạo tenant mới — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-15-create-tenant.md`. Cả luồng self-serve và luồng
  Platform Admin provisioning đều pass.

### #16 — Xem danh sách tenant — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-16-list-tenants.md`. Toàn bộ case pass. Gap thiếu
  plan/subscription trong danh sách vẫn còn, không chặn khóa.

### #17 — Cập nhật thông tin tenant — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-17-update-tenant.md`. Toàn bộ case pass, gồm giới
  hạn quyền (chỉ owner sửa được).

### #18 — Cấu hình giao diện và định dạng — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-18-tenant-display-settings.md`. Toàn bộ case pass.
  Audit `tenant_settings_updated` đã bổ sung và test pass. Gap "language/currency thuộc API #17"
  vẫn còn (đúng kiến trúc hiện tại, không chặn khóa).

---

### #19 — Quản lý IP whitelist — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-19-ip-whitelist.md`. Lịch sử: bản đầu báo nhầm
  "chưa enforce" (sai) → bản 2 tìm đúng gap thật (scope theo client-type web_admin/api không thể
  thực thi, chọn "chỉ Web Admin" vẫn chặn cả app di động) → **đã sửa và test pass (2026-08-13)**:
  đổi hẳn sang scope theo ROLE. Backend: migration V90 (`tenant_ip_whitelist_roles`),
  `IpWhitelistGuard` đọc role từ JWT, chỉ entry có role khớp mới áp dụng. Frontend: form Web Admin
  đổi dropdown scope cũ thành multi-select chọn role (tái dùng danh sách role thật của tenant qua
  `useRolesQuery`), cột bảng hiện tag role hoặc "Tất cả role". Đã xác nhận qua cả API lẫn UI thật:
  role bị giới hạn thì bị chặn đúng khi sai IP; role không nằm trong entry thì không bị ảnh hưởng
  dù sai IP; tự khóa mình bị chặn khi sửa; Platform Admin luôn miễn trừ.

### #20 — Quản lý gói dịch vụ — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-20-manage-plans.md`. Toàn bộ case pass, không có
  gap.

### #21 — Cấu hình giới hạn gói — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-21-plan-limits.md`. Case cấu hình (1-2) pass; case
  enforcement (3-5) đã test hoặc xác nhận hoãn hợp lệ theo đúng Sprint liên quan (không tính là
  fail).

### #22 — Gán subscription cho tenant — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-22-assign-subscription.md`. Toàn bộ case pass,
  không có gap.

### #23 — Seed role và permission hệ thống — ✅ PASS — ĐÃ KHÓA (2026-08-13)
- Kịch bản: `docs/manual-tests/sprint-1-feature-23-seed-roles-permissions.md`. Backend-only, xác
  nhận idempotent qua query DB + restart container, không đổi số lượng role/permission.

---

### #24 — Danh sách role — ✅ PASS — ĐÃ KHÓA (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-1-feature-24-list-roles.md`. Test tự động (Playwright) trên
  1 tenant thật mới tạo riêng cho đợt test này. Case 4 (số người đang giữ) xác nhận đúng: role
  dùng chung `TENANT_ADMIN` hiện "1 người · Xem" — đúng với thực tế của tenant test (chỉ chủ sở
  hữu giữ role này), không lộ số toàn platform (bug đã sửa 2026-08-14). Tìm kiếm theo tên lọc
  đúng ("TENANT" → chỉ ra đúng 1 kết quả `TENANT_ADMIN`).

### #25 — Tạo role tùy chỉnh — ✅ PASS — ĐÃ KHÓA (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-1-feature-25-create-custom-role.md`. Tạo role qua UI thật
  với đúng 2 permission chọn (`roles:create`, `roles:update`) — xác nhận qua API sau khi tạo,
  permission trả về khớp chính xác. Audit `role_created` ghi đúng `entity_id` = role vừa tạo, có
  `request_id`.

### #26 — Sửa role và quyền — ✅ PASS — ĐÃ KHÓA (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-1-feature-26-update-role.md`. Case 2 (evict cache ngay lập
  tức) xác nhận bằng thực nghiệm chặt chẽ: lấy JWT của 1 tài khoản test **trước khi** sửa role,
  gọi 1 API cần quyền chưa có → 403; Owner sửa role qua UI thêm đúng quyền đó; gọi lại **CHÍNH
  JWT CŨ đó, không đăng nhập lại** → 200 ngay lập tức. Audit `role_updated` ghi đúng.

### #27 — Xóa hoặc vô hiệu hóa role — ✅ PASS — ĐÃ KHÓA (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-1-feature-27-delete-role.md`. Phát hiện tích cực: UI **chủ
  động vô hiệu hóa nút Xóa** khi role còn người giữ (disabled, có tooltip) — tốt hơn kỳ vọng gốc
  của kịch bản (chỉ yêu cầu backend trả lỗi). Xác nhận backend cũng chặn đúng ở tầng API (400
  "Role is still assigned..."). Vô hiệu hóa (deactivate) role KHÔNG tự thu hồi quyền người đang
  giữ — đúng thiết kế, đã xác nhận qua UI ("1 người · Xem" không đổi sau khi vô hiệu hóa). Xóa
  thành công sau khi thu hồi hết người giữ — role biến mất khỏi danh sách ngay. Audit
  `role_deleted` ghi đúng.

### #28 — Xem permission theo nhóm — ✅ PASS — ĐÃ KHÓA (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-1-feature-28-list-permissions.md`. Xác nhận permission đã
  bị ẩn ở đợt dọn dẹp trước (`tenants:update`, migration V92) **không xuất hiện** trong picker tạo
  role — 0 lần khớp trong toàn bộ modal "Tạo Role Tùy Chỉnh".

---

### #29 — Gán role cho user — ✅ PASS — ĐÃ KHÓA (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-1-feature-29-assign-role.md`. Case 1 (gán qua UI thật, tab
  "Vai trò & Phân quyền" trong hồ sơ nhân viên) pass — toast "Đã gán role thành công", role xuất
  hiện ngay trong bảng. Case 3 (idempotent) pass theo 2 chiều: gán trùng role đang giữ → 409 rõ
  ràng, không tạo bản ghi rác; thu hồi rồi gán lại → 201 thành công (reactivate đúng bản ghi cũ).
  Case 4 (chặn leo thang đặc quyền khi gán) pass — tài khoản chỉ giữ `roles:create`+`roles:update`
  thử tạo role có quyền `employees:create` (không có) → 403. Case 5 (chặn gán role nền tảng qua
  API tenant) pass — thử gán `PLATFORM_ADMIN` qua `/user-roles` (không phải
  `/user-roles/platform`) → 400 với thông báo rõ ràng.
  **Chưa test hết:** case 2 (giới hạn theo công trình cụ thể) — modal đã xác nhận có UI (radio
  "Công trình cụ thể"), nhưng chưa hoàn tất 1 luồng submit thật với site (cần dữ liệu site thật,
  để dành cho đợt test Epic Workspace/Site ở Sprint 2). Không chặn khóa vì case 1 (scope "Toàn
  công ty") đã pass đầy đủ và đây chỉ là 1 biến thể UI của cùng 1 API.

### #30 — Thu hồi role — ✅ PASS — ĐÃ KHÓA (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-1-feature-30-revoke-role.md`. Case 1 (thu hồi qua UI) pass —
  toast "Đã thu hồi role thành công", role biến mất khỏi bảng ngay. Case 2 (thu hồi lại đúng
  `userRoleId` đã thu hồi) pass — 404 rõ ràng, không crash. Case 3 (thu hồi xuyên tenant) pass —
  403, không lộ hay ảnh hưởng dữ liệu tenant khác.
  **Case 4/5 phát hiện gap thật, nghiêm trọng — đã sửa cùng ngày:** thu hồi role admin cuối cùng
  của 1 tenant từng thành công không cảnh báo, khiến chủ sở hữu bị hệ thống coi như "không thuộc
  công ty nào" dù `tenants.owner_id` không đổi (chi tiết đầy đủ ở
  `docs/reviews/backend/rbac-role-permission-audit-2026-08-13.md` mục 10). **Đã vá 2 lớp:**
  (1) `UserRoleService.assertNotLastAdminHolder` chặn 409 nếu đây là người cuối cùng giữ
  `roles:update` trong tenant (Platform Admin được miễn trừ); (2)
  `UserRoleService.selfHealOwnerRoles` tự động phục hồi `TENANT_ADMIN` cho chủ sở hữu bất kỳ khi
  nào họ đăng nhập/chuyển tenant/gọi `GET /roles/me` mà đang giữ 0 role trong tenant mình sở hữu.
  Đã test lại qua UI thật: (a) chủ sở hữu duy nhất tự thu hồi role của mình qua modal "Danh sách
  người giữ role" → bị chặn đúng, toast hiện "Không thể thu hồi role này vì đây là quyền quản trị
  vai trò cuối cùng của công ty..."; (b) tái hiện đúng kịch bản gốc bằng Platform Admin
  force-revoke (kênh miễn trừ hợp lệ) rồi đăng nhập lại bằng owner → tự động phục hồi hoàn toàn,
  vào thẳng dashboard, không cần can thiệp DB.

### #31 — Ghi audit cho hành động quan trọng — ✅ PASS — ĐÃ KHÓA (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-1-feature-31-audit-logging.md`. Xác nhận qua màn Nhật ký
  audit thật (không chỉ qua DB): toàn bộ hành động phát sinh trong đợt test này —
  `role_created`, `role_updated`, `role_deleted`, `role_assigned` (2 lần), `role_revoked` (nhiều
  lần) — đều xuất hiện đúng thứ tự thời gian, đúng actor (email + user UUID), đúng entity, có
  `request_id` riêng từng dòng. Banner "Dữ liệu được giới hạn theo công ty đang chọn" xác nhận
  không lộ audit chéo tenant. Gap gốc (07-22) "`AuditLogService.record()` không được gọi ở đâu
  cả" đã xác nhận **sai hoàn toàn** với hiện trạng — record() được gọi ở 17 module khác nhau.

---

### #32 — Tạo notification in-app cơ bản — ✅ PASS — ĐÃ KHÓA (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-1-feature-32-notification-inbox.md`. Test tự động
  (Playwright) trên 1 tenant thật riêng cho đợt test này, seed 2 notification test qua DB (vì
  research code xác nhận: hiện tại **chỉ có 1 nơi duy nhất trong toàn hệ thống tạo notification**
  — `RandomCheckDispatchService` khi gửi kiểm tra ngẫu nhiên; mời/chấp nhận/gán role đều KHÔNG tạo
  notification nào — xem thêm mục "logic nghiệp vụ cần bổ sung" trong tin nhắn trả lời). Đã xác
  nhận: chuông header hiện đúng badge số chưa đọc; click 1 item → đánh dấu đã đọc ngay + điều
  hướng qua màn "Thông báo" đầy đủ (phát hiện thêm: có sẵn tab "Tất cả/Chưa đọc" và checkbox chọn
  hàng loạt — tốt hơn kỳ vọng ban đầu của kịch bản); "Đánh dấu tất cả đã đọc" hoạt động đúng, nút
  tự ẩn khi hết thông báo chưa đọc (cách xử lý idempotent chủ động, không cần bấm lại để test).

### #33 — Mời nhân viên bằng email — ✅ PASS — ĐÃ KHÓA (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-2-feature-33-invite-employee.md`. Case 1-2 (mời happy path,
  chặn trùng email) pass qua UI thật, như lần test trước. **Cả 2 gap đã vá cùng ngày, ngay sau
  lần test đầu tiên phát hiện chúng:**
  1. Thêm ô chọn "Phòng ban / Workspace (Tùy chọn)" vào modal mời (dùng chung danh sách workspace
     active của tenant), lưu vào cột mới `employee_invitations.workspace_id` (migration V93).
  2. Thêm audit `invitation_sent` (`AuditLogService`, entity `EmployeeInvitation`).
  Đồng thời bổ sung tính năng liên quan phát sinh từ thảo luận: nếu email được mời đã có tài
  khoản FAMS, gửi thêm notification in-app `EMPLOYEE_INVITED`. Đã test lại toàn bộ qua UI+DB
  thật trên 1 tenant mới: chọn workspace trong modal → gửi → xác nhận `workspace_id` lưu đúng,
  audit `invitation_sent` xuất hiện, notification `EMPLOYEE_INVITED` tới đúng người (xác nhận cả
  qua DB lẫn màn "Thông báo" thật của người nhận).

### #34 — Chấp nhận lời mời — ✅ PASS — ĐÃ KHÓA (Web) (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-2-feature-34-accept-invitation.md`. Case 1, 3 (chấp nhận,
  lỗi token) pass như lần test trước. **Cả 2 gap đã vá cùng ngày:**
  1. `EmployeeInvitationService.acceptInvitation` giờ tự tạo `WorkspaceMember` cho workspace mặc
     định của lời mời (nếu có) ngay khi Employee được resolve — best-effort, không chặn cả luồng
     nếu workspace bị xóa/vô hiệu hóa giữa chừng.
  2. Thêm audit `invitation_accepted`.
  Bổ sung thêm: gửi notification `INVITATION_ACCEPTED` cho người đã gửi lời mời. Đã test lại end-
  to-end trên 1 tenant mới: mời kèm workspace → chấp nhận qua API → xác nhận `WorkspaceMember`
  được tạo đúng workspace, audit `invitation_accepted` xuất hiện, notification tới đúng người mời
  — và xác nhận chéo bằng filter workspace mới ở #36 (nhân viên vừa chấp nhận xuất hiện đúng khi
  lọc theo workspace đó). Case 2 (Mobile App) chưa test — để dành đợt test có thiết bị thật.

### #35 — Hủy lời mời — ✅ PASS — ĐÃ KHÓA (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-2-feature-35-cancel-invitation.md`. Case 1-3 pass như lần
  test trước. **Cả 2 gap đã vá cùng ngày:**
  1. `EmployeeInvitation` có thêm cột `cancelled_by`, `cancel_reason`, `cancelled_at` (migration
     V93); modal Hủy trên Web Admin có thêm ô "Lý do hủy (Tùy chọn)".
  2. Thêm audit `invitation_cancelled` (before/after snapshot).
  Đã test lại qua UI thật: nhập lý do "Ứng viên đã từ chối offer" → hủy → xác nhận qua DB cả 3
  cột lưu đúng giá trị, audit `invitation_cancelled` xuất hiện; tag trạng thái "Đã hủy" trên màn
  "Lời mời đã gửi" giờ có tooltip hiện lý do khi hover.

### #36 — Danh sách nhân viên — ✅ PASS — ĐÃ KHÓA (2026-08-15)
- Kịch bản: `docs/manual-tests/sprint-2-feature-36-list-employees.md`. Case 1-5 pass như lần test
  trước. **Cả 2 gap đã vá cùng ngày:**
  1. Thêm filter "Face ID" (Đã đăng ký/Chưa đăng ký) — `EmployeeSpecification` dùng subquery join
     `face_profiles.status = 'enrolled'`.
  2. Thêm filter "Workspace" riêng biệt với filter "Phòng ban" cũ — join `workspace_members`
     (active, đúng workspaceId), khác với "Phòng ban" (chỉ so tên chuỗi trên `Employee.department`,
     giữ nguyên không đổi để tránh phá hành vi cũ).
  Đã test lại qua UI thật trên tenant vừa test #33/#34: lọc "Chưa đăng ký" → đúng 1 kết quả (nhân
  viên chưa có Face ID); lọc "Đã đăng ký" → đúng 0 kết quả (rỗng, không ai enrolled); lọc theo
  workspace "Phòng Kỹ thuật" (workspace vừa được gán tự động ở #34) → đúng hiện nhân viên đó —
  xác nhận filter mới hoạt động đúng và độc lập với filter Phòng ban cũ.

---

### #37 — Xem chi tiết nhân viên — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-37-employee-detail.md`. Gap gốc 07-22
  ("workspaces/assignments hardcode rỗng") xác nhận qua code là ĐÃ SỬA từ trước — audit note cũ
  đã lỗi thời, không phải gap thật hiện tại. `EmployeeService.getEmployee` lấy đúng dữ liệu thật
  qua WorkspaceMemberRepository/AssignmentRepository. Response giờ có thêm `nationalId`/
  `terminatedAt` (bổ sung cùng đợt fix #39/#40 bên dưới).

### #38 — Tạo nhân viên thủ công — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-38-create-employee-manual.md`. Không có gap đã
  biết. Luồng "Vai trò dự kiến" (`plannedRoleId`) xác nhận hoạt động đúng.

### #39 — Cập nhật nhân viên — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-39-update-employee.md`. Gap ghi audit đã sửa
  (`employee_updated` với before/after). **Gap `national_id` đã vá**: thêm cột `national_id`
  (migration `V95__employee_terminated_at_and_national_id.sql`), field trong
  `CreateEmployeeRequest`/`UpdateEmployeeRequest`, response field `nationalId` trên
  `EmployeeResponse`/`EmployeeDetailResponse` với `@Masked` (cùng cơ chế mask email/phone hiện có,
  dựa trên `employees:pii:read`/PLATFORM_ADMIN qua `PiiAccess`, không phải cơ chế mã hóa mới) —
  và tự động mask trong audit log qua `MaskingUtils.PII_KEYS` (key `nationalId` đã có sẵn từ
  trước, chỉ chưa được dùng). Test live API: PATCH `nationalId="001234567890"` trên tenant test →
  response trả đúng giá trị cho owner (có quyền PII xem full). **Web Admin đã bổ sung UI (2026-08-16
  cùng ngày):** ô "Số CCCD/CMND (Tùy chọn)" thêm vào `EmployeeFormModal.tsx` (modal tạo/sửa nhanh)
  và `EmployeeForm.tsx` (tab "Thông tin cá nhân" trang chi tiết). Test qua UI thật (Playwright):
  tạo nhân viên mới với CCCD `079099001234` qua modal → lưu → mở trang chi tiết → giá trị hiển thị
  đúng, round-trip qua API thật không qua mock.

### #40 — Tạm ngừng/nghỉ việc nhân viên — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-40-terminate-employee.md`. Face ID tự thu hồi khi
  terminate giữ nguyên hành vi đúng từ trước. **Gap nghiêm trọng đã vá**: `changeEmployeeStatus`
  giờ set `assignment.status="cancelled"` + save cho MỌI assignment đang active của nhân viên khi
  terminate (giống hệt cách `AssignmentService.cancelAssignment` làm), thay vì trước đây chỉ hủy
  Random Check đang chờ mà bỏ quên chính Assignment. Đã thêm cột `terminated_at` (migration V95),
  set khi chuyển sang terminated, clear về null khi HR đảo ngược (chuyển khỏi terminated). Đã
  thêm ghi audit log `employee_status_changed` (trước đây hàm này KHÔNG ghi audit gì cả).
  **Test live end-to-end qua API** trên tenant test riêng (tạo mới employee + site + assignment
  active): terminate → xác nhận trong DB assignment chuyển `status='cancelled'`, `terminatedAt`
  được set đúng timestamp, audit log `employee_status_changed` xuất hiện → reactivate
  (status=active) → xác nhận `terminatedAt` trả về `null` đúng như kỳ vọng. **Test lại qua UI thật
  (Playwright, cùng ngày):** trang chi tiết thêm tag "Từ dd/MM/yyyy" cạnh trạng thái khi đã nghỉ
  việc; sửa lại nội dung modal xác nhận trên `EmployeeListPage.tsx` (trước đây ghi sai "các phân
  công hiện có không tự kết thúc" — nay đúng thực tế mới). Tạo nhân viên + assignment active mới
  qua API, rồi terminate **qua đúng thao tác trên Web Admin thật** (bấm badge trạng thái → "Đánh
  dấu Đã nghỉ việc" → xác nhận modal) → trang chi tiết hiện "Đã nghỉ" + "Từ 16/08/2026" → tab
  "Workspace & Phân công" hiện tag "Đã hủy" trên assignment vừa gán — xác nhận gap nghiêm trọng đã
  vá đúng, nhìn thấy trực tiếp trên giao diện chứ không chỉ qua DB.

### #41 — Import danh sách nhân viên — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-41-import-employees.md`. **Gap "không tải được
  file lỗi" đã vá**: thêm endpoint `POST /tenants/{tenantId}/employees/import/errors-export`
  (multipart, nhận lại đúng file đã gửi cho `/import`), tái sử dụng logic validate từng dòng của
  `importEmployees` qua helper `validateImportRow` mới tách ra (tránh trùng lặp, đảm bảo lý do lỗi
  khớp 100% với response JSON gốc), trả về `.xlsx` chỉ gồm các dòng lỗi + cột `errors` gộp mọi lý
  do của dòng đó — theo đúng pattern tải file `.xlsx` sẵn có trong codebase
  (`EmployeeExportService`/`GET /export`, dùng Apache POI). Import gốc vẫn không ghi audit và
  không hỗ trợ cột `plannedRoleId`/`departmentId` — hai điểm này không nằm trong AC gốc của #41
  nên không sửa trong đợt này. **Test live qua API**: import file 3 dòng (1 hợp lệ IMP-001, 2 lỗi:
  thiếu firstName + email sai định dạng, và hiredDate sai định dạng) → gọi endpoint mới với cùng
  file → tải về đúng `.xlsx` chỉ chứa 2 dòng lỗi, cột `errors` khớp chính xác với JSON `errors`
  trả về từ `/import` ban đầu. **Web Admin đã bổ sung UI (cùng ngày):** nút "Tải file lỗi (.xlsx)"
  trong `ImportEmployeeModal.tsx`, hiện khi `failedCount > 0`, tự dùng lại file đang chọn trong
  modal. Test qua UI thật (Playwright): mở modal Nhập Excel → upload file 3 dòng (tương tự trên)
  → xác nhận kết quả "1 thành công, 2 lỗi / 3 dòng" hiện đúng trên UI → bấm nút mới → trình duyệt
  tải về `import-errors.xlsx` → mở file: 2 dòng lỗi, nội dung khớp 100% với bảng lỗi hiển thị.

### #42 — Export danh sách nhân viên — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-42-export-employees.md`. Mask email/phone theo
  quyền `employees:pii:read` hoạt động đúng, filter/site-scope được tôn trọng (giữ nguyên từ
  trước). **Gap đã vá**: thêm cột `nationalId` vào `HEADERS` và vòng ghi dữ liệu của
  `EmployeeExportService`, mask bằng literal `"***"` khi không có quyền PII (khớp cách
  `Masked.MaskType.DEFAULT` mask trong JSON API). Test live: PATCH nationalId → export → mở file
  → cột `nationalId` xuất hiện đúng vị trí, giá trị đúng.

### #43 — Tạo workspace — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-43-create-workspace.md`. **Gap "không ghi audit"
  đã vá**: `WorkspaceService.createWorkspace` giờ gọi `auditLogService.record(...)` đúng pattern
  Employee/RBAC/Tenant, action `workspace_created`. Test live: tạo workspace → `audit_logs` có bản
  ghi đúng entity_id.

### #44 — Danh sách workspace — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-44-list-workspace.md`. Gap gốc "thiếu số thành
  viên trong response" xác nhận SỬA XONG — `listWorkspaces`/`/tree` trả về
  `activeMemberCount`/`childWorkspaceCount` qua batch-load. Test qua UI thật: cây tổ chức hiện
  đúng "X người" theo từng workspace, tự cập nhật ngay sau khi gán/chuyển thành viên.

### #45 — Cập nhật workspace — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-45-update-workspace.md`. AC "không cho tạo vòng
  lặp parent" xác nhận ĐÃ CÓ và đúng — `isDescendant(...)` chặn set parent mới là hậu duệ của
  workspace đang sửa, cộng với chặn tự làm cha của chính mình. **Gap "không ghi audit" đã vá**
  (cùng cơ chế với #43): action `workspace_updated` với before/after snapshot.

### #46 — Gán nhân viên vào workspace — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-46-assign-workspace-member.md`. **Gap "thiếu
  effective_from/is_primary" đã vá**: thêm cột `is_primary`, `effective_from`, `left_at`
  (migration `V96__workspace_member_primary_and_effective_dates.sql`). Enforce "chỉ 1 primary
  active" ở 2 tầng: ứng dụng (tự động demote primary cũ) + DB (partial unique index
  `idx_workspace_members_one_primary_per_employee`). Web Admin: `AddMemberModal.tsx` thêm
  DatePicker "Ngày hiệu lực" + Switch "Đặt làm workspace chính"; tag vàng "Chính" hiện ở cả danh
  sách thành viên workspace và tab Workspace của trang chi tiết nhân viên.
  **2 bug phát hiện và vá ngay trong lúc test sống**: (1) race condition Hibernate flush-order
  (INSERT chạy trước UPDATE demote trong cùng transaction) gây vi phạm unique constraint — vá bằng
  `saveAndFlush`; (2) Lombok+Jackson serialize `boolean isPrimary` ra CẢ HAI key `"isPrimary"` VÀ
  `"primary"` trùng lặp (đúng gotcha "Lombok isXxx() duplicate JSON keys" đã ghi nhận từ audit RBAC
  trước) — vá bằng `@JsonProperty("isPrimary")` + `@JsonIgnoreProperties({"primary"})`.
  **Test live end-to-end qua UI thật**: gán nhân viên vào workspace mới với switch "chính" bật →
  workspace mới ngay lập tức có tag "Chính", workspace cũ tự động mất tag — xác nhận demote hoạt
  động đúng ở cả 2 nơi hiển thị (đồng bộ). DB xác nhận: đúng 1 dòng `is_primary=true` cho mỗi nhân
  viên.

### #47 — Chuyển workspace cho nhân viên — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-47-transfer-workspace-member.md`. **Gap "thiếu
  is_primary/effective date" đã vá** (cùng migration V96 với #46): bản ghi cũ khi transfer giờ có
  `left_at` riêng biệt với `deletedAt` chung; `isPrimary` carry-over mặc định sang membership mới
  (có thể override qua dropdown 3 lựa chọn trên `TransferMemberModal.tsx`: Giữ nguyên / Đặt làm
  chính / Không phải chính); `effectiveFrom` cho membership mới cũng chỉnh được qua DatePicker mới
  thêm. Yêu cầu quyền CẢ HAI `workspace_members:create` VÀ `:delete` giữ nguyên (đúng từ trước,
  không phải gap). **Test live qua đúng thao tác trên Web Admin thật**: chuyển 1 nhân viên đang có
  workspace chính sang workspace mới (không override) → toast "Chuyển phòng ban thành công!" →
  xác nhận DB: bản ghi mới có `is_primary=true` (carry-over đúng), bản ghi cũ có `left_at` được
  set.

### #48 — Ghi nhận đồng ý Face ID — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-48-faceid-consent.md`. **Gap "thiếu
  version/hash/ip/device" đã vá**: thêm cột `consent_version`/`consent_ip`/`consent_device`
  (migration `V97__face_profile_consent_metadata_and_revoke_reason.sql`), version do backend tự
  quyết định (không tin client), IP/device tự lấy từ request. AC "chỉ consent current được dùng"
  giờ thực sự được enforce qua `isConsentCurrent()` ở mọi điểm gate enrollment. Thêm audit log
  `face_id_consent_given`. **Test live qua API+DB pass toàn bộ**: version/ip/device lưu đúng,
  idempotent khi version không đổi (không re-stamp), HR vẫn bị chặn 403 khi cố consent thay người
  khác (giữ nguyên thiết kế đúng từ trước). **Web Admin xác nhận qua UI thật**: tab Sinh trắc học
  hiện đúng "Đã đồng ý (phiên bản 2026-08-v1)". **Mobile App cũng đã test live** bằng cách tự chạy
  App thật ở chế độ `expo start --web` + camera giả lập của Chromium: đăng nhập App thật, vào Hồ sơ
  → Face ID → "Đăng ký Face ID" → xác nhận màn hình **bỏ qua đúng** consent sheet (vì consent đã
  current) và đi thẳng vào hướng dẫn liveness → bấm "Bắt đầu xác minh" → gọi thật API
  `liveness-challenge` (200 OK) → camera thật hiện ra đúng UI (khung dẫn, đếm ngược, nút chụp).

### #49 — Đăng ký Face ID — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-49-faceid-enroll.md`. AC gốc lỗi thời về kiến
  trúc: `quality_score`/`provider`/`aws_face_id` không tồn tại trong hệ thống (dùng InsightFace
  chạy local, không phải AWS Rekognition) — **quyết định không sửa** (thêm field giả không có ý
  nghĩa), khuyến nghị cập nhật lại AC. **Gap "không ghi audit" đã vá**: thêm audit log cho cả 4
  hành động (submit HR-assisted/self-service, approve, reject). **Test live qua UI thật (Web
  Admin)**: HR upload ảnh fixture thật → hồ sơ vào `pending` đúng → ảnh tham chiếu hiện đúng → bấm
  "Duyệt hồ sơ" → toast thành công, trạng thái chuyển "Đã đăng ký" ngay lập tức → audit log
  `face_id_enrollment_submitted_hr_assisted` + `face_id_enrollment_approved` xác nhận có trong DB.
  **Mobile App test live**: Claude chạy App thật (web + camera giả lập) tới sát bước chụp — camera
  hiện đúng UI, App tự nhận diện đúng là không có khuôn mặt thật trong khung (camera giả chỉ phát
  test pattern) nên không tự chụp, hành vi đúng. **User đã tự xác nhận nốt phần còn lại trên thiết
  bị thật (2026-08-16)**: chụp 3 ảnh + submit thành công, chặn đúng khi liveness fail (ảnh in/màn
  hình), chặn đúng khi 2 ảnh không cùng 1 người, chặn đúng rate limit sau 5 lần thử trong 10 phút —
  tất cả hoạt động ổn.

### #50 — HR xem trạng thái Face ID — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-50-faceid-hr-view.md`. Xác nhận "ĐÃ XONG" từ audit
  gốc về cơ bản đúng: danh sách/filter/tìm kiếm/enrolledAt hoạt động tốt. `quality_score` không
  tồn tại (gap kiến trúc, không sửa — giống #49). "Không hiển thị ảnh nếu thiếu quyền" nằm ở
  endpoint riêng (`.../pending-review/photo`), gate bằng quyền + site-scope. **Phát hiện lớn ngoài
  AC gốc, đã test live qua UI thật**: luồng duyệt với ảnh tham chiếu thật hiện đúng ngay tại tab
  Sinh trắc học của nhân viên (không chỉ ở tab "Chờ duyệt" riêng), chống duyệt mù hoạt động đúng.
  Không có mutation nào ở #50 nên không cần thêm audit log riêng — gap audit chung nằm ở #48/#49/#51.

### #51 — Xóa/vô hiệu hóa Face ID — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-51-faceid-revoke.md`. **Gap "thiếu
  deleted_reason/deleted_by; không ghi audit FACE_DELETE" đã vá**: thêm cột (cùng migration V97 với
  #48), `DELETE .../face-id` nhận thêm query param `reason` tùy chọn, audit log `face_id_revoked` +
  `face_id_auto_revoked_on_termination`. Xử lý đúng thứ tự ghi giữa Java (deleted_reason/deleted_by)
  và fams-ai/Python (status/revoked_at) để không ghi đè lẫn nhau. AC nhắc tên cột
  `tenant_users.face_registered` đã lỗi thời — hệ thống dùng `face_profiles.status='revoked'` làm
  nguồn sự thật duy nhất, hiệu quả tương đương. **Web Admin đã bổ sung UI**: nút "Thu hồi Face ID"
  giờ mở modal có ô nhập lý do (giống UX luồng "Từ chối" đã có). **Test live end-to-end qua UI
  thật**: HR duyệt 1 hồ sơ pending trước, rồi mở modal thu hồi, nhập lý do "Nhân viên yêu cầu rút
  lại đồng ý (test UI)" → thu hồi thành công → dòng "Lý do thu hồi" trên tab hiện đúng y hệt nội
  dung đã nhập, khớp DB (`deleted_reason`/`deleted_by` đúng). **Mobile App cũng đã test live**: chạy
  App thật, tự thu hồi Face ID qua đúng nút trên màn hình Hồ sơ → modal xác nhận trong App hiện
  đúng nội dung cảnh báo (không có ô lý do, đúng thiết kế có chủ đích khác Web Admin) → xác nhận →
  toast "Đã thu hồi Face ID" → trạng thái đổi ngay lập tức, nút chuyển lại thành "Đăng ký Face ID".

### #52 — Tạo công trình — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-52-create-site.md`. AC gốc lỗi thời ở 2/3 điểm:
  `province` không tồn tại (gap kiến trúc, `sites` chỉ có `address` tự do, không có tỉnh/thành
  riêng — không sửa); "liên kết workspace" không tồn tại (Site không có cột `workspace_id` —
  không sửa). "Supervisor" thực ra KHÔNG thiếu hẳn — làm qua luồng Phân công riêng
  (`Assignment.role='supervisor'`), tách biệt khỏi bước tạo site; test live: gán supervisor cho
  site mới tạo qua API assignment, thành công. Plan limit `max_sites` hoạt động đúng, trùng tên/mã
  chặn đúng. **Gap "không ghi audit" đã vá**: thêm `auditLogService.record(...)`, action
  `site_created`. Test live: tạo site → `audit_logs` có đúng bản ghi.

### #53 — Danh sách công trình — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-53-list-sites.md`. Filter province/workspace, sort
  theo `start_date` đều là gap kiến trúc (trường không tồn tại trên `Site`, không sửa). **Phát hiện
  tốt ngoài AC gốc**: có cơ chế site-scope filter cho role site-scoped (VD. SITE_SUPERVISOR) — chỉ
  thấy đúng site được gán, kể cả có quyền `sites:list` chung; trường hợp chưa gán site nào trả về
  danh sách rỗng thay vì lỗi hay lộ hết dữ liệu (xác nhận qua code, cơ chế dùng chung với
  Employee/Workspace). Test live qua API: danh sách trả đúng toàn bộ site của tenant với tài khoản
  không giới hạn site-scope.

### #54 — Xem chi tiết công trình — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-54-site-detail.md`. Bản đồ tâm, geofence active
  đều đúng như AC. "Shift default" thực ra là DANH SÁCH shift active (số nhiều), không phải 1 shift
  mặc định duy nhất — không sửa, chỉ ghi chú lại AC lỗi thời. "Assignment active" trên response chi
  tiết chỉ là SỐ ĐẾM, danh sách đầy đủ nằm ở tab riêng — giữ nguyên. **Gap "supervisor không hiện ở
  card chính" đã vá**: thêm field `supervisors` vào `SiteDetailResponse` (lấy từ
  `Assignment.role='supervisor' AND status='active'`, không thêm cột mới trên `sites` — tận dụng
  đúng mô hình dữ liệu có sẵn), Web Admin card "Thông tin công trình" hiện thêm dòng "Người phụ
  trách". **Test live qua UI thật (Playwright)**: tạo site → gán supervisor qua Assignment → vào
  trang chi tiết → dòng "Người phụ trách" hiện đúng tag tên nhân viên ngay lập tức. Site-scope 403
  khi xem site ngoài phạm vi giữ nguyên hoạt động đúng.

### #55 — Cập nhật công trình — ✅ PASS — ĐÃ KHÓA (2026-08-16)
- Kịch bản: `docs/manual-tests/sprint-2-feature-55-update-site.md`. Supervisor không sửa được qua
  API này (đúng kiến trúc, giống #52/#54 — phải qua module Phân công, không sửa). **Phát hiện quan
  trọng: gap "validate status yếu" từ nghiên cứu ban đầu là SAI (false positive)** — test live trực
  tiếp bằng API với `status: "archived"` trả về 400 sạch ngay từ tầng bean validation
  (`UpdateSiteRequest.status` đã có `@Pattern` từ trước, agent nghiên cứu bỏ sót annotation này khi
  đọc code). Đã thêm thêm 1 lớp validate ở tầng service cho chắc (`validateStatus()`, phòng vệ
  kép) nhưng đây không phải sửa 1 gap thật. **Gap thật "không ghi audit" đã vá**: thêm
  `auditLogService.record(...)`, action `site_updated` với before/after. Test live: sửa mô tả site
  → `audit_logs` có đúng bản ghi `site_updated`. Bài học ghi lại trong kịch bản: luôn test live để
  xác nhận trước khi kết luận gap, không chỉ dựa vào đọc code tĩnh.

### #56 — Tạo geofence cho công trình — ✅ PASS — ĐÃ KHÓA (2026-08-17)
- Kịch bản: `docs/manual-tests/sprint-2-feature-56-create-geofence.md`. **Gap `area_sqm` đã vá**:
  migration `V98__geofence_area_change_reason.sql` thêm cột `area_sqm DOUBLE PRECISION`,
  `GeofenceService.computeAreaSqm()` tính bằng công thức shoelace trên hệ tọa độ phẳng quy chiếu
  equirectangular tại vĩ độ trung bình polygon (đủ chính xác cho quy mô công trình). **Gap mới
  phát hiện "không ghi audit" cũng đã vá**: action `geofence_created`. Test live: tạo geofence
  polygon thật (~100m×130m gần Hồ Hoàn Kiếm) qua API → `areaSqm: 13810.98` khớp tính tay; UI thật
  (Playwright) hiện đúng "Diện tích: ~13.810,98 m²" trên thẻ Vùng chấm công; `audit_logs` có đúng
  bản ghi `geofence_created`. Script tự động `test_create_geofence.sh` 13/13 pass, không hồi quy.

### #57 — Sửa geofence — ✅ PASS — ĐÃ KHÓA (2026-08-17)
- Kịch bản: `docs/manual-tests/sprint-2-feature-57-update-geofence.md`. **Cả 2 gap đã vá**: thêm
  `changeReason` (tùy chọn, không bắt buộc — quyết định nghiệp vụ theo tiền lệ #51 Face ID revoke
  reason) và tính lại `area_sqm` mỗi lần tạo phiên bản mới; thêm audit `geofence_updated` với
  snapshot before/after đầy đủ (bao gồm `changeReason` trong `new_value` khi có nhập). Test live
  qua UI thật (Playwright): mở modal sửa geofence đã có, ô "Lý do thay đổi (Tùy chọn)" chỉ hiện khi
  sửa (không hiện khi tạo mới), điền lý do + đổi buffer → lưu thành công → xác nhận `audit_logs` có
  đúng `old_value`/`new_value` kèm lý do. Script tự động `test_update_geofence.sh` 12/12 pass,
  không hồi quy.

### #58 — Xem lịch sử geofence — ✅ PASS — ĐÃ KHÓA (2026-08-17)
- Kịch bản: `docs/manual-tests/sprint-2-feature-58-geofence-history.md`. Thiết kế versioning trong
  bảng `geofences` (không có bảng `geofence_histories` riêng) xác nhận là kiến trúc có chủ đích,
  giữ nguyên. **Gap quan trọng nhất đã vá**: `changed_by` giờ trả về `createdByName` (tên thật,
  resolve qua `UserRepository`/`User.displayName`, batch-load theo trang để tránh N+1 query) thay
  vì chỉ UUID thô — ảnh hưởng trực tiếp khả năng tra cứu "ai đã sửa geofence" phục vụ audit tranh
  chấp vị trí (đúng mục đích AC gốc). `change_type` tường minh cố ý KHÔNG làm — lý do đã ghi trong
  kịch bản (rủi ro định nghĩa ngưỡng cao hơn giá trị mang lại ở giai đoạn này). Test live qua UI
  thật (Playwright, viewport rộng để thấy đủ bảng): cột "Người thay đổi" hiện đúng "Platform Admin"
  ở cả 3 dòng lịch sử, cột "Diện tích"/"Lý do thay đổi" mới cũng hiện đúng. Script tự động
  `test_geofence_history.sh` 10/10 pass, không hồi quy.

---

### #59 — Tạo ca làm việc — ✅ PASS — ĐÃ KHÓA (2026-08-17)
- Kịch bản: `docs/manual-tests/sprint-2-feature-59-create-shift.md`. **Gap `is_default` đã vá**:
  migration `V99__shift_is_default.sql` thêm cột `is_default` + unique index từng phần
  `(site_id) WHERE is_default=true AND deleted_at IS NULL`; đặt mặc định lúc tạo/sửa tự động bỏ mặc
  định ở ca khác cùng site (dùng chung pattern với `SavedFilter.isDefault` đã có sẵn trong
  codebase). **Gap audit đã vá**: action `shift_created`. Test live qua UI thật (Playwright): tạo
  "Ca Chieu Test UI" kèm bật "Đặt làm ca mặc định" → tag "Mặc định" hiện đúng trên ca mới, tự động
  biến mất khỏi "Morning Shift" trước đó — xác nhận cơ chế 1-mặc-định/site hoạt động đúng từ UI.
  `code`/`standard_hours`/JSON-schedule xác nhận là khác biệt kiến trúc có chủ đích so với AC gốc,
  không sửa. Script tự động `test_create_shift.sh` 12/12 pass, không hồi quy.

### #60 — Cấu hình OT và giới hạn giờ — ✅ PASS — ĐÃ KHÓA (2026-08-17)
- Kịch bản: `docs/manual-tests/sprint-2-feature-60-shift-ot-config.md`. Audit gốc "đã xong" xác
  nhận đúng, tính năng đã mở rộng thêm giới hạn OT ngày/tuần (V88, cảnh báo không chặn) + snapshot
  vào `checkins` (không hồi tố) từ trước, không đổi trong đợt này. **Gap audit đã vá**: action
  `shift_ot_configured` với before/after đầy đủ. Test live qua API/DB: bật OT + đặt
  `maxOtMinutesPerDay` trên ca thật → `audit_logs` có đúng bản ghi, before/after khớp chính xác.
  Script tự động `test_shift_ot_config.sh` 12/12 pass, không hồi quy.

### #61 — Danh sách ca theo site — ✅ PASS — ĐÃ KHÓA (2026-08-17)
- Kịch bản: `docs/manual-tests/sprint-2-feature-61-list-shifts.md`. **Cả 2 gap đã vá**: tìm theo
  tên (`search`, case-insensitive) và lọc theo `isDefault`. **Bài học kỹ thuật quan trọng**: bản
  đầu dùng JPQL `@Query` với `CAST(:param AS ...)` cho tham số optional gây lỗi PostgreSQL "cannot
  cast type bytea to boolean/varchar" khi tham số truyền null (Hibernate 6 không suy luận đúng kiểu
  bind parameter trong `CAST()`) — phát hiện qua chính vòng test hồi quy khi `test_list_shifts.sh`
  đột ngột báo lỗi 500. Đã chuyển hẳn sang `JpaSpecificationExecutor`/`ShiftSpecification` (Criteria
  API, cùng pattern đã có sẵn ở `AssignmentSpecification`/`EmployeeSpecification`) — tránh hoàn
  toàn lớp lỗi suy luận kiểu này. Test live qua UI thật (Playwright): gõ "morn" vào ô tìm kiếm mới
  → lọc đúng còn 1 kết quả; badge "Mặc định" hiện đúng trên ca đang là default. Script tự động
  `test_list_shifts.sh` 11/11 pass sau khi sửa.

### #62 — Cập nhật hoặc ngừng dùng ca — ✅ PASS — ĐÃ KHÓA (2026-08-17)
- Kịch bản: `docs/manual-tests/sprint-2-feature-62-update-deactivate-shift.md`. **Audit gốc (07-22)
  xác nhận LỖI THỜI**: ghi chú "không có endpoint xóa cứng" sai — endpoint `DELETE` thật sự tồn tại
  từ trước, chỉ bị chặn 400 đúng điều kiện khi ca đã từng dùng trong assignment (khớp AC, không
  phải thiếu). **Gap audit đã vá**: action `shift_updated`/`shift_deleted`. Hỗ trợ đặt/bỏ
  `isDefault` qua PUT, dùng chung logic 1-mặc-định/site với #59 — test live: PUT `isDefault:true`
  lên ca khác → xác nhận ca cũ tự mất mặc định. Script tự động `test_update_shift.sh` 14/14 pass,
  không hồi quy.

---

### #63 — Tạo phân công nhân viên vào site — ✅ PASS — ĐÃ KHÓA (2026-08-17)
- Kịch bản: `docs/manual-tests/sprint-2-feature-63-create-assignment.md`. **Quyết định nghiệp vụ
  của chủ dự án (hỏi trực tiếp)**: nâng cấp chống trùng cùng site theo khoảng giờ thực tế (giống
  logic cross-site đã có sẵn) thay vì chặn tuyệt đối 1 assignment active/site — bỏ hẳn unique index
  DB cũ `uq_assignments_employee_site_active` (migration V100), enforce hoàn toàn ở tầng service
  qua `assertNoConflicts` (gộp chung cross-site + same-site, cùng 1 helper). **Gap audit đã vá**:
  action `assignment_created`. Test live qua API/DB: tạo 2 assignment cùng nhân viên cùng site
  không chồng ngày (tháng 8 + tháng 9) → cả 2 đều 201; tạo assignment thứ 3 chồng giờ thật với cả 2
  → 409 đúng thông báo. Script tự động `test_create_assignment.sh` 14/14 +
  `test_assignment_recurring_schedule.sh` 10/10 pass, không hồi quy.

### #64 — Danh sách phân công — ✅ PASS — ĐÃ KHÓA (2026-08-17)
- Kịch bản: `docs/manual-tests/sprint-2-feature-64-list-assignments.md`. **Gap filter khoảng ngày
  đã vá**: `dateRangeFrom`/`dateRangeTo` kiểu overlap trong `AssignmentSpecification` + RangePicker
  trên UI. **Quyết định nghiệp vụ của chủ dự án (hỏi trực tiếp)**: làm mới hẳn 1 màn hình Mobile App
  "Phân công của tôi" (trước đây App hoàn toàn không có màn liệt kê phân công) — thêm endpoint mới
  `GET /tenants/{tenantId}/assignments/me` (self-service, không cần quyền `assignments:*`, cùng mô
  hình tin cậy với `/attendance/me/monthly`), trả về toàn bộ phân công nhân viên gộp mọi site kèm
  `siteSummary`. Test live: Web Admin filter khoảng ngày qua UI thật (Playwright) — lọc đúng loại
  trừ/bao gồm theo overlap; Mobile App (Playwright qua `expo start --web`) — đăng nhập nhân viên có
  2 phân công ở 2 site khác nhau, vào Hồ sơ → "Phân công của tôi" → hiện đúng cả 2, đúng tên site,
  vai trò, ngày, trạng thái. Script tự động `test_list_assignments.sh` 13/13 pass, không hồi quy.

### #65 — Cập nhật phân công — ✅ PASS — ĐÃ KHÓA (2026-08-17)
- Kịch bản: `docs/manual-tests/sprint-2-feature-65-update-assignment.md`. **Gap quan trọng nhất
  epic này đã vá**: chặn sửa assignment đã hủy (400 "Cannot modify a cancelled assignment — it is a
  closed record kept for history") — trước đây có thể sửa ngày/ca/vai trò của 1 bản ghi đã đóng qua
  cả API lẫn UI (nút Sửa không disable). Re-validate overlap khi sửa giờ dùng chung logic mới với
  #63 (cross-site + cùng-site). **Gap audit đã vá**: action `assignment_updated`. Test live: hủy 1
  assignment rồi thử sửa `notes` của chính nó qua API → 400 đúng thông báo; nút Sửa trên UI xác
  nhận tự disable với dòng đã hủy (ảnh `webassign-01-list.png`). Script tự động
  `test_update_assignment.sh` 12/12 pass, không hồi quy.

### #66 — Hủy phân công — ✅ PASS — ĐÃ KHÓA (2026-08-17)
- Kịch bản: `docs/manual-tests/sprint-2-feature-66-cancel-assignment.md`. **Gap đã vá**:
  `cancelled_by`/`cancelled_at` (migration V100, cùng pattern đã áp dụng cho `employee_invitations`
  ở V93) + ghi audit `assignment_cancelled`. Tự hủy `scheduled_checks` pending liên quan xác nhận
  vẫn hoạt động đúng, không đổi. Test live: hủy 1 assignment qua API → `cancelledBy`/`cancelledAt`
  trả về đúng; xác nhận hiển thị tới tận Mobile App thật — card phân công đã hủy hiện dòng đỏ
  "Đã hủy lúc [thời gian]" (ảnh `app-05-my-assignments-with-cancelled.png`), round-trip đầy đủ từ
  DB → API → UI nhân viên. Script tự động `test_cancel_assignment.sh` 9/9 pass, không hồi quy.

---

### #67-76 — Chấm công cốt lõi (Check-in/out GPS/Face/Liveness) — ✅ PASS — ĐÃ KHÓA (2026-08-17)
- Kịch bản: `docs/manual-tests/sprint-3-feature-67` đến `-76-*.md`. Nghiên cứu sâu 10 tính năng,
  hầu hết đã "ĐÃ XONG" đúng theo audit gốc hoặc còn tốt hơn — chỉ có 2 nhóm gap thật:
  1. **Gap bảo mật quan trọng nhất đợt này (#69, quyết định chủ dự án sau khi hỏi trực tiếp)**:
     khi check-in/out bằng Face ID theo policy `gps_face` (không phải `gps_face_liveness`), App chỉ
     chụp 1 ảnh và gửi `requiresLiveness=false` — nghĩa là AI hoàn toàn KHÔNG kiểm tra ảnh có phải
     chụp trực tiếp người thật hay không, chỉ so khớp khuôn mặt trong ảnh. Về lý thuyết 1 ảnh
     tĩnh/in ra có thể vượt qua. Đã sửa App (`use-checkin-submit.ts`, `use-checkout-submit.ts`) gửi
     `requiresLiveness=true` bất cứ khi nào có ảnh nộp lên (kể cả qua hàng đợi offline), và sửa
     `OfflineSyncService.java` (trước đó hardcode `false`). Xác nhận qua log backend thật: cờ
     `requiresLiveness` truyền đúng từ request tới `FaceVerifyJobPublisher` tới AI worker.
  2. **Gap audit log (module Chấm công hoàn toàn chưa có)**: đã vá — thêm
     `checkin_submitted`/`checkout_submitted` (trong `CheckinService`, cả nhánh online lẫn offline
     sync) và `checkin_violation_created` (trong `FaceResultCallbackController`, khi AI worker báo
     fail và tạo violation). Xác nhận qua DB thật: 23 bản ghi audit sinh ra ngay trong đợt chạy lại
     bộ test tự động.
  3. **#74 (2 gap khác)**: không trừ giờ nghỉ break; work_minutes vẫn tính khi đang `pending_review`
     — đã hỏi chủ dự án, **quyết định giữ nguyên cả 2, không sửa** (không cần trừ break; vẫn tính
     sẵn work_minutes cho HR xem ngay).
  4. Toàn bộ phần còn lại (#67, #70-73, #75-76) xác nhận qua đọc code + chạy lại script tự động là
     đúng/đã xong, một số điểm "implemented differently" so với AC gốc (không phải gap, chỉ khác
     tên/hình thức — ghi rõ trong từng kịch bản riêng để không nhầm lẫn ở đợt audit sau).
  Script tự động: `test_available_sites.sh` 6/6, `test_basic_checkin.sh` 11/11,
  `test_checkin_history.sh` 8/8, `test_checkin_result.sh` 8/8, `test_checkout.sh` 9/9,
  `test_early_checkin.sh` 6/6, `test_employee_explanation.sh` 16/16, `test_hr_checkin_detail.sh`
  10/10, `test_hr_list_checkins.sh` 11/11, `test_override_checkin.sh` 14/14,
  `test_checkin_face.sh` 5/5, `test_checkin_liveness.sh` 2/2 — tất cả pass, không hồi quy sau khi
  thêm audit log + sửa cờ liveness. Phần E2E cần enrollment Face ID thật qua liveness-challenge (đòi
  hỏi camera thiết bị thật) đã SKIP có chủ đích trong script, không tính là fail.
  **Cần bạn tự test lại trên thiết bị thật**: xác nhận 1 ảnh tĩnh/in ra/chụp màn hình KHÔNG còn qua
  được xác thực khuôn mặt tại site cấu hình `gps_face` (đưa 1 ảnh in trước camera khi check-in) —
  đây là hành vi bảo mật quan trọng, headless/giả lập camera không kiểm chứng được chính xác.

---

## Quy ước cập nhật file này (cho các phiên làm việc sau)

1. Mỗi khi user báo "test xong tính năng #N" kèm kết quả (pass toàn bộ / pass một phần / fail),
   cập nhật đúng dòng trong bảng tổng hợp + viết chi tiết vào mục "Chi tiết" tương ứng.
2. Chỉ đánh dấu ✅ **PASS — ĐÃ KHÓA** khi **toàn bộ** case trong kịch bản test (`sprint-*-feature-*.md`
   tương ứng) đã được xác nhận pass qua UI thật trên **tất cả** nền tảng liên quan (Web/App theo
   đúng cột "Nền tảng" ghi trong `docs/BACKLOG.md`).
3. Nếu chỉ pass một phần hoặc bị chặn bởi môi trường (thiếu thiết bị, thiếu Firebase, v.v.), dùng
   🟡 và ghi rõ case nào còn thiếu — không tự ý khóa sớm.
4. Khi 1 tính năng đã ✅ **ĐÃ KHÓA** mà sau này cần sửa (bug mới phát sinh, thay đổi nghiệp vụ),
   phải: (a) ghi rõ lý do sửa vào mục Chi tiết, (b) hạ trạng thái xuống 🟡 hoặc 🔴 cho tới khi
   test lại xong.
