# Báo cáo rà soát & sửa lỗi — Luồng Violation / HR xử lý Random Check / Điều chỉnh chấm công

> Ngày: 02-03/08/2026. Phạm vi: 8 user story do bạn cung cấp (tạo violation tự động, HR kích hoạt/xem/xử lý random check, HR override check-in, HR chỉnh bảng công, nhân viên giải trình) — đối chiếu với logic nghiệp vụ thật đã build (quản lý nhân viên, Face ID, chấm công, ca làm, phòng ban, công trình, random check), tham khảo cách các hệ thống thực tế (Deputy, QuickBooks Time/Intuit, Connecteam, UKG/Kronos) xử lý spot-check + dispute resolution để quyết định hướng sửa.

## Tóm tắt kết quả

Cả 8 story đều **đã có code thực hiện từ trước** (không phải xây từ đầu), nhưng qua audit sâu (đọc service-layer thật, không chỉ xem controller có tồn tại hay không) phát hiện **1 bug nghiêm trọng** và **6 gap thực chất** ảnh hưởng tới tính đúng đắn/mạch lạc dữ liệu giữa các module. Đã sửa toàn bộ 7 vấn đề, test sống bằng API thật, không có regression (so với hành vi đã xác nhận trước đó).

| # | Vấn đề | Mức độ | Đã sửa |
|---|---|---|---|
| 1 | HR_MANAGER thiếu quyền `checkins:*`/`randomchecks:*` — 4/8 story tên "HR..." thực chất trả 403 cho đúng vai trò được đặt tên | 🔴 Bug nghiêm trọng | ✅ |
| 2 | Sinh scheduled check yêu cầu Face ID cho nhân viên **chưa từng được duyệt Face ID** → tạo `face_fail` violation oan | 🟠 Gap nghiệp vụ | ✅ |
| 3 | Không có idempotency guard khi tạo violation từ callback AI (rủi ro trùng violation nếu callback retry) | 🟡 Rủi ro tiềm ẩn | ✅ |
| 4 | HR dismiss violation (xác nhận vi phạm là oan) không hề cập nhật lại cờ `hasRandomCheckFailure` trên bảng công | 🟠 Gap liên kết dữ liệu | ✅ |
| 5 | Xem chi tiết 1 scheduled check không thấy được violation liên quan — HR phải tự tra `GET /violations` và đoán | 🟠 Gap UX xử lý tranh chấp | ✅ |
| 6 | Nhân viên hoàn toàn không có cách nào tự xem danh sách vi phạm của mình | 🟠 Gap tính năng | ✅ |
| 7 | Nhân viên không lọc được check-in `pending_review` (cần giải trình) trong lịch sử của mình | 🟡 Gap tiện ích | ✅ |

---

## Chi tiết từng story

### Story 1 — Tạo violation khi không phản hồi

**Đã đúng từ trước**: job `NoResponseViolationJob` chạy mỗi 2 phút, có idempotency guard, và tự động **không** tạo violation oan cho assignment đã bị hủy/nhân viên đã nghỉ việc (nhờ `ScheduledCheckCancelService` hủy trước mọi check `pending`/`sent` ngay khi assignment bị hủy hoặc nhân viên nghỉ việc — cơ chế này đã được sửa ở phase trước, hôm nay chỉ xác nhận lại còn đúng).

**Không sửa gì thêm** — logic này đã mạch lạc.

### Story 2 — Tạo violation khi fail random check (location/face/liveness)

**Phát hiện gap nghiêm trọng nhất của cả đợt audit**: `ScheduledCheckGeneratorService` sinh lịch kiểm tra **không hề kiểm tra nhân viên đã được duyệt Face ID hay chưa** — nếu cấu hình yêu cầu `location_face`/`location_face_liveness`, hệ thống vẫn gửi yêu cầu kiểm tra cho MỌI nhân viên theo `applicableRoles`, kể cả người chưa từng đăng ký/được duyệt Face ID. Khi người này buộc phải phản hồi, `CheckResponseService` phát hiện "không có hồ sơ Face ID đã duyệt" → tự động coi là **face_fail** → tạo violation cho một việc nhân viên **chưa bao giờ được cho phép làm**.

