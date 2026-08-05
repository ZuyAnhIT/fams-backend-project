# Báo cáo: Phân tích các sản phẩm và phần mềm chấm công tương tự trên thị trường

> Ngày lập: 2026-08-05. Báo cáo dựa trên (1) khảo sát thị trường qua tìm kiếm web (nguồn liệt kê ở cuối mỗi mục) và (2) đối chiếu với hiện trạng thật của hệ thống FAMS đã phân tích trong 2 báo cáo trước (`bao-cao-kien-truc-kha-nang-chiu-tai-2026-08-05.md`, `bao-cao-kien-truc-ai-faceid-2026-08-05.md`). Thông tin về đối thủ lấy từ nguồn công khai (trang chủ, review bên thứ 3) — **giá cụ thể và số liệu hiệu năng của đối thủ do chính hãng công bố, chưa được FAMS tự kiểm chứng độc lập**, chỉ dùng để tham khảo tương đối.

## Danh sách 8 sản phẩm được chọn phân tích

Tiêu chí chọn: có tối thiểu 3/7 nhóm chức năng người yêu cầu nêu (Face ID + GPS + liveness + random check, quản lý ca, quản lý phép/OT, tích hợp camera/app, báo cáo/xuất lương, đa chi nhánh, API/ERP). Chọn phối hợp 3 nhóm để so sánh công bằng theo từng phân khúc:

| Nhóm | Sản phẩm | Vì sao chọn |
|---|---|---|
| Hãng thiết bị + phần mềm biometric (phần cứng dẫn dắt) | **ZKTeco (ZKBio Time)**, **Hikvision (HikCentral)** | 2 hãng phổ biến nhất tại Việt Nam cho máy chấm công vân tay/khuôn mặt vật lý, có cả phần mềm quản lý đi kèm |
| Super-app doanh nghiệp Trung Quốc (phổ biến ở nhà máy/chuỗi bán lẻ VN) | **DingTalk (Alibaba)** | Bộ chấm công tích hợp trong super-app, dùng rộng rãi ở doanh nghiệp có vốn Trung Quốc tại VN |
| SaaS quốc tế chuyên workforce management | **Deputy**, **Connecteam** | 2 SaaS toàn cầu tiêu biểu, mạnh về GPS + lịch làm việc + tích hợp payroll, có Face ID (Deputy) hoặc face-photo-log (Connecteam) |
| SaaS nội địa Việt Nam (đối thủ cạnh tranh trực tiếp gần nhất) | **MISA AMIS**, **Base HRM+**, **1Office** | 3 nền tảng HRM/chấm công phổ biến nhất thị trường Việt Nam hiện nay, cùng phân khúc khách hàng doanh nghiệp vừa/nhỏ với FAMS |

---

## Phân tích chi tiết từng sản phẩm

### 1. ZKTeco — ZKBio Time (+ dòng thiết bị Horus/SenseFace)

| Tiêu chí | Nội dung |
|---|---|
| **Mô hình triển khai** | Hybrid: bắt buộc có **thiết bị phần cứng vật lý** (máy chấm công/camera AI đặt tại cửa) kết nối lên phần mềm quản lý — ZKBio Time chạy dạng **private cloud/on-premise** (web-based, tự host tại doanh nghiệp), không phải SaaS thuần |
| **Nhóm khách hàng mục tiêu** | Doanh nghiệp có địa điểm cố định (nhà máy, văn phòng, khu công nghiệp), quy mô từ vài trăm tới hàng chục nghìn nhân viên — nơi cần kiểm soát ra/vào vật lý nghiêm ngặt (an ninh + chấm công cùng lúc) |
| **Chức năng chính** | Chấm công + kiểm soát ra vào (access control) hợp nhất, quản lý ca/lịch làm, tính công tự động, báo cáo, quản lý hàng trăm thiết bị/hàng nghìn nhân viên |
| **Công nghệ nhận diện khuôn mặt** | Deep learning tự phát triển bởi ZKTeco, chạy **trên chính thiết bị phần cứng** (edge, không phải server) |
| **Chống giả mạo** | Có — thuật toán "tăng khả năng chống giả mạo trước các kiểu tấn công động", tích hợp sẵn trong firmware thiết bị (không tách rời được) |
| **Khả năng mở rộng** | Quản lý được hàng trăm thiết bị/hàng nghìn nhân viên qua 1 hệ thống trung tâm — nhưng mở rộng đồng nghĩa **mua thêm phần cứng** theo số điểm chấm công, không phải chỉ tăng license phần mềm |
| **Phương thức tích hợp** | ZKBio CVSecurity API — cho phép bên thứ 3 đọc/ghi dữ liệu nghiệp vụ |
| **Chi phí** | Không công bố công khai — mô hình thường là **mua đứt phần cứng + phí phần mềm/license theo số thiết bị hoặc nhân viên**, chi phí ban đầu cao hơn SaaS thuần |
| **Ưu điểm** | Đã chứng minh qua thời gian dài ở VN, xử lý edge (không phụ thuộc mạng lúc chấm công), có chứng nhận ISO 27001/27701 |
| **Hạn chế** | Cần đầu tư phần cứng tại từng điểm, khó dùng cho nhân viên di động/công trường không cố định, không có "random check" chủ động, tích hợp API cần đội kỹ thuật riêng cấu hình |

