# Kịch bản test thủ công — #22 Gán subscription cho tenant

**Nền tảng: Backend, Web Admin** (Platform Admin only).

---

## A. Test trên Web Admin

### 1. Xem subscription hiện tại của tenant
- Vào chi tiết 1 tenant → tab Subscription.
- **Kỳ vọng:** hiện đúng gói, chu kỳ thanh toán, ngày bắt đầu/kết thúc, trạng thái
  (trialing/active/expired).

### 2. Nâng cấp/đổi gói cho tenant
- Đổi gói tenant sang 1 gói khác (VD: `trial` → `pro`).
- **Kỳ vọng:** cập nhật thành công, `tenants.current_subscription_id` trỏ đúng bản ghi mới, giới
  hạn (plan_limits) áp dụng ngay theo gói mới.

### 3. Đổi chu kỳ thanh toán
- Đổi billing cycle (monthly ↔ yearly nếu có).
- **Kỳ vọng:** lưu đúng, ngày hết hạn tính lại theo chu kỳ mới.

### 4. Hết hạn tự động (cron job)
- Kiểm tra `SubscriptionExpirationJob` có đang chạy đúng lịch không (không cần đợi thật, chỉ cần
  xác nhận nó tồn tại và log chạy):
  ```bash
  docker logs fams-api --tail 200 | grep -i "SubscriptionExpirationJob"
  ```
- **Kỳ vọng:** thấy log job chạy định kỳ (không lỗi). Nếu muốn test thật hành vi hết hạn: chỉnh
  tay `current_period_end` của 1 subscription test về quá khứ, đợi job chạy lần kế tiếp (hoặc kích
  hoạt qua endpoint admin nếu có), xác nhận tenant chuyển sang `suspended`.

### 5. Kiểm tra audit log
- Sau case 2, gọi `GET /audit-logs?tenantId=<id>&action=UPDATE&entityType=TenantSubscription` (hoặc
  action tương ứng thực tế trả về).
- **Kỳ vọng:** có bản ghi diff đúng (gói cũ → gói mới).

---

## Ghi chú
Case 4 (hết hạn tự động) là case tốn thời gian nhất — có thể làm sau cùng hoặc bỏ qua nếu chỉ cần
xác nhận job tồn tại và không lỗi qua log, không nhất thiết phải kích hoạt thật.