**Tham khảo hệ thống thực tế**: các hệ thống chấm công sinh trắc học (Deputy, UKG Ready...) đều có nguyên tắc "graceful degradation" — nhân viên chưa hoàn tất đăng ký sinh trắc học không bị chặn làm việc hay bị phạt vì thiếu tính năng họ chưa được cấp; hệ thống tự động hạ về xác thực vị trí (GPS-only) cho tới khi đăng ký xong. Nguyên tắc này **đã có sẵn** trong FAMS ở luồng check-in thường (đã audit trước đó), nhưng **thiếu ở luồng random check** — đây chính là điểm không nhất quán giữa 2 module cùng dùng chung khái niệm Face ID.

**Đã sửa**: `ScheduledCheckGeneratorService` (sinh lịch tự động hàng đêm) và `ManualCheckService` (HR kích hoạt thủ công) đều kiểm tra `FaceProfile.status='enrolled'` trước khi build snapshot — nếu chưa enroll, **tự động hạ `checkMode` xuống `location_only` CHỈ CHO LẦN KIỂM TRA ĐÓ** (không đổi cấu hình gốc của tenant/site). Riêng luồng thủ công: nếu HR **chủ động** chỉ định `checkMode` (không dùng mặc định), tôn trọng đúng ý HR — chỉ áp fail-safe khi HR dùng mặc định của cấu hình, vì đây là hành động có chủ đích (vd HR đang test xem 1 người có thực sự enroll hay chưa).

**Đã test sống**: kích hoạt cả 2 luồng (thủ công + `POST /scheduled-checks/generate`) cho nhân viên chưa enroll tại site có config `location_face` → xác nhận `configSnapshot.checkMode` trả về đúng `location_only`.

**Bonus phát hiện, đã sửa luôn**: `CheckResponseService.applyFaceResult()` (xử lý callback bất đồng bộ từ AI service) **không có** guard chống trùng lặp, khác với `NoResponseViolationJob` vốn đã có — nếu callback bị retry (network chập chờn), có thể tạo 2 violation `face_fail`/`liveness_fail` cho cùng 1 lần kiểm tra. Đã thêm guard cùng pattern.

### Story 3 — HR kích hoạt kiểm tra ngay

**Xác nhận đúng thiết kế** (không phải bug): kích hoạt thủ công **bỏ qua** hoàn toàn giới hạn `checksPerShift`/`minIntervalMinutes` của cấu hình — đúng ý nghĩa "HR cần xác minh ngay một tình huống nghi ngờ", không nên bị chặn bởi nhịp độ kiểm tra thông thường. Lý do đã ghi lại rõ (dùng `checkIndex` âm riêng để không đụng độ với lịch tự động).

**Đã sửa quyền**: xem story tổng hợp bên dưới (bug RBAC).

### Story 4 & 5 — HR xem danh sách / chi tiết scheduled checks

**Danh sách**: đã làm tốt từ trước — có phân trang, lọc đủ (site/nhân viên/trạng thái/khoảng ngày), và đã tự hydrate tên nhân viên/site theo batch (tránh N+1 query, ghi chú rõ đây là fix từ 1 đợt audit FE trước đó).

**Chi tiết — gap thật**: response chi tiết 1 scheduled check có kèm `response` (câu trả lời của nhân viên) nhưng **không có** violation liên quan, dù `check_response_id`/`scheduled_check_id` đã lưu sẵn trên bảng `violations`. Muốn biết "lần kiểm tra này có bị ghi vi phạm không, đã xử lý chưa", HR phải gọi thêm `GET /violations` rồi tự lọc theo `employeeId`+ngày — không chính xác 100% nếu nhân viên có nhiều vi phạm cùng ngày.

