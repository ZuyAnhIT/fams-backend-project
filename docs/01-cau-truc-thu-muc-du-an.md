# 01. Cấu trúc thư mục dự án FAMS Backend

> Phạm vi khảo sát: mã nguồn tại repository `fams-backend-project`, ngày 2026-07-23. Các thư mục sinh tự động như `.git`, `target`, cache IDE, `__pycache__` và dữ liệu runtime trong `ai-service/storage` không được xem là mã nguồn.

## 1. Tổng quan nhanh

Repository là một backend đa dịch vụ, gồm:

- `api-server`: API chính bằng Java/Spring Boot, tổ chức theo module nghiệp vụ.
- `ai-service`: dịch vụ Python/FastAPI cho Face ID và liveness.
- `database`: ERD, migration/seed tham khảo ở cấp repository.
- `api-server/src/main/resources/db/migration`: **nguồn Flyway thực sự được Java API chạy khi khởi động**.
- `docker`, các file `docker-compose*.yml`: hạ tầng chạy local/production-like.
- `scripts`: khởi động, migration và seed.
- `tests`: 161 shell test gọi API thật bằng `curl`.
- `docs`: backlog, API, kiến trúc, bảo mật, review và hướng dẫn vận hành.

Tại thời điểm khảo sát có 671 file do `rg --files` nhìn thấy, trong đó có 375 file Java chính, 64 Flyway migration, 17 file Python và 161 shell test.

## 2. Sơ đồ cây cấp repository

```text
fams-backend-project/
├── .github/
│   ├── ISSUE_TEMPLATE/                 # Mẫu bug/feature issue
│   └── modernize/java-upgrade/         # Dấu vết/cấu hình công cụ nâng cấp Java
├── .vscode/settings.json               # Thiết lập editor dùng chung
├── ai-service/                         # Python AI companion service
│   ├── app/
│   │   ├── routers/                    # HTTP router FastAPI
│   │   │   ├── enroll.py               # Enroll/revoke khuôn mặt
│   │   │   ├── health.py               # Health endpoint
│   │   │   └── status.py               # Trạng thái Face ID
│   │   ├── services/
│   │   │   ├── callback_service.py     # Callback kết quả về Java API
│   │   │   ├── face_service.py         # Trích xuất/so sánh embedding
│   │   │   ├── liveness_service.py     # Anti-spoof/liveness
│   │   │   └── storage_service.py      # Lưu/xóa ảnh Face ID
│   │   ├── config.py                   # Đọc biến môi trường
│   │   ├── db.py                       # PostgreSQL connection pool
│   │   ├── dependencies.py             # Kiểm tra X-Internal-Secret
│   │   ├── main.py                     # Tạo FastAPI app, nạp model, khởi động worker
│   │   ├── redis_client.py              # Redis connection pool
│   │   └── worker.py                    # Worker BRPOP job xác thực khuôn mặt
│   ├── .env.example
│   ├── Dockerfile
│   ├── preload_models.py                # Bake model liveness vào image
│   ├── requirements.txt
│   └── README.md
├── api-server/                         # Java API chính
│   ├── .mvn/wrapper/                   # Maven Wrapper
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/fams/
│   │   │   │   ├── ApiServerApplication.java
│   │   │   │   ├── modules/            # 20 module nghiệp vụ
│   │   │   │   └── shared/             # Hạ tầng/kỹ thuật dùng chung
│   │   │   └── resources/
│   │   │       ├── application.yml      # Cấu hình Spring và profile dev
│   │   │       ├── db/migration/        # Flyway V1..V64
│   │   │       └── static/google-login-test.html
│   │   └── test/java/com/fams/
│   │       └── ApiServerApplicationTests.java
│   ├── Dockerfile                       # Multi-stage Maven build + JRE 21
│   ├── Dockerfile.dev                   # Maven 3.9 + JDK 21 cho dev
│   ├── mvnw, mvnw.cmd
│   └── pom.xml                          # Dependency/build Java
├── database/
│   ├── diagrams/fams-erd.dbml           # Sơ đồ dữ liệu DBML
│   ├── migrations/                      # 2 migration lịch sử/tham khảo
│   ├── seeds/                           # Seed SQL rời
│   └── README.md
├── docker/
│   ├── nginx/nginx.conf                 # Reverse proxy config, chưa nằm trong compose hiện tại
│   ├── postgres/init-postgis.sql        # Khởi tạo PostGIS
│   ├── redis/redis.conf                 # Redis config rời
│   └── seed/Dockerfile                  # Image chạy seed một lần
├── docs/
│   ├── api/                             # Tài liệu API
│   ├── architecture/                    # Tài liệu kiến trúc cũ
│   ├── database/                        # Hướng dẫn migration
│   ├── deployment/                      # Chạy local
│   ├── issues/                          # Danh sách vấn đề
│   ├── manual-tests/                    # Kịch bản kiểm thử thủ công
│   ├── reviews/                         # Review app/web/backend
│   ├── security/                        # Auth/RBAC
│   ├── setup/                           # Firebase setup
│   └── BACKLOG.md
├── scripts/
│   ├── dev-start.sh, dev-stop.sh
│   ├── migrate.sh
│   ├── seed.sh                          # Seed qua API + SQL fallback
│   └── seed_historical.sql
├── tests/                               # Integration/API test bằng Bash + curl
│   ├── attendance/                      # 9 test
│   ├── audit/                           # 1 test
│   ├── auth/                            # 29 test
│   ├── checkin/                         # 10 test
│   ├── dashboard/                       # 3 test
│   ├── employee/                        # 13 test + fixtures Excel
│   ├── face-id/, face_id/               # 9 test Face ID
│   ├── notification/                    # 6 test
│   ├── randomcheck/                     # 18 test
│   ├── rbac/                            # 11 test
│   ├── report/                          # 6 test
│   ├── search/                          # 1 test
│   ├── security/                        # 2 test
│   ├── site/                            # 16 test site/shift/geofence/assignment
│   ├── subscription/                    # 5 test
│   ├── tenant/                          # 9 test
│   ├── violation/                       # 5 test
│   ├── workspace/                       # 6 test
│   ├── lib/test_helpers.sh
│   └── run_all.sh
├── .env.example                         # Mẫu cấu hình chung; `.env` thật không commit
├── Makefile                             # Lệnh vận hành chuẩn
├── README.md
├── backend-structure.txt                # Cây cũ, đã lỗi thời và lẫn virtualenv
├── docker-compose.yml                   # Java API + PostGIS + Redis
├── docker-compose.dev.yml               # Dev override + MinIO + auto-seed
└── docker-compose.full.yml              # Java + AI + PostGIS + Redis
```

