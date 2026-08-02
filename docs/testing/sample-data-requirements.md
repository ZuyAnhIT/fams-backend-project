> **[ĐÃ TRIỂN KHAI 01/08/2026]** Toàn bộ mục `[CẦN THÊM]` và `[ĐỀ XUẤT MỚI]` trong tài liệu này đã được implement vào `scripts/seed.sh` + `scripts/seed_historical.sql`, test sống bằng reseed sạch từ đầu (0 lỗi) + regression 31/31 pass. 1 mục cố ý KHÔNG làm: **case sync offline bị từ chối do conflict** (mục 2.6) — fake dữ liệu tĩnh cho case này rủi ro sai lệch với logic thật của `OfflineSyncService`, nên để lại test trực tiếp qua API khi cần thay vì seed. Chi tiết xem báo cáo triển khai cuối phiên làm việc.
>
> **[SUPERSEDED 01/08/2026]** Tài liệu này được thay thế bởi bản yêu cầu đầy đủ và chi tiết hơn tại **`docs/testing/sample-data-requirements-v2.md`** — viết lại theo bộ yêu cầu chuyên nghiệp do người dùng cung cấp (22 mục, đối chiếu kỹ hơn với cấu trúc DB thật, có bảng "không hỗ trợ/không áp dụng" rõ ràng). Giữ file này lại để tham khảo lịch sử, không dùng làm spec triển khai tiếp theo.

# Kịch bản yêu cầu dữ liệu mẫu (Seed Data Requirements) — FAMS

> Viết lại chuyên nghiệp hóa từ yêu cầu gốc của bạn, có bổ sung thêm các trường hợp còn thiếu để bộ dữ liệu đủ dùng cho việc kiểm thử nghiệp vụ đầu-cuối (không chỉ demo UI). Đối chiếu với 39 thực thể nghiệp vụ và checklist 99 tính năng đã audit (`docs/api/backend-feature-audit-2026-08-01.md`) để đảm bảo mỗi tính năng đều có ít nhất 1 bộ dữ liệu minh họa đúng và 1 bộ minh họa case biên/lỗi.
>
> Trạng thái hiện tại: `scripts/seed.sh` + `scripts/seed_historical.sql` đã implement một phần đáng kể yêu cầu gốc (15 tenant, 3 tenant chuyên sâu, đa dạng role/trạng thái...). Tài liệu này là **spec đầy đủ mục tiêu cuối cùng** — phần nào đã có sẵn được ghi chú `[ĐÃ CÓ]`, phần nào cần bổ sung ghi `[CẦN THÊM]`, phần đề xuất mới ngoài yêu cầu gốc ghi `[ĐỀ XUẤT MỚI]`.

## 0. Nguyên tắc chung khi xây dựng bộ dữ liệu

1. **Đa dạng trạng thái quan trọng hơn số lượng thuần túy.** 15 bản ghi giống hệt nhau chỉ khác tên không có giá trị test bằng 8 bản ghi phủ đủ 8 trạng thái/nhánh nghiệp vụ khác nhau của cùng 1 entity. Với mỗi entity có `status`/lifecycle, bộ dữ liệu phải có **ít nhất 1 bản ghi mỗi trạng thái** hệ thống hỗ trợ, không chỉ trạng thái "vui" (happy path).
2. **Liên kết logic nghiệp vụ phải nhất quán**, không phải random độc lập từng bảng. Ví dụ: `attendance_summaries.work_minutes` phải khớp với chênh lệch thời gian giữa `checkins` bên dưới; `scheduled_checks.config_snapshot` phải khớp với `random_check_configs` đang active tại thời điểm sinh; nhân viên có `face_profiles.status='not_enrolled'` thì không được có `checkins` với `face_verified=true`.
3. **Một mật khẩu chung cho toàn bộ tài khoản mẫu** (giữ nguyên yêu cầu gốc) — trừ các tài khoản cố ý test luồng "không dùng password" (Google login).
4. **Idempotent** — script chạy lại nhiều lần trên cùng DB không lỗi, không tạo trùng (giữ nguyên yêu cầu gốc, đã có sẵn qua upsert/`ON CONFLICT`).
5. **Timestamp phải hợp lý theo thời gian thực** (không đặt dữ liệu tương lai trừ khi cố ý test 1 tính năng cụ thể cần vậy — ví dụ test giới hạn `/my-pending` cần 1 check có `scheduledAt` tương lai xa).
6. **Traceability**: mỗi nhóm dữ liệu nên map được tới 1 hoặc nhiều mục trong checklist 99 tính năng, để biết seed xong có thể test được gì — tránh sinh dữ liệu "cho có" mà không phục vụ test case cụ thể nào.

