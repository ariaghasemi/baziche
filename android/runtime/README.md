# :runtime — Baziche headless game engine (Phase 3)

Pure Kotlin/JVM. No Android imports: runs in unit tests, in `:preview`, and later in `:game-shell`.

- **Load:** `GameEngine.load(projectJson)` — validates `formatVersion == 1`, lenient-skips broken entries.
- **Run:** `start()` → `tap(x, y)` → `tick(dtMs)` (host-driven loop) → `snapshot()` for the canvas.
- **Effects:** `AudioSink / HapticSink / SystemSink / SaveSink` ports (Preview provides Android impls).
- **Honesty rule:** anything unimplemented degrades to a drained warning (`drainWarnings()`), never a silent fake.

Triggers: `start`, `sceneEnter {sceneId?}`, `tap {target?}`, `timer {name}`.
Conditions: `{ a, op, b }`, ops `== != > < >= <=`, operands may be `$variable` / `$last`.

## Capability support matrix (Phase 3)

| CAP | Name | Status |
|---|---|---|
| 0001–0003 | Move / Rotate / Scale (Move+Rotate tweened) | ✅ |
| 0004–0006 | Destroy / Spawn / Set Position | ✅ |
| 0007–0010 | Set/Get Variable / If / Compare (`$last`) | ✅ |
| 0011–0012 | Start Timer / Delay | ✅ |
| 0013 | Play Sound (asset validated vs project assets[]) | ✅ |
| 0014 | Play Animation (keyframe player: x/y/w/h/rotation/opacity, loop) | ✅ |
| 0015 | Change Scene (+back stack) | ✅ |
| 0016–0017 | Show/Hide UI (state tracked; rendered in Phase 4 UIBuilder) | 🟨 PARTIAL |
| 0018–0019 | Save/Load Game (scene+vars+unlocked per slot) | ✅ |
| 0020–0021 | Add Score/Currency | ✅ |
| 0022–0023 | Damage/Heal (Health clamp, invincible window, regen) | ✅ |
| 0024 | Unlock Level | ✅ |
| 0025 | Checkpoint | ❌ Phase 4 respawn (warns) |
| 0026–0027 | Vibrate / Open Link (URL validated) | ✅ |
| 0028–0029 | Rewarded Ad / Purchase | ❌ Phase 8 (warns) |
| 0030–0032 | Navigate Back / Set Gravity / Camera Follow | ✅ |

Audio `autoplay`/`loop` components play on scene enter. Hit-testing ignores rotation (documented; exact rotated picking in Phase 4).
