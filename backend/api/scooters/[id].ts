/**
 * GET    /api/scooters/[id]
 * PUT    /api/scooters/[id]
 * DELETE /api/scooters/[id]
 */
import { getSql } from '../_lib/db.js';
import { getAuth } from '../_lib/auth.js';
import { withCors, jsonResponse, errorResponse } from '../_lib/cors.js';

interface UpdateScooterBody {
  name?: string;
  documented_number?: string | null;
}

export default withCors(async (req: Request) => {
  const auth = await getAuth(req);
  if (!auth) return errorResponse('Unauthorized', 401);
  const id = Number((req as any).params?.id);
  if (!Number.isInteger(id) || id <= 0) return errorResponse('Invalid id', 422);
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
    if (!s) return errorResponse('Not found', 404);
    return jsonResponse(s);
  }

  if (req.method === 'PUT') {
    if (auth.role !== 'admin') return errorResponse('Admin only', 403, 'FORBIDDEN');
    let body: UpdateScooterBody;
    try { body = await req.json(); }
    catch { return errorResponse('Invalid JSON body'); }

    const result = (await sql`
      UPDATE scooters SET
        name              = COALESCE(${body.name ?? null}, name),
        documented_number = COALESCE(${body.documented_number ?? null}, documented_number)
      WHERE id = ${id} AND user_id = ${auth.sub}
      RETURNING id
    `) as any[];
    if (result.length === 0) return errorResponse('Not found', 404);
    return jsonResponse({ id });
  }

  if (req.method === 'DELETE') {
    if (auth.role !== 'admin') return errorResponse('Admin only', 403, 'FORBIDDEN');
    const result = (await sql`
      DELETE FROM scooters WHERE id = ${id} AND user_id = ${auth.sub} RETURNING id
    `) as any[];
    if (result.length === 0) return errorResponse('Not found', 404);
    return jsonResponse({ deleted: id });
  }

  return errorResponse('Method not allowed', 405);
});
