# Tài liệu tích hợp Quản lý Ca làm việc (Shift) & Phân công (Assignment) — Review, sửa lỗi, bổ sung và API tham chiếu

> Cập nhật theo code đang chạy ngày 27/07/2026 (bản vá payroll/audit thứ 2 trong ngày). Base path: `/api/v1/tenants/{tenantId}/sites/{siteId}/shifts` và `/api/v1/tenants/{tenantId}/sites/{siteId}/assignments`.

## 0.1 [MỚI] Bản vá theo phản hồi FE — 2 lỗi P0 trước khi dùng cho payroll/audit

Sau khi FE/App review lại đợt sửa đầu tiên (mục 0-4 dưới đây), bạn chuyển tiếp 2 vấn đề ưu tiên cao. Cả 2 đã được xử lý:

| # | Vấn đề FE nêu | Quyết định | Đã làm |
|---|---|---|---|
| P0-1 | Sửa trực tiếp giờ ca đã dùng có thể khiến lịch sử cũ bị hiểu theo giờ mới | **Snapshot** giờ ca vào từng lượt chấm công tại thời điểm check-in (không chọn "khóa sửa + clone" hay "version theo thời gian") | Mục 2.5 |
| P0-2 | Kiểm tra xung đột chỉ theo ngày, chưa xét giờ ca — chặn nhầm "sáng Site A, tối Site B" | **Cho phép nhiều site cùng ngày**, kiểm tra interval giờ thực tế + timezone + ca qua đêm (không chọn luật "mỗi ngày 1 site") | Mục 2.6 |
| P1 | `employeeSummary`/`shiftSummary` trong Assignment; `canDelete`/`assignmentHistoryCount` trong Shift | Đã làm cả 2 | Mục 3.1-3.2 (cập nhật) |
| — | Endpoint Assignment toàn tenant | Chưa làm — xem đánh giá ở mục 2.7 | — |

**Kết quả test bổ sung**: build lại, test sống toàn bộ luồng check-in → sửa giờ ca → check-out → xác nhận `workMinutes` vẫn tính theo giờ ca lúc check-in (không bị giờ mới ảnh hưởng); test sống case "sáng Site A 06:00-12:00, tối Site B 18:00-22:00 cùng ngày" → nay được phép (`201`, trước đây sẽ bị `409` nhầm); test lại case chồng giờ thật (Site A 06:00-14:00, Site B 13:00-20:00 cùng ngày) → vẫn đúng bị chặn `409`. Chạy lại toàn bộ `tests/site/*.sh` (189 test) + `tests/report/*.sh` (67 test, vì đụng vào `AttendanceSummaryService`) + `tests/rbac/tests/workspace/tests/tenant/tests/employee` — **100% pass**.

## 0.2 [MỚI] Bản vá theo báo cáo App team — 4 lỗi P0 + 4 lỗi P1 (27/07/2026)

App team gửi báo cáo `17_BAO_CAO_APP_CA_LAM_VIEC_VA_PHAN_CONG_2026-07-27.md` sau khi đọc trực tiếp code backend (không sửa backend), liệt kê 8 vấn đề. Đã xác minh từng vấn đề với code thật trước khi sửa — cả 8 đều có thật:

| # | Vấn đề App nêu | Đã sửa | Chi tiết |
|---|---|---|---|
| P0-1 | Nhân viên `terminated`/`inactive` vẫn xem được available-sites và chấm công được | Có | `getAvailableSites` trả về `[]`; `submitCheckin` chặn `403 EMPLOYEE_NOT_ACTIVE` nếu `employee.status != 'active'` |
| P0-2 | Site `inactive` vẫn nhận assignment mới và vẫn chấm công được | Có | `createAssignment`/`updateAssignment` chặn `400`; `submitCheckin` chặn `422 SITE_INACTIVE` nếu `site.status != 'active'` |
| P0-3 | Check trùng chấm công chỉ theo `assignmentId` — nhân viên có 2 assignment (2 site) có thể mở 2 phiên chấm công song song | Có | Đổi sang check theo `(tenantId, employeeId)` — bất kỳ phiên mở nào (site nào) cũng chặn phiên mới. Thêm unique index DB (`V73`) chống race condition khi 2 request gửi gần như đồng thời |
| P0-4 | `getAvailableSites`/`submitCheckin` dùng `LocalDate.now()` theo giờ server, không theo giờ site; ca qua đêm bị lệch ngày | Có | Cả hai dùng logic chung `AssignmentService.resolveAvailableAssignmentsNow/resolveAvailableAssignmentNow` — tính theo giờ Việt Nam, xử lý đúng ca qua đêm và submit theo chính xác `assignmentId` đã chọn |
| P1-1 | Tạo/sửa Shift không kiểm tra `startTime`/`endTime`/`allowOvernight` hợp lệ với nhau | Có | `ShiftService.validateShiftTimes`: ca trong ngày bắt buộc `startTime < endTime`; ca qua đêm bắt buộc 2 giờ khác nhau |
| P1-2 | `validateNotTooEarly` chỉ chặn chấm công quá sớm, không chặn chấm công sau khi ca đã kết thúc | Có | Đổi thành `validateCheckinWindow` — chặn cả 2 chiều, lỗi mới `422 CHECKIN_TOO_LATE` |
| P1-3 | `timezone` của Site chỉ giới hạn độ dài, không kiểm tra là IANA zone hợp lệ → lỗi 500 khi chấm công thay vì 400 khi tạo site | Có | `SiteService.validateTimezone` dùng `ZoneId.of()`, trả `400` ngay lúc tạo/sửa site |
| P1-4/§5 | Đề xuất thêm `serverNow`/`checkinAllowedFrom`/`checkinAllowedUntil`/`availabilityStatus` vào `AvailableSiteResponse` | Có | 4 field mới, `availabilityStatus` ∈ `unrestricted\|upcoming\|open\|closed` — chỉ là gợi ý UX, backend vẫn validate lại khi submit |

