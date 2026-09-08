# Báo cáo sửa và kiểm thử vấn đề 9–11 — 06/09/2026

## 1. Kết quả nghiệp vụ

### Vấn đề 9 — phân quyền chấm công và bảng công

Đã tách quyền đọc khỏi quyền làm thay đổi kết quả tính công:

| Nghiệp vụ | Quyền |
|---|---|
| Nhân viên xem lịch sử/bảng công của chính mình | `checkins:read`, `attendance:read` |
| Xem danh sách chấm công/bảng công thuộc phạm vi quản lý | `checkins:list`, `attendance:list` |
| Duyệt hoặc từ chối bằng chứng chấm công | `checkins:review` |
| Điều chỉnh, mở khóa hoặc tính lại bảng công | `attendance:adjust` |

Migration `V124__separate_attendance_review_permissions.sql` cấp quyền ghi mới cho đúng vai trò
quản lý và xóa phòng vệ các quyền quản trị khỏi role hệ thống `EMPLOYEE`. Namespace cache quyền JWT
được đổi sang `auth:perms:v124:` để quyền cũ không tồn tại thêm 5 phút sau triển khai.

API cũng kiểm tra lại tại tầng service, không chỉ dựa vào việc ẩn nút trên giao diện:

- Nhân viên chỉ xem được `/checkin/history`, `/attendance/me` và `/attendance/me/monthly` của mình.
- `GET /attendance/{summaryId}` kiểm tra chủ sở hữu nếu người gọi không có `attendance:list`, đóng
  lỗ hổng đọc bảng công của người khác khi biết UUID.
- Override check-in bắt buộc `checkins:review`; quyền xem danh sách không còn ngầm cho phép sửa.
- Điều chỉnh/mở khóa/tính lại bảng công bắt buộc `attendance:adjust`.
- Checkout vốn đã có kiểm tra `record.employeeId == callerEmployee.id`; test hồi quy hiện hữu tiếp
  tục xác nhận nhân viên khác nhận `403`.
- Site Supervisor/role bị giới hạn công trình vẫn phải qua `site-scope` ở tầng service.

Ma trận quyền thực tế sau migration:

| Role hệ thống | Quyền quản lý liên quan |
|---|---|
| `EMPLOYEE` | `checkins:read`, `attendance:read` (không list/review/adjust) |
| `SITE_SUPERVISOR` | list/read + `checkins:review`; không `attendance:adjust` |
| `HR_MANAGER` | list/read + `checkins:review` + `attendance:adjust` |
| `TENANT_ADMIN` | list/read + `checkins:review` + `attendance:adjust` |

### Vấn đề 10 — ô chọn nhân viên khi gán vai trò hàng loạt

- Mỗi kết quả hiển thị avatar, tên tiếng Việt, email và mã nhân viên theo cấu trúc dễ đọc.
- Kết quả đã chọn có dấu xác nhận; các tag được thu gọn responsive và có `+N nhân viên` khi dài.
- Có trạng thái đang tải, không tìm thấy, gợi ý tìm theo tên/email/mã và bộ đếm số người đã chọn.
- Không đưa hồ sơ chưa có tài khoản (`userId = null`) vào danh sách gán vai trò.
- Tên role hệ thống và lỗi gán trùng được hiển thị bằng tiếng Việt.

Minh chứng giao diện: `fams-front-web-project/docs/test-evidence/attendance-hover-bulk-role/bulk-role-options.png`.

### Vấn đề 11 — tìm kiếm toàn hệ thống

- Hỗ trợ họ tên đầy đủ theo cả thứ tự `họ + tên` và `tên + họ`.
- Tìm công trình bằng tên, mã nghiệp vụ, địa chỉ, mô tả; tìm chấm công/vi phạm theo nhân viên hoặc
  công trình liên quan.
- Hỗ trợ từ khóa nhóm tiếng Việt như `nhân viên`, `công trình`, `chấm công`, `vi phạm`.
- Loại bỏ tìm trực tiếp UUID và không hiển thị UUID trong dòng kết quả.
- Kết quả chấm công được bổ sung tên nhân viên, mã nhân viên, tên công trình và thời gian dễ đọc.
- Mỗi nhóm kết quả chỉ xuất hiện khi người gọi có quyền xem module tương ứng; site-scope được giữ.
- Popover tìm kiếm được điều khiển rõ ràng, input được blur khi chuyển trang và có thể mở/tìm tiếp
  ngay trên header của trang đích.

Minh chứng lần tìm thứ hai sau khi chuyển trang:
`fams-front-web-project/docs/test-evidence/global-search-navigation/second-search-after-navigation.png`.

## 2. Kiểm thử đã chạy

### Unit/integration backend

Chạy trong container với đúng cấu hình database của môi trường Docker:

```bash
docker exec fams-api sh -lc 'mvn -q test'
```

Kết quả: **62 tests, 0 failures, 0 errors, 0 skipped**.

Test mới quan trọng:

- `AttendanceEmployeeDataIsolationTest`: người chỉ có `attendance:read` không đọc được summary
  của nhân viên khác.
- `CheckinOverridePermissionTest`: `checkins:list` đơn lẻ không thể override check-in.

### Kiểm thử API thật với seed data

Tài khoản nhân viên `duy.anh@fams.test`:

| Tình huống | HTTP thực tế | Kỳ vọng |
|---|---:|---:|
| Danh sách check-in toàn công ty | 403 | 403 |
| Lịch sử check-in của chính mình | 200 | 200 |
| Danh sách attendance toàn công ty | 403 | 403 |
| Attendance của chính mình | 200 | 200 |
| Mở attendance summary của người khác | 403 | 403 |
| Override check-in của người khác | 403 | 403 |

Tài khoản HR gọi endpoint override với đúng quyền mới đi qua lớp phân quyền và nhận `400` vì cố
đặt lại đúng trạng thái hiện tại (không phải `403`, không thay đổi dữ liệu). Migration V124 được
Flyway ghi nhận thành công trên database đang chạy.

Kiểm thử search API thật:

| Truy vấn | Kết quả |
|---|---|
| `Nguyễn Bá Duy Anh` | 1 nhân viên, 4 lượt chấm công liên quan |
| `chấm công` | 8 lượt chấm công gần nhất trong phạm vi quyền |
| UUID nội bộ của công trình | 0 kết quả |

### Web

```bash
npm run lint -- --quiet
npm run build -- --webpack
LIVE_BACKEND=true LIVE_BACKEND_URL=http://localhost:8080 \
  npx playwright test \
  tests/e2e/global-search-navigation.spec.ts \
  tests/e2e/attendance-hover-bulk-role.spec.ts \
  --project=chromium --grep 'search remains|bulk assign role modal'
```

Kết quả:

- ESLint: **pass**.
- Next.js production build (52 routes): **pass**.
- Playwright live backend: **2/2 pass**.

## 3. Lưu ý triển khai

- Phải triển khai backend/migration V124 trước hoặc đồng thời với web để hai quyền mới tồn tại.
- Người dùng đang đăng nhập nên tải lại hồ sơ/đăng nhập lại sau triển khai để frontend nhận danh
  sách quyền mới. Backend đã đổi namespace Redis nên không dùng nhầm cache quyền cũ.
- Role tùy chỉnh chỉ có `checkins:list`/`attendance:list` từ trước sẽ trở thành role xem đúng nghĩa;
  quản trị viên phải chủ động thêm `checkins:review`/`attendance:adjust` nếu role đó cần quyền ghi.
