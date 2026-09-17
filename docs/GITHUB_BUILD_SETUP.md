# 🏭 قرارداد بیلد بازی با GitHub Actions (فاز ۵ + Storage v2)

> وضعیت: **FUNCTIONAL** — `.github/workflows/game-build.yml` واقعی است و با هر دو
> حالت Storage کار می‌کند (`storage` در payload نسخه ۲ انتخاب می‌کند).
> این سند قرارداد دقیق بین Backend و Runner است.

## ایده (۳۰ ثانیه‌ای)

بک‌اند به‌جای داشتن سرور بیلد، برای هر بیلد یک **بسته رمزنگاری‌شده** می‌سازد:

```text
bundle.zip (AES-256) = game.json + manifest.json + assets/ (+ release: keystore.jks + signing.json)
```

بعد به GitHub می‌گوید «این بسته را بگیر، بساز، APK/AAB را آپلود کن، نتیجه را
خبر بده». هیچ Credential ابری در GitHub ذخیره نمی‌شود.

## ورودی‌های workflow_dispatch

| Input | نوع | توضیح |
|---|---|---|
| `payload` | JSON string | فیلد `storage` + مرجع‌های انتقال + کانفیگ بیلد (جدول بعد) |
| `bundle_key` | string (secret) | پسورد AES بسته — بلافاصله mask می‌شود |
| `callback_token` | string (secret) | Bearer کال‌بک به بک‌اند — بلافاصله mask می‌شود |

فیلدهای `payload` — حالت **github** (MVP):

```jsonc
{
  "storage": "github",
  "releaseTag": "baziche-storage-builds",   // ریلیز Worker-managed (rollover با -2، -3…)
  "bundleAsset": "build-<id>-bundle.enc",   // دانلود با: gh release download
  "bundleSha256": "hex…",
  "apkAsset": "build-<id>-game.apk",        // ("" اگر target=aab)
  "aabAsset": "build-<id>-game.aab",        // ("" اگر target=apk یا mode=debug)
  "logAsset": "build-<id>-build.log",       // آپلود با: gh release upload --clobber
  "callbackUrl": "https://api…/api/v1/builds/<id>/callback",
  "appId": "com.example.game",
  "appName": "My Game",
  "versionCode": 1,
  "versionName": "1.0.0",
  "targetApi": 36,
  "minApi": 26,
  "shellRef": "git sha/tags — نسخه قفل‌شده game-shell",
  "mode": "debug"
}
```

فیلدهای `payload` — حالت **r2** (legacy/rollback):

```jsonc
{
  "storage": "r2",
  "bundleUrl": "https://…presigned GET…",
  "bundleSha256": "hex…",
  "apkUrl": "https://…presigned PUT…",
  "aabUrl": "https://…presigned PUT…",  // ("" اگر target=apk یا mode=debug)
  "logUrl": "https://…presigned PUT…",
  // … همان callbackUrl/appId/appName/versionCode/versionName/targetApi/minApi/shellRef/mode
}
```

> قانون: Runner فقط شاخه‌ی `storage` خودش را می‌خواند. در حالت github انتقال
> با `GITHUB_TOKEN` داخلی (`gh release download/upload`، دسترسی `contents: write`)
> انجام می‌شود و هیچ باینری از Worker عبور نمی‌کند.

## فرمت bundle.zip (داخل بسته رمزگشایی‌شده)

```text
game.json          # Project JSON نسخه‌دار (head revision)
manifest.json      # { buildId, projectId, rev, files[] }
assets/<assetId>   # فایل‌های بازی (تا سقف باندل)
keystore.jks       # فقط بیلد release (آینده)
signing.json       # فقط بیلد release (آینده)
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

بک‌اند اصالت را با `callback_token` یکتا هر بیلد بررسی می‌کند و در حالت
COMPLETED ابتدا **نشستن واقعی APK** را راستی‌آزمایی می‌کند
(حالت github: پیدا شدن asset در ریلیز؛ حالت r2: `head()`)؛
اگر APK نباشد، بیلد `FAILED/UPLOAD_MISSING` می‌شود و هزینه برمی‌گردد.

## امنیت

- باندل با AES-256-CBC + PBKDF2 رمز است؛ کلید فقط در حافظه Runner.
- توکن بک‌اند (`GITHUB_DISPATCH_TOKEN`) هرگز وارد payload، URL، لاگ یا Runner نمی‌شود.
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
| Release asset | ۱۰۰۰ asset/ریلیز، هر فایل < ۲ GiB | rollover خودکار `-2`/`-3`… نزدیک ۹۰۰؛ D1 مرجع هر فایل را دارد |

## چک‌لیست فعال‌سازی

```text
[x] android/game-shell ساخته شد
[x] Backend: POST /builds + dispatch + bundle رمزنگاری‌شده
[x] Backend: callback endpoint + توکن یک‌بارمصرف + راستی‌آزمایی APK
[x] game-build.yml دوحالته (github + r2) با payload نسخه ۲
[ ] Fine-grained PAT (Contents RW + Actions RW) → Secret در Cloudflare
[ ] اولین بیلد آزمایشی موفق + دانلود APK
```
