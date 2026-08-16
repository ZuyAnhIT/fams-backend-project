# Kịch bản test thủ công — #41 Import danh sách nhân viên

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không xuất được file lỗi tải về (chỉ trả JSON)".

**ĐÃ VÁ (2026-08-16):** thêm endpoint `POST /tenants/{tenantId}/employees/import/errors-export`
(multipart, nhận lại đúng file đã import), tái sử dụng logic validate của `importEmployees` qua
helper `validateImportRow` mới tách ra, trả về `.xlsx` chỉ chứa các dòng lỗi + cột `errors` gộp lý
do. Web Admin: `ImportEmployeeModal.tsx` thêm nút "Tải file lỗi (.xlsx)" xuất hiện khi
`failedCount > 0`, tự dùng lại file đang chọn trong modal (không cần chọn lại). Import không ghi
audit log và không hỗ trợ cột `plannedRoleId`/`departmentId` vẫn còn nguyên như audit gốc mô tả —
2 điểm này không nằm trong phạm vi AC "xuất file lỗi" nên không sửa trong đợt này.

---

## A. Test trên Web Admin

### 1. Import file hợp lệ — happy path
- Chuẩn bị file `.xlsx` với cột: firstName, lastName, email, phone, employeeCode, position,
  department, hiredDate (không phân biệt hoa/thường tên cột) — 3-5 dòng dữ liệu hợp lệ.
- Vào Nhân viên → Nhập Excel, chọn file, xác nhận.
- **Kỳ vọng:** import thành công, `successCount` khớp số dòng hợp lệ, nhân viên mới xuất hiện
  trong danh sách.

### 2. Import file có dòng lỗi trộn lẫn dòng hợp lệ
- File có 5 dòng: 3 dòng hợp lệ, 2 dòng lỗi (VD: thiếu firstName, email sai định dạng).
- **Kỳ vọng:** 3 dòng hợp lệ vẫn được tạo, 2 dòng lỗi bị báo rõ ràng qua UI (số dòng + lý do), có
  thể xem trực tiếp trên màn hình.

### 3. ✅ Xác nhận gap "không tải được file lỗi" — ĐÃ VÁ, test qua UI thật
- Sau case 2, tìm nút "Tải file lỗi" / "Xuất danh sách lỗi" trên UI kết quả import.
- **Kết quả thật (Playwright, 2026-08-16):** import file 3 dòng (1 hợp lệ, 2 lỗi: thiếu firstName
  + email sai định dạng, và hiredDate sai định dạng) qua modal Nhập Excel thật trên Web Admin →
  kết quả hiện đúng "1 thành công, 2 lỗi / 3 dòng" + bảng lỗi + nút mới "Tải file lỗi (.xlsx)" →
  bấm nút → trình duyệt tải về `import-errors.xlsx` → mở file: đúng 2 dòng lỗi, cột `errors` khớp
  100% với bảng lỗi hiển thị trên UI (dòng 3: "firstName: First name is required; email: Must be
  a valid email address"; dòng 4: "hiredDate: Date must be in YYYY-MM-DD format").

### 4. Import trùng employeeCode trong cùng file
- File có 2 dòng cùng employeeCode.
- **Kỳ vọng:** phát hiện trùng NGAY TRONG FILE (không chỉ so với DB), báo lỗi rõ ràng cho dòng bị
  trùng, không tạo cả 2.

### 5. Import trùng employeeCode với nhân viên đã có trong hệ thống
- 1 dòng trong file có employeeCode đã tồn tại trong tenant.
- **Kỳ vọng:** dòng đó báo lỗi trùng, các dòng khác trong file không bị ảnh hưởng.

### 6. Import file sai định dạng ngày (hiredDate)
- 1 dòng có `hiredDate` không đúng định dạng `YYYY-MM-DD`.
- **Kỳ vọng:** báo lỗi rõ ràng cho đúng dòng đó, không crash toàn bộ import.

### 7. Import khi công ty gần/đạt giới hạn nhân viên theo gói
- Nếu tenant test gần giới hạn `maxEmployees`, import 1 file có số dòng vượt giới hạn còn lại.
- **Kỳ vọng:** các dòng vượt giới hạn bị chặn rõ ràng (không tạo âm thầm vượt quá gói). Nếu tenant
  còn nhiều chỗ trống, ghi "không tái hiện được".

---

## Ghi chú
Case 1-2, 4-6 là luồng chính, rủi ro fail thấp (logic validate đã ổn định). Case 3 đã xác nhận vá
xong qua test UI thật (tải file lỗi thành công, nội dung khớp đúng JSON gốc).
