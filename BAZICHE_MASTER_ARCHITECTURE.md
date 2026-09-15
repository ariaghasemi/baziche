# BAZICHE MASTER ARCHITECTURE
### بازیچه — Android Game Builder | سند معماری مادر | نسخه ۱.۱ (نهایی، مبنای پیاده‌سازی)
**تاریخ:** ۲۰۲۶-۰۹-۱۵ | **وضعیت:** ✅ FINAL — Phase 1 بر اساس این سند اجرا می‌شود

**تاریخچه بازبینی:**
- v1.0 (2026-09-15): تحلیل اولیه، انتخاب استک، پاسخ ۲۰ سؤال
- v1.1 (2026-09-15): اعمال Revision کارفرما — Component System، Capability/Feature Registry، ۲۰ Game Type سه‌Tier، Build با GitHub Actions، استراتژی Free-Tier تک‌اکانتی، احراز هویت Client-Stretch، جدایی Creator Monetization، State Machine ‏Free Build، Agent Manager

---

## ۰. خلاصه اجرایی

بازیچه یک اپ اندرویدی **Native** است (Kotlin + Jetpack Compose) شامل **Visual Game Editor** بدون کدنویسی و **Game Runtime** سبک دوبعدی. زنجیره ارزش محصول:

```text
Game Types → Feature Registry → Capability Registry → Component System
→ Data-Driven Runtime → Game Project JSON → Game Shell → APK/AAB
```

- کاربر در اپ بازی می‌سازد، با **همان Runtime خروجی** Preview می‌گیرد (`EDITOR PREVIEW ≈ EXPORTED GAME`)، پروژه در فضای ابری Sync می‌شود، و خروجی APK/AAB روی **Build Provider** (فاز MVP: **GitHub Actions رایگان**؛ آینده: VPS Agentها) ساخته می‌شود.
- بک‌اند: TypeScript + Hono روی **Cloudflare Workers + D1 + R2**، تک‌اکانت، مهندسی‌شده برای ماندن در Free Tier تا مقیاس هزاران کاربر.
- درآمد بازیچه (Subscription) از درآمد سازنده بازی (Creator Monetization) **کاملاً جدا** و هر دو Provider-Based است.

### تصمیمات غیرقابل‌مذاکره (خطوط قرمز مهندسی)

| # | قانون | دلیل |
|---|---|---|
| ۱ | هیچ بیلد APK داخل Worker یا داخل اپ اندروید | غیرممکن فنی / ناامن |
| ۲ | هیچ Secret در APK، Git، یا چت | امنیت پایه |
| ۳ | هیچ اکانت Cloudflare اضافه برای دور زدن Quota | نقض ToS = ریسک Ban و نابودی کل دیتا؛ مغایر §۴۰ اسپک |
| ۴ | هیچ Hard-Code برای: قیمت‌ها، TARGET_API، منطق Game Typeها | پیکربندی‌پذیری |
| ۵ | هیچ ادعای تکمیل بدون پیاده‌سازی واقعی | برچسب صادقانه: `NOT IMPLEMENTED` / `MOCK` / `DRAFT` |

---

## ۱. استک فناوری (نهایی و قفل‌شده فاز ۱)

| لایه | انتخاب | نسخه پین‌شده (تأییدشده، سپتامبر ۲۰۲۶) |
|---|---|---|
| Android App + Editor + Runtime + Game Shell | Kotlin + Jetpack Compose (Native)، minSdk 26، target/compileSdk **36 (Configurable)** | AGP 9.4.0، Kotlin 2.4.20، Gradle 9.7.1، Compose BOM 2026.09.00، JDK 17 |
| Network/Data (اپ) | Retrofit + OkHttp + kotlinx.serialization؛ Room (kapt)؛ DataStore؛ Coroutines | retrofit 3.0.0، okhttp 5.5.0، room 2.8.5، coroutines-bom 1.11.0 |
| Backend | TypeScript + Hono روی Workers؛ Zod؛ jose (JWT)؛ aws4fetch (امضای R2) | hono 4.13.8، zod 4.6.5، jose 6.2.12، wrangler 4.131.2 |
| Database / Storage | D1 (متا) + R2 (بایت‌ها)؛ SQL مهاجرت نسخه‌دار | — |
| Build (MVP) | **GitHub Actions** (`game-build.yml`) + قرارداد Callback؛ VPS Agent در فاز Scale | — |
| Admin | API نقش‌محور (فاز ۱) + وب‌اپ روی Pages (فاز ۶+) | — |
| Test | Backend: Vitest + sql.js (شبیه‌ساز واقعی D1)؛ Android: JUnit4 + Fake API | vitest 5.0.1، sql.js 1.14.2 |

