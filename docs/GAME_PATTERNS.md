# 🎮 الگوهای بازی (Inventory / Quest / Shop) — بدون CAP جدید

همه‌ی این الگوها فقط با CAPهای موجود ساخته می‌شوند: `CAP-0007` (Set Variable)،
`CAP-0009` (If)، `CAP-0010` (Compare)، `CAP-0011` (Timer). موتور هیچ CAP جدیدی
نمی‌خواهد؛ قراردادها در `runtime/.../Patterns.kt` پیاده و تست شده‌اند.

## ۱) موجودی (Inventory)

- یک متغیر **STRING** به اسم `inventory` (یا هر slot دلخواه) تعریف کن.
- مقدارش یک JSON است: `{"sword": 1, "potion": 5}`.
- خالی = `{}`.

```json
{ "name": "inventory", "type": "string", "initial": "{}" }
```

- گرفتن آیتم: اکشن `CAP-0007` روی `inventory` (میزبان/اپ مقدار جدید را با
  `Inventory.add()` می‌سازد) یا ایونت timer که اسکریپت ادیتور مقدار را می‌نویسد.
- شرط «شمشیر داری؟»: `CAP-0009` با `{"a": "$has_sword", ...}` که `has_sword`
  یک BOOL کمکی است (میزبان با `Inventory.countOf() > 0` به‌روز می‌کند).

## ۲) مأموریت (Quest)

- به‌ازای هر کوئست یک متغیر **NUMBER** به اسم `quest_<id>` (صفر = شروع‌نشده).
- مرحله‌ها عدد صحیح‌اند: ۱ = قبول، ۲ = میانی، ...، `999` = تمام‌شده.

```json
{ "name": "quest_rescue", "type": "number", "initial": 0 }
```

- پیشرفت: `CAP-0007` مقدار مرحله‌ی بعد را بنویسد (میزبان: `Quests.advance()`).
- شرط: `CAP-0010` مقایسه‌ی `$quest_rescue >= 2`.

## ۳) فروشگاه (Shop)

- یک متغیر **NUMBER** ارز (مثلاً `coins`) + یک slot موجودی.
- خرید = اتمیک: کم‌کردن سکه و اضافه‌کردن آیتم با هم (`Shop.buy()`)؛ اگر
  موجودی جا نشود، سکه برمی‌گردد. فروش هم همین‌طور (`Shop.sell()`).

```json
[
  { "name": "coins", "type": "number", "initial": 100 },
  { "name": "inventory", "type": "string", "initial": "{}" }
]
```

- دکمه‌ی «خرید شمشیر (۵۰ سکه)»: ایونت tap → شرط `CAP-0010` (`$coins >= 50`) →
  در شاخه‌ی then میزبان `Shop.buy(vars, "coins", 50.0, "sword", "inventory")`
  را صدا می‌زند و نتیجه را در BOOL کمکی `shop_ok` می‌گذارد تا پیام موفقیت/خطا
  با `CAP-0016/0017` (Show/Hide UI) نمایش داده شود.

## ۴) ذرات محیطی + لرزش دوربین (موتور، خودکار)

```json
"systems": {
  "ambientParticles": {
    "enabled": true, "ratePerSec": 25, "max": 120,
    "speed": 40, "size": 4, "color": "#88CCFF",
    "lifeMs": 3000, "gravity": 0, "spread": 1
  }
}
```

- `CAP-0022` (Damage) خودش لرزش دوربین + انفجار ذرات قرمز می‌سازد.
- `GameEngine.addTrauma(0..1)` برای لرزش دستی (تست/میزبان).
