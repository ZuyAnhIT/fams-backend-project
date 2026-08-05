# Báo cáo: Kiến trúc AI và quy trình chấm công bằng Face ID

> Ngày lập: 2026-08-05. Báo cáo mô tả **hiện trạng thật trong code đang chạy** của `ai-service/` (Python/FastAPI) và phần tích hợp Face ID trong `api-server/` (Java/Spring Boot). Ở những điểm hệ thống **chưa đo/chưa kiểm chứng bằng số liệu thực nghiệm**, báo cáo nêu rõ thay vì suy đoán.

## Tóm tắt nhanh

| Câu hỏi | Trả lời ngắn |
|---|---|
| Mô hình AI dùng gì? | **InsightFace `buffalo_l`** (SCRFD phát hiện + ArcFace nhận diện + landmark 106/68 điểm) + **DeepFace/MiniFASNet** (chống giả mạo) — toàn bộ mã nguồn mở, tự host |
| Tự phát triển hay mã nguồn mở? | Mã nguồn mở tích hợp (pretrained), **không tự huấn luyện model**, **không dùng dịch vụ AI cloud bên thứ 3** (không AWS Rekognition/Azure Face/Google Vision) |
| Dữ liệu lưu dạng gì? | **Cả hai**: vector đặc trưng 512 chiều (plaintext trong PostgreSQL) **và** ảnh gốc JPEG (plaintext trên đĩa) |
| Bao nhiêu ảnh đăng ký? | 3-5 ảnh (HR hỗ trợ tại kiosk) hoặc 3 khung hình qua active-liveness challenge (tự đăng ký) |
| So khớp 1:1 hay 1:N? | **1:1** — xác minh đúng nhân viên đã claim (theo `employeeId` trong request), không tìm kiếm mở trên toàn bộ nhân viên |
| Ngưỡng nhận diện | Cosine similarity ≥ **0.55** (`AI_FACE_SIMILARITY_THRESHOLD`, có thể cấu hình) |
| Độ chính xác đo được? | **Chưa có** — chưa từng đo FAR/FRR/FMR/FNMR trên tập dữ liệu thật, chỉ có 1 ảnh test cố định để kiểm chứng logic pipeline |
| Chống giả mạo (liveness)? | **Có** — 2 lớp: active liveness (quay đầu/nháy mắt theo lệnh ngẫu nhiên) + passive anti-spoofing (MiniFASNet chống ảnh in/màn hình) |
| Xử lý ở đâu? | **Server nội bộ (self-hosted)**, CPU-only, cùng hạ tầng Docker — không on-device, không cloud AI bên thứ 3 |
| Thời gian nhận diện trung bình? | **Chưa đo/chưa công bố số liệu thực nghiệm** — chỉ có thể ước lượng định tính (xem mục 11) |
| Mã hóa dữ liệu sinh trắc? | **Không** — embedding và ảnh đều lưu dạng plaintext, không mã hóa (khác với TOTP secret trong cùng hệ thống, vốn dùng AES-GCM) |

---

## 1. Kiến trúc tổng thể chức năng Face ID

```
┌────────────┐  chụp ảnh/nộp   ┌──────────────────────┐  HTTP nội bộ  ┌───────────────────────────┐
│  App/Web    │  theo lệnh      │  Java API (fams-api)   │  X-Internal-  │  AI service (fams-ai)       │
│  (client)   │────────────────▶│  FaceIdController /     │  Secret       │  FastAPI :5000              │
│             │◀────────────────│  FaceIdReviewController│──────────────▶│                             │
└────────────┘   kết quả/status └──────────┬─────────────┘               │  - InsightFace buffalo_l    │
                                             │ LPUSH (checkin verify)      │    (SCRFD + ArcFace +       │
                                             ▼                             │     landmark + pose)        │
                                        Redis queue                       │  - DeepFace MiniFASNet      │
                                     fams:ai:face_verify_jobs              │    (anti-spoofing)          │
                                             │ BRPOP                       └──────────┬──────────────────┘
                                             └────────────────────────────────────────┘
                                                          │ SQL trực tiếp (psycopg2)
                                                          ▼
                                              PostgreSQL: face_profiles, liveness_challenges
                                                          │
                                              callback: POST /internal/ai-callback/face-result
                                                          ▼
                                              Java cập nhật CheckinRecord / tạo Violation
```

**Thành phần chính:**

- **`fams-api` (Java)**: 2 controller quản lý toàn bộ nghiệp vụ Face ID — `FaceIdController` (theo nhân viên: consent, enroll, status, liveness-challenge, revoke) và `FaceIdReviewController` (hàng đợi duyệt của HR: approve/reject/pending-review). Không tự chứa logic AI — mọi thao tác sinh trắc học đều gọi sang `fams-ai` qua `AiServiceClient`.
- **`fams-ai` (Python/FastAPI, service riêng)**: chứa toàn bộ model AI, xử lý ảnh, tính embedding, so khớp, ghi/đọc trực tiếp bảng `face_profiles`/`liveness_challenges` trong PostgreSQL bằng SQL thô (không qua Java).
- **Redis**: kênh hàng đợi bất đồng bộ cho bước xác thực khuôn mặt lúc chấm công (để check-in trả về ngay, không chờ AI xử lý).
- **PostgreSQL**: lưu embedding (vector), trạng thái hồ sơ, lịch sử liveness challenge.
- **Đĩa cục bộ container `fams-ai`** (`STORAGE_BASE_PATH=/app/storage`): lưu ảnh JPEG gốc (enrollment, checkin, liveness challenge).

