/**
 * Skuter Ijarasi — Admin Panel (frontend).
 * Plain JS + Tailwind (via CDN). No build step.
 * API lives at the same origin under /api/...
 */

// ─── State ──────────────────────────────────────────────
const STORAGE = {
  get base() { return localStorage.getItem('skuter:api_base') || window.location.origin; },
  set base(v) { localStorage.setItem('skuter:api_base', v); },
  get token() { return localStorage.getItem('skuter:jwt'); },
  set token(v) { v ? localStorage.setItem('skuter:jwt', v) : localStorage.removeItem('skuter:jwt'); },
  get role() { return localStorage.getItem('skuter:role'); },
  set role(v) { v ? localStorage.setItem('skuter:role', v) : localStorage.removeItem('skuter:role'); },
  get email() { return localStorage.getItem('skuter:email'); },
  set email(v) { v ? localStorage.setItem('skuter:email', v) : localStorage.removeItem('skuter:email'); },
  clear() {
    localStorage.removeItem('skuter:jwt');
    localStorage.removeItem('skuter:role');
    localStorage.removeItem('skuter:email');
  }
};

const state = {
  renters: [],
  scooters: [],
  history: [],
  notifications: [],
  view: 'login',
  isAdmin: () => STORAGE.role === 'admin'
};

// ─── API client ─────────────────────────────────────────
async function api(path, opts = {}) {
  const headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
  if (STORAGE.token) headers['Authorization'] = `Bearer ${STORAGE.token}`;
  const method = opts.method || (opts.body ? 'POST' : 'GET');
  const fetchOpts = { ...opts, method, headers };
  if (opts.body && method !== 'GET' && method !== 'HEAD') {
    fetchOpts.body = JSON.stringify(opts.body);
  } else {
    delete fetchOpts.body;
  }
  const res = await fetch(`${STORAGE.base}/api${path}`, fetchOpts);
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try { const j = await res.json(); msg = j.error || JSON.stringify(j); } catch {}
    throw new Error(msg);
  }
  if (res.status === 204) return null;
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) return res.json();
  return res.text();
}

// ─── Toasts ─────────────────────────────────────────────
function toast(message, type = 'info', ms = 3500) {
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.textContent = message;
  document.getElementById('toast-host').appendChild(el);
  setTimeout(() => { el.style.opacity = '0'; setTimeout(() => el.remove(), 250); }, ms);
}

// ─── Modals ─────────────────────────────────────────────
function showModal(html) {
  const back = document.createElement('div');
  back.className = 'modal-back';
  back.onclick = e => { if (e.target === back) close(); };
  back.innerHTML = `<div class="modal">${html}</div>`;
  document.getElementById('modal-host').appendChild(back);
  return back;
}
function closeModal() { document.getElementById('modal-host').innerHTML = ''; }

// ─── Date helpers ───────────────────────────────────────
const fmtDate = ts => new Date(ts).toLocaleDateString('uz-UZ', { day: '2-digit', month: '2-digit', year: 'numeric' });
const fmtDateTime = ts => new Date(ts).toLocaleString('uz-UZ', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });

// ─── Status helpers ─────────────────────────────────────
function renterStatus(r) {
  if (r.is_returned) return { key: 'returned', label: 'Qaytgan', color: 'var(--muted)' };
  if (r.debt_amount > 0) return { key: 'overdue', label: 'Qarzdor', color: 'var(--accent)' };
  return { key: 'ok', label: 'Faol', color: 'var(--ok)' };
}
function scooterStatus(scooterId, renters) {
  const active = renters.some(r => r.scooter_id === scooterId && !r.is_returned);
  return active
    ? { key: 'rented', label: 'Ijarada', color: 'var(--accent)' }
    : { key: 'free', label: 'Bazada', color: 'var(--ok)' };
}

// ─── Login ─────────────────────────────────────────────
function renderLogin() {
  state.view = 'login';
  const base = STORAGE.base;
  document.getElementById('root').innerHTML = `
    <div class="min-h-screen flex items-center justify-center px-4">
      <div class="card w-full max-w-md">
        <div class="text-center mb-6">
          <div class="text-3xl mb-2">🛴</div>
          <h1 class="text-xl font-semibold">Skuter Ijarasi</h1>
          <p class="text-sm text-stone-500 mt-1">Admin Panel · Backend bilan sinxron</p>
        </div>

        <form id="login-form" class="space-y-3">
          <div>
            <label class="label">Server URL</label>
            <input class="input" name="base" value="${base}" placeholder="https://city1bike.vercel.app" />
            <p class="text-xs text-stone-400 mt-1">Bo'sh qoldirsangiz — joriy sahifa URL ishlatiladi</p>
          </div>
          <div>
            <label class="label">Email</label>
            <input class="input" name="email" type="email" required placeholder="admin@example.com" />
          </div>
          <div>
            <label class="label">Parol</label>
            <input class="input" name="password" type="password" required minlength="6" />
          </div>
          <button class="btn btn-primary w-full justify-center" type="submit" id="login-btn">Kirish</button>
          <p class="text-xs text-stone-400 text-center">
            Akkauntingiz yo'qmi?
            <a href="#" id="register-link" class="text-orange-600 hover:underline">Ro'yxatdan o'tish</a>
          </p>
        </form>

        <div id="login-error" class="hidden mt-3 p-3 bg-red-50 border border-red-200 text-red-700 text-sm rounded-lg"></div>
      </div>
    </div>
  `;
  document.getElementById('login-form').addEventListener('submit', onLoginSubmit);
  document.getElementById('register-link').addEventListener('click', e => {
    e.preventDefault(); renderRegister();
  });
}

