# Tài liệu tích hợp Face ID — Review, sửa lỗi, bổ sung và API tham chiếu

> Cập nhật theo code đang chạy ngày 30/07/2026. Base path: `/api/v1/tenants/{tenantId}/employees/{employeeId}/face-id` (theo nhân viên) và `/api/v1/tenants/{tenantId}/face-id` (hàng đợi duyệt của HR).

## 0.00 [MỚI] 03/09/2026 — sửa lỗi "quay đầu ngược chiều mới được chấp nhận" (QA thiết bị thật)

### Vấn đề người dùng báo

Khi check-in/check-out bằng Face ID liveness với **camera trước**: hệ thống yêu cầu "quay đầu sang phải" nhưng người dùng phải quay sang **trái** thì mới được nhận; quay đúng theo hướng dẫn thì bị báo sai chiều (`detected ['turn_left']`). Chiều nào cũng bị đảo.

### Nguyên nhân gốc

`ai-service/app/services/head_pose_service.py::classify_frame()` ánh xạ **sai dấu** của góc yaw:
code cũ giả định `yaw > 0` ⇒ `turn_right`. Nhưng khung hình được chụp từ camera selfie **không lật gương** (`FaceLivenessCamera` đặt `mirror={false}`), tức ảnh có chiều như người đối diện nhìn thấy. Ở chiều đó InsightFace trả **yaw ÂM khi người dùng quay sang phải của chính họ** và **yaw DƯƠNG khi quay sang trái** — ngược với giả định của code. Hệ quả: mọi bước `turn_left`/`turn_right` đều từ chối động tác đúng và chấp nhận động tác ngược.

### Đã sửa

1. **`classify_frame()`** — đảo lại nhánh phân loại yaw: `yaw_delta <= -YAW_THRESHOLD ⇒ turn_right`, `yaw_delta >= YAW_THRESHOLD ⇒ turn_left`. Pitch (`look_up`/`look_down`) và `center` không đổi (đối xứng). Không đụng baseline theo phiên (mục 0.0) — vẫn so delta với khung `center`.
2. **Docstring `estimate_head_pose()`** — ghi rõ quy ước dấu yaw đã kiểm chứng trên thiết bị thật.
3. **App `FaceLivenessCamera.tsx`** — mô tả bước quay đầu nói rõ "về phía vai TRÁI/PHẢI của chính bạn" để giảm nhầm lẫn (preview không lật gương).

### Test đã chạy

- `ai-service/tests/test_head_pose_service.py` (mới, 5 test, chạy trong container `fams-ai`): frame yaw âm ⇒ `turn_right` & không `turn_left`; yaw dương ⇒ `turn_left` & không `turn_right`; frontal ⇒ `center`; baseline lệch trục được tôn trọng; pitch không đổi. Toàn bộ suite ai-service: 7/7 pass, không hồi quy.
- App: `tsc --noEmit` sạch.

## 0.0 [MỚI] P0: sửa lỗi hệ thống "center bị nhận nhầm thành look_down" trên thiết bị thật

Bạn gửi đề xuất nâng cấp Active Liveness V2 (`20_DE_XUAT_NANG_CAP_FACE_ID_LIVENESS_V2_2026-07-30.md`) sau khi test 4/4 lần trên camera điện thoại thật đều bị lỗi `expected 'center', detected ['look_down']`. Đã xác minh chẩn đoán trong tài liệu là đúng, sửa ngay phần P0 (an toàn, không cần kiến trúc mới), phần V2 (video liên tục + MediaPipe) trình bày ở mục 0.1 dưới đây để bạn quyết định vì đây là dự án nhiều tuần, đụng tới cả `fams-front-app-project` lẫn thêm dependency mới cho AI service.

### Nguyên nhân gốc

Pipeline cũ dùng **1 ảnh tĩnh cho mỗi hành động**, phân loại `center`/`look_up`/`look_down` bằng cách so **pitch tuyệt đối** (từ `solvePnP`) với ngưỡng cố định quanh 0°. Trên thực tế, camera trước điện thoại hầu như không bao giờ được cầm đúng ngang tầm mắt — người dùng thường cầm điện thoại thấp hơn mặt, khiến ngay cả một khung hình "nhìn thẳng vào camera" có ý định đúng đắn vẫn cho ra pitch âm đáng kể theo phép đo `solvePnP`. Ngưỡng tuyệt đối coi đây là `look_down` một cách có hệ thống — đúng như 4/4 lần test thật của bạn cho thấy.

### Đã sửa (an toàn, tương thích ngược)

1. **Baseline theo phiên thay vì giả định (0°, 0°)** — `ai-service/app/services/head_pose_service.py` thêm `estimate_baseline_pose()`: đo pitch/yaw của chính khung hình `center` trong challenge đó, dùng làm điểm gốc (không phải 0 tuyệt đối). Mọi hành động sau đó (`turn_left`/`turn_right`/`blink`) được so **lệch (delta)** với baseline này, không so tuyệt đối nữa. `classify_frame()` nhận thêm `baseline_pitch`/`baseline_yaw` (mặc định `0.0` — giữ nguyên hành vi cũ cho caller nào chưa truyền).
2. **Bound hợp lý cho baseline** — `CENTER_BASELINE_SANITY_DEG=45°`: chỉ từ chối baseline rõ ràng phi lý (chụp nghiêng gần như quay mặt đi), không đụng tới độ lệch tự nhiên vài-đến-nhiều-độ khi cầm điện thoại thấp. Nếu không đo được baseline hợp lý → fallback về (0°, 0°) — đúng hành vi CŨ, không bao giờ tệ hơn trước.
3. **Tạm bỏ `look_up`/`look_down` khỏi action pool** — `ai-service/app/routers/liveness_challenge.py`, `_ACTION_POOL` giờ chỉ còn `["turn_left", "turn_right", "blink"]`. Đúng theo đề xuất P0 của bạn: 2 hành động dựa vào pitch cần QA lại trên thiết bị thật (nhiều máy, nhiều góc cầm) trước khi bật lại — môi trường agent này không có camera thật để tự QA, nên không tự ý bật lại.
4. **`submit_frames` tính baseline TRƯỚC khi phân loại bất kỳ khung nào** — đọc hết bytes các khung trước, tìm khung `center` (theo `actions.index("center")`, không giả định vị trí cố định), tính baseline từ đó, rồi mới chạy vòng lặp phân loại chính với baseline đã có.

### Test đã chạy — và giới hạn thành thật