### 2. Hikvision — HikCentral + dòng terminal MinMoe/DS-K1T

| Tiêu chí | Nội dung |
|---|---|
| **Mô hình triển khai** | Tương tự ZKTeco — phần cứng terminal + phần mềm quản lý tập trung (HikCentral), có thể quản lý qua app **Hik-Connect** hoặc nền tảng chuyên nghiệp |
| **Nhóm khách hàng mục tiêu** | Doanh nghiệp/tòa nhà cần **an ninh + chấm công** cùng lúc (thường đã dùng camera Hikvision cho an ninh, mở rộng sang chấm công) |
| **Chức năng chính** | Kiểm soát ra vào + chấm công, xem trực tiếp qua NVR/camera mạng khi có sự kiện, tích hợp video intercom |
| **Công nghệ nhận diện khuôn mặt** | Tự phát triển bởi Hikvision, chạy trên thiết bị (edge) |
| **Chống giả mạo** | Có — "embedded face image anti-spoofing" tích hợp firmware; tốc độ nhận diện công bố **0,2 giây**, độ chính xác **>99%** (số liệu tự công bố của hãng) |
| **Khả năng mở rộng** | Liên kết được với NVR/camera mạng có sẵn — thuận lợi cho doanh nghiệp đã đầu tư hạ tầng camera Hikvision |
| **Phương thức tích hợp** | 5 phương án: Intelligent Security API, Access Control Gateway API, Hik-ProConnect OpenAPI, HikCentral Professional OpenAPI, xuất Database/CSV/TXT |
| **Chi phí** | Không công bố công khai — mô hình mua đứt phần cứng tương tự ZKTeco |
| **Ưu điểm** | Tốc độ nhận diện rất nhanh, hệ sinh thái camera an ninh mạnh, nhiều phương án tích hợp API linh hoạt |
| **Hạn chế** | Cùng nhóm hạn chế với ZKTeco (phụ thuộc phần cứng cố định, không hợp cho nhân viên di động), không thấy công bố về active liveness (quay đầu/nháy mắt theo lệnh) mà chủ yếu dựa vào anti-spoofing thụ động trên ảnh/video ngắn |

### 3. DingTalk (Alibaba) — module Chấm công (智能考勤)

| Tiêu chí | Nội dung |
|---|---|
| **Mô hình triển khai** | **SaaS thuần**, chạy trên app di động DingTalk — **không bắt buộc phần cứng** (khác biệt lớn với ZKTeco/Hikvision) |
| **Nhóm khách hàng mục tiêu** | Rất rộng — từ doanh nghiệp nhỏ tới tập đoàn lớn, đặc biệt phổ biến ở doanh nghiệp có vốn/quan hệ với Trung Quốc, chuỗi bán lẻ, nhà máy tại Đông Nam Á |
| **Chức năng chính** | Chấm công đa phương thức (vân tay, khuôn mặt, GPS+Wifi+vị trí "combo 3 lớp"), quản lý lịch làm, tích hợp OA/quy trình phê duyệt, quản lý dự án |
| **Công nghệ nhận diện khuôn mặt** | AI tự phát triển của Alibaba, chạy **không cần phần cứng riêng** — dùng camera điện thoại |
| **Chống giả mạo** | Có — công bố kết hợp **"AI liveness detection + xác thực vị trí kép (dual-location)"**, tuyên bố nhận diện chính xác ảnh tĩnh/phát lại màn hình/mặt nạ 3D |
| **Khả năng mở rộng** | Rất tốt — SaaS đa tenant quy mô lớn, không giới hạn bởi phần cứng, phù hợp doanh nghiệp nhiều chi nhánh/chuỗi cửa hàng |
| **Phương thức tích hợp** | Open Platform API v2.3, hệ sinh thái **hơn 1.200 ISV** tích hợp sẵn (bao gồm hệ thống HR/lương như Jiandao Cloud, GaiaWorks) |
| **Chi phí** | Không công bố cụ thể qua tìm kiếm — nhìn chung DingTalk có gói miễn phí cho tính năng cơ bản, tính phí theo gói doanh nghiệp cho tính năng nâng cao |
| **Ưu điểm** | Không cần đầu tư phần cứng, hệ sinh thái tích hợp cực lớn, chấm công "ngẩng đầu lên là điểm danh" tiện lợi cao |
| **Hạn chế** | Là 1 module trong super-app tổng thể (không phải sản phẩm chuyên biệt chấm công), giao diện/tài liệu chủ yếu tiếng Trung, dữ liệu lưu trên hạ tầng Alibaba (cân nhắc yếu tố chủ quyền dữ liệu với doanh nghiệp VN) |

### 4. Deputy