Đây **không phải kiến trúc "1 mô hình AI làm mọi việc"** mà là một **pipeline nhiều bước độc lập** (detect → pose/blink → liveness → embedding → so khớp), mỗi bước là một model/thuật toán riêng, được `fams-ai` điều phối tuần tự.

---

## 2. Quy trình xử lý đầu-cuối

### 2.1 Thu nhận hình ảnh/video từ camera

- Hệ thống **chỉ thu nhận ảnh tĩnh (JPEG)**, **không quay/xử lý video liên tục**. Với luồng chấm công yêu cầu chống giả mạo chủ động, client (App) chụp **lần lượt từng ảnh theo lệnh** server đưa ra (ví dụ: "nhìn thẳng" → "quay trái" → "nháy mắt"), không phải 1 đoạn video.
- 2 luồng thu nhận:
  1. **HR hỗ trợ tại kiosk**: 3-5 ảnh tĩnh chụp trực tiếp, tải lên 1 lần qua `POST /enroll` (multipart).
  2. **Tự đăng ký / chấm công**: qua **active-liveness challenge** — server random 1 chuỗi hành động (luôn bắt đầu bằng `center`, cộng 2 hành động ngẫu nhiên trong `{turn_left, turn_right, look_up, look_down, blink}`), client chụp đúng 1 ảnh/hành động, nộp `POST .../liveness-challenge/{id}/frames`.
- Ảnh được gửi lên dưới dạng file JPEG qua HTTP multipart (Java) hoặc base64 (đường verify độc lập `submitVerify`) — không có ràng buộc độ phân giải/kích thước tối thiểu được kiểm tra ở tầng ứng dụng ngoài giới hạn dung lượng chung `spring.servlet.multipart.max-file-size: 5MB`.

### 2.2 Phát hiện khuôn mặt

- Thực hiện bởi **SCRFD** (`det_10g.onnx`, nằm trong gói `insightface buffalo_l`), chạy qua `onnxruntime` (CPU).
- Hàm `face_service.detect_single_face()` **bắt buộc phát hiện đúng 1 khuôn mặt**:
  - 0 khuôn mặt → raise `no_face_detected`.
  - ≥2 khuôn mặt → raise `multiple_faces_detected` (xem mục 12 — hệ thống **không tự chọn khuôn mặt to nhất/rõ nhất**, mà từ chối thẳng để tránh xác thực nhầm).

### 2.3 Kiểm tra chất lượng hình ảnh

**Đây là bước còn thiếu trong pipeline hiện tại.** Hệ thống **không có bước kiểm tra chất lượng ảnh riêng biệt** (độ mờ/blur score, độ sáng/brightness, độ phân giải tối thiểu, độ che khuất một phần...) trước khi đưa vào detect/embedding. Các kiểm tra gần nhất đóng vai trò "chất lượng" là gián tiếp:

- `check_liveness()` (MiniFASNet) — trả về điểm chống giả mạo, không đánh giá chất lượng ảnh nói chung.
- Việc detect thất bại (`no_face_detected`) là hệ quả gián tiếp của ảnh quá mờ/tối/thiếu sáng, nhưng hệ thống chỉ báo lỗi chung "không phát hiện khuôn mặt", không phân biệt được nguyên nhân (mờ, thiếu sáng, góc quá nghiêng, hay đúng là không có mặt).

### 2.4 Căn chỉnh và chuẩn hóa khuôn mặt (face alignment)

- Không phải code tự viết — **nằm bên trong thư viện InsightFace**: sau khi SCRFD trả về bounding box + 5 điểm mốc (landmark chính), pipeline `FaceAnalysis.get()` tự động crop, warp-align khuôn mặt về khung chuẩn (canonical template) trước khi đưa vào mạng ArcFace trích embedding — đây là bước chuẩn trong mọi pipeline ArcFace, được xử lý nội bộ, không lộ ra thành 1 API/hàm riêng trong `face_service.py`.
- Đo góc đầu (pitch/yaw/roll) dùng cho active-liveness được tính từ `landmark_3d_68` bằng **phép biến đổi tương đồng 3D (Umeyama/Procrustes) so với khuôn mặt trung bình chuẩn**, tích hợp sẵn trong InsightFace — không phải `cv2.solvePnP` tự viết như pipeline dlib cũ trước đây.

### 2.5 Trích xuất đặc trưng khuôn mặt (feature extraction)

- Model: **ArcFace** (`w600k_r50.onnx`), sinh vector **512 chiều, đã L2-normalize** (`face.normed_embedding`).
- Với enrollment nhiều ảnh: embedding của từng ảnh được tính riêng rồi **lấy trung bình cộng theo từng chiều** (`average_embeddings()`) thành 1 vector đại diện duy nhất.

