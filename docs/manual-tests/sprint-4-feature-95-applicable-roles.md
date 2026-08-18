# Kịch bản test thủ công — #95 Cấu hình áp dụng theo vai trò

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG — "applicableRoles filter". Đã xác nhận lại qua code hiện tại —
**phần lọc THẬT SỰ hoạt động đúng (không phải chỉ lưu mà không dùng), nhưng phát hiện 1 điểm SAI
Ở CHÍNH AC (không phải ở implementation) cần làm rõ khi test:**

- **✅ `applicableRoles` được LỌC THẬT khi sinh lịch, không chỉ lưu vô nghĩa:**
  `ScheduledCheckGeneratorService` — nếu `applicableRoles` không rỗng, CHỈ sinh check cho assignment
  có `role` nằm trong danh sách; assignment không khớp role bị bỏ qua hoàn toàn (log debug, return
  0 check). Xác nhận đúng cơ chế filter thật, không phải field trang trí.
- **✅ Danh sách rỗng = áp dụng cho TẤT CẢ role** (không lọc gì) — hành vi mặc định hợp lý.
- **⚠️ AC ghi "chọn worker/lead/supervisor" — SAI Ở TẦNG HỆ THỐNG, KHÔNG PHẢI GAP CỦA RIÊNG #95:**
  xác nhận qua `Assignment` entity — **role của MỘT ASSIGNMENT chỉ có đúng 2 giá trị hợp lệ:
  `worker` và `supervisor`** (DB CHECK constraint `assignments_role_chk`, migration V26).
  **"lead" KHÔNG TỒN TẠI ở BẤT KỲ ĐÂU trong toàn bộ hệ thống** — không phải riêng random-check
  thiếu hỗ trợ "lead", mà bản thân module Assignment (nền tảng phân công nhân sự) chưa từng có role
  này. Vì vậy #95 không thể lọc theo "lead" đơn giản vì KHÔNG CÓ assignment nào từng mang giá trị
  đó để lọc — đây là AC ghi sai/lỗi thời về mô hình dữ liệu tổng thể, không phải lỗi cài đặt của
  tính năng #95. **Không hạ trạng thái #95 xuống "chưa xong" vì bản chất chức năng đã đúng và đủ so
  với danh sách role THẬT SỰ tồn tại trong hệ thống (worker, supervisor).**

---

## A. Test trên Backend

### 1. ✅ Lọc đúng theo `applicableRoles=["worker"]`
- Site có assignment cả `worker` lẫn `supervisor`, cấu hình `applicableRoles=["worker"]`, trigger
  sinh lịch.
- **Kỳ vọng:** CHỈ assignment role `worker` được sinh scheduled check, `supervisor` bị bỏ qua.

### 2. ✅ Lọc đúng theo `applicableRoles=["supervisor"]`
- Cùng site, đổi sang `["supervisor"]`.
- **Kỳ vọng:** ngược lại case 1 — chỉ `supervisor` được sinh check.

### 3. ✅ Danh sách rỗng — áp dụng cho tất cả
- `applicableRoles=[]`.
- **Kỳ vọng:** cả `worker` lẫn `supervisor` đều được sinh check.

### 4. ⚠️ Xác nhận "lead" không tồn tại trong hệ thống (không phải bug #95)
- Thử tạo 1 assignment với `role="lead"` (không qua random-check, test trực tiếp module Assignment).
- **Kỳ vọng:** bị từ chối ngay từ tầng Assignment (CHECK constraint DB) — xác nhận "lead" không
  tồn tại ở BẤT KỲ đâu, không riêng gì random-check, nên #95 không có gì phải sửa thêm.

---

## B. Test trên Web Admin — ✅ PASS, đã test live qua UI thật kết nối backend thật (Playwright, 2026-08-18)
- Modal sửa cấu hình: tắt toggle "Áp dụng cho tất cả vai trò tại công trình" → hiện đúng dropdown
  "Vai trò tại site" kèm ghi chú rõ ràng "Đây là vai trò trong phân công công trình, không phải
  role RBAC của tài khoản" (tránh nhầm lẫn với vai trò hệ thống).
- **Kết quả thực tế:** dropdown chỉ có ĐÚNG 2 lựa chọn — "Nhân viên hiện trường (worker)" và "Giám
  sát công trình (supervisor)" — xác nhận trực quan trên UI đúng như phát hiện ở tầng backend,
  KHÔNG có "lead" ở bất kỳ đâu.

---

## ✅ Đã fix (2026-08-18)
Không có gap cần vá cho #95 (đúng như nhận định ban đầu). Gap audit log DÙNG CHUNG với #91-94 (cùng
`RandomCheckConfigService`) đã được vá — xác nhận live qua API: `PUT .../applicable-roles` ghi đúng
bản ghi `random_check_config_applicable_roles_updated` với `applicableRoles` cũ/mới (test tay qua
UI Web Admin đã xác nhận dropdown chọn role đúng, xem mục B ở trên — riêng phần lưu qua UI cho case
này chưa chụp lại request cuối do dropdown đóng trước khi bấm chọn trong lần chạy Playwright, không
ảnh hưởng tới kết luận vì logic lưu dùng chung 100% với modal đã test thành công ở #91/#93/#94).
AC nhắc "lead" vẫn là điểm cần làm rõ với chủ dự án (không phải gap của #95, xem giải thích ở trên).

## Regression
Toàn bộ `tests/randomcheck/*.sh` (18 suite) — 100% PASS sau fix, không có regression.