## 3. Cây package Java

Mẫu chuẩn trong một module:

```text
modules/<ten-module>/
├── controller/       # REST endpoint, auth annotation, HTTP mapping
├── dto/
│   ├── request/      # Payload đầu vào + Bean Validation
│   └── response/     # Hợp đồng dữ liệu trả ra
├── entity/           # JPA entity ánh xạ bảng
├── repository/       # Spring Data JPA/JPQL/native query
├── service/          # Nghiệp vụ và transaction; hiện là implementation trực tiếp
├── specification/    # Filter động cho list/search
└── job|scheduler|redis|util|converter/   # Chỉ có ở module cần thiết
```

### 3.1 Danh mục đầy đủ 20 module nghiệp vụ

| Module | Số file | Thành phần chính | Trách nhiệm |
|---|---:|---|---|
| `assignment` | 9 | controller, 2 request DTO, response, entity, repository, service, specification, bitmask util | Phân công nhân viên vào site/shift theo ngày lặp |
| `attendance` | 10 | controller, 4 DTO, entity, job, repository, service, specification | Tổng hợp công, đi muộn, về sớm, OT, thiếu checkout |
| `audit` | 6 | controller, response, entity, 2 repository/spec, service | Nhật ký hành động |
| `auth` | 48 | 2 controller, 21 DTO, 4 entity, 4 repository, 16 service, specification | Đăng ký, login, JWT, refresh/logout, email, phone, Google, TOTP, profile |
| `checkin` | 16 | 2 controller, 9 DTO, entity, repository, 2 service, specification | Check-in/out GPS, offline sync, callback Face ID |
| `dashboard` | 7 | controller, 3 response, 3 service | Dashboard employee/supervisor/HR |
| `employee` | 40 | 5 controller, 18 DTO, 5 entity, 5 repository, 5 service, 2 specification | Nhân viên, phòng ban, invitation, import/export, Face ID |
| `geofence` | 8 | controller, converter, 3 DTO, entity, repository, service | Polygon/buffer vị trí của site |
| `notification` | 31 | 4 controller, 12 DTO, 5 entity, job, 5 repository, 4 service | Inbox, template, setting, FCM device/delivery, retention |
| `platform` | 2 | controller, response | Trạng thái hệ thống |
| `randomcheck` | 33 | 2 controller, 12 DTO, 3 entity, 3 job, queue, 3 repository, 8 service, constants | Sinh/lập lịch/gửi/nhận kiểm tra ngẫu nhiên |
| `rbac` | 23 | 3 controller, 10 DTO, 3 entity, 3 repository, 3 service, specification | Role, permission, gán quyền theo tenant/platform |
| `report` | 9 | controller, 7 response, service | Báo cáo attendance, site presence, violation, Face ID |
| `search` | 3 | controller, response, service | Global search |
| `shift` | 8 | controller, 4 DTO, entity, repository, service | Ca làm, cửa sổ check-in, cấu hình OT |
| `site` | 9 | controller, 4 DTO, entity, repository, service, specification | Địa điểm làm việc |
| `subscription` | 20 | controller, 8 DTO, 3 entity, 3 repository, scheduler, 4 service | Plan, giới hạn gói, subscription, hết hạn |
| `tenant` | 21 | controller, 9 DTO, 3 entity, 3 repository, 4 service, specification | Công ty/tenant, setting, IP whitelist, suspend |
| `violation` | 12 | controller, 7 DTO, entity, repository, service, specification | Vi phạm và xử lý/ảnh hưởng attendance |
| `workspace` | 16 | 2 controller, 7 DTO, 2 entity, 2 repository, 2 service, specification | Cây workspace và thành viên |

