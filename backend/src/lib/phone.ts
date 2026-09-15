// Normalize Iranian mobile numbers to E.164-ish "+98XXXXXXXXXX".
// Accepts: 09xxxxxxxxx, +989xxxxxxxxx, 00989xxxxxxxxx (spaces/dashes ignored).
export function normalizePhone(raw: string): string | null {
  if (!raw) return null;
  const digits = raw.replace(/[\s\-()]/g, '');
  let rest: string | null = null;
  if (/^09\d{9}$/.test(digits)) rest = digits.slice(1);
  else if (/^\+989\d{9}$/.test(digits)) rest = digits.slice(3);
  else if (/^00989\d{9}$/.test(digits)) rest = digits.slice(4);
  else return null;
  return `+98${rest}`;
}
