/**
 * GET    /api/renters/[id]  — один арендатор
 * PUT    /api/renters/[id]  — обновить
 * DELETE /api/renters/[id]  — удалить
 *
 * Все три проверяют, что арендатор принадлежит текущему пользователю
 * (multi-tenant scope) — иначе 404 (намеренно скрываем существование).
 */
import type { VercelRequest, VercelResponse } from '@vercel/node';
import { getSql } from '../_lib/db.js';
import { getAuth } from '../_lib/auth.js';
import { withCors, jsonRes, errorRes } from '../_lib/cors.js';

interface UpdateRenterBody {
  name?: string;
  phone_number?: string;
  debt_amount?: number;
  rent_duration_days?: number;
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

  const idParam = req.query?.id as string | undefined;
  const id = Number(idParam);
  if (!Number.isInteger(id) || id <= 0) { errorRes(res, 'Invalid id', 422); return; }

  const sql = getSql();

  if (req.method === 'GET') {
    const rows = (await sql`
      SELECT id, name, phone_number, debt_amount, rent_duration_days,
             rent_start_date_timestamp, is_returned, is_overdue_sms_sent,
             scooter_id, scooter_name, last_payment_timestamp, balance,
             EXTRACT(EPOCH FROM created_at)::bigint AS created_at
      FROM renters
      WHERE id = ${id} AND user_id = ${auth.sub}
      LIMIT 1
    `) as any[];
    const r = rows[0];
    if (!r) { errorRes(res, 'Not found', 404); return; }
    jsonRes(res, {
      ...r,
      debt_amount: Number(r.debt_amount),
      balance: Number(r.balance),
      is_returned: !!r.is_returned,
      is_overdue_sms_sent: !!r.is_overdue_sms_sent
    });
    return;
  }

  if (req.method === 'PUT') {
    if (auth.role !== 'admin') { errorRes(res, 'Admin only', 403, 'FORBIDDEN'); return; }
    const body = req.body as UpdateRenterBody;
    if (!body) { errorRes(res, 'Invalid JSON body'); return; }

    const result = (await sql`
      UPDATE renters SET
        name                      = COALESCE(${body.name ?? null}, name),
        phone_number              = COALESCE(${body.phone_number ?? null}, phone_number),
        debt_amount               = COALESCE(${body.debt_amount ?? null}, debt_amount),
        rent_duration_days        = COALESCE(${body.rent_duration_days ?? null}, rent_duration_days),
        rent_start_date_timestamp = COALESCE(${body.rent_start_date_timestamp ?? null}, rent_start_date_timestamp),
        is_returned               = COALESCE(${body.is_returned ?? null}, is_returned),
        is_overdue_sms_sent       = COALESCE(${body.is_overdue_sms_sent ?? null}, is_overdue_sms_sent),
        scooter_id                = COALESCE(${body.scooter_id ?? null}, scooter_id),
        scooter_name              = COALESCE(${body.scooter_name ?? null}, scooter_name),
        last_payment_timestamp    = COALESCE(${body.last_payment_timestamp ?? null}, last_payment_timestamp),
        balance                   = COALESCE(${body.balance ?? null}, balance)
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
      DELETE FROM renters WHERE id = ${id} AND user_id = ${auth.sub} RETURNING id
    `) as any[];
    if (result.length === 0) { errorRes(res, 'Not found', 404); return; }
    jsonRes(res, { deleted: id });
    return;
  }

  errorRes(res, 'Method not allowed', 405);
});
