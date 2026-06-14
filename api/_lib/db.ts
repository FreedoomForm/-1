/**
 * Подключение к Neon Postgres через serverless-драйвер.
 */
import { neon, neonConfig } from '@neondatabase/serverless';

let _sql: ReturnType<typeof neon> | null = null;

const FALLBACK_DB_URL = 'postgresql://neondb_owner:npg_JzxDP5fWS7gb@ep-mute-king-aobpl5tx-pooler.c-2.ap-southeast-1.aws.neon.tech/neondb?sslmode=require';

function cleanDbUrl(url: string): string {
  // neon() не поддерживает channel_binding — убираем
  let clean = url.replace(/&?channel_binding=[^&]*/g, '');
  // Убираем лишний & в начале если sslmode был первым
  clean = clean.replace(/\?&/, '?');
  // Убираем trailing & или ?
  clean = clean.replace(/[&?]$/, '');
  return clean;
}

function isValidPostgresUrl(url: string): boolean {
  return url.startsWith('postgresql://') || url.startsWith('postgres://');
}

export function getSql() {
  if (_sql) return _sql;

  const rawUrl = process.env.DATABASE_URL || FALLBACK_DB_URL;
  if (!rawUrl || !isValidPostgresUrl(rawUrl)) {
    console.error('[db] DATABASE_URL is missing or invalid, using fallback');
    _sql = neon(cleanDbUrl(FALLBACK_DB_URL));
    return _sql;
  }
  _sql = neon(cleanDbUrl(rawUrl));
  return _sql;
}

neonConfig.fetchConnectionCache = true;