function renderRegister() {
  document.getElementById('root').innerHTML = `
    <div class="min-h-screen flex items-center justify-center px-4">
      <div class="card w-full max-w-md">
        <div class="text-center mb-6">
          <div class="text-3xl mb-2">🛴</div>
          <h1 class="text-xl font-semibold">Ro'yxatdan o'tish</h1>
          <p class="text-sm text-stone-500 mt-1">Birinchi foydalanuvchi admin bo'ladi</p>
        </div>
        <form id="reg-form" class="space-y-3">
          <div>
            <label class="label">Server URL</label>
            <input class="input" name="base" value="${STORAGE.base}" placeholder="https://city1bike.vercel.app" />
          </div>
          <div>
            <label class="label">Email</label>
            <input class="input" name="email" type="email" required />
          </div>
          <div>
            <label class="label">Parol (kamida 6 belgi)</label>
            <input class="input" name="password" type="password" required minlength="6" />
          </div>
          <button class="btn btn-primary w-full justify-center" type="submit">Yaratish</button>
          <p class="text-xs text-stone-400 text-center">
            <a href="#" id="back-link" class="text-orange-600 hover:underline">Akkaunt bor · Kirish</a>
          </p>
        </form>
        <div id="reg-error" class="hidden mt-3 p-3 bg-red-50 border border-red-200 text-red-700 text-sm rounded-lg"></div>
      </div>
    </div>
  `;
  document.getElementById('reg-form').addEventListener('submit', onRegisterSubmit);
  document.getElementById('back-link').addEventListener('click', e => {
    e.preventDefault(); renderLogin();
  });
}

async function onLoginSubmit(e) {
  e.preventDefault();
  const fd = new FormData(e.target);
  const base = (fd.get('base') || '').trim() || window.location.origin;
  STORAGE.base = base.replace(/\/$/, '');
  const email = fd.get('email').trim();
  const password = fd.get('password');

  const btn = document.getElementById('login-btn');
  btn.disabled = true; btn.textContent = 'Yuklanmoqda...';
  document.getElementById('login-error').classList.add('hidden');

  try {
    const resp = await api('/auth/login', { body: { email, password } });
    STORAGE.token = resp.token;
    STORAGE.role = resp.user.role;
    STORAGE.email = resp.user.email;
    await loadAll();
    renderShell();
  } catch (err) {
    document.getElementById('login-error').textContent = friendlyError(err.message);
    document.getElementById('login-error').classList.remove('hidden');
    btn.disabled = false; btn.textContent = 'Kirish';
  }
}

async function onRegisterSubmit(e) {
  e.preventDefault();
  const fd = new FormData(e.target);
  const base = (fd.get('base') || '').trim() || window.location.origin;
  STORAGE.base = base.replace(/\/$/, '');
  const email = fd.get('email').trim();
  const password = fd.get('password');

  try {
    const resp = await api('/auth/register', { body: { email, password } });
    STORAGE.token = resp.token;
    STORAGE.role = resp.user.role;
    STORAGE.email = resp.user.email;
    toast('Akkaunt yaratildi', 'success');
    await loadAll();
    renderShell();
  } catch (err) {
    document.getElementById('reg-error').textContent = friendlyError(err.message);
    document.getElementById('reg-error').classList.remove('hidden');
  }
}

function friendlyError(msg) {
  if (!msg) return 'Noma\'lum xatolik';
  if (msg.includes('401') || msg.toLowerCase().includes('invalid')) return 'Email yoki parol noto\'g\'ri';
  if (msg.includes('409') || msg.includes('EMAIL_TAKEN')) return 'Bu email bilan akkaunt mavjud';
  if (msg.includes('Failed to fetch') || msg.includes('NetworkError'))
    return 'Serverga ulanib bo\'lmadi. URL va internetni tekshiring.';
  return msg.length > 200 ? msg.slice(0, 200) + '…' : msg;
}

// ─── Data loading ──────────────────────────────────────
async function loadAll() {
  const [renters, scooters, history, notifications] = await Promise.all([
    api('/renters').catch(() => []),
    api('/scooters').catch(() => []),
    api('/contract-history').catch(() => []),
    api('/notifications').catch(() => [])
  ]);
  state.renters = renters;
  state.scooters = scooters;
  state.history = history;
  state.notifications = notifications;
}

