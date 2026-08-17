# Kịch bản test thủ công — #60 Cấu hình OT và giới hạn giờ

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22) ghi: "✅ ĐÃ XONG". Đã xác nhận lại qua code — **audit gốc đúng, tính năng đã
được mở rộng thêm sau đó (V88); gap duy nhất phát hiện thêm là thiếu audit log, đã VÁ (2026-08-17).**

- **Bật/tắt OT, phút sớm/muộn, giới hạn OT ngày/tuần (cảnh báo, không chặn), snapshot vào
  `checkins`:** tất cả XÁC NHẬN hoạt động đúng như research ban đầu mô tả, không đổi gì thêm.
- **Gap mới: KHÔNG ghi audit log khi cấu hình OT — ĐÃ VÁ**, action `shift_ot_configured`, snapshot
  before/after đầy đủ (bao gồm cả field OT lẫn field chung của ca, dùng chung 1 helper snapshot với
  #59/#62 để nhất quán).

---

## A. Test trên Web Admin — ĐÃ TEST LIVE (2026-08-17)

### 1. ✅ Bật OT + nhập phút sớm/muộn — TEST LIVE qua API thật
- `PUT .../shifts/{id}/ot-config` với `{"allowOvertime":true,"earlyCheckinMinutes":15,
  "maxOtMinutesPerDay":120}` trên ca "Night Shift".
- **Kết quả thật:** 200, response trả đúng `allowOvertime:true, earlyCheckinMinutes:15,
  maxOtMinutesPerDay:120`, các field khác giữ nguyên (partial update đúng).

### 2. Nhập số âm cho phút sớm/muộn
- Script `test_shift_ot_config.sh` test 8-9.
- **Kết quả thật:** 400 (`@Min(0)` chặn đúng).

### 3. Đặt giới hạn OT ngày/tuần rồi xóa bằng cờ `clear`
- Script test tương ứng (không thay đổi trong đợt vá này, chỉ thêm audit).
- **Kết quả thật:** hoạt động đúng như trước, không hồi quy.

### 4. Gọi API rỗng
- Script test 7: body `{}`.
- **Kết quả thật:** 400 "phải có ít nhất 1 field" — không đổi.

### 5. ✅ Xác nhận gap "không ghi audit log khi cấu hình OT" — ĐÃ VÁ, TEST LIVE
- Sau case 1, kiểm tra `audit_logs` qua DB.
- **Kết quả thật (2026-08-17):** có đúng bản ghi `shift_ot_configured`, entity `Shift`,
  `old_value.allowOvertime=false` → `new_value.allowOvertime=true`, đầy đủ
  `earlyCheckinMinutes`/`maxOtMinutesPerDay` cũ-mới — xác nhận đúng before/after snapshot.

---

## Ghi chú
Toàn bộ 5 case đã test live: script tự động `test_shift_ot_config.sh` 12/12 pass (không hồi quy) +
test tay qua API/DB. Không có thay đổi hành vi nghiệp vụ nào ngoài việc bổ sung audit log — 2 hành
vi thiết kế quan trọng đã ghi trong kịch bản gốc (cảnh báo không chặn; snapshot không hồi tố) giữ
nguyên, không cần test lại chi tiết vì không bị ảnh hưởng bởi đợt vá này. Đã đóng — ĐÃ KHÓA.
