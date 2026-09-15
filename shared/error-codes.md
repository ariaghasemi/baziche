# BAZICHE — Canonical Error Codes (API v1)

همه خطاها با همین شکل برمی‌گردند:

```json
{ "success": false, "error": { "code": "STRING_CODE", "message": "human readable (en)" } }
```

HTTP status پیشنهادی کنار هر کد آمده. پیام فارسی در اپ ساخته می‌شود (نگاشت کد → رشته).

## Auth (401/400/429)

| Code | HTTP | معنی |
|---|---|---|
| `INVALID_PHONE` | 400 | شماره معتبر نیست |
| `INVALID_USERNAME` | 400 | یوزرنیم معتبر نیست (3-24، حروف/عدد/._) |
| `WEAK_PASSWORD_PARAMS` | 400 | پارامترهای stretch ضعیف است |
| `PHONE_TAKEN` | 409 | شماره قبلاً ثبت شده |
| `USERNAME_TAKEN` | 409 | یوزرنیم قبلاً گرفته شده |
| `INVALID_CREDENTIALS` | 401 | شماره یا رمز اشتباه (عمداً مبهم) |
| `ACCOUNT_SUSPENDED` | 403 | حساب معلق است |
| `TOKEN_EXPIRED` | 401 | Access Token منقضی |
| `TOKEN_INVALID` | 401 | توکن نامعتبر |
| `REFRESH_INVALID` | 401 | Refresh نامعتبر/منقضی/باطل‌شده |
| `RATE_LIMITED` | 429 | سقف نرخ؛ بعداً تلاش کن |

## Projects & Sync (400/404/409/413)

| Code | HTTP | معنی |
|---|---|---|
| `UNKNOWN_GAME_TYPE` | 400 | gameType در رجیستری نیست |
| `PROJECT_NOT_FOUND` | 404 | پروژه یافت نشد / مال تو نیست |
| `PROJECT_DELETED` | 410 | پروژه حذف شده |
| `INVALID_PROJECT_JSON` | 400 | JSON نامعتبر (نسخه/ساختار/حجم) |
| `REVISION_CONFLICT` | 409 | baseRev قدیمی است؛ پاسخ شامل serverRev و serverCopy |
| `PROJECT_LIMIT_REACHED` | 403 | سقف تعداد پروژه پلن |

## Assets (400/403/413)

| Code | HTTP | معنی |
|---|---|---|
| `ASSET_TOO_LARGE` | 413 | فایل از سقف نوعش بزرگ‌تر است |
| `ASSET_TYPE_BLOCKED` | 400 | نوع فایل مجاز نیست |
| `ASSET_NOT_FOUND` | 404 | آبجکت در R2 پیدا نشد (commit ناموفق) |
| `STORAGE_QUOTA_EXCEEDED` | 403 | سقف فضای پلن |

## Builds (402/403/409) — فاز ۵

| Code | HTTP | معنی |
|---|---|---|
| `SUBSCRIPTION_REQUIRED` | 402 | اشتراک لازم است |
| `FREE_BUILD_UNAVAILABLE` | 403 | سهم Free Build مصرف شده |
| `FREE_BUILD_RACE_LOST` | 409 | رزرو همزمان؛ تلاش مجدد |
| `BUILD_VALIDATION_FAILED` | 400 | پروژه آماده بیلد نیست (جزئیات در meta) |
| `BUILD_NOT_FOUND` | 404 | بیلد یافت نشد |
| `NO_AGENT_AVAILABLE` | 503 | ایجنت آزاد نیست؛ در صف بمان |

## Billing (400/402/409) — فاز ۶

| Code | HTTP | معنی |
|---|---|---|
| `UNKNOWN_PLAN` | 400 | پلن نامعتبر |
| `PURCHASE_INVALID` | 402 | خرید در سرور Myket تأیید نشد |
| `PURCHASE_REPLAY` | 409 | این token قبلاً مصرف شده |

## Admin (403) / Generic

| Code | HTTP | معنی |
|---|---|---|
| `FORBIDDEN` | 403 | دسترسی ادمین لازم است |
| `NOT_FOUND` | 404 | یافت نشد |
| `VALIDATION_ERROR` | 400 | خطای عمومی اعتبارسنجی (جزئیات در meta) |
| `CONFLICT` | 409 | تضاد عمومی |
| `INTERNAL` | 500 | خطای داخلی (بدون جزئیات حساس) |
| `NOT_IMPLEMENTED` | 501 | این قابلیت در این فاز پیاده نشده (صادقانه) |
