# 🖥️ راهنمای محیط توسعه (قدم‌به‌قدم)

> هدف: اجرای کامل بازیچه روی کامپیوتر خودت **بدون نیاز به هیچ اکانت و هیچ هزینه‌ای**.

## مرحله ۰ — نصب ابزارها

| ابزار | نسخه لازم | از کجا |
|---|---|---|
| Node.js | 22 LTS | https://nodejs.org (گزینه LTS را دانلود و نصب کن) |
| JDK | 17 (Temurin) | https://adoptium.net/temurin/releases (نسخه 17، سیستم‌عامل خودت) |
| Android Studio | جدیدترین پایدار | https://developer.android.com/studio |
| Git | جدید | https://git-scm.com/downloads |

بررسی نصب (در ترمینال/PowerShell):

```bash
node --version     # باید v22.x باشد
npm --version
java -version      # باید 17 باشد
git --version
```

## مرحله ۱ — اجرای بک‌اند محلی

```bash
cd baziche/backend
npm install
cp .dev.vars.example .dev.vars
```

حالا فایل `.dev.vars` را باز کن و دو مقدار بساز (فقط برای کامپیوتر خودت):

```bash
# در لینوکس/مک:
openssl rand -hex 32   # → بگذار جای JWT_SECRET
openssl rand -hex 32   # → بگذار جای PASSWORD_PEPPER
```

```powershell
# در ویندوز (PowerShell) به‌جای openssl:
-join ((1..32) | ForEach-Object { '{0:x2}' -f (Get-Random -Max 256) })
```

سپس:

```bash
npm run db:local   # ساخت دیتابیس محلی + migration
npm run dev        # اجرا روی http://localhost:8787
```

تست سلامت: مرورگر را باز کن و برو به `http://localhost:8787/health` — باید `{"success":true,"status":"ok"}` ببینی.

تست‌ها:

```bash
npm test        # باید هر ۲۰ تست سبز شود
npm run typecheck
```

## مرحله ۲ — اجرای اپ اندروید

1. Android Studio را باز کن → **Open** → پوشه `baziche/android` را انتخاب کن.
2. صبر کن Gradle Sync تمام شود (اولین بار چند دقیقه طول می‌کشد و اینترنت می‌خواهد).
3. یک Emulator بساز (Device Manager → Create Device → مثلاً Pixel + API 34+).
4. Emulator را اجرا کن، بعد دکمه ▶ **Run** را بزن (ماژول `app`).

اپ به‌صورت خودکار در حالت Debug به `http://10.0.2.2:8787/api/v1` وصل می‌شود — یعنی همان بک‌اند محلی مرحله ۱ (باید روشن باشد).

## مرحله ۳ — سناریوی تست دستی

1. در اپ **ثبت‌نام** کن (شماره ۰۹…، یوزرنیم، رمز ۸+ کاراکتر).
2. داشبورد را ببین (اشتراک FREE + بیلد رایگان Available).
3. **پروژه جدید** بساز (نوع Quiz).
4. پروژه را باز کن (مشخصات + JSON).
5. به تنظیمات برو، زبان را عوض کن، خروج و ورود مجدد.

## عیب‌یابی

| مشکل | راه‌حل |
|---|---|
| `port 8787 busy` | یک `wrangler dev` دیگر روشن است؛ آن را ببند |
| اپ به API وصل نمی‌شود | بک‌اند محلی روشن است؟ روی Emulator واقعی (نه دستگاه فیزیکی) اجرا می‌کنی؟ |
| Gradle Sync خطا می‌دهد | اتصال اینترنت + JDK 17 را بررسی کن؛ `File → Invalidate Caches` |
| تست بک‌اند قرمز | `node --version` باید ۲۲ باشد؛ `npm install` را دوباره بزن |

## متغیرهای محیط

| فایل | کاربرد | کامیت در Git؟ |
|---|---|---|
| `backend/.dev.vars` | سکرت‌های محلی دولوپر | ❌ هرگز |
| `backend/.dev.vars.example` | قالب | ✅ |
| `backend/wrangler.toml` | کانفیگ غیرحساس | ✅ |
| `~/.gradle/gradle.properties` | `BAZICHE_API_URL` شخصی (اختیاری) | ❌ (خارج از ریپو) |

## Android SDK: platform-37 alias (Sept 2026, temporary)

Stable androidx libraries (Compose 1.12.1, core 1.19.0, lifecycle 2.11.0,
navigation 2.10.1) declare `minCompileSdk 37`, but Google has not published
`platforms;android-37` yet (404 + absent from `repository2-1.xml` as of
2026-09-16). So `compileSdk = 37` is satisfied with an alias:

```bash
cp -r $ANDROID_HOME/platforms/android-36 $ANDROID_HOME/platforms/android-37
```

Our code calls no API-37 symbols (minSdk 26, plain Canvas/Compose APIs), and
androidx guards new-API paths with `SDK_INT` checks, so the alias is safe for
debug builds. When Google publishes the real platform, replace the alias:

```bash
rm -rf $ANDROID_HOME/platforms/android-37
sdkmanager "platforms;android-37"
```

CI (`game-build.yml`, Phase 5) must include the same alias step until then.
