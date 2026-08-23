# Hướng dẫn triển khai Production — Toàn bộ hệ thống FAMS

**Phạm vi:** Backend (Spring Boot), AI Service (Face ID/liveness), Database (PostgreSQL+PostGIS), Redis, Object Storage, Web Admin (Next.js), Mobile App (Expo/React Native).
**Đối tượng đọc:** DevOps/người triển khai lần đầu hệ thống ra môi trường thật.

> Tài liệu này trả lời "dùng gì, làm theo thứ tự nào" để đưa toàn bộ hệ thống lên production **ổn định**. Checklist chi tiết từng biến/permission/health-check khi bàn giao tenant đầu tiên nằm ở [`go-live-checklist.md`](./go-live-checklist.md) — dùng **cùng** với tài liệu này, không thay thế.

---

## 0. Bức tranh tổng thể — 4 repo, phải triển khai riêng

FAMS gồm 4 repo độc lập, build/deploy khác nhau:

| Repo | Vai trò | Công nghệ | Deploy như thế nào |
|---|---|---|---|
| `fams-backend-project` (repo này) | API chính + AI service + DB + Redis | Spring Boot 3 (Java 21), FastAPI (Python) | Docker Compose trên VPS/server riêng |
| `fams-front-web` (repo riêng) | Web Admin (HR/Company Admin/Platform Admin) | Next.js 16 (React 19, App Router) | Vercel **hoặc** Docker + Nginx cùng VPS |
| `fams-front-app-project` (repo riêng) | Mobile App (nhân viên chấm công, Face ID) | Expo 54 + React Native | EAS Build → App Store / Google Play |
| — | Reverse proxy + TLS | Nginx + Let's Encrypt | Cùng VPS với backend |

Không có "một lệnh deploy tất cả" vì Web/App là 2 repo tách biệt, khác vòng đời release (App qua store review, Web/Backend release liên tục). Coi đây là 3 pipeline độc lập, đồng bộ qua `.env`/biến môi trường (`APP_FRONTEND_URL`, `NEXT_PUBLIC_API_URL`, `AI_INTERNAL_SECRET`...), không qua chung 1 script.

**Kiến trúc runtime (production):**

```
Internet
   │
   ▼
[Nginx :443 — TLS termination]
   ├─ app.fams.example.com   → fams-front-web (Next.js, cổng nội bộ 3000)
   ├─ api.fams.example.com   → fams-api (Spring Boot, cổng nội bộ 8080)
   │                              │
   │                              ├─ fams-postgres (PostGIS) — KHÔNG expose ra internet
   │                              ├─ fams-redis — KHÔNG expose ra internet
   │                              ├─ fams-ai (FastAPI) — KHÔNG expose port, chỉ nội bộ fams-net
   │                              └─ S3/MinIO hoặc AWS S3 thật — ảnh đại diện, tài liệu
   └─ (Mobile App gọi thẳng api.fams.example.com qua HTTPS, không qua Nginx path riêng)
```

---

## 1. Chọn mô hình hạ tầng

Với quy mô hiện tại của dự án (1 backend monolith + AI service phụ trợ), **Docker Compose trên 1-2 VPS là đủ và nên dùng** — đừng nhảy thẳng lên Kubernetes trừ khi đã có nhiều tenant lớn cần auto-scale theo giờ cao điểm. Compose đã có sẵn (`docker-compose.yml`, `docker-compose.full.yml`) và đúng là quy trình chính thức của dự án cho cả dev lẫn prod (`make prod`/`make full-d` dùng image build sẵn, không mount source).

| Quy mô | Khuyến nghị |
|---|---|
| 1 VPS, ít tenant, ngân sách hạn chế | **1 VPS** chạy toàn bộ stack full (Postgres + Redis + AI + API + Nginx) |
| Tenant tăng, cần tách tải AI (GPU) khỏi API | **2 server**: 1 server API+DB+Redis, 1 server AI (có GPU nếu cần tăng tốc face recognition) |
| Nhiều tenant lớn, cần HA/auto-scale | Cân nhắc Kubernetes/ECS sau — ngoài phạm vi tài liệu này, chỉ làm khi có nhu cầu thật |

**Sizing tối thiểu khuyến nghị cho 1 VPS full stack:** 4 vCPU / 8GB RAM / 60GB SSD (AI service với PyTorch+TensorFlow ăn RAM nhiều nhất khi load model lần đầu — dưới 8GB dễ bị OOM-kill khi vừa chạy API vừa chạy AI).

---

## 2. Chuẩn bị hạ tầng trước khi deploy

1. **Server**: VPS Ubuntu 22.04+ LTS, cài Docker 24+ và Docker Compose v2 (`docker compose version`).
2. **Domain + DNS**: tối thiểu 2 subdomain trỏ về IP server:
   - `api.fams.<domain>` → backend
   - `app.fams.<domain>` → web admin (nếu tự host, không dùng Vercel)
3. **Firewall**: chỉ mở 22 (SSH, khuyến nghị đổi port + key-only), 80, 443. **Không** mở 5432/6379/8080/9000/9001 ra internet — Postgres/Redis/MinIO/API port nội bộ chỉ nên truy cập qua Nginx hoặc VPN/SSH tunnel khi cần debug.
4. **Object storage**: quyết định trước — dùng AWS S3 thật (khuyến nghị production) hay MinIO tự host. Nếu dùng S3 thật, tạo bucket + IAM user quyền tối thiểu (`PutObject`/`GetObject`/`DeleteObject` trên đúng bucket), điền `S3_ENDPOINT` rỗng trong `.env` (theo comment sẵn có trong `.env.example`).
5. **Firebase project**: tạo project thật (không dùng chung project dev), tải service account JSON cho `FCM_SERVICE_ACCOUNT_JSON`, bật Phone Auth nếu dùng OTP.
6. **Gmail App Password** riêng cho production (không dùng chung tài khoản dev).

