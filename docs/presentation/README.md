# Đề cương slide thuyết trình đồ án FAMS

Tài liệu này đề xuất bố cục khoảng 20 slide để thuyết trình sản phẩm phần mềm đồ án cuối kỳ FAMS. Nội dung được tổ chức theo trình tự: **bài toán → nghiệp vụ → thiết kế → AI → triển khai → demo → kết quả**, đồng thời bám theo cấu trúc của báo cáo luận văn.

Thời lượng phù hợp cho bố cục này là khoảng **15–20 phút**, chưa bao gồm phần hỏi đáp.

## 1. Bố cục đề xuất 20 slide

| Slide | Tiêu đề | Nội dung chính | Hình ảnh nên sử dụng |
|---:|---|---|---|
| 1 | Trang bìa | Tên đề tài FAMS, thành viên, giảng viên hướng dẫn và đơn vị | Logo FAMS và ảnh giao diện chính |
| 2 | Bối cảnh và bài toán | Hạn chế của chấm công thủ công; chấm công hộ; khó quản lý nhiều công trình; dữ liệu phân tán | Ba hoặc bốn biểu tượng minh họa vấn đề |
| 3 | Mục tiêu và phạm vi đề tài | SaaS Multi-tenant; Web Admin; Mobile App; GPS/geofence; Face ID/liveness; Random Check | Sơ đồ mục tiêu tổng quát |
| 4 | Đối tượng sử dụng hệ thống | Platform Admin, Tenant Admin/Owner, HR Manager, Site Supervisor và Employee | Sơ đồ actor đơn giản |
| 5 | Các phân hệ chức năng chính | SaaS/tenant, nhân sự, công trình, ca làm việc, chấm công, Face ID, Random Check, vi phạm và báo cáo | Sơ đồ module hoặc Use Case tổng quan |
| 6 | Quy trình nghiệp vụ tổng quát | Tạo tenant → nhân viên → công trình → ca → phân công → Face ID → chấm công → bảng công | Activity/flow nghiệp vụ cấp cao |
| 7 | Nghiệp vụ chấm công | Ba chính sách `gps_only`, `gps_face`, `gps_face_liveness`; check-in/out; offline sync | Sơ đồ so sánh ba chính sách |
| 8 | Phòng chống gian lận | Geofence; Face ID; active/passive liveness; Random Check; xử lý vi phạm | Luồng chống gian lận nhiều lớp |
| 9 | Kiến trúc tổng quan hệ thống | Web Admin, Mobile App, Backend API, AI Service, Redis/Queue, PostgreSQL/PostGIS và MinIO | Sơ đồ kiến trúc tổng quan |
| 10 | Kiến trúc Multi-tenant và bảo mật | Tenant isolation; JWT; 2FA; RBAC; site scope; audit log; IP whitelist | Sơ đồ request đi qua các lớp bảo mật |
| 11 | Thiết kế dữ liệu | Identity/Tenant, Workforce, Site/Shift, Check-in, Face ID, Random Check và Attendance | ERD tổng quan chia theo phân hệ |
| 12 | Kiến trúc AI của FAMS | SCRFD, ArcFace, MiniFASNet, landmarks, FastAPI và Redis Worker | Sơ đồ kiến trúc AI |
| 13 | Quy trình đăng ký Face ID | Consent → liveness challenge → embedding → HR review → approve/reject | Sequence Diagram enrollment |
| 14 | Quy trình xác minh khi chấm công | GPS/geofence → queue → AI Worker → similarity/liveness → callback → attendance/violation | Sequence Diagram xác minh AI |
| 15 | Công nghệ sử dụng | Spring Boot, Next.js, React Native/Expo, FastAPI, PostgreSQL/PostGIS, Redis, MinIO và Docker | Logo công nghệ |
| 16 | Kiến trúc triển khai production | VPS, Docker, Cloudflare, Nginx Proxy Manager, API replicas và monitoring | Deployment Diagram |
| 17 | Trình diễn Web Admin | Tenant, nhân viên, site/geofence, ca/phân công, Face ID review, bảng công và vi phạm | Bốn đến sáu ảnh giao diện hoặc demo trực tiếp |
| 18 | Trình diễn Mobile App | Đăng nhập → xem site → Face ID → check-in → Random Check → lịch sử công | Chuỗi màn hình Mobile theo một kịch bản |
| 19 | Kiểm thử và kết quả | Backend API, Web E2E, Mobile unit test, QA thủ công; kết quả và tồn đọng | Bảng kết quả và biểu đồ tỷ lệ |
| 20 | Kết luận và hướng phát triển | Kết quả đạt được, hạn chế, hướng mở rộng, lời cảm ơn và Q&A | Ba cột: đạt được – hạn chế – phát triển |