**Quyết định kiến trúc đáng chú ý:**
- P0-3 + P0-4 dùng chung một cơ chế: `AssignmentService.AssignmentAvailability` (record mới) là nguồn sự thật duy nhất cho "assignment này có hợp lệ ngay bây giờ không", dùng chung bởi cả `getAvailableSites` và `submitCheckin` — App không bao giờ thấy 1 site "available" rồi bị `submitCheckin` từ chối vì lệch logic.
- Nhân viên `inactive`/`terminated`: theo đúng pattern "deactivation chặn hành động MỚI, không xoá lịch sử" đã dùng xuyên suốt dự án — `getAvailableSites`/`submitCheckin` bị chặn, nhưng `submitCheckout` (hoàn tất phiên đang mở), `getCheckinHistory`, `getCheckinResult`, `explainCheckin` KHÔNG bị chặn.
- P0-3: chọn chặn theo **employee** (không theo site) vì một người không thể có mặt vật lý ở 2 nơi cùng lúc — kể cả khi 2 assignment không xung đột giờ theo lịch (ví dụ sáng Site A, tối Site B), nếu quên checkout ở Site A thì vẫn không được checkin Site B.

**Test sống đã chạy trên dữ liệu seed thật** (tenant `beta-industries`, các API thật qua `curl`, dọn dẹp toàn bộ dữ liệu test sau khi xong):
- Nhân viên `terminated` → `available-sites` trả `[]`, `submitCheckin` → `403 EMPLOYEE_NOT_ACTIVE`.
- Chấm công sau khi ca đã kết thúc (giờ site) → `422 CHECKIN_TOO_LATE` với đúng giờ kết thúc ca theo timezone site.
- Site `inactive` → `submitCheckin` → `422 SITE_INACTIVE`; tạo assignment mới tại site đó → `400`.
- Nhân viên có phiên chấm công đang mở ở Site A, thử chấm công ở Site B (assignment khác, không xung đột giờ) → `409 DUPLICATE_RESOURCE`, đúng site/giờ của phiên đang mở.
- Tạo shift `startTime == endTime` (không qua đêm) → `400`; `startTime > endTime` không qua đêm → `400`.
- Tạo site với `timezone: "Not/AZone"` → `400` ngay, không đợi tới lúc chấm công mới lỗi 500.

Migration `V73__prevent_concurrent_open_checkins_per_employee.sql` — unique partial index `(employee_id) WHERE check_out_at IS NULL AND deleted_at IS NULL`. Chạy lại `tests/site/*.sh` (189 test) + `tests/report/*.sh` (67 test) sau khi sửa — **100% pass**. `tests/checkin/*.sh` có lỗi kịch bản test từ trước (dùng field `email` thay vì `identifier` khi login nhân viên ở dòng setup — không liên quan tới các thay đổi lần này, đã ghi nhận nhưng chưa sửa vì ngoài phạm vi báo cáo App).

## 0. Tóm tắt kết quả (đợt review đầu — vẫn giữ nguyên, xem mục 0.1/0.2 ở trên cho các bản vá mới nhất)

**9 tính năng bạn liệt kê đã được xây dựng đầy đủ, đúng nghiệp vụ từ trước** — tạo/danh sách/cập nhật/deactivate ca làm việc, cấu hình OT, tạo/danh sách/cập nhật/hủy phân công, và "site được phép check-in hôm nay" (App) đều có API thật, hoạt động tốt. Qua review sâu vào business logic (đúng như bạn yêu cầu — không chỉ kiểm tra API có chạy hay không, mà kiểm tra dữ liệu có **mạch lạc, không xung đột**), tôi tìm thấy và sửa **3 lỗ hổng xung đột dữ liệu thật** (nhân viên có thể bị phân công "có mặt ở 2 công trình cùng lúc"; assignment vẫn gắn được vào 1 ca đã deactivate; nhân viên đã nghỉ việc vẫn được phân công mới), và hoàn thiện **1 tính năng bỏ dở** (`shifts:delete` seed sẵn nhưng chưa có endpoint).

| # | Tính năng bạn yêu cầu | Trạng thái trước | Việc đã làm |
|---|---|---|---|
| 1 | Tạo ca làm việc | ✅ Đã có, đúng nghiệp vụ | Xác nhận lại, không cần sửa |
| 2 | Cấu hình OT (`allowOvertime`/`earlyCheckinMinutes`/`lateCheckoutMinutes`) | ✅ Đã có, đúng nghiệp vụ, partial-update đúng chuẩn | Xác nhận lại, không cần sửa |
| 3 | Danh sách ca theo site | ✅ Đã có, đúng nghiệp vụ | Xác nhận lại, không cần sửa |
| 4 | Cập nhật/ngừng dùng ca (không mất lịch sử) | ✅ Đã có — deactivate qua `status`, không xóa | **Đã bổ sung** — thêm hẳn `DELETE` thật cho trường hợp ca chưa từng dùng, mục 2.4 |
| 5 | Tạo phân công nhân viên vào site | ✅ Đã có, đúng nghiệp vụ | **Đã sửa 3 lỗ hổng xung đột dữ liệu** — mục 2.1-2.3 |
| 6 | Danh sách phân công (tìm/lọc/sort/phân trang) | ✅ Đã có, đúng nghiệp vụ | Xác nhận lại, không cần sửa |
| 7 | Cập nhật phân công | ✅ Đã có, đúng nghiệp vụ | **Đã sửa cùng 2 trong 3 lỗ hổng trên** (ca inactive + xung đột site) — mục 2.1-2.2 |
| 8 | Hủy phân công | ✅ Đã có — là chuyển trạng thái `cancelled`, không xóa, tự động hủy luôn các random-check đang chờ gắn với phân công đó | Xác nhận lại, không cần sửa |
| 9 | Hiển thị site được phép check-in (App) | ✅ Đã có, response đã gộp sẵn site+ca+geofence trong 1 lần gọi | Xác nhận lại, không cần sửa |

