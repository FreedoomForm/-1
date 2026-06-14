/**
 * Skuter Ijarasi — Android-matching Web UI.
 * Plain JS, no build step. Custom CSS (no Tailwind).
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
  view: 'login',        // 'login' | 'register' | 'renters' | 'scooters'
  selectedTab: 'renters', // 'renters' | 'scooters'
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
  if (r.is_returned) return { key: 'returned', label: 'Qaytgan', color: 'var(--text-secondary)', cssClass: 'status-returned' };
  if (r.debt_amount > 0) return { key: 'overdue', label: 'Qarzdor', color: 'var(--red)', cssClass: 'status-overdue' };
  return { key: 'ok', label: 'Faol', color: 'var(--green)', cssClass: 'status-ok' };
}
function scooterStatus(scooterId, renters) {
  const active = renters.some(r => r.scooter_id === scooterId && !r.is_returned);
  return active
    ? { key: 'rented', label: 'Ijarada', color: 'var(--red)', cssClass: 'status-rented', dotClass: 'red' }
    : { key: 'free', label: 'Bazada', color: 'var(--green)', cssClass: 'status-free', dotClass: 'green' };
}

// ─── Error formatting ──────────────────────────────────
function friendlyError(msg) {
  if (!msg) return 'Noma\'lum xatolik';
  if (msg.includes('401') || msg.toLowerCase().includes('invalid')) return 'Email yoki parol noto\'g\'ri';
  if (msg.includes('409') || msg.includes('EMAIL_TAKEN')) return 'Bu email bilan akkaunt mavjud';
  if (msg.includes('Failed to fetch') || msg.includes('NetworkError'))
    return 'Serverga ulanib bo\'lmadi. URL va internetni tekshiring.';
  return msg.length > 200 ? msg.slice(0, 200) + '…' : msg;
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
  const parts = s.split('-');
  return Date.UTC(parseInt(parts[0],10), parseInt(parts[1],10)-1, parseInt(parts[2],10));
}

// ═══════════════════════════════════════════════════════════
// LOGIN SCREEN
// ═══════════════════════════════════════════════════════════
function renderLogin() {
  state.view = 'login';
  const base = STORAGE.base;
  document.getElementById('root').innerHTML = `
    <div class="login-screen">
      <div class="login-card">
        <span class="login-icon">🏍️</span>
        <div class="login-title">Skuter Ijarasi</div>
        <div class="login-subtitle">Tizimga kirish</div>

        <form id="login-form" class="login-form">
          <div class="field">
            <label class="field-label">Server URL</label>
            <input class="field-input" name="base" value="${escapeHtml(base)}" placeholder="https://city1bike.vercel.app" />
            <div class="field-hint">Bo'sh qoldirsangiz — joriy sahifa URL ishlatiladi</div>
          </div>
          <div class="field">
            <label class="field-label">Email</label>
            <input class="field-input" name="email" type="email" required placeholder="admin@example.com" />
          </div>
          <div class="field">
            <label class="field-label">Parol</label>
            <input class="field-input" name="password" type="password" required minlength="6" />
          </div>
          <button class="btn-primary" type="submit" id="login-btn">Kirish</button>
        </form>

        <div class="login-error" id="login-error"></div>

        <div class="login-toggle">
          Akkauntingiz yo'qmi? <a id="register-link">Ro'yxatdan o'ting</a>
        </div>
      </div>
    </div>
  `;
  document.getElementById('login-form').addEventListener('submit', onLoginSubmit);
  document.getElementById('register-link').addEventListener('click', e => {
    e.preventDefault(); renderRegister();
  });
}

function renderRegister() {
  state.view = 'register';
  document.getElementById('root').innerHTML = `
    <div class="login-screen">
      <div class="login-card">
        <span class="login-icon">🏍️</span>
        <div class="login-title">Skuter Ijarasi</div>
        <div class="login-subtitle">Ro'yxatdan o'tish</div>

        <form id="reg-form" class="login-form">
          <div class="field">
            <label class="field-label">Server URL</label>
            <input class="field-input" name="base" value="${escapeHtml(STORAGE.base)}" placeholder="https://city1bike.vercel.app" />
          </div>
          <div class="field">
            <label class="field-label">Email</label>
            <input class="field-input" name="email" type="email" required />
          </div>
          <div class="field">
            <label class="field-label">Parol (kamida 6 belgi)</label>
            <input class="field-input" name="password" type="password" required minlength="6" />
          </div>
          <button class="btn-primary" type="submit">Yaratish</button>
        </form>

        <div class="login-error" id="reg-error"></div>

        <div class="login-toggle">
          Akkauntingiz bormi? <a id="back-link">Kirish</a>
        </div>
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
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> Yuklanmoqda...';
  const errEl = document.getElementById('login-error');
  errEl.classList.remove('visible');

  try {
    const resp = await api('/auth/login', { method: 'POST', body: { email, password } });
    STORAGE.token = resp.token;
    STORAGE.role = resp.user.role;
    STORAGE.email = resp.user.email;
    await loadAll();
    renderMain();
  } catch (err) {
    errEl.textContent = friendlyError(err.message);
    errEl.classList.add('visible');
    btn.disabled = false;
    btn.textContent = 'Kirish';
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
    const resp = await api('/auth/register', { method: 'POST', body: { email, password } });
    STORAGE.token = resp.token;
    STORAGE.role = resp.user.role;
    STORAGE.email = resp.user.email;
    toast('Akkaunt yaratildi', 'success');
    await loadAll();
    renderMain();
  } catch (err) {
    const errEl = document.getElementById('reg-error');
    errEl.textContent = friendlyError(err.message);
    errEl.classList.add('visible');
  }
}

// ═══════════════════════════════════════════════════════════
// DATA LOADING
// ═══════════════════════════════════════════════════════════
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

// ═══════════════════════════════════════════════════════════
// MAIN SCREEN (TopAppBar + Content + BottomNav)
// ═══════════════════════════════════════════════════════════
function renderMain() {
  const admin = state.isAdmin();
  const unreadCount = state.notifications.length;

  document.getElementById('root').innerHTML = `
    <div class="main-screen">
      <!-- Top App Bar -->
      <header class="topbar">
        <div class="topbar-title">Skuter Ijarasi</div>
        <div class="topbar-actions">
          <button class="topbar-icon-btn" id="btn-notifications" title="Bildirishnomalar">
            <span class="material-icons">notifications</span>
            ${unreadCount > 0 ? `<span class="topbar-badge">${unreadCount > 99 ? '99+' : unreadCount}</span>` : ''}
          </button>
          <button class="topbar-icon-btn" id="btn-history" title="Kontrakt tarixi">
            <span class="material-icons">date_range</span>
          </button>
          ${admin ? `<button class="topbar-icon-btn" id="btn-settings" title="Sozlamalar">
            <span class="material-icons">settings</span>
          </button>` : ''}
        </div>
      </header>

      <!-- Content -->
      <main class="content" id="main-content"></main>

      <!-- FAB (admin only) -->
      ${admin ? `<button class="fab" id="fab-add"><span class="material-icons">add</span></button>` : ''}

      <!-- Bottom Navigation -->
      <nav class="bottomnav">
        <button class="nav-tab ${state.selectedTab === 'renters' ? 'active' : ''}" data-tab="renters">
          <span class="material-icons">list</span>
          <span class="nav-tab-label">Ijarachilar</span>
        </button>
        <button class="nav-tab ${state.selectedTab === 'scooters' ? 'active' : ''}" data-tab="scooters">
          <span class="material-icons">two_wheeler</span>
          <span class="nav-tab-label">Skuterlar</span>
        </button>
      </nav>
    </div>
  `;

  // Bind events
  document.querySelectorAll('.nav-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      state.selectedTab = tab.dataset.tab;
      document.querySelectorAll('.nav-tab').forEach(t => t.classList.toggle('active', t.dataset.tab === state.selectedTab));
      renderCurrentTab();
    });
  });

  if (admin) {
    document.getElementById('fab-add').addEventListener('click', onFabClick);
    document.getElementById('btn-settings').addEventListener('click', openSettings);
  }

  document.getElementById('btn-notifications').addEventListener('click', openNotificationsModal);
  document.getElementById('btn-history').addEventListener('click', openHistoryModal);

  renderCurrentTab();
}

function renderCurrentTab() {
  if (state.selectedTab === 'renters') renderRentersTab();
  else renderScootersTab();
}

function onFabClick() {
  if (state.selectedTab === 'renters') openRenterForm();
  else openScooterForm();
}

function doLogout() {
  if (!confirm('Chiqishni tasdiqlaysizmi?')) return;
  STORAGE.clear();
  state.renters = state.scooters = state.history = state.notifications = [];
  renderLogin();
}

// ═══════════════════════════════════════════════════════════
// RENTERS TAB
// ═══════════════════════════════════════════════════════════
function renderRentersTab() {
  const content = document.getElementById('main-content');
  content.innerHTML = `
    <div class="search-bar">
      <span class="material-icons">search</span>
      <input id="renter-search" placeholder="Mijoz yoki skuter qidirish" />
      <span class="material-icons" style="cursor:pointer" id="renter-date-filter" title="Sana filtri">event</span>
    </div>

    <div class="filter-row">
      <select class="field-select" id="renter-filter">
        <option value="all">Barchasi</option>
        <option value="active">Faol</option>
        <option value="overdue">Qarzdor</option>
        <option value="returned">Qaytgan</option>
      </select>
    </div>

    <div class="table-wrapper" id="renters-table-wrapper"></div>
  `;

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
  document.getElementById('renters-table-wrapper').innerHTML = buildRentersTable(rows);
  attachRenterRowActions();
}

function buildRentersTable(rows) {
  if (rows.length === 0) return '<div class="empty-state">Mijozlar yo\'q</div>';
  const admin = state.isAdmin();
  return `
    <div class="table-header table-header-renters">
      <div class="th">Ism</div>
      <div class="th">Tel</div>
      <div class="th hide-xs">Skuter</div>
      <div class="th">Bosh.</div>
      <div class="th hide-xs">Tug.</div>
      <div class="th th-right">Qarz</div>
    </div>
    <div class="table-rows">
      ${rows.map(r => {
        const s = renterStatus(r);
        const expiry = r.rent_start_date_timestamp + r.rent_duration_days * 86400000;
        return `
          <div class="renter-row ${s.cssClass}" data-id="${r.id}">
            <div class="renter-name">${escapeHtml(r.name)}</div>
            <div class="renter-phone">${escapeHtml(r.phone_number)}</div>
            <div class="renter-scooter hide-xs">${escapeHtml(r.scooter_name || '—')}</div>
            <div class="renter-date">${fmtDate(r.rent_start_date_timestamp)}</div>
            <div class="renter-date hide-xs">${fmtDate(expiry)}</div>
            <div class="renter-debt">${formatNum(r.debt_amount)}</div>
            ${admin ? `
              <div class="row-actions">
                <button class="row-action-btn edit-btn" data-act="edit" data-id="${r.id}" title="Tahrirlash">
                  <span class="material-icons">edit</span>
                </button>
                <button class="row-action-btn delete-btn" data-act="del" data-id="${r.id}" title="O'chirish">
                  <span class="material-icons">delete</span>
                </button>
              </div>` : ''}
          </div>
        `;
      }).join('')}
    </div>
  `;
}

function attachRenterRowActions() {
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

// ═══════════════════════════════════════════════════════════
// SCOOTERS TAB
// ═══════════════════════════════════════════════════════════
function renderScootersTab() {
  const content = document.getElementById('main-content');
  content.innerHTML = `
    <div class="search-bar search-bar-squared">
      <span class="material-icons">search</span>
      <input id="scooter-search" placeholder="Skuter qidirish" />
    </div>

    <div class="table-wrapper" id="scooters-table-wrapper"></div>
  `;
  document.getElementById('scooter-search').addEventListener('input', filterScooters);
  filterScooters();
}

function filterScooters() {
  const q = (document.getElementById('scooter-search').value || '').toLowerCase();
  let rows = state.scooters.filter(s =>
    !q || `${s.name} ${s.documented_number || ''}`.toLowerCase().includes(q)
  );
  rows.sort((a, b) => a.name.localeCompare(b.name));
  document.getElementById('scooters-table-wrapper').innerHTML = buildScootersTable(rows);
  attachScooterRowActions();
}

function buildScootersTable(rows) {
  if (rows.length === 0) return '<div class="empty-state">Skuterlar yo\'q</div>';
  const admin = state.isAdmin();
  return `
    <div class="table-header table-header-scooters">
      <div class="th">Nomi</div>
      <div class="th">Holat</div>
    </div>
    <div class="table-rows">
      ${rows.map(s => {
        const st = scooterStatus(s.id, state.renters);
        return `
          <div class="scooter-row ${st.cssClass}" data-id="${s.id}">
            <div class="scooter-name">${escapeHtml(s.name)}</div>
            <div class="scooter-status">
              <span class="status-dot ${st.dotClass}"></span>
              <span class="scooter-status-label">${st.label}</span>
            </div>
            ${admin ? `
              <div class="row-actions">
                <button class="row-action-btn edit-btn" data-scooter-act="edit" data-id="${s.id}" title="Tahrirlash">
                  <span class="material-icons">edit</span>
                </button>
                <button class="row-action-btn delete-btn" data-scooter-act="del" data-id="${s.id}" title="O'chirish">
                  <span class="material-icons">delete</span>
                </button>
              </div>` : ''}
          </div>
        `;
      }).join('')}
    </div>
  `;
}

function attachScooterRowActions() {
  document.querySelectorAll('[data-scooter-act="edit"]').forEach(b =>
    b.addEventListener('click', e => {
      e.stopPropagation();
      openScooterForm(parseInt(b.dataset.id, 10));
    }));
  document.querySelectorAll('[data-scooter-act="del"]').forEach(b =>
    b.addEventListener('click', e => {
      e.stopPropagation();
      deleteScooter(parseInt(b.dataset.id, 10));
    }));
}

// ═══════════════════════════════════════════════════════════
// RENTER FORM (Modal)
// ═══════════════════════════════════════════════════════════
function openRenterForm(id) {
  const editing = id ? state.renters.find(r => r.id === id) : null;
  const startTs = editing ? editing.rent_start_date_timestamp : Date.now();
  const scooterOptions = state.scooters
    .map(s => `<option value="${s.id}" ${editing?.scooter_id === s.id ? 'selected' : ''}>${escapeHtml(s.name)}</option>`)
    .join('');

  showModal(`
    <div class="modal-title">${editing ? 'Ijarachini tahrirlash' : 'Yangi ijarachi'}</div>
    <form id="renter-form" class="modal-form">
      <div class="field">
        <label class="field-label">Ism</label>
        <input class="field-input" name="name" required value="${editing ? escapeHtml(editing.name) : ''}" />
      </div>
      <div class="field">
        <label class="field-label">Telefon</label>
        <input class="field-input" name="phone" required value="${editing ? escapeHtml(editing.phone_number) : ''}" placeholder="+998 90 123 45 67" />
      </div>
      <div class="form-row">
        <div class="field">
          <label class="field-label">Boshlanish sanasi</label>
          <input class="field-input" name="start_date" type="date" required value="${toDateInput(startTs)}" />
        </div>
        <div class="field">
          <label class="field-label">Muddat (kun)</label>
          <select class="field-select" name="duration">
            ${[7,14,21,30,60,90,120].map(d =>
              `<option value="${d}" ${editing?.rent_duration_days === d ? 'selected' : ''}>${d} kun</option>`
            ).join('')}
          </select>
        </div>
      </div>
      <div class="form-row">
        <div class="field">
          <label class="field-label">Skuter</label>
          <select class="field-select" name="scooter_id">
            <option value="">— Tanlanmagan —</option>
            ${scooterOptions}
          </select>
        </div>
        <div class="field">
          <label class="field-label">Qarz miqdori (UZS)</label>
          <input class="field-input" name="debt" type="number" min="0" step="1000" value="${editing?.debt_amount ?? 0}" />
        </div>
      </div>
      <div class="checkbox-row">
        <input type="checkbox" name="returned" ${editing?.is_returned ? 'checked' : ''} />
        <span>Skuter qaytarilgan deb belgilash</span>
      </div>
      <div class="modal-actions">
        <button type="button" class="btn-secondary" id="cancel-btn">Bekor qilish</button>
        <button type="submit" class="btn-primary">Saqlash</button>
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
      renderCurrentTab();
    } catch (err) { toast(friendlyError(err.message), 'error'); }
  });
}

async function deleteRenter(id) {
  if (!confirm('Bu ijarachini o\'chirmoqchimisiz?')) return;
  try {
    await api(`/renters/${id}`, { method: 'DELETE' });
    toast('Ijarachi o\'chirildi', 'success');
    await loadAll();
    renderCurrentTab();
  } catch (err) { toast(friendlyError(err.message), 'error'); }
}

// ═══════════════════════════════════════════════════════════
// SCOOTER FORM (Modal)
// ═══════════════════════════════════════════════════════════
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
    <form id="scooter-form" class="modal-form">
      <div class="field">
        <label class="field-label">Nomi (BC- formatida)</label>
        <input class="field-input" name="name" required value="${editing ? escapeHtml(editing.name) : autoName}" />
        <div class="field-hint">Avtomatik: ${autoName}. Istalgan nom bilan almashtirishingiz mumkin.</div>
      </div>
      <div class="field">
        <label class="field-label">Hujjatlashtirilgan raqami (ixtiyoriy)</label>
        <input class="field-input" name="documented_number" value="${editing?.documented_number || ''}" placeholder="Masalan: 01-234 ABC" />
      </div>
      <div class="modal-actions">
        <button type="button" class="btn-secondary" id="cancel-btn">Bekor qilish</button>
        <button type="submit" class="btn-primary">Saqlash</button>
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
      renderCurrentTab();
    } catch (err) { toast(friendlyError(err.message), 'error'); }
  });
}

async function deleteScooter(id) {
  if (!confirm('Bu skuterni o\'chirmoqchimisiz?')) return;
  try {
    await api(`/scooters/${id}`, { method: 'DELETE' });
    toast('Skuter o\'chirildi', 'success');
    await loadAll();
    renderCurrentTab();
  } catch (err) { toast(friendlyError(err.message), 'error'); }
}

// ═══════════════════════════════════════════════════════════
// HISTORY MODAL (from top bar)
// ═══════════════════════════════════════════════════════════
function openHistoryModal() {
  const renterMap = Object.fromEntries(state.renters.map(r => [r.id, r.name]));
  const typeLabels = {
    CREATED: 'Yaratildi',
    PAYMENT: "To'lov",
    AUTO_RENEW: 'Avtomatik yangilanish',
    TERMINATED: 'Tugatildi',
    RETURNED: 'Qaytarildi'
  };

  let rows = [...state.history].sort((a, b) => b.timestamp - a.timestamp);

  showModal(`
    <div class="modal-title">Kontrakt tarixi</div>
    <div style="margin-bottom:12px">
      <div class="filter-row" style="margin-bottom:0">
        <input class="field-input" id="hist-search" placeholder="Ijarachi yoki tur bo'yicha qidirish..." style="flex:1" />
        <select class="field-select" id="hist-type" style="width:auto">
          <option value="all">Barchasi</option>
          <option value="CREATED">Yaratildi</option>
          <option value="PAYMENT">To'lov</option>
          <option value="AUTO_RENEW">Avtomatik yangilanish</option>
          <option value="TERMINATED">Tugatildi</option>
          <option value="RETURNED">Qaytarildi</option>
        </select>
      </div>
    </div>
    <div id="hist-list"></div>
  `);

  function filterAndRender() {
    const q = (document.getElementById('hist-search').value || '').toLowerCase();
    const t = document.getElementById('hist-type').value;
    let filtered = rows.filter(h => {
      if (t !== 'all' && h.type !== t) return false;
      if (q && !(`${renterMap[h.renter_id] || ''} ${h.notes || ''}`.toLowerCase().includes(q))) return false;
      return true;
    });
    if (filtered.length === 0) {
      document.getElementById('hist-list').innerHTML = '<div class="empty-state">Hozircha tarix bo\'sh</div>';
      return;
    }
    document.getElementById('hist-list').innerHTML = `
      <div class="modal-list-header history-header">
        <div class="th">Vaqt</div>
        <div class="th">Ijarachi</div>
        <div class="th">Tur</div>
        <div class="th th-hide-sm">Summa</div>
        <div class="th th-hide-sm">Izoh</div>
      </div>
      <div class="modal-list">
        ${filtered.map(h => `
          <div class="modal-list-row history-row">
            <div class="secondary">${fmtDateTime(h.timestamp)}</div>
            <div class="bold">${escapeHtml(renterMap[h.renter_id] || '#' + h.renter_id)}</div>
            <div>${typeLabels[h.type] || h.type}</div>
            <div class="td-hide-sm">${h.amount > 0 ? formatNum(h.amount) + ' UZS' : '—'}</div>
            <div class="secondary td-hide-sm">${escapeHtml(h.notes || '')}</div>
          </div>
        `).join('')}
      </div>
    `;
  }

  document.getElementById('hist-search').addEventListener('input', filterAndRender);
  document.getElementById('hist-type').addEventListener('change', filterAndRender);
  filterAndRender();
}

// ═══════════════════════════════════════════════════════════
// NOTIFICATIONS MODAL (from top bar)
// ═══════════════════════════════════════════════════════════
function openNotificationsModal() {
  const renterMap = Object.fromEntries(state.renters.map(r => [r.id, r.name]));

  if (state.notifications.length === 0) {
    showModal(`
      <div class="modal-title">Bildirishnomalar</div>
      <div class="empty-state">Hozircha bildirishnomalar yo'q</div>
    `);
    return;
  }

  showModal(`
    <div class="modal-title">Bildirishnomalar</div>
    <div class="modal-list-header notif-header">
      <div class="th">Vaqt</div>
      <div class="th">Sarlavha</div>
      <div class="th">Xabar</div>
    </div>
    <div class="modal-list">
      ${state.notifications.map(n => `
        <div class="modal-list-row notif-row">
          <div class="secondary">${fmtDateTime(n.timestamp)}</div>
          <div class="bold">${escapeHtml(n.title)}</div>
          <div>${escapeHtml(n.message)}</div>
        </div>
      `).join('')}
    </div>
  `);
}

// ═══════════════════════════════════════════════════════════
// SETTINGS MODAL (from top bar, admin only)
// ═══════════════════════════════════════════════════════════
async function openSettings() {
  if (!state.isAdmin()) return;

  let serverInfo = null;
  try {
    serverInfo = await api('/auth/me');
  } catch (err) {
    /* token may have expired */
  }

  showModal(`
    <div class="modal-title">Sozlamalar</div>

    <div class="settings-section">
      <div class="settings-section-title">Backend</div>
      <div class="settings-section-content">
        <div class="settings-info-row">
          <span class="label">Server URL</span>
          <span class="value mono">${escapeHtml(STORAGE.base)}</span>
        </div>
        <div class="settings-info-row">
          <span class="label">Akkaunt</span>
          <span class="value">${serverInfo ? escapeHtml(serverInfo.email) : escapeHtml(STORAGE.email || '?')}</span>
        </div>
        ${serverInfo ? `
          <div class="settings-info-row">
            <span class="label">Rol</span>
            <span class="value">${serverInfo.role} · ID: ${serverInfo.id}</span>
          </div>
          <div class="settings-info-row">
            <span class="label">Ro'yxatdan o'tgan</span>
            <span class="value">${fmtDateTime(serverInfo.created_at)}</span>
          </div>
        ` : `
          <div class="settings-info-row">
            <span class="label">Rol</span>
            <span class="value">${STORAGE.role || '?'}</span>
          </div>
        `}
      </div>
    </div>

    <div class="settings-section">
      <div class="settings-section-title">Tizim holati</div>
      <div class="settings-section-content">
        <div class="settings-info-row">
          <span class="label">Ijarachilar</span>
          <span class="value">${state.renters.length}</span>
        </div>
        <div class="settings-info-row">
          <span class="label">Skuterlar</span>
          <span class="value">${state.scooters.length}</span>
        </div>
        <div class="settings-info-row">
          <span class="label">Tarix yozuvlari</span>
          <span class="value">${state.history.length}</span>
        </div>
        <div class="settings-info-row">
          <span class="label">Bildirishnomalar</span>
          <span class="value">${state.notifications.length}</span>
        </div>
      </div>
    </div>

    <div class="settings-section">
      <div class="settings-section-title">SMS shabloni (namuna)</div>
      <div class="settings-pre">Assalomu alaykum {name}, sizning skuter ijarangiz {days} kunga kechikdi. Iltimos, to'lovni o'z vaqtida kiriting. Umumiy qarz: {debt}.

https://transfer.paycom.uz/680a40043fc0407a2e48e8fe

Call center: 71 200 55 56.</div>
      <div class="field-hint" style="margin-top:8px">
        Shablon teglari: <code>{name}</code>, <code>{days}</code>, <code>{debt}</code>.
        To'liq tahrirlash hozircha faqat Android ilovasida mavjud.
      </div>
    </div>

    <div class="settings-section">
      <div class="settings-section-title">Sinxronizatsiya</div>
      <div class="settings-section-content">
        <button class="btn-primary" id="refresh-btn" style="width:auto;padding:0 24px;height:40px;font-size:14px">
          <span class="material-icons" style="font-size:18px">sync</span> Ma'lumotlarni yangilash
        </button>
      </div>
    </div>

    <div class="modal-actions" style="border-top:1px solid var(--divider);padding-top:12px">
      <button class="btn-danger-outline" id="logout-btn">Chiqish</button>
    </div>
  `);

  document.getElementById('refresh-btn').addEventListener('click', async () => {
    await loadAll();
    toast('Ma\'lumotlar yangilandi', 'success');
    closeModal();
    renderMain();
  });

  document.getElementById('logout-btn').addEventListener('click', () => {
    closeModal();
    doLogout();
  });
}

// ═══════════════════════════════════════════════════════════
// BOOT
// ═══════════════════════════════════════════════════════════
(async function init() {
  if (STORAGE.token) {
    try {
      await loadAll();
      renderMain();
    } catch (err) {
      STORAGE.clear();
      renderLogin();
    }
  } else {
    renderLogin();
  }
})();