---

## 1. Dữ liệu mẫu phía nền tảng hệ thống

### 1.1 Tài khoản Platform Admin & Platform Staff
- **[ĐÃ CÓ]** 1 platform admin + ≥12 platform staff, gồm nhiều platform-role khác nhau (PLATFORM_STAFF, PLATFORM_SUPPORT_LEAD, PLATFORM_BILLING_OPS, PLATFORM_SECURITY_AUDITOR...).
- **[CẦN THÊM]** Nâng lên đúng **>15 bản ghi** platform staff như yêu cầu, đảm bảo mỗi platform-role có ≥2 người (không phải role nào cũng chỉ 1 người — để test trường hợp "nhiều người cùng 1 quyền, thu hồi role của 1 người không ảnh hưởng người còn lại").
- **[ĐỀ XUẤT MỚI]** ≥1 platform staff bị **vô hiệu hóa/khóa** (test hành vi khi tài khoản mất quyền truy cập giữa chừng — token cũ có bị chặn không).

### 1.2 Gói dịch vụ (Plans)
- **[ĐÃ CÓ]** Đủ các gói Trial/Basic/Pro/Enterprise, có tenant dùng monthly lẫn yearly billing cycle.
- **[ĐỀ XUẤT MỚI]** Mỗi gói cần có `plan_limits` **khác biệt rõ rệt** (số nhân viên tối đa, số site tối đa...) để test enforcement thực sự có hiệu lực, không chỉ hiển thị số.
- **[ĐỀ XUẤT MỚI]** 1 gói ở trạng thái **deactivated** (ngừng bán) nhưng vẫn có ≥1 tenant cũ đang dùng — test luồng "gói ngừng bán, tenant cũ vẫn chạy bình thường, chỉ chặn đăng ký mới".

### 1.3 Role & Permission hệ thống
- **[ĐÃ CÓ]** Đủ 5 role hệ thống mặc định (PLATFORM_ADMIN, TENANT_ADMIN, HR_MANAGER, SITE_SUPERVISOR, EMPLOYEE) + toàn bộ permission, seed qua Flyway migration.
- **[ĐỀ XUẤT MỚI]** Đảm bảo permission phủ đủ **mọi nhóm (resource group)** hiện có trong hệ thống — không chỉ để hiển thị đẹp ở màn "xem permission theo nhóm" mà để khi tạo role custom (mục 2.2) có đủ nguyên liệu phối hợp.

### 1.4 Danh sách người dùng hệ thống / nhân viên nền tảng
- **[ĐÃ CÓ]** >15 mỗi loại (owner accounts, platform staff, tenant employees).
- **[CẦN THÊM]** Xác nhận rõ số lượng **users** (bảng `users`, không phải `employees`) tách biệt độc lập cũng đạt >15 mẫu cho mỗi phân khúc (platform-only user chưa từng là employee tenant nào, user vừa là employee vừa có platform role, v.v.) — hiện tại phần lớn user gắn với 1 vai trò cố định.

### 1.5 Trạng thái xác thực tài khoản — **đây là điểm bạn đã tự nhận là cần làm rõ, viết lại chi tiết:**

