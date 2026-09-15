// CapabilityRegistry v1 — stable CAP-xxxx ids. Designed for 1000+.
// MVP ships ~30 real capabilities; runtime execution lands in Phase 3.
// Contract: { id, name, category, version, params: JSON-schema-lite, returns }.

export type CapCategory =
  | 'transform' | 'lifecycle' | 'variable' | 'flow' | 'audio' | 'scene'
  | 'ui' | 'save' | 'score' | 'combat' | 'system' | 'monetization';

export interface CapabilityDef {
  id: string;
  name: string;
  category: CapCategory;
  version: number;
  description: string;
  params: Record<string, { type: string; required: boolean; default?: unknown }>;
}

const c = (
  id: string, name: string, category: CapCategory, description: string,
  params: CapabilityDef['params'] = {},
): CapabilityDef => ({ id, name, category, version: 1, description, params });

export const CAPABILITIES: CapabilityDef[] = [
  c('CAP-0001', 'Move Object', 'transform', 'Move object by dx/dy over time', { target: { type: 'string', required: true }, dx: { type: 'number', required: true }, dy: { type: 'number', required: true }, durationMs: { type: 'number', required: false, default: 0 } }),
  c('CAP-0002', 'Rotate Object', 'transform', 'Rotate object to angle', { target: { type: 'string', required: true }, angle: { type: 'number', required: true }, durationMs: { type: 'number', required: false, default: 0 } }),
  c('CAP-0003', 'Scale Object', 'transform', 'Scale object to sx/sy', { target: { type: 'string', required: true }, sx: { type: 'number', required: true }, sy: { type: 'number', required: true } }),
  c('CAP-0004', 'Destroy Object', 'lifecycle', 'Remove object from scene', { target: { type: 'string', required: true } }),
  c('CAP-0005', 'Spawn Object', 'lifecycle', 'Spawn prefab instance at x/y', { prefab: { type: 'string', required: true }, x: { type: 'number', required: true }, y: { type: 'number', required: true } }),
  c('CAP-0006', 'Set Position', 'transform', 'Teleport object to x/y', { target: { type: 'string', required: true }, x: { type: 'number', required: true }, y: { type: 'number', required: true } }),
  c('CAP-0007', 'Set Variable', 'variable', 'Set variable value', { name: { type: 'string', required: true }, value: { type: 'any', required: true } }),
  c('CAP-0008', 'Get Variable', 'variable', 'Read variable into action context', { name: { type: 'string', required: true } }),
  c('CAP-0009', 'If Condition', 'flow', 'Branch on condition', { condition: { type: 'object', required: true } }),
  c('CAP-0010', 'Compare Values', 'flow', 'Compare a op b', { a: { type: 'any', required: true }, op: { type: 'string', required: true }, b: { type: 'any', required: true } }),
  c('CAP-0011', 'Start Timer', 'flow', 'Start named timer', { name: { type: 'string', required: true }, durationMs: { type: 'number', required: true }, repeat: { type: 'boolean', required: false, default: false } }),
  c('CAP-0012', 'Delay', 'flow', 'Wait then continue', { durationMs: { type: 'number', required: true } }),
  c('CAP-0013', 'Play Sound', 'audio', 'Play one-shot SFX', { assetId: { type: 'string', required: true }, volume: { type: 'number', required: false, default: 1 } }),
  c('CAP-0014', 'Play Animation', 'scene', 'Play animation on target', { target: { type: 'string', required: true }, animationId: { type: 'string', required: true } }),
  c('CAP-0015', 'Change Scene', 'scene', 'Open scene with transition', { sceneId: { type: 'string', required: true }, transition: { type: 'string', required: false, default: 'fade' } }),
  c('CAP-0016', 'Show UI', 'ui', 'Show UI screen/element', { screenId: { type: 'string', required: true } }),
  c('CAP-0017', 'Hide UI', 'ui', 'Hide UI screen/element', { screenId: { type: 'string', required: true } }),
  c('CAP-0018', 'Save Game', 'save', 'Persist save keys now', { slot: { type: 'string', required: false, default: 'auto' } }),
  c('CAP-0019', 'Load Game', 'save', 'Load save slot', { slot: { type: 'string', required: false, default: 'auto' } }),
  c('CAP-0020', 'Add Score', 'score', 'Add points to score variable', { variable: { type: 'string', required: true }, amount: { type: 'number', required: true } }),
  c('CAP-0021', 'Add Currency', 'score', 'Add currency units', { variable: { type: 'string', required: true }, amount: { type: 'number', required: true } }),
  c('CAP-0022', 'Damage', 'combat', 'Deal damage to target', { target: { type: 'string', required: true }, amount: { type: 'number', required: true } }),
  c('CAP-0023', 'Heal', 'combat', 'Restore health', { target: { type: 'string', required: true }, amount: { type: 'number', required: true } }),
  c('CAP-0024', 'Unlock Level', 'scene', 'Unlock level by id', { levelId: { type: 'string', required: true } }),
  c('CAP-0025', 'Checkpoint Save', 'save', 'Store respawn point', { checkpointId: { type: 'string', required: true } }),
  c('CAP-0026', 'Vibrate', 'system', 'Haptic pulse', { durationMs: { type: 'number', required: false, default: 50 } }),
  c('CAP-0027', 'Open Link', 'system', 'Open URL/deep-link', { url: { type: 'string', required: true } }),
  c('CAP-0028', 'Show Rewarded Ad', 'monetization', 'Show rewarded ad (provider adapter)', { placement: { type: 'string', required: false, default: 'default' } }),
  c('CAP-0029', 'Purchase Product', 'monetization', 'Start IAP flow for sku', { sku: { type: 'string', required: true } }),
  c('CAP-0030', 'Navigate Back', 'ui', 'Pop navigation stack', {}),
  c('CAP-0031', 'Set Gravity', 'scene', 'Set world gravity', { gx: { type: 'number', required: true }, gy: { type: 'number', required: true } }),
  c('CAP-0032', 'Camera Follow', 'scene', 'Camera follows target', { target: { type: 'string', required: true } }),
];

export const CAPABILITY_IDS = new Set(CAPABILITIES.map((x) => x.id));
export const REGISTRIES_VERSION = 1;
