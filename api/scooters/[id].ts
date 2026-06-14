/**
 * GET    /api/scooters/[id]
 * PUT    /api/scooters/[id]
 * DELETE /api/scooters/[id]
 */
import type { VercelRequest, VercelResponse } from '@vercel/node';
import { getSql } from '../_lib/db.js';
import { getAuth } from '../_lib/auth.js';
import { withCors, jsonRes, errorRes } from '../_lib/cors.js';

interface UpdateScooterBody {
  name?: string;
  documented_number?: string | null;
}

export default withCors(async (req: VercelRequest, res: VercelResponse) => {
  const auth = await getAuth(req);
  if (!auth) { errorRes(res, 'Unauthorized', 401); return; }
  const id = Number(req.query?.id);
  if (!Number.isInteger(id) || id <= 0) { errorRes(res, 'Invalid id', 422); return; }
  const sql = getSql();

  if (req.method === 'GET') {
    const rows = (await sql`
      SELECT id, name, documented_number,
             EXTRACT(EPOCH FROM created_at)::bigint AS created_at
      FROM scooters
      WHERE id = ${id} AND user_id = ${auth.sub}
      LIMIT 1
    `) as any[];
    const s = rows[0];
    if (!s) { errorRes(res, 'Not found', 404); return; }
    jsonRes(res, s);
    return;
  }

  if (req.method === 'PUT') {
    if (auth.role !== 'admin') { errorRes(res, 'Admin only', 403, 'FORBIDDEN'); return; }
    const body = req.body as UpdateScooterBody;
    if (!body) { errorRes(res, 'Invalid JSON body'); return; }

    const result = (await sql`
      UPDATE scooters SET
        name              = COALESCE(${body.name ?? null}, name),
        documented_number = COALESCE(${body.documented_number ?? null}, documented_number)
      WHERE id = ${id} AND user_id = ${auth.sub}
      RETURNING id
    `) as any[];
    if (result.length === 0) { errorRes(res, 'Not found', 404); return; }
    jsonRes(res, { id });
    return;
  }

  if (req.method === 'DELETE') {
    if (auth.role !== 'admin') { errorRes(res, 'Admin only', 403, 'FORBIDDEN'); return; }
    const result = (await sql`
      DELETE FROM scooters WHERE id = ${id} AND user_id = ${auth.sub} RETURNING id
    `) as any[];
    if (result.length === 0) { errorRes(res, 'Not found', 404); return; }
    jsonRes(res, { deleted: id });
    return;
  }

  errorRes(res, 'Method not allowed', 405);
});
