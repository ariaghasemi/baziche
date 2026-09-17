// Canonical error codes — must match shared/error-codes.md
export type ErrorCode =
  | 'INVALID_PHONE' | 'INVALID_USERNAME' | 'WEAK_PASSWORD_PARAMS'
  | 'PHONE_TAKEN' | 'USERNAME_TAKEN' | 'INVALID_CREDENTIALS' | 'ACCOUNT_SUSPENDED'
  | 'TOKEN_EXPIRED' | 'TOKEN_INVALID' | 'REFRESH_INVALID' | 'RATE_LIMITED'
  | 'UNKNOWN_GAME_TYPE' | 'PROJECT_NOT_FOUND' | 'PROJECT_DELETED' | 'INVALID_PROJECT_JSON'
  | 'REVISION_CONFLICT' | 'PROJECT_LIMIT_REACHED'
  | 'ASSET_TOO_LARGE' | 'ASSET_TYPE_BLOCKED' | 'ASSET_NOT_FOUND' | 'STORAGE_QUOTA_EXCEEDED'
  | 'SUBSCRIPTION_REQUIRED' | 'FREE_BUILD_UNAVAILABLE' | 'FREE_BUILD_RACE_LOST'
  | 'BUILD_VALIDATION_FAILED' | 'BUILD_NOT_FOUND' | 'NO_AGENT_AVAILABLE'
  | 'UNKNOWN_PLAN' | 'PURCHASE_INVALID' | 'PURCHASE_REPLAY'
  | 'AI_UPSTREAM_ERROR'
  | 'FORBIDDEN' | 'NOT_FOUND' | 'VALIDATION_ERROR' | 'CONFLICT' | 'INTERNAL' | 'NOT_IMPLEMENTED';

export class ApiError extends Error {
  constructor(
    public code: ErrorCode,
    message: string,
    public status: number,
    public meta?: Record<string, unknown>,
  ) {
    super(message);
  }
}

export function err(code: ErrorCode, message: string, status: number, meta?: Record<string, unknown>): ApiError {
  return new ApiError(code, message, status, meta);
}

export const nowSec = () => Math.floor(Date.now() / 1000);

export function newId(prefix: string): string {
  return `${prefix}_${crypto.randomUUID().replace(/-/g, '')}`;
}
