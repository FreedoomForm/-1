# Scooter Rent — Backend API + Admin Panel

Serverless-бэкенд для Android-приложения **Skuter Ijarasi**.
Хостится на **Vercel**, данные хранятся в **Neon Postgres**.

## Архитектура

```
Android (Room, локальный кэш)
       │
       │  HTTPS + JWT
       ▼
Vercel Serverless Functions (Node.js, TypeScript)
       │
       ▼
Neon Postgres (multi-tenant: user_id scoping)
```

Frontend (HTML+JS админка) лежит в корне репо (public/),
Vercel раздаёт его автоматически.

## Деплой на Vercel

### 1. Neon
- Создайте проект в https://console.neon.tech
- Скопируйте connection string (формат postgresql://USER:PASSWORD@HOST/DB?sslmode=require)
- ⚠️ Не публикуйте URL нигде

### 2. Vercel
1. https://vercel.com → Add New → Project
2. Импортируйте ваш GitHub-репозиторий FreedoomForm/-1
3. **Root Directory оставьте пустым** (корень репо уже содержит vercel.json)
4. Framework Preset: Other
5. Deploy

### 3. Environment Variables
В Vercel → Settings → Environment Variables:

| Имя | Значение |
|-----|----------|
| DATABASE_URL | connection string из Neon |
| JWT_SECRET | openssl rand -hex 32 |
| INIT_TOKEN | openssl rand -hex 16 |
| ALLOWED_ORIGIN | * |

Vercel передеплоит автоматически после сохранения.

### 4. Инициализация БД (один раз)
```bash
curl -X POST https://YOUR-PROJECT.vercel.app/api/init \
  -H "x-init-token: ВАШ_INIT_TOKEN"
```
После успеха **смените или удалите INIT_TOKEN** в Vercel.

### 5. Создайте первого пользователя
```bash
curl -X POST https://YOUR-PROJECT.vercel.app/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"STRONG-PASSWORD"}'
```
**Первый зарегистрированный автоматически становится admin.**

### 6. Готово!
- Админка: https://YOUR-PROJECT.vercel.app/
- API: https://YOUR-PROJECT.vercel.app/api/...

## API endpoints

```
POST   /api/auth/register
POST   /api/auth/login
GET    /api/auth/me
GET    /api/renters
POST   /api/renters
GET    /api/renters/:id
PUT    /api/renters/:id
DELETE /api/renters/:id
GET    /api/scooters
POST   /api/scooters
GET    /api/scooters/:id
PUT    /api/scooters/:id
DELETE /api/scooters/:id
GET    /api/contract-history
POST   /api/contract-history
GET    /api/notifications
POST   /api/notifications
POST   /api/init (защищён X-Init-Token)
GET    /api/health
```

Все защищённые endpoints требуют `Authorization: Bearer <jwt>`.

## Структура репо

```
/                              ← корень (Vercel разворачивает отсюда)
├── vercel.json                 ← конфиг для Vercel
├── public/                     ← frontend (админка)
│   ├── index.html
│   ├── styles.css
│   └── app.js
├── backend/                    ← Node.js serverless
│   ├── api/                    ← endpoints
│   ├── scripts/init-db.mjs
│   ├── package.json
│   ├── tsconfig.json
│   └── README.md
└── app/                        ← Android-клиент
    └── ... (Gradle, Kotlin, Compose)
```

## Безопасность

- [ ] Сменить пароль БД в Neon (старый утёк в чат)
- [ ] JWT_SECRET ≥ 32 случайных символов
- [ ] После POST /api/init — сменить INIT_TOKEN
- [ ] Первый admin-пароль — не менее 12 символов
