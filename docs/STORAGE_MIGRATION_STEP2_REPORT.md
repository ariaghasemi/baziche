# گزارش مرحله ۲ — وضعیت فعلی Repository قبل از مهاجرت Storage (R2 → GitHub Releases)

> تاریخ: ۲۰۲۶-۰۹-۱۷ — مبنا: کامیت `07bdf89` (پس از سینک کامل ورک‌اسپیس با GitHub).
> این گزارش فقط از روی سورس واقعی و Git history استخراج شده؛ هیچ حدسی در آن نیست.

## ۱. تاریخچه فازها (از Git log)

| کامیت | فاز | خلاصه واقعی تغییرات |
|---|---|---|
| `e49c51d` | پایه | اپ شل + ماژول‌های core اندروید، ورک‌فلوهای CI، پیش‌نویس game-build |
| `212a34c` | docs | راهنماهای ENVIRONMENT/CLOUDFLARE/GITHUB/SECRETS/BUILD_SERVER |
| `31c92ae` | Phase 2 backend | دوقلوی D1 (auth+data)، restore/merge، اعتبارسنجی magic-byte |
| `6e09c59` | Phase 2 android | ماژول `:editor`، sync worker، UI تاریخچه/restore |
| `a264631` | Phase 3 backend | لیست asset + URL دانلود presigned برای Preview |
| `de96c08` | Phase 3 android | موتور `:runtime` + پلیر `:preview` + سیم‌کشی اپ |
| `f8ad947` | Phase 4 | تمپلیت‌های Tier-1، انیمیشن CAP-0014، اسپریت‌ها، AGP 9 + compileSdk 37 |
| `57ebe26` | Phase 5 | `POST /builds` + باندل رمزنگاری‌شده + `:game-shell` + game-build.yml واقعی |
| `363e074` | Phase 6 | تأیید سروری Myket + گیت free-build + `/admin` سرور-رندر |
| `70010d6` | Phase 7 | ذرات محیطی، لرزش دوربین، الگوهای inventory/quest/shop |
| `a3f79d7` | Phase 8 | dispatch MonetizationSink + آداپتور Myket + پورت رزرو تبلیغات |
| `fdabf91` | Phase 9 | ژنراتور آفلاین `:scaffolder` + `POST /ai/expand` |
| `c8bc1b9` | Phase 10 | بکاپ cron شبانه D1→R2 + `/metrics` + RUNBOOK |
| `8610a51` | fix | رفع خطاهای کامپایل تست‌های `:core:data` |
| `d4282f7` | docs | `FINAL_SETUP_INPUTS.md` (چک‌لیست تک‌فایل ورودی کاربر) |
| `fde84ce` | کاربر | ورک‌فلوی `infrastructure.yml` + بذر جدید `storage_shards` + بازنویسی `storage-router.ts` |
| `eb7595d` | کاربر | فیکس setup-android در `android-ci.yml` (v4 + platform-tools) |
| `07bdf89` | کاربر | فیکس capture کردن D1 ID در `infrastructure.yml` |

تغییرات خود کاربر (۳ کامیت آخر) دقیقاً در ورک‌اسپیس سینک شد (`git status` تمیز، tracked files یکسان).

## ۲. فایل‌به‌فایل: نقش و وابستگی Storage

### بک‌اند — کانفیگ و هسته

| PATH | ROLE | CURRENT RESPONSIBILITY | R2 DEPENDENCIES | D1 DEPENDENCIES |
|---|---|---|---|---|
| `backend/wrangler.toml` | کانفیگ Worker | بایندینگ D1/R2، cron بکاپ، vars غیرسکرت | ۳ باکت `baziche-projects/assets/builds` | ۲ دیتابیس (آی‌دی placeholder) |
| `backend/src/index.ts` | سیم‌کشی اپ | اینترفیس `Env`، روت‌ها، cron→`handleScheduled` | تایپ ۳ بایندینگ + ۳ سکرت R2 | تایپ ۲ بایندینگ D1 |
| `backend/src/lib/storage-router.ts` | انتخاب شارد (نسخه جدید کاربر) | امتیازدهی شاردها بر اساس `kind`، ساخت کلید `keyFor` | غیرمستقیم (نام باکت از D1 می‌آید) | جدول `storage_shards` |
| `backend/src/lib/r2.ts` | دسترسی R2 | امضاکننده دستی SigV4 (presign GET/PUT) + `getText/putText` روی بایندینگ | مستقیم (امضا + I/O) | ندارد |
| `backend/src/lib/backup.ts` | بکاپ cron | دامپ همه جداول → یک فایل gzip + `latest.json` | نوشتن در `R2_BUILDS` | خواندن همه جداول auth/data |
| `backend/src/lib/bundle.ts` | باندل بیلد | ساخت ZIP + رمزنگاری AES-256-CBC سازگار openssl | ندارد (بایت خالص) | ندارد |

