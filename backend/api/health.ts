/**
 * GET /api/health
 *
 * Простой health-check — проверяет доступность БД и JWT-конфиг.
 * Удобно для Vercel monitoring и быстрой диагностики после деплоя.
 */
import { getSql } from './_lib/db.js';
import { withCors, jsonResponse, errorResponse } from './_lib/cors.js';

export default withCors(async (req: Request) => {
  if (req.method !== 'GET') return errorResponse('Use GET', 405);

  const checks: Record<string, string> = {
    jwt_secret: process.env.JWT_SECRET ? 'ok' : 'missing',
    database_url: process.env.DATABASE_URL ? 'ok' : 'missing',
    db_ping: 'unknown'
  };

  try {
    await getSql()`SELECT 1 AS ok`;
    checks.db_ping = 'ok';
  } catch (err) {
    checks.db_ping = `error: ${err instanceof Error ? err.message : String(err)}`;
  }

  const healthy = Object.values(checks).every(v => v === 'ok');
  return jsonResponse(
    { healthy, checks, timestamp: Date.now() },
    healthy ? 200 : 503
  );
});
