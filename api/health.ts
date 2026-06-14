/**
 * GET /api/health
 *
 * Простой health-check — без подключения к БД.
 */
import { withCors, jsonResponse } from './_lib/cors.js';

export default withCors(async (req: Request) => {
  return jsonResponse({
    healthy: true,
    checks: {
      jwt_secret: process.env.JWT_SECRET ? 'ok' : 'fallback',
      database_url: process.env.DATABASE_URL ? 'ok' : 'fallback',
    },
    timestamp: Date.now()
  });
});