| Tiêu chí | Nội dung |
|---|---|
| **Mô hình triển khai** | SaaS thuần, không cần phần cứng — app di động + web + kiosk (tablet dùng chung) |
| **Nhóm khách hàng mục tiêu** | Doanh nghiệp bán lẻ, F&B, chăm sóc sức khỏe — ngành có lịch làm ca phức tạp, nhân viên theo giờ (hourly workers), thị trường chính Úc/Mỹ/Anh |
| **Chức năng chính** | Xếp lịch làm việc (scheduling) là thế mạnh cốt lõi, chấm công, quản lý nghỉ phép (PTO), quản lý task/hiệu suất, tuyển dụng |
| **Công nghệ nhận diện khuôn mặt** | Có hỗ trợ "biometric facial recognition" tại kiosk trung tâm, nhưng không phải trọng tâm sản phẩm — công nghệ cụ thể không công bố (khả năng dùng thư viện bên thứ 3) |
| **Chống giả mạo** | GPS + geofence (chặn chấm công ngoài bán kính cho phép) là cơ chế chống gian lận chính; face recognition là lớp bổ sung, không phải bắt buộc |
| **Khả năng mở rộng** | Tốt — SaaS đa địa điểm sẵn có, hướng tới chuỗi cửa hàng/nhà hàng nhiều địa điểm |
| **Phương thức tích hợp** | Tích hợp payroll sẵn với Xero, QuickBooks Online, ADP, MYOB, Access WageEasy... + API để đẩy timesheet |
| **Chi phí** | Theo mô hình subscription/nhân viên/tháng (không có số cụ thể qua tìm kiếm lần này) |
| **Ưu điểm** | Scheduling rất mạnh (không chỉ chấm công), hệ sinh thái tích hợp payroll rộng, UX tốt cho ngành dịch vụ |
| **Hạn chế** | Face recognition là tính năng phụ, không phải core; không thấy công bố cơ chế active liveness/random check; giá tính theo đầu người có thể cao khi mở rộng quy mô lớn |

### 5. Connecteam

| Tiêu chí | Nội dung |
|---|---|
| **Mô hình triển khai** | SaaS thuần, mobile-first, hướng tới lực lượng lao động không ngồi bàn giấy (deskless workforce) |
| **Nhóm khách hàng mục tiêu** | Doanh nghiệp vừa/nhỏ có nhân viên hiện trường (xây dựng, giao nhận, dọn dẹp, bảo trì) |
| **Chức năng chính** | Time clock GPS, lập lịch, giao việc, đào tạo, chat nội bộ, checklist công việc |
| **Công nghệ nhận diện khuôn mặt** | **Không phải nhận diện khuôn mặt thật** — chỉ chụp ảnh selfie lúc chấm công ("Face ID" của Connecteam thực chất là **face-photo-log**), **không tự động so khớp với ảnh hồ sơ**, quản lý phải tự xem ảnh thủ công để đối chiếu |
| **Chống giả mạo** | Chủ yếu dựa vào GPS location stamp + xem ảnh thủ công, **không có liveness detection hay so khớp AI tự động** — đây là điểm khác biệt quan trọng so với FAMS |
| **Khả năng mở rộng** | Tốt — 4 gói theo quy mô (Basic/Advanced/Expert/Enterprise), giá cố định cho 30 nhân viên đầu, +5 USD/người/tháng sau đó |
| **Phương thức tích hợp** | API chỉ có ở gói **Expert** ($119/tháng) trở lên; đồng bộ payroll qua RUN Powered by ADP, QuickBooks, Xero |
| **Chi phí** | Basic $35/tháng, Advanced $59/tháng, Expert $119/tháng (đã gồm 30 nhân viên đầu, cộng thêm $5/người/tháng) |
| **Ưu điểm** | Giá minh bạch, dễ tiếp cận doanh nghiệp nhỏ, mạnh về công cụ vận hành đội ngũ hiện trường (không chỉ chấm công) |
| **Hạn chế** | **Face ID không phải xác thực sinh trắc học thật** — đây là lỗ hổng chống gian lận rõ ràng so với các sản phẩm có so khớp AI thật (bao gồm FAMS); API bị giới hạn ở gói cao |

### 6. MISA AMIS Chấm công

