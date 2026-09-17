# 📋 ران‌بوک عملیات بازیچه (RUNBOOK)

## ۱) دیپلوی

```bash
cd backend
wrangler d1 migrations apply baziche-auth --remote   # بعد wrangler d1 create (یک‌بار)
wrangler d1 migrations apply baziche-data --remote
wrangler deploy
```

- چک سلامت: `GET /health` → ok ، بعد `GET /api/v1/meta/metrics`.
- کرون بکاپ با همین دیپلوی فعال می‌شود (`[triggers]` در wrangler.toml).

## ۲) بکاپ و ری‌استور

- بکاپ خودکار هر شب ۰۳:۰۰ UTC در R2 (`baziche-builds/backups/YYYY-MM-DD/d1-*.json.gz`)
  + اشاره‌گر `backups/latest.json`. مانیتور: فیلد `lastBackup` در `/metrics`.
- دانلود بکاپ:
  `wrangler r2 object get baziche-builds/backups/latest.json --remote` (کلید واقعی را بده)
  بعد `wrangler r2 object get baziche-builds/<key> backup.json.gz --remote`
- ری‌استور (اضطراری، دیتابیس خالی/جدید):
  1. `gzip -dc backup.json.gz | jq .tables` را بازبینی کن.
  2. جدول‌ها را به‌ترتیب والد→فرزند با `wrangler d1 execute <db> --remote --command` برگردان
     (`users` قبل از `subscriptions/purchases`؛ `projects` قبل از revisions/builds).
  3. سطرهای تکراری: اول `DELETE` هدف، بعد `INSERT` (بکاپ snapshot کامل است، نه افزایشی).
- ⚠️ بکاپ شامل هش پسورد است؛ باکت را هرگز public نکن؛ دانلود فقط با توکن API.

## ۳) چرخش سکرت‌ها (نام‌ها در docs/SECRETS.md)

| سکرت | چرخش |
|---|---|
| `JWT_SECRET` | مقدار جدید → `JWT_SECRET_PREV`=قدیمی؛ بعد از ۱۵ دقیقه PREV را پاک کن |
| `PASSWORD_PEPPER` | فقط با re-hash برنامه‌ریزی‌شده (کاربران باید لاگین کنند) |
| `R2_*` | توکن R2 جدید بساز، قدیمی را بعد از اطمینان حذف کن |
| `GITHUB_DISPATCH_TOKEN` | PAT جدید (repo+workflow) → `wrangler secret put` |
| `MYKET_ACCESS_TOKEN` / `AI_API_KEY` | از پنل سرویس جدید بگیر و جایگزین کن |

## ۴) عیب‌یابی سریع

| علامت | اقدام |
|---|---|
| بیلدها در QUEUED می‌مانند | `GITHUB_DISPATCH_TOKEN`/`GITHUB_REPO` ست است؟ لاگ workflow در تب Actions؛ `error_code` سطر بیلد (DISPATCH_REJECTED = توکن/نام workflow) |
| callback بیلد 401 | `callback_token` سطر بیلد با ورودی workflow یکی است؟ (هر بیلد توکن خودش را دارد) |
| خرید 402 می‌خورد | پنل مایکت: SKU فعال است؟ `X-Access-Token` همان اپ است؟ purchaseState در لاگ audit |
| `/ai/expand` گران شد | سقف ساعتی در کد ۲۰ است؛ جدول `ai_usage` را ببین؛ در صورت سوءاستفاده `RATE_LIMITED` جواب می‌دهد |
| بکاپ شبانه نیامد | dashboard → Workers → baziche-api → Triggers؛ `lastBackup` در `/metrics` |
| دیتابیس کند شد | D1 محدودیت خواندن دارد؛ جدول `audit_logs` را دوره‌ای هرس کن (بکاپ قبلش) |

## ۵) اولین ادمین

```sql
-- user_id را از /api/v1/me بگیر، بعد:
INSERT INTO admins (user_id, role, created_at) VALUES ('<user_id>', 'admin', strftime('%s','now'));
```

اجرا: `wrangler d1 execute baziche-auth --remote --command "<SQL>"`.
بعد: `https://<worker>/admin/ui?token=<access-token>` (توکن را از لاگین اپ بگیر).
