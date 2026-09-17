# 💳 اتصال مایکت (Myket Billing) — راهنمای واقعی

> کدها آماده‌اند؛ این قدم‌ها را که انجام بدهی، خرید واقعی کار می‌کند.
> تا وقتی SDK اضافه نشده، `MyketBillingGateway.available == false` و خریدها با
> پیام «در دسترس نیست» برمی‌گردند (هرگز mock به‌جای واقعی جا زده نمی‌شود).

## ۱) پنل توسعه‌دهندگان مایکت

1. در [پنل توسعه‌دهندگان مایکت](https://developer.myket.ir) اپ را ثبت کن
   (packageName نهایی؛ مثلاً `com.baziche.app`).
2. بخش «محصولات درون‌برنامه‌ای» همان اپ، این SKUها را **دقیقاً با همین ID** بساز
   (بک‌اند همین‌ها را می‌شناسد — `backend/src/routes/billing.ts`):
   - `sub_monthly` / `sub_quarterly` / `sub_yearly` / `sub_lifetime`
   - `build_single` (تک‌بیلد؛ مصرف‌شدنی consumable)
3. از همان بخش، **`X-Access-Token`** را کپی کن → سرور:
   `wrangler secret put MYKET_ACCESS_TOKEN`
4. **کلید عمومی RSA** اپ را هم از پنل بردار (برای تأیید امضا؛ در نسخه‌ی بعدی
   سرور استفاده می‌شود).

## ۲) سرور (بک‌اند)

```bash
wrangler secret put MYKET_ACCESS_TOKEN   # از قدم ۱
# اگر packageName غیر از com.baziche.app است:
# در wrangler.toml بخش [vars]: MYKET_PACKAGE = "com.example.app"
```

تست: خرید تستی در مایکت انجام بده، بعد:

```bash
curl -X POST https://<worker>/api/v1/billing/verify \
  -H "Authorization: Bearer <access>" -H "Content-Type: application/json" \
  -d '{"sku":"build_single","token":"<purchaseToken>"}'
```

## ۳) اپ اندروید (SDK مایکت)

1. کتابخانه‌ی `myketbilling` (AAR) را از پنل مایکت دانلود کن.
2. در `android/core/billing/libs/myketbilling.aar` بگذار و به
   `core/billing/build.gradle.kts` اضافه کن:
   `implementation(files("libs/myketbilling.aar"))`
3. متدهای `MyketBillingGateway` را با همان API مستندات مایکت کامل کن
   (`initialize(publicKey)` → `queryInventoryAsync` → `launchPurchaseFlow` →
   `consumeAsync`) و `available` را واقعی کن.
4. `developerPayload` را همیشه با userId پر کن (جلوگیری از replay).

## ۴) فلو کامل خرید (وقتی SDK آمد)

`PurchaseFlow.buy()` → مایکت → چک payload → `POST /billing/verify` (سرور با
توکن X-Access-Token از مایکت استعلام می‌کند) → در صورت تأیید `consume()`.

## تبلیغات (رزرو)

پورت `AdsGateway` تعریف شده ولی پیاده‌سازی ندارد. وقتی شبکه‌ی تبلیغاتی انتخاب
شد (تپسل و...)، ایمپل آن را اضافه کن؛ موتور از قبل `CAP-0028` را به
`MonetizationSink` می‌فرستد.
