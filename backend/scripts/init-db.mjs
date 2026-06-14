#!/usr/bin/env node
/**
 * Локальный запуск инициализации схемы БД.
 * Использование:  node scripts/init-db.mjs
 * Требует DATABASE_URL в env (.env или export DATABASE_URL=...).
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { neon } from '@neondatabase/serverless';

const __dirname = dirname(fileURLToPath(import.meta.url));
const schemaPath = join(__dirname, '..', 'api', '_lib', 'schema.sql');
const schemaSql = readFileSync(schemaPath, 'utf-8');

if (!process.env.DATABASE_URL) {
  console.error('❌ DATABASE_URL not set. export DATABASE_URL=postgres://...');
  process.exit(1);
}

const sql = neon(process.env.DATABASE_URL);

try {
  await sql.query(schemaSql);
  console.log('✅ Schema applied successfully.');
} catch (err) {
  console.error('❌ Failed to apply schema:', err);
  process.exit(1);
}
