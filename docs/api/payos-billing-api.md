# PayOS billing MVP

Phạm vi này hỗ trợ thanh toán một lần cho gói tháng/năm. Đây không phải cơ chế tự động
trừ tiền định kỳ và chưa bao gồm hoàn tiền, prorate, coupon hoặc phát hành hóa đơn điện tử
qua nhà cung cấp được cấp phép. Hệ thống đã quản lý trạng thái nghĩa vụ phát hành hóa đơn
và phiếu xác nhận thanh toán nội bộ để phục vụ đối soát.

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

- `GET /api/v1/billing-orders?search=&tenantId=&status=&billingCycle=&sortBy=&sortDir=&page=0&size=20`
- `GET /api/v1/billing-orders/{orderId}`
- `POST /api/v1/billing-orders/{orderId}/refresh`
- `POST /api/v1/billing-orders/{orderId}/cancel`

Quyền tương ứng là `billing:list`, `billing:read`, `billing:update`; migration gán cho
`PLATFORM_ADMIN` và `PLATFORM_BILLING_OPS` nếu role đã tồn tại.

`search` tìm theo tên công ty, tên gói, mã đơn (`100012` hoặc `#100012`) và mã giao dịch
PayOS. `sortBy` chỉ nhận tập an toàn `createdAt`, `amount`, `paidAt`, `company`, `status`,
`orderCode`; `sortDir` là `asc` hoặc `desc`. Giá trị sắp xếp không hợp lệ tự quay về
`createdAt desc`.

## Quy tắc nghiệp vụ

- Chỉ bán plan đang active và có giá VND nguyên dương.
- Nội dung chuyển khoản dùng mã đơn dạng base-36 có tiền tố `F`, chỉ gồm ASCII và tối đa
  9 ký tự theo giới hạn nghiêm ngặt của PayOS cho tài khoản ngân hàng không liên kết trực tiếp.
- Mỗi tenant chỉ có một đơn đang mở để tránh thanh toán trùng.
- Giá/tên gói và tên công ty được snapshot vào đơn, không thay đổi khi admin sửa dữ liệu sau đó.
- Return URL chỉ hiển thị kết quả; chỉ webhook đã xác minh hoặc kết quả query trực tiếp PayOS
  mới có thể kích hoạt subscription.
- Cùng gói và còn hạn: kỳ mua thêm nối tiếp ngày hết hạn hiện tại.
- Trial, hết hạn hoặc đổi gói: kỳ mới bắt đầu tại thời điểm xác nhận thanh toán.
- Thanh toán thiếu được giữ ở `UNDERPAID`, chưa kích hoạt gói.
- Webhook lặp lại là idempotent; `subscriptionAppliedAt` bảo đảm chỉ cộng kỳ hạn một lần.
- Job `BillingReconciliationJob` đối soát lại đơn mở để tự phục hồi khi webhook bị trễ/mất.
- Thanh toán hợp lệ mở lại tenant bị suspend; tenant đã cancelled không tự mở lại.

## Chứng từ thanh toán và hóa đơn

- Mọi trạng thái đều có **chi tiết giao dịch** để người dùng và Billing Ops xem/đối soát.
- `PENDING`, `PROCESSING`, `CANCELLED`, `EXPIRED`, `FAILED` chưa nhận tiền thì không sinh
  phiếu xác nhận thanh toán và có `invoiceStatus=NOT_ELIGIBLE`.
- `UNDERPAID` đã nhận một phần tiền nhưng chưa kích hoạt gói và được gắn
  `invoiceStatus=PAYMENT_REVIEW`; Billing Ops phải đối soát để thu nốt hoặc hoàn tiền, sau đó
  xử lý chứng từ/hóa đơn theo kết quả thực tế. Trường hợp này không được im lặng coi là pending.
- Khi PayOS xác nhận nhận đủ tiền, đơn chuyển `PAID`, sinh số phiếu nội bộ ổn định dạng
  `PT-yyyyMM-orderCode` và chuyển `invoiceStatus=PENDING_ISSUANCE`.
- Phiếu nội bộ chỉ là bằng chứng đối soát trong FAMS, **không thay thế hóa đơn điện tử/VAT**.
- Chỉ khi một nhà cung cấp hóa đơn hợp pháp phát hành thành công mới được ghi
  `invoiceStatus=ISSUED`, `invoiceNumber`, `invoiceIssuedAt`, `invoiceLookupUrl` và hiển thị
  đường dẫn tra cứu. Phiên bản hiện tại chưa tự đặt trạng thái `ISSUED`.

Theo quy định hóa đơn dịch vụ hiện hành, thời điểm lập hóa đơn không đơn giản luôn là lúc
hoàn tất toàn bộ kỳ dịch vụ: nếu thu tiền trước/trong khi cung cấp dịch vụ thì nhìn chung thời
điểm thu tiền là thời điểm lập hóa đơn (có các ngoại lệ theo loại tiền đặt cọc/dịch vụ). Vì gói
SaaS này thu trước, thanh toán `PAID` được dùng làm thời điểm phát sinh nghĩa vụ phát hành;
không có khoản tiền nhận ở đơn hủy/chờ thì chưa phát sinh. Tham chiếu Nghị định 70/2025/NĐ-CP:
https://vanban.chinhphu.vn/?docid=213179&lang=vi&pageid=27160

## Trước khi thử giao dịch

1. Sửa `priceMonthly`/`priceYearly` của gói thành số VND nguyên thực tế. Dữ liệu seed cũ
   `9.99/29.99/...` không hợp lệ với PayOS VND và backend chủ động từ chối để tránh thu sai tiền.
2. Cấu hình ba khóa PayOS và restart API.
3. Public API qua HTTPS rồi đăng ký webhook URL trong kênh thanh toán PayOS.
4. Tạo một gói/giá test nhỏ, thanh toán thật và kiểm tra cả lịch sử công ty, Billing Ops,
   subscription lẫn audit log.
