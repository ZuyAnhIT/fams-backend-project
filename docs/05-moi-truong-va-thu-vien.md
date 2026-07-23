# 05. Tóm tắt môi trường và thư viện

> Thông tin lấy từ `pom.xml`, `requirements.txt`, Dockerfile, Compose, `application.yml` và `.env.example` tại ngày 2026-07-23. Không sao chép giá trị từ `.env` thật vì có thể chứa secret.

## 1. Technology stack

| Khu vực | Công nghệ/phiên bản |
|---|---|
| Java runtime | Java 21, Eclipse Temurin |
| Java framework | Spring Boot 3.5.15 |
| Build Java | Maven; production image dùng Maven 3.9 + Temurin 21; có Maven Wrapper |
| Python runtime | Python 3.11 slim |
| Python API | FastAPI 0.111.0 + Uvicorn 0.29.0 |
| Database | PostgreSQL/PostGIS image `postgis/postgis:16-3.4` |
| Cache/queue | Redis 7 Alpine |
| Object storage dev | MinIO `latest` |
| Object storage prod | AWS S3-compatible qua AWS SDK v2 |
| Migration | Flyway, 64 migration hiện có |
| API documentation | OpenAPI/Swagger qua springdoc |
| Container orchestration local | Docker Compose v2 + Makefile |
| API test chính | Bash + curl, 161 shell test |
| Face recognition | `face_recognition`/dlib ResNet-34 |
| Liveness | DeepFace anti-spoofing/FasNet, TensorFlow + PyTorch CPU |

## 2. Yêu cầu máy phát triển

Theo README, cách chuẩn không cần cài Java/Maven/Python trực tiếp. Cần:

- Docker 24+.
- Docker Compose v2.
- `make`.
- Bash và `curl` để chạy scripts/tests từ host.
- Git/editor tùy chọn.

Tài nguyên cần lưu ý:

- Java dev JVM được chạy với `-Xms256m -Xmx512m` trong compose dev.
- Full AI image khá nặng vì chứa PyTorch CPU, TensorFlow, dlib/face models và DeepFace.
- Build AI lần đầu tải/build nhiều dependency native; nên giữ Docker layer cache.
- PostgreSQL, Redis, Maven cache và MinIO dùng Docker volume.

## 3. Các biến thể môi trường

| Mục đích | Lệnh | Compose | Service |
|---|---|---|---|
| Setup/dev Java | `make setup` hoặc `make dev-d` | base + dev override | API, PostGIS, Redis, MinIO, one-shot seed |
| Dev foreground | `make dev` | base + dev override | như trên, xem log trực tiếp |
| Production-like Java | `make prod` | base | API JAR, PostGIS, Redis; không auto-seed |
| Full Face ID | `make full-d` | full | API JAR, AI, PostGIS, Redis |
| Full dev | `make full-dev-d` | full + dev override | API source mount, AI, MinIO, auto-seed |

Java-only phù hợp auth/tenant/RBAC/employee/site/checkin GPS/attendance không gọi Face ID. Endpoint enroll/verify/liveness cần full stack.

## 4. Service và port

| Service | Host | Container/network | Ghi chú |
|---|---:|---:|---|
| Java API | `${API_EXPOSE_PORT:-8080}` | `fams-api:8080` | Swagger và REST API |
| PostgreSQL/PostGIS | `${DB_EXPOSE_PORT:-5433}` | `fams-postgres:5432` | DB `fams_db` mặc định |
| Redis | `${REDIS_EXPOSE_PORT:-6379}` | `fams-redis:6379` | Có password |
| MinIO S3 API | `${MINIO_EXPOSE_PORT:-9000}` | `minio:9000` | Chỉ dev override |
| MinIO console | `${MINIO_CONSOLE_EXPOSE_PORT:-9001}` | `minio:9001` | Chỉ dev override |
| Python AI | Không expose | `fams-ai:5000` | Chỉ gọi trong `fams-net` |

URL local thường dùng:

```text
API health:      http://localhost:8080/api/v1/auth/health
Actuator health: http://localhost:8080/actuator/health
Swagger UI:      http://localhost:8080/swagger-ui.html
OpenAPI JSON:    http://localhost:8080/v3/api-docs
MinIO console:   http://localhost:9001
```

## 5. Cấu hình Spring Boot

File: `api-server/src/main/resources/application.yml`.

### 5.1 Database/JPA

```text
JDBC: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
Hikari max pool: 10
Hikari min idle: 2
Connection timeout: 30s
Hibernate ddl-auto: validate
Default schema: public
Flyway location: classpath:db/migration
```

