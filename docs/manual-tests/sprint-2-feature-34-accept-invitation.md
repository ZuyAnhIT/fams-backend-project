# Kịch bản test thủ công — #34 Chấp nhận lời mời

**Nền tảng: Backend, Web Admin, Mobile App.**

ℹ️ Audit gốc (07-22) ghi thiếu: "tạo user+role+employee nhưng không tạo WorkspaceMember". Đã xác
nhận lại qua code hiện tại — **gap vẫn còn thật**: `EmployeeInvitationService.acceptInvitation`
tạo/liên kết `User`, tạo `UserRole`, tạo/liên kết `Employee` — nhưng không đụng tới
`WorkspaceMemberService`/`WorkspaceMemberRepository` ở đâu cả. Workspace là khái niệm đã xây dựng
đầy đủ (không phải placeholder — có entity, API, migration riêng), nên đây là 1 bước tích hợp còn
thiếu, không phải tính năng chưa tồn tại. Ngoài ra phát hiện thêm 1 gap chưa từng nêu: chấp nhận
lời mời cũng **không ghi audit** (giống #33).

Tôi (Claude) đã tự test thành công luồng "chấp nhận lời mời" nhiều lần trong đợt RBAC #24-31
(2026-08-15) — gọi `POST /invitations/accept` chỉ với `token` (không cần password vì tài khoản
email đã tồn tại từ trước), xác nhận tạo đúng Employee có `userId` liên kết, role được gán đúng.
Phần đó không cần test lại kỹ — trọng tâm bây giờ là case 4 (workspace) và edge case token.

---

## A. Test trên Web Admin (giả lập luồng nhận email)

### 1. Chấp nhận lời mời — tài khoản email đã tồn tại
- Mời 1 email đã có tài khoản FAMS từ trước (case này KHÔNG cần đặt mật khẩu mới).
- Lấy token lời mời (qua email thật nếu có, hoặc nhờ kỹ thuật lấy trực tiếp nếu môi trường test
  không nhận được email), gọi chấp nhận.
- **Kỳ vọng:** thành công, trả về JWT đăng nhập luôn; Employee mới xuất hiện trong danh sách nhân
  viên, có tài khoản liên kết (không phải "Chưa đăng ký" nữa); role đã mời được gán đúng.

### 2. Chấp nhận lời mời — email hoàn toàn mới (tạo tài khoản)
- Mời 1 email chưa từng có tài khoản, chấp nhận kèm mật khẩu mới (≥8 ký tự).
- **Kỳ vọng:** tạo tài khoản mới thành công, đăng nhập được ngay bằng email + mật khẩu vừa đặt.

### 3. Chấp nhận với token hết hạn / đã dùng / không tồn tại
- Thử chấp nhận với 1 token không có thật (UUID ngẫu nhiên), và với 1 token đã chấp nhận rồi
  (chấp nhận lại lần 2).
- **Kỳ vọng:** báo lỗi rõ ràng (404 token không tồn tại; 409/422 nếu đã dùng), không tạo dữ liệu
  rác, không crash.

### 4. ⚠️ Xác nhận gap "không tạo WorkspaceMember"
- Sau khi chấp nhận lời mời thành công ở case 1 hoặc 2, kiểm tra nhân viên mới có xuất hiện trong
  bất kỳ workspace/phòng ban nào không (màn Phòng ban → xem thành viên, hoặc chi tiết nhân viên →
  tab Workspace nếu có).
- **Kỳ vọng theo code hiện tại:** KHÔNG tự động có workspace nào — HR/Admin phải vào riêng màn
  Phòng ban để gán thủ công sau đó. Nếu thực tế thấy đã tự động gán, ghi lại là tin tốt bất ngờ.

### 5. ⚠️ Xác nhận gap "không ghi audit" khi chấp nhận
- Sau case 1, kiểm tra Nhật ký audit có bản ghi nào cho hành động chấp nhận lời mời không.
- **Kỳ vọng theo code hiện tại:** không có. Ghi lại đúng thực tế quan sát được.

---

## B. Test trên Mobile App

### 6. Chấp nhận lời mời từ màn hình App (nếu có luồng riêng)
- Nếu App có màn nhập/dán link lời mời riêng, thử chấp nhận từ đó.
- **Kỳ vọng:** hành vi giống Web (cùng 1 API `POST /invitations/accept`), đăng nhập được ngay sau
  khi chấp nhận.

---

## Ghi chú
Case 1-3 phần lớn đã được xác nhận gián tiếp qua đợt test RBAC vừa rồi — trọng tâm khi bạn tự test
lại là case 3 (token lỗi) và case 4-5 (2 gap chưa sửa).