### 2.6 So khớp với dữ liệu người dùng đã đăng ký

- **Cosine similarity** thuần túy bằng NumPy (`np.dot(a,b) / (norm(a)*norm(b))`), không dùng thư viện matching chuyên dụng (FAISS, Milvus...) — hợp lý vì đây là **so khớp 1:1** (1 vector mới với đúng 1 vector đã lưu của nhân viên được claim), không phải tìm kiếm 1:N trên hàng nghìn vector.
- Ngưỡng chấp nhận: `score >= AI_FACE_SIMILARITY_THRESHOLD` (mặc định **0.55**, cấu hình qua biến môi trường).
- Có kiểm tra an toàn: nếu 2 vector khác chiều dài (VD hồ sơ cũ 128 chiều dlib vs. embedding mới 512 chiều ArcFace) → raise lỗi rõ ràng `embedding_dimension_mismatch` thay vì crash hoặc so sánh sai.

### 2.7 Xác định danh tính

- Hệ thống **không "nhận diện ai đó là ai" theo nghĩa 1:N** (tìm trong toàn bộ nhân viên xem ảnh này khớp ai). Danh tính **luôn được client claim trước** (thông qua JWT đăng nhập + `employeeId` trong request check-in), AI chỉ trả lời **có/không** cho câu hỏi "ảnh này có đúng là người có `employeeId` này không" — đúng bản chất bài toán **xác minh (verification)**, không phải **nhận dạng (identification)**.

### 2.8 Ghi nhận thời gian và kết quả chấm công

- **Thời gian chấm công được ghi ngay lập tức, đồng bộ**, tại thời điểm `SubmitCheckin` — **không chờ** kết quả xác thực khuôn mặt (vốn xử lý bất đồng bộ, có thể mất thêm vài giây tới lâu hơn tùy tải hàng đợi).
- **Kết quả xác thực khuôn mặt** cập nhật sau, qua callback `POST /internal/ai-callback/face-result`:
  - Nếu site **không** bắt buộc Face ID → checkin giữ `valid` bất kể kết quả (ảnh chỉ mang tính tham khảo/best-effort).
  - Nếu site **bắt buộc** Face ID (`requireFaceIdCheckin=true`) và xác thực **thất bại** → checkin bị hạ xuống `pending_review` + tự động tạo 1 bản ghi `Violation` để HR xem xét lại thủ công.

---

## 3. Mô hình AI, thư viện, framework, dịch vụ bên thứ 3 đang dùng

| Chức năng | Công nghệ | Loại |
|---|---|---|
| Phát hiện khuôn mặt | **SCRFD** (`det_10g.onnx`, gói InsightFace `buffalo_l`) | Mã nguồn mở, pretrained |
| Nhận diện/embedding | **ArcFace** (`w600k_r50.onnx`, 512 chiều) | Mã nguồn mở, pretrained |
| Landmark & tư thế đầu | InsightFace 106-điểm 2D + 68-điểm 3D + pose | Mã nguồn mở, pretrained (đi kèm `buffalo_l`) |
| Chống giả mạo (anti-spoofing) | **MiniFASNet** (MiniFASNetV2 + MiniFASNetV1SE) qua thư viện **DeepFace 0.0.93** | Mã nguồn mở, pretrained |
| Runtime suy luận | **onnxruntime** (CPU) cho InsightFace; **PyTorch** + **TensorFlow/Keras** cho DeepFace/MiniFASNet | Mã nguồn mở |
| Nháy mắt (blink) | Thuật toán tự viết dựa trên nguyên lý EAR (Soukupová & Čech, 2016), áp lên landmark 106 điểm | Tự viết (thuật toán kinh điển, không phải model học máy) |
| So khớp | Cosine similarity (NumPy) | Tự viết, đơn giản |
| Dịch vụ AI cloud bên thứ 3 | **Không dùng** — không AWS Rekognition, không Azure Face API, không Google Cloud Vision | — |

**Framework hạ tầng**: FastAPI + Uvicorn (Python), giao tiếp với Java qua HTTP nội bộ (`AiServiceClient`, header `X-Internal-Secret`) và Redis (hàng đợi verify bất đồng bộ).

---

## 4. Mô hình tự phát triển, mã nguồn mở, hay tích hợp bên thứ 3?

**Tích hợp mã nguồn mở, không tự huấn luyện model.** Toàn bộ model (SCRFD, ArcFace, MiniFASNet) là **pretrained weight tải sẵn từ cộng đồng InsightFace/DeepFace** (bake sẵn vào Docker image lúc build, xem `ai-service/Dockerfile` — `preload_models.py` và lệnh `insightface.app.FaceAnalysis(...).prepare(...)`), không phải model do đội dự án tự thu thập dữ liệu/huấn luyện. Phần **tự viết** chỉ nằm ở lớp nghiệp vụ bao quanh: logic phân loại tư thế đầu, blink detection dựa trên landmark có sẵn, luồng enroll/review/consent, và cơ chế active-liveness challenge.

