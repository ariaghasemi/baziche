# 📥 اطلاعات نهایی که از تو لازم دارم (تنها فایل ورودی کاربر)

> همه‌ی کدها (فاز ۱ تا ۱۰ + مهاجرت Storage به GitHub) نوشته و تست شده (۸۸ تست
> بک‌اند سبز). APK دیباگ هم بدون هیچ‌کدام از این‌ها کار می‌کند
> (ادیتور/پری‌ویو/بازی آفلاین). موارد زیر فقط برای **سرویس واقعی**
> (لاگین، بیلد ابری، خرید، هوش مصنوعی) لازم است.
>
> ⚠️ قانون امنیت: **توکن/کلید/پسورد را هرگز در چت نفرست.** فقط با دستورهای زیر
> مستقیم در Cloudflare/GitHub ست کن و در چت فقط بگو «انجام شد». چیزهایی که امن
> است در چت بفرستی (آدرس ورکر، نام ریپو) با ✅ مشخص شده.

## USER ACTION REQUIRED — مهاجرت Storage به GitHub (بخوان اول!)

به‌خاطر خطای `10042` اکانت Cloudflare تو (R2 فعال نمی‌شود)، باینری‌ها فعلاً در
**GitHub Releases** ریپوی خودت ذخیره می‌شوند و D1 فقط metadata نگه می‌دارد.
کاره‌ای که **تو واقعاً باید انجام بدهی**:

1. ریپو را **private** نگه دار (ریلیزها و Runها عمومی نشوند).
2. یک Fine-grained PAT بساز (فقط همین ریپو؛ **Contents: Read and write** +
   **Actions: Read and write**) و با `wrangler secret put GITHUB_DISPATCH_TOKEN`
   در Worker ست کن — **نه در کد، نه در چت.**
3. `GITHUB_REPO = "ariaghasemi/baziche"` را در `[vars]` همین `wrangler.toml`
   بگذار (از قبل `BINARY_STORAGE = "github"` هست).
4. ورک‌فلوی `infrastructure` را دستی اجرا کن (Actions → infrastructure →
   Run workflow): دیتابیس‌ها + ۴ ریلیز Storage + مایگریشن + دیپلوی خودکار است.
5. تست واقعی کاربر: ثبت‌نام → ساخت بازی → آپلود asset → ذخیره → بستن →
   بازکردن → Preview → بیلد → دانلود و نصب APK.
6. ❌ **هیچ باکت R2 نساز**، هیچ سکرت `R2_*` ست نکن (فقط برای rollback آینده‌اند).

بقیه‌ی این فایل، همان قدم‌ها با جزئیات است.

## قدم ۰) GitHub — ریپوی private (انجام شده ✅)

ریپو ساخته شده: `ariaghasemi/baziche`. ورک‌فلوها از قبل در ریپو هستند:

- `android-ci.yml` — با هر push روی `android/` (APK دیباگ)
- `backend-ci.yml` — تست‌های بک‌اند
- `game-build.yml` — فقط با دستور سرور (دستی اجرا نکن)؛ دوحالته github/r2
- `infrastructure.yml` — پروویژن D1 + ریلیزهای Storage + مایگریشن + دیپلوی

## قدم ۱) Cloudflare — فقط دیتابیس‌ها (بدون R2!)

```bash
npm i -g wrangler        # اگر نداری
wrangler login           # با مرورگر وارد اکانتت شو
cd backend
npm run db:create        # دو دیتابیس می‌سازد: baziche-auth و baziche-data
```

4. خروجی `db:create` دو تا `database_id` می‌دهد → در `backend/wrangler.toml`
   جای `00000000-0000-0000-0000-000000000000` بگذار (دو جا).
5. ❌ باکت R2 نساز (حالت github نیازی ندارد؛ ورک‌فلوی infrastructure هم دیگر
   باکت نمی‌سازد).
