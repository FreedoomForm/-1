/**
 * GET  /api/scooters        — список скутеров текущего пользователя
 * POST /api/scooters        — создать скутер
 */
import { getSql } from '../_lib/db.js';
import { getAuth } from '../_lib/auth.js';
import { withCors, jsonResponse, errorResponse } from '../_lib/cors.js';

interface CreateScooterBody {
  name: string;
  documented_number?: string | null;
}

export default withCors(async (req: Request) => {
  const auth = await getAuth(req);
  if (!auth) return errorResponse('Unauthorized', 401);
  const sql = getSql();

  if (req.method === 'GET') {
    const rows = await sql`
      SELECT id, name, documented_number,
             EXTRACT(EPOCH FROM created_at)::bigint AS created_at
      FROM scooters
      WHERE user_id = ${auth.sub}
      ORDER BY name ASC
    `;
    return jsonResponse(rows);
  }

  if (req.method === 'POST') {
    if (auth.role !== 'admin') return errorResponse('Admin only', 403, 'FORBIDDEN');
    let body: CreateScooterBody;
    try { body = await req.json(); }
    catch { return errorResponse('Invalid JSON body'); }

    if (!body.name?.trim()) return errorResponse('name required', 422);

    const rows = await sql`
      INSERT INTO scooters (user_id, name, documented_number)
      VALUES (${auth.sub}, ${body.name.trim()}, ${body.documented_number ?? null})
      RETURNING id
    `;
    return jsonResponse({ id: rows[0].id }, 201);
  }

  return errorResponse('Method not allowed', 405);
});
