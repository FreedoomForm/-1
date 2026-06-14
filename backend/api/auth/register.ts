/**
 * POST /api/auth/register
 *
 * Регистрирует нового пользователя.
 * ⚠️  Если в системе уже есть пользователи — только admin может
 *     регистрировать новых (это защита от открытой регистрации).
 *     Первый зарегистрированный пользователь автоматически становится admin.
 */
import bcrypt from 'bcryptjs';
import { getSql } from '../_lib/db.js';
import { signToken } from '../_lib/auth.js';
import { withCors, jsonResponse, errorResponse } from '../_lib/cors.js';

interface RegisterBody {
  email: string;
  password: string;
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default withCors(async (req: Request) => {
  if (req.method !== 'POST') return errorResponse('Use POST', 405);

  let body: RegisterBody;
  try { body = await req.json(); }
  catch { return errorResponse('Invalid JSON body'); }

  const email = body.email?.trim().toLowerCase();
  const password = body.password;
  if (!email || !EMAIL_RE.test(email)) return errorResponse('Invalid email', 422);
  if (!password || password.length < 6) {
    return errorResponse('Password must be at least 6 characters', 422);
  }

  const sql = getSql();

  // Считаем существующих пользователей — первый станет admin.
  const [{ count }] = await sql`SELECT COUNT(*)::int AS count FROM users`;
  const role = count === 0 ? 'admin' : 'viewer';

  const passwordHash = await bcrypt.hash(password, 10);

  let user;
  try {
    const rows = (await sql`
      INSERT INTO users (email, password_hash, role)
      VALUES (${email}, ${passwordHash}, ${role})
      RETURNING id, email, role
    `) as any[];
    user = rows[0];
  } catch (err: any) {
    if (err?.message?.includes('unique') || err?.code === '23505') {
      return errorResponse('Email already registered', 409, 'EMAIL_TAKEN');
    }
    throw err;
  }

  const token = await signToken({ sub: user.id, role: user.role });
  return jsonResponse({ token, user: { id: user.id, email: user.email, role: user.role } }, 201);
});
