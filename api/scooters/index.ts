/**
 * GET  /api/scooters        — список скутеров текущего пользователя
 * POST /api/scooters        — создать скутер
 */
import type { VercelRequest, VercelResponse } from '@vercel/node';
import { getSql } from '../_lib/db.js';
import { getAuth } from '../_lib/auth.js';
import { withCors, jsonRes, errorRes } from '../_lib/cors.js';

interface CreateScooterBody {
  name: string;
  documented_number?: string | null;
}

export default withCors(async (req: VercelRequest, res: VercelResponse) => {
  const auth = await getAuth(req);
  if (!auth) { errorRes(res, 'Unauthorized', 401); return; }
  const sql = getSql();

  if (req.method === 'GET') {
    const rows = (await sql`
      SELECT id, name, documented_number,
             EXTRACT(EPOCH FROM created_at)::bigint AS created_at
      FROM scooters
      WHERE user_id = ${auth.sub}
      ORDER BY name ASC
    `) as any[];
    jsonRes(res, rows);
    return;
  }

  if (req.method === 'POST') {
    if (auth.role !== 'admin') { errorRes(res, 'Admin only', 403, 'FORBIDDEN'); return; }
    const body = req.body as CreateScooterBody;
    if (!body) { errorRes(res, 'Invalid JSON body'); return; }

    if (!body.name?.trim()) { errorRes(res, 'name required', 422); return; }

    const rows = (await sql`
      INSERT INTO scooters (user_id, name, documented_number)
      VALUES (${auth.sub}, ${body.name.trim()}, ${body.documented_number ?? null})
      RETURNING id
    `) as any[];
    jsonRes(res, { id: rows[0].id }, 201);
    return;
  }

  errorRes(res, 'Method not allowed', 405);
});
