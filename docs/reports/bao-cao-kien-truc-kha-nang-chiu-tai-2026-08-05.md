# Báo cáo: Kiến trúc tổng thể và khả năng chịu tải hệ thống FAMS

> Ngày lập: 2026-08-05. Báo cáo này mô tả **hiện trạng thật trong code và cấu hình đang chạy** (`docker-compose*.yml`, `application.yml`, mã nguồn Java/Python), không phải mục tiêu thiết kế mong muốn. Ở những điểm hệ thống **chưa có** cơ chế/số liệu, báo cáo nêu rõ "chưa có" thay vì suy đoán, kèm khuyến nghị.

## Tóm tắt nhanh cho người đọc bận

| Câu hỏi | Trả lời ngắn |
|---|---|
| Kiến trúc là gì? | Modular monolith Java/Spring Boot + AI microservice Python/FastAPI, 1 instance mỗi loại, chạy trên Docker Compose |
| Chịu được bao nhiêu người dùng đồng thời? | **Không có con số thiết kế/cam kết chính thức.** Cấu hình hiện tại (Hikari pool = 10 kết nối DB, 1 instance API) giới hạn thực tế ở mức vài chục request DB-bound đồng thời trước khi bắt đầu xếp hàng/timeout |
| Có load balancer không? | **Chưa** — có file `docker/nginx/nginx.conf` nhưng đang **rỗng**, không được wire vào bất kỳ `docker-compose*.yml` nào |
| Có làm load test / stress test chưa? | **Chưa** ở mức HTTP/throughput. Đã có 1 script sinh **dữ liệu khối lượng lớn** (1,1 triệu dòng checkin) để test hiệu năng truy vấn/phân trang, nhưng không đo request/giây hay độ trễ |
| Có auto-scale không? | **Chưa** — không có k8s, không có Docker Swarm, không có cấu hình scale nhiều instance |
| Có backup tự động không? | **Chưa tìm thấy script/job backup nào** trong repo — dữ liệu chỉ nằm trong Docker named volume |
| Có giám sát/cảnh báo không? | Chỉ có Spring Boot Actuator (`/actuator/health`, `/actuator/info`) — chưa có Prometheus/Grafana/APM/cảnh báo tự động |

---

## 1. Kiến trúc tổng thể hệ thống

### 1.1 Các thành phần chính

```
┌──────────────┐        ┌──────────────────────────┐        ┌────────────────────┐
│  Client       │ HTTPS  │   Java API (fams-api)     │  SQL   │  PostgreSQL 16 +    │
│  - Web (Next) │───────▶│   Spring Boot :8080        │───────▶│  PostGIS 3.4        │
│  - Mobile App │  REST  │   (modular monolith,       │        │  (1 instance)       │
│  - Admin      │  +JWT  │    20 module nghiệp vụ)    │        └────────────────────┘
└──────────────┘        │                            │
                         │  ──cache/queue/blacklist──▶│──────▶ Redis 7 (1 instance,
                         │                            │        maxmemory 256MB, allkeys-lru)
                         │  ──avatar────────────────▶ │──────▶ S3-compatible object storage
                         │                            │        (MinIO ở dev, AWS S3 ở prod)
                         │  ──email────────────────▶  │──────▶ Gmail SMTP
                         │  ──push/OTP──────────────▶ │──────▶ Firebase Auth / FCM
                         │  ──enroll/status/revoke──▶ │──HTTP─▶ AI service (fams-ai)
                         │  ──LPUSH face verify job──▶│──────▶ Redis list
                         └──────────────────────────┘
                                                                       │ BRPOP
                                                              ┌────────▼─────────────┐
                                                              │ AI service (fams-ai)  │
                                                              │ Python/FastAPI :5000  │
                                                              │ (không expose port    │
                                                              │  ra host ở full stack)│
                                                              │ - InsightFace         │
                                                              │ - DeepFace/MiniFASNet │
                                                              └──────────┬────────────┘
                                                                         │ SQL trực tiếp
                                                                         ▼
                                                              PostgreSQL (bảng face_profiles,
                                                              liveness_challenges — CHUNG DB
                                                              với Java, không qua Java)
```

Nguồn: `docker-compose.full.yml`, `docs/02-kien-truc-du-an.md`.

