/**
 * GET  /api/notifications — последние уведомления
 * POST /api/notifications — записать отправленное уведомление
 */
import { getSql } from '../_lib/db.js';
import { getAuth } from '../_lib/auth.js';
import { withCors, jsonResponse, errorResponse } from '../_lib/cors.js';

interface CreateNotificationBody {
  timestamp: number;
  renter_id?: number | null;
  title: string;
  message: string;
}

export default withCors(async (req: Request) => {
  const auth = await getAuth(req);
  if (!auth) return errorResponse('Unauthorized', 401);
  const sql = getSql();

  if (req.method === 'GET') {
    const rows = (await sql`
      SELECT id, timestamp, renter_id, title, message
      FROM notification_history
      WHERE user_id = ${auth.sub}
      ORDER BY timestamp DESC
      LIMIT 500
    `) as any[];
    return jsonResponse(rows);
  }

  if (req.method === 'POST') {
    let body: CreateNotificationBody;
    try { body = await req.json(); }
    catch { return errorResponse('Invalid JSON body'); }

    if (!body.timestamp) return errorResponse('timestamp required', 422);
    if (!body.title?.trim()) return errorResponse('title required', 422);
    if (!body.message?.trim()) return errorResponse('message required', 422);

    const rows = (await sql`
      INSERT INTO notification_history (user_id, timestamp, renter_id, title, message)
      VALUES (${auth.sub}, ${body.timestamp}, ${body.renter_id ?? null},
              ${body.title.trim()}, ${body.message.trim()})
      RETURNING id
    `) as any[];
    return jsonResponse({ id: rows[0].id }, 201);
  }

  return errorResponse('Method not allowed', 405);
});