**چرا Native و نه Flutter/Godot:** حجم Runtime بازی خروجی (~۸–۱۵MB در برابر ۲۵–۶۰MB)، دسترسی مستقیم به Myket IAP، و تضمین Preview یکسان با Runtime واحد. (تحلیل کامل در v1.0، همچنان معتبر.)

---

## ۲. معماری لایه‌ای Runtime (قلب محصول)

### ۲.۱ زنجیره ترکیب (Composition Chain)

```text
┌────────────┐   composes   ┌──────────────────┐   uses   ┌────────────────────┐
│ Game Type  │ ───────────► │ Feature Registry │ ───────► │ Capability Registry│
│ (پازل،...) │   (فقط ارجاع │ (physics، shop…) │  (ارجاع  │ (CAP-0001 Move …)  │
└────────────┘    بدون کد)  └────────┬─────────┘   به کد)  └─────────┬──────────┘
                                     │                               │
                                     ▼                               ▼
                              ┌─────────────────────────────────────────────┐
                              │ Component System (Transform/Sprite/…)       │
                              │ اجرا روی Data-Driven Runtime (Kotlin)       │
                              └─────────────────────────────────────────────┘
```

- **Game Type هیچ‌وقت کد ندارد**؛ فقط لیست Feature + پریست Editor + Template است.
- **Feature** گروهی از Capabilityهاست (مثلاً `shop` = [Show UI, Get Variable, If Condition, Save Game, …]).
- **Capability** کوچک‌ترین واحد قابل‌اجرا با شناسه پایدار (`CAP-xxxx`) است؛ Registry برای ۱۰۰۰+ آماده است، MVP با ~۳۰ Capability شروع می‌شود.
- **Component** واحد دیتای Entity است (۲۵ کامپوننت پایه در §۲.۳)؛ Runtime آن‌ها را تفسیر و اجرا می‌کند.

### ۲.۲ Capability Registry (قرارداد پایدار)

```text
CAP-0001 Move Object        CAP-0011 Timer           CAP-0021 Add Score
CAP-0002 Rotate Object      CAP-0012 Delay           CAP-0022 Damage
CAP-0003 Scale Object       CAP-0013 Play Sound      CAP-0023 Heal
CAP-0004 Destroy Object     CAP-0014 Play Animation  CAP-0024 Spawn Object
CAP-0005 Spawn Object       CAP-0015 Change Scene    CAP-0025 Checkpoint Save
CAP-0006 Set Position       CAP-0016 Show UI         CAP-0026 Vibrate
CAP-0007 Set Variable       CAP-0017 Hide UI         CAP-0027 Open URL/DeepLink
CAP-0008 Get Variable       CAP-0018 Save Game       CAP-0028 Request Ads Reward
CAP-0009 If Condition       CAP-0019 Load Game       CAP-0029 Purchase Product
CAP-0010 Compare Values     CAP-0020 Add Score       CAP-0030 Navigate Back
... (تا ۱۰۰۰+ با همین قرارداد: id پایدار، ورودی/خروجی Schemaدار، نسخه‌دار)
```

پیاده‌سازی: `backend/src/registries/capabilities.ts` + سرویس `GET /meta/registries` (نسخه‌دار، کش‌شدنی) + تست یکپارچگی ارجاع‌ها. Runtime اندروید (فاز ۳) همین شناسه‌ها را اجرا می‌کند.

### ۲.۳ Component System (۲۵ کامپوننت پایه)

```text
Transform · Sprite · Text · Collider · RigidBody · Movement · Health · Damage
· Animation · Audio · Particle · Camera · UI · Button · Input · Timer
· Inventory · Quest · Dialogue · AI · Spawner · Trigger · Checkpoint
· Score · Currency · SaveData
```

هر Object = مجموعه‌ای از Componentها (دیتا)؛ هر System در Runtime = مفسر یک خانواده Component. افزودن Component جدید = افزودن کلاس + ثبت در کاتالوگ، بدون بازنویسی Core. کاتالوگ: `shared/registries/components.json`.

### ۲.۴ Feature Registry (۲۰ فیچر)

```text
physics · animation · particles · audio · dialogue · inventory · quest
· achievement · shop · economy · save_system · leaderboard · ads · iap
· notifications · analytics · ai · level_system · checkpoint · multiplayer(reserved)
```