**Đặc điểm kiến trúc quan trọng cần lưu ý cho câu hỏi khả năng chịu tải:**

- Đây là **modular monolith** — 1 process Java duy nhất chứa toàn bộ 20 module nghiệp vụ (auth, employee, checkin, attendance, violation, dashboard...), không phải microservices tách rời theo domain. Chỉ AI/Face ID được tách thành service riêng.
- Mỗi thành phần hạ tầng (`fams-api`, `fams-ai`, `fams-postgres`, `fams-redis`) hiện chạy **đúng 1 container/instance** — không có cấu hình nhân bản (replica) trong bất kỳ file compose nào.
- AI service **ghi trực tiếp vào schema PostgreSQL của Java** (bảng `face_profiles`, `liveness_challenges`) bằng SQL thô qua `psycopg2`, không đi qua Java API — đây là điểm coupling chặt, ảnh hưởng tới khả năng scale độc lập của AI service (xem mục 7).

### 1.2 Luồng xử lý dữ liệu — từ request tới response

**Luồng đồng bộ (đa số API — CRUD, danh sách, báo cáo, đăng nhập...):**

```
HTTP Request
  → JwtAuthFilter (đọc Bearer token, kiểm tra blacklist Redis, tenant suspended, IP whitelist)
  → Controller (route, @Valid DTO, @PreAuthorize permission)
  → Request DTO (validation)
  → Service (nghiệp vụ, transaction, phối hợp repository)
  → Repository / Specification (JPA, PostGIS native query nếu cần geofence)
  → Entity ↔ PostgreSQL
  → Map Entity → Response DTO
  → ApiResponse wrapper → HTTP Response
```
(Nguồn: `docs/02-kien-truc-du-an.md` mục 3.1, đã đối chiếu `JwtAuthFilter.java`.)

**Luồng bất đồng bộ — riêng cho xác thực khuôn mặt lúc check-in (để không chặn response chấm công):**

```
1. Client gửi SubmitCheckin (kèm livenessChallengeId hoặc ảnh)
2. Java: lưu CheckinRecord (status ban đầu tuỳ policy site) → LPUSH job vào Redis list
   "fams:ai:face_verify_jobs" → trả response ngay cho client (KHÔNG đợi AI)
3. AI worker (1 thread nền trong fams-ai): BRPOP job → liveness check (MiniFASNet)
   → so khớp embedding (ArcFace, cosine similarity) → POST callback về Java
   (endpoint /internal/ai-callback/face-result, xác thực bằng X-Internal-Secret)
4. Java: cập nhật lại CheckinRecord/tạo Violation nếu cần, dựa trên kết quả callback
```
(Nguồn: `ai-service/app/worker.py`, `docs/02-kien-truc-du-an.md` mục 8.2.)

Thiết kế "bắn rồi xử lý bất đồng bộ" này là điểm mạnh cho khả năng chịu tải: request check-in trả về nhanh, không bị nghẽn bởi thời gian xử lý AI (vài trăm ms tới vài giây tuỳ model).

---

## 2. Hệ thống được thiết kế phục vụ tối đa bao nhiêu người dùng đồng thời / bao nhiêu request?

**Trả lời trung thực: không có.** Không tìm thấy tài liệu, cấu hình, hay bài test nào công bố một con số mục tiêu (VD: "500 concurrent users", "1000 req/s"). Đây là dự án chưa qua giai đoạn capacity planning chính thức.

Có thể suy ra **giới hạn thực tế theo cấu hình hiện tại** (không phải năng lực thiết kế, mà là điểm nghẽn sẽ gặp trước tiên):

