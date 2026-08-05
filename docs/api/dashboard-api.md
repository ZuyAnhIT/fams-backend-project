# API Reference: Dashboards (Employee / HR / Site Supervisor)

> Cập nhật theo code đang chạy ngày 2026-08-04, sau audit nghiệp vụ (`docs/reviews/backend/violation-dashboard-audit-2026-08-04.md`). Base path: `/api/v1/tenants/{tenantId}/dashboard`. Tài liệu này là **nguồn tham khảo chính thức cho FE (Web + App)** khi dựng 3 màn hình dashboard theo vai trò — đã kiểm tra và sửa đúng nghiệp vụ.

Bao gồm 3 trong 8 user story hệ thống:
- *Là một nhân viên, tôi muốn xem ca hôm nay, trạng thái chấm công, công tháng và thông báo để biết việc cần làm trong ngày.*
- *Là một HR/Admin, tôi muốn xem tổng quan nhân sự, chấm công, vi phạm và công trình để ra quyết định nhanh.*
- *Là một Site Supervisor, tôi muốn xem nhân viên tại site mình phụ trách để quản lý hiện trường.*

5 story còn lại (HR xử lý vi phạm) đã có tài liệu riêng ở `docs/api/violation-management-api.md` — không lặp lại ở đây.

---

## 1. Khái niệm nền tảng

### 1.1 Mỗi dashboard tự xác định phạm vi theo vai trò của người gọi — không có param chọn vai trò

3 endpoint hoàn toàn tách biệt (`/dashboard/employee`, `/dashboard/hr`, `/dashboard/supervisor`), mỗi endpoint tự scope theo JWT của người gọi (giống pattern `/me/*` đã quen thuộc) — **không có 1 endpoint chung `/dashboard` nhận param `role`**. FE cần gọi đúng endpoint tương ứng màn hình đang hiện, dựa theo vai trò hệ thống của user đăng nhập (lấy từ `GET /roles/me` hoặc claim JWT sẵn có).

Một người có thể hợp lệ với nhiều endpoint cùng lúc — ví dụ HR_MANAGER kiêm cũng là 1 Employee record (được assign vào 1 site để tự chấm công) hoàn toàn có thể gọi được cả `/dashboard/hr` lẫn `/dashboard/employee`. **App** thường chỉ cần `/dashboard/employee`. **Web** cần cả 3 tuỳ vai trò đăng nhập, hoặc dựng 1 dashboard tổng hợp gọi song song nếu muốn hiện đa vai trò trên cùng màn hình.

### 1.2 "Hôm nay"/"tháng này" tính theo timezone của tenant/site — không phải UTC

**Vá 2026-08-04**: trước đây cả 3 dashboard tính "hôm nay" theo UTC cứng (có chỗ còn theo giờ hệ điều hành container, không xác định) — sai lệch tới 7 tiếng với tenant ở Việt Nam (UTC+7), đúng lúc quan trọng nhất (đầu ca sáng). Đã sửa dùng field `timezone` sẵn có trên `Tenant`/`Site` (mặc định `UTC` nếu tenant chưa cấu hình). FE không cần làm gì thêm — số liệu giờ tự đúng theo giờ thực tế của tenant, không cần tự bù trừ múi giờ ở client.

### 1.3 "Đang có mặt tại site" chỉ tính phiên mở HÔM NAY — không tính phiên quên checkout từ trước

**Vá 2026-08-04 — lỗi nghiêm trọng đã sửa**: trước đây `onSiteNow`/`employeesOnSiteNow` đếm MỌI phiên chấm công chưa checkout, kể cả từ nhiều ngày/tuần trước (nhân viên quên checkout). Một phiên quên đóng sẽ khiến người đó bị tính "đang có mặt" vĩnh viễn cho tới khi có ai chỉnh tay. Đã sửa: chỉ tính phiên mở được bắt đầu (`checkInAt`) trong ngày hôm nay. FE không cần validate lại — số liệu server trả về giờ đã đúng nghĩa "đang có mặt ngay bây giờ", không lẫn dữ liệu rác lịch sử.

---

## 2. Ma trận quyền

| Endpoint | Ai gọi được | Cơ chế scope |
|---|---|---|
| `GET /dashboard/employee` | Bất kỳ user nào có Employee record trong tenant | Tự động theo JWT — không cần permission riêng |
| `GET /dashboard/hr` | TENANT_ADMIN / HR_MANAGER (quyền `employees:list`) | Platform Admin bypass |
| `GET /dashboard/supervisor` | User có Employee record với ≥1 assignment `role=supervisor` hôm nay | Tự động theo JWT — không cần permission RBAC riêng, scoping qua chính assignment |

`GET /dashboard/supervisor` trả `supervisedSites: []` (mảng rỗng, KHÔNG phải lỗi) nếu người gọi không có assignment supervisor nào hôm nay — FE nên hiện empty-state "Bạn chưa được phân công giám sát site nào hôm nay" thay vì lỗi.

---

## 3. `GET /dashboard/employee`