`multiplayer` از ابتدا **Reserved** است (رابط خالی، NOT IMPLEMENTED) تا معماری آینده‌نگر بماند بدون ادعای دروغ.

### ۲.۵ Game Types (۲۰ نوع، سه Tier)

```text
Tier 1 — MVP (فاز ۳–۴، پیاده‌سازی واقعی):
  Puzzle · Quiz · Word · Arcade · Runner · Platformer · Match3 · Card
Tier 2 — (فاز ۷، بدون بازنویسی Core):
  Board · TowerDefense · Racing · Strategy · Adventure · RPG · Simulation · Idle
Tier 3 — (نقشه راه):
  Sports · Tycoon · Survival · Educational
```

تعریف هر ۲۰ نوع (نام، Tier، Featureهای ترکیبی، Componentهای پیش‌فرض) از فاز ۱ در `backend/src/registries/game-types.ts` به‌صورت **دیتا** موجود است؛ «پیاده‌سازی واقعی» Tier 1 یعنی Runtime/Template/Editor-Preset آن‌ها در فاز ۳–۴.

---

## ۳. Project Format (نسخه‌دار + Migration)

`shared/project-format.schema.json` مرجع واحد است (JSON Schema). خلاصه v1:

```jsonc
{ "formatVersion": 1, "meta": {}, "settings": {}, "scenes": [], "objects": [],
  "components": [], "events": [], "variables": [], "assets": [], "audio": [],
  "animations": [], "levels": [], "ui": {}, "systems": {}, "monetization": {},
  "build": { "applicationId": "", "appName": "", "versionCode": 1,
             "versionName": "1.0.0", "targetApi": 36, "minApi": 26, "...": "..." } }
```

- `targetApi/minApi` در **Build Config هر پروژه** ذخیره می‌شود؛ مقدار پیش‌فرض از `plans/config` سرور می‌آید (قابل تغییر بدون انتشار اپ).
- اعتبارسنجی سه‌لایه: Editor → API (Zod + Schema-lite) → Build Provider.
- Migration: `migrate(vN→vN+1)` تابعی، دوطرفه (اپ + سرور) در فاز ۲.

---

## ۴. Backend (Cloudflare، تک‌اکانت، Free-Tier-First)

### ۴.۱ ساختار

```text
backend/
├── src/index.ts                 # Hono app، CORS، onError، route mount
├── src/middleware/auth.ts       # requireAuth (JWT) · requireAdmin (role)
├── src/lib/ errors.ts           # ApiError + کدهای shared/error-codes.md
│           validate.ts          # Zod helpers
│           phone.ts             # Normalize شماره ایران
│           password.ts          # PasswordHasher (تصمیم §۴.۳)
│           jwt.ts               # Access HS256 (15m) + چرخش SECRET_PREV
│           ratelimit.ts         # Fixed-window روی D1
│           audit.ts             # audit_logs
│           entitlements.ts      # اشتراک + Free-Build atomic
│           storage-router.ts    # IStorageRouter + D1-backed Router
│           r2.ts                # presign (aws4fetch) + binding helpers
├── src/registries/ capabilities.ts · features.ts · game-types.ts · components.ts
├── src/routes/ auth.ts · projects.ts · assets.ts · meta.ts · admin.ts
│                (builds.ts ← فاز ۵ · billing/ ← فاز ۶ — NOT IMPLEMENTED)
├── migrations/ 001_init.sql · 002_….sql
└── tests/ (vitest + sql.js به‌عنوان D1 واقعی)
```

### ۴.۲ D1 Schema (خلاصه؛ کامل در `migrations/001_init.sql`)

```text
users(id, phone!, username!, salt, server_hash, status, free_build_state,
      free_build_id, plan_cache, created_at, updated_at, last_login)
refresh_tokens(id, user_id, token_hash!, expires_at, revoked, device, created_at)
login_attempts(phone, ip, at)
plans(id, title, price_toman, days, active)            ← قیمت‌ها در DB، Seed اولیه
subscriptions(id, user_id, plan_id, started_at, expires_at, status, provider, purchase_id)
entitlements(id, user_id, type, source, expires_at)    ← گرانت‌ها (LIFETIME، هدیه ادمین)
purchases(id, user_id, provider, sku, token!, state, payload, verified_at)
projects(id, user_id, name, game_type, format_version, shard_id, rev, status, …)
project_revisions(id, project_id, rev, r2_key, bytes, created_at)
assets(id, project_id, kind, hash, r2_key, bytes, created_at)
builds(id, user_id, project_id, rev, provider, status, target, …)   ← مصرف در فاز ۵
signing_keys(project_id, keystore_r2_key(enc), alias, created_at)   ← فاز ۵
storage_shards(id, kind, bucket, capacity, used, status, region, priority, health, …)
agents(id, provider, status, capacity, active_jobs, last_heartbeat, version, region, error_rate)
feature_flags(key, value, updated_at)
admins(user_id, role, created_at)
audit_logs(id, at, user_id, action, meta)
idempotency_keys(key, user_id, at, response)
```

