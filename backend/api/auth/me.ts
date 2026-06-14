/**
 * GET /api/auth/me
 *
 * Возвращает информацию о текущем пользователе (из JWT).
 * Используется админ-панелью для отображения реальных данных,
 * а не только того, что сохранилось в localStorage при логине.
 */
import { getSql } from '../_lib/db.js';
import { getAuth } from '../_lib/auth.js';
import { withCors, jsonResponse, errorResponse } from '../_lib/cors.js';

export default withCors(async (req: Request) => {
  if (req.method !== 'GET') return errorResponse('Use GET', 405);

  const auth = await getAuth(req);
  if (!auth) return errorResponse('Unauthorized', 401);

  const rows = await getSql()`
    SELECT id, email, role,
           EXTRACT(EPOCH FROM created_at)::bigint AS created_at
    FROM users WHERE id = ${auth.sub} LIMIT 1
  `;
  const user = rows[0];
  if (!user) return errorResponse('User not found', 404);

  return jsonResponse({
    id: user.id,
    email: user.email,
    role: user.role,
    created_at: Number(user.created_at) * 1000
  });
});