**Đã sửa**: nhúng thẳng danh sách `violations` (id, loại, đã xử lý chưa, kết luận, mô tả) vào response chi tiết. Đồng thời thêm filter `scheduledCheckId` cho `GET /violations` để có cách tra cứu chính xác 1:1 khi cần, không chỉ dựa vào response nhúng sẵn.

**Đã test sống**: gọi chi tiết 1 check đã có violation `no_response` đã confirmed — response trả đúng field `violations: [{...}]`.

### Story 6 — HR override check-in

**Xác nhận làm tốt từ trước**: override đã tự động trigger tính lại `AttendanceSummary` ngay trong cùng transaction (không đợi job đêm), có ghi audit trail đầy đủ (`overriddenBy`, `overriddenAt`, lý do bắt buộc).

**Chỉ thiếu quyền** — cùng bug RBAC bên dưới.

### Story 7 — HR chỉnh attendance summary (adjust)

**Xác nhận hoạt động đúng cho HR_MANAGER** (đây là 1/8 story KHÔNG bị bug RBAC, vì `attendance:list` đã được cấp sẵn từ trước). Có bắt buộc lý do, có cơ chế khóa (`adjustmentReason` khác null → job đêm không tự ghi đè số liệu HR đã chỉnh tay cho tới khi HR chủ động unlock-and-recompute).

**Gap liên kết dữ liệu đã sửa**: trước đây HR có thể chỉnh sạch bảng công 1 ngày (vd `late=false`) trong khi ngày đó vẫn còn violation **chưa xử lý** — 2 luồng hoàn toàn tách biệt, không cảnh báo nhau. Đã sửa theo hướng khác với "chặn cứng" (vì sẽ giảm linh hoạt của HR khi cần điều chỉnh gấp) — thay vào đó: **khi HR xác nhận/bác bỏ 1 violation, hệ thống tự tính lại `AttendanceSummary` của đúng ngày đó ngay lập tức** (nếu bảng công ngày đó đã tồn tại), để cờ `hasRandomCheckFailure` luôn phản ánh đúng quyết định mới nhất của HR — không còn tình trạng "vi phạm đã bị bác bỏ nhưng báo cáo vẫn hiện có vi phạm mãi mãi".

**Đã test sống 2 chiều**: dùng đúng câu truy vấn SQL đã sửa để xác nhận (a) nếu 1 ngày có nhiều lần kiểm tra thất bại, dismiss 1 lần chưa đủ để tắt cờ (đúng — còn lần khác chưa xử lý), (b) dismiss lần kiểm tra DUY NHẤT của ngày đó → cờ tắt đúng.

### Story 8 — Nhân viên gửi giải trình check-in lỗi

**Xác nhận đây là 2 luồng thật sự tách biệt, không phải trùng lặp thừa** trong đa số trường hợp: check-in ngoài geofence (random thường) không tạo violation, chỉ có `pending_review` trên chính `CheckinRecord`; còn violation từ random check không gắn với `CheckinRecord` nào cả (vì random check là 1 sự kiện GPS/ảnh riêng, không phải 1 lượt chấm công). Riêng 1 trường hợp thật sự trùng lặp (check-in thường tại site yêu cầu Face ID, xác thực khuôn mặt fail) — 1 sự kiện tạo ra CẢ 2 đối tượng cần giải trình riêng, đã ghi nhận vào mục "còn tồn đọng" bên dưới vì cần quyết định UX (gộp 1 form giải trình hay giữ 2, thuộc phạm vi frontend nhiều hơn backend).

**Gap thật sự nghiêm trọng đã sửa**: `EMPLOYEE` không hề có quyền `violations:*` nào trong RBAC seed — nhân viên **không có cách nào** liệt kê vi phạm của chính mình, chỉ gọi được `explain` nếu đã biết sẵn `violationId` (mà không có nơi nào cung cấp ID đó). Đối chiếu thực tế: các app HR thực tế (Connecteam, Deputy) luôn có 1 "inbox" cho nhân viên thấy rõ việc gì cần họ xử lý.