| Tiêu chí | Nội dung |
|---|---|
| **Mô hình triển khai** | SaaS, nằm trong hệ sinh thái **MISA AMIS** (nền tảng quản trị doanh nghiệp hợp nhất — kế toán, nhân sự, CRM...) |
| **Nhóm khách hàng mục tiêu** | Doanh nghiệp Việt Nam mọi quy mô, đặc biệt mạnh ở nhóm đã dùng phần mềm kế toán MISA (bán chéo trong cùng hệ sinh thái) |
| **Chức năng chính** | Đa phương thức chấm công: vân tay, GPS, QR Code, Wifi, khuôn mặt AI; tích hợp trực tiếp với module tính lương/nhân sự MISA AMIS |
| **Công nghệ nhận diện khuôn mặt** | AI deep learning tự phát triển/tích hợp (không công bố rõ tên thư viện nền tảng) — quảng cáo "độ chính xác cao kể cả ánh sáng kém hoặc thay đổi nhỏ ngoại hình" (số liệu tự công bố, chưa có báo cáo kiểm định độc lập) |
| **Chống giả mạo** | Không có thông tin công khai chi tiết về liveness detection (chủ động hay thụ động) qua tìm kiếm lần này |
| **Khả năng mở rộng** | Tốt — SaaS đa tenant, đã phục vụ số lượng lớn doanh nghiệp Việt Nam |
| **Phương thức tích hợp** | Tích hợp sẵn trong hệ sinh thái AMIS (kế toán, nhân sự, lương) — tích hợp ngoài hệ sinh thái (ERP khác) không có thông tin rõ ràng |
| **Chi phí** | Không công bố cụ thể qua tìm kiếm lần này — thường theo mô hình subscription/năm, tính theo số nhân viên |
| **Ưu điểm** | Hệ sinh thái liền mạch với kế toán/lương MISA (rất mạnh cho doanh nghiệp VN đã dùng MISA), thương hiệu lâu năm, hỗ trợ tiếng Việt đầy đủ |
| **Hạn chế** | Thông tin kỹ thuật (mô hình AI cụ thể, ngưỡng, chống giả mạo) không minh bạch công khai; giá trị tốt nhất khi đã trong hệ sinh thái MISA, kém hấp dẫn nếu dùng ERP/kế toán khác |

### 7. Base HRM+ (Base Check-in / Base Timeoff / Base Payroll)

| Tiêu chí | Nội dung |
|---|---|
| **Mô hình triển khai** | SaaS, là 1 phân hệ trong nền tảng **Base.vn** (quản trị công việc, CRM, nhân sự hợp nhất) |
| **Nhóm khách hàng mục tiêu** | Doanh nghiệp vừa/nhỏ Việt Nam, có gói "Starter" riêng cho SME |
| **Chức năng chính** | Chấm công đa kênh (máy tính, điện thoại, camera nhận diện khuôn mặt, QR Code, thẻ từ), GPS cho nhân viên làm việc từ xa, liên kết Base Timeoff (nghỉ phép) + Base Payroll (lương) |
| **Công nghệ nhận diện khuôn mặt** | Không công bố rõ nền tảng AI cụ thể qua tìm kiếm lần này |
| **Chống giả mạo** | Không có thông tin công khai chi tiết |
| **Khả năng mở rộng** | Có hỗ trợ **cả on-premise lẫn cloud** ở một số cấu hình (theo tổng hợp thị trường), tương thích nhiều loại thiết bị sinh trắc học |
| **Phương thức tích hợp** | Tích hợp nội bộ liền mạch giữa Base Check-in ↔ Base HRM ↔ Base Payroll ↔ Base Timeoff; tích hợp ngoài hệ sinh thái không rõ |
| **Chi phí** | Có gói Starter linh hoạt cho SME (giá cụ thể không công bố qua tìm kiếm lần này) |
| **Ưu điểm** | Bộ sản phẩm HRM rất đầy đủ theo mô-đun (không chỉ chấm công), giá tiếp cận tốt cho SME Việt Nam, giao diện tiếng Việt |
| **Hạn chế** | Thông tin công nghệ AI/chống giả mạo không minh bạch; như các SaaS HRM Việt Nam khác, thế mạnh nằm ở bộ tính năng quản trị nhân sự tổng thể hơn là chuyên sâu công nghệ sinh trắc học |

### 8. 1Office (phân hệ 1HRM)

| Tiêu chí | Nội dung |
|---|---|
| **Mô hình triển khai** | SaaS, phân hệ trong nền tảng quản trị doanh nghiệp hợp nhất 1Office (HR, công việc, CRM, quy trình) |
| **Nhóm khách hàng mục tiêu** | Doanh nghiệp Việt Nam quy mô vừa tới lớn (tự công bố phù hợp 20-500 nhân viên) |
| **Chức năng chính** | Chấm công nhận diện khuôn mặt AI, GPS tracking tự động cho nhân viên ngoài văn phòng, tích hợp trực tiếp phần mềm tính lương riêng |
| **Công nghệ nhận diện khuôn mặt** | Tự quảng bá là **"hệ thống chấm công nhận diện khuôn mặt AI mạnh nhất Việt Nam"** (tuyên bố marketing của hãng, chưa có bên thứ 3 kiểm chứng độc lập) |
| **Chống giả mạo** | Không có thông tin công khai chi tiết về cơ chế liveness qua tìm kiếm lần này |
| **Khả năng mở rộng** | SaaS đa tenant, tương tự các nền tảng HRM VN khác |
| **Phương thức tích hợp** | Tích hợp nội bộ với module lương riêng của 1Office; tích hợp ERP/hệ thống ngoài không rõ |
| **Chi phí** | Khoảng **50.000-100.000 VNĐ/người/tháng** (theo tổng hợp thị trường, chưa xác nhận trực tiếp từ báo giá chính thức) |
| **Ưu điểm** | Định vị mạnh về Face ID trong truyền thông marketing, tích hợp gọn trong 1 nền tảng doanh nghiệp Việt |
| **Hạn chế** | Thiếu minh bạch kỹ thuật (không công bố mô hình AI, ngưỡng, benchmark độc lập), giá theo đầu người có thể tăng nhanh khi mở rộng quy mô lớn |

