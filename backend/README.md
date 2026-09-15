# Baziche API (backend)

Hono + TypeScript روی Cloudflare Workers. دیتای ساخت‌یافته در D1، بایت‌ها در R2.

**توپولوژی ذخیره‌سازی (فاز ۲):** دو دیتابیس D1 در یک اکانت (متصل از طریق همین Worker):
- `baziche-auth` — هویت/سشن/پلن/اشتراک/خرید/ادمین/لاگ (binding: `DB_AUTH`)
- `baziche-data` — پروژه/ریویژن/است/بیلد/شارد/ایجنت (binding: `DB_DATA`)
- ۳ باکت R2: `baziche-projects` / `baziche-assets` / `baziche-builds`

## اجرای محلی (بدون نیاز به اکانت Cloudflare)

```bash
npm install
cp .dev.vars.example .dev.vars   # و مقادیر dev را بگذار (openssl rand -hex 32)
npm run db:local                 # اجرای migration هر دو DB روی D1 محلی
npm run dev                      # http://localhost:8787
npm test                         # تست‌ها (۳۱ تست)
npm run typecheck
```

تست‌ها Migration واقعی را روی SQLite واقعی (sql.js) اجرا می‌کنند — بدون Mock منطق اپ.

## استقرار (خلاصه؛ قدم‌به‌قدم: docs/CLOUDFLARE_SETUP.md)

```bash
npx wrangler login
npm run db:create                 # خروجی: دو database_id (auth + data)
# هر دو database_id را در wrangler.toml بگذار (DB_AUTH و DB_DATA)
npm run db:migrate                # اجرای migration روی هر دو D1 واقعی
npx wrangler r2 bucket create baziche-projects
npx wrangler r2 bucket create baziche-assets
npx wrangler r2 bucket create baziche-builds
npx wrangler secret put JWT_SECRET
npx wrangler secret put PASSWORD_PEPPER
npx wrangler secret put R2_ACCESS_KEY_ID
npx wrangler secret put R2_SECRET_ACCESS_KEY
npm run deploy
```

اولین ادمین (بعد از ثبت‌نام خودت در اپ):

```bash
npx wrangler d1 execute baziche-auth --remote \
  --command "INSERT INTO admins (user_id, role, created_at) VALUES ('<USER_ID>', 'admin', strftime('%s','now'))"
```

## قراردادها

- خطاها: `shared/error-codes.md`
- OpenAPI: `shared/api-v1.openAPI.yaml`
- فرمت پروژه: `shared/project-format.schema.json`
