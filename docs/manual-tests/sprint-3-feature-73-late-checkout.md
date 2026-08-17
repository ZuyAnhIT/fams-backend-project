# Kịch bản test thủ công — #73 Kiểm tra check-out muộn

**Nền tảng: Backend, Mobile App.**

ℹ️ Audit gốc (07-22): ✅ ĐÃ XONG. Đã xác nhận lại qua code (`computeWorkMinutes`, dùng chung với
#74) — **đúng tinh thần AC, có nuance quan trọng về nguồn dữ liệu dùng để tính:**

- **`allowOvertime=true`:** giờ kết thúc hiệu lực = `shiftEnd + lateCheckoutMinutes`; phần vượt quá
  mốc này đơn giản KHÔNG được tính vào `workMinutes` (không phải lỗi, chỉ là bị cắt bớt).
- **`allowOvertime=false`:** giờ kết thúc hiệu lực = ĐÚNG `shiftEnd`, KHÔNG cộng thêm
  `lateCheckoutMinutes` dù giá trị này có được cấu hình — tức OT chỉ được tính khi ca cho phép OT.
- **Check-out KHÔNG BAO GIỜ bị chặn vì muộn** — luôn cho phép check-out, chỉ số phút được TÍNH bị
  giới hạn theo 2 quy tắc trên.
- **QUAN TRỌNG: toàn bộ giá trị dùng để tính (giờ kết thúc ca, allowOvertime, lateCheckoutMinutes,
  allowOvernight) đều lấy từ bản SNAPSHOT đã chụp ngay lúc check-in, KHÔNG lấy lại từ Shift hiện
  tại.** Nếu HR sửa `lateCheckoutMinutes` của ca giữa lúc nhân viên đang làm việc (đã check-in,
  chưa check-out), phép tính vẫn dùng giá trị CŨ đã snapshot, không áp dụng giá trị mới.
- **"Ghi metadata" theo AC gốc: chỉ là 1 dòng log ứng dụng, KHÔNG lưu vào DB** — không có field nào
  lưu lại "đã bị cắt bớt bao nhiêu phút" trên bản ghi check-in.

---

## A. Test trên Mobile App

### 1. Check-out đúng giờ hoặc sớm hơn giờ kết thúc ca — happy path
- Check-out trước hoặc đúng giờ kết thúc ca.
- **Kỳ vọng:** `workMinutes` tính đúng bằng thời gian thực tế làm việc.

### 2. Check-out muộn, ca cho phép OT (`allowOvertime=true`)
- Check-out sau giờ kết thúc ca nhưng trong giới hạn `lateCheckoutMinutes`.
- **Kỳ vọng:** `workMinutes` tính đủ cả phần muộn (trong giới hạn).

### 3. Check-out muộn vượt quá giới hạn OT
- Check-out muộn hơn `shiftEnd + lateCheckoutMinutes`.
- **Kỳ vọng:** `workMinutes` chỉ tính đến đúng mốc `shiftEnd + lateCheckoutMinutes`, phần vượt
  thêm không được tính — nhưng check-out vẫn THÀNH CÔNG (không bị chặn).

### 4. Check-out muộn, ca KHÔNG cho phép OT (`allowOvertime=false`)
- Check-out muộn hơn giờ kết thúc ca tại 1 ca có `allowOvertime=false`.
- **Kỳ vọng:** `workMinutes` chỉ tính đến đúng `shiftEnd`, không cộng thêm dù có cấu hình
  `lateCheckoutMinutes` > 0.

### 5. ✅ Xác nhận dùng snapshot, không dùng cấu hình mới của Shift
- Nhân viên check-in với ca có `lateCheckoutMinutes=30`. Trong lúc đang làm, HR sửa ca thành
  `lateCheckoutMinutes=5`. Nhân viên check-out muộn 20 phút.
- **Kỳ vọng theo code hiện tại:** vẫn tính đủ 20 phút OT (dùng giá trị CŨ = 30 phút đã snapshot),
  KHÔNG bị cắt theo giá trị mới = 5 phút — xác nhận đúng thiết kế snapshot.

---

## Ghi chú
Trọng tâm khi test: case 5 (snapshot vs cấu hình live — cùng nguyên lý thiết kế với #72 case 5,
quan trọng để hiểu đúng hành vi hệ thống) và case 3-4 (2 nhánh allowOvertime khác nhau, dễ nhầm lẫn
nếu không phân biệt rõ). Case 1-2 rủi ro fail thấp.

**ĐÃ TEST (2026-08-17):** không có gap, không sửa code — xác nhận đúng qua đọc code (dùng chung
`computeWorkMinutes` với #74, đã xác nhận snapshot đúng). Bao phủ gián tiếp qua `test_checkout.sh`
9/9 pass. Đã đóng — ĐÃ KHÓA.
