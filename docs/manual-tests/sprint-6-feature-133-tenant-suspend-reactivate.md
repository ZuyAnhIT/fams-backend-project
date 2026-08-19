# Kịch bản test thủ công — #133 Khóa/mở tenant

**Nền tảng: Backend, Web Admin.**

ℹ️ Audit gốc (07-22): 🟡 LÀM MỘT PHẦN — bằng chứng: `TenantService.suspendTenant`/
`reactivateTenant`, `test_tenant_status.sh`; thiếu: không gửi notification; không ghi audit. Audit
lại code hiện tại (2026-08-19) — **audit gốc SAI/lỗi thời trên 1 trong 2 điểm**:

- **✅ Ghi audit: ĐÃ CÓ ĐÚNG TỪ TRƯỚC** — `recordTenantAudit` helper đã gọi trong cả
  `suspendTenant`/`reactivateTenant`, dùng `AuditLogService.record()` đã là `REQUIRES_NEW` nên
  không dính bug âm thầm như #124. Audit gốc SAI trên điểm này.
- **❌ GAP thật (đã xác nhận đúng): hoàn toàn không gửi notification.**
- **✅ Chặn login/action theo policy: ĐÃ CÓ, thậm chí mạnh hơn AC yêu cầu** — không chỉ chặn lúc
  login (3/4 luồng: password/Google/refresh-token), mà `JwtAuthFilter` còn chặn MỌI request có
  JWT hợp lệ ngay khi tenant bị suspend giữa phiên, không cần đợi token hết hạn.
- **🟡 GAP nhỏ phát hiện thêm: `FirebasePhoneLoginService` (luồng đăng nhập bằng SĐT) là luồng
  DUY NHẤT trong 4 luồng đăng nhập KHÔNG kiểm tra tenant suspended** — tuy tác động thực tế bị
  giới hạn vì `JwtAuthFilter` vẫn chặn ngay request tiếp theo, nhưng không nhất quán.

## ✅ ĐÃ VÁ (2026-08-19)
- Thêm notification `TENANT_SUSPENDED_OWNER`/`TENANT_REACTIVATED_OWNER` — gửi cho **chủ tenant**
  (không phải toàn bộ user trong tenant, vì chủ tenant là người chịu trách nhiệm liên hệ hỗ trợ),
  đăng ký vào `NotificationEventTypeCatalog` để hiện trong màn cài đặt thông báo. Best-effort
  (try/catch), không làm hỏng luồng suspend/reactivate chính nếu gửi thất bại.
- Thêm check tenant suspended còn thiếu vào `FirebasePhoneLoginService`, đồng bộ với 3 luồng còn
  lại.

---

## A. Test trên Backend

### 1. ✅✅ (Case quan trọng nhất) Suspend/reactivate gửi đúng notification + ghi đúng audit
- **Kỳ vọng — xác nhận đúng qua live API call:** suspend tenant → query `notifications` cho
  `user_id=owner_id` thấy `event_type='TENANT_SUSPENDED_OWNER'`; `audit_logs` thấy
  `action='tenant_suspended'`. Reactivate → tương tự với `TENANT_REACTIVATED_OWNER`/
  `tenant_reactivated`.

### 2. ✅ Set status đúng, chặn login/action đúng (không đổi hành vi cũ, đã hoạt động tốt từ trước)

## B. Test trên Web Admin

### 3. ✅ Nút Suspend/Reactivate trên danh sách tenant — không đổi, đã xác nhận hoạt động từ trước

---

## Ghi chú
Backend regression (`tests/tenant/*.sh`, `tests/auth/*.sh`) 91/91 pass cùng đợt #131-135. Không
có UI riêng cho "tenant của bạn đang bị khóa" phía user thường (chỉ có generic API-error toast) —
ghi nhận là gap nhỏ ngoài phạm vi AC, chưa vá trong đợt này.