**Kết quả test**: build lại, test sống từng lỗi đã sửa, chạy lại toàn bộ `tests/site/*.sh` (16 file, 189 test — bao gồm mọi test Shift/Assignment) + `tests/workspace/*.sh` (65) + `tests/rbac/*.sh` (100) + `tests/tenant/*.sh` (91) + `tests/employee/*.sh` (96) — **100% pass**, không hồi quy.

## 1. Trả lời câu hỏi nghiệp vụ: dữ liệu có liên kết mạch lạc với các module trước không?

```
Employee (đã review) ──┐
                        ├─→ Assignment (site + employee + shift + thời gian + vai trò) ──→ quyết định
Site (đã review) ───────┤                                                                  "site nào cho phép
                        │                                                                   check-in hôm nay"
Shift (site này) ───────┘                                                                   (mục 4 API check-in)

Workspace/Department (đã review) ──╳── KHÔNG liên kết trực tiếp với Assignment
```

**Xác nhận đúng thiết kế, không cần sửa**: `Assignment` là đúng "trục nối" duy nhất giữa Employee/Site/Shift — hoàn toàn tách biệt khỏi `Workspace`/`workspace_members` (không có FK, không tham chiếu chéo, xác nhận qua code). Điều này khớp với kết luận đợt review Site trước: 1 nhân viên có thể vừa có `Assignment` tại "Công trình A" theo ca "Sáng", vừa là thành viên "Phòng Điện" trong `Workspace` — 2 sự thật độc lập, đúng thực tế các hệ thống Deputy/Connecteam vẫn thiết kế.

**Điểm cần lưu ý cho FE**: `Assignment.shiftId` là optional — 1 nhân viên có thể được phân công vào site mà KHÔNG gắn ca cụ thể nào (làm việc "cả ngày", không giới hạn giờ). Khi hiển thị, cần phân biệt rõ "chưa chọn ca" (shiftId null, hợp lệ) với lỗi thiếu dữ liệu.

## 2. Chi tiết các lỗ hổng xung đột dữ liệu đã tìm và sửa

### 2.1 [Đã sửa — xung đột dữ liệu nghiêm trọng nhất] 1 nhân viên có thể "có mặt ở 2 công trình cùng lúc"

**Trước khi sửa**: hệ thống chỉ chặn được **trùng lặp tại CÙNG 1 site** (unique constraint `employee+site+status=active`). Không có gì ngăn: phân công nhân viên A vào Công trình 1 (thứ 2-6, không giới hạn ngày kết thúc), rồi phân công CHÍNH nhân viên A đó vào Công trình 2 (cũng thứ 2-6, cùng khoảng thời gian) — cả 2 phân công đều `active`, và API "site được phép check-in hôm nay" (tính năng 9 bạn yêu cầu) sẽ hiển thị **CẢ HAI** công trình cho nhân viên đó cùng lúc — về mặt vật lý vô lý (1 người không thể ở 2 công trình cùng giờ), và tạo lỗ hổng gian lận chấm công thật (nhân viên chọn công trình gần nhà để "chấm công hộ" dù lịch thật ở nơi khác).

**Đã sửa**: thêm kiểm tra chồng chéo — khi tạo HOẶC sửa 1 phân công đang active, hệ thống tìm mọi phân công active KHÁC của cùng nhân viên tại site KHÁC có khoảng ngày (`startDate`/`endDate`) và ngày-trong-tuần (`daysOfWeek`) giao nhau → chặn với thông báo rõ ràng, nêu tên site đang xung đột.

```
Ví dụ chặn thật: nhân viên đã có phân công active tại "Site A" (2026-01-01 → không giới hạn, mọi ngày).
Thử phân công thêm vào "Site B" (bắt đầu 2026-06-01) → 409:
"Employee already has an overlapping active assignment at site 'Site A' during this period —
an employee cannot be at two sites at once"
```

**Không chặn nhầm các trường hợp hợp lệ thật**: nếu 2 phân công có `daysOfWeek` KHÔNG giao nhau (ví dụ Site A chỉ thứ 2-3, Site B chỉ thứ 4-6) hoặc khoảng ngày không giao nhau (Site A kết thúc trước khi Site B bắt đầu) — vẫn tạo được bình thường, đúng thực tế "nhân viên luân chuyển nhiều công trình theo lịch rõ ràng, không trùng giờ".

### 2.2 [Đã sửa — xung đột dữ liệu] Phân công vẫn gắn được vào 1 ca đã deactivate