- **Test sống qua API thật**: start challenge `purpose=enroll` → xác nhận `actions` trả về không còn `look_up`/`look_down` (chỉ còn tổ hợp từ `turn_left`/`turn_right`/`blink`). Nộp cùng 1 ảnh tĩnh cho 3 hành động (test hồi quy giống các lần trước) → `center` vẫn đúng đắn pass, `turn_left`/`blink` vẫn đúng đắn fail (ảnh tĩnh không thể tạo chuyển động thật) — xác nhận **không có hồi quy** so với hành vi trước khi sửa.
- **Giới hạn — chưa thể tự kiểm chứng phần quan trọng nhất**: môi trường agent này KHÔNG có camera/thiết bị thật, chỉ có 1 ảnh khuôn mặt tĩnh làm fixture — không thể tự tái hiện chính xác lỗi gốc (một ảnh chụp thật từ camera điện thoại cầm thấp hơn mặt) để chứng minh trực tiếp rằng baseline mới KHẮC PHỤC được lỗi `center→look_down`. Việc sửa dựa trên chẩn đoán kỹ thuật đúng đắn (bias hệ thống từ góc cầm máy, không phải lỗi ngẫu nhiên) và toán học rõ ràng (trừ theo baseline thay vì so tuyệt đối phải loại bỏ đúng loại bias này), nhưng **cần đội QA xác nhận lại trên thiết bị thật** trước khi coi là đã khắc phục hoàn toàn — đúng khuyến nghị trong chính đề xuất của bạn.

### 0.0b [MỚI] Đã thử thay thuật toán đo góc bằng MediaPipe — KHÔNG thành công, đã rollback

Sau khi bạn xác nhận muốn thử "giữ active liveness nhưng thay bằng thư viện đo góc tốt hơn (MediaPipe)" thay vì đơn giản hóa nghiệp vụ, đã thử triển khai — nhưng gặp 2 trở ngại thực tế nghiêm trọng, buộc phải **rollback về đúng trạng thái P0** (baseline theo phiên trên dlib, mục 0.0) để không để hệ thống ở trạng thái hỏng:

1. **Băng thông mạng của môi trường build cực kỳ chậm** (~48KB/s đo được thực tế tới PyPI) — tải các gói phụ thuộc cần thiết (riêng `opencv-contrib-python` ~90MB đã mất hơn nửa tiếng không xong) khiến việc cài đặt không khả thi trong thời gian hợp lý của 1 phiên làm việc.
2. **Phiên bản `mediapipe` tương thích với stack hiện tại (protobuf/tensorflow đã cài) là `1.0.0`** — bản này đã **loại bỏ hoàn toàn API `mediapipe.solutions.face_mesh`** (API cũ, đơn giản, dùng để lấy landmark từ 1 ảnh tĩnh) mà tôi định dùng, chỉ còn API Tasks mới hơn (`FaceLandmarker`) — API này cần tải riêng 1 file model `.task` từ máy chủ Google (thêm 1 lần tải file lớn nữa qua đúng đường truyền chậm ở trên). Các phiên bản `mediapipe` cũ hơn (0.10.x, còn giữ `solutions` API) lại đòi `protobuf<4`, xung đột trực tiếp với `protobuf` bản mới đã có sẵn trong môi trường — quay lại đúng vấn đề ban đầu khiến lần thử đầu tiên bị treo hơn 50 phút.

**Đã làm để không để lại hậu quả**: revert sạch `head_pose_service.py`, `liveness_challenge.py`, `requirements.txt` về đúng trạng thái mục 0.0 (dlib + baseline theo phiên, KHÔNG có MediaPipe) — build lại, test sống xác nhận `center` vẫn đúng, action pool vẫn không có `look_up`/`look_down`, không hồi quy.

**Ý nghĩa cho quyết định tiếp theo**: hướng "giữ nguyên kiến trúc, chỉ thay thư viện đo góc" không khả thi ở MÔI TRƯỜNG AGENT NÀY (giới hạn hạ tầng, không phải giới hạn kỹ thuật của chính giải pháp) — nhưng **hoàn toàn có thể khả thi trong môi trường build thật của bạn** (băng thông bình thường). Nếu muốn theo hướng này, cần một trong hai:
- Dùng `mediapipe==1.0.0` + API Tasks mới (`FaceLandmarker` + file `.task`) — cần viết lại phần trích landmark theo API mới, không phải chỉ đổi tên hàm.
- Hoặc ép môi trường xuống `protobuf<4`/`tensorflow` bản cũ hơn để dùng được `mediapipe` 0.10.x với `solutions` API — rủi ro: có thể ảnh hưởng tới `deepface`/`tensorflow` đang dùng cho passive anti-spoofing (MiniFASNet), cần test lại toàn bộ.

Vì cả 2 hướng đều cần tài nguyên (băng thông, thời gian build/test) mà môi trường agent không đáp ứng được, khuyến nghị: nếu muốn tiếp tục hướng MediaPipe, nên thực hiện trên máy/CI của bạn (có mạng nhanh hơn) theo đúng 2 lựa chọn kỹ thuật nêu trên — tôi có thể viết code cho phương án nào bạn chọn, chỉ không tự build/test được ở đây.

## 0.0c [MỚI — ĐÃ TRIỂN KHAI] Chuyển toàn bộ pipeline sang InsightFace (SCRFD + ArcFace + 106pt landmarks)

Bạn đề xuất kiến trúc mới thay hẳn nền tảng nhận diện: `InsightFace Detect → ArcFace Embedding → MiniFASNet → 106 Landmark → Blink → Head Turn → Cosine Similarity → Checkin`. Đã kiểm tra phù hợp với đúng nghiệp vụ hiện có (không đổi API, không đổi luồng consent/HR duyệt/policy 3 tầng) và **đã triển khai**, khác với đề xuất V2 (video+MediaPipe) ở mục 0.1 bên dưới — đây là thay đổi *nền tảng nhận diện*, không phải thay đổi *kiến trúc giao thức/API*.

### Thay thế những gì

| Thành phần | Trước (dlib) | Sau (InsightFace `buffalo_l`) |
|---|---|---|
| Phát hiện khuôn mặt | `face_recognition` (dlib HOG) | SCRFD (`det_10g.onnx`) |
| Embedding nhận diện | dlib ResNet-34, 128 chiều | ArcFace (`w600k_r50.onnx`), **512 chiều** |
| Landmark | dlib 68 điểm | 106 điểm (2D) + 68 điểm (3D) |
| Đo góc đầu | OpenCV `solvePnP` + mô hình 3D chung cho mọi người | Biến đổi tương đồng 3D (Umeyama/Procrustes) so với khuôn mặt trung bình chuẩn — tích hợp sẵn trong thư viện, không tự viết `solvePnP` nữa |
| Nháy mắt | EAR trên 6 điểm mắt dlib | Tỷ lệ cao/rộng của cụm 10 điểm contour mắt (106pt) — cùng nguyên lý EAR, chịu được việc chưa xác định chính xác thứ tự điểm mí trên/dưới |
| Chống ảnh giả (passive PAD) | MiniFASNet (DeepFace) | **Không đổi** — vẫn MiniFASNet, chạy độc lập trên bytes ảnh, không phụ thuộc detector nào |

