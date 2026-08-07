# Báo cáo audit nghiệp vụ: Data Masking, Permission Guard, Health Check, UAT Flow, Tài liệu sử dụng, Go-live Checklist

**Ngày:** 2026-08-06
**Phạm vi:** 6 hạng mục — che dữ liệu nhạy cảm trong audit/API, guard quyền theo RBAC, màn trạng thái hệ thống, kịch bản UAT end-to-end, tài liệu hướng dẫn theo vai trò, checklist go-live.
**Tham chiếu thực tế:** GDPR/BIPA (mask/xoá dữ liệu sinh trắc học không trì hoãn bất hợp lý), OWASP (kiểm tra quyền phải nhất quán giữa mọi đường trả dữ liệu — JSON, export, log — không chỉ 1 đường), AWS/Datadog (health check tổng hợp DB+cache+queue+dịch vụ phụ thuộc trong 1 dashboard), Stripe/GitHub (audit log tự động redact field nhạy cảm ở tầng ghi, không phụ thuộc từng call site nhớ làm đúng).

---

## 1. Tóm tắt kết quả

| # | Hạng mục | Trước audit | Sau audit |
|---|---|---|---|
| 1 | Data Masking | 🟡 3 điểm hở: `EmployeeDetailResponse` không che (trong khi `EmployeeResponse` che), Excel export bỏ qua hoàn toàn cơ chế che, token lời mời log ra cleartext | ✅ Cả 3 đã sửa; audit log diff đã có sẵn cơ chế che ở tầng ghi (kiểm tra lại xác nhận đúng, không phải gap) |
| 2 | Permission Guard | ✅ Đã đúng và nhất quán — kiểm tra toàn bộ controller | Không đổi code, chỉ ghi nhận 1 điểm cần lưu ý về thiết kế (mục 2.2) |
| 3 | Health Check | 🟡 Thiếu tín hiệu sức khoẻ cho dịch vụ AI (Face ID) — story yêu cầu rõ "Notification provider" đã có (FCM), nhưng dịch vụ AI thì chưa | ✅ Thêm `AiServiceHealthIndicator`, xuất hiện trong `/system-status` |
| 4 | UAT Flow | 🟡 Đã có tài liệu luồng end-to-end (`manual-test-scenarios.md` mục B.1) nhưng bắt đầu từ tenant có sẵn, chưa có luồng từ tenant mới hoàn toàn → báo cáo → export đúng nghĩa "go-live" | ✅ Thêm mục B.8 — luồng đầy đủ từ tạo tenant tới export báo cáo, gộp cả các tính năng mới của các đợt audit gần đây |
| 5 | Tài liệu sử dụng | 🔴 Chưa từng có | ✅ Viết mới `docs/user-guides/huong-dan-su-dung-theo-vai-tro.md` — 3 vai trò + FAQ |
| 6 | Go-live Checklist | 🔴 Chưa từng có | ✅ Viết mới `docs/deployment/go-live-checklist.md`, dẫn nguồn cụ thể từng mục từ sự cố/gap thật đã gặp trong dự án |

---

## 2. Đối chiếu từng hạng mục

### 2.1 Data Masking

Cơ chế che dữ liệu (`@Masked` + `MaskedSerializer`, tự động che khi serialize JSON, bỏ qua che nếu người gọi là Platform Admin hoặc giữ quyền `users:create`) đã tồn tại và hoạt động đúng ở `EmployeeResponse` (màn danh sách nhân viên). Rà toàn bộ các đường trả dữ liệu email/số điện thoại khác, phát hiện 3 điểm hở thật:

1. **`EmployeeDetailResponse` (màn chi tiết 1 nhân viên) không có `@Masked`** — cùng field `email`/`phone`, cùng dữ liệu, nhưng màn chi tiết (`employees:read`) lộ dữ liệu thô trong khi màn danh sách (`employees:list`) đã che. Một tài khoản chỉ có `employees:read` (hẹp hơn `employees:list`) lại xem được NHIỀU thông tin hơn — ngược logic phân quyền. **Đã sửa**: thêm `@Masked` giống hệt `EmployeeResponse`.