**Trước khi sửa**: `updateShift` cho phép đặt `status='inactive'` (đúng ý bạn — "sửa hoặc deactivate để thay đổi giờ làm không mất lịch sử"), nhưng **`createAssignment`/`updateAssignment` không hề kiểm tra trạng thái ca** khi gắn `shiftId` — vẫn có thể tạo/sửa 1 phân công MỚI trỏ vào ca đã ngừng dùng, khiến việc "deactivate" mất hết ý nghĩa (ca coi như ngừng dùng nhưng vẫn bị gán cho người mới).

**Đã sửa**: áp dụng đúng nguyên tắc đã dùng nhất quán xuyên suốt hệ thống (Role/Workspace/Site đợt trước) — **"deactivate chặn sử dụng MỚI, không ảnh hưởng người đang giữ"**: `createAssignment` và `updateAssignment` giờ chặn nếu `shiftId` được gắn trỏ tới 1 ca có `status != 'active'`, trả lỗi rõ tên ca. Phân công đã tồn tại trước đó vẫn giữ nguyên `shiftId` cũ dù ca đã bị deactivate (không bị hủy/ảnh hưởng ngược).

### 2.3 [Đã sửa — xung đột dữ liệu] Nhân viên đã nghỉ việc (`terminated`) vẫn được phân công mới

**Trước khi sửa**: `createAssignment` chỉ kiểm tra nhân viên có tồn tại (chưa xóa mềm), **không kiểm tra `status`** — 1 nhân viên đã đổi trạng thái `terminated` (đã nghỉ việc, tính năng "Tạm ngừng/nghỉ việc nhân viên" đã review đợt trước) vẫn có thể được phân công vào site mới, dữ liệu vô lý (người đã nghỉ việc sao còn lịch làm việc mới).

**Đã sửa**: chặn tạo phân công mới nếu `employee.status == "terminated"`, trả lỗi rõ ràng. **Cố ý KHÔNG chặn** nếu employee đang `inactive` (tạm ngừng, có thể quay lại) — chỉ chặn `terminated` (nghỉ hẳn) — phân biệt đúng 2 trạng thái đã thiết lập ở module Employee. Cũng **cố ý không tự động hủy các phân công CŨ** khi nhân viên chuyển sang terminated (đó là quyết định sản phẩm riêng — có thể muốn giữ lịch sử phân công để đối chiếu công nợ/chấm công cũ — không đụng vào, chỉ chặn tạo MỚI).

### 2.4 [Đã bổ sung — hoàn thiện tính năng bỏ dở] `DELETE /shifts/{id}`

**Phát hiện khi review**: `shifts:delete` đã seed cho các role liên quan từ đầu nhưng chưa từng có endpoint dùng tới — cùng loại "tính năng ma" đã gặp ở Site/Workspace đợt trước.

**Khác với Site/Workspace** (nơi tôi đã thêm xóa mềm chặn theo "còn tham chiếu active"), Shift có yêu cầu nghiệp vụ **chặt hơn** do chính bạn đã nêu rõ: *"sửa hoặc deactivate shift template để thay đổi giờ làm không mất lịch sử"* — nghĩa là xóa hẳn 1 ca **không được phép** nếu có bất kỳ phân công nào (kể cả đã hủy/lịch sử cũ) từng dùng nó, vì xóa sẽ làm mất khả năng tra cứu "nhân viên X từng làm ca nào" trong lịch sử. Vì vậy:

- `DELETE /shifts/{id}` giờ chỉ cho phép xóa khi ca **CHƯA TỪNG** được gán vào bất kỳ phân công nào (kiểm tra không phân biệt active/cancelled) — dùng cho trường hợp tạo nhầm ca chưa ai dùng.
- Nếu ca đã từng có ít nhất 1 phân công (dù đã hủy) → `400`, gợi ý dùng "deactivate" (`status=inactive`) thay vì xóa — đúng tinh thần "không mất lịch sử" bạn yêu cầu.

Test sống xác nhận: tạo ca chưa dùng → xóa → `200`; tạo ca, gán vào 1 phân công thật → xóa → `400` "has been used by at least one assignment... use deactivate instead".

### 2.5 [P0 — MỚI] Snapshot giờ ca vào từng lượt chấm công — bảo vệ dữ liệu payroll/audit

**Vấn đề FE nêu**: sửa trực tiếp `startTime`/`endTime` của 1 ca đã có Assignment/Checkin có thể khiến lịch sử cũ bị "hiểu lại" theo giờ mới.

**Đã kiểm chứng chính xác nơi xảy ra**: không phải MỌI tính toán đều bị ảnh hưởng — `CheckinRecord.workMinutes` vốn đã tính-và-lưu-1-lần lúc check-out (immutable), và `ReportService` (báo cáo ngày/tháng) chỉ đọc lại `AttendanceSummary` đã lưu, không join sống sang `Shift`. **Nhưng có đúng 1 đường dữ liệu thật sự bị lộ**: `AttendanceSummaryService.recompute()` — hàm tính late/early/OT — trước đây lấy giờ ca bằng cách **fetch sống** từ bảng `shifts` mỗi lần chạy. Hàm này bị gọi lại cho 1 ngày CŨ bất kỳ khi HR dùng tính năng "Sửa chấm công" (override check-in, đã review ở đợt trước) — nghĩa là: sửa giờ ca hôm nay + HR override 1 checkin tháng trước → `late`/`earlyLeave`/`otMinutes` của tháng trước bị tính lại **bằng giờ ca MỚI**, sai lệch báo cáo đã chốt.