## 2. Nội dung cụ thể từng phần

### 2.1. Slides 1–3: Mở đầu

Ba slide đầu cần trả lời nhanh ba câu hỏi:

- Bài toán thực tế là gì?
- Vì sao cần xây dựng FAMS?
- Đề tài giải quyết đến phạm vi nào?

#### Slide 1 – Trang bìa

Nội dung cần có:

- Tên đề tài: **Hệ thống quản lý chấm công công trình FAMS**.
- Tên sinh viên hoặc nhóm thực hiện.
- Giảng viên hướng dẫn.
- Khoa, trường và năm thực hiện.
- Logo FAMS hoặc một ảnh giao diện tiêu biểu.

Không đưa nội dung kỹ thuật hoặc đoạn mô tả dài lên trang bìa.

#### Slide 2 – Bối cảnh và bài toán

Chỉ nên trình bày bốn vấn đề nổi bật:

- Nhân viên làm việc phân tán tại nhiều công trình.
- Chấm công truyền thống khó xác minh vị trí và danh tính.
- Có nguy cơ sử dụng ảnh, video hoặc nhờ người khác chấm công.
- HR mất nhiều thời gian tổng hợp và xử lý sai lệch bảng công.

Có thể minh họa bằng một luồng đối chiếu ngắn:

```text
Chấm công thủ công
      ↓
Khó xác minh vị trí và danh tính
      ↓
Sai lệch dữ liệu và mất thời gian xử lý
```

#### Slide 3 – Mục tiêu và phạm vi đề tài

Thông điệp chính nên sử dụng:

> FAMS cung cấp một nền tảng SaaS Multi-tenant giúp doanh nghiệp quản lý nhân sự công trình và chấm công bằng GPS, Face ID, liveness cùng cơ chế Random Check chống gian lận.

Phạm vi triển khai gồm:

- Web Admin dành cho quản trị nền tảng, quản trị công ty, HR và giám sát.
- Mobile App dành cho nhân viên và giám sát công trình.
- Backend API xử lý nghiệp vụ đa tenant.
- AI Service xử lý Face ID và liveness.
- Dashboard, báo cáo, audit và hệ thống thông báo.

### 2.2. Slides 4–8: Nghiệp vụ chính

Đây là phần giúp hội đồng hiểu sản phẩm trước khi đi vào kiến trúc kỹ thuật.

#### Slide 4 – Đối tượng sử dụng hệ thống

Trình bày năm nhóm actor chính:

| Actor | Trách nhiệm chính |
|---|---|
| Platform Admin | Quản trị tenant, subscription và hoạt động nền tảng SaaS |
| Tenant Admin/Owner | Quản trị công ty, cấu hình và phân quyền trong tenant |
| HR Manager | Quản lý nhân viên, công trình, ca, phân công, bảng công và vi phạm |
| Site Supervisor | Theo dõi nhân sự, chấm công và Random Check tại site được giao |
| Employee | Đăng ký Face ID, check-in/out, phản hồi Random Check và xem bảng công cá nhân |

Không cần đưa toàn bộ Use Case Diagram chi tiết lên slide này.

#### Slide 5 – Các phân hệ chức năng chính

Nhóm chức năng thành các khối:

- Xác thực và quản lý tài khoản.
- Quản trị SaaS Multi-tenant và subscription.
- Quản lý role, permission và audit log.
- Quản lý nhân viên và workspace.
- Quản lý công trình, geofence, ca và phân công.
- Face ID và liveness.
- Check-in/out và bảng công.
- Random Check và vi phạm.
- Dashboard, báo cáo và thông báo.

Nên sử dụng sơ đồ module hoặc Use Case tổng quan đã rút gọn.

#### Slide 6 – Quy trình nghiệp vụ tổng quát

Trình bày một quy trình xuyên suốt:

```text
Platform Admin tạo tenant
        ↓
HR quản lý nhân viên và cơ cấu tổ chức
        ↓
HR tạo công trình, geofence và ca làm việc
        ↓
Phân công nhân viên vào công trình
        ↓
Nhân viên đăng ký Face ID, HR phê duyệt
        ↓
Nhân viên check-in/check-out trên Mobile App
        ↓
Hệ thống tổng hợp bảng công và phát hiện vi phạm
```