| Lớp | Cấu hình hiện tại | Ý nghĩa |
|---|---|---|
| HikariCP (kết nối DB Java) | `maximum-pool-size: 10`, `minimum-idle: 2`, `connection-timeout: 30000` (`application.yml`) | Tối đa **10 truy vấn DB đồng thời** trên toàn bộ instance API — mọi request cần DB vượt quá 10 sẽ **xếp hàng chờ tối đa 30s** rồi lỗi timeout nếu không tới lượt |
| Tomcat embedded (Spring Boot) | Không override — dùng mặc định (~200 thread xử lý, ~8192 hàng đợi kết nối TCP) | Có thể *nhận* nhiều request đồng thời hơn 10, nhưng phần lớn sẽ bị chặn ở bước chờ kết nối DB phía trên |
| Redis | `maxmemory 256mb`, `allkeys-lru` (`docker-compose.full.yml`) | Đủ cho cache permission/session ở quy mô nhỏ-vừa; dưới áp lực bộ nhớ, Redis sẽ **tự động evict key theo LRU**, kể cả token blacklist — xem rủi ro ở mục 6 |
| AI verify worker | **1 thread duy nhất** xử lý tuần tự (`threading.Thread`, không phải thread pool) trong `worker.py` | Face verify là **nút cổ chai tuyến tính**: nếu 1 job mất ~1-2s (detect + embedding + liveness), worker chỉ xử lý được ~30-60 job/phút; job dồn vào hàng đợi Redis khi vượt tốc độ này (checkin vẫn "valid" ngay lập tức, chỉ kết quả xác thực khuôn mặt bị trễ) |
| Số instance mỗi service | 1 (`fams-api`, `fams-ai`, `fams-postgres`, `fams-redis`) | Không có dự phòng (redundancy); downtime của 1 container = downtime toàn hệ thống |

**Kết luận cho mục này**: hệ thống hiện phù hợp quy mô demo/pilot/vài chục-vài trăm tenant nhỏ dùng đồng thời ở mức vừa phải, **chưa được kiểm chứng hay cấu hình cho tải lớn**. Cần benchmark thật (mục 5) trước khi đưa ra con số cam kết cho khách hàng/đối tác.

---

## 3. Cơ chế phân phối tải, quản lý phiên đăng nhập, hàng đợi xử lý, kiểm soát lỗi

### 3.1 Phân phối tải (load balancing)

**Chưa triển khai.** Có tồn tại file `docker/nginx/nginx.conf` nhưng file này **đang rỗng (0 byte)** — không phải reverse proxy/load balancer đang hoạt động, có vẻ là placeholder chưa hoàn thiện. Không có file nào trong `docker-compose.yml` / `docker-compose.full.yml` / `docker-compose.dev.yml` khai báo Nginx, HAProxy, hay bất kỳ cơ chế cân bằng tải nào — client gọi thẳng vào container `fams-api` qua 1 port duy nhất (`8080`).

### 3.2 Quản lý phiên đăng nhập (session management)

Hệ thống dùng **JWT stateless**, không dùng session lưu trên server (`HttpSession`):

- **Access token**: JWT ký HMAC, TTL mặc định **15 phút** (`app.jwt.access-ttl-minutes`).
- **Refresh token**: chuỗi ngẫu nhiên 64 byte, TTL mặc định **30 ngày**; DB chỉ lưu **hash SHA-256** của refresh token, không lưu plaintext.
- **Đăng xuất / thu hồi token**: token bị đăng xuất được đưa vào **blacklist Redis** (`LogoutService.BLACKLIST_PREFIX`), `JwtAuthFilter` kiểm tra blacklist này trên mỗi request trước khi cho qua (`JwtAuthFilter.java:181-184`).
- **Đa tenant**: JWT mang claim `tenantId` + `role` đang active; endpoint `/auth/switch-tenant` cấp lại token mới.
- **Cache permission**: quyền hạn (permission) được cache theo `userId:tenantId` trong Redis, TTL **5 phút** (`JwtAuthFilter.PERMS_CACHE_TTL_SECONDS`), giảm tải truy vấn `UserRoleRepository`/`PermissionRepository` mỗi request.
- **Tenant suspend / IP whitelist**: kiểm tra trạng thái tenant bị khoá qua Redis key (`TenantService.TENANT_SUSPENDED_PREFIX`), không cần query DB.

Vì stateless, về lý thuyết **nhiều instance Java có thể chạy song song không cần sticky session** — đây là điểm thuận lợi sẵn có nếu sau này scale ngang (mục 7), miễn là Redis (nơi lưu blacklist/cache) được chia sẻ chung giữa các instance.

### 3.3 Hàng đợi xử lý (queue)

Chỉ có **1 hàng đợi** trong toàn hệ thống: Redis list `fams:ai:face_verify_jobs`, dùng `LPUSH`/`BRPOP` thô (không dùng RabbitMQ/Kafka/Celery/RQ). Đặc điểm:

