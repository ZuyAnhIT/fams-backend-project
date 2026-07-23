# 04. Quy trình phát triển một tính năng trong FAMS Backend

> Repository chưa thể hiện một quy trình quản lý nhánh/PR bắt buộc. Phần dưới là quy trình kỹ thuật đề xuất dựa trên kiến trúc và công cụ thực tế của dự án, nhằm tránh tiếp tục tạo tính năng đúng từng endpoint nhưng sai khi nối toàn hệ thống.

## 1. Nguyên tắc đầu tiên: nghiệp vụ trước, code sau

Trước khi tạo entity/DTO/controller, phải có một “feature contract” ngắn trả lời được:

- Actor nào thực hiện: employee, supervisor, HR, tenant admin, platform staff/admin?
- Actor đang hoạt động trong tenant nào? Có trường hợp multi-tenant không?
- Trigger là API, cron job, callback hay event từ feature khác?
- Input bắt buộc, input tùy chọn, giới hạn và format?
- Trạng thái ban đầu, các trạng thái hợp lệ và transition?
- Dữ liệu nguồn nào là source of truth?
- Timezone nào được dùng: UTC, timezone site hay timezone người dùng?
- Quy tắc ownership và permission?
- Nếu bước phụ thất bại thì request chính rollback, retry hay vẫn thành công?
- Kết quả đồng bộ hay eventual/asynchronous?
- Error code ổn định nào frontend/mobile sẽ xử lý?
- Tính năng ảnh hưởng module, báo cáo, dashboard, notification, audit, quota nào?

Nếu chưa trả lời được các câu trên, implementation sẽ chỉ che giấu sự mơ hồ nghiệp vụ.

## 2. Giai đoạn A — Khảo sát và đặc tả

### Bước 1: Viết behavior matrix

Ví dụ cho một hành động “duyệt check-in”:

| Actor | Trạng thái hiện tại | Input | Kết quả | HTTP/error | Side effect |
|---|---|---|---|---|---|
| Employee | `pending_review` của chính mình | explanation | Lưu giải trình | 200 | HR nhìn thấy detail mới |
| HR có quyền | `pending_review` | `valid` + reason | Duyệt | 200 | recompute attendance, audit |
| HR có quyền | `rejected` | `rejected` | Không đổi | 409/400 theo contract | không có |
| User tenant khác | bất kỳ | id hợp lệ | Chặn | 404 hoặc 403 theo chính sách chống lộ | không có |

Matrix phải bao gồm happy path, quyền, tenant, duplicate/idempotency, trạng thái sai, dữ liệu hết hạn và dependency lỗi.

### Bước 2: Vẽ impact map

```text
Feature mới
├── Database/migration
├── Entity/repository/specification
├── DTO/API/Swagger/error contract
├── Permission/RBAC seed/cache invalidation
├── Cross-module service
├── Job/queue/callback nếu có
├── Notification/audit
├── Dashboard/report/search
├── Seed/demo data
└── Unit + integration + manual/provider test
```

Tìm code liên quan bằng:

```bash
rg "<business term>|<table_name>|<permission>" api-server/src/main tests docs
rg "@RequestMapping|@PostMapping|@PatchMapping" api-server/src/main/java/com/fams/modules
rg "@Table\(name = \"<table>\"" api-server/src/main/java
```

### Bước 3: Xác định invariant và source of truth

Ví dụ:

- “Một assignment chỉ có một open check-in” là invariant; hiện service kiểm tra trước khi insert. Nên xác định có cần unique partial index để chống race condition hay không.
- `AttendanceSummary` là dữ liệu tổng hợp; `CheckinRecord` là dữ liệu nguồn. Khi lệch, recompute từ check-in, không sửa cả hai tùy tiện.
- `configSnapshot` của scheduled check là rule tại thời điểm sinh; config hiện tại không được dùng để giải thích check lịch sử.

## 3. Giai đoạn B — Thiết kế kỹ thuật

### Bước 4: Chọn module sở hữu tính năng

- Mở rộng module hiện có nếu feature cùng aggregate/business capability.
- Chỉ tạo module mới khi có vocabulary, dữ liệu và lifecycle riêng.
- Module đọc/tổng hợp như dashboard/report không nên trở thành owner của dữ liệu nguồn.
- Shared chỉ chứa hạ tầng thực sự dùng chung; không chuyển service nghiệp vụ vào shared để “tiện import”.

### Bước 5: Thiết kế dữ liệu và state machine

Viết trước:

```text
pending --submit--> approved
pending --reject--> rejected
approved --cancel--> cancelled
```

Với từng transition, ghi actor, guard, timestamp, audit và khả năng chạy lại. Tránh dùng string status mới ở nhiều file mà không có danh sách thống nhất hoặc DB constraint.

Thiết kế bảng cần:

- UUID primary key.
- `tenant_id` cho dữ liệu tenant-scoped.
- Foreign key và index theo query thực tế.
- Unique constraint/idempotency key cho invariant quan trọng.
- `created_at`, `updated_at`; `deleted_at` nếu dùng soft delete.
- `timestamptz` cho thời điểm, `date` cho ngày nghiệp vụ đã xác định timezone.
- Không lưu secret/token raw nếu có thể hash/mã hóa.

### Bước 6: Thiết kế API contract

Xác định trước code:

```text
METHOD /api/v1/tenants/{tenantId}/resources/{id}/action
Auth: Bearer JWT
Permission: resources:action
Request DTO: ...
Success: status + Response DTO
Errors: validation / not found / forbidden / conflict / business code
Idempotency: none | key | natural unique rule
```

Không trả entity trực tiếp. Không dùng message text làm contract cho frontend; dùng `errorCode` ổn định.

### Bước 7: Quyết định transaction và consistency

Phân loại từng side effect:

| Loại | Ví dụ | Cách xử lý |
|---|---|---|
| Cùng invariant, phải cùng thành công | tạo Employee + role bắt buộc | Cùng transaction |
| Có thể tính lại | AttendanceSummary từ check-in | Recompute idempotent + catch-up job |
| Chậm/bên ngoài | FCM, email, AI | Async; cần retry/timeout/idempotency |
| Audit bắt buộc | Thao tác nhạy cảm | Xác định rõ audit fail có rollback nghiệp vụ không |

Nếu bắt exception của một method `@Transactional` khác, phải hiểu propagation mặc định là `REQUIRED`; exception có thể đã đánh dấu transaction rollback-only. Muốn best-effort thật sự, cân nhắc event sau commit hoặc `REQUIRES_NEW` có chủ đích và test failure path.

## 4. Giai đoạn C — Thứ tự triển khai trong repository

### Bước 8: Flyway migration trước

Tạo version kế tiếp trong:

```text
api-server/src/main/resources/db/migration/V<next>__<meaningful_name>.sql
```

Hiện version cuối là V64; thay đổi tiếp theo phải kiểm tra lại repository trước khi chọn số, không mặc định mãi là V65.

Quy tắc:

- Không sửa migration đã chạy ở môi trường chia sẻ; thêm migration mới.
- Migration phải chạy được trên dữ liệu đang có, không chỉ DB trắng.
- Với `NOT NULL`, backfill dữ liệu cũ trước rồi mới siết constraint.
- Tạo index cho `tenant_id` + cột filter/sort thường dùng.
- Nếu Python AI đọc/ghi bảng đó, cập nhật và kiểm thử cả AI service.
- Chỉ đặt file ở `database/migrations` là chưa đủ; Flyway của app không đọc thư mục đó.

### Bước 9: Entity/model

Tạo hoặc sửa `modules/<feature>/entity`:

- Ánh xạ đúng tên bảng/cột/mutable/nullability.
- Đồng bộ kiểu Java với PostgreSQL.
- `@PrePersist/@PreUpdate` chỉ cho default/timestamp đơn giản.
- Không nhét authorization hoặc workflow phức tạp vào callback entity.
- Với scalar FK UUID, service phải kiểm tra resource liên quan thuộc cùng tenant.

### Bước 10: Repository và specification

- Dùng derived query cho query rõ và ngắn.
- Dùng JPQL cho join/aggregation có kiểm soát.
- Dùng native query khi cần PostGIS/DB-specific.
- List/filter động dùng `JpaSpecificationExecutor` + specification.
- Method tenant-scoped nên có `tenantId` và `DeletedAtIsNull` trong tên/query.
- Tránh N+1: batch-load tên/reference như `AttendanceSummaryService` đang làm.
- Không để controller tự ghép nhiều repository cho nghiệp vụ mới; đặt orchestration ở service.

### Bước 11: Request/response DTO

Trong `dto/request`:

- Dùng Bean Validation cho rule hình thức: required, size, min/max, email, pattern.
- Rule cần DB/current state đặt ở service.
- Chỉ đưa field client có quyền điều khiển.

Trong `dto/response`:

- Không lộ password hash, embedding, token hash, internal note hoặc soft-delete metadata nếu không cần.
- Giữ tên/kiểu tương thích với client.
- Thêm Swagger `@Schema` cho field khó hiểu/status enum.

### Bước 12: Service implementation

Mặc định theo convention hiện tại:

```java
@Service
public class FeatureService {
    // constructor injection

    @Transactional
    public FeatureResponse execute(...) { ... }
}
```

Thứ tự logic nên ổn định:

1. Resolve caller/tenant/ownership.
2. Load resource tenant-scoped.
3. Kiểm tra permission bổ sung và current state.
4. Kiểm tra business invariant.
5. Thực hiện thay đổi domain.
6. Save/flush khi cần bắt DB constraint tại đúng chỗ.
7. Phát event/job/notification/audit theo consistency đã chọn.
8. Map sang response DTO.

