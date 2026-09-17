# 📋 ران‌بوک عملیات بازیچه (RUNBOOK)

## ۱) دیپلوی

```bash
cd backend
wrangler d1 migrations apply baziche-auth --remote   # بعد wrangler d1 create (یک‌بار)
wrangler d1 migrations apply baziche-data --remote   # شامل 004_github_storage
wrangler deploy
```

- چک سلامت: `GET /health` → ok ، بعد `GET /api/v1/meta/metrics`.
- کرون بکاپ با همین دیپلوی فعال می‌شود (`[triggers]` در wrangler.toml).
- حالت Storage: `BINARY_STORAGE` در `[vars]` (`github` در MVP؛ `r2` فقط rollback).

## ۲) بکاپ و ری‌استور

- بکاپ خودکار هر شب ۰۳:۰۰ UTC در ریلیز `baziche-storage-backups`
  (`backup-d1-*.json.gz` + اشاره‌گر `latest.json`). مانیتور: فیلد `lastBackup` در `/metrics`.
- دانلود بکاپ (ریپو private؛ توکن جدا لازم نیست اگر به ریپو دسترسی داری):
  `gh release download baziche-storage-backups --pattern 'latest.json'`
  بعد `gh release download baziche-storage-backups --pattern 'backup-d1-*.json.gz'`
- ری‌استور (اضطراری، دیتابیس خالی/جدید):
  1. `gzip -dc backup-d1-*.json.gz | jq .tables` را بازبینی کن.
  2. جدول‌ها را به‌ترتیب والد→فرزند با `wrangler d1 execute <db> --remote --command` برگردان
     (`users` قبل از `subscriptions/purchases`؛ `projects` قبل از revisions/builds).
  3. سطرهای تکراری: اول `DELETE` هدف، بعد `INSERT` (بکاپ snapshot کامل است، نه افزایشی).
- ⚠️ بکاپ شامل هش پسورد است؛ ریپو را هرگز public نکن؛ ریلیزهای Storage را دستی پاک نکن
  (D1 مرجع `release_tag/asset_id` هر فایل را دارد).

## ۳) چرخش سکرت‌ها (نام‌ها در docs/SECRETS.md)

| سکرت | چرخش |
|---|---|
| `JWT_SECRET` | مقدار جدید → `JWT_SECRET_PREV`=قدیمی؛ بعد از ۱۵ دقیقه PREV را پاک کن (لینک‌های دانلود HMAC هم با همین امضا می‌شوند) |
| `PASSWORD_PEPPER` | فقط با re-hash برنامه‌ریزی‌شده (کاربران باید لاگین کنند) |
| `GITHUB_DISPATCH_TOKEN` | PAT جدید (Contents RW + Actions RW روی همین ریپو) → `wrangler secret put` |
| `R2_*` | فقط در حالت rollback لازم است؛ توکن R2 جدید بساز، قدیمی را بعد از اطمینان حذف کن |
| `MYKET_ACCESS_TOKEN` / `AI_API_KEY` | از پنل سرویس جدید بگیر و جایگزین کن |

## ۴) عیب‌یابی سریع

| علامت | اقدام |
|---|---|
| بیلدها در QUEUED می‌مانند | `GITHUB_DISPATCH_TOKEN`/`GITHUB_REPO` ست است؟ لاگ workflow در تب Actions؛ `error_code` سطر بیلد (DISPATCH_REJECTED = توکن/نام workflow؛ در حالت github توکن برای Storage هم لازم است) |
| callback بیلد 401 | `callback_token` سطر بیلد با ورودی workflow یکی است؟ (هر بیلد توکن خودش را دارد) |
| بیلد FAILED/UPLOAD_MISSING | Runner نتوانسته asset را آپلود کند: لاگ workflow؛ ریلیز `baziche-storage-builds` را ببین (asset با نام `build-<id>-game.apk` هست؟) |
| دانلود asset/build 401 | لینک HMAC منقضی شده — URL تازه از `:id/url` بگیر (عادی است) |
| خرید 402 می‌خورد | پنل مایکت: SKU فعال است؟ `X-Access-Token` همان اپ است؟ purchaseState در لاگ audit |
| `/ai/expand` گران شد | سقف ساعتی در کد ۲۰ است؛ جدول `ai_usage` را ببین؛ در صورت سوءاستفاده `RATE_LIMITED` جواب می‌دهد |
| بکاپ شبانه نیامد | dashboard → Workers → baziche-api → Triggers؛ `lastBackup` در `/metrics`؛ ریلیز `baziche-storage-backups` |
| دیتابیس کند شد | D1 محدودیت خواندن دارد؛ جدول `audit_logs` را دوره‌ای هرس کن (بکاپ قبلش) |

## ۵) اولین ادمین

```sql
-- user_id را از /api/v1/me بگیر، بعد:
INSERT INTO admins (user_id, role, created_at) VALUES ('<user_id>', 'admin', strftime('%s','now'));
```

اجرا: `wrangler d1 execute baziche-auth --remote --command "<SQL>"`.
بعد: `https://<worker>/admin/ui?token=<access-token>` (توکن را از لاگین اپ بگیر).