### Vì sao đây là bản sửa đúng gốc rễ, không phải vá thêm

Nguyên nhân gốc của lỗi `center` bị nhận nhầm `look_down` (bạn phát hiện qua test thật) là `solvePnP` dùng 1 mô hình khuôn mặt 3D **chung** cho mọi người — không tính đến khác biệt hình học khuôn mặt thật giữa các cá nhân, cũng không tính đến góc cầm điện thoại tự nhiên (luôn thấp hơn mắt). Bản vá P0 (baseline theo phiên) chỉ giảm nhẹ triệu chứng bằng cách so lệch tương đối thay vì tuyệt đối — vẫn dùng đúng thuật toán yếu bên dưới. InsightFace's landmark_3d_68 dùng mạng neural đã huấn luyện chuyên biệt để định vị 68 điểm 3D chính xác hơn nhiều so với suy luận hình học từ 6 điểm, rồi tính góc bằng phép biến đổi tương đồng so với khuôn mặt trung bình chuẩn (kỹ thuật kinh điển trong tài liệu học thuật về căn chỉnh khuôn mặt, không phải công nghệ thử nghiệm). Baseline theo phiên (mục 0.0) vẫn được **giữ lại** làm lớp bảo vệ bổ sung.

**Xác nhận số học trên fixture test (ảnh chính diện thật)**: pose đo được `pitch=2.78°, yaw=-4.73°, roll=0.27°` — rất gần 0 đúng như kỳ vọng cho ảnh chính diện, khác hẳn độ lệch lớn có hệ thống của pipeline dlib cũ.

### ⚠️ Thay đổi phá vỡ tương thích ngược — CẦN LƯU Ý khi triển khai thật

**Mọi nhân viên đã đăng ký Face ID trước đây (embedding 128 chiều dlib) đều KHÔNG so khớp được với embedding mới (512 chiều ArcFace)** — 2 không gian vector hoàn toàn khác nhau, không thể so sánh trực tiếp. Đã thêm lớp bảo vệ để không làm sập worker khi gặp trường hợp này (`cosine_similarity` phát hiện lệch chiều dài vector → báo lỗi rõ ràng `embedding_dimension_mismatch` thay vì crash), nhưng **về nghiệp vụ, mọi nhân viên đã enrolled trước đợt deploy này đều cần đăng ký lại Face ID**. Đã test sống trực tiếp: giả lập 1 embedding 128 chiều cũ, gọi checkout → kết quả sạch sẽ `checkoutFaceVerified=false`, escalate đúng sang `pending_review`, không có exception nào bị nuốt.

**Khuyến nghị khi go-live thật**: thông báo trước cho toàn bộ nhân viên đã đăng ký Face ID rằng cần đăng ký lại 1 lần sau đợt nâng cấp này — không có cách nào tự động "nâng cấp" embedding cũ lên không gian vector mới vì bản chất 2 mô hình học ra biểu diễn khác nhau hoàn toàn.

### Đã khôi phục `look_up`/`look_down` vào action pool

Vì nguyên nhân gốc (thuật toán đo góc yếu) đã được sửa tận gốc chứ không chỉ vá triệu chứng, action pool quay lại đủ 5 hành động (`turn_left`/`turn_right`/`look_up`/`look_down`/`blink`) thay vì tạm bớt 2 hành động như ở P0.

### Test đã chạy — và giới hạn thành thật (không đổi so với các đợt trước)

- **Test sống đầy đủ qua API thật, không mock**: đăng ký Face ID (ảnh tĩnh) → embedding 512 chiều lưu đúng → HR duyệt → check-in với đúng ảnh đã đăng ký → worker xác thực bất đồng bộ trả `faceVerified=true, score=1.0` (khớp hoàn hảo, đúng kỳ vọng vì cùng 1 ảnh) → check-out cũng qua được luồng tương tự.
- Test challenge liveness: `actions` trả về đủ cả `look_up`/`look_down` (đã khôi phục), `center` tiếp tục được nhận đúng khi nộp lại đúng 1 ảnh tĩnh cho 3 hành động (không hồi quy so với hành vi trước).
- Test riêng dimension-mismatch: xác nhận không crash, escalate đúng.
- Hồi quy `tests/checkin/test_basic_checkin.sh` (11/11 pass, không liên quan Face ID nhưng dùng chung service) và các suite `tests/face-id/*.sh` khác — các lỗi còn lại đều là lỗi kịch bản test có từ trước (field `email`/`identifier`, thứ tự test tự-consent), không phải hồi quy từ thay đổi này.
- **Giới hạn còn nguyên**: vẫn KHÔNG có camera thật trong môi trường agent — chỉ verify được bằng 1 ảnh tĩnh cố định. Việc quay đầu/nháy mắt thật trên tay người dùng **vẫn cần bạn tự test trên điện thoại thật** để xác nhận cuối cùng, dù xác suất thành công giờ cao hơn nhiều nhờ thuật toán đo góc chính xác hơn hẳn.

### Việc KHÔNG tự triển khai — cần bạn quyết định (mục 0.1)

## 0.1 Đề xuất Active Liveness V2 (video liên tục + MediaPipe) — cần quyết định trước khi làm

Phần P0 ở mục 0.0 là fix an toàn, đúng, đã kiểm chứng logic — nhưng đây chỉ là "giảm đau" trên kiến trúc hiện tại (1 ảnh/hành động, ngưỡng heuristic). Đề xuất V2 trong tài liệu bạn gửi đúng hướng về mặt kỹ thuật (video liên tục, MediaPipe Face Landmarker, baseline đa khung, PAD đa khung, kiểm tra danh tính xuyên suốt, state machine `created→evidence_uploaded→processing→passed/failed/expired`) nhưng là **một dự án kiến trúc nhiều tuần**, không phải một đợt sửa lỗi:

- Đụng tới **2 repo** (`fams-backend-project` + `fams-front-app-project`) — App cần chuyển từ Expo Go sang Development Build, tích hợp ML Kit native, quay video liên tục thay vì chụp ảnh.
- Thêm **dependency mới** cho AI service (MediaPipe Face Landmarker) — cần đánh giá tương thích, hiệu năng, kích thước image.
- Cần **hạ tầng xử lý bất đồng bộ mới**: upload video (endpoint `202 Accepted` + poll kết quả), decode video, trích frame, giới hạn dung lượng/thời lượng, dọn dẹp evidence theo retention.
- Cần **bộ test nghiệm thu bằng thiết bị thật** (mục 12 trong đề xuất của bạn: ít nhất 3 Android + 2 iPhone, nhiều điều kiện ánh sáng/góc camera/nhân khẩu học) — **môi trường agent này không có khả năng tự thực hiện**, chỉ backend/AI code có thể viết trước, không thể tự QA đạt chuẩn "90% pass lần đầu, 95% trong 2 lần" mà đề xuất đặt ra.