---

## Bảng so sánh chức năng: FAMS vs. thị trường

Ký hiệu: ✅ Có đầy đủ · 🟡 Có một phần/không rõ · ❌ Không có/không công bố

| Chức năng | **FAMS** | ZKTeco | Hikvision | DingTalk | Deputy | Connecteam | MISA AMIS | Base HRM+ | 1Office |
|---|---|---|---|---|---|---|---|---|---|
| Nhận diện khuôn mặt (AI, so khớp tự động) | ✅ ArcFace 512-d | ✅ | ✅ | ✅ | 🟡 | ❌ (chỉ log ảnh) | ✅ | 🟡 | ✅ |
| Chấm công GPS/geofence | ✅ | 🟡 (1 số dòng thiết bị) | 🟡 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Active liveness** (quay đầu/nháy mắt theo lệnh ngẫu nhiên) | ✅ | ❌ (chỉ passive) | ❌ (chỉ passive) | ✅ (công bố) | 🟡 không rõ | ❌ | 🟡 không rõ | 🟡 không rõ | 🟡 không rõ |
| Passive anti-spoofing (chống ảnh in/màn hình) | ✅ MiniFASNet | ✅ | ✅ | ✅ (công bố) | 🟡 | ❌ | 🟡 không rõ | 🟡 không rõ | 🟡 không rõ |
| **Random check** (kiểm tra hiện diện đột xuất chủ động, có state machine + escalate violation) | ✅ — **tính năng khác biệt, hiếm gặp trên thị trường phổ thông** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Quản lý ca làm việc | ✅ | ✅ | ✅ | ✅ | ✅ (thế mạnh) | ✅ | ✅ | ✅ | ✅ |
| Đi muộn/về sớm/nghỉ phép/OT | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Hàng đợi HR duyệt enrollment sinh trắc học (không tự kích hoạt) | ✅ — **quy trình review 2 bước, hiếm gặp ở SaaS phổ thông** | 🟡 (tùy triển khai, thường enroll tại chỗ) | 🟡 | ❌ (tự động kích hoạt) | ❌ | — (không áp dụng) | ❌ (thường tự động) | ❌ | ❌ |
| Đa chi nhánh/đa địa điểm | ✅ (multi-tenant + site-scope) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Đa tenant (nhiều công ty độc lập trên 1 hệ thống) | ✅ | ❌ (thường 1 doanh nghiệp/instance) | ❌ | 🟡 (super-app dùng chung hạ tầng Alibaba) | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 |
| Báo cáo chấm công & xuất dữ liệu lương | ✅ (báo cáo, chưa có module tính lương đầy đủ — xem hạn chế) | ✅ | ✅ | ✅ | ✅ (tích hợp payroll sẵn) | ✅ (tích hợp payroll sẵn) | ✅ (liền mạch với MISA kế toán) | ✅ (liền mạch Base Payroll) | ✅ (liền mạch 1Office payroll) |
| Module tính lương tích hợp sẵn (payroll engine) | ❌ **— hệ thống chỉ xuất dữ liệu chấm công, chưa có module tính lương riêng** | 🟡 | 🟡 | 🟡 | ✅ | ✅ | ✅ | ✅ | ✅ |
| API mở tích hợp ERP/HR bên ngoài | 🟡 (có API nội bộ Java↔AI, chưa thấy public API/webhook cho bên thứ 3 tích hợp ERP ngoài) | ✅ (ZKBio CVSecurity API) | ✅ (5 phương án API) | ✅ (1.200+ ISV) | ✅ | ✅ (từ gói Expert) | 🟡 (chủ yếu nội bộ hệ sinh thái) | 🟡 (chủ yếu nội bộ hệ sinh thái) | 🟡 (chủ yếu nội bộ) |
| Kiến trúc microservice AI tách riêng (dễ scale/thay model độc lập) | ✅ | ❌ (AI nhúng firmware thiết bị) | ❌ | ❓ không rõ (nội bộ Alibaba) | ❓ | ❓ | ❓ | ❓ | ❓ |
| Không cần đầu tư phần cứng chuyên dụng | ✅ (dùng camera điện thoại/webcam) | ❌ (bắt buộc terminal vật lý) | ❌ (bắt buộc terminal vật lý) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Mã hóa dữ liệu sinh trắc học at-rest | ❌ **— đã xác nhận là khoảng trống thật của FAMS (xem báo cáo AI/FaceID)** | ❓ không công bố | ❓ không công bố | ❓ không công bố | ❓ | — | ❓ | ❓ | ❓ |
| Benchmark độ chính xác (FAR/FRR) công bố độc lập | ❌ (FAMS chưa đo) | 🟡 (tự công bố, chưa kiểm định độc lập) | 🟡 (tự công bố ">99%", chưa kiểm định độc lập) | 🟡 | 🟡 | — | 🟡 | 🟡 | 🟡 |

