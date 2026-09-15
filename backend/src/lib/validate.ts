import { z } from 'zod';
import { err } from './errors';

// Parse with Zod; throw canonical VALIDATION_ERROR on failure (no stack/raw internals leak).
export function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const r = schema.safeParse(data);
  if (!r.success) {
    throw err('VALIDATION_ERROR', 'Validation failed', 400, {
      issues: r.error.issues.slice(0, 10).map((i) => ({ path: i.path.join('.'), message: i.message })),
    });
  }
  return r.data;
}

export const zId = z.string().min(1).max(80);
export const zUsername = z
  .string()
  .min(3)
  .max(24)
  .regex(/^[a-zA-Z0-9_.]+$/, 'username charset');
export const zHex = (min: number, max: number) =>
  z.string().min(min).max(max).regex(/^[0-9a-fA-F]+$/, 'hex expected');