**Khuyến nghị**: triển khai đúng theo lộ trình giảm rủi ro đã đề xuất trong tài liệu của bạn (mục 13) — P0 (đã làm ở mục 0.0) → P1 (V2 đầy đủ, chạy shadow mode song song V1 trước khi quyết định nghiệp vụ) → P2 (bật theo feature flag từng tenant, hardening). Trước khi tôi bắt đầu viết code cho P1 (API V2, MediaPipe, state machine), cần bạn xác nhận:

1. **Có triển khai V2 ngay hay dừng ở P0 trước, đo hiệu quả P0 trên thiết bị thật rồi mới quyết định có cần V2 không?** (P0 có thể đã đủ tốt — nguyên nhân gốc bạn tự chẩn đoán là bias baseline, chính là thứ P0 sửa trực tiếp; look_up/look_down tạm bỏ thì tổn thất tính năng không lớn).
2. **Nếu làm V2**: có chấp nhận rủi ro thêm MediaPipe làm dependency mới (kích thước, license, bảo trì) hay ưu tiên giải pháp khác?
3. **Ai/khi nào thực hiện bộ test thiết bị thật ở mục 12** — cần trước khi bật production, không thể agent tự làm.

Toàn bộ chi tiết kỹ thuật (giao thức API V2, thuật toán AI, state machine, mã lỗi chuẩn hóa, kế hoạch test) đã có sẵn, chất lượng tốt, trong tài liệu bạn gửi — khi có quyết định, dùng thẳng tài liệu đó làm spec triển khai.

Cả 2 team (Web: `17_BAO_CAO_FACE_ID_UI_2026-07-28.md`, App: `18_BAO_CAO_APP_FACE_ID_ACTIVE_LIVENESS_2026-07-28.md`) tích hợp xong theo tài liệu mục 0.1 và báo lại — đã xác minh từng điểm với code thật, tất cả đều đúng, đã sửa:

| # | Vấn đề | Team báo | Đã sửa |
|---|---|---|---|
| P0-1 | `getStatus()` không có kiểm tra tự xem — nhân viên thường (không có `face_id:manage`/`employees:read`) bị `403` khi xem chính Face ID của mình, dù Controller Javadoc/App đều giả định xem được | App | Thêm check `employee.userId == callerUserId`, cùng pattern với các action khác |
| P0-2 | `available-sites` không trả `requireFaceIdCheckin` trong `SiteInfo` — App phải submit mù, nhận `422` rồi mới biết cần mở camera, thay vì biết trước | App | Thêm field, map từ `site.isRequireFaceIdCheckin()` |
| P0-3 | Challenge check-in: không kiểm tra hết hạn/độ mới lúc tiêu thụ, không gắn với site cụ thể, không atomic (2 request đồng thời có thể cùng tiêu thụ 1 challenge), publish job lỗi thì âm thầm để checkin `valid` | App | 4 sửa riêng — xem mục 0.3 |
| P0-4 | Hàng đợi duyệt (`pending-review`) không có cách xem ảnh — HR "duyệt mù" | Cả 2 team | Endpoint mới `GET .../face-id/pending-review/photo` |
| P0-5 | Có thể bắt đầu/nộp frame challenge `purpose=enroll` trước khi có consent — server đã xử lý ảnh sinh trắc học trước khi xác nhận đồng ý | App | Chặn ngay lúc `POST .../liveness-challenge` nếu chưa consent (purpose=enroll) |
| P1 | Rate-limit số lần thử liveness | App | Tối đa 5 lần/10 phút mỗi nhân viên, lỗi `429 TOO_MANY_ATTEMPTS` |
| P1 | Swagger mô tả `/consent` sai — nói HR gọi hộ được, code (đã sửa từ mục 0.1) chỉ cho tự làm | Web | Cập nhật lại mô tả |

### 0.3 Chi tiết 4 sửa cho challenge check-in (P0-3)

1. **Gắn với site**: `POST .../liveness-challenge` giờ nhận thêm `siteId` (bắt buộc khi `purpose=checkin`) — `submitCheckin` chỉ chấp nhận challenge có đúng `siteId` khớp với site đang chấm công. Migration `V76` thêm cột `liveness_challenges.site_id`.
2. **Kiểm tra độ mới lúc tiêu thụ**: challenge phải hoàn tất (`completedAt`) trong vòng 2 phút trước khi submit checkin — không chỉ dựa vào `status=passed` (có thể "passed" từ rất lâu rồi mới đem ra dùng).
3. **Atomic consume**: đổi từ đọc-rồi-ghi sang 1 câu `UPDATE ... WHERE status='passed'` duy nhất (`LivenessChallengeRepository.consumeIfPassed`) — 2 request dùng cùng `challengeId` gần như đồng thời, chỉ 1 request thắng, request còn lại nhận lỗi rõ ràng thay vì cả 2 cùng "thành công".
4. **Publish job lỗi không còn bị bỏ qua**: nếu không gửi được job xác thực khuôn mặt tới AI worker (lỗi mạng/Redis), checkin tại site bắt buộc Face ID giờ tự chuyển sang `pending_review` thay vì âm thầm giữ `valid` — không phải violation (chưa chứng minh được sai, chỉ là không xác minh được), nhưng HR sẽ thấy để xem lại.

### 0.4 Endpoint mới: xem ảnh đang chờ duyệt

`GET /api/v1/tenants/{tenantId}/employees/{employeeId}/face-id/pending-review/photo` — trả về JPEG của ảnh đại diện cho lượt nộp đang `pending`, yêu cầu quyền `face_id:manage` + đúng site-scope (SITE_SUPERVISOR chỉ xem được nhân viên thuộc site của mình). Nguồn ảnh:
- Nếu đăng ký qua active liveness → ảnh `center` của challenge đã pass.
- Nếu HR enroll trực tiếp (ảnh tĩnh, kiosk) → ảnh đầu tiên trong lô đã tải lên.

Ảnh được xóa (`pending_photo_path = NULL`) ngay khi duyệt hoặc từ chối — không lưu thêm ngoài vòng đời của 1 lượt đang chờ xử lý.

### 0.5 Việc CHƯA làm — ghi nhận nhưng ngoài phạm vi đợt này

