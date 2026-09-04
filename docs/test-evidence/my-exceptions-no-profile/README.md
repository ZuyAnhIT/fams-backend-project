# #19 — "Cần giải thích" không tải được dữ liệu (404 khi tài khoản không có hồ sơ nhân viên)

Ngày: 2026-09-04 · Repo: `fams-backend-project`

## Vấn đề (ảnh người dùng)
Trang **"Cần tôi giải thích"** (`/customer/exceptions` trên web, tab tương ứng trên app) hiện
banner đỏ: *"Không thể tải hộp thư — Không tìm thấy dữ liệu yêu cầu."*

Tài khoản trong ảnh là **QA · Quản trị công ty** (TENANT_ADMIN).

## Nguyên nhân
`GET /api/v1/tenants/{tenantId}/me/exceptions` gộp 2 nguồn:
- `checkinService.getCheckinHistory(...)` → `resolveEmployee()` → `orElseThrow(ResourceNotFoundException)`
- `violationService.listMyViolations(...)` → cũng `orElseThrow(ResourceNotFoundException)`

Menu "Cần giải thích" hiển thị cho **mọi thành viên công ty** (tenant_admin, hr_manager,
supervisor, employee), nhưng **chỉ tài khoản có hồ sơ `employees`** mới có thể có check-in
`pending_review` hoặc vi phạm. Một tenant_admin / HR / chủ công ty thuần (không phải nhân viên)
gọi endpoint này → 404 → banner đỏ dead-end trên Web + App.

## Đã sửa
[MyExceptionsController.java](../../../api-server/src/main/java/com/fams/modules/selfservice/controller/MyExceptionsController.java):
inject `EmployeeRepository`, kiểm tra `existsByTenantIdAndUserIdAndDeletedAtIsNull` ở đầu
handler. Nếu người gọi **không có hồ sơ nhân viên** trong công ty → trả **`200` + danh sách
rỗng** (đúng nghiệp vụ: "bạn không có mục nào cần giải thích"), thay vì 404.

### Frontend (phòng thủ 2 lớp — không bắt buộc nhưng theo tiền lệ #14)
Backend đã trả `200` rỗng nên trang tự hiện trạng thái rỗng. Bổ sung thêm lớp chắn cho các
nguyên nhân 404 khác (tenantId cũ trong lúc hydrate, backend cũ chưa có bản vá):
- **Web** [MyExceptionsPage.tsx](../../../../fams-front-web-project/src/features/customer/violation/components/MyExceptionsPage.tsx)
  + [use-violation.ts](../../../../fams-front-web-project/src/features/customer/violation/hooks/use-violation.ts):
  404/403 → không hiện banner đỏ "Không thể tải hộp thư", hiện `<Empty>`; không retry.
- **App** [use-my-exceptions.ts](../../../../fams-front-app-project/src/features/exception/hooks/use-my-exceptions.ts):
  404 → `isError=false` → màn hình rơi vào `ListEmptyComponent` "Không có mục cần giải thích";
  không retry 403/404.

## Kiểm thử — `tests/selfservice/test_my_exceptions.sh` (6/6 PASS)

```
--- Test 1: tenant owner WITHOUT an employee profile ---
PASS: no-profile caller → HTTP 200 (was 404)
PASS: no-profile caller → empty data array

--- Test 2: real employee still sees their exceptions ---
PASS: employee caller → HTTP 200
PASS: employee caller → the seeded violation is listed
PASS: employee caller → reasonType no_response

--- Test 3: unauthenticated ---
PASS: no token → HTTP 401
```

Xác minh thêm bằng dữ liệu seed thật (không sửa DB):
- `duyanh19102005@gmail.com` (TENANT_ADMIN FOFO, **0 hồ sơ nhân viên**) →
  `GET /me/exceptions` → `200 {"data":[]}` (trước: 404).
- `anhtrauluoi@gmail.com` (nhân viên FOFO) → `200` + 1 vi phạm `no_response` chưa xử lý.

Regression: `test_checkin_history.sh` 8/8, `test_employee_explanation.sh` 16/16,
`test_hr_list_violations.sh` 11/11. `mvn -o compile` OK, `fams-api` restart OK, health UP.

Web E2E: `fams-front-web-project/tests/e2e/my-exceptions-no-profile.spec.ts` — PASS
(TENANT_ADMIN FOFO không hồ sơ → thấy "Không có mục cần giải thích", không có banner đỏ).
Ảnh `web-no-profile.png`. `tsc` + `eslint` (web + app) sạch.
