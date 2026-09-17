# ☁️ راهنمای ساخت Cloudflare (قدم‌به‌قدم)

> چه زمانی؟ وقتی خواستی API واقعی روی اینترنت داشته باشی (برای تست محلی لازم نیست).
> هزینه: **۰** — همه‌چیز در Free Tier جا می‌شود.
> زمان تقریبی: ۳۰ دقیقه.

## پیش‌نیاز

- یک ایمیل معتبر (برای حساب Cloudflare).
- Node 22 + کد پروژه (مرحله ENVIRONMENT_SETUP انجام شده).

## گام ۱ — ساخت حساب

1. برو به https://dash.cloudflare.com/sign-up
2. با ایمیلت ثبت‌نام کن و ایمیل را تأیید کن.
3. وارد داشبورد شو: https://dash.cloudflare.com

> ⚠️ فقط **یک اکانت** — همین کافی است. ساخت اکانت دوم برای دور زدن سقف رایگان، خلاف قوانین Cloudflare است و باعث بن شدن می‌شود. طراحی ما عمداً تک‌اکانتی است.

## گام ۲ — ورود Wrangler (اتصال ترمینال به حسابت)

در پوشه `backend`:

```bash
npx wrangler login
```

مرورگر باز می‌شود → **Allow** را بزن. پیام موفقیت در ترمینال می‌آید.

## گام ۳ — ساخت دیتابیس‌های D1 (دو تا، در همین یک اکانت)

بازیچه هویت (کاربر/سشن/خرید) را از دیتای بازی (پروژه/نسخه/است) جدا نگه می‌دارد — مثل دو کشوی یک کمد که با یک کلید (همین Worker) باز می‌شوند:

```bash
npx wrangler d1 create baziche-auth
npx wrangler d1 create baziche-data
```

خروجی موفق برای هر کدام شبیه این است:

```text
✅ Successfully created DB 'baziche-auth'
database_id = "a1b2c3d4-...."
```

**هر دو `database_id` را کپی کن** و در فایل `backend/wrangler.toml` بگذار:
- اولی → بخش `[[d1_databases]]` با `binding = "DB_AUTH"`
- دومی → بخش `[[d1_databases]]` با `binding = "DB_DATA"`

```bash
npm run db:migrate
```

خروجی موفق: هر دو migration اعمال می‌شود (`001_init.sql` در auth و data). این دستور جدول‌ها + پلن‌ها + شارد را می‌سازد.

> ✅ SAFE TO SEND: اسم دیتابیس‌ها و `database_id`ها را می‌توانی برای من بفرستی (سکرت نیست).
> ❌ چیزی برای فرستادن نیست: پسورد/توکنی در کار نیست.

## گام ۴ — باکت R2 لازم نیست (حالت github)

در معماری MVP (`BINARY_STORAGE=github` در `wrangler.toml`) باینری‌ها در
GitHub Releases ذخیره می‌شوند و **هیچ باکت R2 لازم نیست**.
ریلیزها با ورک‌فلوی `infrastructure.yml` ساخته می‌شوند (خودکار).

> اگر روزی خواستی به R2 برگردی (rollback): باکت‌های
> `baziche-projects` / `baziche-assets` / `baziche-builds` را بساز،
> سه بایندینگ `[[r2_buckets]]` را به `wrangler.toml` برگردان و
> `BINARY_STORAGE=r2` بگذار — بدون مایگریشن دیتا.

## گام ۵ — توکن GitHub برای Storage و بیلد (اجباری در حالت github)

`GITHUB_DISPATCH_TOKEN` در حالت github هم تریگر بیلد است و هم دسترسی
Storage به ریلیزها. یک Fine-grained PAT بساز:

1. GitHub → Settings → Developer settings → Personal access tokens → **Fine-grained**.
2. Repository access: فقط همین ریپو (`ariaghasemi/baziche`).
3. Permissions: **Contents: Read and write** + **Actions: Read and write**.
4. انقضا کوتاه بگذار و در تقویم برای چرخش یادداشت کن.

> ❌ NEVER SEND IN CHAT: توکن را برای هیچ‌کس (از جمله من در چت) نفرست.
> فقط با دستور گام ۶ داخل Secretهای Worker بگذار.

## گام ۶ — ثبت سکرت‌ها در Worker

```bash
npx wrangler secret put JWT_SECRET
# یک رشته تصادفی قوی بده (مثلاً خروجی: openssl rand -hex 32)
# (هم امضای Access Token است، هم امضای لینک‌های دانلود HMAC)

npx wrangler secret put PASSWORD_PEPPER
# یک رشته تصادفی قوی و متفاوت بده

npx wrangler secret put GITHUB_DISPATCH_TOKEN
# PAT گام ۵ را بده (تریگر بیلد + GitHub Release Storage)
```

و در `wrangler.toml` بخش `[vars]` (مقدار غیرسکرت):

```toml
GITHUB_REPO = "ariaghasemi/baziche"
```

## گام ۷ — دیپلوی

```bash
npm run deploy
```

خروجی موفق شامل یک URL است، مثل:

```text
https://baziche-api.<your-subdomain>.workers.dev
```

تست سلامت: `https://.../health` باید `{"success":true,"status":"ok"}` بدهد.

> ✅ SAFE TO SEND: این Worker URL را برای من بفرست تا کانفیگ اپ را با آن به‌روز کنم.
> جمله‌ای که بگویی: «دیپلوی شد: <URL>»

## گام ۸ — اتصال اپ به API واقعی (اختیاری، بعد از گام ۷)

```bash
cd android
./gradlew :app:assembleDebug -PBAZICHE_API_URL='https://baziche-api.<sub>.workers.dev/api/v1/'
```

یا برای همیشه در فایل `~/.gradle/gradle.properties` خودت (نه داخل ریپو):

```properties
BAZICHE_API_URL=https://baziche-api.<sub>.workers.dev/api/v1/
```

## گام ۹ — اولین ادمین (اختیاری)

اول در اپ **ثبت‌نام** کن، بعد `user_id` خودت را پیدا کن (لاگین کن و `GET /api/v1/me` را صدا بزن — مثلاً با مرورگر + توکن؟ ساده‌تر: موقتاً از داشبورد D1):

داشبورد → **Workers & Pages** → **D1** → `baziche-auth` → **Console** → اجرا:

```sql
SELECT id, username FROM users;
```

بعد:

```bash
npx wrangler d1 execute baziche-auth --remote \
  --command "INSERT INTO admins (user_id, role, created_at) VALUES ('<USER_ID>', 'admin', strftime('%s','now'))"
```

## چک‌لیست تأیید

```text
[ ] /health روی اینترنت جواب می‌دهد
[ ] ثبت‌نام از اپ (با API واقعی) موفق است
[ ] ساخت پروژه موفق است
[ ] /api/v1/meta/registries لیست ۲۰ گیم‌تایپ را می‌دهد
```
