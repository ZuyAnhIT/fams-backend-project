# Tài liệu bàn giao UI: Web vs App — Bảng công / Attendance Summary

> Cập nhật theo code đang chạy ngày 31/07/2026 (bản vá lần 2, phản hồi trực tiếp báo cáo audit code của team Web/App gửi 31/07/2026). Đây **không phải** tài liệu API đầy đủ — chi tiết request/response đã có ở `docs/api/attendance-management-api.md` (business logic, các lỗi đã sửa, tối ưu phân trang). Tài liệu này chỉ trả lời:
> 1. Tính năng thuộc **Web** hay **App**?
> 2. Với từng vai trò, màn hình nào hiện gì, ẩn gì, disable gì?
> 3. Các field/trạng thái MỚI (do đợt sửa lỗi vừa rồi) cần hiển thị đúng để không đánh lừa người dùng.

## 0.b Trả lời trực tiếp 4 điểm team Web/App đã báo cáo (31/07/2026) — bản vá lần 2

| # | Team báo cáo gì | Đã xác minh đúng? | Backend đã làm gì | FE cần làm gì |
|---|---|---|---|---|
| 1 | `POST /attendance/recompute` không lọc theo tenant — có rủi ro đụng dữ liệu tenant khác | ✅ Đúng, nghiêm trọng | Đã sửa — endpoint giờ tự lọc đúng tenant + site-scope, không đổi request/response, không đổi hành vi phía FE | Không cần đổi gì, hành vi gọi API y hệt cũ |
| 2 | Dữ liệu lịch sử 146 ngày `pending_review` chưa được phản ánh đúng trên `attendance_summaries` | ✅ Đúng | Đã chạy backfill, xác nhận khớp 100% (146/146) | Không cần đổi gì — dữ liệu trả về từ API giờ đã đúng, không cần FE tự bù trừ hay lọc thủ công |
| 3 | `/reports/attendance/monthly` và `/reports/attendance/export` — sai permission, thiếu site-scope, Excel không có tên | ✅ Đúng, nghiêm trọng | Đã sửa cả 4 điểm + thêm guard chặn xuất sớm khi còn dữ liệu chưa duyệt | **BẮT BUỘC đổi**: permission check `reports:export` → `attendance:export`; bắt lỗi `409 ATTENDANCE_NOT_READY` khi xuất Excel — xem mục 4.4b |
| 4 | Không có cách mở khóa 1 bản ghi đã HR điều chỉnh tay khi có dữ liệu mới đến muộn | Yêu cầu tính năng hợp lý | Đã bổ sung `POST /attendance/{id}/unlock-and-recompute` (có audit trail) | **Nên thêm** nút "Mở khóa và tính lại" trên modal chi tiết — xem mục 4.5b (không bắt buộc ngay, nhưng khuyến nghị vì đây là cách duy nhất HR tự xử lý được tình huống dữ liệu đến muộn mà không cần dev can thiệp DB) |

Ngoài 4 điểm trên, team Web/App cũng nêu câu hỏi về **split-shift** (1 nhân viên có 2 ca khác nhau trong cùng 1 ngày) — đã kiểm tra trực tiếp trong schema và xác nhận **KHÔNG xảy ra được**: DB có ràng buộc unique `uq_assignments_employee_site_active` — 1 nhân viên chỉ có đúng 1 ca (assignment) đang active tại 1 site, tại 1 thời điểm. Không cần FE xử lý gì cho trường hợp này (chi tiết kỹ thuật xem `attendance-management-api.md` mục 11.1 nếu cần).

## 0. Việc bắt buộc phải đọc trước khi dựng UI — 3 khái niệm mới

### 0.1 `hasPendingReviewSession` / `hasRejectedSession` — số liệu có thể "chưa chốt"

Trước đây bảng công tính TẤT CẢ phiên chấm công bất kể trạng thái. Giờ **chỉ phiên `valid` được tính vào giờ công/đi muộn/về sớm/OT** — 2 field mới cho biết ngày đó có phiên bị loại hay không:

- `hasPendingReviewSession = true` → có phiên đang **chờ HR duyệt** bị loại khỏi số liệu ngày đó → **số liệu ngày này có thể THẤP HƠN thực tế**, chưa phải số cuối cùng.
- `hasRejectedSession = true` → có phiên đã bị HR **từ chối** (xác nhận gian lận) → phiên đó không được tính, đúng như ý.

**UI bắt buộc phải phân biệt 2 trạng thái này** — không dùng chung 1 icon "có vấn đề":
- `hasPendingReviewSession` → icon màu vàng/cam, tooltip "Có chấm công đang chờ HR duyệt — số liệu ngày này có thể thay đổi".
- `hasRejectedSession` → icon màu đỏ nhạt/xám, tooltip "Có chấm công đã bị từ chối, không tính vào công".

### 0.2 `adjustmentReason != null` — bản ghi đã bị HR khóa tay, không tự cập nhật nữa

Nếu 1 ngày có `adjustmentReason` khác rỗng, nghĩa là HR đã sửa tay số liệu ngày đó — **hệ thống sẽ không tự động tính lại ngày này nữa** cho tới khi HR chủ động sửa lại. UI cần:
- Hiện rõ badge "Đã điều chỉnh thủ công" + icon khóa 🔒 trên dòng đó.
- Hiện `adjustmentReason` khi hover/xem chi tiết.
- Nếu sau này có dữ liệu chấm công mới phát sinh cho ngày đã khóa (ví dụ đồng bộ offline đến trễ) — **KHÔNG có cách nào tự động báo cho HR biết** (đây là giới hạn đã biết, xem `attendance-management-api.md` mục 2) — nên khi HR mở chi tiết 1 ngày đã khóa, nên gợi ý nhẹ "Nếu có dữ liệu chấm công mới cho ngày này, bấm Tính lại + xác nhận lại điều chỉnh".

### 0.3 `totalWorkMinutes` ĐÃ BAO GỒM `otMinutes` — không cộng dồn

Khi hiển thị "Tổng giờ làm: X, trong đó OT: Y" — **X đã bao gồm Y**, không phải X + Y. Nếu tính lương theo công thức riêng, dùng đúng `totalWorkMinutes` làm tổng, `otMinutes` chỉ để hiển thị breakdown/tô màu phần vượt ca trong biểu đồ, không được cộng thêm.

## 1. Kết luận nhanh: App hay Web?

| Tính năng | Web (Company Portal) | App (nhân viên) |
|---|---|---|
| Xem kết quả 1 lượt chấm công (valid/pending/rejected) ngay sau khi bấm | — | ✅ |
| Xem lịch sử chấm công theo ngày | — | ✅ |
| Xem bảng công ngày/tháng của bản thân | — | ✅ |
| HR xem danh sách check-in (tìm/lọc/sort/trang) | ✅ | — |
| HR xem chi tiết 1 check-in (giải quyết tranh chấp) | ✅ | — |
| HR xem danh sách attendance summary theo ngày | ✅ | — |
| HR xem bảng công tổng hợp theo tháng (trước khi xuất lương) | ✅ | — |
| HR điều chỉnh tay 1 bản ghi bảng công | ✅ | — |
| HR trigger tính lại 1 ngày | ✅ | — |

## 2. Ma trận quyền theo vai trò

| Tính năng | TENANT_ADMIN / HR_MANAGER | SITE_SUPERVISOR | Nhân viên thường |
|---|---|---|---|
| Xem danh sách/chi tiết check-in toàn tenant | ✅ (`attendance:list`/`attendance:read`) | ✅ (chỉ site được giao) | ❌ |
| Xem bảng công tổng hợp tháng | ✅ | ✅ (chỉ site được giao) | ❌ |
| Điều chỉnh bảng công | ✅ | ✅ nếu có `attendance:list` + site được giao | ❌ |
| Trigger tính lại (`/recompute`) | ✅ | ✅ nếu có `attendance:list` | ❌ |
| Xem bảng công/lịch sử của chính mình | ✅ (nếu có hồ sơ nhân viên) | ✅ | ✅ |

