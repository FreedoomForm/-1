/**
 * GET  /api/notifications — последние уведомления
 * POST /api/notifications — записать отправленное уведомление
 */
import type { VercelRequest, VercelResponse } from '@vercel/node';
import { getSql } from '../_lib/db.js';
import { getAuth } from '../_lib/auth.js';
import { withCors, jsonRes, errorRes } from '../_lib/cors.js';

interface CreateNotificationBody {
  timestamp: number;
  renter_id?: number | null;
  title: string;
  message: string;
}

export default withCors(async (req: VercelRequest, res: VercelResponse) => {
  const auth = await getAuth(req);
  if (!auth) { errorRes(res, 'Unauthorized', 401); return; }
  const sql = getSql();

  if (req.method === 'GET') {
    const rows = (await sql`
      SELECT id, timestamp, renter_id, title, message
      FROM notification_history
      WHERE user_id = ${auth.sub}
      ORDER BY timestamp DESC
      LIMIT 500
    `) as any[];
    jsonRes(res, rows);
    return;
  }

  if (req.method === 'POST') {
    const body = req.body as CreateNotificationBody;
    if (!body) { errorRes(res, 'Invalid JSON body'); return; }

    if (!body.timestamp) { errorRes(res, 'timestamp required', 422); return; }
    if (!body.title?.trim()) { errorRes(res, 'title required', 422); return; }
    if (!body.message?.trim()) { errorRes(res, 'message required', 422); return; }

    const rows = (await sql`
      INSERT INTO notification_history (user_id, timestamp, renter_id, title, message)
      VALUES (${auth.sub}, ${body.timestamp}, ${body.renter_id ?? null},
              ${body.title.trim()}, ${body.message.trim()})
      RETURNING id
    `) as any[];
    jsonRes(res, { id: rows[0].id }, 201);
    return;
  }

  errorRes(res, 'Method not allowed', 405);
});