2. **Export Excel nhân viên bỏ qua hoàn toàn cơ chế che** — `EmployeeExportService` ghi thẳng `email`/`phone` thô vào file `.xlsx` bằng Apache POI, không đi qua Jackson nên `@Masked` không có tác dụng gì. Cùng 1 quyền `employees:list` nhưng gọi JSON list thì được che, gọi export thì không — đúng loại lỗ hổng OWASP hay cảnh báo: kiểm soát quyền không nhất quán giữa các đường trả dữ liệu khác nhau của cùng 1 tài nguyên. **Đã sửa**: áp dụng đúng quy tắc bypass giống `MaskedSerializer` (Platform Admin hoặc giữ `users:create` mới thấy dữ liệu thô), áp dụng thủ công qua `MaskingUtils.maskEmail`/`maskPhone` trước khi ghi cell. Live-test xác nhận: tài khoản HR_MANAGER (không giữ `users:create`) thấy `a***@hoanglong.vn`/`***001` ở CẢ HAI nơi (JSON detail lẫn Excel export); Platform Admin thấy dữ liệu thô ở cả hai — nhất quán.

3. **Token lời mời (invitation token) bị log cleartext ở mức INFO** — 3 nơi (`InvitationPublicController` 2 chỗ, `PlatformInvitationPublicController` 1 chỗ). Token này là thông tin xác thực dùng 1 lần để tạo tài khoản/tham gia tenant — dù mức độ nghiêm trọng thấp hơn session token, vẫn là dữ liệu nhạy cảm không nên nằm nguyên trong log ứng dụng (log thường được lưu lâu hơn và nhiều người có quyền đọc hơn DB). **Đã sửa**: chỉ log 8 ký tự đầu của token, đủ để tra cứu/đối chiếu khi debug, không đủ để tái sử dụng.

**Kiểm tra lại cơ chế che dữ liệu trong Audit Log** (oldValue/newValue diff): ban đầu nghi ngờ đây là "dead code" (hàm `MaskingUtils.maskAuditMap` tồn tại nhưng không nơi nào gọi) — kiểm tra trực tiếp `AuditLogService.record(...)` xác nhận **đã được gọi đúng, che dữ liệu TRƯỚC khi ghi vào DB** (không phải chỉ che lúc trả về API) — bảo vệ tốt hơn dự kiến, vì ngay cả người có quyền truy vấn DB trực tiếp cũng không thấy được dữ liệu thô đã bị che ngay từ lúc ghi. Không cần sửa gì thêm ở đây.

### 2.2 Permission Guard

Rà toàn bộ controller có thao tác ghi (POST/PUT/PATCH/DELETE) trong hệ thống — kết luận: **cơ chế phân quyền đã đúng và nhất quán**, nhưng theo 1 cách khác với những gì `@PreAuthorize` nhìn bề ngoài thể hiện.

**Phát hiện quan trọng về kiến trúc (không phải lỗi, nhưng cần ghi lại)**: JWT chỉ mang 1 `tenantId` "đang active" tại thời điểm đăng nhập — `@PreAuthorize("hasAuthority('employees:update')")` trên controller kiểm tra quyền theo tenant ĐÓ, hoàn toàn không biết gì về `{tenantId}` nằm trong đường dẫn URL đang được gọi. **Lớp bảo vệ thật sự chống việc 1 người dùng ở công ty A thao tác dữ liệu công ty B nằm ở tầng SERVICE** — mọi service (Employee, Violation, Workspace, Shift, FaceId, Role...) đều tự truy vấn lại quyền của người gọi theo đúng `tenantId` LẤY TỪ ĐƯỜNG DẪN, không dựa vào JWT. Đã kiểm tra và xác nhận pattern này được áp dụng **nhất quán** ở mọi module rà được, kể cả 2 endpoint ban đầu nghi ngờ thiếu guard (`ViolationController.explainViolation`/`explainViolationWithPhoto`) — 2 endpoint này không có `@PreAuthorize` nhưng đúng chủ đích, vì đây là tài nguyên tự-scope theo chính người gọi (nhân viên chỉ giải trình được vi phạm CỦA MÌNH, service tự tra `employee` theo `userId + tenantId` từ path, không tồn tại cách nào giải trình hộ người khác).

**Không sửa code** — hệ thống đã đúng. Ghi nhận vào tài liệu như 1 lưu ý thiết kế: bất kỳ module MỚI nào viết sau này **bắt buộc phải copy đúng pattern "service tự tra quyền theo tenantId lấy từ path"**, vì chỉ dựa vào `@PreAuthorize` là không đủ để chống thao tác xuyên-tenant — đây chính là rủi ro thật nếu 1 lập trình viên mới không biết quy ước ngầm này.