**Đã chọn phương án Snapshot** (không chọn "khóa sửa + clone ca mới" như đề xuất ban đầu của bạn, cũng không chọn "version ca theo thời gian hiệu lực") — lý do:
- **Version theo thời gian** đòi hỏi thêm hẳn 1 bảng `shift_versions` + logic "tra đúng phiên bản có hiệu lực tại ngày X" ở MỌI nơi đang đọc giờ ca — chi phí kỹ thuật lớn cho vấn đề mà Snapshot đã giải quyết trọn vẹn với chi phí thấp hơn nhiều.
- **Khóa sửa + clone** giải quyết được phần `startTime`/`endTime`, nhưng KHÔNG giải quyết được phần OT-config (`allowOvertime`/`lateCheckoutMinutes`) — nếu khóa cả OT-config thì HR không còn cách nào điều chỉnh chính sách OT cho ca đang dùng mà không phải tạo ca mới, đi ngược lại đúng mục đích tồn tại của endpoint `PUT .../ot-config` (vốn được thiết kế để tinh chỉnh chính sách OT theo thời gian, áp dụng về sau). Snapshot giải quyết cả 2 vế cùng lúc mà KHÔNG cần hạn chế quyền sửa ca.

**Đã làm** (migration `V72__snapshot_shift_times_on_checkin.sql`):
- Thêm 5 cột vào bảng `checkins`: `shift_start_time`, `shift_end_time`, `shift_allow_overnight`, `shift_allow_overtime`, `shift_late_checkout_minutes` — ghi đúng 1 lần tại thời điểm check-in (khi ca được xác định lần đầu để validate check-in sớm), **không bao giờ ghi đè lại**.
- `CheckinService.computeWorkMinutes` (tính giờ công lúc check-out) giờ đọc từ 5 cột snapshot này trên chính bản ghi checkin, **không còn fetch `Shift` sống**.
- `AttendanceSummaryService.recompute()` (tính late/early/OT) giờ lấy giờ ca từ snapshot của các lượt chấm công trong ngày đó, **đã bỏ hẳn dependency `ShiftRepository`** khỏi class này — về mặt kiến trúc, class tính báo cáo giờ không còn khả năng đọc bảng `shifts` nữa, đóng đường rò rỉ tận gốc thay vì chỉ vá triệu chứng.
- Sửa `Shift` tự do không còn rủi ro gì với dữ liệu lịch sử — HR có thể đổi giờ ca/chính sách OT bất cứ lúc nào, chỉ áp dụng cho các lượt chấm công MỚI từ sau thời điểm sửa.

**Test sống xác nhận** (luồng đầy đủ): tạo ca 08:00-17:00 → nhân viên check-in (snapshot ghi 08:00/17:00) → HR sửa ca thành 10:00-20:00 → xác nhận bản ghi checkin cũ **vẫn giữ nguyên** snapshot 08:00/17:00 trong DB → nhân viên check-out → `workMinutes` tính đúng theo giờ **cũ** (08:00-17:00), hoàn toàn không bị ảnh hưởng bởi lần sửa ca ở giữa. Chạy lại `tests/report/*.sh` (67 test, bộ test duy nhất phụ thuộc `AttendanceSummaryService`) — 100% pass, không hồi quy.

### 2.6 [P0 — MỚI] Kiểm tra xung đột theo giờ ca thực tế (không chỉ theo ngày)

**Vấn đề FE nêu**: kiểm tra xung đột đa-site (mục 2.1) trước đó chỉ xét `startDate`/`endDate` + `daysOfWeek` — chưa xét giờ ca cụ thể, nên sẽ chặn NHẦM trường hợp hợp lệ: nhân viên làm Site A buổi sáng và Site B buổi tối cùng ngày.

**Đã chọn phương án "cho phép nhiều site cùng ngày, kiểm tra interval giờ"** (không chọn luật cứng "mỗi ngày chỉ 1 site") — vì đây là nhu cầu thực tế phổ biến trong ngành xây dựng/dịch vụ đa điểm (giám sát viên ghé nhiều công trình trong ngày, nhân viên bán thời gian làm ca sáng chỗ này ca tối chỗ khác), và các hệ thống tham chiếu (Deputy) cũng kiểm tra chồng chéo theo GIỜ THỰC TẾ chứ không khóa cứng theo ngày.

**Thuật toán** (`AssignmentService.assertNoCrossSiteConflict`, chạy khi tạo HOẶC sửa 1 phân công đang active):
1. Lọc thô bằng SQL (như cũ): tìm các phân công active khác của cùng nhân viên, site khác, có `startDate`/`endDate` và `daysOfWeek` (bitmask) giao nhau về mặt lịch — đây là điều kiện CẦN nhưng chưa đủ.
2. Với mỗi kết quả lọc thô, tính khoảng ngày giao nhau thực tế, và với từng thứ-trong-tuần được cả 2 bên cho phép, tìm 1 ngày cụ thể đại diện nằm trong khoảng giao đó.
3. Tại ngày đại diện đó, quy đổi khung giờ ca của TỪNG bên sang UTC (theo đúng timezone của SITE tương ứng, xử lý cả ca qua đêm bằng cách cộng thêm 1 ngày vào giờ kết thúc) — rồi so sánh 2 khoảng UTC có giao nhau hay không.
4. Nếu có bất kỳ ngày nào cho kết quả giao nhau về giờ → chặn `409`, nêu rõ tên site xung đột. Nếu không có ngày nào giao nhau về giờ → **cho phép tạo/sửa bình thường**.
5. Phân công không gắn ca cụ thể (`shiftId = null`) được coi là chiếm trọn ngày hôm đó (00:00-24:00 theo giờ địa phương của site) — hợp lý vì không có thông tin giờ cụ thể để so sánh hẹp hơn.

