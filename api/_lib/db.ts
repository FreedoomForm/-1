/**
 * Подключение к Neon Postgres через serverless-драйвер.
 *
 * Neon HTTP fetch — не WebSocket — корректно работает в Vercel
 * serverless-функциях (cold start + быстрое закрытие соединения).
 */
import { neon, neonConfig } from '@neondatabase/serverless';

// Кэширование клиента между инвокациями в одном Lambda-контейнере.
let _sql: ReturnType<typeof neon> | null = null;

// Fallback — используется только если env var не установлен в Vercel.
const FALLBACK_DB_URL = 'postgresql://neondb_owner:npg_JzxDP5fWS7gb@ep-mute-king-aobpl5tx-pooler.c-2.ap-southeast-1.aws.neon.tech/neondb?sslmode=require';

export function getSql() {
  if (_sql) return _sql;

  const url = process.env.DATABASE_URL || FALLBACK_DB_URL;
  if (!url) {
    console.error('[db] FATAL: DATABASE_URL is not set.');
    throw new Error('DATABASE_URL is not set.');
  }
  console.log('[db] Connecting to Neon…');
  _sql = neon(url);
  return _sql;
}

// Полезно для логов в продакшене.
neonConfig.fetchConnectionCache = true;