**User story**: *Nhân viên muốn xem ca hôm nay, trạng thái chấm công, công tháng và thông báo để biết việc cần làm trong ngày.*

`404` nếu người gọi không có Employee record trong tenant (ví dụ tài khoản chỉ có role platform admin).

```json
{
  "todayShifts": [
    {
      "assignmentId": "uuid",
      "siteId": "uuid",
      "siteName": "Trụ sở Hoàng Long Hà Nội",
      "role": "worker",
      "shift": { "shiftId": "uuid", "name": "Ca sáng", "startTime": "07:00:00", "endTime": "15:00:00" }
    }
  ],
  "checkin": {
    "checkinId": "uuid", "siteId": "uuid", "status": "valid",
    "checkInAt": "2026-08-04T00:05:00Z", "checkOutAt": null, "workMinutes": null, "open": true
  },
  "monthlyAttendance": {
    "month": "2026-08", "presentDays": 3, "lateDays": 1, "earlyLeaveDays": 0,
    "missingCheckoutDays": 0, "totalOtMinutes": 45, "totalWorkMinutes": 1440
  },
  "alerts": {
    "unreadNotifications": 2,
    "pendingExplanations": 11
  }
}
```

- `todayShifts`: mảng — nhân viên có thể có >1 assignment cùng ngày (hiếm nhưng hợp lệ). Rỗng nếu không có ca nào hôm nay.
- `checkin`: `null` nếu chưa chấm công hôm nay — FE hiện CTA "Chấm công ngay" khi null. `open=true` nghĩa là đã check-in nhưng chưa check-out.
- `monthlyAttendance`: tổng hợp tháng hiện tại (theo timezone tenant, mục 1.2), luôn trả về (không null) — tất cả field = 0 nếu tháng chưa có dữ liệu.
- **`alerts` — MỚI (2026-08-04)**: đây chính là phần "thông báo" mà user story yêu cầu, trước đó hoàn toàn thiếu.
  - `unreadNotifications`: cùng nguồn dữ liệu với `GET /notifications` — dùng để hiện badge số trên icon chuông.
  - `pendingExplanations`: tổng số checkin `pending_review` + violation `resolved=false` của chính nhân viên — **cùng bộ dữ liệu** với `GET /me/exceptions` (xem `violation-management-api.md` mục 4.2), cung cấp dạng đếm nhẹ để trang chủ không cần tải cả danh sách chỉ để hiện số. Bấm vào badge này nên điều hướng sang màn "Hộp thư cần giải thích" (`/me/exceptions`).

**UI đề xuất**: màn Trang chủ nhân viên — khối "Ca hôm nay" (từ `todayShifts`), khối "Trạng thái chấm công" (từ `checkin`, có nút Check-in/Check-out tuỳ trạng thái), khối "Công tháng này" (từ `monthlyAttendance`, dạng số liệu tóm tắt + link sang bảng công chi tiết), và badge/banner "Cần bạn xử lý" hiện số `alerts.pendingExplanations` + `alerts.unreadNotifications` ngay đầu trang — tham khảo mô hình BambooHR/Deputy: trang chủ nhân viên luôn có 1 khối tổng hợp việc cần làm ngay khi mở app, không bắt nhân viên tự đi tìm từng mục riêng lẻ.

---

## 4. `GET /dashboard/hr`

**User story**: *HR/Admin muốn xem tổng quan nhân sự, chấm công, vi phạm và công trình để ra quyết định nhanh.*

Quyền `employees:list`. `403` nếu thiếu quyền.

```json
{
  "personnel": { "totalEmployees": 40, "newThisMonth": 3 },
  "attendance": { "presentToday": 32, "lateToday": 2, "onSiteNow": 18 },
  "violations": {
    "unresolved": 17, "resolvedThisMonth": 9,
    "unresolvedByType": { "no_response": 8, "location_fail": 9 }
  },
  "sites": { "totalSites": 13, "employeesOnSiteNow": 18 }
}
```

- `personnel.newThisMonth`: nhân viên được tạo trong tháng hiện tại (theo timezone tenant).
- `attendance.onSiteNow` và `sites.employeesOnSiteNow`: **cùng 1 giá trị** (cùng định nghĩa "đang có mặt hôm nay", mục 1.3) — backend tính 1 lần dùng chung cho cả 2 field, FE không cần lo 2 số lệch nhau.
- `violations.unresolvedByType`: map linh động theo `violationType` thực tế đang tồn tại (`no_response`/`location_fail`/`face_fail`/`liveness_fail`) — key nào không có vi phạm sẽ không xuất hiện trong map (không phải `0`), FE cần xử lý bằng `map[key] ?? 0` khi vẽ chart.
- Muốn xem chi tiết danh sách vi phạm đằng sau các con số này (không chỉ đếm) → điều hướng sang `GET /violations` (`violation-management-api.md` mục 3.1) với filter tương ứng.