// ─── Shell + Sidebar ────────────────────────────────────
function renderShell() {
  state.view = 'dashboard';
  const admin = state.isAdmin();
  document.getElementById('root').innerHTML = `
    <div class="flex min-h-screen">
      <aside class="w-60 border-r border-stone-200 bg-white p-4 flex flex-col">
        <div class="text-lg font-semibold mb-1 flex items-center gap-2">
          <span>🛴</span><span>Skuter Ijarasi</span>
        </div>
        <p class="text-xs text-stone-400 mb-4">Admin Panel</p>

        <nav class="space-y-1 flex-1">
          <div class="nav-item" data-view="dashboard">📊 Dashboard</div>
          <div class="nav-item" data-view="renters">👥 Ijarachilar <span class="ml-auto text-xs text-stone-400" id="nav-renters-count">0</span></div>
          <div class="nav-item" data-view="scooters">🛴 Skuterlar <span class="ml-auto text-xs text-stone-400" id="nav-scooters-count">0</span></div>
          <div class="nav-item" data-view="history">📜 Kontrakt tarixi</div>
          <div class="nav-item" data-view="notifications">🔔 Bildirishnomalar</div>
          ${admin ? '<div class="nav-item" data-view="settings">⚙️ Sozlamalar</div>' : ''}
        </nav>

        <div class="border-t border-stone-200 pt-3 mt-3">
          <p class="text-xs text-stone-500 truncate" id="user-email">${STORAGE.email || ''}</p>
          <p class="text-xs text-stone-400 mb-2">Rol: <b>${STORAGE.role || '?'}</b></p>
          <button id="logout-btn" class="btn btn-secondary btn-sm w-full justify-center">Chiqish</button>
        </div>
      </aside>

      <main class="flex-1 p-6 overflow-y-auto" id="main-content"></main>
    </div>
  `;

  document.querySelectorAll('.nav-item[data-view]').forEach(el => {
    el.addEventListener('click', () => navigate(el.dataset.view));
  });
  document.getElementById('logout-btn').addEventListener('click', doLogout);

  refreshCounts();
  navigate('dashboard');
}

function refreshCounts() {
  const rc = document.getElementById('nav-renters-count');
  const sc = document.getElementById('nav-scooters-count');
  if (rc) rc.textContent = state.renters.length;
  if (sc) sc.textContent = state.scooters.length;
}

function navigate(view) {
  state.view = view;
  document.querySelectorAll('.nav-item').forEach(el => {
    el.classList.toggle('active', el.dataset.view === view);
  });
  switch (view) {
    case 'dashboard': renderDashboard(); break;
    case 'renters': renderRenters(); break;
    case 'scooters': renderScooters(); break;
    case 'history': renderHistory(); break;
    case 'notifications': renderNotifications(); break;
    case 'settings': renderSettings(); break;
  }
}

function doLogout() {
  if (!confirm('Chiqishni tasdiqlaysizmi?')) return;
  STORAGE.clear();
  state.renters = state.scooters = state.history = state.notifications = [];
  renderLogin();
}

// ─── Dashboard ─────────────────────────────────────────
function renderDashboard() {
  const activeRenters = state.renters.filter(r => !r.is_returned).length;
  const overdueRenters = state.renters.filter(r => !r.is_returned && r.debt_amount > 0).length;
  const totalDebt = state.renters.reduce((sum, r) => sum + (r.debt_amount || 0), 0);
  const rentedScooters = state.scooters.filter(s =>
    state.renters.some(r => r.scooter_id === s.id && !r.is_returned)
  ).length;

  const recentRenters = [...state.renters]
    .sort((a, b) => (b.rent_start_date_timestamp || 0) - (a.rent_start_date_timestamp || 0))
    .slice(0, 5);

  document.getElementById('main-content').innerHTML = `
    <h1 class="text-2xl font-semibold mb-6">Dashboard</h1>

    <div class="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
      ${statCard('Ijarachilar', activeRenters, overdueRenters, 'active')}
      ${statCard('Skuterlar', state.scooters.length, rentedScooters, 'rented')}
      ${statCard('Qarz', formatNum(totalDebt) + ' UZS', overdueRenters, 'overdue', 'UZS')}
      ${statCard('Bildirishnomalar', state.notifications.length, 0, 'notif')}
    </div>

    <div class="card">
      <div class="flex items-center justify-between mb-3">
        <h2 class="font-semibold">So'nggi ijarachilar</h2>
        <button class="btn btn-secondary btn-sm" data-go="renters">Barchasi →</button>
      </div>
      ${recentRenters.length === 0
        ? '<p class="text-stone-400 text-sm">Hali ijarachilar yo\'q. "Ijarachilar" → "Yangi qo\'shish"</p>'
        : '<div class="overflow-x-auto">' + rentersTable(recentRenters) + '</div>'}
    </div>
  `;

  document.querySelectorAll('[data-go]').forEach(el =>
    el.addEventListener('click', () => navigate(el.dataset.go)));
}