Cả 2 báo cáo có thêm loạt đề xuất P1 hợp lý về mặt kỹ thuật/pháp lý nhưng cần quyết định tổ chức hoặc hạ tầng ngoài phạm vi 1 đợt sửa code, **chưa triển khai**:
- Consent lưu thêm `noticeVersion`/policy URL/mục đích/nguồn ghi nhận (hiện chỉ có boolean + timestamp) — cần input về nội dung chính sách thật từ đội pháp lý.
- DPIA/đánh giá tác động, lịch retention chính thức, quy trình yêu cầu truy cập/xoá dữ liệu — quy trình tổ chức, không phải thay đổi code.
- Play Integrity (Android) / App Attest (iOS) chống app giả/camera injection — thuộc phạm vi App, cần build production thật (không chạy được qua Expo Go).
- Đo FMR/FNMR và kiểm thử PAD độc lập theo ISO/IEC 30107-3 trên tập dữ liệu đa dạng thực tế — cần tập dữ liệu và quy trình kiểm thử riêng, môi trường hiện tại chỉ có 1 ảnh mặt thật để test (đã nêu ở mục 0.1).
- Chuyển từ 3 ảnh rời sang phiên video liên tục — thay đổi kiến trúc lớn, cân nhắc sau khi có dữ liệu thực tế về tỷ lệ gian lận với thiết kế hiện tại.
- Phương án chấm công thay thế cho người từ chối/không dùng được sinh trắc học (rate-limit khi thất bại lặp lại đã làm; phương án thủ công/manual review thay thế thì chưa).

## 0.1 [MỚI] Active liveness (quay đầu/nháy mắt theo lệnh) — backend + AI service

Bạn hỏi cơ chế chống giả mạo hiện tại có phải dạng "quay các góc mặt (trực diện/trái/phải/trên/dưới), nháy mắt theo yêu cầu" hay không — câu trả lời trước đó là **chưa**, hệ thống chỉ có "passive liveness" (phân tích 1 ảnh tĩnh). Đã nâng cấp lên **active liveness** đầy đủ theo đúng lựa chọn của bạn, phạm vi backend + AI service trước (chưa đụng vào UI camera của App — xem mục "Việc còn lại cho App" cuối phần này).

### Cơ chế mới

1. **Bắt đầu 1 challenge**: `POST .../face-id/liveness-challenge?purpose=enroll|checkin` → server random 1 chuỗi hành động, luôn bắt đầu bằng `center` (ảnh chính diện, dùng làm ảnh tham chiếu) + 2 hành động ngẫu nhiên trong `{turn_left, turn_right, look_up, look_down, blink}`. Random mỗi lần để không thể quay sẵn 1 video rồi phát lại cho lần sau.
2. **Nộp ảnh theo đúng thứ tự**: `POST .../liveness-challenge/{challengeId}/frames` — client chụp đúng 1 ảnh cho mỗi hành động, gửi theo đúng thứ tự. Server (fams-ai) kiểm tra:
   - **Hướng đầu** (turn/look): ước lượng góc quay đầu bằng `cv2.solvePnP` (thuật toán chuẩn dùng trong nhận diện tư thế khuôn mặt — 6 điểm mốc: mũi, cằm, 2 khóe mắt, 2 khóe miệng, đối chiếu với mô hình mặt 3D chuẩn) — KHÔNG phải phân loại ảnh học máy đen-hộp, mà là hình học đo lường được, dễ giải thích và kiểm chứng.
   - **Nháy mắt**: Eye Aspect Ratio (EAR) — công thức chuẩn trong nghiên cứu phát hiện nháy mắt (Soukupová & Čech, 2016), tỷ lệ khoảng cách các điểm mốc quanh mắt giảm mạnh khi nhắm mắt.
   - **Cùng 1 người xuyên suốt**: so khớp cosine similarity giữa embedding của TẤT CẢ các ảnh trong chuỗi — chặn việc ghép ảnh nháy mắt/quay đầu của người khác vào.
   - **Chống ảnh in/màn hình**: chạy lại MiniFASNet (như trước) trên ảnh `center` — lớp phòng thủ thứ 2, phòng trường hợp kẻ gian dùng mặt nạ 3D tinh vi vẫn "quay đầu" đúng nhưng texture vẫn lộ là giả.
   - Trả `status: passed|failed` kèm chi tiết từng bước (`steps`) — ví dụ ảnh nào không đạt hành động nào, để hiển thị lỗi rõ ràng cho người dùng.
3. **Dùng kết quả đã pass**:
   - Đăng ký: `POST .../face-id/enroll/from-challenge?challengeId=...` (thay cho `POST .../enroll` khi tự đăng ký) — cùng luồng chờ HR duyệt như trước, chỉ khác nguồn ảnh.
   - Chấm công: `SubmitCheckinRequest` có thêm field `livenessChallengeId` — **bắt buộc thay cho `employeePhotoBase64`** tại site có `requireFaceIdCheckin=true`.

### Quyết định thiết kế quan trọng

- **Tự đăng ký (self-service) giờ BẮT BUỘC qua active liveness** — gọi thẳng `POST .../enroll` (ảnh tĩnh) khi là chính chủ sẽ bị chặn `400`. **HR hỗ trợ đăng ký tại kiosk** (có mặt giám sát trực tiếp — bản thân sự có mặt của con người đã là 1 lớp chống gian lận) vẫn được dùng `POST .../enroll` (ảnh tĩnh) như cũ, không bắt buộc active liveness — vì có người giám sát trực tiếp việc chụp.
- **Chấm công tại site KHÔNG bắt buộc Face ID**: giữ nguyên hành vi cũ hoàn toàn (ảnh tĩnh optional qua `employeePhotoBase64`), không có gì đổi — chỉ site có `requireFaceIdCheckin=true` mới bắt buộc `livenessChallengeId`.
- **Không tái sử dụng challenge**: mỗi challenge chỉ dùng được 1 lần (`status` chuyển `consumed` ngay khi tiêu thụ) — chặn việc dùng lại 1 kết quả pass cho nhiều lần đăng ký/chấm công.

### Test đã chạy — và giới hạn thành thật của việc test

