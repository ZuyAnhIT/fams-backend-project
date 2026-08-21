# FAMS Use Case Diagrams (as-is)

**Phạm vi:** toàn bộ nền tảng FAMS  
**Ký pháp:** UML Use Case, PlantUML  
**Căn cứ:** controller, RBAC/Flyway, tài liệu API và feature map trong repository; rà soát 2026-08-17

## Cấu trúc hai mức

Mô tả đầy đủ từng kết nối để dựng lại thủ công: [`use-case-connections.md`](use-case-connections.md).

### Mức 1 — System Overview

Tổng quan được tách theo hai phía để không đặt actor cấp nền tảng và actor của công ty sử dụng trên cùng một sơ đồ:

- [`01a-platform-overview.puml`](01a-platform-overview.puml): Platform Admin, Platform Staff và các năng lực vận hành toàn nền tảng.
- [`01b-company-overview.puml`](01b-company-overview.puml): Tenant Admin/Owner, HR Manager, Site Supervisor, Employee và các năng lực trong phạm vi tenant/site.

`Người dùng đã xác thực` xuất hiện trong cả hai sơ đồ chỉ với vai trò actor tổng quát cho quan hệ kế thừa. Tài khoản/xác thực và thông báo là năng lực dùng chung; phần chi tiết vẫn được giữ nguyên tại `02-auth-account.puml` và `09-notification.puml`.

### Mức 2 — Module-level

| # | Phân hệ | File |
|---|---|---|
| 1 | Tài khoản và xác thực | [`02-auth-account.puml`](02-auth-account.puml) |
| 2A | Platform, subscription, RBAC và audit phía nền tảng | [`03a-platform-governance.puml`](03a-platform-governance.puml) |
| 2B | Tenant, RBAC và audit phía công ty | [`03b-company-governance.puml`](03b-company-governance.puml) |
| 3 | Tổ chức, nhân sự, nơi làm và lịch làm | [`04-workforce-organization.puml`](04-workforce-organization.puml) |
| 4 | Face ID và liveness | [`05-face-id.puml`](05-face-id.puml) |
| 5 | Check-in/out và bảng công | [`06-checkin-attendance.puml`](06-checkin-attendance.puml) |
| 6 | Random check và vi phạm | [`07-random-check-violation.puml`](07-random-check-violation.puml) |
| 7A | Tra cứu dữ liệu phía nền tảng | [`08a-platform-analytics.puml`](08a-platform-analytics.puml) |
| 7B | Dashboard, báo cáo, tìm kiếm và bộ lọc phía công ty | [`08b-company-analytics-reporting.puml`](08b-company-analytics-reporting.puml) |
| 8 | Thông báo | [`09-notification.puml`](09-notification.puml) |

Ở Mức 2, chỉ Governance (`03a/03b`) và Analytics (`08a/08b`) cần tách vì bản gốc có actor của cả hai phía. Các sơ đồ `04`–`07` vốn chỉ thuộc phía công ty. Sơ đồ `02` và `09` được giữ nguyên vì mô tả năng lực dùng chung của `Người dùng` / `Người dùng đã xác thực` trên toàn hệ thống.

## Quy ước actor

- **Người dùng:** actor khái quát cho hành vi công khai như đăng ký/đăng nhập.
- **Người dùng đã xác thực:** actor khái quát cho chức năng dùng chung sau đăng nhập. Sáu vai trò RBAC kế thừa actor này nhưng **không kế thừa lẫn nhau**; actor này có thể được lặp lại ở hai sơ đồ tổng quan để thể hiện quan hệ kế thừa.
- **Platform Admin / Platform Staff:** vai trò cấp nền tảng. Platform Staff chỉ thực hiện use case mà permission được cấp cho phép.
- **Tenant Admin / HR Manager / Site Supervisor / Employee:** vai trò cấp tenant; dữ liệu luôn bị giới hạn bởi tenant đang active. Site Supervisor còn bị giới hạn theo site được gán.
- **Google Identity, Firebase, Email, Object Storage:** supporting actors vì nằm ngoài ranh giới FAMS.

## Nguyên tắc mô hình hóa

1. Actor là vai trò/hệ thống bên ngoài, không phải màn hình, database, Redis, AI service hay background job nội bộ.
2. Tên use case dùng **động từ + đối tượng/mục tiêu**, không dùng tên controller/endpoint.
3. `<<include>>` biểu diễn bước bắt buộc, được tái sử dụng trong use case gốc.
4. `<<extend>>` biểu diễn hành vi tùy chọn hoặc chỉ xảy ra khi đủ điều kiện; mũi tên đi từ use case mở rộng tới use case cơ sở.
5. Actor generalization chỉ dùng cho hành vi dùng chung; không ngụ ý Platform Admin là Tenant Admin hay HR Manager.
6. Các sơ đồ mô tả năng lực backend hiện có. Việc Web/Mobile có hay chưa có màn hình không làm thay đổi một use case backend đã tồn tại.

## Những nội dung không nên đưa vào Use Case Diagram

- PostgreSQL, Redis, Java API và Python AI: chi tiết Container/Component Diagram.
- JWT, HTTP, queue, DTO và endpoint: chi tiết thiết kế/sequence.
- Tự tính phút công, kiểm tra tenant scope, masking và audit interceptor: hành vi nội bộ; chỉ ghi `include` nếu cần làm rõ quy tắc nghiệp vụ.
- Mỗi CRUD endpoint thành một oval riêng: chỉ tách khi actor, mục tiêu hoặc vòng đời nghiệp vụ thực sự khác nhau.

## Render

Mỗi file là một sơ đồ độc lập:

```bash
plantuml docs/architecture/use-cases/*.puml
```

PlantUML sẽ sinh PNG cùng thư mục; có thể dùng `-tsvg` để sinh SVG.