Profile mặc định là `dev`; profile dev bật `show-sql=true` và format SQL. Production nên đặt `SPRING_PROFILES_ACTIVE=prod` và không dựa vào default dev.

### 5.2 Upload

- Multipart max file: 5 MB.
- Multipart max request: 5 MB.
- Ảnh avatar đi S3/MinIO.
- Ảnh Face ID được chuyển tới AI service; full compose mount `ai-service/storage` vào `/app/storage`.

### 5.3 API/monitoring

- Server port 8080.
- Actuator expose `health,info`.
- Chi tiết health chỉ hiện khi authorized.
- Swagger có thể tắt qua `SWAGGER_ENABLED=false`.
- Default response media type JSON.

## 6. Maven dependency Java

### 6.1 Spring Boot starters

Các starter sau không ghi version riêng; version được Spring Boot parent 3.5.15 dependency-management quản lý.

| Dependency | Công dụng trong dự án |
|---|---|
| `spring-boot-starter-web` | Spring MVC/REST, JSON, multipart |
| `spring-boot-starter-validation` | Jakarta Bean Validation cho DTO |
| `spring-boot-starter-data-jpa` | Hibernate, repository, specification, transaction |
| `spring-boot-starter-data-redis` | `StringRedisTemplate`, cache/blacklist/queue |
| `spring-boot-starter-security` | JWT filter chain, method authorization |
| `spring-boot-starter-websocket` | Hạ tầng WebSocket; cần kiểm tra mức sử dụng thực tế trước khi dựa vào nó |
| `spring-boot-starter-actuator` | Health/info endpoints và custom indicators |
| `spring-boot-starter-mail` | Gmail SMTP cho verification/reset/invitation |
| `spring-boot-starter-test` | JUnit/Spring test; scope test |
| `spring-security-test` | Security test support; scope test |

### 6.2 Database và migration

| Dependency | Version | Vai trò |
|---|---:|---|
| `org.postgresql:postgresql` | Boot-managed | JDBC driver runtime |
| `org.flywaydb:flyway-core` | Boot-managed | Migration engine |
| `org.flywaydb:flyway-database-postgresql` | Boot-managed | PostgreSQL Flyway support |

PostGIS không có Java library riêng trong POM; geospatial logic dùng native SQL trên extension PostgreSQL.

### 6.3 Authentication, provider và token

| Dependency | Version | Vai trò |
|---|---:|---|
| `io.jsonwebtoken:jjwt-api` | 0.12.6 | API ký/parse JWT |
| `io.jsonwebtoken:jjwt-impl` | 0.12.6 runtime | Implementation JWT |
| `io.jsonwebtoken:jjwt-jackson` | 0.12.6 runtime | JSON serializer cho JWT |
| `com.google.api-client:google-api-client` | 2.4.0 | Verify Google ID token |
| `com.google.firebase:firebase-admin` | 9.4.3 | Verify Firebase Phone token và gửi FCM |

### 6.4 File, API docs và object storage

| Dependency | Version | Vai trò |
|---|---:|---|
| `org.apache.poi:poi-ooxml` | 5.3.0 | Import/export Excel employee |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 2.8.5 | Swagger UI/OpenAPI |
| `software.amazon.awssdk:s3` | 2.29.6 | MinIO/AWS S3 avatar storage |
| `org.projectlombok:lombok` | Boot-managed, optional | Getter/setter/builder/log boilerplate |

### 6.5 Build plugins

- `spring-boot-maven-plugin`: đóng gói executable JAR; loại Lombok khỏi artifact.
- `maven-compiler-plugin`: cấu hình Lombok annotation processor cho compile và test compile.
- Java source level lấy từ `java.version=21`.

## 7. Python/AI dependencies

File `ai-service/requirements.txt` khóa version trực tiếp:

| Package | Version | Vai trò |
|---|---:|---|
| `fastapi` | 0.111.0 | REST API |
| `uvicorn[standard]` | 0.29.0 | ASGI server/reload |
| `face_recognition` | 1.3.0 | Detect/encode khuôn mặt qua dlib |
| `deepface` | 0.0.93 | Liveness/anti-spoofing wrapper |
| `numpy` | 1.26.4 | Vector/embedding computation |
| `Pillow` | 10.3.0 | Xử lý ảnh |
| `psycopg2-binary` | 2.9.9 | PostgreSQL pool và SQL trực tiếp |
| `redis` | 5.0.4 | Redis queue consumer |
| `httpx` | 0.27.0 | Callback HTTP về Java |
| `pydantic` | 2.7.1 | Schema/config model |
| `python-multipart` | 0.0.9 | Upload enrollment photos |
| `python-dotenv` | 1.0.1 | Load `.env` ngoài container |

Dockerfile cài thêm:

- `torch` từ CPU wheel index, **không khóa version**.
- `tensorflow`, **không khóa version**.
- `tf-keras`, **không khóa version**.
- Native packages: build-essential, cmake, OpenBLAS, LAPACK, GLib/SM/X11/GOMP, libGL và curl.

Rủi ro reproducibility: ba ML framework/shim không pin version và MinIO dùng `latest`. Một rebuild trong tương lai có thể tạo image khác dù source không đổi. Nên pin sau khi xác nhận combination đang chạy ổn.

## 8. Nhóm biến môi trường

### 8.1 Database/Redis

| Biến | Bắt buộc | Default/ý nghĩa |
|---|---|---|
| `DB_NAME`, `DB_USER` | Có qua compose | `fams_db`, `fams_user` trong mẫu |
| `DB_PASSWORD` | Có | Không có default an toàn |
| `DB_HOST`, `DB_PORT` | Compose tự đặt | Local default `localhost:5433`; container `fams-postgres:5432` |
| `REDIS_PASSWORD` | Có | Secret |
| `REDIS_HOST`, `REDIS_PORT` | Compose tự đặt | Local/container address |

### 8.2 JWT/session/security

| Biến | Ý nghĩa | Default |
|---|---|---|
| `JWT_SECRET` | HMAC signing key | Bắt buộc |
| `JWT_ACCESS_TTL_MINUTES` | Access token TTL | 15 phút |
| `JWT_REFRESH_TTL_DAYS` | Refresh token TTL | 30 ngày |
| `TOTP_ENCRYPTION_KEY` | Mã hóa TOTP secret | Bắt buộc |
| `CORS_ALLOWED_ORIGIN_PATTERNS` | Browser cross-origin patterns | Local/LAN patterns ở dev |

JWT secret phải đủ dài cho `Keys.hmacShaKeyFor`; mẫu gợi ý `openssl rand -hex 32`.

### 8.3 Email/OAuth/Firebase

| Biến | Dùng bởi |
|---|---|
| `GMAIL_USERNAME`, `GMAIL_APP_PASSWORD` | Spring Mail SMTP |
| `GOOGLE_CLIENT_ID` | Google login/link |
| `FCM_PROJECT_ID`, `FCM_SERVICE_ACCOUNT_JSON` | Firebase Admin: Phone Auth token verify + FCM |
| `OTP_RATE_LIMIT_MAX` | Throttle `/auth/otp/verify`, mặc định 10/15 phút/IP theo code/comment |
| `EMAIL_VERIFICATION_TTL_HOURS` | Verification token TTL, default 24h |
| `EMAIL_RESEND_RATE_LIMIT_MAX` | Resend verification rate, default 3 |
| `PASSWORD_RESET_RATE_LIMIT_MAX` | Password reset rate, default 3 |
| `INVITATION_EXPIRY_DAYS` | Invitation expiry, default 7 ngày |

`FCM_SERVICE_ACCOUNT_JSON` là JSON một dòng có private key; không log, commit hoặc đưa vào tài liệu/issue.

### 8.4 AI

| Biến | Java/Python | Default |
|---|---|---|
| `AI_INTERNAL_SECRET` | Cả hai | Bắt buộc; shared secret cho HTTP/callback |
| `AI_SERVICE_INTERNAL_URL` | Java | `http://fams-ai:5000` |
| `JAVA_API_INTERNAL_URL` | Python | `http://fams-api:8080` |
| `AI_ENROLL_MIN_PHOTOS` | Cả hai | 3 |
| `AI_ENROLL_MAX_PHOTOS` | Cả hai | 5 |
| `AI_FACE_SIMILARITY_THRESHOLD` | Python | 0.55 |
| `AI_LIVENESS_THRESHOLD` | Python | 0.6 |
| `STORAGE_BASE_PATH` | Python | `/app/storage` |

Java `FaceIdService` còn đọc `app.ai.verify-timeout-seconds` với default 30 giây, dù biến tương ứng chưa được khai rõ trong `application.yml`; placeholder default tại annotation vẫn có hiệu lực.

### 8.5 S3/MinIO

| Biến | Dev default |
|---|---|
| `S3_ENDPOINT` | `http://fams-minio:9000` |
| `S3_REGION` | `us-east-1` |
| `S3_BUCKET` | `fams-avatars` |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY` | MinIO credential mẫu; production phải thay bằng IAM credential phù hợp |
| `S3_PUBLIC_URL` | `http://localhost:9000/fams-avatars` |

Production AWS thường để `S3_ENDPOINT` trống để SDK gọi AWS endpoint thật và tự cấu hình bucket policy/CDN.

### 8.6 Retention và port