- **Test sống qua API thật**: toàn bộ luồng start challenge → nộp ảnh → enroll-from-challenge → HR duyệt → chấm công với `livenessChallengeId` → worker AI khớp đúng embedding → checkin `valid`. Test cả trường hợp **nộp cùng 1 ảnh tĩnh cho 3 hành động khác nhau** — hệ thống đúng đắn từ chối 2/3 bước (chỉ `center` đạt), chứng minh cơ chế chặn được kiểu gian lận "gửi lại đúng 1 ảnh cũ" phổ biến nhất.
- **Đã kiểm chứng toán học của thuật toán đo góc đầu độc lập**: dựng lại phép chiếu 3D→2D với góc quay đã biết trước (0°, ±10°, ±20°, 30°), đưa qua đúng pipeline `solvePnP` + trích Euler angle, xác nhận góc phục hồi khớp chính xác góc đưa vào — đồng thời phát hiện và sửa 1 lỗi số học thật (ambiguity dấu trong `cv2.decomposeProjectionMatrix`, khiến ảnh chính diện ban đầu bị tính sai thành pitch≈-171°/roll≈-180° thay vì gần 0°).
- **Giới hạn**: môi trường test chỉ có 1 ảnh khuôn mặt thật (chân dung chính diện, dùng cho toàn bộ các test khác trong tài liệu này) — **không có ảnh mặt quay các góc thật** để test độc lập từng ngưỡng `turn_left/turn_right/look_up/look_down` qua đúng pipeline nhận diện mốc khuôn mặt của `face_recognition` trên ảnh camera thật. Đã bù bằng cách kiểm chứng toán học thuần túy (mục trên) — nhưng khuyến nghị **đội QA validate lại ngưỡng góc (`YAW_THRESHOLD_DEG=15°`, `PITCH_THRESHOLD_DEG=12°` trong `ai-service/app/services/head_pose_service.py`) bằng ảnh chụp thật từ camera điện thoại thật** trước khi đưa vào dùng chính thức — góc quay đầu "vừa đủ" để qua ngưỡng có thể khác giữa camera trước điện thoại (góc rộng, biến dạng nhiều ở rìa) và ảnh test tĩnh.

### Việc còn lại cho App (fams-front-app-project) — chưa làm trong lượt này

- Màn hình quay: hiện lệnh theo `actions` trả về (ví dụ "Nhìn thẳng vào camera" → "Quay đầu sang trái" → "Nháy mắt"), tự động chụp 1 ảnh khi phát hiện đúng khoảnh khắc (hoặc đơn giản hơn: đếm ngược 2 giây mỗi bước rồi tự chụp) — không cần quay video thật, chỉ cần N ảnh JPEG đúng thứ tự.
- Xử lý `status=failed` từ `.../frames`: hiện đúng bước nào sai (`steps[].passed=false`) để người dùng biết cần làm lại kiểu gì (ví dụ "chưa quay đủ sang trái" thay vì lỗi chung chung).
- Đổi màn hình chấm công tại site `requireFaceIdCheckin=true`: thay chụp 1 ảnh bằng luồng challenge đầy đủ trước khi gọi `submitCheckin`.

## 0. Tóm tắt kết quả

**4 tính năng bạn liệt kê đã được xây dựng từ trước** (ghi nhận đồng ý, đăng ký, HR xem trạng thái, xóa/thu hồi) — nhưng review sâu vào nghiệp vụ (đối chiếu với các hệ thống chấm công sinh trắc học thực tế: ZKTeco/Hikvision, DingTalk/Feishu chấm công khuôn mặt doanh nghiệp, và luật riêng tư Nghị định 13/2023/NĐ-CP) phát hiện **công nghệ nền tảng đã đúng hướng** (embedding 128 chiều qua `face_recognition`/dlib, so khớp bằng cosine similarity — không lưu ảnh phẳng để so khớp trực tiếp như bạn lo ngại) nhưng có **7 lỗ hổng nghiệp vụ thật**, đã sửa cả 7:

| # | Vấn đề | Mức độ | Đã sửa |
|---|---|---|---|
| 1 | Đăng ký (enroll) không chạy anti-spoofing — ảnh in/chụp màn hình vẫn đăng ký được | 🔴 Nghiêm trọng | Mục 2.1 |
| 2 | N ảnh trong 1 lượt đăng ký không được kiểm tra có cùng 1 người | 🔴 Nghiêm trọng | Mục 2.1 |
| 3 | Face verify khi chấm công không bắt buộc, thất bại cũng không ảnh hưởng gì | 🟠 Xung đột logic | Mục 2.2, 2.3 |
| 4 | HR có thể tự "đồng ý" Face ID thay nhân viên | 🟠 Vi phạm nguyên tắc consent | Mục 2.4 |
| 5 | Nghỉ việc không tự thu hồi Face ID — dữ liệu sinh trắc học tồn tại vô thời hạn | 🟠 Rủi ro pháp lý | Mục 2.5 |
| 6 | Báo cáo Face ID cho HR không áp site-scope | 🟡 Thiếu nhất quán | Mục 2.6 |
| 7 | Job dọn dẹp định kỳ (`DataRetentionJob`) gọi 1 API không tồn tại ở fams-ai, lỗi âm thầm mỗi tuần | 🟡 Bug tiềm ẩn | Mục 2.7 |

Và bổ sung theo quyết định của bạn (đã xác nhận qua 3 câu hỏi lựa chọn):
- **Mọi lượt đăng ký/đăng ký lại đều phải qua HR duyệt** (không tự động kích hoạt) — mục 1.
- **Site có thể cấu hình bắt buộc Face ID khi chấm công** (`requireFaceIdCheckin`) — mục 3.

**Kết quả test**: build lại cả `fams-api` (Java) và `fams-ai` (Python/AI), test sống qua API thật trên dữ liệu seed (tenant `beta-industries`) cho: consent bị HR chặn/nhân viên tự làm được, site bắt buộc Face ID chặn thiếu ảnh (`FACE_ID_REQUIRED`), chặn nhân viên chưa enroll (`FACE_ID_NOT_ENROLLED`). Chạy lại `tests/site/*.sh` (189 test) — 100% pass, không hồi quy. `tests/face-id/*.sh` và `tests/face_id/*.sh` (9 file) bị chặn bởi lỗi kịch bản test có từ trước (tạo tenant thiếu `ownerEmail` — API đã đổi từ lâu, kịch bản test chưa cập nhật), không liên quan tới thay đổi lần này.

## 1. Kiến trúc mới: mọi đăng ký đều qua hàng đợi duyệt của HR

### 1.1 Vì sao — tham khảo thực tế

Máy chấm công sinh trắc học chuyên dụng (ZKTeco, Hikvision — phổ biến ở VN) luôn để **HR/quản lý enroll tại thiết bị**, nhân viên không tự enroll qua app cá nhân, chính vì sợ gian lận (đúng nỗi lo bạn nêu ban đầu). Các app hiện đại hơn cho tự chụp qua điện thoại (DingTalk/Feishu chấm công khuôn mặt) đều **yêu cầu HR duyệt ảnh trước khi kích hoạt**. Hệ thống của bạn giờ theo đúng mô hình thứ hai: tự chụp qua app, nhưng không tự động có hiệu lực.

### 1.2 Cỗ máy trạng thái

