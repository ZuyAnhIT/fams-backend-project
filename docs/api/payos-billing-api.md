# PayOS billing MVP

Phạm vi này hỗ trợ thanh toán một lần cho gói tháng/năm. Đây không phải cơ chế tự động
trừ tiền định kỳ và chưa bao gồm hoàn tiền, prorate, coupon hay hóa đơn điện tử.

## Cấu hình

Thiết lập tại backend (không đưa ba khóa này sang frontend):

```dotenv
PAYOS_CLIENT_ID=
PAYOS_API_KEY=
PAYOS_CHECKSUM_KEY=
PAYOS_PAYMENT_LINK_EXPIRY_MINUTES=30
PAYOS_RECONCILIATION_DELAY_MS=300000
```

Webhook đăng ký trên PayOS:

```text
POST https://<api-domain>/api/v1/payments/payos/webhook
```

Webhook không dùng JWT vì do PayOS gọi, nhưng mọi payload đều được SDK PayOS xác minh chữ
ký. Payload có chữ ký hợp lệ nhưng không khớp đơn nội bộ được ACK để hỗ trợ payload mẫu khi
PayOS xác nhận URL; nó không làm thay đổi subscription.

## API công ty (Owner)

- `POST /api/v1/tenants/{tenantId}/billing-orders`: tạo checkout PayOS.
- `GET /api/v1/tenants/{tenantId}/billing-orders`: lịch sử thanh toán.
- `GET /api/v1/tenants/{tenantId}/billing-orders/{orderId}`: trạng thái một đơn.
- `POST /api/v1/tenants/{tenantId}/billing-orders/{orderId}/refresh`: đối soát ngay với PayOS.
- `POST /api/v1/tenants/{tenantId}/billing-orders/{orderId}/cancel`: hủy đơn chưa hoàn tất.

Request tạo đơn:

```json
{
  "planId": "00000000-0000-0000-0000-000000000000",
  "billingCycle": "MONTHLY"
}
```

Chỉ Owner của tenant được thao tác. Platform Admin có thể truy cập để hỗ trợ. Tenant bị
suspend do hết hạn vẫn được phép gọi các endpoint billing để có thể thanh toán và tự mở lại.

## API Billing Ops

- `GET /api/v1/billing-orders?tenantId=&status=&page=0&size=20`
- `GET /api/v1/billing-orders/{orderId}`
- `POST /api/v1/billing-orders/{orderId}/refresh`
- `POST /api/v1/billing-orders/{orderId}/cancel`

Quyền tương ứng là `billing:list`, `billing:read`, `billing:update`; migration gán cho
`PLATFORM_ADMIN` và `PLATFORM_BILLING_OPS` nếu role đã tồn tại.

## Quy tắc nghiệp vụ

- Chỉ bán plan đang active và có giá VND nguyên dương.
- Nội dung chuyển khoản dùng mã đơn dạng base-36 có tiền tố `F`, chỉ gồm ASCII và tối đa
  9 ký tự theo giới hạn nghiêm ngặt của PayOS cho tài khoản ngân hàng không liên kết trực tiếp.
- Mỗi tenant chỉ có một đơn đang mở để tránh thanh toán trùng.
- Giá/tên gói được snapshot vào đơn, không thay đổi khi admin sửa plan sau đó.
- Return URL chỉ hiển thị kết quả; chỉ webhook đã xác minh hoặc kết quả query trực tiếp PayOS
  mới có thể kích hoạt subscription.
- Cùng gói và còn hạn: kỳ mua thêm nối tiếp ngày hết hạn hiện tại.
- Trial, hết hạn hoặc đổi gói: kỳ mới bắt đầu tại thời điểm xác nhận thanh toán.
- Thanh toán thiếu được giữ ở `UNDERPAID`, chưa kích hoạt gói.
- Webhook lặp lại là idempotent; `subscriptionAppliedAt` bảo đảm chỉ cộng kỳ hạn một lần.
- Job `BillingReconciliationJob` đối soát lại đơn mở để tự phục hồi khi webhook bị trễ/mất.
- Thanh toán hợp lệ mở lại tenant bị suspend; tenant đã cancelled không tự mở lại.

## Trước khi thử giao dịch

1. Sửa `priceMonthly`/`priceYearly` của gói thành số VND nguyên thực tế. Dữ liệu seed cũ
   `9.99/29.99/...` không hợp lệ với PayOS VND và backend chủ động từ chối để tránh thu sai tiền.
2. Cấu hình ba khóa PayOS và restart API.
3. Public API qua HTTPS rồi đăng ký webhook URL trong kênh thanh toán PayOS.
4. Tạo một gói/giá test nhỏ, thanh toán thật và kiểm tra cả lịch sử công ty, Billing Ops,
   subscription lẫn audit log.
