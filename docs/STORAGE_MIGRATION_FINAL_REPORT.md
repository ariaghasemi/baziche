# گزارش نهایی مهاجرت Storage (R2 → GitHub Releases) — مراحل ۱ تا ۱۸

> تاریخ: ۲۰۲۶-۰۹-۱۷ — کامیت: `5b03a00` (روی `07bdf89` سینک‌شده با GitHub).
> قرارداد: D1 = metadata، GitHub Releases = binary، با انتزاع قابل‌برگشت.

## A. Files Created (فایل → هدف)

```text
backend/src/lib/binary-storage.ts      → اینترفیس BinaryStorage + R2BinaryStorage + GitHubReleaseStorage + فکتوری + نام‌گذاری asset
backend/src/lib/content-token.ts       → امضا/راستی‌آزمایی HMAC لینک‌های دانلود بدون-auth (با JWT_SECRET، جداسازی دامنه)
backend/migrations/data/004_github_storage.sql → ستون‌های مرجع Storage (افزایشی؛ بدون حذف)
backend/tests/github-emu.ts            → شبیه‌ساز درون‌حافظه GitHub Releases + dispatch (بدون شبکه)
backend/tests/github-storage.test.ts   → ۱۵ تست واحد Storage/توکن/فکتوری/rollover
backend/tests/e2e-github.test.ts       → ۳ تست سناریوی کامل کاربر تا APK + بیلد خراب/retry + بکاپ
docs/STORAGE_MIGRATION_STEP2_REPORT.md → گزارش مرحله ۲ (وضعیت قبل از مهاجرت)
docs/STORAGE_MIGRATION_FINAL_REPORT.md → همین گزارش (مرحله ۱۸)
```

## B. Files Modified (فایل: قبل → بعد، چرا)

