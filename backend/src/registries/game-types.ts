// GameTypeRegistry v1 — DATA ONLY. A game type NEVER contains logic;
// it only composes features + default components + editor preset + template.

export type GameTier = 1 | 2 | 3;

export interface GameTypeDef {
  id: string;
  name: string;
  tier: GameTier;
  features: string[]; // FeatureRegistry refs
  defaultComponents: string[]; // Component catalog refs (player/object presets)
  orientation: 'portrait' | 'landscape';
  description: string;
  nameFa: string;
  descriptionFa: string;
}

const CORE = ['input', 'audio', 'animation', 'save_system', 'level_system'];

export const GAME_TYPES: GameTypeDef[] = [
  // ---------- Tier 1 (MVP — 8 game types) ----------
  {
    id: 'puzzle',
    name: 'Puzzle',
    nameFa: 'معمایی و جورچین',
    tier: 1,
    features: [...CORE, 'checkpoint'],
    defaultComponents: ['Transform', 'Sprite', 'Input', 'Timer'],
    orientation: 'portrait',
    description: 'Tile/board puzzles with goals and moves',
    descriptionFa: 'پازل‌های چیدمانی، جورچین و حل معماهای تصویری',
  },
  {
    id: 'quiz',
    name: 'Quiz',
    nameFa: 'کوئیز و دانستنی‌ها',
    tier: 1,
    features: [...CORE],
    defaultComponents: ['Transform', 'Text', 'Button', 'Timer', 'Score'],
    orientation: 'portrait',
    description: 'Question/answer trivia with score and timer',
    descriptionFa: 'بازی‌های چهارگزینه‌ای، مسابقه هوش و اطلاعات عمومی',
  },
  {
    id: 'word',
    name: 'Word Game',
    nameFa: 'کلمات و حدس واژه',
    tier: 1,
    features: [...CORE],
    defaultComponents: ['Transform', 'Text', 'Button', 'Score'],
    orientation: 'portrait',
    description: 'Word puzzles, letters and dictionaries',
    descriptionFa: 'حدس کلمات، اتصال حروف و جدول‌های فارسی',
  },
  {
    id: 'arcade',
    name: 'Arcade',
    nameFa: 'آرکید و رکوردی',
    tier: 1,
    features: [...CORE, 'physics'],
    defaultComponents: ['Transform', 'Sprite', 'Movement', 'Collider', 'Score'],
    orientation: 'portrait',
    description: 'Fast score-attack arcade games',
    descriptionFa: 'بازی‌های رکوردی و هیجانی سریع با عکس‌العمل بالا',
  },
  {
    id: 'runner',
    name: 'Endless Runner',
    nameFa: 'دونده بی‌پایان',
    tier: 1,
    features: [...CORE, 'physics', 'checkpoint'],
    defaultComponents: ['Transform', 'Sprite', 'Movement', 'Collider', 'Spawner', 'Score'],
    orientation: 'landscape',
    description: 'Auto-run, dodge, survive, high score',
    descriptionFa: 'دویدن، پرش از روی موانع و جمع‌آوری سکه‌ها',
  },
  {
    id: 'platformer',
    name: 'Platformer',
    nameFa: 'سکوبازی و پرش',
    tier: 1,
    features: [...CORE, 'physics', 'checkpoint'],
    defaultComponents: ['Transform', 'Sprite', 'Movement', 'Collider', 'RigidBody', 'Health', 'Camera'],
    orientation: 'landscape',
    description: 'Run, jump, gravity, checkpoints',
    descriptionFa: 'پرش میان سکوها، شکست دشمنان و رسیدن به انتهای مرحله',
  },
  {
    id: 'match3',
    name: 'Match-3',
    nameFa: 'تطبیق سه تایی',
    tier: 1,
    features: [...CORE],
    defaultComponents: ['Transform', 'Sprite', 'Input', 'Score', 'Timer'],
    orientation: 'portrait',
    description: 'Match-3 boards with goals and boosters',
    descriptionFa: 'اتصال و حذف مهره‌های هم‌رنگ شبیه کندی کراش',
  },
  {
    id: 'card',
    name: 'Card Game',
    nameFa: 'کارتی و پاسور',
    tier: 1,
    features: [...CORE],
    defaultComponents: ['Transform', 'Sprite', 'Text', 'Button', 'Score'],
    orientation: 'portrait',
    description: 'Card hands, decks and turns',
    descriptionFa: 'دست‌های کارت، نوبت‌بندی و بازی‌های کارتی',
  },

  // ---------- Tier 2 (Phase 7 — 8 game types) ----------
  {
    id: 'board',
    name: 'Board Game',
    nameFa: 'تخته و منچ',
    tier: 2,
    features: [...CORE, 'ai'],
    defaultComponents: ['Transform', 'Sprite', 'Text'],
    orientation: 'landscape',
    description: 'Turn-based boards, dice and pieces',
    descriptionFa: 'بازی‌های نوبتی، پرتاب تاس، منچ و تخته',
  },
  {
    id: 'towerdefense',
    name: 'Tower Defense',
    nameFa: 'دفاع از قلعه',
    tier: 2,
    features: [...CORE, 'ai', 'economy'],
    defaultComponents: ['Transform', 'Sprite', 'Spawner', 'Health', 'Damage'],
    orientation: 'landscape',
    description: 'Waves, towers and economy',
    descriptionFa: 'ساخت برج‌های دفاعی و مقابله با موج‌های حمله',
  },
  {
    id: 'racing',
    name: 'Racing',
    nameFa: 'ماشینی و مسابقه‌ای',
    tier: 2,
    features: [...CORE, 'physics', 'ai'],
    defaultComponents: ['Transform', 'Sprite', 'Movement', 'Camera'],
    orientation: 'landscape',
    description: 'Laps, speed and opponents',
    descriptionFa: 'سرعت، سبقت، لایی‌کشی و پیست‌های مسابقه‌ای',
  },
  {
    id: 'shooter',
    name: 'Shooter',
    nameFa: 'شوتر و تیراندازی',
    tier: 2,
    features: [...CORE, 'physics', 'ai'],
    defaultComponents: ['Transform', 'Sprite', 'Movement', 'Collider', 'Spawner', 'Health'],
    orientation: 'landscape',
    description: 'Target shooting and projectile defense',
    descriptionFa: 'شلیک به هدف‌ها، سفینه‌های فضایی و دفاع موشکی',
  },
  {
    id: 'adventure',
    name: 'Adventure',
    nameFa: 'ماجراجویی و داستانی',
    tier: 2,
    features: [...CORE, 'dialogue', 'inventory', 'quest'],
    defaultComponents: ['Transform', 'Sprite', 'Dialogue', 'Inventory'],
    orientation: 'landscape',
    description: 'Story, exploration and quests',
    descriptionFa: 'کاوش در دنیاهای ناشناخته و مکالمه با شخصیت‌ها',
  },
  {
    id: 'idle',
    name: 'Idle Game',
    nameFa: 'آیدل و کلیکی',
    tier: 2,
    features: [...CORE, 'economy', 'notifications'],
    defaultComponents: ['Transform', 'Text', 'Timer', 'Currency'],
    orientation: 'portrait',
    description: 'Incremental and idle progression',
    descriptionFa: 'کلیک، ارتقا و جمع‌آوری ثروت حتی در حالت آفلاین',
  },
  {
    id: 'tycoon',
    name: 'Tycoon',
    nameFa: 'سرمایه‌داری و مدیریت',
    tier: 2,
    features: [...CORE, 'economy', 'shop'],
    defaultComponents: ['Transform', 'Text', 'Currency'],
    orientation: 'landscape',
    description: 'Build and manage businesses',
    descriptionFa: 'مدیریت رستوران، فرودگاه یا شهر و کسب درآمد',
  },
  {
    id: 'merge',
    name: 'Merge Game',
    nameFa: 'ترکیب و ادغام',
    tier: 2,
    features: [...CORE, 'economy'],
    defaultComponents: ['Transform', 'Sprite', 'Input', 'Timer'],
    orientation: 'portrait',
    description: 'Merge identical items to level up',
    descriptionFa: 'ترکیب دو مهره یا شخصیت مشابه برای ارتقا',
  },

  // ---------- Tier 3 (Roadmap — 4 game types) ----------
  {
    id: 'survival',
    name: 'Survival',
    nameFa: 'بقا و نجات',
    tier: 3,
    features: [...CORE, 'ai', 'inventory', 'checkpoint'],
    defaultComponents: ['Transform', 'Sprite', 'Health', 'Inventory'],
    orientation: 'landscape',
    description: 'Gather, craft, survive',
    descriptionFa: 'جمع‌آوری منابع، ساخت پناهگاه و زنده ماندن',
  },
  {
    id: 'detective',
    name: 'Detective',
    nameFa: 'کارآگاهی و اشیاء مخفی',
    tier: 3,
    features: [...CORE, 'dialogue', 'inventory', 'quest'],
    defaultComponents: ['Transform', 'Sprite', 'Dialogue', 'Inventory'],
    orientation: 'landscape',
    description: 'Find clues, solve mysteries and interrogate',
    descriptionFa: 'پیدا کردن سرنخ‌ها، بازجویی و حل پرونده‌های مرموز',
  },
  {
    id: 'zombie',
    name: 'Zombie Defense',
    nameFa: 'دفاع در برابر زامبی‌ها',
    tier: 3,
    features: [...CORE, 'ai', 'physics'],
    defaultComponents: ['Transform', 'Sprite', 'Spawner', 'Health', 'Collider'],
    orientation: 'landscape',
    description: 'Defend barricades against waves of undead',
    descriptionFa: 'سنگربندی، شلیک و دفاع در برابر هجوم زامبی‌ها',
  },
  {
    id: 'minigames',
    name: 'Mini Games',
    nameFa: 'مینی‌گیم‌های متنوع',
    tier: 3,
    features: [...CORE, 'leaderboard'],
    defaultComponents: ['Transform', 'Sprite', 'Button', 'Score'],
    orientation: 'portrait',
    description: 'Collection of quick fun mini-challenges',
    descriptionFa: 'مجموعه‌ای از بازی‌های کوچک، جذاب و متنوع در یک پکیج',
  },
];

export const GAME_TYPE_IDS = new Set(GAME_TYPES.map((x) => x.id));
export const TIER1_IDS = GAME_TYPES.filter((g) => g.tier === 1).map((g) => g.id);