**UI đề xuất**: màn Dashboard HR dạng 4 khối thẻ (personnel/attendance/violations/sites) ở đầu trang, mỗi khối có link "Xem chi tiết" điều hướng sang màn tương ứng đã có sẵn (Danh sách nhân viên, Bảng công hôm nay, Danh sách vi phạm, Danh sách site) — dashboard chỉ đóng vai trò tổng quan/entry-point, không phải nơi thao tác trực tiếp. Khối `violations.unresolvedByType` phù hợp vẽ dạng donut/bar chart nhỏ.

---

## 5. `GET /dashboard/supervisor`

**User story**: *Site Supervisor muốn xem nhân viên tại site mình phụ trách để quản lý hiện trường.*

Không cần quyền RBAC riêng — tự scope theo assignment `role=supervisor` của người gọi hôm nay. `404` nếu người gọi không có Employee record trong tenant (khác với "không có site nào" — trường hợp đó trả `200` với mảng rỗng, xem mục 2).

```json
{
  "supervisedSites": [
    {
      "siteId": "uuid",
      "siteName": "Trụ sở Hoàng Long Hà Nội",
      "expectedToday": 24,
      "onSiteNow": 18,
      "onSiteEmployees": [
        {
          "employeeId": "uuid", "firstName": "Văn", "lastName": "Nguyễn",
          "employeeCode": "NV001", "checkinId": "uuid", "checkInAt": "2026-08-04T00:05:00Z"
        }
      ]
    }
  ]
}
```

- `supervisedSites`: 1 phần tử cho mỗi site mà người gọi đang giám sát hôm nay — 1 supervisor có thể phụ trách nhiều site cùng lúc, FE cần hỗ trợ hiện nhiều site (tab hoặc danh sách cuộn), không giả định luôn chỉ 1 site.
- `expectedToday`: tổng số nhân viên có assignment active tại site hôm nay (nhân sự dự kiến).
- `onSiteNow`: số đang thực sự có mặt (đã check-in, chưa check-out, tính từ đầu ngày hôm nay theo mục 1.3) — so sánh `onSiteNow`/`expectedToday` để biết tỷ lệ có mặt.
- `onSiteEmployees`: danh sách chi tiết từng người đang có mặt, kèm giờ check-in — dùng cho màn điểm danh trực quan.

**UI đề xuất**: màn "Hiện trường của tôi" — mỗi site 1 card hiện tỷ lệ `onSiteNow`/`expectedToday` (dạng progress bar), danh sách `onSiteEmployees` bên dưới dạng list có avatar + giờ check-in. Tham khảo Connecteam: supervisor cần thấy ngay "ai đang ở đây" dạng trực quan, không phải bảng dữ liệu thô — ưu tiên hiển thị theo thời gian thực (khuyến nghị FE tự poll lại endpoint này mỗi 1-2 phút nếu màn hình đang mở, backend không có WebSocket/push riêng cho dashboard này).

---

## 6. Mã lỗi cần xử lý

| HTTP | Khi nào | FE nên làm gì |
|---|---|---|
| 401 | Chưa đăng nhập / token hết hạn | Redirect đăng nhập lại |
| 403 | `/dashboard/hr` — thiếu quyền `employees:list` | Ẩn hẳn menu Dashboard HR nếu biết trước không có quyền (dựa vào `GET /roles/me`) |
| 404 | `/dashboard/employee`, `/dashboard/supervisor` — người gọi không có Employee record trong tenant | Hiện thông báo phù hợp (ví dụ tài khoản admin thuần không nên thấy màn này) — KHÔNG nhầm với "không có site giám sát" (trường hợp đó là `200` + mảng rỗng, xem mục 2 và mục 5) |

---

## 7. Checklist bàn giao frontend

- [ ] **App — bắt buộc, chưa có màn hình**: dựng Trang chủ nhân viên dùng `GET /dashboard/employee` — 4 khối: ca hôm nay, trạng thái chấm công, công tháng, cần bạn xử lý (`alerts`).
- [ ] **App**: badge `alerts.pendingExplanations` + `alerts.unreadNotifications` điều hướng đúng màn tương ứng (`/me/exceptions`, `/notifications`).
- [ ] **Web — bắt buộc, chưa có màn hình**: dựng Dashboard HR dùng `GET /dashboard/hr` — 4 khối thẻ, mỗi khối link sang màn chi tiết tương ứng đã có sẵn.
- [ ] **Web/App — bắt buộc, chưa có màn hình**: dựng màn "Hiện trường của tôi" cho Site Supervisor dùng `GET /dashboard/supervisor`, hỗ trợ nhiều site cùng lúc.
- [ ] **Web/App**: xử lý đúng 2 trường hợp rỗng khác nhau ở `/dashboard/supervisor` — `200` + `supervisedSites: []` (chưa được phân công hôm nay, không phải lỗi) vs `404` (không có Employee record, lỗi tài khoản).
- [ ] **Web**: `violations.unresolvedByType` — xử lý key không tồn tại như giá trị `0`, không giả định đủ 4 loại luôn có mặt trong response.
- [ ] **Web/App**: không cần tự bù trừ múi giờ cho số liệu "hôm nay"/"tháng này" — backend đã tính theo timezone tenant/site (mục 1.2).
