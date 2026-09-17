// In-memory GitHub Releases + Actions-dispatch emulator for tests.
// Speaks just enough of the real REST API for GitHubReleaseStorage + tryDispatch:
//   GET  /repos/{o}/{r}/releases/tags/{tag}      (+ asset upload/download/delete,
//   POST /repos/{o}/{r}/releases                  paged asset lists, dispatches)
// No network, deterministic ids. Also doubles as the emulated `gh` CLI used by
// the fake external build server in e2e-github.test.ts.

export interface EmuAsset {
  id: number;
  name: string;
  bytes: Uint8Array;
  contentType: string;
}

export interface EmuRelease {
  id: number;
  tag: string;
  assets: Map<string, EmuAsset>;
}

export interface DispatchCall {
  url: string;
  auth: string;
  ref: string;
  payload: Record<string, unknown>;
  bundleKey: string;
  callbackToken: string;
}

function toBytes(body: unknown): Uint8Array {
  if (body instanceof Uint8Array) return body;
  if (body instanceof ArrayBuffer) return new Uint8Array(body);
  if (typeof body === 'string') return new TextEncoder().encode(body);
  if (body === null || body === undefined) return new Uint8Array(0);
  throw new Error(`emu: unsupported body type ${typeof body}`);
}

export function createGitHubEmu() {
  const releases = new Map<string, EmuRelease>();
  const byId = new Map<number, EmuRelease>();
  const assetById = new Map<number, { rel: EmuRelease; asset: EmuAsset }>();
  let nextId = 1000;
  const dispatches: DispatchCall[] = [];

  function json(data: unknown, status = 200): Response {
    return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
  }
  function notFound(): Response {
    return json({ message: 'Not Found' }, 404);
  }

  /** Emulated `gh release upload` (what game-build.yml does with GITHUB_TOKEN). */
  function uploadAsset(tag: string, name: string, bytes: Uint8Array, contentType: string): EmuAsset {
    let rel = releases.get(tag);
    if (!rel) {
      rel = { id: nextId++, tag, assets: new Map() };
      releases.set(tag, rel);
      byId.set(rel.id, rel);
    }
    const old = rel.assets.get(name);
    if (old) assetById.delete(old.id);
    const asset: EmuAsset = { id: nextId++, name, bytes, contentType };
    rel.assets.set(name, asset);
    assetById.set(asset.id, { rel, asset });
    return asset;
  }

  /** Emulated `gh release download` (returns bytes or null). */
  function downloadAsset(tag: string, name: string): Uint8Array | null {
    return releases.get(tag)?.assets.get(name)?.bytes ?? null;
  }

  async function fetchImpl(input: string | URL | Request, init: RequestInit = {}): Promise<Response> {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const url = new URL(raw);
    const method = (init.method ?? 'GET').toUpperCase();
    const headers = new Headers(init.headers ?? {});
    const auth = headers.get('authorization') ?? '';

    if (url.hostname === 'uploads.github.com') {
      const m = /^\/repos\/[^/]+\/[^/]+\/releases\/(\d+)\/assets$/.exec(url.pathname);
      if (method === 'POST' && m) {
        const rel = byId.get(Number(m[1]));
        if (!rel) return notFound();
        const name = url.searchParams.get('name') ?? '';
        if (!name) return json({ message: 'name required' }, 422);
        const a = uploadAsset(rel.tag, name, toBytes(init.body), headers.get('content-type') ?? 'application/octet-stream');
        return json({ id: a.id, name: a.name, size: a.bytes.byteLength }, 201);
      }
      return notFound();
    }

    if (url.hostname !== 'api.github.com') return notFound();
    const p = url.pathname;

    // POST .../actions/workflows/game-build.yml/dispatches
    if (p.endsWith('/actions/workflows/game-build.yml/dispatches') && method === 'POST') {
      const body = JSON.parse(String(init.body ?? '{}')) as { ref: string; inputs: { payload: string; bundle_key: string; callback_token: string } };
      dispatches.push({
        url: raw,
        auth,
        ref: body.ref,
        payload: JSON.parse(body.inputs.payload) as Record<string, unknown>,
        bundleKey: body.inputs.bundle_key,
        callbackToken: body.inputs.callback_token,
      });
      return new Response(null, { status: 204 });
    }

    // GET /repos/{o}/{r}/releases/tags/{tag}
    let m = /^\/repos\/[^/]+\/[^/]+\/releases\/tags\/(.+)$/.exec(p);
    if (m && method === 'GET') {
      const rel = releases.get(decodeURIComponent(m[1]));
      if (!rel) return notFound();
      return json({ id: rel.id, tag_name: rel.tag });
    }

    // POST /repos/{o}/{r}/releases
    if (/^\/repos\/[^/]+\/[^/]+\/releases$/.test(p) && method === 'POST') {
      const body = JSON.parse(String(init.body ?? '{}')) as { tag_name: string };
      if (releases.has(body.tag_name)) return json({ message: 'already_exists' }, 422);
      const rel: EmuRelease = { id: nextId++, tag: body.tag_name, assets: new Map() };
      releases.set(rel.tag, rel);
      byId.set(rel.id, rel);
      return json({ id: rel.id, tag_name: rel.tag }, 201);
    }

    // GET /repos/{o}/{r}/releases/{id}/assets (paged)
    m = /^\/repos\/[^/]+\/[^/]+\/releases\/(\d+)\/assets$/.exec(p);
    if (m && method === 'GET') {
      const rel = byId.get(Number(m[1]));
      if (!rel) return notFound();
      const perPage = Math.min(100, Math.max(1, Number(url.searchParams.get('per_page') ?? '30')));
      const page = Math.max(1, Number(url.searchParams.get('page') ?? '1'));
      const all = [...rel.assets.values()];
      const slice = all.slice((page - 1) * perPage, page * perPage);
      return json(slice.map((a) => ({ id: a.id, name: a.name, size: a.bytes.byteLength })));
    }

    // GET|DELETE /repos/{o}/{r}/releases/assets/{id}
    m = /^\/repos\/[^/]+\/[^/]+\/releases\/assets\/(\d+)$/.exec(p);
    if (m) {
      const hit = assetById.get(Number(m[1]));
      if (!hit) return notFound();
      if (method === 'DELETE') {
        hit.rel.assets.delete(hit.asset.name);
        assetById.delete(hit.asset.id);
        return new Response(null, { status: 204 });
      }
      if (method === 'GET') {
        if (headers.get('accept') === 'application/octet-stream') {
          return new Response(hit.asset.bytes as unknown as BodyInit, { status: 200, headers: { 'Content-Type': 'application/octet-stream' } });
        }
        return json({ id: hit.asset.id, name: hit.asset.name, size: hit.asset.bytes.byteLength });
      }
    }

    return notFound();
  }

  return {
    fetchImpl: fetchImpl as typeof fetch,
    releases,
    dispatches,
    uploadAsset,
    downloadAsset,
  };
}

export type GitHubEmu = ReturnType<typeof createGitHubEmu>;
