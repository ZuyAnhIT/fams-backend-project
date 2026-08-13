# Kịch bản test thủ công — #19 Quản lý IP whitelist

**Nền tảng: Backend, Web Admin.**

## Lịch sử phát hiện (để hiểu bối cảnh, không cần đọc để test)
1. Bản đầu tiên báo nhầm "chưa enforce" — sai, đã đính chính.
2. Bản đính chính phát hiện gap thật: có enforce, nhưng field "Phạm vi áp dụng" cũ
   (`web_admin`/`api`/`all`) không thể thực thi đúng nghĩa vì Web Admin và Mobile App gọi chung
   1 API — backend không phân biệt được nguồn gọi. Chọn "chỉ Web Admin" vẫn khóa luôn app di động
   của nhân viên công trình.
3. **Đã sửa (2026-08-13):** thay "phạm vi theo loại client" bằng **"phạm vi theo role"** — mỗi
   entry IP chọn áp dụng cho role cụ thể (VD: chỉ Company Admin + HR), để trống = áp dụng mọi
   role. Role không được chọn (VD: Site Supervisor, Field Employee) **không bị ảnh hưởng** —
   đúng nhu cầu thực tế: giới hạn IP văn phòng cho khối quản trị, nhân viên hiện trường vẫn
   check-in được từ bất kỳ đâu. Backend đã tự kiểm chứng qua API (owner bị chặn đúng khi sai IP,
   nhân viên hiện trường KHÔNG bị chặn dù sai IP) — file này là để bạn xác nhận lại qua UI thật.

---

## A. Test CRUD trên Web Admin

### 1. Thêm entry IP/CIDR — áp dụng cho tất cả role
- Đăng nhập chủ sở hữu tenant, vào Cài đặt Bảo mật → IP Whitelist → Thêm mới.
- Nhập 1 IP đơn (VD: `203.0.113.10`), để trống ô "Áp dụng cho role".
- **Kỳ vọng:** thêm thành công, cột "Áp dụng cho role" hiện tag "Tất cả role".

### 2. Thêm entry — chỉ áp dụng cho 1 vài role cụ thể
- Thêm 1 IP khác (VD: `203.0.113.0/24`), chọn role trong ô multi-select (VD: chọn
  `TENANT_ADMIN`, `HR_MANAGER` — gõ để tìm nếu danh sách dài).
- **Kỳ vọng:** thêm thành công, cột "Áp dụng cho role" hiện đúng 2 tag role đã chọn (không phải
  "Tất cả role").

### 3. Thêm trùng IP
- Thêm lại đúng IP/CIDR đã có ở case 1.
- **Kỳ vọng:** lỗi 409 "đã tồn tại".

### 4. Bật/tắt entry
- Tắt tạm 1 entry qua công tắc trong bảng.
- **Kỳ vọng:** trạng thái đổi ngay, không cần reload.

---

## B. Test enforcement thật theo role (quan trọng nhất)

Cần 2 tài khoản trong cùng 1 tenant: 1 tài khoản role quản trị (VD: chủ tenant, `TENANT_ADMIN`),
1 tài khoản role hiện trường (VD: mời/tạo 1 nhân viên role `EMPLOYEE` — có thể để dành case này
tới khi tới Sprint 2 nếu tenant test chưa có nhân viên nào).

### 5. Role bị giới hạn — sai IP thì bị chặn
- Sửa 1 entry đang có ở case 2 (chỉ áp dụng `TENANT_ADMIN`) thành đúng IP máy bạn đang dùng để
  test, xác nhận chủ tenant (role `TENANT_ADMIN`) vẫn dùng được bình thường.
- Sửa entry đó sang 1 IP khác không phải máy bạn.
- **Kỳ vọng:** chủ tenant bị từ chối truy cập (thông báo "Truy cập bị từ chối do địa chỉ IP
  không nằm trong danh sách cho phép của công ty bạn."), không vào được Web Admin nữa.

### 6. Role KHÔNG bị giới hạn — vẫn dùng bình thường dù "sai IP"
- Với entry ở case 5 vẫn đang set sai IP (chỉ áp dụng `TENANT_ADMIN`), đăng nhập bằng tài khoản
  nhân viên role `EMPLOYEE`/`SITE_SUPERVISOR` (không nằm trong danh sách role của entry đó) từ
  máy/mạng bất kỳ (kể cả không phải IP đã whitelist).
- **Kỳ vọng:** vẫn đăng nhập/dùng được bình thường — đây là điểm quan trọng nhất cần xác nhận,
  chứng minh gap cũ đã được sửa đúng.

### 7. Platform Admin luôn không bị chặn
- Với tenant đang ở chế độ chặn (case 5), đăng nhập Platform Admin (tài khoản FAMS staff, không
  thuộc tenant đó) để hỗ trợ tenant.
- **Kỳ vọng:** không bị chặn bởi whitelist của tenant.

### 8. Tự khóa mình — bị chặn khi sửa
- Là chủ tenant, thử sửa entry đang whitelist đúng IP bạn dùng sang 1 IP khác (tự làm mình mất
  quyền truy cập).
- **Kỳ vọng:** bị từ chối lưu, thông báo rõ "thay đổi này sẽ khiến IP hiện tại của bạn bị khóa" —
  không cho lưu.

---

## Dọn dẹp sau khi test
Xóa/tắt các entry whitelist test đã thêm, tránh vô tình khóa người test khác sau này.

## Ghi chú
Case 1-4, 7-8 dễ test ngay. Case 5-6 (trọng tâm) cần 2 tài khoản khác role trong cùng tenant —
nếu tenant test hiện chưa có nhân viên role hiện trường, có thể tạm dùng 2 tài khoản
`TENANT_ADMIN` khác nhau để test cơ chế "role không nằm trong danh sách entry thì không bị chặn"
một cách gián tiếp (tạo entry chỉ áp dụng cho role khác hẳn role bạn đang có).