```
not_enrolled ──consent──▶ not_enrolled (đã đồng ý) ──enroll──▶ reviewStatus=pending
                                                                     │
                                            approve ◀────────────────┴───────────────▶ reject
                                               │                                          │
                                               ▼                                          ▼
                                           enrolled                          (status GIỮ NGUYÊN,
                                               │                              reviewStatus=rejected)
                                        re-enroll (bất kỳ lúc nào)
                                               │
                                               ▼
                                    reviewStatus=pending, status VẪN LÀ enrolled
                                    (khuôn mặt cũ vẫn dùng chấm công được trong lúc chờ duyệt)
```

Điểm mấu chốt: **`status`** (not_enrolled|enrolled|revoked) luôn phản ánh trạng thái **đã được duyệt gần nhất** — đây là cái quyết định có chấm công được hay không. **`reviewStatus`** (none|pending|rejected) theo dõi lượt nộp đang chờ xử lý, **độc lập với `status`**. Nhờ vậy, khi một nhân viên đã `enrolled` nộp ảnh đăng ký lại (đổi diện mạo, thiết bị mới...), họ **không bị gián đoạn khả năng chấm công** trong lúc chờ HR duyệt — khuôn mặt cũ vẫn dùng được cho tới khi (nếu) HR duyệt ảnh mới.

### 1.3 API

**`POST /face-id/consent`** — CHỈ nhân viên tự gọi được (xem mục 2.4), không đổi request/response so với trước.

**`POST /face-id/enroll`** (multipart, 3-5 ảnh) — KHÔNG còn kích hoạt ngay. Mỗi ảnh phải:
1. Pass anti-spoofing (MiniFASNet qua DeepFace) — ảnh in/chụp màn hình bị từ chối ngay `400`.
2. Detect được đúng 1 khuôn mặt.
3. Cùng 1 người với các ảnh còn lại trong lô (cosine similarity ≥ ngưỡng `AI_ENROLL_SAME_PERSON_THRESHOLD`, mặc định 0.45).

Qua hết → `reviewStatus=pending`, `status` giữ nguyên. Response mẫu:
```json
{ "status": "not_enrolled", "consentGiven": true, "reviewStatus": "pending",
  "pendingPhotoCount": 4, "submittedAt": "2026-07-28T10:00:00Z" }
```
Nếu đang có 1 lượt `pending` chưa xử lý → `409`, phải chờ HR quyết định trước khi nộp lô mới.

**`POST /face-id/approve`** (mới) — HR/Admin duyệt, **không tự duyệt được cho chính mình** (khác hẳn pattern "self OR face_id:manage" dùng ở các action khác — duyệt hồ sơ của chính mình triệt tiêu hoàn toàn ý nghĩa bước duyệt). Promote `pending_embedding` → `embedding`, `status=enrolled`.

**`POST /face-id/reject`** (mới, body `{"reason": "..."}`) — HR/Admin từ chối kèm lý do. Nếu đây là lượt đăng ký lại, khuôn mặt đã duyệt trước đó (nếu có) không đổi.

**`GET /api/v1/tenants/{tenantId}/face-id/pending-review`** (mới, tenant-level, không theo employeeId) — hàng đợi duyệt cho HR, tự động lọc theo site-scope của người gọi (SITE_SUPERVISOR chỉ thấy nhân viên thuộc site được giao). Trả về danh sách kèm `employeeId/employeeCode/employeeName` để HR không cần tra cứu thêm.

## 2. Chi tiết các lỗi đã sửa

### 2.1 [Đã sửa — nghiêm trọng] Enroll không chống giả mạo

`ai-service/app/routers/enroll.py` trước đây chỉ gọi `extract_embedding()` (detect + encode khuôn mặt) — hàm `check_liveness()` (MiniFASNet anti-spoofing) đã tồn tại sẵn trong codebase và được dùng cho checkin, nhưng **không hề được gọi ở bước enroll**. Ai đó đăng ký bằng ảnh in/ảnh chụp lại màn hình vẫn qua được, và từ đó **mọi lần chấm công sau đều "khớp"** với khuôn mặt giả đó — lỗ hổng đúng như bạn dự đoán ban đầu.

Đã sửa: mỗi ảnh trong lô enroll giờ bắt buộc pass `check_liveness()` trước khi được chấp nhận; đồng thời thêm kiểm tra chéo N ảnh phải cùng 1 người (cosine similarity giữa từng cặp ảnh ≥ ngưỡng riêng, thấp hơn ngưỡng verify vì ảnh enroll cố ý chụp nhiều góc).

### 2.2 [Đã sửa — xung đột logic] Face verify khi chấm công không có hậu quả gì

Trước đây: ảnh chấm công là optional, xử lý async "bắn rồi quên" — dù AI trả về thất bại, bản ghi checkin vẫn giữ `status=valid`. So sánh: tính năng **random-check (kiểm tra đột xuất)** trong CÙNG hệ thống đã xử lý đúng từ trước — khi face fail thì hạ `outcome=fail` + tạo **violation**. Đây là 2 tính năng xử lý cùng loại sự kiện (face verify fail) nhưng khác nhau hoàn toàn.

Đã sửa (mục 2.3 + 3): `FaceResultCallbackController` giờ áp đúng logic của random-check cho checkin thường — NHƯNG chỉ khi site yêu cầu Face ID (`requireFaceIdCheckin=true`), để không phạt những trường hợp ảnh chỉ là optional/best-effort.

### 2.3 [Đã sửa] Escalate + tạo violation khi face verify thất bại tại site bắt buộc Face ID

Migration `V74` thêm cột `checkin_id` (nullable) vào bảng `violations` — trước đây bảng này chỉ liên kết được với `scheduled_check_id`/`check_response_id` (random-check), không có đường nối tới `checkins` thường.

`FaceResultCallbackController` (nhánh `sourceType=checkin`): khi AI trả `faceVerified=false` VÀ site của lượt chấm công đó có `requireFaceIdCheckin=true` → hạ `checkin.status` từ `valid` xuống `pending_review` (nếu chưa ở trạng thái đó) + tạo 1 `Violation` (`violation_type = face_fail` hoặc `liveness_fail` tùy nguyên nhân, `checkin_id` trỏ về bản ghi checkin). HR thấy ngay trong danh sách vi phạm, giống hệt cách random-check đã làm.

### 2.4 [Đã sửa — vi phạm nguyên tắc consent] HR "đồng ý" thay nhân viên

`POST /consent` trước đây cho phép người có quyền `face_id:manage` gọi hộ nhân viên khác. Theo Nghị định 13/2023/NĐ-CP (dữ liệu sinh trắc học là dữ liệu cá nhân nhạy cảm, Điều 11) và GDPR Art.9, đồng ý xử lý dữ liệu sinh trắc học **phải do chính chủ thể dữ liệu thực hiện** — HR không được "đồng ý hộ", kể cả khi có quyền quản lý.