**Không** tích hợp dịch vụ AI thương mại bên thứ 3 (không SaaS API) — toàn bộ suy luận chạy **tại chỗ (self-hosted)** trên hạ tầng của dự án.

---

## 5. Dữ liệu khuôn mặt lưu dưới dạng gì?

**Cả hai — ảnh gốc VÀ vector đặc trưng**, không phải chỉ một trong hai:

- **Vector đặc trưng (embedding)**: cột `face_profiles.embedding DOUBLE PRECISION[]` trong PostgreSQL — mảng 512 số thực (trước đây 128 số thực khi còn dùng dlib). Đây là dữ liệu dùng để **so khớp lúc chấm công**.
- **Ảnh gốc JPEG**: lưu trên đĩa container `fams-ai`, chia theo mục đích:
  - `storage/enrollments/{tenant}/{employee}/*.jpg` — ảnh đăng ký gốc, còn giữ tới khi hồ sơ bị thu hồi (revoke).
  - `storage/checkins/{tenant}/{source_id}.jpg` — ảnh mỗi lần chấm công/check thất thường, dọn định kỳ sau N ngày (mặc định 30 ngày, `DATA_RETENTION_BIOMETRIC_PHOTO_DAYS`).
  - `storage/liveness_challenges/{tenant}/{challenge_id}.jpg` — khung `center` của mỗi challenge đã pass, cùng chu kỳ dọn dẹp với ảnh checkin.
- **Không** lưu "ảnh phẳng để so khớp trực tiếp" (không so khớp bằng pixel-matching) — việc so khớp luôn qua vector, đúng thông lệ ngành. Ảnh gốc được giữ lại **chỉ để phục vụ HR xem xét thủ công** (duyệt enrollment, xem lại trường hợp nghi vấn), không dùng trong vòng lặp so khớp tự động.

⚠️ Cả embedding lẫn ảnh đều lưu **dạng plaintext, không mã hóa** — xem chi tiết mục 13.

---

## 6. Cơ chế đăng ký khuôn mặt ban đầu

Có **2 luồng đăng ký**, khác nhau về ai được dùng luồng nào:

| Luồng | Ai dùng | Số ảnh/mẫu | Chống gian lận |
|---|---|---|---|
| **HR hỗ trợ tại kiosk** | HR/Admin thao tác hộ, nhân viên có mặt trực tiếp | **3-5 ảnh tĩnh** (`AI_ENROLL_MIN_PHOTOS`/`MAX_PHOTOS`), upload 1 lần | Có giám sát trực tiếp của con người (coi là 1 lớp chống gian lận); mỗi ảnh vẫn phải qua anti-spoofing + kiểm tra N ảnh cùng 1 người |
| **Tự đăng ký (self-service)** | Nhân viên tự làm qua app cá nhân — **bắt buộc**, không được gọi thẳng API ảnh tĩnh | **3 khung hình** qua active-liveness challenge (center + 2 hành động ngẫu nhiên) | Chống giả mạo đầy đủ: pose thật, blink thật, cùng 1 người xuyên suốt, chống ảnh in/màn hình |

**Cả 2 luồng đều KHÔNG kích hoạt ngay** — mọi lượt nộp (kể cả đăng ký lại của người đã `enrolled`) đưa vào `review_status=pending`, chờ HR/Admin có quyền `face_id:manage` **approve/reject** thủ công qua `POST /enroll/{employeeId}/approve` (không tự duyệt được cho chính mình). Trong lúc chờ duyệt, khuôn mặt cũ (nếu có) vẫn dùng chấm công được bình thường — không gián đoạn dịch vụ.

Trước khi enroll, nhân viên phải **tự mình xác nhận consent** (`POST /face-id/consent`) — HR không được đồng ý hộ, kể cả có quyền quản lý (tuân thủ Nghị định 13/2023/NĐ-CP về dữ liệu sinh trắc học).

---

## 7. Phương pháp so khớp 1:1 hay 1:N, ngưỡng và độ chính xác

