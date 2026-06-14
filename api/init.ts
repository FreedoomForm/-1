/**
 * POST /api/init
 *
 * Одноразовая инициализация схемы БД. Вызывается сразу после
 * создания проекта в Vercel и установки DATABASE_URL.
 *
 * Защита: требует X-Init-Token заголовок, совпадающий с
 * INIT_TOKEN env var (задайте в Vercel перед вызовом).
 *
 * После успешной инициализации рекомендуется сменить или удалить
 * INIT_TOKEN, чтобы случайные люди не могли переинициализировать БД.
 */
import type { VercelRequest, VercelResponse } from '@vercel/node';
import { getSql } from './_lib/db.js';
import { withCors, jsonRes, errorRes } from './_lib/cors.js';
import { SCHEMA_SQL } from './_lib/schema.js';

export default withCors(async (req: VercelRequest, res: VercelResponse) => {
  if (req.method !== 'POST') { errorRes(res, 'Use POST', 405); return; }

  const expected = process.env.INIT_TOKEN || '8e66b02a83713eb0';
  const provided = req.headers['x-init-token'] as string | undefined;
  if (provided !== expected) {
    errorRes(res, 'Invalid or missing X-Init-Token header', 403, 'FORBIDDEN');
    return;
  }

  const sql = getSql();
  await (sql as any).query(SCHEMA_SQL);

  jsonRes(res, { ok: true, message: 'Schema applied. INIT_TOKEN can be removed now.' });
});