```text
FILE: backend/src/index.ts
BEFORE: بایندینگ‌های R2 اجباری + سکرت‌های R2 اجباری در Env
CHANGED TO: بایندینگ‌ها و سکرت‌های R2 اختیاری + BINARY_STORAGE? + کامنت اجباری‌بودن توکن گیت‌هاب در حالت github
WHY: پروداکشن بدون R2 بالا بیاید؛ حالت Storage با یک var انتخاب شود

FILE: backend/src/lib/storage-router.ts
BEFORE: Shard بدون backend؛ SELECT بدون backend
CHANGED TO: فیلد backend? در Shard و SELECT (NULL = تصمیم env)
WHY: override آینده per-shard بدون تغییر منطق امتیازدهی (جدول حذف نشد)

FILE: backend/src/routes/projects.ts
BEFORE: getText/putText مستقیم روی R2_PROJECTS؛ INSERT بدون مرجع Storage
CHANGED TO: خواندن/نوشتن هر rev از طریق BinaryStorage + ثبت storage/release_tag/release_id/asset_id/asset_name/sha256
WHY: تعویض بک‌اند بدون تغییر API/منطق revision

FILE: backend/src/routes/assets.ts
BEFORE: فقط presign/commit روی R2
CHANGED TO: presign/commit فقط-r2 (در github خطای صادقانه STORAGE_MODE) + POST /upload مولتی‌پارت (هر دو حالت) + GET /content عمومی با HMAC + /:id/url دوحالته
WHY: در github امکان presigned-PUT نیست؛ Preview بدون تغییر (GET بی‌auth) کار کند

FILE: backend/src/routes/builds.ts
BEFORE: dispatch با ۴ presign؛ راستی‌آزمایی callback با head()؛ download با ۳۰۲ به R2
CHANGED TO: payload نسخه ۲ (storage + releaseTag/assetها یا presignها) + راستی‌آزمایی APK با findAssetByName + GET /content عمومی + گیت/ریفاند دست‌نخورده
WHY: باینری بیلد از Worker عبور نکند (gh transfer)؛ API و گیت بیلد حفظ شود

FILE: backend/src/lib/backup.ts + backend/src/routes/meta.ts
BEFORE: بکاپ/خواندن latest.json مستقیم از R2_BUILDS
CHANGED TO: هر دو از طریق BinaryStorage (بکاپ‌ها روی تگ ثابت، بدون rollover)
WHY: بکاپ شبانه و /metrics در هر دو حالت کار کنند

FILE: backend/src/lib/errors.ts + shared/error-codes.md
BEFORE: بدون کد STORAGE_MODE
CHANGED TO: کد STORAGE_MODE (400) در هر دو (همگام طبق قرارداد)
WHY: خطای صادقانه به‌جای شکست گنگ در حالت اشتباه

FILE: backend/wrangler.toml
BEFORE: سه [[r2_buckets]] + بدون BINARY_STORAGE
CHANGED TO: بایندینگ‌ها حذف + BINARY_STORAGE="github" + دستور rollback در کامنت
WHY: دیپلوی روی اکانت 10042 بدون R2 کار کند

FILE: .github/workflows/game-build.yml
BEFORE: فقط R2 (۴ presign + curl)؛ contents:read
CHANGED TO: دوحالته با فیلد storage؛ شاخه github با gh release download/upload؛ contents:write
WHY: انتقال باینری بدون عبور از Worker و بدون PAT اضافه

FILE: .github/workflows/infrastructure.yml
BEFORE: مرحله Provision R2 (شکست 10042)؛ contents:read
CHANGED TO: حذف R2 + مرحله Bootstrap چهار ریلیز Storage (idempotent)؛ contents:write
WHY: ستاپ خودکار Storage؛ D1 و دیپلوی دست‌نخورده

FILE: android/.../BazicheApi.kt + Dto.kt + build.gradle.kts (:core:network)
BEFORE: بدون متد آپلود یک‌مرحله‌ای
CHANGED TO: uploadAsset مولتی‌پارت + UploadedAsset/UploadAssetResponse (@Serializable) + okhttp از implementation به api
WHY: کلاینت endpoint جدید؛ lag نکردن FakeApi (تغییر حداقلی، بدون UI)

FILE: android/.../AuthRepositoryTest.kt (FakeApi)
BEFORE: بدون override آپلود
CHANGED TO: override uploadAsset + ایمپورت‌ها
WHY: همگام با اینترفیس (تنها پیاده‌سازی BazicheApi در تست‌ها)

FILE: docs/SECRETS.md — مصرف JWT_SECRET (امضای HMAC هم) + اجباری‌شدن GITHUB_DISPATCH_TOKEN با Contents/Actions RW + R2 فقط-rollback
FILE: docs/CLOUDFLARE_SETUP.md — گام ۴/۵ R2 حذف منطقی (rollback در متن)؛ گام PAT و GITHUB_REPO
FILE: docs/GITHUB_BUILD_SETUP.md — وضعیت FUNCTIONAL + payload نسخه ۲ هر دو شاخه + چک‌لیست به‌روز
FILE: docs/FINAL_SETUP_INPUTS.md — بخش USER ACTION REQUIRED + حذف باکت/R2 + توکن گیت‌هاب اجباری
FILE: docs/RUNBOOK.md — بکاپ/ری‌استور با gh + چرخش PAT + عیب‌یابی UPLOAD_MISSING/HMAC
FILE: shared/api-v1.openAPI.yaml — /assets/upload، /assets/content، /builds/content + برچسب r2-only
```

## C. Files Deleted

```text
(هیچ فایلی حذف نشد — طبق مرحله ۱۷: بدون بازنویسی/حذف بی‌دلیل. کد R2 به‌عنوان مسیر rollback ماند.)
```

## D. API Changes

```text
endpoint                        before                    after
POST /assets/presign            presign روی R2            فقط حالت r2؛ در github: 400 STORAGE_MODE
POST /assets/commit             commit روی R2             فقط حالت r2؛ در github: 400 STORAGE_MODE
POST /assets/upload             (نبود)                    مولتی‌پارت projectId/kind/file → {id,key} (هر دو حالت)
GET /assets/content             (نبود)                    دانلود عمومی با ?key=&exp=&token= (HMAC)
GET /assets/:id/url             presigned R2              دوحالته (R2 یا URL پروکسی HMAC)؛ شکل پاسخ یکی
GET /builds/:id (apkUrl)        presigned R2              دوحالته؛ شکل پاسخ یکی
GET /builds/:id/download        ۳۰۲ به R2                 ۳۰۲ به URL دوحالته
GET /builds/content             (نبود)                    دانلود عمومی artifact با HMAC
بقیه APIها                       —                         بدون تغییر (auth/projects/billing/admin/ai/meta)
```

## E. Database Changes (مایگریشن 004_github_storage — همه nullable، صفر حذف)