**Giới hạn đã biết, chấp nhận được ở quy mô hiện tại**: không xử lý chuyển giờ mùa (DST) — không ảnh hưởng thực tế vì các múi giờ Việt Nam hệ thống đang dùng (`Asia/Ho_Chi_Minh` v.v.) không áp dụng DST.

**Test sống xác nhận**: Site A ca 06:00-12:00 + Site B ca 18:00-22:00, cùng nhân viên, cùng ngày → **`201` cả 2** (trước đây sẽ bị chặn nhầm `409`); Site A ca 06:00-14:00 + Site B ca 13:00-20:00 (chồng 1 giờ), cùng nhân viên, cùng ngày → vẫn đúng bị chặn `409` "the shift hours overlap".

### 2.7 Endpoint Assignment toàn tenant — đánh giá, chưa làm

FE đề xuất thêm 1 endpoint liệt kê Assignment trên toàn bộ tenant (không giới hạn theo 1 site) cho màn điều phối nhiều công trình cùng lúc. Đánh giá: **hợp lý về nghiệp vụ, nhưng chưa cấp thiết** — hiện `GET .../sites/{siteId}/assignments` đã đủ cho toàn bộ 9 tính năng bạn yêu cầu (đều thao tác trong phạm vi 1 site cụ thể). Nếu FE có nhu cầu thật cho 1 màn "điều phối tổng" (ví dụ dashboard xem tất cả phân công của cả công ty trong 1 bảng), đây là việc nhỏ (thêm `GET /api/v1/tenants/{tenantId}/assignments` dùng lại `AssignmentSpecification` đã có, bỏ điều kiện `siteId`, thêm site-scope filter như đã làm cho Employee list) — báo lại khi cần, chưa triển khai vì chưa có màn hình cụ thể nào trong 9 tính năng yêu cầu nó.

## 3. API tham chiếu đầy đủ

### 3.1 Shift — base path `/api/v1/tenants/{tenantId}/sites/{siteId}/shifts`

| # | Endpoint | Method | Quyền cần | Mô tả |
|---|---|---|---|---|
| 1 | `/` | POST | `shifts:create` | Tạo ca, tên duy nhất trong site |
| 2 | `/` | GET | `shifts:list` | Danh sách phân trang, filter `status`, sort cố định theo `startTime` tăng dần |
| 3 | `/{id}` | PUT | `shifts:update` | Sửa từng phần — `status=inactive` để ngừng dùng (không mất lịch sử) |
| 4 | `/{id}/ot-config` | PUT | `shifts:update` | Cấu hình riêng OT (partial update, tách khỏi update thường) |
| 5 | `/{id}` | DELETE | `shifts:delete` | **Mới** — chỉ xóa được nếu ca chưa từng dùng trong bất kỳ phân công nào |

**`CreateShiftRequest`**:
```json
{
  "name": "Morning Shift",   // bắt buộc, tối đa 100 ký tự, unique trong site
  "startTime": "08:00",      // bắt buộc, định dạng HH:mm
  "endTime": "17:00",        // bắt buộc, định dạng HH:mm
  "allowOvernight": false    // optional, mặc định false — true nếu endTime rơi sang ngày hôm sau
}
```

**`UpdateShiftRequest`** (mọi field optional):
```json
{
  "name": "...", "startTime": "07:00", "endTime": "16:00",
  "allowOvernight": true,
  "status": "inactive"   // "active" | "inactive" — deactivate ở đây
}
```

**`ConfigureShiftOtRequest`** (ít nhất 1 field, tách khỏi update thường để phân quyền/audit riêng nếu cần sau này):
```json
{
  "allowOvertime": true,
  "earlyCheckinMinutes": 15,       // >= 0 — cho phép check-in sớm hơn startTime bao nhiêu phút
  "lateCheckoutMinutes": 30,       // >= 0 — cho phép check-out muộn hơn endTime bao nhiêu phút
  "maxOtMinutesPerDay": 120,       // MỚI (2026-08-07, #60) — >= 0, omit = giữ nguyên. Chỉ CẢNH BÁO
                                    //   (set AttendanceSummary.otDailyLimitExceeded), KHÔNG chặn checkout,
                                    //   KHÔNG cap otMinutes thực tế. null = không giới hạn.
  "clearMaxOtMinutesPerDay": false, // MỚI — true để xoá giới hạn ngày (về unlimited); bị bỏ qua nếu maxOtMinutesPerDay cũng có giá trị
  "maxOtMinutesPerWeek": 600,      // MỚI — cùng cơ chế cảnh báo, tính theo ISO week (Thứ 2 - CN), theo employee (không theo site)
  "clearMaxOtMinutesPerWeek": false // MỚI — tương tự clearMaxOtMinutesPerDay
}
```

**`ShiftResponse`** (đầy đủ, **4 field cuối mới bổ sung**):
```json
{
  "id": "uuid", "siteId": "uuid", "tenantId": "uuid", "name": "Morning Shift",
  "startTime": "08:00:00", "endTime": "17:00:00",
  "allowOvernight": false, "allowOvertime": true,
  "earlyCheckinMinutes": 15, "lateCheckoutMinutes": 30,
  "maxOtMinutesPerDay": 120, "maxOtMinutesPerWeek": 600,  // MỚI (2026-08-07, #60) — null = không giới hạn
  "status": "active", "createdBy": "uuid", "createdAt": "...", "updatedAt": "...",
  "assignmentHistoryCount": 3,   // MỚI — số phân công (active hoặc đã hủy) từng dùng ca này
  "canDelete": false             // MỚI — = (assignmentHistoryCount == 0); dùng để bật/tắt nút Xóa mà không cần thử-và-lỗi
}
```