6. مایگریشن و دیپلوی:
```bash
npm run db:migrate       # اجرای 001/002/003/004 روی هر دو دیتابیس
wrangler deploy          # آدرس ورکر را می‌دهد: https://baziche-api.<user>.workers.dev
```
7. ✅ در چت بفرست: **آدرس ورکر** (همان URL مرحله‌ی قبل).

## قدم ۲) سکرت‌های اجباری سرور (با CLI، نه چت!)

```bash
cd backend
openssl rand -hex 32 | wrangler secret put JWT_SECRET
openssl rand -hex 32 | wrangler secret put PASSWORD_PEPPER
# GitHub: ریپو → Settings → Developer settings → Personal access tokens →
# Fine-grained (فقط همین ریپو) با Contents RW + Actions RW بساز، بعد:
wrangler secret put GITHUB_DISPATCH_TOKEN
wrangler deploy                          # اعمال نهایی
```

و در `backend/wrangler.toml` بخش `[vars]` (غیرسکرت، الان هم هست):

```toml
BINARY_STORAGE = "github"
GITHUB_REPO = "ariaghasemi/baziche"
```

## قدم ۳) سکرت‌های اختیاری (هر کدام را که می‌خواهی)

| قابلیت | چه کار کنی |
|---|---|
| 💳 خرید مایکت | طبق `docs/MYKET_SETUP.md`: اپ را در پنل مایکت ثبت کن، ۵ SKU را بساز، `X-Access-Token` را بگیر → `wrangler secret put MYKET_ACCESS_TOKEN`. اگر packageName غیر از `com.baziche.app` است در `[vars]`: `MYKET_PACKAGE = "..."`. |
| ✨ گسترش با هوش مصنوعی | از OpenAI (یا هر سرویس OpenAI-compatible) کلید بگیر → `wrangler secret put AI_API_KEY`. اگر پروکسی داری در `[vars]`: `AI_BASE_URL` و `AI_MODEL`. |

(بیلد ابری دیگر «اختیاری» نیست — در قدم ۲ فعال شد، چون Storage هم همان توکن است.)

## قدم ۴) اولین ادمین + چک نهایی

```bash
# ۱. در اپ ثبت‌نام کن، بعد user_id را از GET /api/v1/me بردار
# ۲. ادمینش کن:
wrangler d1 execute baziche-auth --remote --command \
  "INSERT INTO admins (user_id, role, created_at) VALUES ('<user_id>', 'admin', strftime('%s','now'));"
```

- چک سلامت: مرورگر → `https://<worker>/health` و `/api/v1/meta/metrics`
- کنسول ادمین: `https://<worker>/admin/ui?token=<access-token>` (توکن را از لاگین اپ بگیر)
- بکاپ شبانه خودکار است (۰۳:۰۰ UTC) در ریلیز `baziche-storage-backups`؛
  چیزی برای فعال‌سازی لازم نیست.
- تست آپلود/دانلود: یک asset آپلود کن و URL دانلودش را در مرورگر بی‌توکن باز کن.

## قدم ۵) APK متصل به سرور تو (آخرین قدم، با من)

APK فعلی به لوکال‌هاست وصل است. وقتی قدم ۱ تمام شد:

8. ✅ در چت بفرست: **آدرس ورکر**.
9. من با `-PBAZICHE_API_URL=https://<worker>/api/v1/` ری‌بیلد می‌کنم و APK نهایی را می‌دهم.

## چک‌لیست برگشتی (فقط همین‌ها را در چت بفرست)

- [ ] ✅ آدرس ورکر (`https://...`)
- [ ] ✅ «دیتابیس‌ها ساخته و migrate شد + infrastructure اجرا شد» یا نه
- [ ] ✅ کدام اختیاری‌ها فعال شود: مایکت / هوش مصنوعی
- [ ] ✅ اگر مایکت: فایل AAR کتابخانه‌ی مایکت + packageName نهایی (AAR را همین‌جا آپلود کن تا در `:core:billing` سیم‌کشی کنم)
- [ ] (امضای release در v1 لازم نیست — بیلدهای رایگان APK دیباگ می‌دهند)