- **1:1 (verification)**, không phải 1:N (identification). Server luôn biết trước danh tính được claim (`employeeId`) trước khi so khớp — chỉ so 1 vector mới với đúng 1 vector đã lưu của người đó, không quét toàn bộ cơ sở dữ liệu khuôn mặt để "tìm xem đây là ai".
- **Ngưỡng nhận diện (matching threshold)**: cosine similarity ≥ **0.55** (`AI_FACE_SIMILARITY_THRESHOLD`, mặc định, cấu hình được qua biến môi trường) — đây là 1 con số cấu hình thủ công dựa trên kinh nghiệm/tài liệu ArcFace, **chưa được hiệu chỉnh (calibrate) bằng thực nghiệm** trên tập dữ liệu người dùng thật của hệ thống.
- **Ngưỡng cho enrollment** (kiểm tra N ảnh trong 1 lượt đăng ký có cùng 1 người): 0.45 (`AI_ENROLL_SAME_PERSON_THRESHOLD`) — thấp hơn ngưỡng verify vì ảnh đăng ký cố ý chụp nhiều góc khác nhau.
- **Độ chính xác thực tế (FAR/FRR, FMR/FNMR, % accuracy)**: **CHƯA đo được**. Toàn bộ việc "test" trong quá trình phát triển (ghi trong `docs/api/face-id-management-api.md`) chỉ dùng **1 ảnh khuôn mặt thật duy nhất** làm fixture để kiểm chứng pipeline chạy đúng logic (VD: 1 ảnh cho "center" pass, ảnh tĩnh lặp lại cho "turn_left" fail đúng như kỳ vọng) — đây là **kiểm thử chức năng (functional test)**, không phải **đánh giá độ chính xác thống kê** trên tập dữ liệu đa dạng (nhiều người, nhiều điều kiện). Việc đo FMR/FNMR theo chuẩn ISO/IEC 30107-3 được chính tài liệu dự án ghi nhận là **"chưa triển khai, ngoài phạm vi"** — cần tập dữ liệu và quy trình kiểm thử riêng.

---

## 8. Khả năng nhận diện trong điều kiện khó (ánh sáng yếu, góc mặt, kính, khẩu trang, thay đổi ngoại hình)

| Điều kiện | Hiện trạng |
|---|---|
| **Góc mặt thay đổi** | Có xử lý chủ động — active-liveness yêu cầu quay trái/phải/nhìn lên/xuống, đo bằng pose 3D của InsightFace (chính xác hơn hẳn `solvePnP` cũ). Đã kiểm chứng bằng toán học (dựng lại phép chiếu 3D→2D với góc biết trước), nhưng **chưa test trên ảnh mặt nghiêng thật từ camera điện thoại** (chỉ có 1 ảnh chính diện làm fixture) |
| **Ánh sáng yếu** | **Không có xử lý đặc biệt** (không low-light enhancement, không hồng ngoại/IR camera) — độ chính xác dưới ánh sáng yếu phụ thuộc hoàn toàn vào độ bền vững sẵn có của model SCRFD/ArcFace gốc, **chưa được dự án tự kiểm chứng thực nghiệm** |
| **Đeo kính** | Không có xử lý/test riêng trong dự án. Về lý thuyết, ArcFace pretrained nhìn chung chịu được kính thường (theo benchmark công khai của model gốc), nhưng đây **chưa phải kết quả tự đo của dự án** |
| **Đeo khẩu trang** | **Không có xử lý riêng cho khuôn mặt bị che (occlusion)** — không dùng model chuyên nhận diện có khẩu trang. Nếu khẩu trang che phần lớn khuôn mặt, khả năng cao là detect thất bại hoặc embedding kém chính xác; hệ thống **không có cảnh báo tự động** kiểu "vui lòng tháo khẩu trang" — chỉ trả lỗi chung `no_face_detected` hoặc verify thất bại |
| **Thay đổi ngoại hình theo thời gian** (để tóc, tăng/giảm cân, phẫu thuật...) | Giải quyết ở **tầng nghiệp vụ, không phải AI**: nhân viên có thể **đăng ký lại (re-enroll)** bất kỳ lúc nào, hồ sơ mới chờ HR duyệt, khuôn mặt cũ vẫn dùng được cho tới khi duyệt xong |

---

## 9. Cơ chế chống giả mạo khuôn mặt (liveness / anti-spoofing)

**Có, 2 lớp độc lập, bổ sung cho nhau:**

1. **Active liveness (chủ động)** — chỉ áp dụng cho tự đăng ký và chấm công tại site bắt buộc Face ID:
   - Server random 1 chuỗi hành động (center + 2 hành động ngẫu nhiên trong turn_left/turn_right/look_up/look_down/blink) **mỗi lần khác nhau** — chặn việc quay sẵn 1 video rồi phát lại cho lần sau.
   - Kiểm tra hướng đầu thật (pose 3D) và nháy mắt thật (EAR trên landmark) — không phải phân loại ảnh học máy đen-hộp mà là đo lường hình học có thể giải thích được.
   - **Cùng 1 người xuyên suốt**: so cosine similarity giữa embedding của TẤT CẢ các khung trong chuỗi — chặn việc ghép ảnh của người khác vào giữa chừng.
2. **Passive anti-spoofing (thụ động)** — MiniFASNet, chạy trên khung `center`: phát hiện ảnh in, ảnh chụp lại từ màn hình (screen replay).

**Giới hạn đã biết** (tự ghi nhận trong tài liệu dự án, không giấu):
- Chưa test bằng mặt nạ 3D tinh vi hay deepfake video thật.
- Chưa có Play Integrity (Android)/App Attest (iOS) chống app giả/camera injection ở tầng thiết bị — thuộc phạm vi ứng dụng App, cần build production thật.
- Chưa đo hiệu quả PAD (Presentation Attack Detection) theo chuẩn ISO/IEC 30107-3 trên tập dữ liệu tấn công đa dạng.

---

## 10. Xử lý trên thiết bị, máy chủ nội bộ, hay cloud?

