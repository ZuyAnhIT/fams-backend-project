# Kịch bản test thủ công — #47 Chuyển workspace cho nhân viên

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi thiếu: "giữ lịch sử tốt nhưng thiếu is_primary/effective date".

**ĐÃ VÁ (2026-08-16), cùng đợt migration V96 với #46:**
- Bản ghi cũ khi transfer giờ set thêm `left_at` (riêng biệt với `deletedAt` chung) — ghi rõ "rời
  vì được chuyển đi" thay vì chỉ dựa vào cột soft-delete dùng chung cho mọi trường hợp xóa.
- `isPrimary` được **carry-over mặc định**: nếu membership đang chuyển là primary, membership mới
  ở workspace đích cũng tự động là primary (người dùng không cần làm gì thêm để giữ trạng thái
  "chính" sau khi chuyển phòng ban) — có thể override tường minh qua dropdown 3 lựa chọn "Giữ
  nguyên / Đặt làm chính / Không phải chính" trên `TransferMemberModal.tsx`.
- `effectiveFrom` cho membership mới: mặc định hôm nay, có thể chỉnh qua DatePicker mới thêm.
- Yêu cầu quyền **CẢ HAI** `workspace_members:create` VÀ `workspace_members:delete` giữ nguyên
  (không đổi, đã đúng từ trước) — không phải gap, chỉ là điểm cần test riêng theo ma trận quyền.

---

## A. Test trên Web Admin

### 1. Chuyển nhân viên từ workspace A sang workspace B — happy path
- Chọn 1 nhân viên đang là thành viên workspace A, dùng chức năng "Chuyển workspace", chọn workspace
  B làm đích.
- **Kỳ vọng:** chuyển thành công, nhân viên KHÔNG còn hiện trong danh sách thành viên active của A,
  và CÓ hiện trong danh sách thành viên active của B.

### 2. Chuyển kèm đổi vai trò (role override)
- Chuyển 1 nhân viên khác sang workspace mới, đồng thời chọn vai trò khác với vai trò cũ.
- **Kỳ vọng:** bản ghi mới ở workspace đích có đúng vai trò mới đã chọn, không giữ nguyên vai trò
  cũ.

### 3. ✅ Xác nhận `left_at` ghi nhận đúng khi chuyển — gap đã vá
- Sau case 1, kiểm tra bản ghi cũ ở workspace A (qua DB/API trực tiếp — UI danh sách thành viên chỉ
  hiện active).
- **Kết quả thật (2026-08-16):** bản ghi cũ có `left_at` = đúng thời điểm chuyển, tách biệt với
  `deleted_at` (cũng được set cùng lúc, nhưng `left_at` là field ý nghĩa nghiệp vụ riêng, không lẫn
  với xóa vì lý do khác).

### 4. ✅ Xác nhận `isPrimary` carry-over đúng khi chuyển workspace chính — gap đã vá
- Chuyển 1 nhân viên đang có workspace chính (tag "Chính") sang workspace mới, KHÔNG chọn override
  trong dropdown "Workspace chính" (để mặc định "Giữ nguyên như hiện tại").
- **Kết quả thật:** workspace đích tự động nhận tag "Chính", workspace nguồn hết còn hiển thị (đã
  bị đóng). Test live end-to-end qua API: chuyển 1 membership `isPrimary=true` từ workspace A sang
  C → xác nhận C có `is_primary=true` trong DB ngay sau đó, không cần gọi thêm API nào khác.

### 5. Chuyển khi tài khoản chỉ có 1 trong 2 quyền cần thiết
- Đăng nhập tài khoản chỉ có `workspace_members:create` (không có `:delete`), hoặc ngược lại, thử
  chuyển 1 nhân viên.
- **Kỳ vọng:** bị chặn 403 — xác nhận đúng yêu cầu "cần cả 2 quyền cùng lúc" (hành vi đúng từ
  trước, không phải gap, chỉ cần xác nhận qua UI thật).

### 6. Chuyển nhân viên không thuộc workspace nguồn (dữ liệu sai/đã bị chuyển từ trước)
- Thử gọi chuyển cho 1 memberId không còn active (đã bị soft-delete từ trước, VD do đã chuyển 1
  lần).
- **Kỳ vọng:** báo lỗi rõ ràng (404/400), không tạo thêm bản ghi rác.

### 7. Ghi đè `isPrimary` khi chuyển (không dùng mặc định carry-over)
- Chuyển 1 nhân viên KHÔNG phải primary ở workspace nguồn, nhưng chọn tường minh "Đặt làm workspace
  chính" trong dropdown khi chuyển.
- **Kỳ vọng:** membership mới ở đích thành primary (demote bất kỳ primary cũ nào khác của nhân viên
  đó — dùng chung logic demote với #46), dù membership gốc không phải primary.

---

## Ghi chú
Toàn bộ 7 case đã test (case 3-4 qua API trực tiếp để xác nhận chính xác giá trị DB; case 1-2, 5-7
qua luồng UI thật). Case 3-4 là trọng tâm xác nhận 2 gap đã biết từ audit gốc đã vá đúng.
