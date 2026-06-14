/**
 * GET  /api/renters        — список арендаторов текущего пользователя
 * POST /api/renters        — создать арендатора
 */
import { getSql } from '../_lib/db.js';
import { getAuth } from '../_lib/auth.js';
import { withCors, jsonResponse, errorResponse } from '../_lib/cors.js';

interface CreateRenterBody {
  name: string;
  phone_number: string;
  debt_amount?: number;
  rent_duration_days: number;
  rent_start_date_timestamp: number;
  is_returned?: boolean;
  is_overdue_sms_sent?: boolean;
  scooter_id?: number | null;
  scooter_name?: string | null;
  last_payment_timestamp?: number | null;
  balance?: number;
}

export default withCors(async (req: Request) => {
  const auth = await getAuth(req);
  if (!auth) return errorResponse('Unauthorized', 401);

  const sql = getSql();

  if (req.method === 'GET') {
    const rows = (await sql`
      SELECT id, name, phone_number, debt_amount, rent_duration_days,
             rent_start_date_timestamp, is_returned, is_overdue_sms_sent,
             scooter_id, scooter_name, last_payment_timestamp, balance,
             EXTRACT(EPOCH FROM created_at)::bigint AS created_at
      FROM renters
      WHERE user_id = ${auth.sub}
      ORDER BY is_returned ASC, rent_start_date_timestamp DESC
    `) as any[];
    return jsonResponse(rows.map(r => ({
      ...r,
      debt_amount: Number(r.debt_amount),
      balance: Number(r.balance),
      is_returned: !!r.is_returned,
      is_overdue_sms_sent: !!r.is_overdue_sms_sent
    })));
  }

  if (req.method === 'POST') {
    if (auth.role !== 'admin') return errorResponse('Admin only', 403, 'FORBIDDEN');
    let body: CreateRenterBody;
    try { body = await req.json(); }
    catch { return errorResponse('Invalid JSON body'); }

    if (!body.name?.trim()) return errorResponse('name required', 422);
    if (!body.phone_number?.trim()) return errorResponse('phone_number required', 422);
    if (!body.rent_duration_days || body.rent_duration_days < 1) {
      return errorResponse('rent_duration_days must be > 0', 422);
    }

    const rows = (await sql`
      INSERT INTO renters (
        user_id, name, phone_number, debt_amount, rent_duration_days,
        rent_start_date_timestamp, is_returned, is_overdue_sms_sent,
        scooter_id, scooter_name, last_payment_timestamp, balance
      ) VALUES (
        ${auth.sub},
        ${body.name.trim()},
        ${body.phone_number.trim()},
        ${body.debt_amount ?? 0},
        ${body.rent_duration_days},
        ${body.rent_start_date_timestamp ?? Date.now()},
        ${body.is_returned ?? false},
        ${body.is_overdue_sms_sent ?? false},
        ${body.scooter_id ?? null},
        ${body.scooter_name ?? null},
        ${body.last_payment_timestamp ?? null},
        ${body.balance ?? 0}
      )
      RETURNING id
    `) as any[];
    return jsonResponse({ id: rows[0].id }, 201);
  }

  return errorResponse('Method not allowed', 405);
});