Dùng `@Transactional(readOnly = true)` cho read path. Tránh `catch (Exception)` rộng rồi tiếp tục mà không biết dữ liệu nào đã commit.

Chỉ tạo `FeatureService` interface + `FeatureServiceImpl` khi có lý do thiết kế rõ; dự án hiện không có convention `ImplService` cho mọi module.

### Bước 13: Controller

Controller cần:

- Base route theo `/api/v1/tenants/{tenantId}/...` với tenant-scoped feature.
- `@Valid` cho body; validate query pagination/range.
- `@AuthenticationPrincipal FamsUserDetails`.
- `@PreAuthorize` theo permission đã seed.
- HTTP status đúng: 201 create, 202 accepted async, 200 read/update, 204 nếu contract chọn no body.
- `ApiResponse`/`PageResponse` nhất quán.
- OpenAPI operation, response status và security đúng hành vi thật.

Controller không nên gọi repository trực tiếp. Code hiện tại có vài nơi làm vậy; tính năng mới không nên mở rộng pattern này.

### Bước 14: Error handling

- Tái dùng exception hiện có nếu semantics đúng.
- Rule nghiệp vụ cần status/code riêng dùng `BusinessException` hoặc exception chuyên biệt + handler.
- Thêm handler trong `GlobalExceptionHandler` nếu cần.
- Viết `errorCode` ổn định và `userMessage` tiếng Việt rõ ràng.
- Không trả stack trace, SQL error hoặc secret cho client.

### Bước 15: Permission và cache

Nếu thêm permission:

1. Thêm migration seed permission/role mapping, không chỉ sửa file seed demo.
2. Dùng cùng chuỗi trong `@PreAuthorize` và kiểm tra service.
3. Xác định permission tenant-scoped hay platform-scoped.
4. Xử lý cache `auth:perms:*`; role service hiện có logic invalidation, phải tái dùng.
5. Test user có quyền, thiếu quyền, platform admin/staff và user ở tenant khác.

### Bước 16: Side effect liên module

Rà soát tối thiểu:

- Có cần audit không?
- Có notification/email/FCM không?
- Có thay dashboard/report/search không?
- Có ảnh hưởng plan limit/quota không?
- Có job cleanup/expiry không?
- Có cần seed demo/history để frontend nhìn thấy không?
- Có AI callback hoặc Redis payload phải version không?

## 5. Giai đoạn D — Kiểm thử

### Bước 17: Unit test logic thuần

Hiện repository còn thiếu unit test. Tính năng mới nên thêm unit test Java cho:

- State transition.
- Timezone/ca qua đêm/DST nếu có.
- Boundary min/max/quota.
- Duplicate/idempotency/race-sensitive decision.
- Permission/ownership helper.
- Mapper che field nhạy cảm.

Ưu tiên tách thuật toán thuần khỏi service lớn để test không cần Spring context.

### Bước 18: Repository/integration test

Query JPQL/native/PostGIS nên test với PostgreSQL/PostGIS thật hoặc Testcontainers; H2 không phản ánh PostGIS và nhiều khác biệt PostgreSQL.

Test:

- Tenant filter.
- Soft delete.
- Pagination/sort.
- Unique/FK constraint.
- Native geospatial query.
- Migration từ state có dữ liệu cũ.

### Bước 19: API shell test theo convention dự án

Thêm file `tests/<feature>/test_<behavior>.sh`, dùng `tests/lib/test_helpers.sh`. Bao phủ:

- Happy path.
- Validation.
- Không auth.
- Có auth nhưng thiếu permission.
- Sai tenant/ownership.
- Resource không tồn tại/đã xóa.
- Duplicate/idempotency.
- Trạng thái trước/sau và row DB nếu cần.
- Side effect downstream.

Chạy một suite:

```bash
BASE_URL=http://localhost:8080 bash tests/<feature>/test_<behavior>.sh
```

Chạy toàn bộ:

```bash
BASE_URL=http://localhost:8080 bash tests/run_all.sh
```

Các file có `manual` trong tên bị loại khỏi `run_all.sh`; luồng Firebase/Google/provider thật cần chạy riêng với credential test.

### Bước 20: Failure-path và concurrency test

Đặc biệt cho tính năng nối nhiều module:

- Redis/AI/FCM/SMTP down thì request trả gì?
- Callback gửi hai lần có idempotent không?
- Hai request đồng thời có vượt quota/tạo duplicate không?
- Job chạy lại cùng ngày có tạo lại dữ liệu không?
- Transaction rollback giữa chừng để lại row/queue nào?
- Permission cache vừa đổi role có stale tối đa bao lâu?