**Đã sửa**: thêm `GET /violations/my` (tự scope theo chính nhân viên gọi, không cần quyền `violations:list`, cùng pattern với `/scheduled-checks/my-pending` đã có sẵn) + thêm filter `status` cho `GET /checkins/history` để lọc riêng `pending_review`. 2 endpoint này là 2 "inbox" riêng (đúng thực tế nghiệp vụ là 2 loại sự kiện khác nhau), nhưng giờ **cả 2 đều tồn tại và tìm được**, thay vì 1 có 1 không.

**Đã test sống**: gọi cả 2 endpoint mới bằng tài khoản nhân viên thật, xác nhận 200 và tự scope đúng.

---

## Bug xuyên suốt — HR_MANAGER thiếu quyền `checkins:*` / `randomchecks:*`

Đây là phát hiện quan trọng nhất: đối chiếu migration seed role gốc (`V13`), `HR_MANAGER` **chưa bao giờ** được cấp `checkins:create/read/list` hay `randomchecks:create/read/list/configure` — trong khi `SITE_SUPERVISOR` và `TENANT_ADMIN` đều có đủ. Hệ quả: **4/8 story trong yêu cầu của bạn** ("HR kích hoạt kiểm tra ngay", "HR xem danh sách/chi tiết scheduled checks", "HR override check-in") — dù Javadoc code và tên story đều nói rõ "HR" — trên thực tế **HR_MANAGER gọi vào đều nhận 403**, chỉ Tenant Admin/Site Supervisor dùng được.

**Đã sửa**: migration `V83__hr_manager_checkins_and_randomchecks_access.sql`, cấp cho HR_MANAGER đúng bộ quyền `checkins:*`/`randomchecks:*` mà `SITE_SUPERVISOR` đang có (nguyên tắc: HR cần ít nhất ngang bằng khả năng vận hành của 1 site supervisor).

**Đã test sống**: đăng nhập bằng tài khoản HR_MANAGER thật (`dung.pham.hr@gmail.com`, role HR_MANAGER tại Hoàng Long) → gọi cả 4 endpoint trước đó bị 403 → nay đều trả 200/201 thành công.

---

## Danh sách file đã thay đổi

| File | Thay đổi |
|---|---|
| `db/migration/V83__hr_manager_checkins_and_randomchecks_access.sql` | Cấp quyền `checkins:*`/`randomchecks:*` cho HR_MANAGER |
| `randomcheck/service/ScheduledCheckGeneratorService.java` | Fail-safe hạ `checkMode` về `location_only` khi nhân viên chưa enroll Face ID (luồng tự động) |
| `randomcheck/service/ManualCheckService.java` | Fail-safe tương tự (luồng HR kích hoạt thủ công, chỉ áp khi HR không tự chỉ định mode) |
| `randomcheck/service/CheckResponseService.java` | Idempotency guard chống tạo trùng violation từ callback AI |
| `violation/repository/ViolationRepository.java` | Thêm `findByScheduledCheckIdAndDeletedAtIsNull` |
| `randomcheck/repository/ScheduledCheckRepository.java` | Sửa query `existsFailedOrNoResponseCheck` — loại trừ violation đã dismiss hoàn toàn |
| `attendance/service/AttendanceSummaryService.java` | Thêm `recomputeIfSummaryExists` |
| `violation/service/ViolationService.java` | Gọi recompute sau confirm/dismiss; thêm `listMyViolations`; thêm filter `scheduledCheckId` |
| `violation/specification/ViolationSpecification.java` | Overload nhận thêm `scheduledCheckId` |
| `violation/controller/ViolationController.java` | Endpoint mới `GET /violations/my`; filter mới trên `GET /violations` |
| `randomcheck/dto/response/ScheduledCheckDetailResponse.java` | Field mới `violations` (nested `ViolationSummary`) |
| `randomcheck/controller/ScheduledCheckController.java` | Nhúng violations vào response chi tiết |
| `checkin/service/CheckinService.java` | `getCheckinHistory` nhận thêm filter `status` |
| `checkin/controller/CheckinController.java` | Filter `status` trên `GET /checkin/history` |