### بک‌اند — روت‌ها

| PATH | ROLE | CURRENT RESPONSIBILITY | R2 DEPENDENCIES | D1 DEPENDENCIES |
|---|---|---|---|---|
| `backend/src/routes/projects.ts` | CRUD پروژه | هر revision یک آبجکت JSON (`projects/<shard>/<id>/r<N>.json`) | `R2_PROJECTS` (خواندن/نوشتن هر rev) | `projects` + `project_revisions` (ستون `r2_key`) |
| `backend/src/routes/assets.ts` | آپلود/دانلود asset | `presign` (PUT)، `commit` (magic-verify)، `:id/url` (GET)، لیست | presign روی `baziche-assets` + خواندن head | جدول `assets` (ستون `r2_key`) |
| `backend/src/routes/builds.ts` | بیلد | باندل رمزنگاری‌شده + ۴ presign + دیسپچ گیت‌هاب؛ callback با توکن per-build؛ `apkUrl` و `download` با ۳۰۲ | `R2_BUILDS` (باندل/APK/AAB/log) + خواندن `R2_ASSETS`/`R2_PROJECTS` | جدول `builds` (`*_r2_key`، `callback_token`، `spent`) |
| `backend/src/routes/meta.ts` | متا/مانیتورینگ | `/metrics` عمومی | خواندن `backups/latest.json` از `R2_BUILDS` | شمارش users/projects/builds/subs |
| `backend/src/routes/auth.ts`, `billing.ts`, `admin.ts`, `ai.ts` | احراز/پرداخت/ادمین/AI | بدون تماس باینری | ندارد | جداول auth/data مرتبط |

### مهاجرت‌های D1

| PATH | ROLE | CURRENT RESPONSIBILITY | R2 DEPENDENCIES | D1 DEPENDENCIES |
|---|---|---|---|---|
| `migrations/data/001_init.sql` | اسکیمای پایه (نسخه جدید کاربر) | جداول projects/revisions/assets/builds/shards/agents/flags | فقط نام ستون‌ها (`r2_key`) + بذر ۳ شارد (`kind`=project/asset/build) | تعریف جداول |
| `migrations/data/002_builds_callback.sql` | توکن callback | ستون `callback_token` | ندارد | `builds` |
| `migrations/data/003_builds_spent.sql` | نوع خرج بیلد | ستون `spent` (free/oneshot/NULL) | ندارد | `builds` |

### ورک‌فلوها

| PATH | ROLE | CURRENT RESPONSIBILITY | R2 DEPENDENCIES | D1 DEPENDENCIES |
|---|---|---|---|---|
| `.github/workflows/infrastructure.yml` | پروویژن (کاربر) | ساخت D1 + تزریق ID در wrangler + ساخت R2 + migrate + deploy | مرحله «Provision R2 buckets» — **روی اکانت کاربر با خطای 10042 شکست می‌خورد** | ساخت/مایگریت `baziche-auth/data` |
| `.github/workflows/game-build.yml` | بیلد بازی | دانلود باندل با curl، دیکریپت، گریدل، آپلود APK/AAB/log، callback | کاملاً R2-محور (۴ presigned URL)؛ `contents: read` | ندارد |
| `.github/workflows/backend-ci.yml` | CI بک‌اند | typecheck + vitest روی node22 | ندارد (تست‌ها R2 را با Map شبیه‌سازی می‌کنند) | ندارد (sql.js در حافظه) |
| `.github/workflows/android-ci.yml` | CI اندروید | بیلد APK دیباگ | ندارد | ندارد |

### اندروید و shared