**Lưu ý site-scope**: nếu SITE_SUPERVISOR được giao **nhiều site**, `GET .../attendance` (danh sách theo ngày) và `GET .../attendance/monthly` yêu cầu chỉ định `siteId` cụ thể — không tự gộp nhiều site. Web nên hiện dropdown chọn site trước khi tải danh sách nếu người dùng thuộc nhiều site.

## 3. Màn hình phía App (nhân viên)

### 3.1 Màn "Kết quả chấm công" — ngay sau khi bấm check-in/out

Đã có sẵn từ trước (không đổi) — hiện `status` (`valid`/`pending_review`/`rejected`) + `message` do backend build sẵn (đã đúng ngôn ngữ tự nhiên, không cần tự viết lại). Chỉ cần lưu ý: message của `pending_review` giờ có thể do NHIỀU nguyên nhân (vị trí GPS, xác thực khuôn mặt...) — không hardcode diễn giải chỉ về vị trí.

### 3.2 Màn "Lịch sử chấm công" (`GET /checkins/history`)

Không đổi so với trước — mỗi dòng hiện giờ vào/ra + trạng thái xác thực khuôn mặt 2 đầu nếu có.

### 3.3 [Cần rà soát lại] Màn "Bảng công của tôi" — ngày (`GET /attendance/me`) và tháng (`GET /attendance/me/monthly`)

**Cấu trúc màn tháng đề xuất** (theo đúng kiểu Deputy/QuickBooks Time — thẻ tổng quan phía trên + danh sách ngày phía dưới):

```
┌─ Thẻ tổng quan tháng 7/2026 ────────────────────────────┐
│ Số ngày công: 22        Tổng giờ làm: 176h (10,560 phút) │
│ Đi muộn: 6 ngày (176 phút)   Về sớm: 0 ngày               │
│ OT: 0 phút               Thiếu checkout: 0 ngày           │
│ ⚠️ 2 ngày có chấm công đang chờ duyệt — số liệu tạm thời  │  ← MỚI, chỉ hiện nếu daysWithPendingReview > 0
└───────────────────────────────────────────────────────────┘

Danh sách ngày (dailySummaries[], sắp xếp theo ngày tăng dần):
31/07  08:02 → 17:05   8h33p  🟡 Đi muộn 2p          [xem chi tiết]
30/07  08:00 → 17:00   9h00p                          [xem chi tiết]
29/07  08:15 → --:--   --      🟠 Đang chờ HR duyệt   [xem chi tiết]   ← hasPendingReviewSession=true
28/07  --:-- → --:--   0h      🔴 Bị từ chối          [xem chi tiết]   ← hasRejectedSession=true, không tính vào tổng
```

**Việc App cần làm mới**:
- Thêm dòng cảnh báo tổng quan nếu `daysWithPendingReview > 0` hoặc `daysWithRejectedSession > 0` (2 field mới trên `AttendanceMonthlyResponse`) — nhân viên hiểu vì sao tổng giờ làm "có vẻ thiếu" so với họ tự đếm.
- Mỗi dòng ngày trong `dailySummaries[]`: đọc `hasPendingReviewSession`/`hasRejectedSession` (2 field mới trên từng `AttendanceSummaryResponse`) để hiện đúng icon cảnh báo — không chỉ dựa vào `status` (`present`/`incomplete`) như trước, vì 1 ngày có thể `present` (đã đóng đủ) nhưng vẫn có phiên bị loại nếu nhân viên có NHIỀU phiên trong ngày và chỉ 1 phiên bị pending/rejected.
- Nếu 1 ngày có `adjustmentReason != null` → hiện badge "HR đã điều chỉnh" (không hiện lý do chi tiết cho nhân viên trừ khi sản phẩm muốn minh bạch hoàn toàn — cân nhắc theo chính sách công ty, nhưng nên ít nhất báo là CÓ điều chỉnh để nhân viên không thắc mắc sai số).

