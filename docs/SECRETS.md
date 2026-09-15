# 🔑 جدول سکرت‌های بازیچه

> این فایل فقط **نام** سکرت‌ها و **محل نگهداری** را مشخص می‌کند.
> **هیچ مقدار واقعی هرگز اینجا، در کد، در Git یا در چت قرار نمی‌گیرد.**

| Secret | مصرف‌کننده | محل نگهداری | روش ست کردن | چرخش |
|---|---|---|---|---|
| `JWT_SECRET` | Backend (امضای Access Token) | Cloudflare Secret + `.dev.vars` محلی | `wrangler secret put JWT_SECRET` | با `JWT_SECRET_PREV` بدون قطعی |
| `JWT_SECRET_PREV` | Backend (دوره چرخش) | Cloudflare Secret | `wrangler secret put JWT_SECRET_PREV` | بعد از ۱۵ دقیقه پاک شود |
| `PASSWORD_PEPPER` | Backend (هش نهایی پسورد) | Cloudflare Secret + `.dev.vars` محلی | `wrangler secret put PASSWORD_PEPPER` | نیازمند re-hash کاربران؛ با برنامه |
| `R2_ACCESS_KEY_ID` | Backend (امضای Presigned URL) | Cloudflare Secret | `wrangler secret put R2_ACCESS_KEY_ID` | ساخت توکن جدید R2 + حذف قدیمی |
| `R2_SECRET_ACCESS_KEY` | Backend (امضای Presigned URL) | Cloudflare Secret | `wrangler secret put R2_SECRET_ACCESS_KEY` | همراه بالایی |
| `GITHUB_DISPATCH_TOKEN` | Backend فاز ۵ (تریگر بیلد) | Cloudflare Secret | dashboard → secrets | Fine-grained PAT، اسکوپ Actions |
| `MYKET_ACCESS_TOKEN` | Backend فاز ۶ (تأیید خرید) | Cloudflare Secret | از پنل توسعه‌دهنده Myket | طبق پنل Myket |
| `SIGNING_MASTER_KEY` | Backend فاز ۵ (رمز Keystoreها) | Cloudflare Secret | `wrangler secret put` | با re-encrypt برنامه‌ریزی‌شده |
| `BUILD_AGENT_TOKEN` | Agent فاز Scale | VPS env (Docker secret) | فایل env روی سرور | چرخش دوره‌ای |
| Keystore هر پروژه | Build (امضای APK) | R2 خصوصی، رمزنگاری‌شده | خودکار در اولین بیلد | هرگز دستی دستکاری نشود |

## اگر سکرتی لو رفت

1. فوراً در محل اصلی Revoke/Rotate کن (Cloudflare dashboard یا پنل سرویس).
2. مقدار جدید را با همان روش «ست کردن» جایگزین کن.
3. اگر در Git کامیت شده: تاریخچه را بازنویسی نکن — فقط Rotate کن (تاریخچه دیگر امن نیست).
4. در چت فقط بگو «Rotate شد»، مقدار جدید را نفرست.