---

## 3. Triển khai Backend (API + AI + DB + Redis)

### 3.1 Clone và cấu hình

```bash
git clone <repo-backend-url> /opt/fams-backend
cd /opt/fams-backend
cp .env.example .env
```

Điền `.env` theo đúng bảng ở [`go-live-checklist.md` mục 1](./go-live-checklist.md#1-biến-môi-trường-env--bắt-buộc-đầy-đủ-trước-khi-khởi-động) — **đặc biệt chú ý**:

- `SPRING_PROFILES_ACTIVE=prod` (không để `dev`).
- `JWT_SECRET`, `TOTP_ENCRYPTION_KEY`, `AI_INTERNAL_SECRET`, `NOTIFICATIONS_INTERNAL_SECRET`, `REDIS_PASSWORD`, `DB_PASSWORD` — tất cả sinh mới bằng `openssl rand -hex 32`, **khác hoàn toàn** giá trị dev/`.env.example`.
- `APP_FRONTEND_URL=https://app.fams.<domain>` — sai giá trị này làm link trong email (mời nhân viên, reset password) không mở được.
- `CORS_ALLOWED_ORIGIN_PATTERNS=https://app.fams.<domain>` — bỏ hết các pattern `localhost`/LAN IP của dev.

### 3.2 Chạy stack production

Dùng biến thể **không có** `docker-compose.dev.yml` (không mount source, không auto-seed demo data):

```bash
# Full stack (API + AI + DB + Redis) — cần nếu dùng Face ID/liveness
docker compose -f docker-compose.full.yml up --build -d

# Hoặc chỉ Java (không có Face ID)
docker compose -f docker-compose.yml up --build -d
```

Tương đương `make full-d` / `make prod` — dùng Makefile cho ngắn gọn nếu muốn.

Flyway migration chạy tự động khi `fams-api` khởi động. **Không chạy `scripts/seed.sh`** trên production (script này chỉ tạo dữ liệu demo cho dev/staging).

### 3.3 Xác nhận health

```bash
curl https://api.fams.<domain>/api/v1/auth/health
# Kỳ vọng: "FAMS Auth Module is running"
```

Sau khi có tài khoản Platform Admin, gọi `GET /api/v1/platform/system-status` và xác nhận **tất cả** đều `UP` (`db`, `redis`, `fcm`, `aiService`, mọi `jobs[]`) — chi tiết ở go-live-checklist mục 4.

---

## 4. Reverse proxy + TLS (Nginx + Let's Encrypt)

File `docker/nginx/nginx.conf` trong repo hiện **chưa có nội dung** — cần điền trước khi dùng, hoặc chạy Nginx trực tiếp trên host (khuyến nghị đơn giản hơn cho 1 VPS, không thêm 1 container nữa vào `fams-net`).

### 4.1 Cài Nginx + Certbot trên host

```bash
sudo apt update && sudo apt install -y nginx certbot python3-certbot-nginx
```

### 4.2 Config reverse proxy cho API (`/etc/nginx/sites-available/fams-api`)

```nginx
server {
    listen 80;
    server_name api.fams.example.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 20m;   # đủ cho upload ảnh Face ID/tài liệu
    }
}
```

### 4.3 Config reverse proxy cho Web Admin (nếu tự host, không dùng Vercel)

```nginx
server {
    listen 80;
    server_name app.fams.example.com;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/fams-api /etc/nginx/sites-enabled/
sudo ln -s /etc/nginx/sites-available/fams-web /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx

# Bật HTTPS, tự động sửa config thêm redirect 80→443 + auto-renew
sudo certbot --nginx -d api.fams.example.com -d app.fams.example.com
```

Certbot tự thêm cron/systemd timer renew — kiểm tra bằng `sudo certbot renew --dry-run`.

---

## 5. Database — migration, backup, khôi phục

- **Migration**: Flyway tự chạy khi `fams-api` start (`api-server/src/main/resources/db/migration/`). Không can thiệp thủ công vào `flyway_schema_history` trừ khi biết chắc đang làm gì.
- **Backup tự động** — thêm cron job trên host chạy `pg_dump` hằng ngày, đẩy ra nơi khác VPS (S3/off-site), ví dụ:

```bash
# /opt/fams-backend/scripts/backup-db.sh
#!/bin/bash
set -euo pipefail
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
docker exec fams-postgres pg_dump -U "$DB_USER" "$DB_NAME" | gzip > "/opt/backups/fams_${TIMESTAMP}.sql.gz"
# Đẩy lên S3/off-site + xoá backup local quá 14 ngày
aws s3 cp "/opt/backups/fams_${TIMESTAMP}.sql.gz" "s3://fams-backups/"
find /opt/backups -name "*.sql.gz" -mtime +14 -delete
```

```bash
crontab -e
# 0 2 * * * /opt/fams-backend/scripts/backup-db.sh >> /var/log/fams-backup.log 2>&1
```

- **Khôi phục** (thử trên staging trước khi cần thật trên prod):

```bash
gunzip -c fams_20260821_020000.sql.gz | docker exec -i fams-postgres psql -U "$DB_USER" "$DB_NAME"
```

- **PostGIS**: image `postgis/postgis:16-3.4` đã có extension sẵn qua `docker/postgres/init-postgis.sql` — không cần cài thêm.
- Volume dữ liệu (`fams_postgres_data`, `fams_redis_data`, `minio_data` nếu dùng) nằm trên Docker volume — đảm bảo phân vùng đĩa chứa `/var/lib/docker` được backup/snapshot ở tầng hạ tầng (VPS snapshot) như một lớp bảo vệ bổ sung, không thay thế `pg_dump` logic backup ở trên.

---

## 6. Triển khai Web Admin (`fams-front-web`, Next.js 16)

### Phương án A — Vercel (khuyến nghị, đơn giản nhất, có CDN/edge sẵn)

1. Import repo `fams-front-web` vào Vercel.
2. Set biến môi trường trên Vercel dashboard: `NEXT_PUBLIC_API_URL=https://api.fams.example.com` (và các biến khác theo `.env.example` của repo web — kiểm tra trong repo đó).
3. Domain: gắn `app.fams.example.com` vào project Vercel, Vercel tự cấp TLS.
4. Backend cần cho phép origin này trong `CORS_ALLOWED_ORIGIN_PATTERNS` — nhưng lưu ý README ghi rõ: Web Admin gọi API **same-origin qua Next.js rewrite proxy**, nên phần lớn trường hợp không cần CORS cross-origin thật, chỉ cần đúng `NEXT_PUBLIC_API_URL`/rewrite target.

### Phương án B — Tự host cùng VPS (Docker)

```bash
git clone <repo-web-url> /opt/fams-front-web
cd /opt/fams-front-web
# Build production
docker build -t fams-web .
docker run -d --name fams-web --restart unless-stopped \
  -p 127.0.0.1:3000:3000 \
  -e NEXT_PUBLIC_API_URL=https://api.fams.example.com \
  fams-web
```

Nginx trỏ vào `127.0.0.1:3000` như mục 4.3. Deploy lại = `git pull && docker build ... && docker restart fams-web` (hoặc dựng CI/CD ở mục 8).

---

## 7. Triển khai Mobile App (`fams-front-app-project`, Expo 54)

Mobile App không "deploy lên server" — build ra file cài đặt rồi cài trực tiếp lên máy hoặc nộp lên store. **Không liên quan gì tới IP máy bạn** — đó chỉ là đặc điểm của chế độ dev (Expo Go), không phải cách app chạy khi đã build.

### 7.0 Phân biệt 3 chế độ chạy — đọc trước khi làm bất cứ gì

| Chế độ | Dùng khi | Cần Expo Go? | Cần máy bạn bật/cùng wifi? | Chạy được native module (Firebase Auth...)? |
|---|---|---|---|---|
| `npx expo start` (Expo Go, quét QR) | Dev nhanh phần UI thuần JS | Có | **Có** — đây là lý do IP đổi liên tục | **Không** |
| **EAS dev-client** (`--profile development`) | Dev/test có dùng native module, chưa cần build lại mỗi lần sửa code JS | Không (dùng app dev-client tự build riêng, thay thế Expo Go) | Không — dev-client tự kết nối qua Expo server, không cần LAN | **Có** |
| **EAS production/preview build** (`--profile production`/`preview`) | Cài lên máy thật để dùng như app thật — đây là cái bạn cần bây giờ | Không | **Không** — chạy độc lập hoàn toàn | **Có** |

Bạn đang hỏi "chạy trên máy, chưa cần đẩy store" → dùng **EAS build với distribution nội bộ** (`internal`), không phải Expo Go, không phải nộp store. Làm theo các bước dưới.

### 7.1 Cấu hình `eas.json` — định nghĩa các profile build

Trong repo `fams-front-app-project`, file `eas.json` (tạo bằng `eas build:configure` nếu chưa có) cần tối thiểu:

```json
{
  "cli": { "version": ">= 5.0.0" },
  "build": {
    "development": {
      "developmentClient": true,
      "distribution": "internal",
      "android": { "buildType": "apk" }
    },
    "preview": {
      "distribution": "internal",
      "android": { "buildType": "apk" },
      "ios": { "simulator": false }
    },
    "production": {
      "autoIncrement": true,
      "android": { "buildType": "app-bundle" },
      "ios": {}
    }
  },
  "submit": {
    "production": {}
  }
}
```

- **`development`**: build app dev-client (thay Expo Go), cài 1 lần, sau đó sửa code JS chỉ cần `eas update` hoặc chạy Metro dev server bình thường, không cần build lại mỗi lần.
- **`preview`**: **đây là profile bạn cần** — build gần giống production (đóng cứng API URL thật, không mount code), nhưng cài trực tiếp qua link tải, **không qua store**.
- **`production`**: dùng khi thật sự nộp store (`app-bundle` cho Android bắt buộc với Play Store, `apk` không nộp được).

### 7.2 Cấu hình biến môi trường trỏ đúng API production TRƯỚC khi build

Biến này bị **đóng cứng vào bundle lúc build** — sai là phải build lại từ đầu, không sửa được sau khi đã có file cài đặt:

```bash
# .env hoặc app.config.ts của repo app — theo đúng profile sắp build
EXPO_PUBLIC_API_URL=https://api.fams.example.com
```

Nếu muốn build thử nhắm vào backend đang chạy trên máy/VPS staging (chưa có domain thật), dùng IP/domain của **server đó** (server đứng yên, có IP cố định hoặc domain), không phải IP máy laptop cá nhân — nguyên tắc vẫn giữ: app cần gọi tới 1 địa chỉ ổn định, không phải địa chỉ hay đổi.

### 7.3 Build — cài trực tiếp lên máy, chưa cần store

```bash
npm install -g eas-cli
eas login
```

**Android — dễ nhất, làm trước:**

```bash
eas build --platform android --profile preview
```

Build xong, terminal in ra 1 link dạng `https://expo.dev/artifacts/eas/xxxxx.apk`. Trên điện thoại Android:
1. Mở link đó bằng trình duyệt điện thoại (hoặc quét QR mà `eas build` cũng hiển thị kèm link).
2. Tải file `.apk`.
3. Lần đầu cài app không qua Play Store, Android sẽ hỏi "Cài từ nguồn không xác định" → bấm cho phép cho đúng app trình duyệt/file đang dùng để cài.
4. Cài xong, mở app — chạy độc lập, không cần máy bạn bật, không cần Expo Go.

**iOS — phức tạp hơn vì Apple giới hạn cài app ngoài store:**

iOS **bắt buộc** phải đăng ký UDID của từng thiết bị test trước khi build ad-hoc (khác Android — không có khái niệm "tải .apk cài tự do"). Cần có **Apple Developer Program** ($99/năm) dù chỉ test nội bộ, chưa nộp App Store:

```bash
# Đăng ký thiết bị iPhone sẽ cài app thử (chạy 1 lần/thiết bị mới)
eas device:create
# EAS in ra 1 link, mở link đó TRÊN CHÍNH IPHONE cần đăng ký → cài 1 profile nhỏ →
# UDID của máy được ghi nhận vào tài khoản Apple Developer của bạn

# Build ad-hoc, ký với đúng danh sách thiết bị đã đăng ký
eas build --platform ios --profile preview
```

Build xong, link EAS trả về cho phép cài trực tiếp qua Safari trên đúng chiếc iPhone đã đăng ký UDID ở bước trên — iPhone chưa đăng ký UDID sẽ **không cài được** dù có link, đây là giới hạn của Apple chứ không phải lỗi cấu hình.

> Nếu chỉ có Android để test trước mắt, có thể bỏ qua bước iOS ở giai đoạn này và quay lại khi cần test đa nền tảng.

### 7.4 Native module cần build lại — nhắc lại

Dự án đã ghi nhận (BACKLOG.md): OTP điện thoại dùng `@react-native-firebase/auth` — **native module**, không chạy trên Expo Go. Với `preview`/`production` build thì không vấn đề gì (đã build sẵn native code vào app) — chỉ cần lưu ý khi dev hằng ngày nên dùng **EAS dev-client** (profile `development` ở mục 7.1) thay vì Expo Go để test được đúng luồng OTP thật, tránh nhầm lẫn "sao Expo Go không chạy được OTP" (đây là giới hạn cố hữu của Expo Go, không phải bug).

### 7.5 Cập nhật app sau khi đã cài (không build lại từ đầu)

Với thay đổi **chỉ ở code JS** (sửa UI, sửa logic, không thêm permission/native module mới):

```bash
eas update --branch preview --message "fix: ..."
```

App đã cài trên máy tự động kiểm tra và tải bản cập nhật này ở lần mở tiếp theo — **không cần build lại `.apk`/`.ipa`, không cần cài lại**, và không liên quan gì tới máy bạn có đang bật hay không (bản cập nhật nằm trên server Expo, luôn online). Chỉ khi đổi native code (thêm quyền camera mới, thêm thư viện native khác) mới cần lặp lại bước 7.3 (build lại + cài lại thủ công qua link mới).

### 7.6 Khi nào mới cần đẩy lên store thật (chưa cần ngay)

```bash
eas build --platform android --profile production
eas build --platform ios --profile production
eas submit --platform android
eas submit --platform ios
```

Chỉ làm bước này khi cần phát hành công khai cho người dùng ngoài (không thể gửi link cài thủ công cho hàng trăm/nghìn người) — cần trước: Google Play Console developer account, Apple Developer Program, app đã tạo listing trên cả 2 store. Ở giai đoạn hiện tại (đang tự chạy thử trên máy), **không cần bước này**.

---

## 8. CI/CD — tự động hoá deploy

Khuyến nghị GitHub Actions riêng cho từng repo, kích hoạt khi merge vào `main`/`develop`:

**Backend** (`.github/workflows/deploy.yml` trong `fams-backend-project`):
```yaml
name: Deploy Backend
on:
  push:
    branches: [main]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Deploy via SSH
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.PROD_HOST }}
          username: ${{ secrets.PROD_USER }}
          key: ${{ secrets.PROD_SSH_KEY }}
          script: |
            cd /opt/fams-backend
            git pull origin main
            docker compose -f docker-compose.full.yml up --build -d
```

**Web** (`fams-front-web`): nếu dùng Vercel, deploy tự động khi merge — không cần workflow riêng. Nếu tự host, dùng pattern SSH tương tự backend.

**Mobile App**: dùng `eas build` + `eas submit` trong GitHub Actions với `EXPO_TOKEN` secret, kích hoạt thủ công (`workflow_dispatch`) vì build store cần review thủ công trước khi submit — không nên tự động submit store trên mỗi merge.

Chạy test trước deploy trong cùng pipeline (`tests/run_all.sh` cho backend) — chặn deploy nếu test fail.

---

## 9. Monitoring, Logging, Alerting

- **Health check**: `GET /api/v1/platform/system-status` (đã có sẵn, dùng cho go-live checklist) — có thể poll định kỳ (cron + curl + alert qua Slack/Telegram webhook nếu có field `DOWN`).
- **Container logs**: `docker compose logs -f fams-api` — cân nhắc thêm `docker compose` logging driver đẩy log ra file có xoay vòng (`json-file` với `max-size`/`max-file`) để tránh đầy đĩa:

```yaml
# thêm vào từng service trong docker-compose override cho prod
logging:
  driver: json-file
  options:
    max-size: "50m"
    max-file: "5"
```

- **Delivery log thông báo**: theo dõi `GET /api/v1/platform/notifications/delivery-logs?status=FAILED` tuần đầu sau go-live (đã ghi trong go-live-checklist mục 7).
- **Uptime bên ngoài**: dùng dịch vụ ping ngoài (UptimeRobot/Better Stack, miễn phí) point vào `https://api.fams.example.com/api/v1/auth/health` — phát hiện downtime kể cả khi Nginx/DNS lỗi, không chỉ container lỗi.

---

## 10. Trước khi bàn giao khách hàng thật

Sau khi hoàn tất mục 1-9, **bắt buộc** chạy qua toàn bộ [`go-live-checklist.md`](./go-live-checklist.md) — đặc biệt:

- Toàn bộ biến `.env` production đã đúng (mục 1 checklist).
- `GET /api/v1/platform/system-status` mọi thứ `UP` (mục 4).
- Test nhanh RBAC + data masking (mục 5).
- Chạy trọn luồng UAT **B.8** (`docs/testing/manual-test-scenarios.md`) trên chính môi trường production/staging trước khi mở cho khách hàng thật.

---

## 11. Rollback nhanh khi có sự cố sau deploy

```bash
# Backend: quay lại image/commit trước đó
cd /opt/fams-backend
git log --oneline -5          # tìm commit trước
git checkout <commit-truoc>
docker compose -f docker-compose.full.yml up --build -d

# Database: nếu migration mới gây lỗi dữ liệu, khôi phục từ backup gần nhất (mục 5)
# — KHÔNG tự sửa flyway_schema_history thủ công nếu không chắc chắn.
```

Vì `docker compose up -d <service>` (không phải `restart`) mới nạp lại biến môi trường mới — nhớ dùng đúng lệnh này mỗi khi đổi `.env`, kể cả khi rollback code không đổi env.

---

## 12. Hiện trạng khả năng chịu tải — vì sao chưa vội nhảy lên K8s

> Nguồn: [`bao-cao-kien-truc-kha-nang-chiu-tai-2026-08-05.md`](../reports/bao-cao-kien-truc-kha-nang-chiu-tai-2026-08-05.md) — báo cáo audit hiện trạng thật trong code/config, đọc trước khi làm phần này.

Trước khi thiết kế phần Kubernetes/load-test, cần hiểu rõ **hệ thống hiện tại giới hạn ở đâu trước tiên** — không phải "cần K8s vì hệ thống lớn", mà vì các điểm nghẽn cụ thể sau đây sẽ chặn hệ thống lại rất lâu trước khi số instance API là vấn đề:

| Điểm nghẽn | Hiện trạng | Ảnh hưởng khi tải tăng |
|---|---|---|
| HikariCP pool | `maximum-pool-size: 10` mỗi instance API | >10 truy vấn DB đồng thời → xếp hàng, timeout 30s → lỗi 500 |
| Redis | `maxmemory 256mb`, `allkeys-lru` | Đầy bộ nhớ → tự evict **cả token blacklist** → rủi ro bảo mật (token đã revoke tạm thời hợp lệ lại) |
| AI face-verify worker | 1 thread, xử lý tuần tự, không retry/DLQ | Spike checkin (đầu ca) → verify khuôn mặt trễ hàng phút, dồn ứ hàng đợi Redis list |
| Load balancer | `docker/nginx/nginx.conf` rỗng, chưa wire vào compose nào | Không thể chạy nhiều instance API — mọi traffic dồn vào 1 container |
| Rate limiting | Chỉ có ở OTP/reset password/liveness — phần lớn endpoint (danh sách, báo cáo, checkin) không giới hạn | Dễ bị client lỗi/bot làm nghẽn DB trực tiếp |
| Circuit breaker | Không có giữa Java↔AI/Redis/DB | AI service down → request đồng bộ (enroll/status) chờ timeout 30s thay vì fail nhanh |
| Backup | Không có `pg_dump`/snapshot tự động trong repo (đã lấp ở mục 5 của guide này) | Rủi ro mất dữ liệu vĩnh viễn nếu volume hỏng |

**Kết luận thực dụng**: scale ngang (nhiều instance API + LB) và tăng Hikari pool/Redis memory giải quyết được phần lớn tải trước khi cần K8s. K8s chỉ thật sự cần khi đã vượt qua giới hạn của "vài container Docker Compose + scale thủ công" — tức là cần **auto-scale theo thời gian thực** hoặc **high availability đa node** mà 1-2 VPS không đáp ứng nổi. Roadmap ở mục 15 phản ánh đúng thứ tự ưu tiên này.

---

## 13. Kiểm thử tải & đo hiệu năng (Load Testing)

Hệ thống **chưa từng được benchmark ở mức HTTP req/s** — chỉ có bộ dữ liệu khối lượng lớn (`scripts/seed_perf.sql`: 150 tenant, ~23.302 nhân viên, ~1,1 triệu dòng checkin) dùng để test tốc độ truy vấn/phân trang qua UI, không đo throughput. Đây là bước **bắt buộc phải làm trước khi cam kết SLA hoặc quyết định có cần K8s hay không** — đừng scale hạ tầng dựa trên phỏng đoán.

### 13.1 Công cụ khuyến nghị: k6 (nhẹ, script JS, dễ tích hợp CI)

```bash
# Cài k6 trên máy chạy test (không chạy trên chính server production)
sudo apt install -y gnupg2 curl
curl -s https://dl.k6.io/key.gpg | sudo apt-key add -
echo "deb https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt update && sudo apt install -y k6
```

### 13.2 Kịch bản test — mô phỏng đúng luồng nghiệp vụ tải cao nhất: chấm công đầu ca

Đây là kịch bản thật sự đáng lo (nhiều nhân viên checkin cùng lúc 7-8h sáng), không phải test tải chung chung:

```js
// scripts/load-test/checkin-spike.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 50 },   // ramp-up
    { duration: '2m', target: 200 },   // giữ 200 user đồng thời — mô phỏng đầu ca
    { duration: '30s', target: 0 },    // ramp-down
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<3000'],  // ngưỡng chấp nhận được, điều chỉnh theo SLA thật muốn cam kết
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.API_URL || 'https://staging-api.fams.example.com';

export default function () {
  const loginRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    email: __ENV.TEST_EMAIL, password: __ENV.TEST_PASSWORD,
  }), { headers: { 'Content-Type': 'application/json' } });
  check(loginRes, { 'login 200': (r) => r.status === 200 });
  const token = loginRes.json('data.accessToken');

  const checkinRes = http.post(`${BASE_URL}/api/v1/checkins`, JSON.stringify({
    siteId: __ENV.TEST_SITE_ID, latitude: 21.0285, longitude: 105.8542,
  }), { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } });
  check(checkinRes, { 'checkin 200/201': (r) => r.status === 200 || r.status === 201 });

  sleep(1);
}
```

```bash
API_URL=https://staging-api.fams.example.com \
TEST_EMAIL=... TEST_PASSWORD=... TEST_SITE_ID=... \
k6 run scripts/load-test/checkin-spike.js
```

**Nguyên tắc bắt buộc**: chạy trên **staging** với dữ liệu từ `seed_perf.sql` (không chạy nhắm vào production thật), theo dõi song song `docker stats`, log Hikari pool (`HikariPool-1 - Connection is not available, request timed out`), và Redis `INFO memory` trong lúc test để biết **đúng điểm nghẽn nào vỡ trước** (thường sẽ là Hikari pool = 10 trước khi CPU/RAM server bị đầy).

### 13.3 Việc cần làm sau khi có số liệu

1. Ghi lại p95/p99/req-per-second tối đa trước khi lỗi bắt đầu tăng — đây là **con số thật đầu tiên** dự án có, dùng làm baseline.
2. Tăng dần `hikari.maximum-pool-size` (theo `max_connections` của Postgres chia cho số instance dự kiến) và chạy lại test — xác nhận throughput tăng tương ứng.
3. Lặp lại test sau mỗi thay đổi hạ tầng lớn (thêm LB, thêm instance, đổi K8s) để so sánh — không đổi hạ tầng "cho chắc" mà không đo lại.

---

## 14. Giám sát hiệu năng nâng cao — Prometheus + Grafana

Hiện chỉ có Spring Boot Actuator (`/actuator/health`, `/actuator/info`) — không đủ để biết *tại sao* chậm khi có sự cố tải. Bổ sung observability thật trước khi hoặc song song với việc lên K8s.

### 14.1 Backend — thêm Micrometer + Prometheus registry

```xml
<!-- api-server/pom.xml -->
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
# application-prod.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    tags:
      application: fams-api
```

Việc này expose `/actuator/prometheus` — **không** để endpoint này lộ ra internet qua Nginx public; chỉ Prometheus (nội bộ `fams-net`/VPN) được gọi tới.

### 14.2 Stack Prometheus + Grafana (thêm vào `docker-compose.full.yml` dưới dạng override riêng cho monitoring, không sửa file gốc)

```yaml
# docker-compose.monitoring.yml
services:
  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./docker/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus_data:/prometheus
    networks: [fams-net]
    ports: ["127.0.0.1:9090:9090"]   # chỉ bind localhost — truy cập qua SSH tunnel

  grafana:
    image: grafana/grafana:latest
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}
    volumes:
      - grafana_data:/var/lib/grafana
    networks: [fams-net]
    ports: ["127.0.0.1:3001:3000"]

  alertmanager:
    image: prom/alertmanager:latest
    volumes:
      - ./docker/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
    networks: [fams-net]

volumes:
  prometheus_data:
  grafana_data:
```

```yaml
# docker/prometheus/prometheus.yml
scrape_configs:
  - job_name: fams-api
    metrics_path: /actuator/prometheus
    static_configs: [{ targets: ['fams-api:8080'] }]
  - job_name: fams-postgres-exporter   # cần thêm container postgres_exporter nếu muốn số liệu DB
    static_configs: [{ targets: ['postgres-exporter:9187'] }]
  - job_name: fams-redis-exporter      # tương tự, redis_exporter
    static_configs: [{ targets: ['redis-exporter:9121'] }]
```

```bash
docker compose -f docker-compose.full.yml -f docker-compose.monitoring.yml up -d
ssh -L 3001:localhost:3001 -L 9090:localhost:9090 user@prod-server   # truy cập Grafana/Prometheus qua tunnel, không public
```

Dashboard tối thiểu cần dựng trong Grafana: HikariCP pool usage (active/idle/pending connections), Redis memory + evicted keys/sec (theo dõi trực tiếp rủi ro evict blacklist ở mục 12), độ trễ hàng đợi `fams:ai:face_verify_jobs` (queue depth qua `LLEN` — cần custom exporter nhỏ hoặc script cron đẩy metric), request rate/latency/error rate theo endpoint.

### 14.3 Alerting tối thiểu

Cấu hình Alertmanager đẩy về Slack/Telegram webhook khi: `hikari_connections_pending > 0` kéo dài >30s, `redis_memory_used_bytes / redis_memory_max_bytes > 0.9`, `up{job="fams-api"} == 0` (downtime), job định kỳ (`ScheduledJobMonitor`) báo `ERROR`.

---

## 15. Kubernetes — khi nào cần và cách triển khai

### 15.1 Điều kiện nên chuyển sang K8s (không chuyển "cho chắc")

Chỉ bắt đầu phần này khi **ít nhất một** điều kiện sau xảy ra thật, dựa trên số liệu đo được ở mục 13-14, không phải dự đoán:

- Load test (mục 13) cho thấy 1 instance API đã bão hoà (CPU/pool DB nghẽn) ở mức tải **thực tế đang/sắp gặp** (không phải tải giả định).
- Cần **high availability thật** — downtime của 1 VPS/container không còn chấp nhận được (SLA khách hàng yêu cầu uptime cao).
- Cần **auto-scale theo giờ cao điểm** (VD: toàn bộ tenant checkin dồn 7-8h sáng, tải giảm mạnh buổi tối) — chạy cố định N instance 24/7 trên Compose lãng phí tài nguyên rõ rệt.
- Số lượng tenant/server đã lớn tới mức vận hành thủ công nhiều VPS Compose riêng lẻ trở nên khó quản lý hơn 1 cluster.

Nếu chưa tới các mốc này: **ở lại Docker Compose + nhiều instance sau LB (mục 15.2 dưới, làm trên Compose trước, không cần K8s)** — đúng theo khuyến nghị ưu tiên #3 trong báo cáo audit.

### 15.2 Bước đệm trước K8s: scale ngang ngay trên Docker Compose (rẻ, nhanh, nên làm trước)

JWT là stateless + Redis dùng chung → **chạy nhiều instance `fams-api` không cần sticky session**, khả thi ngay trên Compose:

```yaml
# docker-compose.full.yml — thêm deploy.replicas (cần Compose v2 + `docker compose up --scale`)
services:
  fams-api:
    # ... giữ nguyên
```

```bash
docker compose -f docker-compose.full.yml up -d --scale fams-api=3
```

Rồi hoàn thiện `docker/nginx/nginx.conf` làm load balancer trước container (round-robin mặc định của Nginx `upstream`):

```nginx
upstream fams_api_pool {
    least_conn;
    server fams-api:8080 max_fails=3 fail_timeout=10s;
    # với --scale, Docker DNS round-robin đã tự phân phối qua tên service — cấu hình này
    # chủ yếu cần khi muốn kiểm soát thuật toán LB tường minh hơn round-robin mặc định của Docker
}
```

Nhớ tăng `hikari.maximum-pool-size × số instance ≤ postgres max_connections` (mặc định Postgres 16 là 100) — đây là lỗi dễ gặp nhất khi scale ngang mà quên chỉnh.

### 15.3 Khi thật sự cần K8s — kiến trúc mục tiêu

```
┌─────────────────────────── Kubernetes Cluster ───────────────────────────┐
│                                                                            │
│  Ingress (nginx-ingress + cert-manager)                                  │
│    ├─ api.fams.example.com → Service fams-api (ClusterIP)                │
│    └─ app.fams.example.com → Service fams-web (nếu tự host, không Vercel)│
│                                                                            │
│  Deployment fams-api (HPA: 2-10 pod theo CPU + custom metric queue-depth)│
│  Deployment fams-ai   (HPA riêng — AI CPU-bound khác pattern với API)    │
│  Deployment fams-web  (nếu tự host)                                      │
│                                                                            │
│  ConfigMap/Secret — biến môi trường (Secret cho JWT_SECRET/DB_PASSWORD...)│
│                                                                            │
└────────────────────────────────────────────────────────────────────────┘
        │                              │
        ▼                              ▼
  Managed PostgreSQL              Managed Redis
  (AWS RDS/GCP Cloud SQL —        (AWS ElastiCache/GCP Memorystore —
   KHÔNG tự chạy Postgres          KHÔNG tự chạy Redis trong
   trong pod K8s — mất backup/     pod K8s cho production)
   PITR tự động nếu tự vận hành)
```

**Nguyên tắc quan trọng nhất**: **không** tự chạy PostgreSQL/Redis trong pod Kubernetes cho production — StatefulSet + PVC vẫn phải tự lo backup/failover/PITR, phức tạp hơn nhiều so với dùng managed database service (RDS/Cloud SQL/ElastiCache) vốn đã có backup tự động, multi-AZ, patching. Đây là lý do sizing/chi phí K8s luôn phải tính kèm chi phí managed DB, không tính riêng.

### 15.4 Manifest tối thiểu — `fams-api` Deployment + HPA

```yaml
# k8s/fams-api-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: fams-api
spec:
  replicas: 2
  selector:
    matchLabels: { app: fams-api }
  template:
    metadata:
      labels: { app: fams-api }
    spec:
      containers:
        - name: fams-api
          image: <registry>/fams-api:<tag>
          ports: [{ containerPort: 8080 }]
          envFrom:
            - secretRef: { name: fams-api-secrets }
            - configMapRef: { name: fams-api-config }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 60
            periodSeconds: 15
          resources:
            requests: { cpu: "500m", memory: "768Mi" }
            limits: { cpu: "1500m", memory: "1536Mi" }
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: fams-api-hpa
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: fams-api }
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 65 } }
```

**Lưu ý bắt buộc phải sửa trước khi dùng**: `management.endpoint.health.probes.enabled=true` và cấu hình `readiness`/`liveness` health group riêng trong `application-prod.yml` — hiện `/actuator/health` gộp chung tất cả indicator (kể cả `fcm`), nên nếu Firebase credential lỗi, K8s sẽ hiểu nhầm là pod chết và loop restart liên tục dù API vẫn phục vụ được request bình thường (đúng như bug đã ghi nhận với `docker-compose.dev.yml` trong báo cáo audit — phải sửa tận gốc trước khi đưa lên K8s, không chỉ workaround ở tầng compose như hiện tại).

### 15.5 Scale AI service trên K8s — giải quyết nút cổ chai 1-thread

Vì `BRPOP` trên Redis list an toàn cho multi-consumer, chạy nhiều pod `fams-ai` cùng đọc chung hàng đợi là khả thi **nhưng** cần giải quyết trước 2 việc (đã nêu trong báo cáo audit mục 8):

1. Ảnh Face ID hiện lưu **local disk container** (`STORAGE_BASE_PATH=/app/storage`) — phải chuyển sang S3-compatible object storage trước khi chạy nhiều pod (nhiều pod không thể dùng chung 1 đĩa cục bộ).
2. Cân nhắc thay Redis list thô bằng Redis Streams (consumer group) để có retry/ack — tránh 1 pod chết giữa chừng làm mất job đang xử lý.

### 15.6 Managed Kubernetes nên dùng

| Nhà cung cấp | Dịch vụ | Khi nào chọn |
|---|---|---|
| AWS | EKS + RDS + ElastiCache | Đã dùng AWS S3 cho avatar, hệ sinh thái đồng bộ |
| GCP | GKE + Cloud SQL + Memorystore | Muốn autopilot mode (Google quản lý node), giảm gánh vận hành |
| DigitalOcean | DOKS + Managed PostgreSQL/Redis | Ngân sách nhỏ hơn, đơn giản hơn AWS/GCP, đủ cho quy mô vừa |

Không tự dựng cluster K8s bằng `kubeadm` trên VPS tự thuê cho production trừ khi có người vận hành K8s kinh nghiệm sẵn trong đội — chi phí vận hành (control plane, etcd, upgrade, security patching) tự làm cao hơn nhiều so với chênh lệch giá managed K8s.

---

## 16. Lộ trình triển khai đầy đủ (Roadmap theo giai đoạn)

Thứ tự dưới đây bám sát mức độ ưu tiên thật trong báo cáo audit — **không nhảy cóc lên K8s (giai đoạn 4) trước khi hoàn thành giai đoạn 1-2**, vì phần lớn vấn đề tải hiện tại được giải quyết rẻ hơn nhiều ở giai đoạn 1-2.

| Giai đoạn | Mục tiêu | Việc cụ thể | Điều kiện chuyển giai đoạn tiếp theo |
|---|---|---|---|
| **0 — Hiện tại** | Chạy demo/dev | Docker Compose 1 instance mỗi service | Đã hoàn thành |
| **1 — Production nền tảng** (mục 1-11 guide này) | Go-live an toàn cho khách hàng đầu tiên | Deploy Compose prod, Nginx+TLS, backup `pg_dump` tự động, deploy Web/App, chạy go-live-checklist | Có khách hàng thật đang dùng ổn định |
| **2 — Quan sát được hệ thống** | Biết hệ thống đang khoẻ hay không, thay vì đoán | Prometheus+Grafana (mục 14), alerting tối thiểu, tăng `maxmemory` Redis / tách blacklist khỏi policy LRU, bật rate limit chung (không chỉ vài endpoint) | Có dashboard + alert hoạt động, không còn "khoảng trống rủi ro cao nhất" (backup) trong báo cáo audit |
| **3 — Đo tải thật** | Có con số thật thay vì phỏng đoán | Chạy k6 load test (mục 13) trên staging với `seed_perf.sql`, xác định điểm vỡ đầu tiên (thường là Hikari pool) | Có báo cáo p95/p99/req-s làm baseline, biết rõ ngưỡng tải hiện tại |
| **4 — Scale ngang trên Compose** | Tăng sức chịu tải mà chưa cần K8s | Hoàn thiện `nginx.conf` làm LB thật, chạy nhiều instance `fams-api` (mục 15.2), scale nhiều `fams-ai` sau khi chuyển ảnh sang S3 | Load test lại — nếu vẫn nghẽn dù đã scale ngang tối đa hạ tầng hiện có, hoặc cần auto-scale/HA thật → sang giai đoạn 5 |
| **5 — Kubernetes** (mục 15) | Auto-scale, HA đa node | Chuyển sang managed K8s + managed DB/Redis, viết Deployment/HPA, chuyển hàng đợi face-verify sang Redis Streams/broker thật | Vận hành ổn định, có observability đầy đủ trên cluster mới |
| **6 — Tối ưu sâu** (tuỳ nhu cầu thực đo được) | Giải quyết coupling còn lại | PgBouncer/read replica cho báo cáo, tách module tải cao (dashboard/report/notification) nếu số liệu thật cho thấy cần, circuit breaker Java↔AI | Chỉ làm khi có số liệu chứng minh cần thiết — tránh over-engineering |

---

## Tóm tắt "dùng gì"

| Thành phần | Công cụ |
|---|---|
| Container hoá backend/AI/DB/Redis | Docker + Docker Compose (đã có sẵn trong repo) |
| Reverse proxy + TLS | Nginx + Let's Encrypt (Certbot) |
| Web Admin | Vercel (khuyến nghị) hoặc Docker tự host |
| Mobile App | EAS Build + EAS Submit + EAS Update (OTA) |
| CI/CD | GitHub Actions (SSH deploy cho backend/self-host web; EAS cho app) |
| Backup DB | `pg_dump` qua cron + đẩy S3 off-site |
| Monitoring cơ bản | `GET /platform/system-status` + uptime checker ngoài (UptimeRobot) |
| Monitoring nâng cao | Prometheus + Grafana + Alertmanager (mục 14) |
| Load testing | k6, kịch bản checkin-spike trên staging + `seed_perf.sql` (mục 13) |
| Scale ngang (trước K8s) | Nhiều instance `fams-api` trên Compose sau Nginx LB (mục 15.2) |
| Orchestration khi cần auto-scale/HA thật | Kubernetes (EKS/GKE/DOKS) + managed PostgreSQL/Redis (mục 15) |
| Bảo mật/checklist go-live | [`go-live-checklist.md`](./go-live-checklist.md) |
| Hiện trạng khả năng chịu tải (nguồn số liệu) | [`bao-cao-kien-truc-kha-nang-chiu-tai-2026-08-05.md`](../reports/bao-cao-kien-truc-kha-nang-chiu-tai-2026-08-05.md) |