### 3.2 Danh sách file theo module lõi

```text
modules/auth/
├── controller/{AuthController,UserController}.java
├── dto/request/
│   ├── LoginRequest, LoginTotpRequest, FirebasePhoneLoginRequest
│   ├── RegisterRequest, GoogleLoginRequest, SwitchTenantRequest
│   ├── RefreshTokenRequest, LogoutRequest, ChangePasswordRequest
│   ├── ForgotPasswordRequest, ResetPasswordRequest, ResendVerificationRequest
│   └── TotpVerifyRequest, DisableTotpRequest, UpdateProfileRequest
├── dto/response/
│   ├── LoginResponse, RegisterResponse, SessionResponse
│   └── TotpEnableResponse, TotpSetupResponse, UserProfileResponse
├── entity/{User,RefreshToken,TotpBackupCode,HealthCheck}.java
├── repository/{UserRepository,RefreshTokenRepository,TotpBackupCodeRepository,HealthCheckRepository}.java
├── service/
│   ├── AuthService, RegisterService, RefreshTokenService, LogoutService
│   ├── LoginTotpService, TotpService, TotpSecretCipher
│   ├── FirebasePhoneLoginService, FirebasePhoneTokenVerifier, GoogleLoginService
│   ├── EmailService, EmailVerificationService, PasswordResetService
│   └── ChangePasswordService, UserProfileService, AvatarStorageService
└── specification/UserSpecification.java

modules/checkin/
├── controller/{CheckinController,FaceResultCallbackController}.java
├── dto/request/{SubmitCheckinRequest,SubmitCheckoutRequest,OfflineCheckinRequest,
│                OverrideCheckinRequest,FaceResultCallbackRequest}.java
├── dto/response/{AvailableSiteResponse,CheckinResponse,CheckinDetailResponse,SyncResultItem}.java
├── entity/CheckinRecord.java
├── repository/CheckinRepository.java
├── service/{CheckinService,OfflineSyncService}.java
└── specification/CheckinSpecification.java

modules/attendance/
├── controller/AttendanceSummaryController.java
├── dto/request/AdjustAttendanceSummaryRequest.java
├── dto/response/{AttendanceSummaryResponse,AttendanceMonthlyResponse,AttendanceHrMonthlyResponse}.java
├── entity/AttendanceSummary.java
├── repository/AttendanceSummaryRepository.java
├── service/AttendanceSummaryService.java
├── specification/AttendanceSummarySpecification.java
└── job/AttendanceSummaryJob.java

modules/randomcheck/
├── controller/{RandomCheckConfigController,ScheduledCheckController}.java
├── dto/request/                         # 7 request DTO
├── dto/response/                        # 5 response DTO
├── entity/{RandomCheckConfig,ScheduledCheck,CheckResponse}.java
├── repository/{RandomCheckConfigRepository,ScheduledCheckRepository,CheckResponseRepository}.java
├── service/
│   ├── RandomCheckConfigService, ScheduledCheckGeneratorService
│   ├── RandomCheckDispatchService, ManualCheckService
│   ├── CheckResponseService, NoResponseViolationService
│   └── ScheduledCheckCancelService, CheckExpiredException
├── job/{RandomCheckSchedulerJob,RandomCheckDispatchJob,NoResponseViolationJob}.java
├── redis/RandomCheckDispatchQueue.java
└── constant/RandomCheckEventTypes.java
```