Đây là slide nghiệp vụ quan trọng vì kết nối tất cả phân hệ thành một câu chuyện thống nhất.

#### Slide 7 – Nghiệp vụ chấm công

Sử dụng bảng so sánh ba chính sách:

| Chính sách | GPS/geofence | Face ID | Liveness |
|---|:---:|:---:|:---:|
| `gps_only` | ✓ |  |  |
| `gps_face` | ✓ | ✓ |  |
| `gps_face_liveness` | ✓ | ✓ | ✓ |

Nội dung thuyết trình:

- Mỗi site hoặc ca có thể áp dụng chính sách chấm công phù hợp.
- Backend kiểm tra phân công và thời gian trước khi kiểm tra GPS.
- Face ID và liveness chỉ được kích hoạt khi policy yêu cầu.
- Mobile App hỗ trợ lưu chấm công offline và đồng bộ khi có mạng lại.

#### Slide 8 – Phòng chống gian lận

Trình bày các lớp bảo vệ:

1. Geofence kiểm tra nhân viên có nằm trong vùng công trình hay không.
2. Face ID xác minh đúng danh tính nhân viên.
3. Active liveness yêu cầu thực hiện hành động ngẫu nhiên.
4. Passive anti-spoofing phát hiện ảnh in hoặc phát lại qua màn hình.
5. Random Check kiểm tra nhân viên trong thời gian làm việc.
6. Vi phạm được tạo tự động, nhân viên có thể giải trình và HR xử lý.

Luồng Random Check đề xuất:

```text
Hệ thống hoặc HR phát yêu cầu
        ↓
Nhân viên nhận FCM notification
        ↓
Phản hồi trong thời gian giới hạn
        ↓
Kiểm tra GPS / Face ID / Liveness
        ↓
Pass hoặc tạo vi phạm
        ↓
Nhân viên giải trình, HR xác nhận/bỏ qua
```

### 2.3. Slides 9–11: Phân tích và thiết kế

Không nên đưa toàn bộ Use Case Diagram hoặc ERD vật lý lên slide vì chữ sẽ quá nhỏ.

#### Slide 9 – Kiến trúc tổng quan hệ thống

Luồng chính:

```text
Web Admin / Mobile App
          ↓ HTTPS
Spring Boot Backend API
   ├── PostgreSQL/PostGIS
   ├── Redis Queue
   ├── MinIO
   └── FastAPI AI Service
```

Các điểm cần nhấn mạnh:

- Web và Mobile không gọi trực tiếp database hoặc AI Service.
- Backend API là cổng vào duy nhất của nghiệp vụ.
- Redis tách request HTTP khỏi thời gian AI suy luận.
- PostgreSQL/PostGIS lưu dữ liệu nghiệp vụ và geofence.
- MinIO lưu trữ đối tượng do Backend quản lý.

#### Slide 10 – Multi-tenant và bảo mật

Trình bày đường đi của một request:

```text
Request
  → JWT/2FA
  → Tenant đang active
  → RBAC permission
  → Site scope
  → Validation nghiệp vụ
  → Repository/Database
  → Audit log
```

Thông điệp chính:

- Mỗi request được gắn với tenant đang active.
- Tenant isolation ngăn truy cập dữ liệu giữa các doanh nghiệp.
- RBAC quyết định quyền thao tác.
- Site scope giới hạn dữ liệu của giám sát.
- IP whitelist hỗ trợ giới hạn truy cập theo vai trò.
- Audit log lưu các hành động quan trọng để truy vết.

#### Slide 11 – Thiết kế dữ liệu

Chỉ đưa ERD tổng quan theo nhóm:

- Identity, Tenant và RBAC.
- Workforce và Workspace.
- Site, Geofence, Shift và Assignment.
- Face ID và Liveness Challenge.
- Check-in, Attendance và Violation.
- Random Check, Notification và Audit.

ERD vật lý chi tiết và danh sách cột nên để trong báo cáo hoặc phụ lục.

### 2.4. Slides 12–14: Mô hình AI

Đây là nội dung quan trọng, nên dành khoảng ba đến bốn phút.

#### Slide 12 – Kiến trúc AI của FAMS

Pipeline xử lý:

```text
Ảnh đầu vào
   ↓
SCRFD phát hiện khuôn mặt
   ↓
Landmark và head pose/blink
   ↓
MiniFASNet anti-spoofing
   ↓
ArcFace tạo embedding 512 chiều
   ↓
Cosine similarity
   ↓
Kết quả Face ID và liveness
```