| PATH | ROLE | CURRENT RESPONSIBILITY | R2 DEPENDENCIES | D1 DEPENDENCIES |
|---|---|---|---|---|
| `android/.../BazicheApi.kt` | اینترفیس Retrofit | `presign/commitAsset/assets/assetUrl` | غیرمستقیم (URL آماده از سرور) | ندارد (فقط API) |
| `android/.../Dto.kt` | DTOها | `PresignRequest/Response`، `CommitRequest`، `AssetDto` و… | ندارد | ندارد |
| `android/.../PreviewViewModel.kt` | دانلود Preview | `GET` ساده و **بدون احراز** روی URL برگشتی `assetUrl` با سقف حجم | غیرمستقیم — **طراحی را دیکته می‌کند: URL دانلود باید بدون هدر auth کار کند** | ندارد |
| `shared/api-v1.openAPI.yaml` | قرارداد API | مستندسازی endpointهای asset | ندارد | ندارد |
| `docs/GITHUB_BUILD_SETUP.md` | قرارداد payload | `bundleUrl/apkUrl/aabUrl/logUrl/callbackUrl` + سکرت‌های `bundle_key/callback_token` | فرض R2 در payload | ندارد |

یافته کلیدی تأییدشده با grep: متدهای `presign/commit` در اپ **هیچ caller واقعی** ندارند (فقط fake تست‌ها)؛ تنها مصرف‌کننده واقعی، دانلود Preview است.

## ۳. Storage فعلی چگونه کار می‌کند

- انتخاب شارد: `storageRouter.resolveShard(db, kind)` از جدول `storage_shards` (فیلتر `kind` + امتیاز health/headroom/latency)؛ نام‌گذاری کلید فقط در `keyFor`.
- پروژه‌ها: هر save یک کلید جدید `projects/<shard>/<id>/r<rev>.json` در `R2_PROJECTS` + سطر `project_revisions` با `r2_key`.
- assetها: دو مرحله‌ای — `presign` (PUT امضاشده ۱۵ دقیقه‌ای) بعد `commit` (خواندن ۳۲ بایت اول + magic-byte + ثبت سطر).
- بیلدها: Worker باندل ZIP رمزنگاری‌شده را در `R2_BUILDS` می‌گذارد، ۴ presign می‌سازد، `game-build.yml` را دیسپچ می‌کند؛ ورک‌فلو با curl آپلود می‌کند و با توکن per-build کال‌بک می‌زند؛ Worker با `head()` نشستن APK را راستی‌آزمایی می‌کند.
- بکاپ: هر شب کل D1 به‌صورت gzip در `R2_BUILDS` + اشاره‌گر `latest.json` که `/metrics` می‌خواند.

## ۴. تصمیم‌های طراحی مهاجرت (تأییدشده برای اجرا)

1. **انتزاع**: اینترفیس `BinaryStorage` در `lib/binary-storage.ts` + دو پیاده‌سازی `R2BinaryStorage` (روی کد فعلی `r2.ts`) و `GitHubReleaseStorage` (روی GitHub REST API) + فکتوری با متغیر `BINARY_STORAGE` (پیش‌فرض `r2` برای سازگاری عقب‌رو؛ پروداکشن در `wrangler.toml` روی `github` قفل می‌شود).
2. **سکرت جدید صفر**: استفاده مجدد از `GITHUB_DISPATCH_TOKEN` و `GITHUB_REPO` برای API ریلیزها؛ امضای HMAC لینک‌های دانلود با `JWT_SECRET` (جداسازی دامنه). PAT حداقلی: Contents خواندن/نوشتن + Actions خواندن/نوشتن روی همین ریپو.
3. **دانلود بدون auth** (نیاز Preview): در حالت github، `/:id/url` و `download` به URL پروکسی Worker با `?token=` امضاشده برمی‌گردند؛ اندروید بدون تغییر کار می‌کند.
4. **Release strategy**: چهار ریلیز چرخشی `baziche-storage-{projects,assets,builds,backups}` با rollover خودکار (`-2`، `-3`…) نزدیک سقف ۱۰۰۰ asset؛ نام فایل‌ها یکتا (`project-<id>-r<rev>.json` و…)؛ D1 مرجع کامل (`release_tag/release_id/asset_id/asset_name/sha256`) + ستون `storage` نگه می‌دارد؛ ستون‌های `r2_key` برای rollback دست‌نخورده می‌مانند.
5. **game-build.yml دوحالته**: payload نسخه ۲ با فیلد `storage`؛ حالت github با `gh release download/upload` و `GITHUB_TOKEN` داخلی (بدون عبور باینری از Worker)؛ شاخه R2 حفظ می‌شود.
6. **حفظ‌شده‌ها**: موتور/ادیتور/auth/پرداخت/معماری بیلد/APIها بدون تغییر؛ جدول `storage_shards` حذف نمی‌شود (ستون nullable جدید `backend` برای override آینده).