---

## Kết luận: chức năng đang có, chức năng còn thiếu, lợi thế cạnh tranh

### Những chức năng FAMS đang có, ngang tầm hoặc vượt phần lớn thị trường

1. **Nhận diện khuôn mặt AI thật với so khớp tự động** (ArcFace 512 chiều) — ngang các đối thủ mạnh nhất (ZKTeco, Hikvision, DingTalk, MISA, 1Office), **vượt hẳn Connecteam** (chỉ log ảnh thủ công, không so khớp tự động).
2. **Active liveness (quay đầu/nháy mắt theo lệnh ngẫu nhiên, chống replay)** — đây là tính năng **hiếm** trên thị trường phổ thông; đa số đối thủ (ZKTeco, Hikvision) chỉ có **passive anti-spoofing** (phân tích 1 ảnh/đoạn ngắn), không bắt người dùng thực hiện chuỗi hành động ngẫu nhiên thật sự. Chỉ DingTalk công bố cơ chế tương đương (nhưng là tuyên bố marketing, không có chi tiết kỹ thuật công khai để đối chiếu).
3. **Random check (kiểm tra hiện diện đột xuất)** — **không thấy đối thủ nào trong 8 sản phẩm khảo sát có tính năng tương đương** (state machine chủ động gửi yêu cầu xác minh ngẫu nhiên trong ca làm, tự tạo violation nếu không phản hồi). Đây là **lợi thế cạnh tranh khác biệt rõ rệt nhất** của FAMS.
4. **Quy trình duyệt (HR review) 2 bước cho mọi lần đăng ký/đăng ký lại khuôn mặt** — không tự động kích hoạt ngay như phần lớn SaaS phổ thông (DingTalk, MISA, 1Office thường tự động kích hoạt ngay sau khi chụp) — đúng thông lệ tốt nhất của máy chấm công chuyên dụng (ZKTeco/Hikvision, nơi HR enroll tại chỗ), kết hợp được ưu điểm bảo mật của phần cứng chuyên dụng **mà không cần mua phần cứng**.
5. **Kiến trúc AI tách microservice riêng, không phụ thuộc phần cứng** — linh hoạt hơn ZKTeco/Hikvision (bắt buộc terminal vật lý theo từng điểm chấm công), dễ nâng cấp model/scale độc lập hơn các đối thủ nhúng AI vào firmware.
6. **Đa tenant thật (nhiều công ty độc lập trên cùng 1 hệ thống, cách ly dữ liệu theo `tenant_id`)** — hầu hết đối thủ khảo sát (trừ DingTalk ở mức hạ tầng dùng chung) được thiết kế cho **1 doanh nghiệp/1 instance**, không phải nền tảng multi-tenant kiểu SaaS-cho-nhiều-khách-hàng — đây là lợi thế nếu FAMS định vị là nền tảng **bán cho nhiều doanh nghiệp** (giống mô hình MISA/Base/1Office) hơn là 1 sản phẩm nội bộ.

### Những chức năng còn thiếu so với thị trường

1. **Module tính lương (payroll engine) tích hợp sẵn** — Deputy, Connecteam, MISA AMIS, Base HRM+, 1Office **đều có** module lương liền mạch hoặc tích hợp payroll bên thứ 3 sẵn (Xero, QuickBooks, ADP...). FAMS hiện **chỉ xuất báo cáo chấm công**, chưa có module tính lương hay tích hợp payroll ngoài nào được xác nhận trong code — đây là khoảng trống lớn nhất so với thị trường, vì hầu hết khách hàng doanh nghiệp coi "chấm công → lương" là 1 luồng liền mạch, không tách rời.
2. **API công khai (public API/webhook) cho bên thứ 3 tích hợp ERP/HR ngoài hệ thống** — ZKTeco, Hikvision, DingTalk, Deputy, Connecteam đều công bố API/hệ sinh thái tích hợp rõ ràng (DingTalk có hơn 1.200 ISV). FAMS hiện có API nội bộ Java↔AI nhưng **chưa xác nhận có public API/webhook** dành cho khách hàng tự tích hợp ERP/kế toán/payroll bên ngoài.
3. **Mã hóa dữ liệu sinh trắc học at-rest** — đã xác nhận là khoảng trống thật (báo cáo AI/FaceID trước), trong khi đây là yêu cầu ngày càng phổ biến ở các hệ thống biometric nghiêm túc, đặc biệt quan trọng nếu FAMS muốn cạnh tranh ở phân khúc doanh nghiệp lớn/yêu cầu tuân thủ pháp lý cao.
4. **Benchmark độ chính xác công khai (FAR/FRR) và chứng nhận bảo mật** (VD ISO/IEC 27001 như ZKBio Time công bố) — chưa có, trong khi đây là yếu tố các đối thủ hardware-driven (ZKTeco) dùng làm điểm bán hàng với khách hàng doanh nghiệp lớn.
5. **Hỗ trợ thiết bị phần cứng chuyên dụng (terminal/camera AI cố định)** — FAMS thuần dựa vào camera điện thoại/webcam qua app; với khách hàng nhà máy/công trường quy mô lớn muốn có **trạm chấm công cố định không cần điện thoại nhân viên** (phổ biến ở ZKTeco/Hikvision), FAMS hiện chưa có phương án — có thể không cần thiết nếu định vị sản phẩm là "mobile-first, không phần cứng" (giống DingTalk/Deputy/Connecteam), nhưng cần là 1 quyết định định vị rõ ràng, không phải khoảng trống ngẫu nhiên.
6. **Hệ sinh thái tích hợp ISV/marketplace** — các nền tảng SaaS lớn (DingTalk, Deputy) có hệ sinh thái đối tác tích hợp sẵn hàng trăm-hàng nghìn ứng dụng; FAMS hiện là hệ thống độc lập, chưa có chiến lược marketplace/đối tác.