```text
table               column(s)                                                                 purpose
project_revisions   storage, release_tag, release_id, asset_id, asset_name, sha256            مرجع باینری هر rev
assets              storage, release_tag, release_id, asset_id, asset_name, sha256,            مرجع + تایپ فایل برای Content-Type
                    content_type (+ ایندکس idx_assets_key روی r2_key)
builds              bundle_storage/bundle_key/bundle_release_tag/bundle_asset_id/             مرجع باندل + نام/id موردانتظار
                    bundle_asset_name/bundle_sha256/release_tag/apk|aab|log_asset_name|_id    آرتیفکت‌ها (راستی‌آزمایی کال‌بک)
storage_shards      backend                                                                   override per-shard (NULL=env)
```

## F. GitHub Configuration (نام سکرت؛ هرگز مقدار)

```text
Secret: GITHUB_DISPATCH_TOKEN (استفاده مجدد — سکرت جدید صفر)
Where: Cloudflare Worker secret (wrangler secret put) — همان قبلی
Why: تریگر game-build.yml + خواندن/نوشتن GitHub Releases (Fine-grained: همین ریپو، Contents RW + Actions RW)
Note: ورک‌فلوها از GITHUB_TOKEN داخلی استفاده می‌کنند (contents:write) — بدون PAT اضافه
```

## G. User Actions (فقط کارهای واقعی تو — جزئیات در FINAL_SETUP_INPUTS.md)

```text
1. ریپو را private نگه دار.
2. Fine-grained PAT (همین ریپو، Contents RW + Actions RW) بساز و فقط با wrangler secret put ست کن.
3. GITHUB_REPO="ariaghasemi/baziche" در [vars] (BINARY_STORAGE=github از قبل هست).
4. ورک‌فلوی infrastructure را دستی اجرا کن.
5. سناریوی واقعی: ثبت‌نام → بازی → asset → ذخیره → بستن → بازکردن → Preview → بیلد → نصب APK.
6. آدرس ورکر را در چت بفرست تا APK متصل را ری‌بیلد کنم.
```

## H. Tests (test → result)

```text
typecheck (tsc --noEmit)                                  PASS
70 تست قبلی (۱۱ فایل، حالت پیش‌فرض r2، بدون تغییر)        70/70 PASS
15 تست جدید github-storage (فکتوری/توکن/put/get/head/      15/15 PASS
  delete/downloadURL/rollover/replace/r2-roundtrip)
3 تست جدید e2e-github (سفر کامل تا بایت‌به‌بایت APK +      3/3 PASS
  گیت free-build + بیلد خراب/retry/UPLOAD_MISSING + بکاپ/metrics)
جمع بک‌اند                                                 88/88 PASS (13 فایل)
اعتبارسنجی YAML (game-build + infrastructure + openapi)   PASS
kotlinc 2.4.20 مستقل روی BazicheApi.kt + Dto.kt           PASS (exit 0) + تطابق امضای FakeApi با javap
```

## I. Remaining Problems (صادقانه)

```text
1. کد مسیر R2 عمداً مانده (rollback با یک var) — اما در حالت github هیچ فراخوانی R2 اجرا نمی‌شود
   (تأیید با grep: refهای R2 فقط در R2BinaryStorage و شاخه‌های گیت‌شده r2).
2. شاخه github در game-build.yml روی Actions واقعی هنوز اجرا نشده (نیازمند PAT و دیپلوی تو) —
   منطق همان شاخه بایت‌به‌بایت در E2E شبیه‌سازی و سبز شد؛ YAML معتبر است.
3. بیلد کامل Gradle این‌جا اجرا نشد (JDK17/SDK در سندباکس نیست) — فایل‌های کوت‌لین تغییرکرده
   با kotlinc تأیید شدند؛ android-ci روی push کامپایل کامل را می‌گیرد.
4. سقف‌ها (MVP): ۱۰۰۰ asset/ریلیز (rollover در ۹۰۰)، بکاپ بدون rollover (~۲.۵ سال ظرفیت)،
   آپلود ویدیو ۵۰MB از حافظه Worker عبور می‌کند (سقف قبلی حفظ شد، کم نشد).
5. دیتای واقعی برای migrate لازم نیست (دیتابیس‌ها تازه‌اند)؛ سطرهای قدیمی NULL حالت env را می‌گیرند.
```
