# Kịch bản test thủ công — #61 Danh sách ca theo site

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không tìm theo tên/mã, không có khái niệm 'default'". Đã xác nhận
lại qua code — **cả 2 gap là thật, đã VÁ (2026-08-17).**

- **Tìm theo tên: ĐÃ VÁ** — thêm query param `search` (case-insensitive substring match trên
  `name`), triển khai qua `ShiftSpecification` (Criteria API, theo đúng pattern đã có sẵn trong
  codebase ở `AssignmentSpecification`/`EmployeeSpecification`) thay vì JPQL `@Query` thô — lý do kỹ
  thuật quan trọng: bản đầu dùng JPQL với `CAST(:param AS ...)` cho tham số optional bị lỗi
  **"cannot cast type bytea to boolean"** khi tham số truyền null (Postgres/Hibernate 6 không suy
  luận đúng kiểu cho bind parameter null khi đứng trong `CAST()`, dù đã ép kiểu tường minh) — phát
  hiện qua chính vòng test hồi quy (script `test_list_shifts.sh` từ pass chuyển sang lỗi 500 ngay
  sau khi thêm tính năng), chuyển hẳn sang Specification để tránh toàn bộ lớp lỗi này (Criteria API
  build predicate có kiểu tường minh ở tầng Java, không phụ thuộc suy luận kiểu của driver).
- **"Mã" (code): KHÔNG có gì để tìm** — vì trường `code` không tồn tại (xác nhận cùng #59), chỉ tìm
  theo tên.
- **Khái niệm "default": ĐÃ VÁ** — thêm query param `isDefault` (boolean) lọc theo cờ mặc định mới
  (xem #59). "Chỉ một ca default active mỗi site" tự động đúng vì tại-most-1-default-per-site được
  enforce ở tầng DB (unique index).
- **Sort cố định theo `startTime`:** giữ nguyên, không đổi trong đợt vá này (không phải gap theo AC
  gốc, chỉ là ghi chú).
- **`assignmentHistoryCount`/`canDelete`:** giữ nguyên, không đổi.

---

## A. Test trên Web Admin — ĐÃ TEST LIVE (2026-08-17)

### 1. ✅ Tìm kiếm theo tên ca — ĐÃ VÁ, TEST LIVE qua UI thật
- Gõ "morn" vào ô tìm kiếm mới trên tab "Ca làm việc" (ảnh `shift-03-search.png`).
- **Kết quả thật:** danh sách lọc ngay còn đúng 1 kết quả "Morning Shift" (khớp một phần, không
  phân biệt hoa/thường) — xác nhận đúng gap đã vá, hoạt động real-time qua React Query.

### 2. Lọc theo trạng thái active/inactive
- Không đổi, script `test_list_shifts.sh` test 3-4 xác nhận không hồi quy.

### 3. ✅ Xác nhận có badge "Mặc định" trên UI — ĐÃ VÁ
- Xem ảnh `shift-02-list.png`/`shift-06-after-create.png`.
- **Kết quả thật:** cột "Tên ca" hiện tag vàng "Mặc định" ngay cạnh tên khi `isDefault=true` — xác
  nhận đúng, không phải thiếu hiển thị 1 dữ liệu đã có.

### 4. ✅ Lọc theo `isDefault=true` qua API
- `GET .../shifts?isDefault=true`.
- **Kết quả thật:** trả về đúng 1 kết quả — đúng ca đang là mặc định của site, xác nhận tại-most-1
  hoạt động đúng qua cả API lẫn dữ liệu thật (2 lần tạo default liên tiếp → chỉ 1 tồn tại).

### 5. Phân trang
- Script test 5-6, không đổi, không hồi quy.

### 6. Site không có ca nào trả về danh sách rỗng
- Script test 8, không đổi.

---

## Ghi chú
Toàn bộ 6 case đã test live: script tự động `test_list_shifts.sh` 11/11 pass (sau khi sửa từ lỗi
500 sang Specification pattern) + Playwright qua UI thật xác nhận ô tìm kiếm + badge mặc định hoạt
động đúng. Bài học kỹ thuật quan trọng ghi lại: **tránh dùng JPQL `@Query` với `CAST(:param AS X)`
cho tham số nullable trên PostgreSQL qua Hibernate 6 — dùng `JpaSpecificationExecutor` +
`Specification` (Criteria API) cho mọi trường hợp filter optional từ nay về sau**, để tránh lặp lại
đúng lỗi "cannot cast type bytea to X" đã gặp ở đợt này. Đã đóng — ĐÃ KHÓA.