- **Không có retry policy** — nếu worker lỗi giữa chừng (exception), job bị mất, không tự động thử lại.
- **Không có dead-letter queue (DLQ)** — job lỗi chỉ được `log.error`, không có nơi lưu lại để xử lý thủ công.
- **Không có idempotency key rõ ràng** ở tầng queue.
- Đã có 1 lớp bảo vệ nghiệp vụ: nếu **publish job thất bại** (lỗi mạng/Redis) tại site bắt buộc Face ID, check-in tự chuyển `pending_review` thay vì âm thầm giữ `valid` — nhưng đây là xử lý phía Java lúc *gửi* job, không phải cơ chế đảm bảo phía *hàng đợi*.
- Worker chỉ có **1 thread**, không scale ngang theo nhiều consumer (dù về lý thuyết chạy nhiều instance `fams-ai` cùng đọc chung 1 Redis list là khả thi vì `BRPOP` vốn an toàn cho multi-consumer — nhưng hiện chưa có compose config nào làm việc này).

(Nguồn: `ai-service/app/worker.py`, `docs/02-kien-truc-du-an.md` mục 12 điểm 5.)

### 3.4 Kiểm soát lỗi (error handling)

- **Hợp đồng lỗi thống nhất** qua `GlobalExceptionHandler`: mọi lỗi nghiệp vụ trả về JSON `{success:false, message, errorCode, userMessage}` — nhất quán toàn API.
- **Rate limiting**: chỉ áp dụng ở **một số endpoint nhạy cảm cụ thể**, không có rate limit chung toàn API/theo IP ở tầng gateway:
  - OTP verify: giới hạn theo cấu hình `otp.rate-limit-max` (mặc định 10).
  - Reset mật khẩu: `password-reset.rate-limit-max` (mặc định 3).
  - Gửi lại email xác thực: `email.resend-rate-limit-max` (mặc định 3).
  - Liveness challenge (chống gian lận Face ID): tối đa 5 lần/10 phút/nhân viên (`429 TOO_MANY_ATTEMPTS`).
  - Các endpoint còn lại (danh sách, báo cáo, checkin thường...) **không có rate limit** — dễ bị lạm dụng nếu có client lỗi lặp request hoặc tấn công.
- **Không có circuit breaker** (Resilience4j/Hystrix) giữa Java ↔ AI service hay Java ↔ Redis/DB — nếu AI service down, các lệnh gọi HTTP đồng bộ (enroll/status) sẽ chờ timeout (Java `AiServiceClient` cấu hình `connectTimeout=3s`, `readTimeout=30s`) rồi ném lỗi, không có fallback tự động hay retry.

---

## 4. Kiểm thử hiệu năng / tải / stress test — đã thực hiện chưa?

**Chưa thực hiện kiểm thử tải/stress ở mức HTTP (request/giây, độ trễ theo tải, số user đồng thối)** — không tìm thấy công cụ (JMeter, k6, Gatling, wrk, ab, Locust...), script, hay báo cáo kết quả nào trong repo liên quan tới loại test này.

**Đã có** một hoạt động liên quan nhưng khác bản chất — **kiểm thử khối lượng dữ liệu (data volume test)**, phục vụ đánh giá tốc độ truy vấn/phân trang/báo cáo khi DB lớn, không đo throughput API:

- `scripts/seed_perf.sql` + `scripts/seed_perf_cleanup.sql`: sinh trực tiếp bằng SQL (bỏ qua toàn bộ business validation của API) một bộ dữ liệu quy mô lớn.
- **Kết quả 1 lần chạy thật** (ghi trong `docs/testing/sample-data-requirements-v2.md`): **150 tenant, 9.455 user, 797 site, 23.302 nhân viên** (2 "mega tenant" ~2.500 nhân viên/tenant), **1.111.986 dòng checkin** (~1,1 triệu).
- Mục đích: có dữ liệu đủ lớn để **tự tay test** tốc độ danh sách/tìm kiếm/phân trang/báo cáo trên UI — nhưng **không có số liệu đo đạc (ms response time, req/s) nào được ghi lại** sau khi seed; đây là bộ dữ liệu chuẩn bị sẵn, chưa có báo cáo benchmark đi kèm.
- Script tự cảnh báo: chạy trên DB riêng, không trộn với dữ liệu demo chính, vì insert thẳng SQL bỏ qua validation.