Các điểm cần nói rõ:

- AI Service chạy độc lập bằng FastAPI.
- SCRFD yêu cầu phát hiện đúng một khuôn mặt.
- ArcFace tạo embedding 512 chiều đã chuẩn hóa.
- MiniFASNet kiểm tra giả mạo thụ động.
- Landmark được sử dụng để nhận diện tư thế đầu và chớp mắt.
- AI không tự quyết định nghiệp vụ chấm công; Backend áp dụng policy và tạo vi phạm.

#### Slide 13 – Quy trình đăng ký Face ID

Sử dụng sơ đồ:

- [`fams-ai-enrollment-sequence.puml`](../architecture/fams-ai-enrollment-sequence.puml)

Khi đưa lên slide, nên giản lược tên API và chỉ giữ sáu bước:

1. Nhân viên ghi nhận consent.
2. Backend tạo liveness challenge.
3. AI kiểm tra ảnh, pose, blink, anti-spoofing và same-person.
4. ArcFace tạo embedding.
5. Hồ sơ được lưu ở trạng thái chờ duyệt.
6. HR approve hoặc reject; chỉ embedding được duyệt mới dùng để xác minh.

Thông điệp cần nhấn mạnh:

> FAMS áp dụng human-in-the-loop: AI không tự kích hoạt Face ID, quyết định cuối cùng thuộc HR hoặc quản trị viên có thẩm quyền.

#### Slide 14 – Quy trình xác minh khi chấm công

Sử dụng sơ đồ:

- [`fams-ai-verification-sequence.puml`](../architecture/fams-ai-verification-sequence.puml)

Luồng rút gọn:

```text
Mobile gửi check-in
        ↓
Backend kiểm tra JWT, assignment và GPS/geofence
        ↓
Đẩy Face Verify Job vào Redis
        ↓
AI Worker xử lý ảnh và so khớp embedding
        ↓
AI callback kết quả về Backend
        ↓
Cập nhật check-in, violation và attendance
```

Điểm cần nhấn mạnh:

- Request không phải chờ AI suy luận hoàn tất.
- Redis cung cấp hàng đợi bất đồng bộ.
- Callback được bảo vệ bằng internal secret.
- Kết quả AI được sử dụng thống nhất trong chấm công, Random Check, bảng công và vi phạm.

### 2.5. Slides 15–16: Công nghệ và triển khai

#### Slide 15 – Công nghệ sử dụng

Chia slide thành bốn nhóm:

| Nhóm | Công nghệ |
|---|---|
| Frontend | Next.js, React, React Native, Expo, TypeScript |
| Backend | Java 21, Spring Boot, Spring Security, Spring Data JPA, Flyway |
| AI | Python, FastAPI, InsightFace, SCRFD, ArcFace, MiniFASNet, ONNX Runtime |
| Dữ liệu và hạ tầng | PostgreSQL, PostGIS, Redis, MinIO, Docker, Nginx Proxy Manager, Cloudflare |

Không giải thích dài về từng framework. Chỉ nói lý do chọn những công nghệ quan trọng.

#### Slide 16 – Kiến trúc triển khai production

Sử dụng Deployment Diagram và trình bày:

- VPS Ubuntu 22.04.1 LTS.
- 5 vCPU, 4 GB RAM, 80 GB SSD và 1 GB swap.
- Cloudflare DNS Proxy và HTTPS.
- Nginx Proxy Manager định tuyến domain.
- Toàn bộ dịch vụ chạy trong Docker.
- Backend API được cấu hình hai replica.
- PostgreSQL, Redis và AI Service không public trực tiếp.
- Prometheus, Grafana, cAdvisor và Node Exporter phục vụ giám sát.
- Mobile APK gọi Backend qua `api.fams.io.vn`.

Luồng triển khai rút gọn:

```text
Web/Mobile
   ↓ HTTPS
Cloudflare
   ↓
Nginx Proxy Manager
   ├── fams-web
   ├── fams-api
   └── fams-minio

fams-api
   ├── PostgreSQL/PostGIS
   ├── Redis
   ├── MinIO
   └── FastAPI AI
```

### 2.6. Slides 17–18: Trình diễn sản phẩm

Không demo theo từng màn hình rời rạc. Nên demo thành một câu chuyện nghiệp vụ hoàn chỉnh.

#### Slide 17 – Demo Web Admin

Kịch bản đề xuất:

