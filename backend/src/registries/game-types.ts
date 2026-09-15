// GameTypeRegistry v1 — DATA ONLY. A game type NEVER contains logic;
// it only composes features + default components + editor preset + template.
// Tier 1 = implemented in Phase 3-4. Tier 2/3 = composition ready, runtime later.

export type GameTier = 1 | 2 | 3;

export interface GameTypeDef {
  id: string;
  name: string;
  tier: GameTier;
  features: string[]; // FeatureRegistry refs
  defaultComponents: string[]; // Component catalog refs (player/object presets)
  orientation: 'portrait' | 'landscape';
  description: string;
}

const CORE = ['input', 'audio', 'animation', 'save_system', 'level_system'];

export const GAME_TYPES: GameTypeDef[] = [
  // Tier 1 — MVP
  { id: 'puzzle', name: 'Puzzle', tier: 1, features: [...CORE, 'checkpoint'], defaultComponents: ['Transform', 'Sprite', 'Input', 'Timer'], orientation: 'portrait', description: 'Tile/board puzzles with goals and moves' },
  { id: 'quiz', name: 'Quiz', tier: 1, features: [...CORE], defaultComponents: ['Transform', 'Text', 'Button', 'Timer', 'Score'], orientation: 'portrait', description: 'Question/answer trivia with score and timer' },
  { id: 'word', name: 'Word Game', tier: 1, features: [...CORE], defaultComponents: ['Transform', 'Text', 'Button', 'Score'], orientation: 'portrait', description: 'Word puzzles, letters and dictionaries' },
  { id: 'arcade', name: 'Arcade', tier: 1, features: [...CORE, 'physics'], defaultComponents: ['Transform', 'Sprite', 'Movement', 'Collider', 'Score'], orientation: 'portrait', description: 'Fast score-attack arcade games' },
  { id: 'runner', name: 'Endless Runner', tier: 1, features: [...CORE, 'physics', 'checkpoint'], defaultComponents: ['Transform', 'Sprite', 'Movement', 'Collider', 'Spawner', 'Score'], orientation: 'landscape', description: 'Auto-run, dodge, survive, high score' },
  { id: 'platformer', name: 'Platformer', tier: 1, features: [...CORE, 'physics', 'checkpoint'], defaultComponents: ['Transform', 'Sprite', 'Movement', 'Collider', 'RigidBody', 'Health', 'Camera'], orientation: 'landscape', description: 'Run, jump, gravity, checkpoints' },
  { id: 'match3', name: 'Match-3', tier: 1, features: [...CORE], defaultComponents: ['Transform', 'Sprite', 'Input', 'Score', 'Timer'], orientation: 'portrait', description: 'Match-3 boards with goals and boosters' },
  { id: 'card', name: 'Card Game', tier: 1, features: [...CORE], defaultComponents: ['Transform', 'Sprite', 'Text', 'Button', 'Score'], orientation: 'portrait', description: 'Card hands, decks and turns' },
  // Tier 2 — Phase 7 (composition ready)
  { id: 'board', name: 'Board Game', tier: 2, features: [...CORE, 'ai'], defaultComponents: ['Transform', 'Sprite', 'Text'], orientation: 'landscape', description: 'Turn-based boards, dice and pieces' },
  { id: 'towerdefense', name: 'Tower Defense', tier: 2, features: [...CORE, 'ai', 'economy'], defaultComponents: ['Transform', 'Sprite', 'Spawner', 'Health', 'Damage'], orientation: 'landscape', description: 'Waves, towers and economy' },
  { id: 'racing', name: 'Racing', tier: 2, features: [...CORE, 'physics', 'ai'], defaultComponents: ['Transform', 'Sprite', 'Movement', 'Camera'], orientation: 'landscape', description: 'Laps, speed and opponents' },
  { id: 'strategy', name: 'Strategy', tier: 2, features: [...CORE, 'ai', 'economy', 'save_system'], defaultComponents: ['Transform', 'Sprite', 'Text'], orientation: 'landscape', description: 'Resources, units and tactics' },
  { id: 'adventure', name: 'Adventure', tier: 2, features: [...CORE, 'dialogue', 'inventory', 'quest'], defaultComponents: ['Transform', 'Sprite', 'Dialogue', 'Inventory'], orientation: 'landscape', description: 'Story, exploration and quests' },
  { id: 'rpg', name: 'RPG', tier: 2, features: [...CORE, 'dialogue', 'inventory', 'quest', 'achievement'], defaultComponents: ['Transform', 'Sprite', 'Health', 'Inventory'], orientation: 'landscape', description: 'Stats, gear, quests and story' },
  { id: 'simulation', name: 'Simulation', tier: 2, features: [...CORE, 'economy', 'analytics'], defaultComponents: ['Transform', 'Text', 'Timer', 'Currency'], orientation: 'portrait', description: 'Systems and management sims' },
  { id: 'idle', name: 'Idle Game', tier: 2, features: [...CORE, 'economy', 'notifications'], defaultComponents: ['Transform', 'Text', 'Timer', 'Currency'], orientation: 'portrait', description: 'Incremental and idle progression' },
  // Tier 3 — roadmap (composition ready)
  { id: 'sports', name: 'Sports', tier: 3, features: [...CORE, 'physics', 'ai', 'leaderboard'], defaultComponents: ['Transform', 'Sprite', 'Movement'], orientation: 'landscape', description: 'Matches, scores and seasons' },
  { id: 'tycoon', name: 'Tycoon', tier: 3, features: [...CORE, 'economy', 'shop'], defaultComponents: ['Transform', 'Text', 'Currency'], orientation: 'landscape', description: 'Build and manage businesses' },
  { id: 'survival', name: 'Survival', tier: 3, features: [...CORE, 'ai', 'inventory', 'checkpoint'], defaultComponents: ['Transform', 'Sprite', 'Health', 'Inventory'], orientation: 'landscape', description: 'Gather, craft, survive' },
  { id: 'educational', name: 'Educational', tier: 3, features: [...CORE, 'achievement'], defaultComponents: ['Transform', 'Text', 'Button', 'Score'], orientation: 'portrait', description: 'Learning games for kids and schools' },
];

export const GAME_TYPE_IDS = new Set(GAME_TYPES.map((x) => x.id));
export const TIER1_IDS = GAME_TYPES.filter((g) => g.tier === 1).map((g) => g.id);