### 3.2 Assignment — base path `/api/v1/tenants/{tenantId}/sites/{siteId}/assignments`

| # | Endpoint | Method | Quyền cần | Mô tả |
|---|---|---|---|---|
| 1 | `/` | POST | `assignments:create` | Tạo phân công — **giờ chặn cả xung đột đa-site + ca inactive + nhân viên terminated (mới)** |
| 2 | `/` | GET | `assignments:list` | Danh sách phân trang, filter `status`/`role`/`employeeId`/`shiftId`, sort `startDate`\|`endDate`\|`role`\|`status`\|`createdAt` |
| 3 | `/{id}` | PUT | `assignments:update` | Sửa ca/thời gian/vai trò/ghi chú — **giờ chặn cùng 2 lỗi trên (ca inactive + xung đột đa-site)** |
| 4 | `/{id}` | DELETE | `assignments:delete` | Hủy phân công — chuyển `status=cancelled`, tự động hủy các random-check đang chờ liên quan |

**`CreateAssignmentRequest`**:
```json
{
  "employeeId": "uuid",             // bắt buộc
  "shiftId": "uuid",                // optional — PHẢI thuộc đúng site này và đang active (mới)
  "startDate": "2026-07-01",        // bắt buộc, yyyy-MM-dd
  "endDate": "2026-12-31",          // optional — bỏ trống = không giới hạn ngày kết thúc
  "daysOfWeek": ["MONDAY","WEDNESDAY","FRIDAY"],  // optional — bỏ trống = mọi ngày trong khoảng
  "role": "worker",                 // optional, mặc định "worker" — hoặc "supervisor"
  "notes": "..."                    // optional
}
```

**`UpdateAssignmentRequest`** (mọi field optional, dùng `clearShift`/`clearEndDate`/`clearDaysOfWeek` để xóa hẳn field tương ứng — không dùng `null` đơn thuần):
```json
{
  "shiftId": "uuid", "clearShift": false,
  "startDate": "2026-07-01",
  "endDate": "2026-12-31", "clearEndDate": false,
  "daysOfWeek": ["MONDAY"], "clearDaysOfWeek": false,
  "role": "supervisor", "notes": "..."
}
```

**`AssignmentResponse`** (2 field `*Summary` mới bổ sung — tránh phải tự gọi thêm API Employee/Shift để hiện tên/giờ ca khi render danh sách hoặc chi tiết 1 phân công; danh sách phân trang dùng batch-query nội bộ, không phát sinh N+1):
```json
{
  "id": "uuid", "tenantId": "uuid", "siteId": "uuid", "employeeId": "uuid", "shiftId": "uuid | null",
  "employeeSummary": {                          // MỚI — null nếu employee không còn tìm thấy
    "id": "uuid", "employeeCode": "EMP-001", "fullName": "John Doe", "status": "active"
  },
  "shiftSummary": {                             // MỚI — null nếu không gắn ca (shiftId null)
    "id": "uuid", "name": "Morning Shift", "startTime": "08:00", "endTime": "17:00", "status": "active"
  },
  "startDate": "2026-07-01", "endDate": "2026-12-31 | null",
  "daysOfWeek": ["MONDAY","WEDNESDAY","FRIDAY"] | null,
  "role": "worker | supervisor", "status": "active | cancelled",
  "lifecycleStatus": "upcoming | effective | completed | cancelled",
  "notes": "...", "createdBy": "uuid", "createdAt": "...", "updatedAt": "..."
}
```
Lưu ý: `shiftSummary.status` phản ánh trạng thái **hiện tại** của ca (có thể đã `inactive` dù phân công vẫn `active` — deactivate không hủy phân công đang dùng nó, xem mục 2.2). `shiftSummary.startTime`/`endTime` là giờ ca **hiện tại**, không phải snapshot lúc tạo phân công — để xem đúng giờ đã snapshot lúc chấm công thực tế (phục vụ payroll/audit), xem field `shiftStartTime`/`shiftEndTime` trên từng bản ghi check-in (module Checkin), không phải ở đây.

`status` là trạng thái quản trị của **bản ghi phân công**: `active` chỉ có nghĩa bản ghi chưa bị hủy. Không được dịch `active` thành “Đang làm việc”. `lifecycleStatus` mới là trạng thái theo thời gian site/Việt Nam: `upcoming` = sắp bắt đầu, `effective` = đang hiệu lực, `completed` = đã qua lần làm cuối cùng, `cancelled` = đã hủy. Muốn khẳng định nhân viên thực sự đang làm việc phải dựa vào phiên check-in còn mở, không dựa vào assignment.

