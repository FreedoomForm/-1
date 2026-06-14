/**
 * GET  /api/contract-history — список событий (новые сверху)
 * POST /api/contract-history — добавить событие
 *
 * Используется Android-клиентом для синхронизации истории
 * (CREATED / PAYMENT / AUTO_RENEW / TERMINATED / RETURNED).
 */
import { getSql } from '../../lib/db.js';
import { getAuth } from '../../lib/auth.js';
import { withCors, jsonResponse, errorResponse } from '../../lib/cors.js';

interface CreateEntryBody {
  renter_id: number;
  timestamp: number;
  type: string;
  amount?: number;
  notes?: string | null;
}

const ALLOWED_TYPES = new Set(['CREATED', 'PAYMENT', 'AUTO_RENEW', 'TERMINATED', 'RETURNED']);

export default withCors(async (req: Request) => {
  const auth = await getAuth(req);
  if (!auth) return errorResponse('Unauthorized', 401);
  const sql = getSql();

  if (req.method === 'GET') {
    const rows = (await sql`
      SELECT id, renter_id, timestamp, type, amount, notes
      FROM contract_history
      WHERE user_id = ${auth.sub}
      ORDER BY timestamp DESC
      LIMIT 500
    `) as any[];
    return jsonResponse(rows.map(r => ({ ...r, amount: Number(r.amount) })));
  }

  if (req.method === 'POST') {
    if (auth.role !== 'admin') return errorResponse('Admin only', 403, 'FORBIDDEN');
    let body: CreateEntryBody;
    try { body = await req.json(); }
    catch { return errorResponse('Invalid JSON body'); }

    if (!body.renter_id) return errorResponse('renter_id required', 422);
    if (!body.timestamp) return errorResponse('timestamp required', 422);
    if (!body.type || !ALLOWED_TYPES.has(body.type)) {
      return errorResponse(`type must be one of: ${[...ALLOWED_TYPES].join(', ')}`, 422);
    }

    const rows = (await sql`
      INSERT INTO contract_history (user_id, renter_id, timestamp, type, amount, notes)
      VALUES (${auth.sub}, ${body.renter_id}, ${body.timestamp}, ${body.type},
              ${body.amount ?? 0}, ${body.notes ?? null})
      RETURNING id
    `) as any[];
    return jsonResponse({ id: rows[0].id }, 201);
  }

  return errorResponse('Method not allowed', 405);
});