**Kết luận**: hệ thống **chưa có cơ sở số liệu thực nghiệm** để trả lời "chịu được bao nhiêu req/s" hay "độ trễ p95/p99 ở tải X là bao nhiêu". Đây là khoảng trống cần lấp trước khi cam kết SLA hoặc go-live quy mô lớn.

---

## 5. Xử lý khi số lượng người dùng/request tăng đột biến (spike)

Hiện **không có cơ chế chủ động chống quá tải** (không auto-scaling, không circuit breaker, không backpressure ở tầng ứng dụng). Hành vi thực tế khi spike xảy ra, suy ra từ cấu hình hiện có:

| Điểm nghẽn | Hành vi khi vượt ngưỡng |
|---|---|
| HikariCP pool = 10 | Request mới xếp hàng chờ kết nối DB tối đa 30s → hết thời gian → lỗi `500`/timeout trả về client. Không có hàng đợi ưu tiên, không loại bỏ request cũ. |
| Redis 256MB maxmemory + allkeys-lru | Khi đầy bộ nhớ, Redis **tự xoá key cũ nhất theo LRU** — kể cả key **token blacklist**. ⚠️ Rủi ro bảo mật tiềm ẩn: dưới áp lực bộ nhớ cao, 1 token đã bị revoke có thể bị evict khỏi blacklist *trước* khi hết TTL tự nhiên, khiến token đó tạm thời hợp lệ trở lại. Đây là hệ quả phụ chưa được đánh giá rủi ro chính thức. |
| AI verify worker 1 thread | Job dồn ứ trong Redis list, xử lý tuần tự chậm dần — checkin vẫn ghi nhận ngay (không mất dữ liệu), nhưng **kết quả xác thực khuôn mặt/violation có thể trễ hàng phút** nếu spike lớn (VD: toàn bộ nhân viên chấm công đầu ca cùng lúc). |
| 1 instance Java, không LB | Không có dự phòng — nếu container quá tải/crash, **toàn bộ hệ thống gián đoạn** cho tới khi Docker `restart: unless-stopped` khởi động lại. |
| Không rate limit tổng | Không có lớp chặn sớm cho traffic bất thường (bot, retry loop lỗi phía client) ở phần lớn endpoint — traffic xấu đi thẳng tới tầng DB. |

**Về mất dữ liệu**: rủi ro mất dữ liệu **checkin** khi spike là thấp — checkin được ghi ngay đồng bộ vào PostgreSQL trước khi async đẩy job verify khuôn mặt, nên bản ghi chấm công không bị mất kể cả khi AI/Redis quá tải (chỉ verify bị trễ). Rủi ro mất dữ liệu cao hơn nằm ở **các thao tác đồng bộ khác** (VD ghi violation, cập nhật attendance summary) khi bị timeout DB giữa transaction — cần test kỹ hành vi rollback trong các trường hợp này (đã được ghi nhận là rủi ro kiến trúc trong `docs/02-kien-truc-du-an.md` mục 12.6).

---

## 6. Phương án mở rộng hệ thống trong tương lai (đề xuất — chưa triển khai)

Vì hiện trạng chưa có kiến trúc scale-out, dưới đây là lộ trình đề xuất theo thứ tự ưu tiên/độ phức tạp tăng dần:

1. **Đưa reverse proxy/load balancer thật vào vận hành** — hoàn thiện `docker/nginx/nginx.conf` (hiện rỗng) hoặc dùng managed load balancer (AWS ALB/GCP Load Balancer) khi lên cloud; cần thiết trước khi chạy nhiều instance `fams-api`.
2. **Scale ngang Java API** — vì đã stateless (JWT + Redis dùng chung), có thể chạy nhiều container `fams-api` phía sau load balancer mà không cần sticky session; cần tăng `hikari.maximum-pool-size` hợp lý theo số instance × pool để tránh vượt `max_connections` của PostgreSQL.
3. **Scale AI verify worker** — chạy nhiều container `fams-ai` cùng đọc chung Redis list `fams:ai:face_verify_jobs` (Redis `BRPOP` vốn hỗ trợ multi-consumer an toàn), giải quyết nút cổ chai 1-thread hiện tại.
4. **Nâng cấp cơ chế hàng đợi** — thay Redis list thô bằng broker có retry/DLQ/idempotency thật (RabbitMQ, hoặc Redis Streams nếu muốn giữ hạ tầng hiện có) cho luồng face-verify, theo đúng đề xuất đã ghi trong `docs/02-kien-truc-du-an.md` mục 13.5.
5. **Cache & DB**: bổ sung connection pooler (PgBouncer) nếu tăng số instance API; cân nhắc read replica PostgreSQL cho báo cáo/dashboard (tách read-heavy khỏi write path chấm công); Redis có thể chuyển sang cluster mode / managed Redis (AWS ElastiCache) khi vượt 256MB hiện tại.
6. **Tách dịch vụ (service decomposition) có chọn lọc** — không cần vi-dịch-vụ hoá toàn bộ 20 module ngay; ưu tiên tách các module có tải/nhịp độ khác biệt rõ (VD: dashboard/report đọc nhiều, notification gửi hàng loạt) nếu đo đạc thực tế cho thấy cần thiết — tránh over-engineering khi chưa có số liệu (mục 4).
7. **Triển khai cloud** — chuyển từ Docker Compose sang orchestrator hỗ trợ auto-scaling (Kubernetes HPA, AWS ECS/Fargate với target-tracking scaling) dựa trên CPU/queue-depth; cần thiết kế lại health check phân biệt readiness/liveness (hiện `/actuator/health` đã có nhưng đang bị vô hiệu hoá trong healthcheck dev vì phụ thuộc Firebase credential — xem `docker-compose.dev.yml` ghi chú).
8. **Đối tượng lưu trữ**: avatar đã trừu tượng hoá qua S3-compatible (`AvatarStorageService`) — sẵn sàng chuyển MinIO (dev) → AWS S3 (prod) không đổi code, chỉ đổi biến môi trường `S3_ENDPOINT`. Ảnh Face ID/checkin hiện lưu **trên đĩa cục bộ container fams-ai** (`STORAGE_BASE_PATH=/app/storage`, mount volume) — **chưa dùng object storage**, là điểm cần chuyển sang S3-compatible nếu scale AI service ra nhiều instance (nhiều container cần đĩa dùng chung, hiện chưa hỗ trợ).

---

## 7. Giám sát hiệu năng, cảnh báo sự cố, sao lưu và khôi phục dữ liệu

### 7.1 Giám sát (monitoring)

- Java API có **Spring Boot Actuator** (`spring-boot-starter-actuator` trong `pom.xml`), nhưng chỉ expose 2 endpoint: `/actuator/health`, `/actuator/info` (`management.endpoints.web.exposure.include: health,info`).
- **Không có Micrometer + Prometheus/Grafana**, không có APM (New Relic/Datadog/Elastic APM) được cấu hình.
- Có 1 cơ chế giám sát nội bộ tự viết: `ScheduledJobMonitor` (nhắc tới trong `docs/02-kien-truc-du-an.md` mục 9) ghi trạng thái các job định kỳ (subscription expiration, random-check dispatch, attendance summary...) để phục vụ health/status — đây là giải pháp custom nhỏ, không phải observability stack đầy đủ (không có dashboard, không có time-series lưu trữ dài hạn).
- AI service (`fams-ai`) có `/health` endpoint riêng, dùng cho Docker healthcheck, không có giám sát metric (latency model, queue depth...) nào được expose.

### 7.2 Cảnh báo sự cố (alerting)

**Không tìm thấy cơ chế cảnh báo tự động nào** — không có tích hợp Slack/Email/PagerDuty/webhook khi có lỗi hệ thống, job thất bại, hay downtime. Lỗi hiện chỉ được ghi vào log (`logger.error`, `traceback.format_exc()` phía Python; SLF4J phía Java) — cần người vận hành chủ động xem log, không có cơ chế đẩy cảnh báo chủ động.

### 7.3 Sao lưu và khôi phục dữ liệu (backup & restore)

**Không tìm thấy script, job, hay cấu hình backup tự động nào trong repo** (đã tìm theo tên file `backup*`/`restore*`, không có kết quả). Hiện trạng:

- Dữ liệu PostgreSQL và Redis chỉ được giữ bền vững nhờ **Docker named volume** (`fams_postgres_data`, `fams_redis_data`) — đây là cơ chế persistence của container, **không phải backup** (nếu volume bị xoá nhầm hoặc host bị hỏng, dữ liệu mất hoàn toàn, không có bản sao ở nơi khác).
- Không có `pg_dump`/`pg_basebackup`/WAL archiving định kỳ được cấu hình.
- Không có RPO (Recovery Point Objective) / RTO (Recovery Time Objective) nào được tài liệu hoá.
- Quy trình "khôi phục" duy nhất được ghi nhận (`docs/testing/sample-data-requirements-v2.md` mục 21) là **thủ công**: xoá volume + chạy lại `scripts/seed.sh` để tái tạo bộ **dữ liệu demo** — đây là quy trình reset môi trường test, **không phải khôi phục dữ liệu production thật**.
- Ảnh Face ID/checkin lưu trên đĩa container `fams-ai` cũng không có cơ chế backup ngoài lưu qua bind-mount `./ai-service/storage` (persistent theo host filesystem, nhưng vẫn không có bản sao dự phòng).

**Đây là khoảng trống rủi ro cao nhất trong toàn bộ báo cáo** — hệ thống chấm công có dữ liệu nghiệp vụ quan trọng (chấm công, vi phạm, dữ liệu sinh trắc học) nhưng chưa có chiến lược backup/DR chính thức. Khuyến nghị ưu tiên hàng đầu: thiết lập `pg_dump` định kỳ (hoặc managed database service có backup tự động nếu lên cloud — AWS RDS snapshot, GCP Cloud SQL backup) kèm lưu trữ off-site, và tài liệu hoá RPO/RTO mục tiêu.

---

## 8. Tổng hợp khoảng trống & khuyến nghị ưu tiên

| # | Khoảng trống | Mức độ ưu tiên | Khuyến nghị |
|---|---|---|---|
| 1 | Không có backup/restore tự động | 🔴 Cao nhất | Thiết lập `pg_dump`/snapshot định kỳ + lưu off-site, định nghĩa RPO/RTO |
| 2 | Chưa từng chạy load/stress test thật | 🔴 Cao | Chạy k6/JMeter với kịch bản checkin đồng thời (giờ cao điểm chấm công) trên bộ dữ liệu `seed_perf.sql` sẵn có, đo p95/p99 và req/s tối đa trước khi cam kết SLA |
| 3 | Không có load balancer/nhiều instance | 🟠 Trung bình-cao | Hoàn thiện `nginx.conf`, thử nghiệm chạy 2+ instance `fams-api` phía sau LB (đã stateless, khả thi ngay) |
| 4 | Redis LRU eviction có thể xoá blacklist token sớm | 🟠 Trung bình | Tăng `maxmemory` hoặc tách blacklist sang policy/DB riêng không bị LRU chi phối |
| 5 | Hàng đợi face-verify không có retry/DLQ | 🟠 Trung bình | Cân nhắc Redis Streams hoặc broker thật có DLQ |
| 6 | Không có alerting tự động | 🟠 Trung bình | Tích hợp tối thiểu 1 kênh cảnh báo (email/Slack) cho lỗi job định kỳ và downtime |
| 7 | Không có Prometheus/Grafana/APM | 🟡 Thấp-trung bình | Thêm Micrometer + Prometheus scrape khi có nhu cầu vận hành thật sự (không cần ngay ở quy mô hiện tại) |
| 8 | AI worker 1 thread, ảnh lưu local disk | 🟡 Thấp (ở quy mô hiện tại) | Cần giải quyết trước khi scale AI service ra nhiều instance |

---

*Nguồn tham chiếu chính dùng để lập báo cáo*: `docker-compose.yml`, `docker-compose.full.yml`, `docker-compose.dev.yml`, `api-server/src/main/resources/application.yml`, `api-server/src/main/java/com/fams/shared/security/JwtAuthFilter.java`, `api-server/src/main/java/com/fams/shared/ai/AiServiceClient.java`, `ai-service/app/worker.py`, `ai-service/app/main.py`, `docker/nginx/nginx.conf`, `scripts/seed_perf.sql`, `docs/02-kien-truc-du-an.md`, `docs/testing/sample-data-requirements-v2.md`.
