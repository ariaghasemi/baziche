// Component catalog mirror (source of truth: shared/registries/components.json).
// Backend uses it to validate project JSON object components (Phase 2+ strict mode).
// Keep in sync with shared/registries/components.json (integrity test enforces it).

export const COMPONENT_NAMES = [
  'Transform', 'Sprite', 'Text', 'Collider', 'RigidBody', 'Movement', 'Health',
  'Damage', 'Animation', 'Audio', 'Particle', 'Camera', 'UI', 'Button', 'Input',
  'Timer', 'Inventory', 'Quest', 'Dialogue', 'AI', 'Spawner', 'Trigger',
  'Checkpoint', 'Score', 'Currency', 'SaveData',
] as const;

export const COMPONENT_SET = new Set<string>(COMPONENT_NAMES);
