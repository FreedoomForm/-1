import type { VercelRequest, VercelResponse } from '@vercel/node';
import { getSql } from '../_lib/db.js';
import { getAuth } from '../_lib/auth.js';
import { withCors, jsonRes, errorRes } from '../_lib/cors.js';

export default withCors(async (req: VercelRequest, res: VercelResponse) => {
  if (req.method !== 'GET') { errorRes(res, 'Use GET', 405); return; }

  const auth = await getAuth(req);
  if (!auth) { errorRes(res, 'Unauthorized', 401); return; }

  const rows = (await getSql()`
    SELECT id, email, role,
           EXTRACT(EPOCH FROM created_at)::bigint AS created_at
    FROM users WHERE id = ${auth.sub} LIMIT 1
  `) as any[];

  const user = rows[0];
  if (!user) { errorRes(res, 'User not found', 404); return; }

  jsonRes(res, {
    id: user.id,
    email: user.email,
    role: user.role,
    created_at: Number(user.created_at) * 1000
  });
});
