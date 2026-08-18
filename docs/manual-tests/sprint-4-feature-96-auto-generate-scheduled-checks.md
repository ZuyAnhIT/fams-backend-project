# Kịch bản test thủ công — #96 Tự động sinh scheduled checks đầu ca

**Nền tảng: Backend, Queue/AI/Automation.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — "chỉ random thời điểm, số lần check là cố định không random
trong min/max". Đã xác nhận lại qua code hiện tại — **KHÔNG PHẢI gap thật, AC ghi sai/lỗi thời so
với mô hình dữ liệu, khớp đúng phát hiện đã có ở #91 (RandomCheckConfig.checksPerShift là 1 số cố
định, KHÔNG hề tồn tại field min/max nào trong entity/DTO/migration):**

- **`ScheduledCheckGeneratorService.generateForAssignment`** — lấy assignment active, resolve
  config site trước rồi mới tới tenant (đúng thứ tự override), lọc theo role, giao khung giờ config
  với giờ ca thực tế, có kiểm tra quota tháng.
- **✅ Sinh ĐÚNG số lần = `checksPerShift`** (không phải random trong khoảng, vì làm gì có khoảng để
  random) — `checksToGenerate = min(config.checksPerShift, remaining)`, có test script
  `test_scheduled_check_generation.sh` xác nhận `checksPerShift=2` → sinh đúng 2 lần.
- **✅ Random THẬT SỰ chỉ áp dụng cho THỜI ĐIỂM mỗi lần check** — dùng `SecureRandom` +
  thuật toán "stars and bars" để phân bố ngẫu nhiên các mốc giờ trong khung cho phép, đảm bảo cách
  nhau tối thiểu `minIntervalMinutes` (test script assert khoảng cách ≥ 60 phút qua SQL).
- **Kết luận: KHÔNG có gì cần vá cho #96** — AC ghi "random số lần trong min/max" nhưng bản thân
  field `checksPerShift` không có khái niệm min/max để random (đã xác nhận qua #91), nên yêu cầu
  này về bản chất KHÔNG THỂ thực hiện được nếu không thay đổi schema (phạm vi thuộc quyết định
  chung với #91, không phải gap riêng của #96).

---

## A. Test trên Backend

### 1. ✅ Sinh đúng số lần = `checksPerShift` của config hiệu lực (site override hoặc tenant mặc định)
- Site có override `checksPerShift=3`, trigger sinh lịch cho 1 assignment active.
- **Kỳ vọng:** đúng 3 bản ghi `scheduled_checks` được tạo cho assignment đó.

### 2. ✅ Random thời điểm — không phải cùng 1 giờ cố định mỗi lần chạy
- Chạy sinh lịch nhiều lần (hoặc nhiều assignment cùng config), so sánh giờ các `scheduled_at`.
- **Kỳ vọng:** giờ khác nhau giữa các lần/assignment (trong cùng khung cho phép), không phải luôn
  cùng 1 giờ cố định.

### 3. ✅ Đảm bảo khoảng cách tối thiểu `minIntervalMinutes` giữa các lần check cùng ca
- Config `minIntervalMinutes=60`, `checksPerShift=3`, khung giờ đủ rộng.
- **Kỳ vọng:** khoảng cách giữa mọi cặp `scheduled_at` liền kề trong cùng ca ≥ 60 phút.

### 4. Chỉ sinh cho assignment ACTIVE
- Assignment đã hết hạn/hủy trong ngày đang xét.
- **Kỳ vọng:** KHÔNG sinh scheduled_check cho assignment đó.

### 5. Ưu tiên config site override hơn tenant mặc định
- Site có override, tenant cũng có mặc định khác.
- **Kỳ vọng:** sinh theo config CỦA SITE, không phải tenant mặc định.

### 6. Giao khung giờ config với giờ ca thực tế
- Config cho phép 6h-22h nhưng ca làm chỉ 8h-17h.
- **Kỳ vọng:** `scheduled_at` sinh ra chỉ nằm trong phần GIAO của 2 khung (8h-17h), không vượt ra
  ngoài giờ ca dù config cho phép rộng hơn.

---

## Ghi chú
Kịch bản này chưa được test live qua UI thật — mới hoàn tất bước nghiên cứu code + viết kịch bản.
**Không có gap thật cần vá cho #96** — chỉ cần làm rõ với chủ dự án rằng phần "random số lần trong
min/max" của AC gốc không khớp với mô hình dữ liệu thật (dùng chung quyết định với #91 về việc có
đổi `checksPerShift` sang khoảng min/max hay không). Case 1-6 rủi ro fail thấp, đã có
`test_scheduled_check_generation.sh` phủ khá đầy đủ các case cốt lõi.
