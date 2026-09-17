// Myket server-to-server purchase validation (per official docs:
// https://myket.ir/kb/pages/server-to-server-payment-validation-api/)
//   POST https://developer.myket.ir/api/partners/applications/{PACKAGE}/purchases/products/{SKU}/verify
//   Header: X-Access-Token: <key from Myket dev panel>
//   Body:   {tokenId}
//   200:    {purchaseState: 0|1, consumptionState, purchaseTime, developerPayload, ...}

export interface MyketVerifyResult {
  ok: boolean;
  purchaseState?: number;
  consumptionState?: number;
  purchaseTime?: number;
  developerPayload?: string;
  rawStatus: number;
}

export async function verifyMyketPurchase(
  accessToken: string,
  packageName: string,
  sku: string,
  purchaseToken: string,
): Promise<MyketVerifyResult> {
  const url = `https://developer.myket.ir/api/partners/applications/${encodeURIComponent(packageName)}/purchases/products/${encodeURIComponent(sku)}/verify`;
  let res: Response;
  try {
    res = await fetch(url, {
      method: 'POST',
      headers: { 'X-Access-Token': accessToken, 'Content-Type': 'application/json' },
      body: JSON.stringify({ tokenId: purchaseToken }),
    });
  } catch {
    return { ok: false, rawStatus: 0 };
  }
  if (!res.ok) return { ok: false, rawStatus: res.status };
  let j: Record<string, unknown>;
  try {
    j = (await res.json()) as Record<string, unknown>;
  } catch {
    return { ok: false, rawStatus: res.status };
  }
  const purchaseState = typeof j.purchaseState === 'number' ? j.purchaseState : 1;
  return {
    ok: purchaseState === 0,
    purchaseState,
    consumptionState: typeof j.consumptionState === 'number' ? j.consumptionState : undefined,
    purchaseTime: typeof j.purchaseTime === 'number' ? j.purchaseTime : undefined,
    developerPayload: typeof j.developerPayload === 'string' ? j.developerPayload : undefined,
    rawStatus: res.status,
  };
}