## 4. Màn hình phía Web (HR/Admin)

### 4.1 Danh sách check-in (`GET /checkins`) — đã có, không đổi

Tìm/lọc/sort/phân trang đã đúng từ trước, không cần sửa.

### 4.2 Chi tiết 1 check-in (`GET /checkins/{id}/detail`) — đã có, không đổi

Đủ bằng chứng GPS + Face ID cho tranh chấp, không cần sửa.

### 4.3 [MỚI — cần cập nhật] Danh sách bảng công theo ngày (`GET /attendance`)

Thêm 2 cột/badge mới trên mỗi dòng: `hasPendingReviewSession` (badge cam), `hasRejectedSession` (badge đỏ). Gợi ý thêm bộ lọc nhanh "Chỉ hiện ngày có vấn đề" (client-side filter trên 2 field này, hoặc đề xuất backend bổ sung param lọc nếu cần dùng thường xuyên — hiện API chưa có filter riêng cho 2 field này).

### 4.4 [MỚI — quan trọng nhất] Bảng công tổng hợp tháng (`GET /attendance/monthly`) — màn trước khi xuất lương

Đây là màn HR dùng để **chốt số trước khi xuất lương** — đã tối ưu lại phân trang ở tầng DB (không đổi cách gọi API, chỉ nhanh hơn với tenant nhiều nhân viên).

**Cấu trúc đề xuất**:

```
┌─ Bảng công tổng hợp — Tháng 7/2026 — [Chọn site ▾] [Xuất Excel] ─┐
│ Nhân viên      │ Site          │ Ngày công │ Tổng giờ │ Đi muộn │ OT   │ ⚠️  │
│ Nguyễn Văn A   │ Kho Bắc       │ 22        │ 176h     │ 6 (176p)│ 0    │  -  │
│ Trần Thị B     │ Kho Nam       │ 20        │ 160h     │ 0       │ 90p  │ ⚠️2 │  ← daysWithPendingReview=2
│ Lê Văn C       │ Kho Bắc       │ 18        │ 144h     │ 3       │ 0    │ 🔴1 │  ← daysWithRejectedSession=1
└─────────────────────────────────────────────────────────────────┘
```

**Việc Web BẮT BUỘC phải làm** (đây là thay đổi nghiệp vụ quan trọng nhất của đợt này):
- Thêm cột `daysWithPendingReview`/`daysWithRejectedSession` (2 field mới trên `AttendanceHrMonthlyResponse`) — **KHÔNG cho phép xuất lương/đánh dấu "đã chốt"** cho dòng nào có `daysWithPendingReview > 0` mà không có cảnh báo rõ ràng trước, vì số `totalWorkMinutes` của dòng đó tạm thời thấp hơn thực tế (còn phiên chưa duyệt).
- Nút "Xuất Excel"/"Chốt bảng lương" nên hiện modal cảnh báo nếu bất kỳ dòng nào trong trang có `daysWithPendingReview > 0`: *"X nhân viên còn chấm công chưa được duyệt trong tháng này — số liệu có thể thay đổi sau khi duyệt. Tiếp tục xuất?"*
- 1 nhân viên làm việc ở NHIỀU site trong tháng sẽ xuất hiện **nhiều dòng** (gộp theo employee+site, không phải 1 dòng/nhân viên) — nếu payroll cần tổng theo TỪNG nhân viên (không phân theo site), Web phải tự cộng dồn các dòng cùng `employeeId` lại, backend không tự gộp xuyên site.

### 4.4b [MỚI — bản vá lần 2] `GET /reports/attendance/export` — permission đổi, có guard chặn xuất sớm

