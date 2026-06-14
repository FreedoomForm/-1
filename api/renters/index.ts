/**
 * GET  /api/renters        — список арендаторов текущего пользователя
 * POST /api/renters        — создать арендатора
 */
import type { VercelRequest, VercelResponse } from '@vercel/node';
import { getSql } from '../_lib/db.js';
import { getAuth } from '../_lib/auth.js';
import { withCors, jsonRes, errorRes } from '../_lib/cors.js';

interface CreateRenterBody {
  name: string;
  phone_number: string;
  debt_amount?: number;
  rent_duration_days: number;
  rent_start_date_timestamp?: number;
  is_returned?: boolean;
  is_overdue_sms_sent?: boolean;
  scooter_id?: number | null;
  scooter_name?: string | null;
  last_payment_timestamp?: number | null;
  balance?: number;
}

export default withCors(async (req: VercelRequest, res: VercelResponse) => {
  const auth = await getAuth(req);
  if (!auth) { errorRes(res, 'Unauthorized', 401); return; }

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
    jsonRes(res, rows.map(r => ({
      ...r,
      debt_amount: Number(r.debt_amount),
      balance: Number(r.balance),
      is_returned: !!r.is_returned,
      is_overdue_sms_sent: !!r.is_overdue_sms_sent
    })));
    return;
  }

  if (req.method === 'POST') {
    if (auth.role !== 'admin') { errorRes(res, 'Admin only', 403, 'FORBIDDEN'); return; }
    const body = req.body as CreateRenterBody;
    if (!body) { errorRes(res, 'Invalid JSON body'); return; }

    if (!body.name?.trim()) { errorRes(res, 'name required', 422); return; }
    if (!body.phone_number?.trim()) { errorRes(res, 'phone_number required', 422); return; }
    if (!body.rent_duration_days || body.rent_duration_days < 1) {
      errorRes(res, 'rent_duration_days must be > 0', 422);
      return;
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
    jsonRes(res, { id: rows[0].id }, 201);
    return;
  }

  errorRes(res, 'Method not allowed', 405);
});
