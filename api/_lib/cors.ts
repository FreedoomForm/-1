/**
 * Утилиты CORS и JSON-ответов.
 * Android не нуждается в CORS, но это полезно для тестов из браузера.
 */

export const CORS_HEADERS: Record<string, string> = {
  'Access-Control-Allow-Origin': process.env.ALLOWED_ORIGIN ?? '*',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization',
  'Access-Control-Max-Age': '86400'
};

export function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', ...CORS_HEADERS }
  });
}

export function errorResponse(message: string, status = 400, code?: string): Response {
  return new Response(
    JSON.stringify({ error: message, code: code ?? null }),
    { status, headers: { 'Content-Type': 'application/json', ...CORS_HEADERS } }
  );
}

/**
 * Обёртка для обработчика: автоматически отвечает на OPTIONS preflight.
 */
export function withCors(handler: (req: Request) => Promise<Response> | Response) {
  return async (req: Request): Promise<Response> => {
    if (req.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }
    try {
      return await handler(req);
    } catch (err) {
      console.error('Handler error:', err);
      const message = err instanceof Error ? err.message : 'Internal error';
      return errorResponse(message, 500, 'INTERNAL');
    }
  };
}