- **Permission đổi**: giờ yêu cầu `attendance:export` (trước đây kiểm tra nhầm `reports:export` — nếu Web đang hardcode check quyền `reports:export` để ẩn/hiện nút Xuất Excel, phải đổi lại theo `attendance:export`, kiểm tra qua `GET /roles/me`).
- **Guard mới — 409 `ATTENDANCE_NOT_READY`**: nếu còn dòng nào trong phạm vi (tháng + site filter) có `daysWithPendingReview>0` hoặc `daysWithRejectedSession>0`, gọi export sẽ nhận lỗi `409` kèm `errorCode=ATTENDANCE_NOT_READY` và `userMessage` tiếng Việt sẵn có thể hiện thẳng cho HR. Web bắt lỗi này, hiện modal xác nhận, nếu HR đồng ý gọi lại kèm query param `confirmDespiteWarnings=true` để xuất dù vậy.
- Site-scope giờ được áp dụng đúng (trước đây thiếu) — SITE_SUPERVISOR gọi export với `siteId` ngoài phạm vi sẽ nhận `403`, không còn xuất được dữ liệu site khác.
- File Excel giờ có thêm cột Employee Code/Employee Name/Site Name (không còn UUID thô) và 2 cột cảnh báo pending/rejected — không cần Web tự map ID sang tên nữa.

### 4.5b [MỚI — bản vá lần 2] Nút "Mở khóa + tính lại" (`POST /attendance/{summaryId}/unlock-and-recompute`)

Trên modal chi tiết 1 bản ghi đã bị khóa (`adjustmentReason != null`, xem mục 4.5), nên thêm nút **"Mở khóa và tính lại"** bên cạnh nút "Điều chỉnh lại" — dùng khi HR biết có dữ liệu chấm công mới hợp lệ đến muộn và muốn hệ thống tự tính lại (thay vì tự nhập tay số mới). Yêu cầu:
- Bắt buộc nhập `reason` (lý do mở khóa) trước khi xác nhận — backend từ chối `400` nếu thiếu.
- Sau khi gọi thành công, response trả về bản ghi ĐÃ TÍNH LẠI (`adjustmentReason=null`, số liệu mới) — Web cập nhật UI ngay từ response này, không cần gọi lại API riêng.
- Hành động này được ghi vào audit log hệ thống (ai mở khóa, khi nào, lý do, số liệu trước/sau) — nếu Web có màn xem lịch sử audit, có thể hiển thị action `attendance_summary_unlock_and_recompute` cho bản ghi đó.
- Dùng cùng quyền `attendance:list` + site-scope như "Điều chỉnh" — không cần quyền riêng.

### 4.5 [MỚI] Modal "Điều chỉnh bảng công" (`PATCH /attendance/{id}/adjust`)

- Trường `reason` **bắt buộc nhập** (backend từ chối nếu thiếu, trả `400`).
- Sau khi lưu, hiện rõ cho HR: *"Bản ghi này đã được khóa — hệ thống sẽ KHÔNG tự động tính lại nữa cho tới khi bạn điều chỉnh lại lần nữa"* — tránh HR hiểu nhầm là hệ thống vẫn "theo dõi" và tự cập nhật.
- Nếu bấm "Tính lại" (`/recompute`) cho 1 ngày ĐÃ điều chỉnh, API trả về `200` nhưng **âm thầm không đổi gì** (theo thiết kế bảo vệ) — Web nên tự kiểm tra: nếu gọi `/recompute` xong mà `updatedAt` của bản ghi không đổi, hiện thông báo *"Bản ghi này đã được điều chỉnh thủ công trước đó — không có gì thay đổi. Muốn cập nhật theo dữ liệu chấm công mới nhất, hãy điều chỉnh lại."*

### 4.6 Nút "Tính lại" (`POST /attendance/recompute?date=`)

Không đổi — chỉ cần lưu ý hành vi mới ở mục 4.5 khi ngày đó đã bị khóa bởi điều chỉnh trước đó.

## 5. Mã lỗi cần xử lý