### 2.3 Health Check

`GET /api/v1/platform/system-status` đã tổng hợp khá đầy đủ: DB (tự động qua Actuator), Redis (custom indicator kèm độ sâu hàng đợi), FCM/notification provider (custom indicator kiểm tra credentials Firebase), 2 job liên quan random-check, số tenant active. Đối chiếu đúng yêu cầu story "DB, Redis, Queue, Notification provider" — thiếu đúng 1 mảnh: **dịch vụ AI (fams-ai — Face ID, liveness, chống giả mạo) không có tín hiệu sức khoẻ nào**, dù đây là dịch vụ phụ thuộc quan trọng không kém FCM (nếu fams-ai down, toàn bộ luồng đăng ký/duyệt/chấm công Face ID đều lỗi âm thầm, Platform Admin chỉ biết qua việc nhìn thấy nhiều lỗi rải rác trong log).

**Đã sửa**: thêm `AiServiceHealthIndicator`, gọi `GET /health` (endpoint sẵn có, không cần xác thực, đúng mục đích liveness-check) trên fams-ai, timeout ngắn (2s connect/3s read) để không làm chậm toàn bộ response `/system-status` nếu fams-ai bị treo. Live-test: `system-status` trả `"aiService": {"status":"UP", "details": {"response":"{\"status\":\"ok\"}"}}`.

Đồng thời phát hiện endpoint này **chưa từng có tài liệu bàn giao** dù đã tồn tại — đã viết mới `docs/api/system-status-api.md`.

### 2.4 UAT Flow

Phát hiện tài liệu `docs/testing/manual-test-scenarios.md` đã có sẵn **932 dòng**, bao 99 tính năng, chia rõ Phần A (test từng tính năng đơn lẻ) và Phần B (7 luồng nghiệp vụ nhiều bước, trong đó B.1 chính là "công trình → ca → phân công → chấm công → HR xem kết quả" — gần khớp yêu cầu story). Không viết trùng lặp tài liệu này.

**Gap thật**: B.1 giả định site/ca/tenant đã có sẵn — không có luồng nào bắt đầu từ **tạo tenant mới hoàn toàn**, đúng kịch bản 1 PO/QA cần chạy trước khi go-live cho khách hàng thật (không phải test tính năng đơn lẻ, mà test "hệ thống có sẵn sàng triển khai không"). Đồng thời các tính năng mới của vài đợt audit gần đây (lưu bộ lọc, trace audit log, template thông báo, che dữ liệu, health check) chưa có mặt trong bất kỳ luồng nào.

**Đã bổ sung**: mục B.8 — luồng 15 bước từ `POST /tenants` (có `ownerEmail`) tới export báo cáo, lồng ghép đúng các tính năng mới (bước 13: lưu bộ lọc, bước 14: trace audit log kèm lưu ý "tạo/sửa Employee hiện chưa được audit" — 1 giới hạn đã biết từ đợt audit trước, không giấu đi; bước 15: kiểm tra system-status trước bàn giao). Kèm 1 case lỗi (nhân viên A check-in bằng token của B → phải bị chặn, tham chiếu đúng phát hiện ở mục 2.2) và 1 đoạn xác nhận riêng cho Data Masking (đối chiếu JSON detail và Excel export phải che giống nhau).

### 2.5 Tài liệu sử dụng theo vai trò

Chưa từng tồn tại. Viết mới `docs/user-guides/huong-dan-su-dung-theo-vai-tro.md` — cấu trúc theo đúng story: Platform Admin, Company Admin/HR, Employee, cộng thêm mục FAQ để giảm trực tiếp chi phí hỗ trợ (đúng mục đích story nêu). Nội dung mô tả **nghiệp vụ**, không mô tả giao diện cụ thể (backend không có UI) — mỗi mục chỉ nêu những gì đã xác nhận hoạt động đúng qua các đợt audit trong dự án, kể cả những giới hạn/lưu ý nghiệp vụ quan trọng (ví dụ: duyệt Face ID luôn cần con người, chấm công ngoài vùng không tự động thành vi phạm, bộ lọc là riêng tư không chia sẻ).

### 2.6 Go-live Checklist