1. Platform Admin xem tenant và thông tin vận hành.
2. HR đăng nhập vào công ty.
3. HR xem hoặc tạo nhân viên.
4. HR xem công trình và geofence.
5. HR xem ca làm việc và phân công.
6. HR duyệt Face ID của nhân viên.
7. HR xem check-in, bảng công và vi phạm.
8. HR xem dashboard hoặc xuất báo cáo công tháng.

Nếu demo trực tiếp, slide chỉ cần ghi “Kịch bản trình diễn” và tám bước trên. Chuẩn bị ảnh dự phòng phòng trường hợp mạng hoặc server gặp sự cố.

#### Slide 18 – Demo Mobile App

Dùng một tài khoản nhân viên và thực hiện:

1. Đăng nhập.
2. Xem công trình và ca được phân công.
3. Xem hoặc đăng ký Face ID.
4. Thực hiện check-in bằng GPS/Face ID/liveness.
5. Nhận và phản hồi Random Check.
6. Xem kết quả chấm công.
7. Xem lịch sử và bảng công cá nhân.
8. Gửi giải trình nếu có vi phạm hoặc check-in bất thường.

Nên quay sẵn video 60–90 giây cho luồng camera/liveness vì demo camera trực tiếp có thể bị ảnh hưởng bởi ánh sáng, mạng hoặc thời gian xử lý AI.

### 2.7. Slide 19: Kiểm thử và kết quả

Chỉ đưa các số liệu quan trọng:

| Hạng mục | Kết quả gần nhất |
|---|---:|
| Backend unit test | 7/7 đạt |
| Backend API test suite | 146/146 đạt ở lần chạy tổng hợp gần nhất |
| Mobile unit/contract test | 17/17 đạt |
| Web Playwright | 89 đạt, 1 lỗi timeout, 3 chưa chạy hoặc không hoàn tất |
| QA theo tính năng | 140/147 hoàn tất đầy đủ |

Nội dung thuyết trình:

- Các chức năng cốt lõi đã được kiểm thử trên Backend, Web Admin và Mobile App.
- Một ca Web E2E của Random Check còn lỗi timeout và cần kiểm tra lại.
- Một số luồng Firebase, GPS và camera cần tiếp tục kiểm thử trên nhiều thiết bị thật.
- Chưa có kết quả kiểm thử tải chính thức nên không công bố số người dùng đồng thời.

### 2.8. Slide 20: Kết luận và hướng phát triển

Chia slide thành ba cột.

#### Kết quả đạt được

- Hoàn thiện nền tảng SaaS Multi-tenant.
- Quản lý nhân viên, công trình, ca và phân công.
- Chấm công bằng GPS, Face ID và liveness.
- Random Check, vi phạm, bảng công và báo cáo.
- Triển khai Web, Backend, AI và Mobile App.

#### Hạn chế

- AI hiện chạy CPU trên một VPS có tài nguyên giới hạn.
- Chưa có kiểm thử hiệu năng chính thức.
- Một số luồng Firebase/camera cần mở rộng kiểm thử đa thiết bị.
- Hệ thống một VPS chưa cung cấp khả năng chịu lỗi cao.

#### Hướng phát triển

- Tách AI và database sang máy chủ riêng khi tải tăng.
- Bổ sung load testing và cảnh báo tự động.
- Hiệu chỉnh ngưỡng Face ID/liveness trên tập dữ liệu thực tế.
- Phát hành ứng dụng qua Google Play và App Store.
- Hoàn thiện backup, khôi phục và phương án dự phòng sự cố.

Kết thúc bằng lời cảm ơn và chuyển sang phần hỏi đáp.

## 3. Phân bổ thời gian đề xuất

| Phần | Slide | Thời gian |
|---|---:|---:|
| Bài toán và mục tiêu | 1–3 | Khoảng 2 phút |
| Nghiệp vụ hệ thống | 4–8 | Khoảng 4 phút |
| Thiết kế và kiến trúc | 9–11 | Khoảng 3 phút |
| Mô hình AI | 12–14 | Khoảng 3–4 phút |
| Công nghệ và triển khai | 15–16 | Khoảng 2 phút |
| Demo sản phẩm | 17–18 | Khoảng 4–5 phút |
| Kiểm thử và kết luận | 19–20 | Khoảng 2 phút |

Nếu thời lượng bị giới hạn còn 12–15 phút, nên rút ngắn slides 4, 10, 11 và 15; không nên cắt phần nghiệp vụ chấm công, AI, demo và kết quả.

## 4. Kế hoạch demo an toàn

