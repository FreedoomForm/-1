# Scooter Rent — Backend API

Serverless-бэкенд для Android-приложения **Skuter Ijarasi**.
Хостится на **Vercel**, данные хранятся в **Neon Postgres**.

## Архитектура

```
Android (Room, локальная БД)
       │
       │  HTTPS + JWT
       ▼
Vercel Serverless Functions (Node.js, TypeScript)
       │
       ▼
Neon Postgres (multi-tenant: user_id scoping)
```

## Почему так (а не напрямую из APK в Postgres)

- **Credentials никогда не попадают в APK** — только в env-vars Vercel.
- **Multi-tenancy** — данные каждого пользователя изолированы по `user_id`.
- **Авторизация** — JWT, можно отзывать, можно добавлять роли.
- **Бэкапы и масштабирование** — на стороне Neon.

## Структура

```
backend/
├── api/                          # ← Vercel автоматически разворачивает это
│   ├── _lib/
│   │   ├── db.ts                 # подключение к Neon
│   │   ├── auth.ts               # JWT + middleware
│   │   ├── cors.ts               # CORS + JSON helpers
│   │   └── schema.sql            # схема БД
│   ├── auth/
│   │   ├── login.ts              # POST /api/auth/login
│   │   └── register.ts           # POST /api/auth/register
│   ├── renters/
│   │   ├── index.ts              # GET/POST /api/renters
│   │   └── [id].ts               # GET/PUT/DELETE /api/renters/:id
│   ├── scooters/
│   │   ├── index.ts              # GET/POST /api/scooters
│   │   └── [id].ts               # GET/PUT/DELETE /api/scooters/:id
│   ├── contract-history/index.ts # GET/POST /api/contract-history
│   ├── notifications/index.ts    # GET/POST /api/notifications
│   ├── init.ts                   # POST /api/init (одноразовая инициализация)
│   └── health.ts                 # GET  /api/health
├── package.json
├── tsconfig.json
├── vercel.json
├── .env.example
└── README.md
```

## Деплой на Vercel — пошагово

### 1. Создайте новый проект в Neon

1. Откройте https://console.neon.tech → **Create Project**.
2. Скопируйте **fresh** connection string (вида `postgresql://USER:PASSWORD@HOST/DB?sslmode=require`).
3. ⚠️ **НИКОГДА** не публикуйте этот URL в чате, git, скриншотах.

### 2. Залейте этот репозиторий на GitHub

```bash
git push
```

(код уже в репозитории после моего коммита).

### 3. Создайте проект в Vercel

1. https://vercel.com → **Add New… → Project**.
2. Импортируйте ваш GitHub-репозиторий.
3. **Root Directory**: `backend` ← важно!
4. **Framework Preset**: оставьте "Other".
5. Нажмите **Deploy** (пока без env-vars — деплой пройдёт, но API будет падать).

### 4. Добавьте Environment Variables

В Vercel → Settings → Environment Variables:

| Имя | Значение |
|-----|----------|
| `DATABASE_URL` | connection string из Neon (шаг 1) |
| `JWT_SECRET` | `openssl rand -hex 32` (любая длинная случайная строка ≥ 32 символа) |
| `INIT_TOKEN` | `openssl rand -hex 16` (одноразовый токен для инициализации БД) |
| `ALLOWED_ORIGIN` | `*` или ваш домен |

После сохранения Vercel передеплоит автоматически.

### 5. Инициализируйте схему БД

```bash
curl -X POST https://YOUR-PROJECT.vercel.app/api/init \
  -H "x-init-token: ВАШ_INIT_TOKEN"
```

Должно вернуть `{"ok": true, ...}`. После этого **удалите или смените `INIT_TOKEN`** в Vercel.

### 6. Проверьте health

```bash
curl https://YOUR-PROJECT.vercel.app/api/health
```

Должно вернуть `{"healthy": true, ...}`.

### 7. Создайте первого пользователя

```bash
curl -X POST https://YOUR-PROJECT.vercel.app/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"your-strong-password"}'
```

**Первый зарегистрированный пользователь автоматически становится admin.** Все последующие — viewer (но в коде можно поменять роль, если нужно больше админов).

### 8. Готово!

Все эндпоинты доступны:

```
GET    /api/health
POST   /api/auth/register
POST   /api/auth/login
GET    /api/renters
POST   /api/renters              (admin)
GET    /api/renters/:id
PUT    /api/renters/:id          (admin)
DELETE /api/renters/:id          (admin)
GET    /api/scooters
POST   /api/scooters             (admin)
GET    /api/scooters/:id
PUT    /api/scooters/:id         (admin)
DELETE /api/scooters/:id         (admin)
GET    /api/contract-history
POST   /api/contract-history     (admin)
GET    /api/notifications
POST   /api/notifications
POST   /api/init                 (требует X-Init-Token)
```

Все защищённые эндпоинты требуют `Authorization: Bearer <token>` header.

## Как подключить Android-клиент

В MainActivity можно добавить простой HTTP-клиент, который при логине сохраняет токен в EncryptedSharedPreferences и шлёт его с каждым запросом. Хранилище Room можно оставить как **offline-кэш** (зеркало сервера), а запись делать сначала локально, потом синхронизировать с API.

Скажите, если хотите — подготовлю Android-сетевой слой (Retrofit + OkHttp + AuthInterceptor) отдельным коммитом.

## Безопасность — чек-лист

- [ ] Сменить пароль БД в Neon **прямо сейчас** (старый был опубликован в чате)
- [ ] `JWT_SECRET` ≥ 32 случайных символов
- [ ] После `POST /api/init` — сменить/удалить `INIT_TOKEN`
- [ ] Включить Vercel **Password Protection** (Settings → Deployment Protection) для непубличного доступа во время разработки
- [ ] Первый admin-пароль — не менее 12 символов
- [ ] После теста — отзовите GitHub токен, который был в чате