Chưa từng tồn tại. Viết mới `docs/deployment/go-live-checklist.md`, gồm 8 mục: biến môi trường, database/migration, tạo tenant đúng quy trình, kiểm tra sức khoẻ hệ thống, kiểm tra bảo mật/phân quyền, thông báo, theo dõi tuần đầu, rollback. **Mỗi mục dẫn nguồn cụ thể** từ 1 sự cố/gap thật đã gặp trong chính dự án này (không phải checklist chung chung) — ví dụ: nhắc riêng việc thiếu `ownerEmail` khi tạo tenant (lỗi kịch bản test gặp nhiều nhất toàn dự án), nhắc riêng việc thêm biến môi trường mới cần recreate container chứ không chỉ restart (đã gặp thật với `NOTIFICATIONS_INTERNAL_SECRET`), nhắc riêng việc đối chiếu masking JSON vs Excel (gap vừa sửa ở mục 2.1).

---

## 3. Danh sách thay đổi kỹ thuật

| File | Thay đổi |
|---|---|
| `EmployeeDetailResponse.java` | Thêm `@Masked` cho `email`/`phone`, khớp `EmployeeResponse` |
| `EmployeeExportService.java` | Áp dụng masking thủ công (`MaskingUtils.maskEmail`/`maskPhone`) cho export Excel, cùng quy tắc bypass với `MaskedSerializer` |
| `InvitationPublicController.java`, `PlatformInvitationPublicController.java` | Log invitation token chỉ 8 ký tự đầu, không log cleartext |
| `AiServiceHealthIndicator.java` (mới) | Health indicator mới cho fams-ai, đăng ký bean `"aiService"`, gọi `GET /health` |

Build lại, compile sạch. Live-test bằng dữ liệu thật: HR_MANAGER (không giữ `users:create`) thấy dữ liệu che ở cả JSON detail và Excel export; Platform Admin thấy dữ liệu thô ở cả hai; `system-status` trả đúng `aiService: UP`. Chạy lại regression: `tests/employee/test_get_employee.sh`, `test_export_employees.sh`, `test_list_employees.sh`, `test_invite_employee.sh` — toàn bộ pass, không hồi quy.

---

## 4. Giới hạn đã biết / đề xuất

- **Employee create/update hiện chưa được ghi audit log** (phát hiện lại từ đợt audit notification trước, nêu lại ở đây vì liên quan trực tiếp tới Data Masking + UAT Flow mục B.8 bước 14) — nằm ngoài phạm vi 6 hạng mục lần này, đề xuất là 1 hạng mục riêng nếu cần audit trail đầy đủ cho thay đổi thông tin nhân viên.
- **`UserProfileResponse` (`GET /auth/me`) cố tình KHÔNG thêm `@Masked`** dù cùng field `email`/`phone`/`address` như Employee — vì đây là API "xem hồ sơ CỦA CHÍNH MÌNH", thêm `@Masked` sẽ khiến người dùng thấy chính email/SĐT của họ bị che (lỗi UX thật, không phải cải tiến bảo mật, vì `MaskedSerializer` hiện chưa có khái niệm "đang xem hồ sơ của chính mình thì luôn bỏ qua che"). Chỉ nên thêm nếu sau này DTO này bị tái sử dụng cho màn "admin xem hồ sơ người khác" — lúc đó cần bổ sung logic "self-bypass" vào `MaskedSerializer` trước, không chỉ thêm annotation.
- Go-live checklist mục 6 (tuỳ chỉnh nội dung thông báo) giả định khách hàng đã biết cách dùng `POST .../notification-templates` — nếu đội triển khai cần 1 quy trình thao tác cụ thể hơn (ai làm, làm ở đâu), có thể bổ sung thành 1 SOP riêng khi có nhu cầu thật.

---

## 5. Kết luận

6 hạng mục lần này chia rõ: 2 hạng mục (Permission Guard, phần lớn Health Check) xác nhận hệ thống đã làm đúng, chỉ bổ sung nhỏ hoặc chỉ cần ghi lại kiến thức thiết kế; 1 hạng mục (Data Masking) phát hiện 3 điểm hở thật — nghiêm trọng nhất là export Excel bỏ qua hoàn toàn cơ chế che đã áp dụng cho JSON, một lỗ hổng dạng "nhất quán kiểm soát quyền giữa các đường trả dữ liệu" kinh điển; 3 hạng mục còn lại (UAT Flow, Tài liệu sử dụng, Go-live Checklist) là tài liệu hoàn toàn mới hoặc mở rộng tài liệu có sẵn, đều bám sát dữ liệu/gap thật đã phát hiện xuyên suốt các đợt audit trong dự án thay vì viết chung chung.