function statCard(title, value, accent, kind, suffix = '') {
  let color = 'var(--ok)';
  if (kind === 'overdue' && accent > 0) color = 'var(--accent)';
  if (kind === 'rented' && accent > 0) color = 'var(--accent)';
  if (kind === 'active' && accent > 0) color = 'var(--warn)';
  return `
    <div class="card">
      <p class="text-xs uppercase tracking-wide text-stone-500">${title}</p>
      <p class="text-3xl font-semibold mt-1">${value}</p>
      ${accent ? `<p class="text-xs mt-2" style="color:${color}">${kind === 'overdue' ? accent + ' ta qarzdor' : kind === 'rented' ? accent + ' ta ijarada' : kind === 'active' ? accent + ' overdue' : ''}</p>` : ''}
    </div>
  `;
}

// ─── Renters ────────────────────────────────────────────
function renderRenters() {
  const admin = state.isAdmin();
  document.getElementById('main-content').innerHTML = `
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-semibold">Ijarachilar <span class="text-stone-400 text-base font-normal">(${state.renters.length})</span></h1>
      ${admin ? '<button class="btn btn-primary" id="add-renter-btn">+ Yangi ijarachi</button>' : ''}
    </div>

    <div class="card">
      <div class="flex gap-2 mb-4">
        <input class="input flex-1" id="renter-search" placeholder="Ism, telefon yoki skuter bo'yicha qidirish..." />
        <select class="select" id="renter-filter">
          <option value="all">Barchasi</option>
          <option value="active">Faol</option>
          <option value="overdue">Qarzdor</option>
          <option value="returned">Qaytgan</option>
        </select>
      </div>
      <div id="renters-list" class="overflow-x-auto"></div>
    </div>
  `;
  if (admin) document.getElementById('add-renter-btn').addEventListener('click', () => openRenterForm());
  document.getElementById('renter-search').addEventListener('input', filterRenters);
  document.getElementById('renter-filter').addEventListener('change', filterRenters);
  filterRenters();
}