### Điểm khác biệt và lợi thế cạnh tranh của FAMS

FAMS đang định vị tự nhiên vào **phân khúc giao thoa** giữa 2 nhóm đối thủ:

- **So với nhóm hardware-driven (ZKTeco, Hikvision)**: FAMS có công nghệ nhận diện AI hiện đại tương đương (thậm chí có active liveness mà 2 hãng này chưa công bố có), nhưng **không cần đầu tư phần cứng** — giảm chi phí triển khai ban đầu, phù hợp doanh nghiệp có nhân viên phân tán/di động (mà ZKTeco/Hikvision không phục vụ tốt).
- **So với nhóm SaaS quốc tế (Deputy, Connecteam)**: FAMS có công nghệ sinh trắc học **thật và nghiêm túc hơn** (Connecteam không so khớp tự động; Deputy không công bố rõ chi tiết kỹ thuật), nhưng **thiếu hệ sinh thái tích hợp payroll** mà 2 sản phẩm này đã xây dựng nhiều năm.
- **So với nhóm SaaS Việt Nam (MISA AMIS, Base HRM+, 1Office)**: FAMS có **minh bạch kỹ thuật cao hơn** (đây là báo cáo nội bộ chi tiết dựa trên code thật — 3 đối thủ VN đều không công bố công khai mô hình AI/ngưỡng/kiến trúc cụ thể), có **random check** và **quy trình duyệt HR 2 bước** mà 3 đối thủ này không thấy có, nhưng **thiếu module tính lương liền mạch** — vốn là thế mạnh cốt lõi khiến 3 đối thủ này chiếm lĩnh thị trường HRM Việt Nam.

**Tóm lại**: lợi thế cạnh tranh rõ nhất của FAMS là **chiều sâu và tính nghiêm ngặt của cơ chế chống gian lận sinh trắc học** (active liveness + random check + quy trình duyệt bắt buộc) — vượt trội so với cả 8 đối thủ khảo sát trên khía cạnh này. Điểm yếu rõ nhất là **thiếu hệ sinh thái nghiệp vụ đi kèm** (payroll, tích hợp ERP ngoài, marketplace) mà các đối thủ đã có sẵn từ lâu.

---

## Các chức năng nên ưu tiên phát triển trong giai đoạn tiếp theo

Sắp xếp theo mức độ ảnh hưởng tới khả năng cạnh tranh, dựa trên khoảng trống đã xác định ở trên:

| Ưu tiên | Hạng mục | Lý do |
|---|---|---|
| 🔴 1 | **Module tính lương hoặc tích hợp payroll bên thứ 3** (tối thiểu: xuất định dạng chuẩn cho phần mềm lương phổ biến VN — MISA, Fast, hoặc kế toán nội bộ; lý tưởng: tích hợp API 2 chiều) | Là khoảng trống lớn nhất so với **toàn bộ 8 đối thủ** khảo sát — khách hàng doanh nghiệp coi chấm công-lương là 1 luồng, thiếu phần này làm giảm sức cạnh tranh trực tiếp nhất |
| 🔴 2 | **Mã hóa dữ liệu sinh trắc học at-rest** (embedding + ảnh) | Không phải khoảng trống so với thị trường (đối thủ cũng không công bố rõ), nhưng là rủi ro pháp lý/bảo mật thật đã tự phát hiện — nên chủ động dẫn đầu thay vì chờ bị hỏi, đặc biệt nếu muốn bán vào doanh nghiệp lớn/ngành yêu cầu tuân thủ cao |
| 🟠 3 | **Public API/webhook cho tích hợp ERP/HR ngoài hệ thống** | Cần thiết để cạnh tranh với hệ sinh thái tích hợp của DingTalk/Deputy/Connecteam, và để bán được vào doanh nghiệp đã có ERP/kế toán riêng không thuộc hệ sinh thái FAMS |
| 🟠 4 | **Benchmark độ chính xác (FAR/FRR) + đo hiệu năng thật, công bố minh bạch** | Biến "chưa đo" (khoảng trống đã xác nhận trong báo cáo AI) thành lợi thế truyền thông thực chất — đặc biệt có giá trị vì phần lớn đối thủ VN (MISA/Base/1Office) hiện chỉ dùng tuyên bố marketing, không có số liệu kiểm định độc lập; nếu FAMS làm bài bản, đây là điểm khác biệt dễ chứng minh |
| 🟡 5 | **Quyết định định vị rõ ràng về phần cứng chuyên dụng** (có hỗ trợ terminal/camera cố định hay giữ mobile-first thuần) | Không phải "thiếu" theo nghĩa xấu, nhưng cần quyết định chủ động — nếu nhắm phân khúc nhà máy/công trường lớn (nơi ZKTeco/Hikvision đang mạnh), cần ít nhất hỗ trợ tích hợp với thiết bị chấm công vật lý có sẵn của khách hàng qua API, không nhất thiết tự sản xuất phần cứng |
| 🟡 6 | **Chứng nhận bảo mật/tuân thủ chính thức** (ISO/IEC 27001 hoặc tương đương) | Giúp cạnh tranh ở phân khúc doanh nghiệp lớn, nơi yếu tố này thường là điều kiện đấu thầu bắt buộc — độ ưu tiên thấp hơn vì tốn thời gian/chi phí, nên làm sau khi các hạng mục kỹ thuật cốt lõi (1-4) đã hoàn thiện |

---

## Nguồn tham khảo

- [ZKBio Time](https://www.zkteco.com/en/ZKBio_Time/ZKBioTime), [ZKBio Time API](https://www.zkteco.com/en/ZKBioTime_API), [Facial Recognition Attendance](https://www.zkteco.in/face-attendance), [ZKBio CVSecurity API](https://www.zkteco.com/en/ZKBio_CVSecurity_API)
- [Hikvision Face Recognition Terminals](https://www.hikvision.com/en/products/Access-Control-Products/Face-Recognition-Terminals/), [Hikvision Access Control Guide — Swiftlane](https://swiftlane.com/blog/hikvision-access-control-guide/)
- [How DingTalk Face Check-in Works](https://www.dingtalk-macau.com/en/explain/how-dingtalk-face-checkin-works-in-macau-260118), [DingTalk Smart Attendance](https://www.dingtalk-global.com/news/activity/tan-suo-ding-ding-de-ling-huo-kao-qin-jie-jue-fang-an-zhao-dao-zui-shi-he-ni-de-da-ka-fang-250529-en), [DingTalk Best 10 Attendance Management](https://www.dingtalk.io/blog/best-10-attendance-management/)
- [Deputy Employee Time Clock](https://www.deputy.com/features/employee-time-clock), [Deputy Review — Connecteam](https://connecteam.com/reviews/deputy/), [Deputy for ADP Marketplace](https://apps.adp.com/en-US/apps/98199/deputy-for-adp-workforce-now-and-adp-workforce-now-essential-time/features)
- [Connecteam GPS Time Clock Solutions](https://connecteam.com/gps-time-clock-solutions/), [Connecteam Review — OnTheClock](https://www.ontheclock.com/blog/connecteam-review), [Connecteam Review — Buddy Punch](https://buddypunch.com/blog/connecteam-review/)
- [MISA AMIS Chấm công khuôn mặt AI](https://amis.misa.vn/132747/cham-cong-khuon-mat-ai/), [MISA AMIS chấm công GPS](https://amis.misa.vn/95758/tinh-nang-cham-cong-gps/), [AMIS Chấm công](https://amis.misa.vn/amis-cham-cong/)
- [Base HRM+](https://signup.base.vn/base-hrm/), [Top 10 phần mềm chấm công GPS — Nhanh.vn](https://nhanh.vn/cham-cong-bang-gps-la-gi-top-10-phan-mem-cham-cong-dinh-vi-hieu-qua-nhat-n165277.html)
- [1Office phần mềm chấm công điện thoại](https://1office.vn/phan-mem-cham-cong-tren-dien-thoai), [Top 7 phần mềm tính lương 2026](https://hoanghamobile.com/tin-tuc/phan-mem-tinh-luong/)
- [Selfie Attendance App — Waggex](https://www.waggex.com/selfie-attendance), [Buddy Punching Prevention — ShiftFlow](https://www.shiftflow.app/blog/prevent-buddy-punching)

*Đối chiếu nội bộ FAMS dựa trên*: `bao-cao-kien-truc-kha-nang-chiu-tai-2026-08-05.md`, `bao-cao-kien-truc-ai-faceid-2026-08-05.md`, `docs/api/face-id-management-api.md`, mã nguồn `ai-service/` và `api-server/src/main/java/com/fams/modules/employee/`, `randomcheck/`.
