/**
 * GET /api/ping
 * Simple no-dependency health check — no DB, no JWT, no imports.
 * Useful for verifying Vercel serverless functions work at all.
 */
export default function handler(req: Request) {
  return new Response(JSON.stringify({
    pong: true,
    timestamp: Date.now(),
    env_DATABASE_URL: process.env.DATABASE_URL ? 'SET' : 'MISSING',
    env_JWT_SECRET: process.env.JWT_SECRET ? 'SET' : 'MISSING',
    env_INIT_TOKEN: process.env.INIT_TOKEN ? 'SET' : 'MISSING'
  }), {
    status: 200,
    headers: { 'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*'
    }
  });
}
