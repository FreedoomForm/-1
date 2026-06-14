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
import { getSql } from './_lib/db.js';
import { withCors, jsonResponse, errorResponse } from './_lib/cors.js';

export default withCors(async (req: Request) => {
  if (req.method !== 'POST') return errorResponse('Use POST', 405);

  const expected = process.env.INIT_TOKEN;
  const provided = req.headers.get('x-init-token');
  if (!expected || provided !== expected) {
    return errorResponse('Invalid or missing X-Init-Token header', 403, 'FORBIDDEN');
  }

  // Идемпотентно применяем schema.sql.
  const { readFileSync } = await import('node:fs');
  const { fileURLToPath } = await import('node:url');
  const path = await import('node:path');

  const __dirname = path.dirname(fileURLToPath(import.meta.url));
  const schemaPath = path.join(__dirname, '..', 'lib', 'schema.sql');
  const schemaSql = readFileSync(schemaPath, 'utf-8');

  const sql = getSql();
  await (sql as any).query(schemaSql);

  return jsonResponse({ ok: true, message: 'Schema applied. INIT_TOKEN can be removed now.' });
});