### ۴.۳ تصمیم مستند: هش پسورد سازگار با Free Tier

**مسئله:** CPU ‏10ms در Workers Free اجازه Argon2/bcrypt سروری نمی‌دهد.
**راه‌حل (دو‌لایه):**
1. **Client-Stretch (گوشی):** PBKDF2-HMAC-SHA512، ≥۱۰۰٬۰۰۰ تکرار، Salt تصادفی هر کاربر (纯 JDK، بدون وابستگی).
2. **Server-Finalize (ورکر):** `SHA-256(clientHash ‖ pepper)` با WebCrypto در میکروثانیه + Pepper در Secrets.

امنیت: سرور هرگز پسورد خام نمی‌بیند؛ نشت DB بدون Pepper قابل‌استفاده برای لاگین نیست؛ کرک آفلاین نیازمند شکستن PBKDF2 است. رابط `PasswordHasher` اجازه مهاجرت به Argon2id سروری در پلن Paid را بدون تغییر API نگه می‌دارد. فلو: `challenge` (تحویل Salt) → `register/login` (تحویل clientHash).

### ۴.۴ StorageRouter (فراتر از Capacity)

```ts
interface IStorageRouter {
  resolveShard(db, kind: 'project'|'asset'|'build'): Promise<Shard>; // امتیاز: health×w1 + headroom×w2 − latency×w3 − errorRate×w4 + priority×w5
  keyFor(shard, kind, ...parts): string;
}
```

MVP: یک Shard سالم (`shard_1`)؛ منطق امتیازدهی واقعی و تست‌شده؛ Providerهای آینده (R2 دوم، S3) با همین اینترفیس. اندروید هرگز به R2 مستقیم وصل نمی‌شود؛ فقط Presigned URL کوتاه‌عمر.

### ۴.۵ سقف‌های Free Tier و بودجه مصرف (طراحی‌شده برای ماندن در رایگان)

| منبع | سقف Free | مصرف طراحی‌شده (تخمین هر کاربر فعال/روز) |
|---|---|---|
| Workers Requests | ۱۰۰k/روز | ~۲۰ → کافی تا ~۵k کاربر فعال روزانه |
| Workers CPU | 10ms/req | <۲ms (بدون هش سنگین، کوئری‌های ایندکس‌دار) |
| D1 Reads / Writes | 5M / 100k ردیف/روز | ~۱۰۰ read / ~۱۰ write → Writes گلوگاه در ~۱۰k DAU |
| R2 | 10GB + 1M/10M ops | JSON پروژه‌ها + Assetها؛ هر پروژه ~MB |

**نتیجه:** MVP کاملاً رایگان تا هزاران کاربر؛ عبور از سقف = ماشه ارتقا به Paid (۵$/ماه)، نه اکانت‌بازی.

---

## ۵. احراز هویت، اشتراک، Free Build

- **Auth:** شماره (Normalize ایران، Unique) + یوزرنیم (Unique) + پسورد (≥۸). Access ‏15m + Refresh چرخشی ‏30d (Hash در D1، Revoke تکی/کلی). Rate-Limit لاگین + Backoff + Salt ساختگی برای کاربر ناموجود (ضد Enumeration).
- **Subscription:** پلن‌ها در جدول `plans` (FREE/MONTHLY/QUARTERLY/YEARLY/LIFETIME)؛ قیمت تومان، قابل‌ویرایش توسط ادمین بدون انتشار نسخه.
- **Free Build — State Machine اتمیک:**
```text
AVAILABLE ──claim──► RESERVED ──success──► CONSUMED
                ▲        │──failure──► AVAILABLE (بازگشت)
   (تراکنش: UPDATE … WHERE state='AVAILABLE'؛ دقیقاً یک برنده در رقابت)
```
Source of Truth = Backend؛ حذف اپ/تعویض دستگاه بی‌اثر.

---

## ۶. Creator Monetization (جدا از Subscription بازیچه)