function filterRenters() {
  const q = (document.getElementById('renter-search').value || '').toLowerCase();
  const filter = document.getElementById('renter-filter').value;
  let rows = state.renters.filter(r => {
    if (q) {
      const hay = `${r.name} ${r.phone_number} ${r.scooter_name || ''}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    if (filter === 'active' && r.is_returned) return false;
    if (filter === 'overdue' && !(r.debt_amount > 0 && !r.is_returned)) return false;
    if (filter === 'returned' && !r.is_returned) return false;
    return true;
  });
  rows.sort((a, b) => Number(b.is_returned) - Number(a.is_returned)
    || (b.rent_start_date_timestamp || 0) - (a.rent_start_date_timestamp || 0));
  document.getElementById('renters-list').innerHTML = rentersTable(rows);
  attachRowActions();
}

function rentersTable(rows) {
  if (rows.length === 0) return '<p class="text-stone-400 text-sm">Hech narsa topilmadi.</p>';
  const admin = state.isAdmin();
  return `
    <table class="w-full text-sm">
      <thead>
        <tr class="text-left text-stone-500 border-b border-stone-200">
          <th class="py-2 px-2">Mijoz</th>
          <th class="py-2 px-2">Tel</th>
          <th class="py-2 px-2">Skuter</th>
          <th class="py-2 px-2">Bosh.</th>
          <th class="py-2 px-2">Tug.</th>
          <th class="py-2 px-2 text-right">Qarz</th>
          <th class="py-2 px-2">Holat</th>
          ${admin ? '<th class="py-2 px-2 text-right"></th>' : ''}
        </tr>
      </thead>
      <tbody>
        ${rows.map(r => {
          const s = renterStatus(r);
          const expiry = r.rent_start_date_timestamp + r.rent_duration_days * 86400000;
          return `
            <tr class="table-row" data-id="${r.id}">
              <td class="py-2 px-2 font-medium">${escapeHtml(r.name)}</td>
              <td class="py-2 px-2 text-stone-600">${escapeHtml(r.phone_number)}</td>
              <td class="py-2 px-2 text-stone-600">${escapeHtml(r.scooter_name || '—')}</td>
              <td class="py-2 px-2 text-stone-500 text-xs">${fmtDate(r.rent_start_date_timestamp)}</td>
              <td class="py-2 px-2 text-stone-500 text-xs">${fmtDate(expiry)}</td>
              <td class="py-2 px-2 text-right font-medium">${formatNum(r.debt_amount)}</td>
              <td class="py-2 px-2"><span class="status-dot" style="background:${s.color}"></span>${s.label}</td>
              ${admin ? `
                <td class="py-2 px-2 text-right">
                  <button class="btn btn-secondary btn-sm" data-act="edit" data-id="${r.id}">Tahrir</button>
                  <button class="btn btn-danger btn-sm" data-act="del" data-id="${r.id}">O'chirish</button>
                </td>` : ''}
            </tr>
          `;
        }).join('')}
      </tbody>
    </table>
  `;
}

function attachRowActions() {
  document.querySelectorAll('[data-act="edit"]').forEach(b =>
    b.addEventListener('click', e => {
      e.stopPropagation();
      openRenterForm(parseInt(b.dataset.id, 10));
    }));
  document.querySelectorAll('[data-act="del"]').forEach(b =>
    b.addEventListener('click', e => {
      e.stopPropagation();
      deleteRenter(parseInt(b.dataset.id, 10));
    }));
}

function openRenterForm(id) {
  const editing = id ? state.renters.find(r => r.id === id) : null;
  const startTs = editing ? editing.rent_start_date_timestamp : Date.now();
  const scooterOptions = state.scooters
    .map(s => `<option value="${s.id}" ${editing?.scooter_id === s.id ? 'selected' : ''}>${escapeHtml(s.name)}</option>`)
    .join('');

  showModal(`
    <div class="modal-title">${editing ? 'Ijarachini tahrirlash' : 'Yangi ijarachi'}</div>
    <form id="renter-form" class="space-y-3">
      <div>
        <label class="label">Ism</label>
        <input class="input" name="name" required value="${editing ? escapeHtml(editing.name) : ''}" />
      </div>
      <div>
        <label class="label">Telefon</label>
        <input class="input" name="phone" required value="${editing ? escapeHtml(editing.phone_number) : ''}" placeholder="+998 90 123 45 67" />
      </div>
      <div class="grid grid-cols-2 gap-2">
        <div>
          <label class="label">Boshlanish sanasi</label>
          <input class="input" name="start_date" type="date" required value="${toDateInput(startTs)}" />
        </div>
        <div>
          <label class="label">Muddat (kun)</label>
          <select class="select" name="duration">
            ${[7,14,21,30,60,90,120].map(d =>
              `<option value="${d}" ${editing?.rent_duration_days === d ? 'selected' : ''}>${d} kun</option>`
            ).join('')}
          </select>
        </div>
      </div>
      <div class="grid grid-cols-2 gap-2">
        <div>
          <label class="label">Skuter</label>
          <select class="select" name="scooter_id">
            <option value="">— Tanlanmagan —</option>
            ${scooterOptions}
          </select>
        </div>
        <div>
          <label class="label">Qarz miqdori (UZS)</label>
          <input class="input" name="debt" type="number" min="0" step="1000" value="${editing?.debt_amount ?? 0}" />
        </div>
      </div>
      <label class="flex items-center gap-2 text-sm">
        <input type="checkbox" name="returned" ${editing?.is_returned ? 'checked' : ''} />
        Skuter qaytarilgan deb belgilash
      </label>
      <div class="flex justify-end gap-2 pt-2">
        <button type="button" class="btn btn-secondary" id="cancel-btn">Bekor</button>
        <button type="submit" class="btn btn-primary">Saqlash</button>
      </div>
    </form>
  `);

  document.getElementById('cancel-btn').addEventListener('click', closeModal);
  document.getElementById('renter-form').addEventListener('submit', async e => {
    e.preventDefault();
    const fd = new FormData(e.target);
    const body = {
      name: fd.get('name').trim(),
      phone_number: fd.get('phone').trim(),
      rent_start_date_timestamp: parseDateInput(fd.get('start_date')),
      rent_duration_days: parseInt(fd.get('duration'), 10),
      scooter_id: fd.get('scooter_id') ? parseInt(fd.get('scooter_id'), 10) : null,
      scooter_name: state.scooters.find(s => s.id === parseInt(fd.get('scooter_id') || '0', 10))?.name || null,
      debt_amount: parseFloat(fd.get('debt') || '0'),
      is_returned: !!fd.get('returned'),
      is_overdue_sms_sent: false,
      balance: editing?.balance ?? 0,
      last_payment_timestamp: editing?.last_payment_timestamp || null
    };
    try {
      if (editing) await api(`/renters/${editing.id}`, { method: 'PUT', body });
      else await api('/renters', { method: 'POST', body });
      toast(editing ? 'Ijarachi yangilandi' : 'Ijarachi qo\'shildi', 'success');
      closeModal();
      await loadAll();
      refreshCounts();
      filterRenters();
    } catch (err) { toast(friendlyError(err.message), 'error'); }
  });
}

async function deleteRenter(id) {
  if (!confirm('Bu ijarachini o\'chirmoqchimisiz?')) return;
  try {
    await api(`/renters/${id}`, { method: 'DELETE' });
    toast('Ijarachi o\'chirildi', 'success');
    await loadAll();
    refreshCounts();
    filterRenters();
  } catch (err) { toast(friendlyError(err.message), 'error'); }
}

// ─── Scooters ───────────────────────────────────────────
function renderScooters() {
  const admin = state.isAdmin();
  document.getElementById('main-content').innerHTML = `
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-semibold">Skuterlar <span class="text-stone-400 text-base font-normal">(${state.scooters.length})</span></h1>
      ${admin ? '<button class="btn btn-primary" id="add-scooter-btn">+ Yangi skuter</button>' : ''}
    </div>

    <div class="card">
      <div class="flex gap-2 mb-4">
        <input class="input flex-1" id="scooter-search" placeholder="Nomi yoki hujjat raqami bo'yicha qidirish..." />
      </div>
      <div id="scooters-list" class="overflow-x-auto"></div>
    </div>
  `;
  if (admin) document.getElementById('add-scooter-btn').addEventListener('click', () => openScooterForm());
  document.getElementById('scooter-search').addEventListener('input', filterScooters);
  filterScooters();
}

function filterScooters() {
  const q = (document.getElementById('scooter-search').value || '').toLowerCase();
  let rows = state.scooters.filter(s =>
    !q || `${s.name} ${s.documented_number || ''}`.toLowerCase().includes(q)
  );
  rows.sort((a, b) => a.name.localeCompare(b.name));
  document.getElementById('scooters-list').innerHTML = scootersTable(rows);
  document.querySelectorAll('[data-scooter-act="edit"]').forEach(b =>
    b.addEventListener('click', () => openScooterForm(parseInt(b.dataset.id, 10))));
  document.querySelectorAll('[data-scooter-act="del"]').forEach(b =>
    b.addEventListener('click', () => deleteScooter(parseInt(b.dataset.id, 10))));
}

function scootersTable(rows) {
  if (rows.length === 0) return '<p class="text-stone-400 text-sm">Hech narsa topilmadi.</p>';
  const admin = state.isAdmin();
  return `
    <table class="w-full text-sm">
      <thead><tr class="text-left text-stone-500 border-b border-stone-200">
        <th class="py-2 px-2">Nomi</th>
        <th class="py-2 px-2">Hujjat raqami</th>
        <th class="py-2 px-2">Holat</th>
        ${admin ? '<th class="py-2 px-2 text-right"></th>' : ''}
      </tr></thead>
      <tbody>
        ${rows.map(s => {
          const st = scooterStatus(s.id, state.renters);
          return `
            <tr class="table-row">
              <td class="py-2 px-2 font-medium">${escapeHtml(s.name)}</td>
              <td class="py-2 px-2 text-stone-600">${escapeHtml(s.documented_number || '—')}</td>
              <td class="py-2 px-2"><span class="status-dot" style="background:${st.color}"></span>${st.label}</td>
              ${admin ? `
                <td class="py-2 px-2 text-right">
                  <button class="btn btn-secondary btn-sm" data-scooter-act="edit" data-id="${s.id}">Tahrir</button>
                  <button class="btn btn-danger btn-sm" data-scooter-act="del" data-id="${s.id}">O'chirish</button>
                </td>` : ''}
            </tr>
          `;
        }).join('')}
      </tbody>
    </table>
  `;
}

function openScooterForm(id) {
  const editing = id ? state.scooters.find(s => s.id === id) : null;
  const nextN = (state.scooters
    .map(s => s.name.replace(/^BC-/, '').replace(/^0+/, ''))
    .filter(n => /^\d+$/.test(n))
    .map(n => parseInt(n, 10))
    .reduce((max, n) => Math.max(max, n), 0) || 0) + 1;
  const autoName = `BC-${String(nextN).padStart(3, '0')}`;
  showModal(`
    <div class="modal-title">${editing ? 'Skuterni tahrirlash' : 'Yangi skuter'}</div>
    <form id="scooter-form" class="space-y-3">
      <div>
        <label class="label">Nomi (BC- formatida)</label>
        <input class="input" name="name" required value="${editing ? escapeHtml(editing.name) : autoName}" />
        <p class="text-xs text-stone-400 mt-1">Avtomatik: ${autoName}. Istalgan nom bilan almashtirishingiz mumkin.</p>
      </div>
      <div>
        <label class="label">Hujjatlashtirilgan raqami (ixtiyoriy)</label>
        <input class="input" name="documented_number" value="${editing?.documented_number || ''}" placeholder="Masalan: 01-234 ABC" />
      </div>
      <div class="flex justify-end gap-2 pt-2">
        <button type="button" class="btn btn-secondary" id="cancel-btn">Bekor</button>
        <button type="submit" class="btn btn-primary">Saqlash</button>
      </div>
    </form>
  `);
  document.getElementById('cancel-btn').addEventListener('click', closeModal);
  document.getElementById('scooter-form').addEventListener('submit', async e => {
    e.preventDefault();
    const fd = new FormData(e.target);
    const body = {
      name: fd.get('name').trim(),
      documented_number: fd.get('documented_number')?.trim() || null
    };
    try {
      if (editing) await api(`/scooters/${editing.id}`, { method: 'PUT', body });
      else await api('/scooters', { method: 'POST', body });
      toast(editing ? 'Skuter yangilandi' : 'Skuter qo\'shildi', 'success');
      closeModal();
      await loadAll();
      refreshCounts();
      filterScooters();
    } catch (err) { toast(friendlyError(err.message), 'error'); }
  });
}

async function deleteScooter(id) {
  if (!confirm('Bu skuterni o\'chirmoqchimisiz?')) return;
  try {
    await api(`/scooters/${id}`, { method: 'DELETE' });
    toast('Skuter o\'chirildi', 'success');
    await loadAll();
    refreshCounts();
    filterScooters();
  } catch (err) { toast(friendlyError(err.message), 'error'); }
}

// ─── History ────────────────────────────────────────────
function renderHistory() {
  const renterMap = Object.fromEntries(state.renters.map(r => [r.id, r.name]));

  document.getElementById('main-content').innerHTML = `
    <h1 class="text-2xl font-semibold mb-6">Kontrakt tarixi <span class="text-stone-400 text-base font-normal">(${state.history.length})</span></h1>
    <div class="card">
      <div class="flex gap-2 mb-4">
        <input class="input flex-1" id="hist-search" placeholder="Ijarachi yoki tur bo'yicha qidirish..." />
        <select class="select" id="hist-type">
          <option value="all">Barchasi</option>
          <option value="CREATED">Yaratildi</option>
          <option value="PAYMENT">To'lov</option>
          <option value="AUTO_RENEW">Avtomatik yangilanish</option>
          <option value="TERMINATED">Tugatildi</option>
          <option value="RETURNED">Qaytarildi</option>
        </select>
      </div>
      <div id="history-list" class="overflow-x-auto"></div>
    </div>
  `;
  document.getElementById('hist-search').addEventListener('input', filterHistory);
  document.getElementById('hist-type').addEventListener('change', filterHistory);

  function filterHistory() {
    const q = (document.getElementById('hist-search').value || '').toLowerCase();
    const t = document.getElementById('hist-type').value;
    let rows = state.history.filter(h => {
      if (t !== 'all' && h.type !== t) return false;
      if (q && !(`${renterMap[h.renter_id] || ''} ${h.notes || ''}`.toLowerCase().includes(q))) return false;
      return true;
    });
    rows.sort((a, b) => b.timestamp - a.timestamp);
    document.getElementById('history-list').innerHTML = historyTable(rows, renterMap);
  }
  filterHistory();
}

function historyTable(rows, renterMap) {
  if (rows.length === 0) return '<p class="text-stone-400 text-sm">Hozircha tarix bo\'sh.</p>';
  const typeLabels = {
    CREATED: 'Yaratildi',
    PAYMENT: "To'lov",
    AUTO_RENEW: 'Avtomatik yangilanish',
    TERMINATED: 'Tugatildi',
    RETURNED: 'Qaytarildi'
  };
  return `
    <table class="w-full text-sm">
      <thead><tr class="text-left text-stone-500 border-b border-stone-200">
        <th class="py-2 px-2">Vaqt</th>
        <th class="py-2 px-2">Ijarachi</th>
        <th class="py-2 px-2">Tur</th>
        <th class="py-2 px-2 text-right">Summa</th>
        <th class="py-2 px-2">Izoh</th>
      </tr></thead>
      <tbody>
        ${rows.map(h => `
          <tr class="table-row">
            <td class="py-2 px-2 text-stone-500 text-xs">${fmtDateTime(h.timestamp)}</td>
            <td class="py-2 px-2 font-medium">${escapeHtml(renterMap[h.renter_id] || '#' + h.renter_id)}</td>
            <td class="py-2 px-2">${typeLabels[h.type] || h.type}</td>
            <td class="py-2 px-2 text-right">${h.amount > 0 ? formatNum(h.amount) + ' UZS' : '—'}</td>
            <td class="py-2 px-2 text-stone-600">${escapeHtml(h.notes || '')}</td>
          </tr>
        `).join('')}
      </tbody>
    </table>
  `;
}

// ─── Notifications ──────────────────────────────────────
function renderNotifications() {
  const renterMap = Object.fromEntries(state.renters.map(r => [r.id, r.name]));
  document.getElementById('main-content').innerHTML = `
    <h1 class="text-2xl font-semibold mb-6">Bildirishnomalar <span class="text-stone-400 text-base font-normal">(${state.notifications.length})</span></h1>
    <div class="card">
      ${state.notifications.length === 0
        ? '<p class="text-stone-400 text-sm">Hozircha bildirishnomalar yo\'q.</p>'
        : `<div class="overflow-x-auto"><table class="w-full text-sm">
            <thead><tr class="text-left text-stone-500 border-b border-stone-200">
              <th class="py-2 px-2">Vaqt</th>
              <th class="py-2 px-2">Sarlavha</th>
              <th class="py-2 px-2">Ijarachi</th>
              <th class="py-2 px-2">Xabar</th>
            </tr></thead>
            <tbody>
              ${state.notifications.map(n => `
                <tr class="table-row">
                  <td class="py-2 px-2 text-stone-500 text-xs whitespace-nowrap">${fmtDateTime(n.timestamp)}</td>
                  <td class="py-2 px-2 font-medium">${escapeHtml(n.title)}</td>
                  <td class="py-2 px-2 text-stone-600">${escapeHtml(renterMap[n.renter_id] || (n.renter_id ? '#' + n.renter_id : '—'))}</td>
                  <td class="py-2 px-2 text-stone-700">${escapeHtml(n.message)}</td>
                </tr>
              `).join('')}
            </tbody>
          </table></div>`}
    </div>
  `;
}

// ─── Settings ───────────────────────────────────────────
async function renderSettings() {
  if (!state.isAdmin()) {
    renderDashboard();
    return;
  }

  // Тянем реальные данные с сервера через /api/me (защищён JWT).
  let serverInfo = null;
  try {
    serverInfo = await api('/auth/me');
  } catch (err) {
    /* token мог протухнуть — пользователь увидит ошибку ниже */
  }

  document.getElementById('main-content').innerHTML = `
    <h1 class="text-2xl font-semibold mb-6">Sozlamalar</h1>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
      <div class="card">
        <h2 class="font-semibold mb-3">Backend</h2>
        <p class="text-sm text-stone-500 mb-1">Server URL</p>
        <p class="font-mono text-xs break-all mb-3">${escapeHtml(STORAGE.base)}</p>
        <p class="text-sm text-stone-500 mb-1">Sizning akkaunt (serverdan tasdiqlangan)</p>
        ${serverInfo ? `
          <p class="text-sm mb-1"><b>${escapeHtml(serverInfo.email)}</b></p>
          <p class="text-xs text-stone-400">Rol: ${serverInfo.role} · ID: ${serverInfo.id}</p>
          <p class="text-xs text-stone-400">Ro'yxatdan o'tgan: ${fmtDateTime(serverInfo.created_at)}</p>
        ` : `
          <p class="text-sm text-red-600 mb-1">Serverdan ma'lumot olinmadi</p>
          <p class="text-xs text-stone-400">Local cache: ${escapeHtml(STORAGE.email || '?')} (${STORAGE.role})</p>
        `}
      </div>

      <div class="card">
        <h2 class="font-semibold mb-3">Tizim holati</h2>
        <p class="text-sm text-stone-500 mb-2">
          ${state.renters.length} ta ijarachi,
          ${state.scooters.length} ta skuter,
          ${state.history.length} ta tarix yozuvi,
          ${state.notifications.length} ta bildirishnoma
        </p>
        <button class="btn btn-secondary btn-sm" id="refresh-btn">🔄 Ma'lumotlarni yangilash</button>
      </div>

      <div class="card lg:col-span-2">
        <h2 class="font-semibold mb-3">SMS shabloni (namuna)</h2>
        <pre class="text-xs bg-stone-50 p-3 rounded border border-stone-200 whitespace-pre-wrap font-mono">Assalomu alaykum {name}, sizning skuter ijarangiz {days} kunga kechikdi. Iltimos, to'lovni o'z vaqtida kiriting. Umumiy qarz: {debt}.

https://transfer.paycom.uz/680a40043fc0407a2e48e8fe

Call center: 71 200 55 56.</pre>
        <p class="text-xs text-stone-500 mt-2">
          Shablon teglari: <code>{name}</code>, <code>{days}</code>, <code>{debt}</code>.
          To'liq tahrirlash hozircha faqat Android ilovasida mavjud.
        </p>
      </div>
    </div>
  `;
  document.getElementById('refresh-btn').addEventListener('click', async () => {
    await loadAll();
    toast('Ma\'lumotlar yangilandi', 'success');
    navigate('settings');
  });
}

// ─── Utils ──────────────────────────────────────────────
function escapeHtml(s) {
  if (s == null) return '';
  return String(s).replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
}
function formatNum(n) {
  if (n == null) return '0';
  return Number(n).toLocaleString('uz-UZ');
}
function toDateInput(ts) {
  const d = new Date(ts);
  const y = d.getFullYear(), m = String(d.getMonth()+1).padStart(2,'0'), day = String(d.getDate()).padStart(2,'0');
  return `${y}-${m}-${day}`;
}
function parseDateInput(s) {
  // yyyy-mm-dd → midnight UTC ms
  const parts = s.split('-');
  return Date.UTC(parseInt(parts[0],10), parseInt(parts[1],10)-1, parseInt(parts[2],10));
}

// ─── Boot ──────────────────────────────────────────────
(async function init() {
  if (STORAGE.token) {
    try {
      await loadAll();
      renderShell();
    } catch (err) {
      STORAGE.clear();
      renderLogin();
    }
  } else {
    renderLogin();
  }
})();