- `DATA_RETENTION_DELIVERY_LOG_DAYS`: default 30.
- `DATA_RETENTION_NOTIFICATION_DAYS`: default 90.
- `DB_EXPOSE_PORT`: default 5433.
- `REDIS_EXPOSE_PORT`: default 6379.
- `API_EXPOSE_PORT`: default 8080.
- `MINIO_EXPOSE_PORT`: default 9000.
- `MINIO_CONSOLE_EXPOSE_PORT`: default 9001.
- `SPRING_PROFILES_ACTIVE`: mẫu dùng `dev`.

## 9. Storage và dữ liệu runtime

| Dữ liệu | Nơi lưu | Độ bền local |
|---|---|---|
| Business data | PostgreSQL volume `fams_postgres_data` | Giữ qua restart/down; mất khi `down -v` |
| Redis/cache/queue | `fams_redis_data` | Giữ qua restart; queue/cache có thể mất khi xóa volume |
| Avatar | MinIO volume `minio_data` ở dev | Giữ qua restart; production ở S3 |
| AI enrollment/checkin photos | Host `./ai-service/storage` mount `/app/storage` | Nằm trên filesystem host |
| Maven dependencies | `maven_cache` | Tăng tốc dev rebuild |

`make stop-v`, `make full-stop-v` và `make clean` có thể xóa volume. Không chạy trên môi trường có dữ liệu cần giữ mà chưa backup.

## 10. Lệnh vận hành thường dùng

```bash
make help
make setup             # dev + auto-seed
make dev-d             # Java dev background
make full-dev-d        # Java + AI dev background
make ps
make logs
make logs-api
make logs-ai
make restart-api
make restart-ai
make shell-api
make shell-db
make seed
make stop
```

Build/test Java không qua Docker nếu máy có JDK 21:

```bash
cd api-server
bash mvnw test
bash mvnw package
```

Trong checkout hiện tại, `mvnw` không có executable bit nên `./mvnw` trả `Permission denied`; gọi qua `bash mvnw` vẫn dùng đúng Maven Wrapper.

Test API cần stack đang chạy và đã seed:

```bash
BASE_URL=http://localhost:8080 bash tests/auth/test_login.sh
BASE_URL=http://localhost:8080 bash tests/run_all.sh
```

## 11. External services và chế độ suy giảm

| Dependency ngoài | Khi thiếu/sai config |
|---|---|
| Gmail SMTP | Email verification/reset/invitation gửi thất bại; do một số send là async nên cần xem log |
| Google OAuth | Google login/link không hoạt động |
| Firebase Admin | Phone OTP token verify và FCM không hoạt động; actuator aggregate có thể báo DOWN |
| AI service | Endpoint Face ID/liveness lỗi; check-in GPS không có ảnh vẫn dùng được |
| S3/MinIO | Upload avatar lỗi |
| Redis | Auth permission cache/blacklist/rate-limit/queue/random check bị ảnh hưởng lớn |
| PostgreSQL | API nghiệp vụ không hoạt động |

Dev healthcheck compose cố ý dùng `/api/v1/auth/health` thay vì actuator, vì FCM credential giả/thiếu có thể làm actuator trả HTTP 503 dù Java process vẫn phục vụ feature không liên quan FCM.

## 12. Các điểm cấu hình cần chuẩn hóa

1. Pin version `torch`, `tensorflow`, `tf-keras` và MinIO image.
2. Tạo profile production rõ trong config hoặc external config, thay vì chỉ có base + dev block.
3. Quản lý secret qua secret manager/CI secrets; `.env` chỉ dành local.
4. Đồng bộ `.env.example` với mọi property đang đọc ở Java/Python, gồm verify timeout và threshold AI.
5. Xác định retention/backup cho ảnh AI trên host; hiện không có lifecycle/backup được mô tả rõ.
6. Giám sát Redis queue depth, callback failure và job pending timeout.
7. Thêm tool/version lock hoặc CI image để mọi developer build cùng Maven/Docker behavior.

## 13. Hai lỗi local build đã quan sát trong checkout hiện tại

1. `api-server/mvnw` được Git lưu mode `100644`, nên chạy `./mvnw` báo `Permission denied`. Dùng `bash mvnw ...` hoặc sửa executable bit trong một commit riêng.
2. Dev container mount `./api-server:/workspace` và có thể tạo một phần `api-server/target` với owner `root`. Khi Maven chạy bằng user host, bước copy resource có thể báo `Operation not permitted`. Đây không phải compile error của source. Cách xử lý là dừng container, xóa/chuyển ownership **chỉ thư mục generated `api-server/target`** một cách có kiểm soát, hoặc build trong container/thư mục sạch. Không thay ownership toàn repository.