```text
BAZICHE Subscription (درآمد ما: پلن‌ها، پرداخت Myket در فاز ۶)
        ≠ (کاملاً جدا)
Creator Game Monetization (درآمد سازنده بازی):
  Game → CreatorBillingAccount → BillingProvider (myket|bazaar|google-play)
```

- بازی خروجی با **حساب فروشنده خود سازنده** کار می‌کند؛ پول به‌صورت پیش‌فرض به بازیچه نمی‌آید.
- معماری: `iap-core` (اینترفیس) + `iap-myket` (فاز ۸) + `iap-bazaar` + `iap-play` (بعداً).
- Credential مالی هرگز در APK/کلاینت؛ Verify سمت **سرور سازنده یا سرویس Verify بازیچه (فاز ۸)** با کلیدهای سروری.

---

## ۷. Build System (Game Shell + GitHub Actions)

### ۷.۱ اصل: هیچ کامپایلِ سورسِ پویا

```text
Prebuilt Game Shell (android/game-shell ← فاز ۵، هم‌نسخه با :runtime)
  + Project JSON + Assets + Build Config  →  Gradle assemble →
  apksigner → APK + AAB → R2 → Download
```

### ۷.۲ پایپ‌لاین (قرارداد ثابت، Provider قابل‌تعویض)

```text
POST /builds → Auth → Entitlement (اشتراک یا Free-Claim اتمیک)
→ Validation (JSON/Asset/AppID/Version/Icon/Signing/Billing/Permission)
→ Create Job (QUEUED) → BuildProvider.dispatch → Agent/Runner:
   Docker/Runner → Inject → Compile → Sign → Upload R2 (presigned)
→ Callback → COMPLETED/FAILED (+بازگردانی Free در شکست واقعی)
```

### ۷.۳ GitHub Actions به‌عنوان BuildProvider اول (رایگان)

- فایل: `.github/workflows/game-build.yml` (قابل‌فراخوانی با `workflow_dispatch` از بک‌اند).
- امنیت: انتقال فقط با Presigned URL + باندل رمزنگاری‌شده (AES-256)؛ بدون Credential ابری در گیت‌هاب؛ Keystore هر پروژه رمزنگاری‌شده در R2.
- محدودیت‌های صادقانه: ~۲۰۰۰ دقیقه/ماه (ریپو Private)، سقف Concurrency ‏20، هر Job حداکثر ۶ ساعت، لاگ/آرتیفکت ۹۰ روز. ریپو **Private** (حریم Asset کاربران) با وجود سقف دقیقه.
- ToS: استفاده = CI واقعی (بیلد از سورس ریپو) در مقیاس MVP؛ در Scale تجاری → مهاجرت به `VpsAgentProvider` (اینترفیس آماده).
- `AgentManager`: رجیستری ایجنت‌ها (`agents`: provider، status، capacity، activeJobs، heartbeat، version، region، errorRate)؛ از فاز ۵ با یک ایجنت منطقی `github-actions-1`.

### ۷.۴ Signing

Keystore جدا به‌ازای هر پروژه (ساخته‌شده در اولین بیلد موفق)، AES-GCM در R2 خصوصی، پسوردها فقط در Secrets. هیچ‌چیز در APK/اپ/Git.

---

## ۸. اپ اندروید (ماژول‌ها و فازبندی)

```text
android/
├── app/            # فاز ۱: Shell (Auth/Dashboard/Projects/Settings) + Nav + Theme fa/en + RTL
├── core/common     # فاز ۱: Result، ثابت‌ها
├── core/network    # فاز ۱: Retrofit API + Interceptor/Refresh + DTO
├── core/data       # فاز ۱: Room cache + DataStore session + PBKDF2 + Repositories
├── editor/         # ← فاز ۲ (NOT IMPLEMENTED)
├── runtime/        # ← فاز ۳ (NOT IMPLEMENTED)
├── game-shell/     # ← فاز ۵ (NOT IMPLEMENTED)
├── preview/        # ← فاز ۳ (NOT IMPLEMENTED)
└── iap-*/          # ← فاز ۸ (NOT IMPLEMENTED)
```

آفلاین: Room کش + ذخیره Debounce + `dirty` flag؛ Sync خودکار WorkManager در فاز ۲ (فعلاً کش خواندنی + Sync دستی).

---

## ۹. Sync، بکاپ، امنیت، ادمین (خلاصه اجرایی)