Đã sửa: `giveConsent()` giờ **chỉ chấp nhận chính nhân viên** (so khớp `employee.userId == callerUserId`), kể cả platform admin cũng không được gọi hộ. `enrollFace()`/`revokeFace()` vẫn giữ pattern "self OR face_id:manage" như cũ (HR hỗ trợ chụp ảnh tại kiosk sau khi nhân viên đã tự đồng ý qua tài khoản của họ — vẫn hợp lệ và phổ biến trong thực tế).

### 2.5 [Đã sửa — rủi ro pháp lý] Nghỉ việc không tự thu hồi Face ID

`EmployeeService.changeEmployeeStatus` trước đây không đụng tới `face_profiles`. Dữ liệu sinh trắc học (ảnh + embedding) của người đã nghỉ việc tồn tại vô thời hạn dù mục đích xử lý đã kết thúc.

Đã sửa: khi chuyển trạng thái nhân viên sang `terminated`, tự động gọi thu hồi Face ID (xóa ảnh gốc + embedding qua `fams-ai`, giống hệt luồng nhân viên tự thu hồi). **Chỉ áp dụng cho `terminated`, không áp dụng cho `inactive`** (tạm nghỉ/nghỉ phép — có thể quay lại, không nên bắt đăng ký lại sinh trắc học).

### 2.6 [Đã sửa] Báo cáo Face ID không áp site-scope

`ReportService.getFaceIdEnrollmentReport` trước đây nạp TOÀN BỘ nhân viên trong tenant, không lọc theo site được phép của người gọi — khác với mọi tính năng khác trong hệ thống (checkins, employees, assignments đều đã áp site-scope nhất quán). Một SITE_SUPERVISOR bị giới hạn 1 site nhưng có quyền `reports:list` vẫn xem được Face ID của toàn công ty.

Đã sửa: áp `SiteScopeService.resolveAllowedSiteIds` giống các báo cáo khác, lọc nhân viên qua liên kết assignment→site (Employee không có cột site trực tiếp). Đồng thời bổ sung `reviewStatus`/`submittedAt`/`rejectionReason` vào từng dòng báo cáo, và `statusFilter=pending` giờ lọc theo `reviewStatus` (trước đây filter theo `status='pending'` không còn ý nghĩa vì `pending` đã chuyển hẳn sang `reviewStatus`).

### 2.7 [Đã sửa — bug tiềm ẩn] Job dọn embedding định kỳ gọi API không tồn tại

Phát hiện khi review: `DataRetentionJob` (chạy mỗi Chủ Nhật 3h sáng) gọi `AiServiceClient.deleteEmbedding()` → `DELETE /embeddings/{profileId}` ở `fams-ai` — nhưng route này **chưa từng được implement** (chỉ có biến gọi, không có handler, không đăng ký trong `main.py`). Mỗi tuần job này âm thầm nhận lỗi 404, log error, và cờ `embedding_deleted` không bao giờ được set — chạy vô ích mỗi tuần, mãi mãi.

*Đính chính*: ban đầu tôi tưởng cột `embedding_deleted` là dead code (không có nơi nào set) — đã đọc nhầm, thực ra nó có được set bởi `DataRetentionJob`, chỉ là job đó luôn thất bại vì thiếu route ở phía Python. Rất may phát hiện trước khi đề xuất xóa nhầm.

Đã sửa: implement `DELETE /embeddings/{profile_id}` (router mới `embeddings.py`) — xóa embedding + set `embedding_deleted=true` theo đúng `face_profiles.id`. Đồng thời revoke tương tác (qua `DELETE /enroll/{employee_id}`) giờ cũng set `embedding_deleted=true` ngay lập tức (đồng bộ), nên job định kỳ giờ chỉ còn là lưới an toàn cho trường hợp lệnh revoke tương tác bị lỗi mạng giữa chừng.

## 3. Tính năng mới: Face ID bắt buộc theo site

`Site.requireFaceIdCheckin` (boolean, mặc định `false`) — cấu hình được khi tạo/sửa site (`POST`/`PUT /sites`, field `requireFaceIdCheckin`). Khi bật:

- `submitCheckin` từ chối ngay (`422 FACE_ID_REQUIRED`) nếu thiếu ảnh — không mất công gửi job async cho một lượt chắc chắn không đạt.
- Từ chối ngay (`422 FACE_ID_NOT_ENROLLED`) nếu nhân viên chưa có Face ID `enrolled` — hướng dẫn rõ đi đăng ký trước, thay vì để chấm công "trôi qua" không xác thực.
- `requiresLiveness` bị ép `true` bất kể client gửi gì — tránh App tự ý tắt anti-spoofing ở nơi chính sách yêu cầu bật.
- Nếu ảnh gửi lên sau đó xác thực THẤT BẠI (async) → escalate + tạo violation như mục 2.3.

Vì sao theo site chứ không theo tenant (khớp lựa chọn "Recommended" bạn đã chọn): một công ty xây dựng có thể có công trường an ninh cao (kho vật tư, khu vực hạn chế) cần Face ID bắt buộc, và văn phòng thường không cần — linh hoạt theo nhu cầu thực tế, giống Connecteam/Deputy cho phép cấu hình chính sách theo từng địa điểm riêng.

## 4. Mã lỗi mới cần FE xử lý

| HTTP | errorCode | Khi nào |
|---|---|---|
| 403 | (message: "consent must be given by the employee themselves") | HR/Admin cố gọi `/consent` hộ nhân viên khác |
| 409 | (message: "previous submission still pending review") | Nộp enroll mới khi đang có lượt `pending` chưa xử lý |
| 422 | `FACE_ID_REQUIRED` | Site bắt buộc Face ID nhưng chấm công thiếu ảnh |
| 422 | `FACE_ID_NOT_ENROLLED` | Site bắt buộc Face ID nhưng nhân viên chưa `enrolled` |
| 400 | (enroll — nhiều dạng message) | Ảnh fail anti-spoofing, sai số lượng, nhiều/không có khuôn mặt, ảnh không cùng 1 người trong lô |
| 404 | (approve/reject) | Không có lượt đăng ký nào đang `pending` cho nhân viên đó |

## 5. Xác nhận thêm — không cần sửa

- **Không lưu ảnh phẳng để so khớp** — nền tảng công nghệ (embedding 128 chiều qua `face_recognition`/dlib ResNet-34, so khớp bằng cosine similarity) đã đúng chuẩn ngành từ trước, không phải sửa.
- **Thu hồi (revoke) không cần duyệt, luôn thực hiện ngay** — dù do chính nhân viên hay do HR — đây là quyền hợp pháp (rút consent) không thể trì hoãn bằng một bước xét duyệt.
- **`pending`/`enrolled`/`revoked` là 3 trạng thái độc lập với `reviewStatus`** — tránh nhầm giữa "trạng thái đang dùng để chấm công" và "trạng thái của lượt nộp gần nhất".
