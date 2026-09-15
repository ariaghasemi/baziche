# 🐙 راهنمای GitHub (قدم‌به‌قدم)

> چرا؟ اجرای خودکار CI (بیلد + تست) روی هر Push — رایگان.
> چرا Private؟ Assetها و بیلدهای کاربران در آینده نباید عمومی باشند.

## گام ۱ — ساخت ریپو

1. برو به https://github.com/new
2. Repository name: `baziche`
3. Visibility: **Private** ✅ (مهم)
4. **هیچکدام** از گزینه‌های Add README / .gitignore / license را تیک نزن (ما از قبل داریم).
5. **Create repository**.

آدرس ریپو شبیه این می‌شود: `https://github.com/<username>/baziche`

> ✅ SAFE TO SEND: همین URL را برای من بفرست.
> جمله‌ای که بگویی: «ریپو ساخته شد: <URL>»

## گام ۲ — Push کردن کد

در پوشه `baziche` (جایی که این فایل‌هاست):

```bash
git init -b main
git add .
git commit -m "feat: baziche phase 1 (shell, auth, backend, projects, admin, ci)"
git remote add origin https://github.com/<username>/baziche.git
git push -u origin main
```

اگر Git از تو username/password خواست: پسورد گیت‌هاب کار نمی‌کند — باید Personal Access Token بسازی:

1. گیت‌هاب → عکس پروفایل → **Settings** → **Developer settings** → **Personal access tokens** → **Tokens (classic)**.
2. **Generate new token (classic)** → اسم: `baziche-push` → تیک `repo` → Generate.
3. توکن را به‌جای پسورد بده (یک‌بار؛ کش می‌شود).

> ❌ NEVER SEND IN CHAT: این توکن را برای من نفرست. فقط روی کامپیوتر خودت استفاده کن.

## گام ۳ — سبز شدن CI

1. در صفحه ریپو تب **Actions** را باز کن.
2. دو ورک‌فلو می‌بینی: `android-ci` و `backend-ci` — باید هر دو **سبز (✓)** شوند (۵–۱۵ دقیقه اول).
3. اگر قرمز شدند: روی Run کلیک کن، لاگ خطا را بخوان و متن خطا را برای من بفرست تا درستش کنم.

## گام ۴ — دانلود APK تست (اختیاری)

بعد از سبز شدن `android-ci`:

1. تب **Actions** → آخرین Run سبز → پایین صفحه **Artifacts** → `baziche-debug-apk` را دانلود کن.
2. روی گوشی نصب کن (این نسخه Debug به API محلی وصل نمی‌شود؛ برای تست واقعی گام ۸ CLOUDFLARE_SETUP را انجام بده).

## سکرت‌های گیت‌هاب در فاز ۱

```text
NO SECRETS REQUIRED — در فاز ۱ هیچ GitHub Secret لازم نیست.
(GITHUB_DISPATCH_TOKEN در فاز ۵ لازم می‌شود — آن‌وقت آموزشش می‌آید.)
```