**Mã lỗi cần bắt trong form** (mới bổ sung so với trước, đánh dấu **MỚI**):
| HTTP | Khi nào | Message mẫu |
|---|---|---|
| 400 | `endDate` trước `startDate`, `daysOfWeek` rỗng, ca inactive, nhân viên terminated, ca đã có lịch sử khi xóa, site inactive khi tạo/sửa phân công **(MỚI — mục 0.2)**, giờ ca không hợp lệ khi tạo/sửa Shift **(MỚI — mục 0.2, P1-1)** | `"Shift 'X' is inactive and can no longer be assigned"`, `"Cannot assign a terminated employee to a site"`, `"Site 'X' is inactive and can no longer receive new assignments"`, `"startTime must be before endTime for a same-day shift..."` |
| 403 | Site ngoài phạm vi site-scope của caller | `"You do not have permission to act on this site"` |
| 404 | Site/employee/shift/assignment không tồn tại | `"Shift not found for this site: <id>"` |
| 409 | Trùng active tại CÙNG site, hoặc trùng tên ca, **hoặc xung đột đa-site (MỚI)** | `"Employee already has an active assignment at this site"`, `"Employee already has an overlapping active assignment at site 'X'..."` |

### 3.3 "Site được phép check-in hôm nay" (App) — cập nhật theo mục 0.2 (P0-4, §5)

`GET /api/v1/tenants/{tenantId}/checkin/available-sites` (module Checkin) — trả về danh sách gộp sẵn site + ca + geofence cho MỌI phân công `active` mà nhân viên (`employee.status == 'active'`, xem P0-1) đang có, tính theo **timezone của từng site** (không phải giờ server — sửa ở P0-4), gồm cả ca qua đêm bắt đầu "hôm qua" theo giờ site nhưng vẫn còn mở. Response mẫu (đã thêm 4 field mới ở cuối):
```json
{
  "assignmentId": "uuid", "assignmentRole": "worker",
  "site": { "id": "...", "name": "...", "code": "...", "address": "...", "latitude": 21.03, "longitude": 105.85, "timezone": "..." },
  "shift": { "id": "...", "name": "...", "startTime": "08:00:00", "endTime": "17:00:00", "allowOvernight": false, "earlyCheckinMinutes": 15, "lateCheckoutMinutes": 30 } ,
  "geofence": { "id": "...", "coordinates": [[...]], "bufferMeters": 50 },
  "serverNow": "2026-07-27T22:34:45+07:00",
  "checkinAllowedFrom": "2026-07-27T05:45:00+07:00",
  "checkinAllowedUntil": "2026-07-27T14:00:00+07:00",
  "availabilityStatus": "open | upcoming | closed | unrestricted"
}
```
`checkinAllowedFrom/Until`/`availabilityStatus` là **gợi ý UX cho App** (ví dụ để hiện đếm ngược hoặc mờ nút "Chấm công" khi `closed`) — `submitCheckin` phải gửi `assignmentId` của đúng dòng người dùng chọn và backend tự validate lại bằng `AssignmentService.resolveAvailableAssignmentNow`. App không được tự làm lại logic thời gian/ca qua đêm hoặc dùng giờ thiết bị để quyết định. `unrestricted` = phân công không gắn ca cụ thể, được phép cả ngày theo giờ Việt Nam.

Nhờ đợt sửa mục 2.1, danh sách này **không thể** hiện 2 site trùng giờ cho cùng 1 nhân viên (việc tạo ra tình huống đó đã bị chặn ngay từ khi phân công). Nhưng lưu ý: danh sách vẫn có thể hiện nhiều site cùng ngày với `availabilityStatus` khác nhau (ví dụ sáng đã `closed`, tối `upcoming`) — đây là hành vi đúng, không phải lỗi (một nhân viên có thể làm nhiều ca/site không trùng giờ trong ngày).

**Chấm công (`POST .../checkin`) — mã lỗi mới (mục 0.2):**
| HTTP | errorCode | Khi nào |
|---|---|---|
| 403 | `EMPLOYEE_NOT_ACTIVE` | Nhân viên không ở trạng thái `active` (P0-1) |
| 422 | `SITE_INACTIVE` | Site đang `inactive` (P0-2) |
| 422 | `CHECKIN_TOO_EARLY` | Trước cửa sổ cho phép (như cũ) |
| 422 | `CHECKIN_TOO_LATE` | Sau khi ca đã kết thúc (mới — P1-2) |
| 409 | `DUPLICATE_RESOURCE` | Nhân viên đang có phiên chấm công mở **ở bất kỳ site nào** (đổi từ chỉ-theo-assignment sang theo-nhân viên — P0-3), kèm site/giờ của phiên đang mở trong message |

## 4. Xác nhận thêm — không cần sửa

- **`shifts:*` bị seed 2 lần ở 2 migration khác nhau** (`V13` và `V24`) — không phải lỗi chức năng: 2 migration cộng dồn quyền (không ghi đè), kết quả cuối cùng đúng ý bạn (HR_MANAGER có đủ quyền tạo/sửa/xóa ca). Chỉ là lịch sử migration hơi rối, không ảnh hưởng hành vi, không cần dọn.
- **`SITE_SUPERVISOR` chỉ xem được phân công, không tạo/sửa/hủy** — đúng nghiệp vụ thực tế (giám sát viên điều phối tại chỗ nhưng quyết định nhân sự vẫn thuộc HR/Admin), khớp đúng yêu cầu vai trò bạn liệt kê ("Danh sách phân công" ghi rõ cả Supervisor, còn tạo/sửa/hủy chỉ ghi HR/Admin).
- **Không có giới hạn số lượng nhân viên tối đa cho 1 ca (headcount)** — không phải lỗi, chỉ là chưa có nhu cầu rõ ràng từ bạn; nếu cần giới hạn quân số mỗi ca (ví dụ ca đêm tối đa 5 người), báo tôi bổ sung riêng.
