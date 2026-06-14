/**
 * JWT и middleware авторизации.
 *
 * Алгоритм: HS256, секрет из env JWT_SECRET, TTL 30 дней.
 * В payload кладём { sub: userId, role } — никакой PII.
 */
import { SignJWT, jwtVerify } from 'jose';

// Fallback JWT secret — only used if env var not set in Vercel.
const FALLBACK_JWT_SECRET = 'skuter-ijarasi-jwt-secret-key-2024-production-fallback';

const secret = () => {
  const s = process.env.JWT_SECRET || FALLBACK_JWT_SECRET;
  if (!s || s.length < 32) {
    throw new Error(
      'JWT_SECRET must be set and at least 32 chars. Set it in Vercel env vars.'
    );
  }
  return new TextEncoder().encode(s);
};

export interface AuthPayload {
  sub: number;   // user id
  role: 'admin' | 'viewer';
}

export async function signToken(payload: AuthPayload): Promise<string> {
  return await new SignJWT({ role: payload.role })
    .setProtectedHeader({ alg: 'HS256' })
    .setSubject(String(payload.sub))
    .setIssuedAt()
    .setExpirationTime('30d')
    .sign(secret());
}

export async function verifyToken(token: string): Promise<AuthPayload> {
  const { payload } = await jwtVerify(token, secret());
  return {
    sub: Number(payload.sub),
    role: payload.role as 'admin' | 'viewer'
  };
}

/**
 * Возвращает auth-context или null, если заголовок отсутствует /
 * токен невалиден. Использовать в каждом защищённом эндпоинте.
 */
export async function getAuth(req: Request): Promise<AuthPayload | null> {
  const header = req.headers.get('authorization');
  if (!header?.startsWith('Bearer ')) return null;
  const token = header.slice(7).trim();
  if (!token) return null;
  try {
    return await verifyToken(token);
  } catch {
    return null;
  }
}