**Xử lý hoàn toàn tại server (self-hosted), không on-device, không dùng cloud AI bên thứ 3.**

- Thiết bị (App/Web) **chỉ đóng vai trò camera + giao diện hiển thị lệnh** — chụp ảnh JPEG rồi gửi thẳng lên server, không chạy model AI cục bộ nào trên máy khách.
- Toàn bộ suy luận (detect, embedding, liveness, so khớp) chạy trong container `fams-ai`, **CPU-only** (`onnxruntime` với `CPUExecutionProvider`, ép `CUDA_VISIBLE_DEVICES=-1` — không dùng GPU dù có sẵn phần cứng), cùng mạng Docker nội bộ với PostgreSQL/Java, không expose port ra ngoài host trong cấu hình full stack.
- Không gọi bất kỳ API AI bên thứ 3 nào qua Internet (không AWS/Azure/Google) — mọi dữ liệu sinh trắc học không rời khỏi hạ tầng tự quản của dự án. Đây là điểm thuận lợi cho việc kiểm soát dữ liệu/tuân thủ pháp lý, đổi lại phải tự quản lý toàn bộ hiệu năng/độ sẵn sàng của model thay vì dựa vào SLA nhà cung cấp cloud.
- Việc triển khai trên "máy chủ nội bộ" hay "VM cloud tự quản" (AWS EC2, GCP Compute...) đều dùng chung 1 kiến trúc Docker Compose hiện tại — không có khác biệt về code, chỉ khác nơi host container.

---

## 11. Thời gian trung bình cho một lần nhận diện và ghi nhận chấm công

**Không có số liệu benchmark chính thức đã đo đạc trong dự án** — không tìm thấy log thời gian xử lý, không có báo cáo latency, không có công cụ đo (profiler/APM) nào được cấu hình cho AI service.

Có thể tách 2 khái niệm khác nhau để trả lời chính xác câu hỏi:

- **"Ghi nhận chấm công" (thời điểm CheckinRecord được tạo)**: gần như **tức thời** (<1 giây) — vì bản ghi chấm công được lưu **đồng bộ** ngay khi client gửi request, **không chờ** AI xử lý xong.
- **"Có kết quả xác thực khuôn mặt"**: phụ thuộc độ trễ hàng đợi Redis + thời gian xử lý model, gồm: detect (SCRFD) + trích embedding (ArcFace) + (nếu cần) anti-spoofing (MiniFASNet) — **thời gian ước lượng định tính** cho 1 ảnh trên CPU thông thường (không có GPU) thường ở mức **vài trăm mili-giây tới 1-2 giây/ảnh**, nhưng đây là **ước lượng kỹ thuật hợp lý dựa trên đặc tính model, không phải số đo thực nghiệm của chính dự án này** — cần đo đạc thật trên phần cứng triển khai thực tế trước khi công bố con số chính thức.
- Vì **worker chỉ chạy 1 thread duy nhất, xử lý tuần tự** (`ai-service/app/worker.py`), khi nhiều người chấm công cùng lúc (giờ cao điểm), job dồn vào hàng đợi và được xử lý lần lượt — độ trễ "có kết quả" có thể **tăng tuyến tính theo số người chấm công đồng thời**, dù bản thân bản ghi chấm công không bị mất hay chậm.

**Khuyến nghị**: cần chạy benchmark thật (đo p50/p95/p99 thời gian xử lý 1 job, và thời gian hàng đợi dồn ứ ở các mức tải khác nhau) trước khi đưa ra cam kết SLA về tốc độ chấm công.

---

## 12. Xử lý khi nhận diện sai, không nhận diện được, hoặc nhiều khuôn mặt cùng lúc

| Tình huống | Xử lý |
|---|---|
| **Không phát hiện khuôn mặt** (`no_face_detected`) | Lúc enroll: trả lỗi `400` rõ ràng, chỉ đúng ảnh nào lỗi. Lúc chấm công (async): worker gán `faceVerified=false`, `errorCode="no_face_detected"` — **checkin vẫn được ghi nhận** (không mất dữ liệu chấm công), nếu site bắt buộc Face ID thì tự chuyển `pending_review` + tạo `Violation` để HR xem lại |
| **Nhiều khuôn mặt trong 1 ảnh** (`multiple_faces_detected`) | **Từ chối thẳng**, không tự động chọn khuôn mặt "to nhất"/"rõ nhất" — tránh rủi ro xác thực nhầm người khi có người đứng gần camera. Cùng luồng xử lý lỗi như trên (400 lúc enroll, escalate lúc checkin) |
| **Nhận diện sai / điểm so khớp dưới ngưỡng** | `faceVerified=false` — tại site bắt buộc Face ID: hạ checkin xuống `pending_review` + tạo `Violation` (loại `face_fail`/`liveness_fail`), HR xem ảnh đã chụp (lưu lại) để quyết định thủ công. Tại site không bắt buộc: không ảnh hưởng, checkin vẫn `valid` (ảnh chỉ mang tính tham khảo) |
| **Publish job lỗi** (Redis/mạng lỗi giữa chừng) | Checkin tại site bắt buộc Face ID **tự chuyển `pending_review`** thay vì âm thầm giữ `valid` — đây là 1 sửa lỗi có chủ đích, tránh "trôi qua" không xác thực |
| **Lệch chiều vector** (`embedding_dimension_mismatch` — hồ sơ cũ dlib 128-d so với model mới ArcFace 512-d) | Báo lỗi rõ ràng thay vì crash, `faceVerified=false`, escalate đúng quy trình — nhân viên cần đăng ký lại |
| **Lạm dụng/brute-force** (thử liveness challenge liên tục) | Rate-limit **5 lần/10 phút/nhân viên**, trả `429 TOO_MANY_ATTEMPTS` |

