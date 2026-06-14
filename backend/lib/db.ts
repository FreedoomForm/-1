/**
 * Подключение к Neon Postgres через serverless-драйвер.
 *
 * Neon HTTP fetch — не WebSocket — корректно работает в Vercel
 * serverless-функциях (cold start + быстрое закрытие соединения).
 *
 * ⚠️  DATABASE_URL НИКОГДА не должен попадать в клиентский код
 *     (Android, iOS, web). Используется ТОЛЬКО в Vercel env vars.
 */
import { neon, neonConfig } from '@neondatabase/serverless';

// Кэширование клиента между инвокациями в одном Lambda-контейнере.
let _sql: ReturnType<typeof neon> | null = null;

export function getSql() {
  if (_sql) return _sql;

  const url = process.env.DATABASE_URL;
  if (!url) {
    throw new Error(
      'DATABASE_URL is not set. Добавьте его в Vercel → Settings → Environment Variables.'
    );
  }
  _sql = neon(url);
  return _sql;
}

// Полезно для логов в продакшене.
neonConfig.fetchConnectionCache = true;