### 4.1. Dữ liệu cần chuẩn bị

- Một tài khoản Platform Admin.
- Một tenant demo đã hoạt động.
- Một tài khoản HR có đủ permission.
- Một tài khoản Employee đã được phân công.
- Một site có geofence và ca làm việc hợp lệ trong thời điểm demo.
- Hồ sơ Face ID đã được duyệt hoặc một hồ sơ chờ duyệt.
- Một bản ghi check-in và một vi phạm mẫu.
- Một Random Check có thể kích hoạt thủ công.

### 4.2. Kiểm tra trước buổi bảo vệ

- Xác nhận các container đều healthy.
- Kiểm tra `app.fams.io.vn` và `api.fams.io.vn` qua mạng khác VPS.
- Đăng nhập lại Web và Mobile để xác nhận credential.
- Kiểm tra camera, GPS, FCM và quyền hệ điều hành trên điện thoại.
- Kiểm tra thời gian của VPS và thiết bị không bị lệch.
- Dọn dữ liệu demo gây nhiễu khỏi dashboard và báo cáo.
- Tắt thông báo cá nhân hoặc nội dung nhạy cảm trên điện thoại trình chiếu.
- Chuẩn bị ảnh chụp và video dự phòng.

### 4.3. Phương án dự phòng

Chuẩn bị ba cấp độ:

1. **Demo trực tiếp:** ưu tiên khi server, mạng và thiết bị ổn định.
2. **Video quay sẵn:** dùng cho Face ID, liveness và Random Check.
3. **Ảnh chụp theo từng bước:** dùng khi không thể phát video hoặc kết nối mạng bị gián đoạn.

## 5. Nguyên tắc thiết kế slide

- Mỗi slide chỉ truyền đạt một thông điệp chính.
- Không quá bốn hoặc năm gạch đầu dòng trên một slide.
- Không chụp nguyên trang báo cáo hoặc bảng đặc tả dài.
- Chỉ đưa Use Case, ERD và Sequence Diagram đã rút gọn.
- Font nội dung tối thiểu 22–24 pt; tiêu đề khoảng 30–36 pt.
- Sử dụng cùng màu nhận diện với Web Admin và Mobile App FAMS.
- Mỗi ảnh giao diện cần có chú thích actor và chức năng.
- Dùng cùng một kiểu icon, màu và cách đặt tiêu đề trên toàn bộ slide.
- Không dùng quá nhiều hiệu ứng chuyển động.
- Không để khóa API, mật khẩu, IP VPS hoặc nội dung `.env` xuất hiện trên slide.

## 6. Những nội dung nên để trong báo cáo thay vì slide

Không nên đưa các nội dung sau lên phần trình chiếu chính:

- Đặc tả đầy đủ của khoảng 40 Use Case.
- Toàn bộ Activity Diagram và Sequence Diagram.
- ERD vật lý có toàn bộ bảng và cột.
- Danh sách đầy đủ thư viện và phiên bản.
- Toàn bộ bảng kiểm thử chi tiết.
- Nội dung biến môi trường và cấu hình secret.
- Chi tiết từng câu lệnh cài đặt Docker/VPS.
- Toàn bộ API endpoint.

Các nội dung này có thể đặt trong slide phụ để sử dụng khi hội đồng đặt câu hỏi.

## 7. Slide phụ nên chuẩn bị

Ngoài 20 slide chính, nên chuẩn bị từ năm đến tám slide phụ và không tính vào phần thuyết trình chính:

1. Use Case tổng quan đầy đủ.
2. ERD theo từng phân hệ.
3. Sequence Diagram đăng ký Face ID đầy đủ.
4. Sequence Diagram xác minh AI bất đồng bộ đầy đủ.
5. Bảng quyền theo actor.
6. Bảng kiểm thử chi tiết.
7. Deployment Diagram đầy đủ.
8. Bảng ngưỡng Face ID/liveness và giải thích FAR/FRR.

Các slide phụ giúp trả lời câu hỏi chuyên sâu mà không làm 20 slide chính bị quá tải.

## 8. Thông điệp kết thúc đề xuất

> FAMS không chỉ số hóa thao tác chấm công mà còn kết hợp quản lý đa tenant, GPS/geofence, nhận diện khuôn mặt, liveness và Random Check thành một quy trình thống nhất. Hệ thống giúp doanh nghiệp quản lý nhân sự công trình minh bạch hơn, giảm gian lận và hỗ trợ HR tổng hợp bảng công hiệu quả hơn.

