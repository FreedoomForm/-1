/**
 * POST /api/auth/register
 *
 * Регистрирует нового пользователя.
 * ⚠️  Если в системе уже есть пользователи — только admin может
 *     регистрировать новых (это защита от открытой регистрации).
 *     Первый зарегистрированный пользователь автоматически становится admin.
 */
import type { VercelRequest, VercelResponse } from '@vercel/node';
import bcrypt from 'bcryptjs';
import { getSql } from '../_lib/db.js';
import { signToken } from '../_lib/auth.js';
import { withCors, jsonRes, errorRes } from '../_lib/cors.js';

interface RegisterBody {
  email: string;
  password: string;
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default withCors(async (req: VercelRequest, res: VercelResponse) => {
  if (req.method !== 'POST') { errorRes(res, 'Use POST', 405); return; }

  const body = req.body as RegisterBody;
  if (!body) { errorRes(res, 'Invalid JSON body'); return; }

  const email = body.email?.trim().toLowerCase();
  const password = body.password;
  if (!email || !EMAIL_RE.test(email)) { errorRes(res, 'Invalid email', 422); return; }
  if (!password || password.length < 6) {
    errorRes(res, 'Password must be at least 6 characters', 422);
    return;
  }

  const sql = getSql();

  // Считаем существующих пользователей — первый станет admin.
  const cntRows = (await sql`SELECT COUNT(*)::int AS count FROM users`) as any[];
  const count = cntRows[0].count;
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
      errorRes(res, 'Email already registered', 409, 'EMAIL_TAKEN');
      return;
    }
    throw err;
  }

  const token = await signToken({ sub: user.id, role: user.role });
  jsonRes(res, { token, user: { id: user.id, email: user.email, role: user.role } }, 201);
});