| Case | Số lượng tối thiểu | Mục đích test |
|---|---|---|
| Tài khoản xác thực đầy đủ (email + phone), sẵn sàng login ngay | Đa số (mặc định) | Test happy-path mọi tính năng |
| Chưa xác thực email (`email_verified=false`) | ≥2 | Test chặn/nhắc verify email, test luồng resend |
| Chưa xác thực số điện thoại | ≥2 | Test chặn/nhắc verify phone, test OTP resend |
| Tài khoản bị khóa do đăng nhập sai quá 5 lần (`locked_until` còn hiệu lực) | ≥1 | Test hành vi khóa/mở khóa (checklist #14) |
| Tài khoản đã bật TOTP 2FA sẵn | ≥2 (1 owner + 1 employee) | Test luồng login có 2FA (checklist #13), test backup code |
| Tài khoản đăng nhập qua Google (không có password thật/không set được) | ≥1 | Test riêng luồng Google login, test link/unlink |
| Refresh token đã bị thu hồi (revoked) còn nằm trong DB | ≥1 | Test logout-all thực sự chặn token cũ, không chỉ xóa ở FE |
| Access token đã hết hạn nhưng refresh token còn hiệu lực | Không cần seed sẵn (test runtime bằng cách chờ/giả lập TTL) | — |
| Mật khẩu chung cho MỌI tài khoản còn lại | Bắt buộc | Test nhanh, dễ nhớ (giữ nguyên yêu cầu gốc) |

---

## 2. Dữ liệu mẫu các công ty sử dụng dịch vụ nền tảng

### 2.1 Danh sách tenant (12-15 công ty)
- **[ĐÃ CÓ]** 15 tenant, đa dạng ngành nghề, đa dạng gói (Trial/Basic/Pro/Enterprise), có tenant `SUSPENDED`, có tenant subscription `CANCELLED`.
- **[ĐÃ CÓ]** Case người dùng thuộc 2 công ty với vai trò khác nhau ở mỗi công ty (Phạm Thị Dung: HR_MANAGER + SITE_SUPERVISOR; Trương Văn Đạt: EMPLOYEE + EMPLOYEE).
- **[ĐỀ XUẤT MỚI]** ≥1 tenant **mới tạo, chưa cấu hình gì** (0 site/0 shift/0 employee ngoài owner) — test "empty state" của mọi màn hình danh sách, test màn onboarding gợi ý các bước cần làm.
- **[ĐỀ XUẤT MỚI]** ≥1 tenant **sắp hết hạn Trial** (còn 1-2 ngày) và ≥1 tenant **đã hết hạn Trial** chưa nâng cấp — test cảnh báo/khóa tính năng đúng thời điểm.
- **[ĐỀ XUẤT MỚI]** ≥1 tenant có cấu hình **IP whitelist đang bật** (test chặn truy cập ngoài dải IP cho phép) và phần lớn tenant còn lại **không bật** (mặc định mở) — để phân biệt rõ 2 nhánh hành vi.
- **[ĐỀ XUẤT MỚI]** ≥1 tenant đã tùy chỉnh **branding/format riêng** (logo, màu thương hiệu, định dạng ngày/giờ khác mặc định) — test cấu hình giao diện thực sự ảnh hưởng response, không chỉ lưu DB.

### 2.2 Công ty đại diện chuyên sâu (2-3 công ty)
Viết lại rõ tiêu chí lựa chọn (yêu cầu gốc nói "gói free/cao cấp/mới tạo" nhưng chưa nêu mục đích test rõ ràng của từng loại — bổ sung ở đây):

| Công ty | Đặc điểm | Mục đích test chính |
|---|---|---|
| **Công ty A — gói cao cấp (Pro/Enterprise), dữ liệu đầy đủ nhất** | Toàn bộ entity có dữ liệu phong phú, mọi tính năng đều test được | Trục chính để test mọi luồng nghiệp vụ end-to-end |
| **Công ty B — gói Trial, chạm giới hạn gói** | Số nhân viên = đúng giới hạn tối đa cho phép | Test enforcement giới hạn gói (chặn tạo thêm khi đã full) |
| **Công ty C — mới tạo hoặc đang bị khóa/suspended** | Ít/không có dữ liệu vận hành, hoặc đang bị chặn truy cập | Test hành vi khi tenant bị giới hạn quyền truy cập giữa chừng (nhân viên vẫn login được nhưng API vận hành bị chặn ra sao?) |

**[ĐÃ CÓ]** 3 tenant chuyên sâu hiện tại (Hoàng Long/Bình Minh/Phương Nam đều là Pro) — **[ĐỀ XUẤT MỚI]**: đổi/bổ sung để 1 trong 3 công ty chuyên sâu thuộc nhóm Trial-chạm-giới-hạn hoặc suspended thay vì cả 3 đều "khỏe mạnh" như nhau (hiện Tia Sáng/Đông Á đã đóng vai trò này nhưng ở mức nhẹ, chưa "chuyên sâu" — cân nhắc nâng độ sâu dữ liệu của 1 trong 2 tenant này lên ngang tầm 3 tenant chính nếu cần test giới hạn gói với dữ liệu đầy đủ).

### 2.3 Role, quyền, phòng ban, nhân viên trong công ty chuyên sâu

**Role & quyền:**
- **[ĐÃ CÓ]** Role mặc định hệ thống áp dụng sẵn cho tenant + role custom riêng theo tenant (site-scoped role đã có).
- **[CẦN THÊM]** Đảm bảo mỗi tenant chuyên sâu có **≥1 role custom với tập quyền cắt lai** (không phải sao chép y hệt role mặc định) — ví dụ "Trưởng ca đêm" chỉ có quyền xem chấm công + duyệt Face ID nhưng không có quyền xóa nhân viên — để test phân quyền chi tiết theo permission thay vì chỉ theo role.

**Phòng ban/Workspace:**
- **[ĐÃ CÓ]** 12-13 workspace/tenant chuyên sâu, có quan hệ cha-con.
- **[CẦN THÊM]** Nâng đúng **12-15** như yêu cầu, đảm bảo có **≥2 cấp lồng nhau thực sự sâu** (không chỉ 1 cấp cha-con mà 3 cấp: Công ty → Khối → Đội) để test đầy đủ `GET .../workspaces/tree`.

**Nhân viên:**
- **[ĐÃ CÓ]** 15 nhân viên/tenant chuyên sâu, đa dạng vị trí.
- **[CẦN THÊM]** Đảm bảo **10-15 mẫu cho MỖI loại vai trò riêng biệt** (công trình/worker, HR, trưởng bộ phận/supervisor) trong CÙNG 1 tenant chuyên sâu — hiện 15 người/tenant có thể không đủ chia đều 3 nhóm x 10-15 mỗi nhóm; cần tăng tổng số nhân viên tenant chuyên sâu lên ~30-40 nếu muốn đủ độ sâu mỗi vai trò theo đúng yêu cầu gốc.
- **[ĐỀ XUẤT MỚI]** Đủ trạng thái nhân viên: active, inactive (tạm ngừng), terminated (đã nghỉ, có ngày nghỉ việc) — test hành vi hủy assignment/scheduled-check tự động khi terminate.

**Lời mời (invitation):**
- **[ĐÃ CÓ]** Có invitation đã hết hạn (expired) trong lịch sử.
- **[ĐỀ XUẤT MỚI]** Bổ sung đủ **4 trạng thái**: `pending` (đang chờ, còn hạn — để tự tay test accept), `accepted` (đã dùng), `expired` (hết hạn), `cancelled` (bị HR hủy tay) — hiện thiếu case `pending` còn hiệu lực để người test tự thao tác accept trực tiếp.

**Gán nhân viên vào phòng ban:**
- **[ĐỀ XUẤT MỚI]** Đa số nhân viên đã có `workspace_members`, nhưng giữ lại **≥2-3 nhân viên chưa gán phòng ban nào** — test case "nhân viên mồ côi" (chưa hoàn tất setup), đảm bảo các tính năng khác (chấm công, random check) vẫn hoạt động đúng dù thiếu workspace.

### 2.4 Công trình, phân vùng, ca làm việc, phân công

**Công trình (Site):**
- **[ĐÃ CÓ]** 12-13 site/tenant chuyên sâu.
- **[ĐỀ XUẤT MỚI]** Trải rộng **nhiều tỉnh/thành khác nhau** (không chỉ 1 thành phố) để test khoảng cách/geofence đa dạng bán kính thực tế; ≥1 site ở trạng thái `inactive`.

**Geofence:**
- **[ĐÃ CÓ]** 1 geofence active/site + geofence lịch sử (superseded) cho ~nửa số site — test tính năng xem lịch sử (checklist #58).
- **[CẦN THÊM]** Nâng đúng **12-15 geofence** như yêu cầu (hiện phụ thuộc số site, số geofence lịch sử chỉ phủ ~nửa — cân nhắc phủ 100% site có ≥1 lần sửa geofence để dữ liệu lịch sử phong phú hơn).

**Ca làm việc (Shift):** yêu cầu gốc "5-8 mẫu đủ trường hợp" — viết rõ danh sách trường hợp bắt buộc:
1. Ca ngày hành chính tiêu chuẩn (8h-17h, không OT)
2. Ca ngày có bật OT + giới hạn dung sai check-out muộn
3. Ca đêm (`allowOvernight=true`, qua nửa đêm)
4. Ca xoay ca ngắn (ví dụ 6h, cho site vận hành 3 ca/ngày)
5. Ca có early-checkin tolerance rộng (cho phép vào sớm nhiều) vs. ca tolerance hẹp/không cho vào sớm
6. ≥1 ca đã bị **deactivate** (ngừng dùng, còn nhân viên/assignment tham chiếu lịch sử) — test hành vi khi ca cũ vẫn hiện trong dữ liệu lịch sử dù không còn active

**Phân công (Assignment):**
- **[ĐÃ CÓ]** Có case cancelled qua phần "đa dạng hóa" trong seed hiện tại.
- **[ĐỀ XUẤT MỚI]** Đủ: `active` (đa số), `cancelled`, **đã hết hạn tự nhiên** (`endDate` đã qua nhưng không bị cancel — khác về mặt nghiệp vụ với cancel chủ động), và ≥1 nhân viên có **lịch sử nhiều phân công theo thời gian** (từng ở site A, chuyển sang site B) để test đúng logic "chỉ tính assignment hiệu lực tại thời điểm chấm công", không lẫn dữ liệu chấm công cũ vào assignment mới.

### 2.5 Dữ liệu Face ID

- **[ĐÃ CÓ]** Đa dạng trạng thái: not_enrolled, pending review (submit lần đầu), enrolled+pending (re-enroll trên nền đã duyệt), revoked.
- **[CẦN THÊM]** Bổ sung rõ **case `rejected`** (HR từ chối duyệt, có lý do) nếu seed hiện tại chưa có — đủ vòng đời: consent → enroll → pending → approved/rejected → (revoked sau đó).
- **[ĐỀ XUẤT MỚI]** Đảm bảo tỉ lệ đủ để test thật: trong các tenant chuyên sâu, có nhân viên **enrolled thật** dùng để test check-in Face ID/liveness sống (không chỉ dữ liệu lịch sử tĩnh) — ảnh/embedding demo phải đủ để pass được luồng xác thực thật khi test thủ công qua Swagger/Postman, không chỉ nằm trong SQL lịch sử.

### 2.6 Dữ liệu chấm công (check-in/check-out) & bảng công

- **[ĐÃ CÓ]** 30 ngày lịch sử checkin/attendance_summary cho 5 tenant giàu dữ liệu nhất.
- **[ĐỀ XUẤT MỚI]** Kéo dài **≥2-3 tháng** lịch sử (không chỉ 30 ngày) cho ít nhất 1-2 tenant chuyên sâu — để test đúng nghĩa "bảng công theo tháng" qua nhiều tháng liên tiếp, test báo cáo xu hướng, test đúng biên giới đầu/cuối tháng.
- Đủ case bắt buộc (đối chiếu checklist #71-84):
  - Đúng giờ hoàn toàn (không vi phạm gì)
  - Đi muộn (nhiều mức độ: muộn nhẹ trong dung sai, muộn vượt dung sai)
  - Về sớm
  - Có OT hợp lệ (ca cho phép OT, làm quá giờ)
  - Cố tình làm quá giờ nhưng ca **không cho OT** — test có bị cap đúng không, không tính dư giờ
  - Thiếu checkout (bỏ dở, còn session mở) — cả trường hợp còn "mở" tới hiện tại và trường hợp đã "chốt" qua đêm hôm sau
  - Check-in ngoài geofence → escalate `pending_review`, có case đã HR duyệt tay và case còn treo
  - Check-in offline rồi sync sau — bao gồm **case sync bị từ chối do đã có bản ghi trùng/overlap** (test idempotency + conflict detection), không chỉ case sync thành công
  - Check-in Face ID fail / liveness fail — có bản ghi hiển thị đúng trạng thái "chờ xác minh"/"thất bại" phân biệt rõ 2 loại
  - **[LƯU Ý]** Hệ thống hiện **chưa có khái niệm "ngày nghỉ phép/leave"** (không có trong checklist 99 mục cũng như entity nào) — **không đưa case "nghỉ phép" vào seed** vì tính năng chưa tồn tại; nếu cần test "ngày không có ca làm" thì chỉ đơn giản là ngày không có `assignment` hiệu lực, không phải nghỉ phép có phê duyệt.

### 2.7 Dữ liệu Random Check

- **[ĐÃ CÓ]** Config tenant-default + site-override, scheduled_checks 14 ngày lịch sử, check_responses, violations (no_response + location_fail).
- **[ĐỀ XUẤT MỚI]** Đủ 3 `checkMode` (location_only/location_face/location_face_liveness) được **thực sự áp dụng và thấy kết quả khác nhau** trong dữ liệu (không chỉ cấu hình tồn tại mà không có check nào dùng mode đó).
- **[ĐỀ XUẤT MỚI]** Đủ trạng thái `scheduled_checks.status`: pending (bao gồm ≥1 case còn cách xa để test bị `/my-pending` ẩn đi, và ≥1 case sắp tới trong vòng lookahead để test hiện ra), sent, responded (pass/fail), no_response, cancelled (do assignment bị hủy).
- **[ĐỀ XUẤT MỚI]** Violation đủ loại: `no_response`, `location_fail`, `face_fail` (nếu hệ thống phân loại riêng) — cả case đã `resolved` (HR xử lý) lẫn chưa resolved, để test màn xử lý vi phạm của HR.

### 2.8 Thông báo & cấu hình

- **[ĐÃ CÓ]** notifications, notification_templates, user_notification_settings, user_devices, notification_delivery_logs trong lịch sử.
- **[ĐỀ XUẤT MỚI]** Do vừa sửa xong tính năng tách in-app/push (xem `docs/api/random-check-config-review.md` mục 13.3), cần bổ sung dữ liệu test **đúng bộ 4 tổ hợp**: (in-app bật, push bật) / (in-app tắt, push bật) / (in-app bật, push tắt) / (cả 2 tắt) — cho ít nhất vài nhân viên khác nhau, để người test xác nhận trực quan qua dữ liệu có sẵn thay vì phải tự tạo tay.
- **[ĐỀ XUẤT MỚI]** User device: có device đang active, có device đã **unregister** (soft-delete) — test không gửi nhầm tới token đã hủy.
- **[ĐỀ XUẤT MỚI]** Có notification đã đọc và chưa đọc trộn lẫn hợp lý theo thời gian (không phải toàn bộ cùng 1 trạng thái).

---

## 3. Tổng hợp — những điểm bổ sung quan trọng nhất ngoài yêu cầu gốc

Nếu phải ưu tiên, đây là các bổ sung có giá trị test cao nhất mà yêu cầu gốc của bạn chưa đề cập rõ:

1. **Ma trận trạng thái xác thực tài khoản** (mục 1.5) — yêu cầu gốc có nhắc nhưng chưa liệt kê đủ 7 case cụ thể.
2. **Tenant rỗng/mới tinh** và **tenant sắp/đã hết hạn trial** — test "empty state" và cảnh báo hết hạn, hai luồng UI quan trọng thường bị quên khi seed data luôn "đầy đủ sẵn".
3. **Kéo dài lịch sử chấm công sang nhiều tháng** thay vì chỉ 30 ngày — bắt buộc nếu muốn test đúng nghĩa bảng công theo tháng qua nhiều kỳ.
4. **Role custom có tập quyền cắt lai thực sự** (không phải bản sao role có sẵn) — để test đúng RBAC ở mức permission, không chỉ mức role.
5. **Bộ 4 tổ hợp in-app/push preference** — trực tiếp phục vụ việc xác minh phase sửa lỗi vừa xong.
6. **Case sync offline bị từ chối do conflict** — hiện seed thường chỉ có case thành công, thiếu case lỗi.
7. Xác nhận rõ ràng: **hệ thống chưa có "nghỉ phép/leave"** — tránh yêu cầu seed cho 1 tính năng chưa tồn tại trong backend.

---

## 4. Bước tiếp theo

Tài liệu này là **spec/yêu cầu**, chưa phải triển khai. Sau khi bạn xác nhận/điều chỉnh các mục trên (đặc biệt các dòng `[ĐỀ XUẤT MỚI]` — bạn có muốn tất cả hay chỉ chọn lọc một số), bước kế tiếp sẽ là cập nhật `scripts/seed.sh`/`scripts/seed_historical.sql` theo đúng spec đã chốt, rồi seed thử + verify số lượng/trạng thái bằng query SQL đối chiếu lại từng mục.