### 3.3 Package dùng chung

```text
shared/
├── ai/
│   ├── AiServiceClient.java             # HTTP client Java -> AI enroll/status/revoke
│   ├── FaceVerifyJobPublisher.java      # Đẩy job verify vào Redis list
│   ├── FaceStatusDto.java
│   └── AiServiceException.java
├── client/FcmClient.java                # Firebase Cloud Messaging
├── config/
│   ├── SecurityConfig.java              # Stateless security chain + CORS
│   ├── RedisConfig.java
│   ├── FcmConfig.java
│   └── OpenApiConfig.java
├── constants/AppConstants.java
├── dto/{ExplanationResponse,SubmitExplanationRequest}.java
├── exception/
│   ├── GlobalExceptionHandler.java
│   └── 15 exception nghiệp vụ/kỹ thuật
├── monitoring/
│   ├── FcmHealthIndicator, RedisHealthIndicator
│   ├── RandomCheckJobHealthIndicator, RandomCheckQueueHealthIndicator
│   └── ScheduledJobMonitor, ScheduledJobStatus, ScheduledJobStatusRepository
├── pagination/PageResponse.java
├── response/ApiResponse.java
├── security/
│   ├── JwtAuthFilter.java
│   ├── JwtProvider.java
│   ├── FamsUserDetails.java
│   ├── UserDetailsServiceImpl.java
│   └── HttpRequestUtils.java
└── util/{Masked,MaskedSerializer,MaskingUtils}.java
```

## 4. Tài nguyên và database

```text
api-server/src/main/resources/
├── application.yml
├── static/google-login-test.html
└── db/migration/
    ├── V1..V5      # extensions, auth identities, users/tokens, admin, tenants
    ├── V6..V14     # tenant settings/IP, plans/subscriptions, RBAC
    ├── V15..V21    # email/TOTP/Google, invitation, employee, workspace
    ├── V22..V32    # site, geofence, shift, assignment, checkin, attendance
    ├── V33..V43    # random check, violation, Face ID và kết quả verify
    ├── V44..V52    # tenant suspend, notification, audit, device, retention
    └── V53..V64    # offline nonce, department, profile, session, platform staff,
                     # recurring assignment và active tenant của refresh token
```

Lưu ý quan trọng: `database/migrations/` chỉ có V1–V2 cũ. Khi thêm schema mới phải thêm vào `api-server/src/main/resources/db/migration/`; nếu chỉ thêm vào `database/migrations/`, Spring Boot/Flyway sẽ không tự chạy file đó.

## 5. Các file/thư mục không nên dùng làm nguồn sự thật

- `backend-structure.txt` được sinh từ một phiên bản cũ trên Windows, chứa cả `venv` và package Python không còn tồn tại. Không dùng nó để định vị code hiện tại.
- `database/migrations/` không đầy đủ so với 64 migration mà ứng dụng chạy.
- `.github/modernize/.../logs`, `.claude`, IDE settings là metadata công cụ, không phải kiến trúc runtime.
- `ai-service/storage` là dữ liệu ảnh runtime và được mount từ host trong full compose; không đưa vào Git hoặc tài liệu cấu trúc source.

## 6. Quy tắc tìm nhanh cho người mới

- Bắt đầu từ endpoint: tìm `@RequestMapping`/`@*Mapping` trong `modules/<feature>/controller`.
- Theo nghiệp vụ: controller gọi class trong `service`, rồi theo các repository/service được inject trong constructor.
- Theo bảng: tìm `@Table(name = "...")` trong `entity`, sau đó tìm migration tạo bảng tương ứng.
- Theo permission: tìm chuỗi trong `@PreAuthorize`, rồi đối chiếu `V13__seed_roles_and_permissions.sql` và `UserRoleRepository`.
- Theo test: tìm tên feature trong `tests/<feature>`; đây là test API chạy trên stack thật, không phải unit test Maven.
