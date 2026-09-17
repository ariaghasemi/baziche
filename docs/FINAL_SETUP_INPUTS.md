# 📥 اطلاعات نهایی که از تو لازم دارم (تنها فایل ورودی کاربر)

> همه‌ی کدها (فاز ۱ تا ۱۰) نوشته و تست شده. APK دیباگ هم بدون هیچ‌کدام از این‌ها
> کار می‌کند (ادیتور/پری‌ویو/بازی آفلاین). موارد زیر فقط برای **سرویس واقعی**
> (لاگین، بیلد ابری، خرید، هوش مصنوعی) لازم است.
>
> ⚠️ قانون امنیت: **توکن/کلید/پسورد را هرگز در چت نفرست.** فقط با دستورهای زیر
> مستقیم در Cloudflare/GitHub ست کن و در چت فقط بگو «انجام شد». چیزهایی که امن
> است در چت بفرستی (آدرس ورکر، نام ریپو، نام باکت‌ها) با ✅ مشخص شده.

## قدم ۰) GitHub — فقط push (بدون هیچ سکرتی در گیت‌هاب!)

1. یک ریپوی **private** بساز و کل پوشه‌ی `baziche` را push کن.
2. همین. هر سه ورک‌فلو از قبل در ریپو هستند و کدی لازم نیست کپی کنی:
   - `android-ci.yml` — با هر push روی `android/` اجرا می‌شود (APK دیباگ می‌سازد)
   - `backend-ci.yml` — تست‌های بک‌اند
   - `game-build.yml` — فقط با دستور سرور اجرا می‌شود (دستی اجرا نکن)
3. ✅ در چت بفرست: **`owner/repo`** ریپو (مثلاً `ali/baziche`).

## قدم ۱) Cloudflare — دیتابیس‌ها و باکت‌ها

```bash
npm i -g wrangler        # اگر نداری
wrangler login           # با مرورگر وارد اکانتت شو
cd backend
npm run db:create        # دو دیتابیس می‌سازد: baziche-auth و baziche-data
```

4. خروجی `db:create` دو تا `database_id` می‌دهد → در `backend/wrangler.toml`
   جای `00000000-0000-0000-0000-000000000000` بگذار (دو جا).
5. سه باکت R2 بساز (داشبورد یا CLI):
   `baziche-projects` ، `baziche-assets` ، `baziche-builds`
6. مایگریشن و دیپلوی:
```bash
npm run db:migrate       # اجرای 001/002/003 روی هر دو دیتابیس
wrangler deploy          # آدرس ورکر را می‌دهد: https://baziche-api.<user>.workers.dev
```
7. ✅ در چت بفرست: **آدرس ورکر** (همان URL مرحله‌ی قبل).

## قدم ۲) سکرت‌های اجباری سرور (با CLI، نه چت!)

```bash
cd backend
openssl rand -hex 32 | wrangler secret put JWT_SECRET
openssl rand -hex 32 | wrangler secret put PASSWORD_PEPPER
# R2: داشبورد Cloudflare → R2 → Manage R2 API Tokens → یک توکن با دسترسی
# Object Read & Write روی هر سه باکت بساز، بعد:
wrangler secret put R2_ACCOUNT_ID        # همان Account ID اکانتت
wrangler secret put R2_ACCESS_KEY_ID
wrangler secret put R2_SECRET_ACCESS_KEY
wrangler deploy                          # اعمال نهایی
```

## قدم ۳) سکرت‌های اختیاری (هر کدام را که می‌خواهی)

| قابلیت | چه کار کنی |
|---|---|
| 🤖 بیلد ابری بازی | GitHub → Settings → Developer settings → Personal access tokens → **Tokens (classic)** با اسکوپ‌های `repo` و `workflow` بساز → `wrangler secret put GITHUB_DISPATCH_TOKEN`. بعد در `wrangler.toml` بخش `[vars]` اضافه کن: `GITHUB_REPO = "owner/repo"` (همان قدم ۰) و `wrangler deploy`. |
| 💳 خرید مایکت | طبق `docs/MYKET_SETUP.md`: اپ را در پنل مایکت ثبت کن، ۵ SKU را بساز، `X-Access-Token` را بگیر → `wrangler secret put MYKET_ACCESS_TOKEN`. اگر packageName غیر از `com.baziche.app` است در `[vars]`: `MYKET_PACKAGE = "..."`. |
| ✨ گسترش با هوش مصنوعی | از OpenAI (یا هر سرویس OpenAI-compatible) کلید بگیر → `wrangler secret put AI_API_KEY`. اگر پروکسی داری در `[vars]`: `AI_BASE_URL` و `AI_MODEL`. |

## قدم ۴) اولین ادمین + چک نهایی

```bash
# ۱. در اپ ثبت‌نام کن، بعد user_id را از GET /api/v1/me بردار
# ۲. ادمینش کن:
wrangler d1 execute baziche-auth --remote --command \
  "INSERT INTO admins (user_id, role, created_at) VALUES ('<user_id>', 'admin', strftime('%s','now'));"
```

- چک سلامت: مرورگر → `https://<worker>/health` و `/api/v1/meta/metrics`
- کنسول ادمین: `https://<worker>/admin/ui?token=<access-token>` (توکن را از لاگین اپ بگیر)
- بکاپ شبانه خودکار است (۰۳:۰۰ UTC)؛ چیزی برای فعال‌سازی لازم نیست.

## قدم ۵) APK متصل به سرور تو (آخرین قدم، با من)

APK فعلی به لوکال‌هاست وصل است. وقتی قدم ۱ تمام شد:

8. ✅ در چت بفرست: **آدرس ورکر + `GITHUB_REPO`** (اگر قدم ۳ را رفتی).
9. من با `-PBAZICHE_API_URL=https://<worker>/api/v1/` ری‌بیلد می‌کنم و APK نهایی را می‌دهم.

## چک‌لیست برگشتی (فقط همین‌ها را در چت بفرست)

- [ ] ✅ `owner/repo` گیت‌هاب
- [ ] ✅ آدرس ورکر (`https://...`)
- [ ] ✅ «دیتابیس‌ها + R2 ساخته و migrate شد» یا نه
- [ ] ✅ کدام اختیاری‌ها فعال شود: بیلد ابری / مایکت / هوش مصنوعی
- [ ] ✅ اگر مایکت: فایل AAR کتابخانه‌ی مایکت + packageName نهایی (AAR را همین‌جا آپلود کن تا در `:core:billing` سیم‌کشی کنم)
- [ ] (امضای release در v1 لازم نیست — بیلدهای رایگان APK دیباگ می‌دهند)