Nguyên tắc chung xuyên suốt: **không bao giờ tự động từ chối hẳn quyền chấm công hay tự động kết luận gian lận chỉ dựa trên AI** — mọi trường hợp thất bại đều rơi vào trạng thái "cần con người xem lại" (`pending_review`/`Violation`), giữ vai trò AI là **công cụ hỗ trợ quyết định**, không phải người quyết định cuối cùng.

---

## 13. Mã hóa, phân quyền truy cập, lưu trữ và bảo vệ dữ liệu sinh trắc học

### 13.1 Mã hóa

**Không có mã hóa cho dữ liệu sinh trắc học** — đây là điểm cần lưu ý nhất trong mục này:

- **Embedding**: lưu dạng `DOUBLE PRECISION[]` **plaintext** trực tiếp trong PostgreSQL (`face_profiles.embedding`, `V41__create_face_profiles.sql`) — không mã hóa ở tầng ứng dụng, không dùng pgcrypto hay cột mã hóa.
- **Ảnh gốc**: lưu file `.jpg` **plaintext** trên đĩa (`storage_service.py`), không mã hóa at-rest.
- **So sánh nội bộ**: trong cùng hệ thống, `TOTP secret` (mã xác thực 2 lớp) **đã được mã hóa AES-GCM** (`app.totp.encryption-key`, theo `docs/02-kien-truc-du-an.md` mục 7.1) — cho thấy đội dự án **có sẵn năng lực/hạ tầng mã hóa**, nhưng **chưa áp dụng cho dữ liệu sinh trắc học**, dù đây là loại dữ liệu cá nhân nhạy cảm nhất theo Nghị định 13/2023/NĐ-CP (Điều 11) và GDPR Art.9. Đây là **khoảng trống bảo mật đáng ưu tiên xử lý**.
- **Truyền tải**: giao tiếp Java ↔ AI hiện đi qua mạng Docker nội bộ (`fams-net`), bảo vệ bằng header bí mật dùng chung (`X-Internal-Secret`), không thấy cấu hình TLS nội bộ giữa 2 service — nếu triển khai với 2 service không cùng mạng riêng tin cậy (VD tách ra 2 host/cloud khác nhau), cần bổ sung TLS ngay.

### 13.2 Phân quyền truy cập (RBAC)

- Permission chuyên biệt **`face_id:manage`** — mặc định gán cho vai trò `HR_MANAGER`, `TENANT_ADMIN`, `PLATFORM_ADMIN` (`V41__create_face_profiles.sql`).
- **Nhân viên thường**: chỉ được tự thao tác trên hồ sơ **của chính mình** — tự cho consent (bắt buộc, không ai làm hộ được), tự đăng ký qua active-liveness, tự xem status/thu hồi. Không xem được hồ sơ Face ID của người khác.
- **HR/Admin** (`face_id:manage`): enroll hộ (kiosk), duyệt/từ chối hồ sơ, xem hàng đợi chờ duyệt, xem ảnh đại diện đang chờ duyệt — nhưng **không tự đồng ý consent hộ nhân viên**, và **không tự duyệt hồ sơ của chính mình**.
- **SITE_SUPERVISOR**: hàng đợi duyệt và báo cáo Face ID được lọc theo site-scope (chỉ thấy nhân viên thuộc site được giao), áp dụng nhất quán qua `SiteScopeService`.
- Mọi endpoint nội bộ giữa Java↔AI đều yêu cầu header `X-Internal-Secret` — client bên ngoài không thể gọi thẳng `fams-ai` (không expose port ra host trong cấu hình full stack).

### 13.3 Vòng đời lưu trữ và xóa dữ liệu (retention)

- **Ảnh checkin/liveness challenge**: tự động dọn sau **30 ngày** (mặc định, `DATA_RETENTION_BIOMETRIC_PHOTO_DAYS`, chạy bởi `DataRetentionJob` hàng tuần) — đây là giá trị khởi điểm hợp lý, **chưa xác nhận chính thức là yêu cầu pháp lý/consent cụ thể**.
- **Ảnh đăng ký (enrollment)**: **không** bị dọn theo tuổi — chỉ xóa khi hồ sơ bị **thu hồi (revoke)**, lúc đó xóa cả file ảnh lẫn set `embedding_deleted=true`.
- **Nhân viên nghỉ việc (`terminated`)**: **tự động thu hồi Face ID** (xóa embedding + ảnh) khi đổi trạng thái nhân viên — tránh giữ dữ liệu sinh trắc học vô thời hạn sau khi mục đích xử lý đã kết thúc. Chỉ áp dụng cho `terminated`, không áp dụng cho `inactive` (tạm nghỉ, có thể quay lại).
- **Thu hồi chủ động** (theo yêu cầu nhân viên hoặc HR): luôn thực hiện ngay, không cần qua bước duyệt — vì đây là quyền hợp pháp (rút lại consent) không nên bị trì hoãn.

