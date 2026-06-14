/**
 * POST /api/auth/login
 *
 * Проверяет email + пароль и возвращает JWT.
 */
import bcrypt from 'bcryptjs';
import { getSql } from '../_lib/db.js';
import { signToken } from '../_lib/auth.js';
import { withCors, jsonResponse, errorResponse } from '../_lib/cors.js';

interface LoginBody {
  email: string;
  password: string;
}

export default withCors(async (req: Request) => {
  if (req.method !== 'POST') return errorResponse('Use POST', 405);

  let body: LoginBody;
  try { body = await req.json(); }
  catch { return errorResponse('Invalid JSON body'); }

  const email = body.email?.trim().toLowerCase();
  const password = body.password;
  if (!email || !password) return errorResponse('Email and password required', 422);

  const sql = getSql();
  const rows = (await sql`
    SELECT id, email, role, password_hash FROM users WHERE email = ${email} LIMIT 1
  `) as any[];
  const user = rows[0];
  if (!user) return errorResponse('Invalid credentials', 401, 'INVALID_CREDENTIALS');

  const ok = await bcrypt.compare(password, user.password_hash);
  if (!ok) return errorResponse('Invalid credentials', 401, 'INVALID_CREDENTIALS');

  const token = await signToken({ sub: user.id, role: user.role });
  return jsonResponse({
    token,
    user: { id: user.id, email: user.email, role: user.role }
  });
});
