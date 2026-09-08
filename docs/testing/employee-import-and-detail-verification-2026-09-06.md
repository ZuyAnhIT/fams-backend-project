# Minh chứng kiểm thử import và chi tiết nhân viên — 06/09/2026

## Phạm vi

- Cung cấp file Excel mẫu tiếng Việt để HR tải về và điền dữ liệu.
- Kiểm tra file trước khi import, không ghi dữ liệu ở bước kiểm tra và trả lỗi theo dòng/trường.
- Giữ tương thích với tiêu đề tiếng Anh của file import cũ.
- Hiển thị tên, mã, địa chỉ công trình và tên/giờ ca trong chi tiết nhân viên; không dùng UUID làm nhãn cho người dùng.

## Hợp đồng import

| API | Mục đích | Ghi dữ liệu |
|---|---|---|
| `GET /api/v1/tenants/{tenantId}/employees/import/template` | Tải `mau-import-nhan-vien.xlsx` gồm trang dữ liệu và trang hướng dẫn | Không |
| `POST /api/v1/tenants/{tenantId}/employees/import/validate` | Kiểm tra cấu trúc, định dạng và lỗi từng dòng/trường | Không |
| `POST /api/v1/tenants/{tenantId}/employees/import` | Tạo các hồ sơ hợp lệ; vẫn giữ tương thích luồng API import từng phần cũ | Có |
| `POST /api/v1/tenants/{tenantId}/employees/import/errors-export` | Xuất các dòng cần sửa thành Excel tiếng Việt | Không |

Các cột trong file mẫu: `Mã nhân viên`, `Họ và tên đệm`, `Tên`, `Email`, `Số điện thoại`, `Chức vụ`, `Phòng ban`, `Ngày vào làm`.

Các lỗi được kiểm tra gồm: thiếu cột bắt buộc, cột trùng, file không có dữ liệu, tên/họ trống hoặc quá dài, mã nhân viên sai/trùng, email sai/quá dài, số điện thoại sai/quá dài, chức vụ/phòng ban quá dài và ngày không tồn tại hoặc sai định dạng. Ngày nhận `dd/MM/yyyy`, `yyyy-MM-dd` và ô ngày thật của Excel.

## Kết quả tự động

### Backend

Lệnh:

```bash
cd api-server
mvn -q -Dtest=EmployeeImportWorkflowTest,AssignmentServiceExactSelectionTest,PlanLimitEnforcementServiceEmployeeCapacityTest test
```

Kết quả: `12 tests`, `0 failures`, `0 errors`, `0 skipped`.

- File sinh ra có tiêu đề tiếng Việt và trang `Hướng dẫn`.
- Validate phát hiện lỗi theo trường và không gọi `employeeRepository.save`.
- Ngày Việt Nam hợp lệ được import; ngày không tồn tại như `31/02/2026` bị từ chối.
- File tổng hợp lỗi giữ đúng số dòng Excel, dữ liệu gốc và tiêu đề tiếng Việt.
- Import hàng loạt tuân thủ cùng giới hạn số nhân viên của gói dịch vụ như tạo thủ công; nếu hết hạn mức, transaction import được rollback.
- Assignment trong chi tiết nhân viên được bổ sung đầy đủ ngữ cảnh site/shift.

Toàn bộ test Maven cũng được chạy trong container `fams-api`, nơi có đúng cấu hình PostgreSQL,
Redis và khóa ứng dụng của môi trường local:

```bash
docker compose exec -T fams-api mvn -q test
```

Kết quả mới nhất sau khi bổ sung kiểm tra hạn mức batch: `54 tests`, `0 failures`, `0 errors`,
`0 skipped`. Khi chạy Maven trực tiếp ngoài
container mà không nạp `.env`, riêng `contextLoads` sẽ thiếu biến môi trường; đây là khác biệt
cấu hình chạy test, không phải lỗi nghiệp vụ.

### Web

Lệnh kiểm tra tĩnh:

```bash
npm run typecheck
npx eslint \
  src/features/customer/employee/components/EmployeeWorkTab.tsx \
  src/features/customer/employee/components/ImportEmployeeModal.tsx \
  src/features/customer/employee/hooks/use-employee.ts \
  src/features/customer/employee/services/employee.service.ts \
  src/features/customer/employee/types/employee.type.ts \
  tests/e2e/employee-management.spec.ts
```

Kết quả: cả TypeScript và ESLint đều đạt.

Production build:

```bash
npm run build -- --webpack
```

Kết quả: build thành công, TypeScript thành công và sinh đủ `52/52` trang tĩnh.

Lệnh kiểm thử trình duyệt:

```bash
npx playwright test tests/e2e/employee-management.spec.ts
```

Kết quả: toàn bộ module đạt `6 passed` trên Chromium khi chạy bằng production build mới nhất.

- Nút xác nhận import bị khóa trước khi validate và khi còn lỗi.
- Lỗi hiển thị đúng dòng Excel, tên trường tiếng Việt và nội dung cần sửa.
- Chọn lại file đã sửa sẽ xóa kết quả cũ và bắt buộc kiểm tra lại trước khi import.
- Sau kết quả hợp lệ, import được thực hiện và hiển thị số hồ sơ đã tạo.
- Chi tiết nhân viên hiển thị `Công trình Tây Hồ`, `AP-TH`, địa chỉ, `Ca chiều` và giờ ca; chuỗi UUID site không xuất hiện trên giao diện.

Ảnh được sinh bởi Playwright tại project Web:

- `docs/test-evidence/employee-management/02-employee-detail.png`
- `docs/test-evidence/employee-management/04-import-validation.png`
- `docs/test-evidence/employee-management/05-import-success.png`

## Kiểm tra API trên Docker local

Backend được khởi động lại với source mới rồi kiểm tra qua HTTP bằng tài khoản HR demo:

- Tải template: HTTP `200`, đúng MIME `.xlsx`, đúng tên file và archive Excel hợp lệ.
- Validate file có ba dòng: `1` dòng hợp lệ, `2` dòng lỗi; trả chính xác lỗi email tại dòng 3 và thiếu tên tại dòng 4.
- Tổng số nhân viên trước/sau validate không đổi, xác nhận endpoint validate không ghi dữ liệu.
- API chi tiết nhân viên `AP009` trả `siteSummary` gồm tên/mã/địa chỉ công trình và `shiftSummary` gồm tên/giờ ca.

Bộ test tích hợp dùng chung `tests/employee/test_import_employees.sh` cũng được mở rộng thành 9 tình huống: tải template, validate không ghi dữ liệu, import hợp lệ/một phần, trùng mã, file rỗng, chưa đăng nhập và thiếu quyền. Tenant test dùng tài khoản chủ công ty riêng, không gán Platform Admin làm chủ hoặc thành viên công ty. Cú pháp Bash đã được kiểm tra bằng `bash -n`.

Kiểm tra read-only trên database hiện tại xác nhận `admin@fams.com` có `0` công ty đang sở hữu và `0` role công ty đang hoạt động.

Không ghi token, mật khẩu hay khóa môi trường vào tài liệu minh chứng.