### 13.4 Điểm chưa làm (tự ghi nhận trong tài liệu dự án, chưa triển khai)

- Consent hiện chỉ lưu boolean + timestamp — chưa lưu phiên bản chính sách/mục đích xử lý/nguồn ghi nhận chi tiết.
- Chưa có DPIA (Data Protection Impact Assessment)/đánh giá tác động chính thức.
- Chưa có quy trình chính thức cho yêu cầu truy cập/xóa dữ liệu theo yêu cầu của chủ thể dữ liệu (data subject access request).

---

## 14. Phương án mở rộng hệ thống AI và tối ưu tốc độ khi số lượng nhân viên/lần chấm công tăng

Vì `fams-ai` **đã được tách thành microservice độc lập** với Java API (không phải module trong monolith), đây là điểm thuận lợi có sẵn để scale riêng theo tải Face ID mà không ảnh hưởng phần còn lại của hệ thống. Đề xuất theo thứ tự ưu tiên:

1. **Scale ngang worker xử lý verify** — hiện chỉ 1 thread duy nhất tiêu thụ hàng đợi Redis (`fams:ai:face_verify_jobs`). Vì `BRPOP` hỗ trợ nhiều consumer an toàn, có thể chạy **nhiều container `fams-ai`** cùng đọc chung 1 hàng đợi ngay mà không cần đổi code — giải quyết trực tiếp nút cổ chai tuyến tính hiện tại.
2. **Tách lưu ảnh sang object storage dùng chung** (S3-compatible, giống cách avatar đã làm) — hiện ảnh lưu trên đĩa cục bộ container (`STORAGE_BASE_PATH`), là rào cản khi chạy nhiều instance `fams-ai` (mỗi container cần thấy chung 1 kho ảnh). Cần làm bước này **trước khi** scale ngang thật sự ở production.
3. **Cân nhắc dùng GPU** — hiện toàn bộ suy luận ép chạy CPU (`CUDA_VISIBLE_DEVICES=-1`). Với tải lớn (hàng nghìn lượt chấm công/giờ cao điểm), chuyển `onnxruntime` sang `CUDAExecutionProvider` trên máy có GPU sẽ giảm đáng kể thời gian xử lý mỗi ảnh so với CPU.
4. **Batch inference** — hiện mỗi job xử lý 1 ảnh riêng lẻ tuần tự; có thể gom nhiều job đang chờ trong hàng đợi thành 1 batch đưa vào model cùng lúc (SCRFD/ArcFace đều hỗ trợ batch), tăng thông lượng trên cùng phần cứng.
5. **Nâng cấp cơ chế hàng đợi** — thay Redis list thô bằng giải pháp có retry/DLQ/backpressure thật (Redis Streams hoặc broker chuyên dụng), tránh mất job khi worker lỗi giữa chừng, đồng thời cho phép giám sát độ sâu hàng đợi (queue depth) để biết khi nào cần scale thêm worker.
6. **Cache kết quả detect/embedding** không cần thiết cho luồng verify (mỗi ảnh chỉ dùng 1 lần), nhưng nên **cache warm-up model** (đã có sẵn — `lifespan` load model 1 lần lúc start) tiếp tục giữ khi scale nhiều instance, tránh mỗi container mất thời gian load `buffalo_l` (~280MB) lúc khởi động lại.
7. **Benchmark trước khi mở rộng** — vì hiện chưa có số liệu latency/throughput thực đo (mục 11), bước đầu tiên trước khi đầu tư hạ tầng (GPU, nhiều instance) nên là **đo tải thật** bằng dữ liệu đã có sẵn (`scripts/seed_perf.sql` — 23.302 nhân viên, 1,1 triệu checkin) để biết chính xác điểm nghẽn hiện tại nằm ở đâu trước khi tối ưu.

---

*Nguồn tham chiếu chính*: `ai-service/app/services/face_service.py`, `head_pose_service.py`, `liveness_service.py`, `storage_service.py`, `callback_service.py`, `ai-service/app/worker.py`, `ai-service/app/routers/enroll.py`, `liveness_challenge.py`, `checkin_photo.py`, `status.py`, `ai-service/app/config.py`, `ai-service/requirements.txt`, `ai-service/Dockerfile`, `api-server/src/main/java/com/fams/modules/employee/service/FaceIdService.java`, `api-server/src/main/java/com/fams/shared/ai/AiServiceClient.java`, `api-server/src/main/resources/db/migration/V41__create_face_profiles.sql`, `docs/api/face-id-management-api.md`, `docs/02-kien-truc-du-an.md`.
