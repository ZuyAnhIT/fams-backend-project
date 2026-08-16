# Kịch bản test thủ công — #46 Gán nhân viên vào workspace

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "không có effective_from/is_primary".

**ĐÃ VÁ (2026-08-16):**
- Thêm cột `is_primary` (BOOLEAN), `effective_from` (DATE), `left_at` (TIMESTAMPTZ) vào
  `workspace_members` (migration `V96__workspace_member_primary_and_effective_dates.sql`).
- Enforce "chỉ một primary workspace active" bằng **2 lớp**: (1) tầng ứng dụng —
  `WorkspaceMemberService` tự động demote primary cũ khi gán/chuyển một membership mới thành
  primary; (2) tầng DB — partial unique index
  `idx_workspace_members_one_primary_per_employee` trên `(tenant_id, employee_id) WHERE is_primary
  AND deleted_at IS NULL`, chặn ngay cả khi có bug ở tầng ứng dụng hoặc ai đó sửa DB trực tiếp.
- Mặc định thông minh: nếu không truyền `isPrimary`, hệ thống tự đặt `true` CHỈ KHI nhân viên chưa
  có primary nào khác (workspace đầu tiên tự động thành chính); truyền `true` tường minh luôn thắng
  và demote workspace chính cũ.
- Web Admin: `AddMemberModal.tsx` thêm ô "Ngày hiệu lực (Tùy chọn)" (DatePicker) và switch "Đặt
  làm workspace chính". Danh sách thành viên (`WorkspacePage.tsx`) và tab Workspace ở trang chi
  tiết nhân viên (`EmployeeWorkTab.tsx`) đều hiện tag vàng "Chính" khi `isPrimary=true`.
- **Bug phát hiện và vá trong lúc test sống**: do Hibernate mặc định flush INSERT trước UPDATE
  trong cùng transaction, thao tác "demote workspace chính cũ rồi tạo membership chính mới" bị
  race — INSERT chạy trước khi UPDATE demote kịp ghi xuống DB, vi phạm unique index. Đã sửa bằng
  `saveAndFlush` cho bước demote để đảm bảo thứ tự thực thi đúng.
- **Bug JSON phát hiện và vá**: field `boolean isPrimary` qua Lombok + Jackson mặc định serialize
  ra CẢ HAI key `"isPrimary"` và `"primary"` trùng lặp (đúng gotcha "Lombok isXxx() duplicate JSON
  keys" đã biết từ đợt audit RBAC trước). Đã vá bằng `@JsonProperty("isPrimary")` trên field +
  `@JsonIgnoreProperties({"primary"})` ở class để loại bỏ key trùng.

---

## A. Test trên Web Admin

### 1. Gán nhân viên vào workspace — happy path
- Vào 1 workspace, "Thêm thành viên", chọn 1 nhân viên chưa thuộc workspace này, chọn vai trò.
- **Kỳ vọng:** gán thành công, nhân viên xuất hiện trong danh sách thành viên workspace đó ngay.

### 2. Gán trùng — nhân viên đã là thành viên active của workspace đó
- Thử gán lại đúng nhân viên vừa gán ở case 1 vào cùng workspace đó.
- **Kỳ vọng:** báo lỗi rõ ràng (409 hoặc validate), không tạo bản ghi trùng.

### 3. ✅ Gán cùng 1 nhân viên vào NHIỀU workspace — workspace đầu tiên tự động thành chính
- Gán nhân viên ở case 1 vào thêm 1 workspace khác (workspace B), không bật switch "Đặt làm chính".
- **Kết quả thật (2026-08-16):** workspace đầu tiên (case 1) tự động là "Chính" (tag vàng), workspace
  B mới gán KHÔNG phải chính — đúng mặc định thông minh, không cần thao tác gì thêm.

### 4. ✅ Xác nhận `is_primary` hoạt động đúng — gap đã vá
- Bật switch "Đặt làm workspace chính" khi gán vào 1 workspace thứ 3 (workspace C).
- **Kết quả thật:** workspace C ngay lập tức thành "Chính", đồng thời workspace cũ (case 1) tự động
  mất tag "Chính" — xác nhận demote-tự-động hoạt động đúng, tại cả trang danh sách thành viên
  workspace VÀ tab Workspace ở trang chi tiết nhân viên (2 nơi hiển thị đồng bộ).

### 5. ✅ Xác nhận `effective_from` hoạt động đúng — gap đã vá
- Gán 1 nhân viên khác, chọn "Ngày hiệu lực" là 1 ngày trong tương lai qua DatePicker.
- **Kết quả thật:** lưu đúng ngày đã chọn, khác với `createdAt` (thời điểm tạo bản ghi thật). Để
  trống thì mặc định là ngày hôm nay.

### 6. Gán với vai trò khác nhau (member/lead/manager)
- Gán 1 nhân viên khác vào workspace với vai trò khác.
- **Kỳ vọng:** lưu đúng vai trò đã chọn, hiển thị đúng trên danh sách thành viên.

### 7. ✅ Xác nhận DB chặn được vi phạm "2 primary cùng lúc" ngay cả khi bypass tầng ứng dụng
- (Test kỹ thuật, không qua UI) Thử `UPDATE workspace_members SET is_primary=true` trực tiếp bằng
  SQL cho 1 bản ghi thứ 2 của cùng nhân viên đã có primary.
- **Kết quả thật:** bị chặn bởi partial unique index `idx_workspace_members_one_primary_per_employee`
  — bảo vệ dữ liệu ở tầng DB, không chỉ dựa vào code ứng dụng.

---

## Ghi chú
Toàn bộ 7 case đã test live và pass. Case 3-4 là trọng tâm xác nhận đúng nghiệp vụ "chỉ 1 primary
active". Trong lúc test phát hiện và vá thêm 1 race condition (Hibernate flush order) và 1 lỗi JSON
key trùng (Lombok/Jackson) — cả hai không nằm trong audit gốc, phát sinh từ chính code mới viết.
