# 🏭 قرارداد بیلد بازی با GitHub Actions (فاز ۵)

> وضعیت: **DRAFT** — فایل `.github/workflows/game-build.yml` نوشته شده ولی تا فاز ۵ فعال نیست.
> این سند قرارداد دقیق بین Backend و Runner است تا فاز ۵ بدون ابهام اجرا شود.

## ایده (۳۰ ثانیه‌ای)

بک‌اند به‌جای داشتن سرور بیلد، برای هر بیلد یک **بسته رمزنگاری‌شده** می‌سازد:

```text
bundle.zip (AES-256) = game.json + assets/ + keystore.jks + signing.json
```

بعد به GitHub می‌گوید «این بسته را بگیر، بساز، APK/AAB را با این لینک‌ها آپلود کن، نتیجه را به من خبر بده». همه لینک‌ها **Presigned کوتاه‌عمر** هستند و هیچ Credential ابری در GitHub ذخیره نمی‌شود.

## ورودی‌های workflow_dispatch

| Input | نوع | توضیح |
|---|---|---|
| `payload` | JSON string | همه URLها + کانفیگ بیلد (جدول بعد) |
| `bundle_key` | string (secret) | پسورد AES بسته — بلافاصله mask می‌شود |
| `callback_token` | string (secret) | Bearer کال‌بک به بک‌اند — بلافاصله mask می‌شود |

فیلدهای `payload`:

```jsonc
{
  "bundleUrl": "https://…presigned GET…",
  "bundleSha256": "hex…",
  "apkUrl": "https://…presigned PUT…",
  "aabUrl": "https://…presigned PUT…",
  "logUrl": "https://…presigned PUT…",
  "callbackUrl": "https://api…/api/v1/builds/<id>/callback",
  "appId": "com.example.game",
  "appName": "My Game",
  "versionCode": 1,
  "versionName": "1.0.0",
  "targetApi": 36,
  "minApi": 26,
  "shellRef": "git sha/tags — نسخه قفل‌شده game-shell"
}
```

## فرمت bundle.zip (داخل بسته رمزگشایی‌شده)

```text
game.json          # Project JSON نسخه‌دار
assets/...         # فایل‌های بازی
keystore.jks       # Keystore همان پروژه (فقط همین بیلد)
signing.json       # { "alias": "...", "storePassword": "...", "keyPassword": "..." }
```

## قرارداد Callback

Runner بعد از اتمام (موفق یا ناموفق) POST می‌زند:

```http
POST {callbackUrl}
Authorization: Bearer {callback_token}
Content-Type: application/json

{"status":"COMPLETED","runId":"12345"}
{"status":"FAILED","runId":"12345"}
```

بک‌اند با `callback_token` یک‌بارمصرف هر بیلد، اصالت را بررسی می‌کند (Phase 5).

## امنیت

- باندل با AES-256-CBC + PBKDF2 رمز است؛ کلید فقط در حافظه Runner.
- Keystore فقط همان پروژه است، نه کلید مادر.
- بعد از هر Job، کل Workspace پاک می‌شود (`rm -rf` در گام Cleanup).
- لاگ‌ها ممکن است نام فایل‌ها را داشته باشند ولی هرگز پسورد/توکن (mask) ندارند.
- ریپو **Private** است تا Runها و آرتیفکت‌ها عمومی نباشند.

## محدودیت‌های صادقانه (Free)

| محدودیت | مقدار | اثر |
|---|---|---|
| دقیقه بیلد | ~۲۰۰۰/ماه (ریپو Private) | ≈ ۱۳۰–۴۰۰ بیلد/ماه (هر بیلد ۵–۱۵ دقیقه) |
| همزمانی | ۲۰ Job | صف بیلد در بک‌اند مدیریت می‌کند |
| سقف هر Job | ۶ ساعت | کافی (بیلدها چند دقیقه‌اند) |
| آرتیفکت | ۹۰ روز | ما همه‌چیز را در R2 نگه می‌داریم، نه گیت‌هاب |

## چک‌لیست فعال‌سازی (فاز ۵ — فعلاً هیچ‌کدام)

```text
[ ] android/game-shell ساخته شد
[ ] Backend: POST /builds + BuildProvider.dispatch
[ ] Backend: callback endpoint + توکن یک‌بارمصرف
[ ] Backend: ساخت bundle رمزنگاری‌شده + presign آپلودها
[ ] Fine-grained PAT گیت‌هاب (Actions:Write) → Secret در Cloudflare
[ ] اولین بیلد آزمایشی موفق + دانلود APK
```