| HTTP | Khi nào | Web/App nên làm gì |
|---|---|---|
| 403 | Không có quyền `attendance:list`/`attendance:read`, hoặc site ngoài phạm vi được giao | Ẩn hẳn menu/nút liên quan nếu biết trước không có quyền (dựa vào `GET /roles/me`), không chỉ chặn sau khi bấm |
| 403 | `siteId` được yêu cầu nhưng người dùng scope nhiều site chưa chọn | Bắt buộc hiện dropdown chọn site trước khi gọi API danh sách/tháng |
| 400 | `month` ngoài khoảng 1-12 | Validate phía client trước khi gọi |
| 400 | Thiếu `reason` khi điều chỉnh/mở khóa | Validate form trước khi submit |
| 404 | `summaryId` không tồn tại hoặc khác tenant | Refresh lại danh sách, báo "bản ghi không còn tồn tại" |
| 409 | `ATTENDANCE_NOT_READY` khi xuất Excel còn dòng pending/rejected chưa chốt | Hiện modal xác nhận (dùng `userMessage` có sẵn), gọi lại kèm `confirmDespiteWarnings=true` nếu HR đồng ý |

## 6. Sơ đồ nav đề xuất

```text
COMPANY PORTAL (fams-front-web-project) — TENANT_ADMIN / HR_MANAGER / SITE_SUPERVISOR
└── Chấm công → Danh sách check-in (đã có)
└── Chấm công → Chi tiết check-in (đã có)
└── Bảng công → Theo ngày (MỚI: thêm badge pending/rejected — mục 4.3)
└── Bảng công → Tổng hợp tháng (MỚI: thêm cột cảnh báo + chặn xuất lương sớm — mục 4.4, QUAN TRỌNG NHẤT)
    └── Modal "Điều chỉnh" (MỚI: thông báo khóa — mục 4.5)

MOBILE APP (fams-front-app-project)
└── Màn "Kết quả chấm công" (đã có, không đổi)
└── Màn "Lịch sử chấm công" (đã có, không đổi)
└── Màn "Bảng công của tôi"
    ├── Tab Tháng (MỚI: thẻ tổng quan + cảnh báo pending/rejected — mục 3.3)
    └── Tab theo ngày (MỚI: icon cảnh báo trên từng dòng)
```

## 7. Checklist bàn giao frontend

- [ ] **App**: thêm dòng cảnh báo tổng quan (`daysWithPendingReview`/`daysWithRejectedSession`) trên màn bảng công tháng.
- [ ] **App**: thêm icon/badge phân biệt `hasPendingReviewSession` (cam) vs `hasRejectedSession` (đỏ) trên từng dòng ngày — không dùng chung 1 icon.
- [ ] **App**: hiện badge "Đã điều chỉnh thủ công" khi `adjustmentReason != null`.
- [ ] **Web**: thêm cột cảnh báo tương tự trên danh sách bảng công theo ngày.
- [ ] **Web — bắt buộc**: thêm cột `daysWithPendingReview`/`daysWithRejectedSession` trên bảng công tổng hợp tháng, chặn/cảnh báo trước khi xuất lương nếu có dòng chưa chốt.
- [ ] **Web**: modal điều chỉnh phải cảnh báo rõ hành vi khóa tự động-tính-lại.
- [ ] **Web**: xử lý trường hợp `/recompute` không đổi gì do bản ghi đã bị khóa (so sánh `updatedAt` trước/sau).
- [ ] **Web**: nếu 1 nhân viên có nhiều dòng (nhiều site) trong bảng tổng hợp tháng, không giả định 1 dòng = 1 nhân viên khi tính tổng lương.
- [ ] Không hiển thị `totalWorkMinutes + otMinutes` ở bất kỳ đâu — `otMinutes` chỉ là breakdown, đã nằm trong `totalWorkMinutes`.
- [ ] **Web — bắt buộc (bản vá lần 2)**: đổi check quyền nút "Xuất Excel" từ `reports:export` sang `attendance:export`.
- [ ] **Web — bắt buộc (bản vá lần 2)**: bắt lỗi `409 ATTENDANCE_NOT_READY` khi xuất Excel, hiện modal xác nhận, gọi lại kèm `confirmDespiteWarnings=true`.
- [ ] **Web**: thêm nút "Mở khóa và tính lại" trên modal chi tiết bản ghi đã điều chỉnh — mục 4.5b.
