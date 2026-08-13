# Kịch bản test thủ công — #21 Cấu hình giới hạn gói

**Nền tảng: Backend, Web Admin** (Platform Admin only).

ℹ️ Khác #19 (IP whitelist — chưa enforce), tính năng này **có enforcement thật**:
`PlanLimitEnforcementService` chặn khi vượt giới hạn nhân viên/site/random-check. Test enforcement
thật cần tenant có sẵn dữ liệu (nhân viên/site) — nếu bạn chưa tới Sprint 2, có thể tạm hoãn case
3-5 (enforcement) và chỉ test case 1-2 (cấu hình) trước, quay lại sau khi có dữ liệu nhân viên/site
thật.

---

## A. Test trên Web Admin

### 1. Xem/sửa giới hạn của 1 gói
- Đăng nhập Platform Admin, vào chi tiết 1 gói (VD: `trial`) → tab Giới hạn.
- Sửa `maxEmployees` (VD: 5), `maxSites` (VD: 1), lưu.
- **Kỳ vọng:** lưu thành công, hiển thị đúng giá trị mới.

### 2. Nhập số âm — phải bị chặn
- Nhập `maxEmployees = -1`.
- **Kỳ vọng:** lỗi validate rõ ràng, không lưu được giá trị âm.

### 3. Enforcement — tạo nhân viên vượt giới hạn (cần dữ liệu Sprint 2, có thể hoãn)
- Với 1 tenant đang dùng gói có `maxEmployees = 5` và đã có sẵn 5 nhân viên active, thử tạo thêm
  nhân viên thứ 6.
- **Kỳ vọng:** bị từ chối, lỗi rõ ràng kiểu "Đã đạt giới hạn số nhân viên theo gói hiện tại, vui
  lòng nâng cấp gói hoặc liên hệ hỗ trợ."

### 4. Enforcement — tạo site vượt giới hạn (có thể hoãn, tương tự case 3)
- Tương tự case 3 nhưng với `maxSites`.
- **Kỳ vọng:** bị từ chối tương tự.

### 5. Enforcement — random check vượt hạn mức tháng (có thể hoãn tới Sprint 4)
- Nếu tenant đã dùng hết quota random check tháng này (theo gói), thử tạo thêm 1 random check thủ
  công.
- **Kỳ vọng:** bị từ chối.

### 6. Giới hạn để trống = không giới hạn
- Đặt `maxSites` để trống/null cho 1 gói (nếu UI hỗ trợ).
- **Kỳ vọng:** tenant dùng gói đó tạo site không giới hạn số lượng.

---

## Ghi chú
Case 1-2 test được ngay. Case 3-5 cần dữ liệu nhân viên/site/random-check thật — nếu bạn đang test
theo đúng thứ tự Sprint (chưa tới Sprint 2/4), đánh dấu 3 case này là "hoãn tới sau", không tính là
fail, quay lại làm khi tới đúng phần đó.