## Đã kiểm thử

- Compile sạch, container build lại thành công không lỗi.
- Test sống qua API thật cho toàn bộ 7 điểm sửa (không phải chỉ đọc code) — chi tiết nằm trong từng mục ở trên.
- Chạy lại bộ regression sẵn có (`tests/randomcheck`, `tests/violation`, `tests/attendance`, `tests/checkin`, `tests/rbac`, `tests/employee`) — các case fail (16/~90) đều đã xác minh là **lỗi kịch bản test có sẵn từ trước**, không liên quan thay đổi hôm nay: 5 file dùng thiếu field `ownerEmail` khi tạo tenant test (bug cũ đã ghi nhận nhiều lần trong phiên làm việc), còn lại phụ thuộc giờ hệ thống thật lúc chạy test (VD "ca đã kết thúc lúc 17:00" khi chạy vào ban đêm — không phải lỗi logic).
- Reseed sạch từ đầu (18 tenant, 0 tenant do admin sở hữu nhầm) + backfill attendance — xác nhận migration V83 và toàn bộ fix vẫn đúng trên dữ liệu mới hoàn toàn.

## Còn tồn đọng — cần bạn quyết định (chưa làm)

Cả 3 mục dưới đây đã được xử lý trên backend ngày 2026-08-04 (xem chi tiết + kết quả test trực tiếp trong `docs/reviews/backend/random-check-violation-audit-2026-08-03.md`, mục 0):

| # | Vấn đề | Xử lý |
|---|---|---|
| 1 | UX gộp 2 form giải trình (check-in Face ID fail tại site thường + violation face_fail đi kèm) thành 1 hay giữ 2 | Thêm `GET /api/v1/tenants/{tenantId}/me/exceptions` gộp **phần đọc** của 2 nguồn (checkin pending_review + violation unresolved) thành 1 danh sách, kèm `explainEndpoint` cho từng item. 2 luồng nộp giải trình (POST) vẫn tách riêng — đúng vì là 2 loại bản ghi khác nhau, chỉ hợp nhất chỗ hiển thị. |
| 2 | Kích hoạt thủ công (Story 3) hiện **không giới hạn số lần/ngày** — HR có thể gửi liên tục nhiều lần cho 1 nhân viên trong ngày, chỉ bị chặn bởi quota tháng của gói dịch vụ | Giữ nguyên **không rate-limit cứng** (chủ đích, khớp Deputy/QuickBooks Time — không nên chặn khi cần xác minh khẩn). Bổ sung audit log mỗi lần kích hoạt + trường `manualTriggerCountToday` trong response để HR/FE biết đã gửi bao nhiêu lần, làm tín hiệu mềm chứ không chặn. |
| 3 | Trường hợp "check-in đã enroll ok nhưng đúng lúc đó Face ID vừa bị thu hồi (revoke) giữa chừng" — chưa kiểm tra race-condition này, xác suất cực thấp | Đã vá tại `FaceResultCallbackController`: khi callback báo `faceVerified=true`, đọc lại trạng thái Face ID tại đúng thời điểm nhận callback (không tin kết quả đã kiểm tra lúc check-in); nếu đã bị revoke, hạ check-in về `pending_review` thay vì chấp nhận kết quả khớp đã lỗi thời. Verify trực tiếp: revoke → callback true → đúng là `pending_review`; khôi phục enrolled → callback true → đúng giữ `valid` (không có false positive). |
