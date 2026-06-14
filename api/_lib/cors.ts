/**
 * Утилиты CORS и JSON-ответов.
 * Android не нуждается в CORS, но это полезно для тестов из браузера.
 *
 * Использует VercelRequest/VercelResponse вместо Web API Request/Response,
 * так как Vercel с framework:null использует Node.js runtime,
 * а Web API формат вызывает зависание serverless-функций.
 */
import type { VercelRequest, VercelResponse } from '@vercel/node';

export const CORS_HEADERS: Record<string, string> = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization',
  'Access-Control-Max-Age': '86400'
};

export function jsonRes(res: VercelResponse, data: unknown, status = 200): void {
  res.status(status).setHeader('Content-Type', 'application/json');
  for (const [k, v] of Object.entries(CORS_HEADERS)) {
    res.setHeader(k, v);
  }
  res.end(JSON.stringify(data));
}

export function errorRes(res: VercelResponse, message: string, status = 400, code?: string): void {
  jsonRes(res, { error: message, code: code ?? null }, status);
}

/**
 * Обёртка для обработчика: автоматически отвечает на OPTIONS preflight + ловит ошибки.
 */
export function withCors(
  handler: (req: VercelRequest, res: VercelResponse) => Promise<void> | void
) {
  return async (req: VercelRequest, res: VercelResponse): Promise<void> => {
    if (req.method === 'OPTIONS') {
      res.status(204).setHeader('Access-Control-Allow-Origin', CORS_HEADERS['Access-Control-Allow-Origin']);
      res.setHeader('Access-Control-Allow-Methods', CORS_HEADERS['Access-Control-Allow-Methods']);
      res.setHeader('Access-Control-Allow-Headers', CORS_HEADERS['Access-Control-Allow-Headers']);
      res.setHeader('Access-Control-Max-Age', CORS_HEADERS['Access-Control-Max-Age']);
      res.end();
      return;
    }
    try {
      await handler(req, res);
    } catch (err) {
      console.error('Handler error:', err);
      const message = err instanceof Error ? err.message : 'Internal error';
      errorRes(res, message, 500, 'INTERNAL');
    }
  };
}
