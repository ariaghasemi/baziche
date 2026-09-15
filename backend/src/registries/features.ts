// FeatureRegistry v1 — features compose capabilities. Game types compose features.
// Status: 'mvp' (Phase 3-4 runtime) | 'later' (Phase 7+) | 'reserved' (roadmap, no code).

export type FeatureStatus = 'mvp' | 'later' | 'reserved';

export interface FeatureDef {
  id: string;
  name: string;
  status: FeatureStatus;
  capabilities: string[]; // CAP-xxxx refs
  systems: string[]; // runtime systems required
}

export const FEATURES: FeatureDef[] = [
  { id: 'physics', name: 'Physics', status: 'mvp', capabilities: ['CAP-0001', 'CAP-0006', 'CAP-0031'], systems: ['physics'] },
  { id: 'animation', name: 'Animation', status: 'mvp', capabilities: ['CAP-0014', 'CAP-0002', 'CAP-0003'], systems: ['animation'] },
  { id: 'particles', name: 'Particles', status: 'later', capabilities: ['CAP-0005'], systems: ['particles'] },
  { id: 'audio', name: 'Audio', status: 'mvp', capabilities: ['CAP-0013'], systems: ['audio'] },
  { id: 'dialogue', name: 'Dialogue', status: 'later', capabilities: ['CAP-0016', 'CAP-0017'], systems: ['dialogue'] },
  { id: 'inventory', name: 'Inventory', status: 'later', capabilities: ['CAP-0007', 'CAP-0008'], systems: ['inventory'] },
  { id: 'quest', name: 'Quest', status: 'later', capabilities: ['CAP-0007', 'CAP-0009'], systems: ['quest'] },
  { id: 'achievement', name: 'Achievement', status: 'later', capabilities: ['CAP-0020'], systems: ['achievement'] },
  { id: 'shop', name: 'Shop', status: 'later', capabilities: ['CAP-0016', 'CAP-0021', 'CAP-0029'], systems: ['shop', 'economy'] },
  { id: 'economy', name: 'Economy', status: 'later', capabilities: ['CAP-0021', 'CAP-0007'], systems: ['economy'] },
  { id: 'save_system', name: 'Save System', status: 'mvp', capabilities: ['CAP-0018', 'CAP-0019', 'CAP-0025'], systems: ['save'] },
  { id: 'leaderboard', name: 'Leaderboard', status: 'later', capabilities: ['CAP-0020'], systems: ['score'] },
  { id: 'ads', name: 'Ads', status: 'later', capabilities: ['CAP-0028'], systems: ['ads'] },
  { id: 'iap', name: 'In-App Purchase', status: 'later', capabilities: ['CAP-0029'], systems: ['iap'] },
  { id: 'notifications', name: 'Notifications', status: 'later', capabilities: [], systems: [] },
  { id: 'analytics', name: 'Analytics', status: 'later', capabilities: [], systems: [] },
  { id: 'ai', name: 'AI Behaviors', status: 'later', capabilities: ['CAP-0001'], systems: ['ai'] },
  { id: 'level_system', name: 'Level System', status: 'mvp', capabilities: ['CAP-0015', 'CAP-0024'], systems: ['levels'] },
  { id: 'checkpoint', name: 'Checkpoint', status: 'mvp', capabilities: ['CAP-0025', 'CAP-0019'], systems: ['save'] },
  { id: 'input', name: 'Input & UI', status: 'mvp', capabilities: ['CAP-0016', 'CAP-0017', 'CAP-0030'], systems: ['input', 'ui'] },
];

export const FEATURE_IDS = new Set(FEATURES.map((x) => x.id));
