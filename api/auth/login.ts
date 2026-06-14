/**
 * POST /api/auth/login
 *
 * Проверяет email + пароль и возвращает JWT.
 */
import type { VercelRequest, VercelResponse } from '@vercel/node';
import bcrypt from 'bcryptjs';
import { getSql } from '../_lib/db.js';
import { signToken } from '../_lib/auth.js';
import { withCors, jsonRes, errorRes } from '../_lib/cors.js';

interface LoginBody {
  email: string;
  password: string;
}

export default withCors(async (req: VercelRequest, res: VercelResponse) => {
  if (req.method !== 'POST') { errorRes(res, 'Use POST', 405); return; }

  const body = req.body as LoginBody;
  if (!body) { errorRes(res, 'Invalid JSON body'); return; }

  const email = body.email?.trim().toLowerCase();
  const password = body.password;
  if (!email || !password) { errorRes(res, 'Email and password required', 422); return; }

  const sql = getSql();
  const rows = (await sql`
    SELECT id, email, role, password_hash FROM users WHERE email = ${email} LIMIT 1
  `) as any[];
  const user = rows[0];
  if (!user) { errorRes(res, 'Invalid credentials', 401, 'INVALID_CREDENTIALS'); return; }

  const ok = await bcrypt.compare(password, user.password_hash);
  if (!ok) { errorRes(res, 'Invalid credentials', 401, 'INVALID_CREDENTIALS'); return; }

  const token = await signToken({ sub: user.id, role: user.role });
  jsonRes(res, {
    token,
    user: { id: user.id, email: user.email, role: user.role }
  });
});
