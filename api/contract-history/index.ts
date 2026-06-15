/**
 * GET    /api/contract-history — список событий (новые сверху)
 * POST   /api/contract-history — добавить событие
 * DELETE /api/contract-history — удалить все события пользователя
 *
 * Используется Android-клиентом для синхронизации истории
 * (CREATED / PAYMENT / AUTO_RENEW / TERMINATED / RETURNED).
 */
import type { VercelRequest, VercelResponse } from '@vercel/node';
import { getSql } from '../_lib/db.js';
import { getAuth } from '../_lib/auth.js';
import { withCors, jsonRes, errorRes } from '../_lib/cors.js';

interface CreateEntryBody {
  renter_id: number;
  timestamp: number;
  type: string;
  amount?: number;
  notes?: string | null;
}

const ALLOWED_TYPES = new Set(['CREATED', 'PAYMENT', 'AUTO_RENEW', 'TERMINATED', 'RETURNED']);

export default withCors(async (req: VercelRequest, res: VercelResponse) => {
  const auth = await getAuth(req);
  if (!auth) { errorRes(res, 'Unauthorized', 401); return; }
  const sql = getSql();

  if (req.method === 'GET') {
    const rows = (await sql`
      SELECT id, renter_id, timestamp, type, amount, notes
      FROM contract_history
      WHERE user_id = ${auth.sub}
      ORDER BY timestamp DESC
      LIMIT 500
    `) as any[];
    jsonRes(res, rows.map(r => ({ ...r, amount: Number(r.amount) })));
    return;
  }

  if (req.method === 'POST') {
    if (auth.role !== 'admin') { errorRes(res, 'Admin only', 403, 'FORBIDDEN'); return; }
    const body = req.body as CreateEntryBody;
    if (!body) { errorRes(res, 'Invalid JSON body'); return; }

    if (!body.renter_id) { errorRes(res, 'renter_id required', 422); return; }
    if (!body.timestamp) { errorRes(res, 'timestamp required', 422); return; }
    if (!body.type || !ALLOWED_TYPES.has(body.type)) {
      errorRes(res, `type must be one of: ${[...ALLOWED_TYPES].join(', ')}`, 422);
      return;
    }

    const rows = (await sql`
      INSERT INTO contract_history (user_id, renter_id, timestamp, type, amount, notes)
      VALUES (${auth.sub}, ${body.renter_id}, ${body.timestamp}, ${body.type},
              ${body.amount ?? 0}, ${body.notes ?? null})
      RETURNING id
    `) as any[];
    jsonRes(res, { id: rows[0].id }, 201);
    return;
  }

  if (req.method === 'DELETE') {
    if (auth.role !== 'admin') { errorRes(res, 'Admin only', 403, 'FORBIDDEN'); return; }
    const result = (await sql`
      DELETE FROM contract_history WHERE user_id = ${auth.sub} RETURNING id
    `) as any[];
    jsonRes(res, { deleted: result.length });
    return;
  }

  errorRes(res, 'Method not allowed', 405);
});