- **Sync:** `rev` + `baseRev`؛ `409 + serverRev + serverCopy` در تضاد؛ Auto-Merge فیلدهای امن + انتخاب دستی در فاز ۲.
- **Backup:** Snapshot روزانه D1→R2 (Cron، فاز ۱۰) + `project_revisions` (از فاز ۱) + Time-Travel ذاتی D1.
- **امنیت:** HTTPS، JWT چرخشی، Rate-Limit چندلایه، Zod + JSON-Schema، SQL پارامتری، اعتبارسنجی MIME/Magic-Byte، Presigned کوتاه‌عمر، R2 خصوصی، Audit، نقش ادمین، ایزولاسیون بیلد (Runner تمیز هر Job + cleanup).
- **ادمین فاز ۱ (skeleton واقعی):** stats، لیست کاربران (بدون هش)، suspend/restore، reset-free-build، مشاهده audit. وب‌UI در فاز ۶+.

---

## ۱۰. پاسخ ۲۰ سؤال (به‌روزشده v1.1)

1. اندروید: Kotlin + Compose Native (AGP 9.4.0 / Kotlin 2.4.20 / SDK 36 قابل‌پیکربندی).
2. موتور: Custom 2D دیتا-محور کامپوننتی (`:runtime`، فاز ۳).
3. Editor: Compose + Canvas تعاملی، تولیدکننده همان JSON (فاز ۲).
4. فرمت: JSON نسخه‌دار (`shared/project-format.schema.json`) + Migration (فاز ۲).
5. بک‌اند: Hono + TS روی Workers + D1 + R2.
6. اسکیما: §۴.۲ / `migrations/001_init.sql`.
7. R2: سه باکت (projects/assets/builds) + Presigned؛ Router امتیازی.
8. Multi-Account: فقط قانونی (Prod/Staging/Backup)؛ MVP تک‌اکانت Free؛ Router آماده Scale.
9. احراز: شماره+یوزر+پسورد با Client-Stretch + Pepper سروری؛ JWT ‏15m + Refresh چرخشی ‏30d.
10. اشتراک: جدول `plans` در DB + `subscriptions` + `entitlements`.
11. Myket Verify: فاز ۶، API رسمی + X-Access-Token + امضای RSA + payload یکتا.
12. Free Build: State Machine اتمیک AVAILABLE→RESERVED→CONSUMED.
13. APK: Game Shell از پیش‌ساخته + تزریق + Gradle + apksigner؛ خروجی APK و AAB.
14. محل بیلد: **GitHub Actions** (MVP رایگان) → VPS Agent در Scale.
15. امضا: Keystore هر پروژه، رمزنگاری‌شده در R2 خصوصی.
16. Sync: rev/baseRev + ‏409 + Merge فاز ۲؛ کش Room از فاز ۱.
17. بکاپ: revisions + snapshot روزانه (فاز ۱۰) + Time-Travel.
18. امنیت: §۹ + ممیزی هر فاز.
19. Scale تا ۱۰۰k: API استیت‌لس + صف + Router + Provider انتزاعی + ایجنت‌های افقی.
20. هزینه: **۰$ تا سقف Free** (هزاران کاربر)؛ سپس ~۵$ Workers + R2 مصرفی + (در Scale) VPS بیلد. دامنه اختیاری.

---

## ۱۱. نقشه فازها (قفل‌شده)

```text
PHASE 1  ✅ این نوبت: Shell + Auth + Backend Core + D1/R2 + Project Core + Admin skeleton + CI + Registries(data)
PHASE 2  Project Sync کامل + Editor MVP (Scene/Object/Inspector/UIBuilder پایه)
PHASE 3  Runtime Core (Component/Event/Scene/Render/Audio minimale)
PHASE 4  Feature/Capability اجرایی + ۸ Game Type Tier-1 + Templateها
PHASE 5  Build System (game-shell + Provider + game-build.yml فعال + Signing)
PHASE 6  Subscription + Myket Billing + Free-Build Gate + Admin Web
PHASE 7  Systems پیشرفته (Inventory/Quest/Shop/AI/Particles/…)
PHASE 8  Creator Monetization (IAP/Ads Providers در بازی خروجی)
PHASE 9  AI Assistant (Scaffolder صادقانه → LLM اختیاری)
PHASE 10 Production Hardening (Monitoring/Backup/DR/Scaling)
```

قانون پایان هر فاز: بیلد سبز + تست سبز + گزارش `ساخته‌شده / NOT IMPLEMENTED / MOCK` + Handoff.

---

### پایان سند v1.1 — مبنای پیاده‌سازی
