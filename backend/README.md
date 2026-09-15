# Baziche API (backend)

Hono + TypeScript روی Cloudflare Workers. دیتای ساخت‌یافته در D1، بایت‌ها در R2.

## اجرای محلی (بدون نیاز به اکانت Cloudflare)

```bash
npm install
cp .dev.vars.example .dev.vars   # و مقادیر dev را بگذار (openssl rand -hex 32)
npm run db:local                 # اجرای migration روی D1 محلی
npm run dev                      # http://localhost:8787
npm test                         # تست‌ها
npm run typecheck
```

تست‌ها Migration واقعی را روی SQLite واقعی (sql.js) اجرا می‌کنند — بدون Mock منطق اپ.

## استقرار (خلاصه؛ قدم‌به‌قدم: docs/CLOUDFLARE_SETUP.md)

```bash
npx wrangler login
npm run db:create                 # خروجی: database_id
# database_id را در wrangler.toml بگذار
npm run db:migrate                # اجرای migration روی D1 واقعی
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
npx wrangler d1 execute baziche-db --remote \
  --command "INSERT INTO admins (user_id, role, created_at) VALUES ('<USER_ID>', 'admin', strftime('%s','now'))"
```

## قراردادها

- خطاها: `shared/error-codes.md`
- OpenAPI: `shared/api-v1.openAPI.yaml`
- فرمت پروژه: `shared/project-format.schema.json`
