# بازیچه — BAZICHE

**Android Game Builder بدون کدنویسی.** کاربر بازی می‌سازد، Preview می‌گیرد، APK/AAB واقعی تحویل می‌گیرد.

> 📐 سند معماری مادر (خواندن اول): [`BAZICHE_MASTER_ARCHITECTURE.md`](BAZICHE_MASTER_ARCHITECTURE.md) — نسخه ۱.۱، نهایی

## ساختار ریپو

```text
android/    اپ Native (Kotlin + Compose) — app + core/*  (editor/runtime/game-shell در فازهای بعد)
backend/    API روی Cloudflare Workers (Hono + TS + D1 + R2)
shared/     قراردادها: project-format.schema.json، api-v1.openAPI.yaml، error-codes.md، registries
.github/    CI (اندروید + بک‌اند) + game-build.yml (ورک‌فلوی بیلد بازی — DRAFT فاز ۵)
docs/       راهنماهای قدم‌به‌قدم محیط/کلادفلر/گیت‌هاب/سکرت‌ها (فارسی)
```

## شروع سریع (Local Dev — بدون نیاز به هیچ اکانتی، رایگان)

```bash
# 1) Backend (http://localhost:8787)
cd backend
npm install
npm run db:local        # ساخت دیتابیس محلی + اجرای migration 001 + seed
npm run dev             # wrangler dev (local D1 + R2)
npm test                # تست‌ها (vitest + شبیه‌ساز واقعی D1)

# 2) Android (Emulator)
# اندروید استودیو → باز کردن پوشه android/ → اجرا روی Emulator
# حالت Debug به‌صورت پیش‌فرض به http://10.0.2.2:8787/api/v1 وصل می‌شود (بک‌اند محلی بالا)
```

جزئیات قدم‌به‌قدم: [`docs/ENVIRONMENT_SETUP.md`](docs/ENVIRONMENT_SETUP.md)

## وضعیت فازها (صادقانه)

| فاز | وضعیت | خروجی |
|---|---|---|
| PHASE 1 | ✅ پیاده‌سازی‌شده (این نسخه) | Shell + Auth + Backend Core + D1/R2 + Project Core + Admin skeleton + Registries + CI |
| PHASE 2 | ⬜ NOT IMPLEMENTED | Sync کامل + Editor MVP |
| PHASE 3 | ⬜ NOT IMPLEMENTED | Runtime Core |
| PHASE 4 | ⬜ NOT IMPLEMENTED | ۸ Game Type اجرایی + Template |
| PHASE 5 | 🟨 DRAFT | `game-build.yml` نوشته شده؛ فعال‌سازی با game-shell |
| PHASE 6–10 | ⬜ NOT IMPLEMENTED | طبق نقشه معماری |

## قوانین امنیتی (خلاصه)

- هیچ Secret در کد/Git/APK/چت. جدول سکرت‌ها: [`docs/SECRETS.md`](docs/SECRETS.md)
- اگر سکرتی لو رفت: Rotate/Revoke کن، بعد ادامه بده.
- اندروید فقط با API حرف می‌زند؛ هرگز مستقیم به D1/R2.