## 6. Giai đoạn E — Chạy và xác minh local

### Java-only feature

```bash
cp .env.example .env       # chỉ lần đầu; điền secret bắt buộc
make dev-d
make logs-api
make ps
```

### Feature Face ID/liveness

```bash
make full-dev-d
make logs-ai
make logs-api
```

### Build tĩnh

```bash
cd api-server
bash mvnw test
bash mvnw package
```

`mvnw` đang được Git lưu mode `100644` (không có executable bit), vì vậy dùng `bash mvnw ...` trên checkout hiện tại; hoặc sửa executable bit trong một thay đổi riêng nếu đội dự án muốn dùng `./mvnw`.

Kiểm tra thêm:

- `GET /api/v1/auth/health`: app up.
- `GET /actuator/health`: dependency health; có thể DOWN nếu FCM credential dev không hợp lệ.
- Swagger: `http://localhost:8080/swagger-ui.html`.
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`.

Không dùng `make stop-v`/`make clean` nếu chưa muốn xóa volume DB/Redis/MinIO.

## 7. Giai đoạn F — Review và Definition of Done

### Checklist review code

- [ ] Rule đã có behavior matrix và được PO/BA xác nhận.
- [ ] Mọi query tenant data đều scope `tenantId`.
- [ ] Path tenant và active tenant/role được kiểm tra phù hợp.
- [ ] Ownership và platform exception rõ ràng.
- [ ] Không trả entity/secret/PII ngoài ý muốn.
- [ ] Migration append-only, tương thích dữ liệu cũ, có index/FK/constraint.
- [ ] State transition từ trạng thái sai bị chặn.
- [ ] Duplicate/concurrent request có DB guard hoặc idempotency.
- [ ] Transaction và side effect failure behavior đã định nghĩa.
- [ ] Permission seed, annotation, service check và cache invalidation khớp nhau.
- [ ] Timezone dùng rõ ràng; không vô tình dùng timezone máy chủ.
- [ ] Swagger và error code khớp code thật.
- [ ] Unit/integration/API test bao phủ cả failure path.
- [ ] Dashboard/report/notification/audit/seed được cập nhật nếu bị ảnh hưởng.
- [ ] Java-only/full stack được chọn đúng theo dependency.

### Definition of Done đề xuất

Một feature chỉ “done” khi:

1. Nghiệp vụ và acceptance criteria được xác nhận.
2. Migration chạy được trên DB hiện có và DB mới.
3. API contract được tài liệu hóa.
4. Auth/tenant/permission/ownership đúng.
5. Unit test logic quan trọng pass.
6. Feature shell test pass.
7. Các test module liên quan pass, không chỉ test file mới.
8. Failure của dependency ngoài đã được kiểm chứng.
9. Seed/demo/manual setup được cập nhật nếu cần.
10. Không còn TODO mơ hồ về dữ liệu/rollback/state.

## 8. Ví dụ cấu trúc file cho một feature mới trong module hiện có

Giả sử thêm “đơn xin điều chỉnh công” vào `attendance`:

```text
api-server/src/main/resources/db/migration/
└── V<next>__create_attendance_adjustment_requests.sql

api-server/src/main/java/com/fams/modules/attendance/
├── controller/AttendanceAdjustmentController.java
├── dto/request/CreateAttendanceAdjustmentRequest.java
├── dto/request/ReviewAttendanceAdjustmentRequest.java
├── dto/response/AttendanceAdjustmentResponse.java
├── entity/AttendanceAdjustment.java
├── repository/AttendanceAdjustmentRepository.java
├── service/AttendanceAdjustmentService.java
└── specification/AttendanceAdjustmentSpecification.java

tests/attendance/
└── test_attendance_adjustment_workflow.sh
```

Luồng nên có state machine `pending → approved/rejected`, employee ownership, HR permission, tenant scoping, audit, notification và quy tắc approved có cập nhật `AttendanceSummary` theo cùng transaction hay event đã được quyết định trước khi viết service.

## 9. Anti-pattern cần tránh trong dự án này

- Viết controller mới gọi thẳng nhiều repository.
- Chỉ thêm field entity mà quên migration, DTO, response, seed và client contract.
- Dùng `findById` với entity tenant-scoped rồi tin vào permission generic.
- Copy logic PostGIS/timezone/quota sang service mới thay vì tái dùng boundary phù hợp.
- Sửa migration cũ để “chạy được máy mình”.
- Catch mọi exception và trả success dù side effect là bắt buộc.
- Tạo queue job nhưng không định nghĩa duplicate, timeout, retry và callback late.
- Chỉ test platform admin; bỏ qua user thường và cross-tenant.
- Chỉ assert HTTP status mà không kiểm tra DB/state/side effect.
- Thêm mọi thứ vào `shared` hoặc tạo interface/impl cho mọi CRUD mà không có mục đích.
