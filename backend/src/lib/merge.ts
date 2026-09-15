// Server-assisted three-way merge for project JSON.
// Arrays of {id}/{name} merge item-by-item; scalars are last-writer-wins with flags.
// Server (theirs) wins every real conflict and the conflict is REPORTED (never silent).
// Client flow: PATCH -> 409 -> POST /merge {baseRev, json} -> review -> PATCH merged.

export interface MergeOutput {
  merged: unknown;
  conflicts: string[];
}

const ID_ARRAYS: Record<string, string> = {
  scenes: 'id',
  objects: 'id',
  events: 'id',
  variables: 'name',
  assets: 'id',
  animations: 'id',
  levels: 'id',
};

function isObj(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function eq(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

export function threeWayMerge(base: unknown, mine: unknown, theirs: unknown): MergeOutput {
  const conflicts: string[] = [];
  if (!isObj(base) || !isObj(mine) || !isObj(theirs)) return { merged: theirs, conflicts: ['root'] };
  if (base.formatVersion !== mine.formatVersion || base.formatVersion !== theirs.formatVersion) {
    return { merged: theirs, conflicts: ['formatVersion'] };
  }
  const merged: Record<string, unknown> = {};
  const keys = new Set([...Object.keys(base), ...Object.keys(mine), ...Object.keys(theirs)]);
  for (const k of keys) {
    const b = base[k];
    const m = mine[k];
    const t = theirs[k];
    if (eq(m, t)) {
      merged[k] = m;
      continue;
    }
    if (eq(m, b)) {
      merged[k] = t;
      continue;
    }
    if (eq(t, b)) {
      merged[k] = m;
      continue;
    }
    const idKey = ID_ARRAYS[k];
    if (idKey && Array.isArray(b) && Array.isArray(m) && Array.isArray(t)) {
      merged[k] = mergeIdArray(k, idKey, b as unknown[], m as unknown[], t as unknown[], conflicts);
      continue;
    }
    merged[k] = t;
    conflicts.push(k);
  }
  return { merged, conflicts };
}

function mergeIdArray(
  path: string,
  idKey: string,
  b: unknown[],
  m: unknown[],
  t: unknown[],
  conflicts: string[],
): unknown[] {
  const index = (arr: unknown[]) => {
    const map = new Map<string, unknown>();
    const noId: unknown[] = [];
    for (const it of arr) {
      if (isObj(it) && typeof it[idKey] === 'string') map.set(it[idKey] as string, it);
      else noId.push(it);
    }
    return { map, noId };
  };
  const B = index(b);
  const M = index(m);
  const T = index(t);
  const out = new Map<string, unknown>();
  const ids = new Set([...B.map.keys(), ...M.map.keys(), ...T.map.keys()]);
  for (const id of ids) {
    const inB = B.map.get(id);
    const inM = M.map.get(id);
    const inT = T.map.get(id);
    const hasB = B.map.has(id);
    const hasM = M.map.has(id);
    const hasT = T.map.has(id);
    if (!hasB) {
      // Added on one or both sides.
      if (hasM && hasT) {
        if (eq(inM, inT)) out.set(id, inM);
        else {
          out.set(id, inT);
          conflicts.push(`${path}.${id}`);
        }
      } else if (hasM) out.set(id, inM);
      else if (hasT) out.set(id, inT);
      continue;
    }
    if (!hasM && !hasT) continue; // deleted on both
    if (!hasM) {
      if (eq(inT, inB)) continue; // deleted here, untouched there
      out.set(id, inT);
      conflicts.push(`${path}.${id}#deleted-vs-modified`);
      continue;
    }
    if (!hasT) {
      if (eq(inM, inB)) continue;
      out.set(id, inM);
      conflicts.push(`${path}.${id}#modified-vs-deleted`);
      continue;
    }
    if (eq(inM, inT)) out.set(id, inM);
    else if (eq(inM, inB)) out.set(id, inT);
    else if (eq(inT, inB)) out.set(id, inM);
    else {
      out.set(id, inT);
      conflicts.push(`${path}.${id}`);
    }
  }
  // Deterministic order: theirs first, then mine-only additions.
  const ordered: unknown[] = [];
  for (const it of t) {
    if (isObj(it) && typeof it[idKey] === 'string' && out.has(it[idKey] as string)) {
      ordered.push(out.get(it[idKey] as string));
      out.delete(it[idKey] as string);
    } else if (!isObj(it) || typeof (it as Record<string, unknown>)[idKey] !== 'string') {
      ordered.push(it);
    }
  }
  for (const [, v] of out) ordered.push(v);
  // Items without ids: union (theirs + unseen mine).
  for (const it of M.noId) if (!T.noId.some((x) => eq(x, it))) ordered.push(it);
  return ordered;
}
