/**
 * Skuter Ijarasi — Full-featured Web UI matching Android app.
 * Plain JS, no build step. Custom CSS (no Tailwind).
 * API lives at the same origin under /api/...
 *
 * BALANCE LOGIC:
 *   totalOwed  = weeks_elapsed × weekly_price  (for active, non-returned renters)
 *   balance    = total amount paid by renter (stored in DB)
 *   displayBalance = balance - totalOwed  (negative = owes money, positive = credit)
 *   debt_amount in DB = max(0, totalOwed - balance)  (kept in sync for Android)
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
  get weeklyPrice() { return localStorage.getItem('skuter:weekly_price') || '350000'; },
  set weeklyPrice(v) { localStorage.setItem('skuter:weekly_price', v); },
  get monthlyPrice() { return localStorage.getItem('skuter:monthly_price') || '1200000'; },
  set monthlyPrice(v) { localStorage.setItem('skuter:monthly_price', v); },
  get smsTemplate() { return localStorage.getItem('skuter:sms_template') || ''; },
  set smsTemplate(v) { localStorage.setItem('skuter:sms_template', v); },
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
  selectedTab: 'renters',
  isAdmin: () => STORAGE.role === 'admin',
  sortColumn: 'name',
  sortDir: 'asc',
  scooterSortDir: 'asc',
  selectedRenterIds: new Set(),
  selectedScooterIds: new Set(),
  dateFrom: null,
  dateTo: null,
  overdueChecked: false
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

// ─── Balance calculation ────────────────────────────────
function calcRenterFinances(r) {
  const weeklyPrice = parseInt(STORAGE.weeklyPrice, 10) || 350000;
  if (r.is_returned) {
    return {
      weeksElapsed: 0,
      totalOwed: 0,
      balancePaid: r.balance || 0,
      displayBalance: r.balance || 0,
      debtAmount: 0,
      isOverdue: false
    };
  }
  const now = Date.now();
  const startDate = r.rent_start_date_timestamp;
  const msElapsed = now - startDate;
  const daysElapsed = Math.max(0, Math.floor(msElapsed / 86400000));
  const weeksElapsed = Math.max(1, Math.ceil(daysElapsed / 7));
  const totalOwed = weeksElapsed * weeklyPrice;
  const balancePaid = r.balance || 0;
  const displayBalance = balancePaid - totalOwed;
  const debtAmount = Math.max(0, totalOwed - balancePaid);
  const expiryDate = startDate + r.rent_duration_days * 86400000;
  const isOverdue = now > expiryDate && debtAmount > 0;

  return { weeksElapsed, totalOwed, balancePaid, displayBalance, debtAmount, isOverdue, expiryDate };
}

// ─── Status helpers ─────────────────────────────────────
function renterStatus(r) {
  const fin = calcRenterFinances(r);
  if (r.is_returned) return { key: 'returned', label: 'Qaytgan', color: 'var(--text-secondary)', cssClass: 'status-returned' };
  if (fin.isOverdue || fin.displayBalance < 0) return { key: 'overdue', label: 'Qarzdor', color: 'var(--red)', cssClass: 'status-overdue' };
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
  if (msg.includes('403')) return 'Ruxsat yo\'q. Faqat admin uchun.';
  return msg.length > 200 ? msg.slice(0, 200) + '...' : msg;
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
function formatBalance(n) {
  const num = Number(n);
  if (num < 0) return '-' + formatNum(Math.abs(num));
  return formatNum(num);
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
function formatPhone(phone) {
  if (!phone) return '';
  const digits = phone.replace(/\D/g, '');
  if (digits.length === 12 && digits.startsWith('998')) {
    return `+998 ${digits.slice(3,5)} ${digits.slice(5,8)} ${digits.slice(8,10)} ${digits.slice(10,12)}`;
  }
  if (digits.length === 9) {
    return `+998 ${digits.slice(0,2)} ${digits.slice(2,5)} ${digits.slice(5,7)} ${digits.slice(7,9)}`;
  }
  return phone;
}
function sortArrow(column, currentColumn, currentDir) {
  if (column !== currentColumn) return '<span class="sort-arrow muted">&#8597;</span>';
  return currentDir === 'asc' ? '<span class="sort-arrow active">&#8593;</span>' : '<span class="sort-arrow active">&#8595;</span>';
}

// ─── Overdue check & notification creation ─────────────
async function checkAndNotifyOverdue() {
  if (state.overdueChecked) return;
  state.overdueChecked = true;
  const admin = state.isAdmin();
  if (!admin) return;

  const weeklyPrice = parseInt(STORAGE.weeklyPrice, 10) || 350000;

  for (const r of state.renters) {
    if (r.is_returned) continue;
    const fin = calcRenterFinances(r);
    if (!fin.isOverdue) continue;

    // Sync debt_amount in DB if it doesn't match calculated value
    if (r.debt_amount !== fin.debtAmount) {
      try {
        await api(`/renters/${r.id}`, {
          method: 'PUT',
          body: {
            debt_amount: fin.debtAmount,
            is_overdue_sms_sent: false
          }
        });
      } catch (e) { console.warn('Failed to sync debt for renter', r.id, e); }
    }
  }

  // Count overdue for notification badge
  const overdueCount = state.renters.filter(r => !r.is_returned && calcRenterFinances(r).isOverdue).length;
  if (overdueCount > 0) {
    // Check if we already notified about this today
    const today = new Date().toDateString();
    const lastNotified = localStorage.getItem('skuter:overdue_notified_date');
    if (lastNotified !== today) {
      try {
        await api('/notifications', {
          method: 'POST',
          body: {
            timestamp: Date.now(),
            renter_id: null,
            title: 'Qarzdorlik eslatmasi',
            message: `${overdueCount} ta ijarachi qarzdorlikda. To'lovlarni tekshiring.`
          }
        });
        localStorage.setItem('skuter:overdue_notified_date', today);
      } catch (e) { console.warn('Failed to create overdue notification', e); }
    }
  }
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
        <div class="login-hint">Birinchi ro'yxatdan o'tgan foydalanuvchi admin bo'ladi</div>
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
    state.overdueChecked = false;
    await loadAll();
    await checkAndNotifyOverdue();
    // Reload notifications after creating new ones
    if (state.isAdmin()) {
      state.notifications = await api('/notifications').catch(() => state.notifications);
    }
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
    state.overdueChecked = false;
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
// MAIN SCREEN
// ═══════════════════════════════════════════════════════════
function renderMain() {
  const admin = state.isAdmin();
  const unreadCount = state.notifications.length;

  document.getElementById('root').innerHTML = `
    <div class="main-screen">
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
          <button class="topbar-icon-btn" id="btn-settings" title="Sozlamalar">
            <span class="material-icons">settings</span>
          </button>
          <button class="topbar-icon-btn" id="btn-logout" title="Chiqish">
            <span class="material-icons">logout</span>
          </button>
        </div>
      </header>

      <main class="content" id="main-content"></main>

      ${admin ? `<button class="fab" id="fab-add"><span class="material-icons">add</span></button>` : ''}

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

  document.querySelectorAll('.nav-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      state.selectedTab = tab.dataset.tab;
      state.selectedRenterIds.clear();
      state.selectedScooterIds.clear();
      document.querySelectorAll('.nav-tab').forEach(t => t.classList.toggle('active', t.dataset.tab === state.selectedTab));
      renderCurrentTab();
    });
  });

  if (admin) {
    document.getElementById('fab-add').addEventListener('click', onFabClick);
  }

  document.getElementById('btn-notifications').addEventListener('click', openNotificationsModal);
  document.getElementById('btn-history').addEventListener('click', openHistoryModal);
  document.getElementById('btn-settings').addEventListener('click', openSettings);
  document.getElementById('btn-logout').addEventListener('click', doLogout);

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
  state.selectedRenterIds.clear();
  state.selectedScooterIds.clear();
  state.overdueChecked = false;
  renderLogin();
}

// ═══════════════════════════════════════════════════════════
// RENTERS TAB
// ═══════════════════════════════════════════════════════════
function renderRentersTab() {
  const content = document.getElementById('main-content');
  const overdueCount = state.renters.filter(r => !r.is_returned && calcRenterFinances(r).isOverdue).length;

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
      ${state.dateFrom || state.dateTo ? '<button class="btn-secondary" id="clear-date-filter" style="font-size:12px;padding:4px 10px">Sana: o\'chirish</button>' : ''}
    </div>

    ${overdueCount > 0 ? `<div class="overdue-banner">${overdueCount} ta ijarachi qarzdorlikda!</div>` : ''}

    <div id="renter-batch-bar"></div>
    <div class="table-wrapper" id="renters-table-wrapper"></div>
  `;

  document.getElementById('renter-search').addEventListener('input', filterRenters);
  document.getElementById('renter-filter').addEventListener('change', filterRenters);
  document.getElementById('renter-date-filter').addEventListener('click', openDateRangeFilter);

  const clearBtn = document.getElementById('clear-date-filter');
  if (clearBtn) {
    clearBtn.addEventListener('click', () => {
      state.dateFrom = null;
      state.dateTo = null;
      renderRentersTab();
    });
  }

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
    const fin = calcRenterFinances(r);
    if (filter === 'active' && (r.is_returned || fin.displayBalance < 0)) return false;
    if (filter === 'overdue' && !(fin.displayBalance < 0 && !r.is_returned)) return false;
    if (filter === 'returned' && !r.is_returned) return false;

    if (state.dateFrom || state.dateTo) {
      const expiry = r.rent_start_date_timestamp + r.rent_duration_days * 86400000;
      const expiryDate = new Date(expiry);
      const from = state.dateFrom ? new Date(state.dateFrom) : null;
      const to = state.dateTo ? new Date(state.dateTo + 'T23:59:59') : null;
      if (from && expiryDate < from) return false;
      if (to && expiryDate > to) return false;
    }

    return true;
  });

  // Sort
  rows.sort((a, b) => {
    let cmp = 0;
    const col = state.sortColumn;
    if (col === 'name') cmp = a.name.localeCompare(b.name);
    else if (col === 'start') cmp = (a.rent_start_date_timestamp || 0) - (b.rent_start_date_timestamp || 0);
    else if (col === 'status') {
      const sa = renterStatus(a).key, sb = renterStatus(b).key;
      const order = { overdue: 0, ok: 1, returned: 2 };
      cmp = (order[sa] ?? 2) - (order[sb] ?? 2);
    }
    else if (col === 'balance') {
      const fa = calcRenterFinances(a), fb = calcRenterFinances(b);
      cmp = fa.displayBalance - fb.displayBalance;
    }
    return state.sortDir === 'desc' ? -cmp : cmp;
  });

  document.getElementById('renters-table-wrapper').innerHTML = buildRentersTable(rows);
  updateBatchBar();
  attachRenterRowActions();
}

function buildRentersTable(rows) {
  if (rows.length === 0) return '<div class="empty-state">Mijozlar yo\'q</div>';
  const admin = state.isAdmin();
  const sc = state.sortColumn;
  const sd = state.sortDir;
  return `
    <div class="table-header table-header-renters">
      <div class="th sortable" data-sort="name">Ism ${sortArrow('name', sc, sd)}</div>
      <div class="th">Tel</div>
      <div class="th hide-xs">Skuter</div>
      <div class="th sortable" data-sort="start">Bosh. ${sortArrow('start', sc, sd)}</div>
      <div class="th hide-xs">Tug.</div>
      <div class="th sortable th-right" data-sort="balance">Balans ${sortArrow('balance', sc, sd)}</div>
    </div>
    <div class="table-rows">
      ${rows.map(r => {
        const s = renterStatus(r);
        const fin = calcRenterFinances(r);
        const expiry = r.rent_start_date_timestamp + r.rent_duration_days * 86400000;
        const selected = state.selectedRenterIds.has(r.id);
        const balanceClass = fin.displayBalance < 0 ? 'balance-negative' : fin.displayBalance > 0 ? 'balance-positive' : '';
        return `
          <div class="renter-row ${s.cssClass} ${selected ? 'selected' : ''}" data-id="${r.id}">
            <div class="renter-name">
              <span class="checkbox-wrap">
                <input type="checkbox" class="renter-checkbox" data-id="${r.id}" ${selected ? 'checked' : ''} />
              </span>
              ${escapeHtml(r.name)}
            </div>
            <div class="renter-phone">${formatPhone(escapeHtml(r.phone_number))}</div>
            <div class="renter-scooter hide-xs">${escapeHtml(r.scooter_name || '—')}</div>
            <div class="renter-date">${fmtDate(r.rent_start_date_timestamp)}</div>
            <div class="renter-date hide-xs">${fmtDate(expiry)}</div>
            <div class="renter-balance ${balanceClass}">${formatBalance(fin.displayBalance)}</div>
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
  const admin = state.isAdmin();

  // Sort headers
  document.querySelectorAll('.sortable[data-sort]').forEach(th => {
    th.style.cursor = 'pointer';
    th.addEventListener('click', () => {
      const col = th.dataset.sort;
      if (state.sortColumn === col) {
        state.sortDir = state.sortDir === 'asc' ? 'desc' : 'asc';
      } else {
        state.sortColumn = col;
        state.sortDir = 'asc';
      }
      filterRenters();
    });
  });

  // Row click -> edit or toggle selection
  document.querySelectorAll('.renter-row').forEach(row => {
    row.addEventListener('click', e => {
      if (e.target.classList.contains('renter-checkbox')) {
        const id = parseInt(e.target.dataset.id, 10);
        if (e.target.checked) state.selectedRenterIds.add(id);
        else state.selectedRenterIds.delete(id);
        updateBatchBar();
        row.classList.toggle('selected', e.target.checked);
        return;
      }
      if (e.target.closest('.row-action-btn')) return;
      const id = parseInt(row.dataset.id, 10);
      openRenterForm(id);
    });
  });

  // Long press to toggle selection
  let longPressTimer = null;
  document.querySelectorAll('.renter-row').forEach(row => {
    row.addEventListener('mousedown', e => {
      if (e.target.closest('.row-action-btn') || e.target.classList.contains('renter-checkbox')) return;
      longPressTimer = setTimeout(() => {
        const id = parseInt(row.dataset.id, 10);
        state.selectedRenterIds.add(id);
        updateBatchBar();
        filterRenters();
      }, 500);
    });
    row.addEventListener('mouseup', () => clearTimeout(longPressTimer));
    row.addEventListener('mouseleave', () => clearTimeout(longPressTimer));
  });

  if (admin) {
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
}

function updateBatchBar() {
  const bar = document.getElementById('renter-batch-bar');
  if (!bar) return;
  const admin = state.isAdmin();
  const count = state.selectedRenterIds.size;
  if (count === 0) {
    bar.innerHTML = '';
    return;
  }
  const wp = parseInt(STORAGE.weeklyPrice, 10) || 350000;
  bar.innerHTML = `
    <div class="batch-row">
      <span class="count">${count} ta tanlandi</span>
      ${admin ? `
        <button class="btn-secondary" id="batch-pay-weekly">
          <span class="material-icons" style="font-size:16px">payments</span>
          To'lov (${formatNum(wp)} so'm)
        </button>
        <button class="btn-secondary" id="batch-terminate">
          <span class="material-icons" style="font-size:16px">block</span>
          Kontraktni uzish
        </button>
        <button class="btn-danger-outline" id="batch-delete">
          <span class="material-icons" style="font-size:16px">delete</span>
          O'chirish
        </button>
      ` : ''}
      <button class="btn-secondary" id="batch-clear" style="margin-left:auto">
        <span class="material-icons" style="font-size:16px">close</span>
      </button>
    </div>
  `;

  document.getElementById('batch-clear').addEventListener('click', () => {
    state.selectedRenterIds.clear();
    updateBatchBar();
    filterRenters();
  });

  if (admin) {
    document.getElementById('batch-pay-weekly').addEventListener('click', batchPayWeekly);
    document.getElementById('batch-terminate').addEventListener('click', batchTerminate);
    document.getElementById('batch-delete').addEventListener('click', batchDeleteRenters);
  }
}

async function batchPayWeekly() {
  const weeklyPrice = parseInt(STORAGE.weeklyPrice, 10) || 350000;
  const ids = [...state.selectedRenterIds];
  let successCount = 0;

  for (const id of ids) {
    const r = state.renters.find(x => x.id === id);
    if (!r || r.is_returned) continue;
    try {
      // Increase balance by weekly price
      const newBalance = (r.balance || 0) + weeklyPrice;
      // Recalculate debt after payment
      const fin = calcRenterFinances(r);
      const newDebt = Math.max(0, fin.totalOwed - newBalance);

      await api(`/renters/${id}`, {
        method: 'PUT',
        body: {
          balance: newBalance,
          debt_amount: newDebt,
          last_payment_timestamp: Date.now()
        }
      });
      // Log contract history
      await api('/contract-history', {
        method: 'POST',
        body: {
          renter_id: id,
          timestamp: Date.now(),
          type: 'PAYMENT',
          amount: weeklyPrice,
          notes: `Haftalik to'lov: ${formatNum(weeklyPrice)} UZS`
        }
      });
      successCount++;
    } catch (err) {
      console.error('Pay weekly error for', id, err);
      toast(`Xatolik: ${friendlyError(err.message)}`, 'error');
    }
  }
  if (successCount > 0) {
    toast(`${successCount} ta ijarachiga ${formatNum(weeklyPrice)} so'm to'lov qo'llandi`, 'success');
  }
  state.selectedRenterIds.clear();
  await loadAll();
  renderCurrentTab();
}

async function batchTerminate() {
  const weeklyPrice = parseInt(STORAGE.weeklyPrice, 10) || 350000;
  const ids = [...state.selectedRenterIds];
  let successCount = 0;

  for (const id of ids) {
    const r = state.renters.find(x => x.id === id);
    if (!r || r.is_returned) continue;
    try {
      // Calculate current debt, add weekly price for final payment
      const fin = calcRenterFinances(r);
      const newBalance = (r.balance || 0) + weeklyPrice;
      const newDebt = Math.max(0, fin.totalOwed - newBalance);

      await api(`/renters/${id}`, {
        method: 'PUT',
        body: {
          is_returned: true,
          balance: newBalance,
          debt_amount: newDebt
        }
      });
      await api('/contract-history', {
        method: 'POST',
        body: {
          renter_id: id,
          timestamp: Date.now(),
          type: 'TERMINATED',
          amount: weeklyPrice,
          notes: 'Kontrakt uzildi'
        }
      });
      successCount++;
    } catch (err) {
      console.error('Terminate error for', id, err);
      toast(`Xatolik: ${friendlyError(err.message)}`, 'error');
    }
  }
  if (successCount > 0) {
    toast(`${successCount} ta kontrakt uzildi`, 'success');
  }
  state.selectedRenterIds.clear();
  await loadAll();
  renderCurrentTab();
}

async function batchDeleteRenters() {
  const ids = [...state.selectedRenterIds];
  if (!confirm(`${ids.length} ta ijarachini o'chirmoqchimisiz?`)) return;
  let successCount = 0;
  for (const id of ids) {
    try {
      await api(`/renters/${id}`, { method: 'DELETE' });
      successCount++;
    } catch (err) {
      console.error('Delete error for', id, err);
      toast(`O'chirish xatoligi: ${friendlyError(err.message)}`, 'error');
    }
  }
  toast(`${successCount} ta ijarachi o'chirildi`, 'success');
  state.selectedRenterIds.clear();
  await loadAll();
  renderCurrentTab();
}

// ─── Date Range Filter ──────────────────────────────────
function openDateRangeFilter() {
  showModal(`
    <div class="modal-title">Sana oralig'i bo'yicha filtrlash</div>
    <p style="font-size:13px;color:var(--text-secondary);margin-bottom:12px">Kontrakt tugash sanasi bo'yicha filtrlash</p>
    <form id="date-range-form" class="modal-form">
      <div class="form-row">
        <div class="field">
          <label class="field-label">Dan</label>
          <input class="field-input" name="date_from" type="date" value="${state.dateFrom || ''}" />
        </div>
        <div class="field">
          <label class="field-label">Gacha</label>
          <input class="field-input" name="date_to" type="date" value="${state.dateTo || ''}" />
        </div>
      </div>
      <div class="modal-actions">
        <button type="button" class="btn-secondary" id="clear-range-btn">Tozalash</button>
        <button type="button" class="btn-secondary" id="cancel-btn">Bekor qilish</button>
        <button type="submit" class="btn-primary">Tanlash</button>
      </div>
    </form>
  `);

  document.getElementById('cancel-btn').addEventListener('click', closeModal);
  document.getElementById('clear-range-btn').addEventListener('click', () => {
    state.dateFrom = null;
    state.dateTo = null;
    closeModal();
    renderRentersTab();
  });
  document.getElementById('date-range-form').addEventListener('submit', e => {
    e.preventDefault();
    const fd = new FormData(e.target);
    state.dateFrom = fd.get('date_from') || null;
    state.dateTo = fd.get('date_to') || null;
    closeModal();
    renderRentersTab();
  });
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
    <div id="scooter-batch-bar"></div>
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
  rows.sort((a, b) => {
    const cmp = a.name.localeCompare(b.name);
    return state.scooterSortDir === 'desc' ? -cmp : cmp;
  });
  document.getElementById('scooters-table-wrapper').innerHTML = buildScootersTable(rows);
  updateScooterBatchBar();
  attachScooterRowActions();
}

function buildScootersTable(rows) {
  if (rows.length === 0) return '<div class="empty-state">Skuterlar yo\'q</div>';
  const admin = state.isAdmin();
  const sd = state.scooterSortDir;
  return `
    <div class="table-header table-header-scooters">
      <div class="th sortable" data-scooter-sort="name">Nomi ${sortArrow('name', 'name', sd)}</div>
      <div class="th">Holat</div>
    </div>
    <div class="table-rows">
      ${rows.map(s => {
        const st = scooterStatus(s.id, state.renters);
        const selected = state.selectedScooterIds.has(s.id);
        return `
          <div class="scooter-row ${st.cssClass} ${selected ? 'selected' : ''}" data-id="${s.id}">
            <div class="scooter-name">
              <span class="checkbox-wrap">
                <input type="checkbox" class="scooter-checkbox" data-id="${s.id}" ${selected ? 'checked' : ''} />
              </span>
              ${escapeHtml(s.name)}
            </div>
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
  const admin = state.isAdmin();

  document.querySelectorAll('.sortable[data-scooter-sort]').forEach(th => {
    th.style.cursor = 'pointer';
    th.addEventListener('click', () => {
      state.scooterSortDir = state.scooterSortDir === 'asc' ? 'desc' : 'asc';
      filterScooters();
    });
  });

  document.querySelectorAll('.scooter-row').forEach(row => {
    row.addEventListener('click', e => {
      if (e.target.classList.contains('scooter-checkbox')) {
        const id = parseInt(e.target.dataset.id, 10);
        if (e.target.checked) state.selectedScooterIds.add(id);
        else state.selectedScooterIds.delete(id);
        updateScooterBatchBar();
        row.classList.toggle('selected', e.target.checked);
        return;
      }
      if (e.target.closest('.row-action-btn')) return;
      const id = parseInt(row.dataset.id, 10);
      openScooterForm(id);
    });
  });

  if (admin) {
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
}

function updateScooterBatchBar() {
  const bar = document.getElementById('scooter-batch-bar');
  if (!bar) return;
  const admin = state.isAdmin();
  const count = state.selectedScooterIds.size;
  if (count === 0 || !admin) {
    bar.innerHTML = '';
    return;
  }
  bar.innerHTML = `
    <div class="batch-row">
      <span class="count">${count} ta tanlandi</span>
      <button class="btn-danger-outline" id="scooter-batch-delete">
        <span class="material-icons" style="font-size:16px">delete</span>
        O'chirish
      </button>
      <button class="btn-secondary" id="scooter-batch-clear" style="margin-left:auto">
        <span class="material-icons" style="font-size:16px">close</span>
      </button>
    </div>
  `;

  document.getElementById('scooter-batch-delete').addEventListener('click', async () => {
    const ids = [...state.selectedScooterIds];
    if (!confirm(`${ids.length} ta skuterni o'chirmoqchimisiz?`)) return;
    let ok = 0;
    for (const id of ids) {
      try { await api(`/scooters/${id}`, { method: 'DELETE' }); ok++; } catch {}
    }
    toast(`${ok} ta skuter o'chirildi`, 'success');
    state.selectedScooterIds.clear();
    await loadAll();
    renderCurrentTab();
  });

  document.getElementById('scooter-batch-clear').addEventListener('click', () => {
    state.selectedScooterIds.clear();
    updateScooterBatchBar();
    filterScooters();
  });
}

// ═══════════════════════════════════════════════════════════
// RENTER FORM (Modal)
// ═══════════════════════════════════════════════════════════
function openRenterForm(id) {
  const editing = id ? state.renters.find(r => r.id === id) : null;
  if (id && !editing) return;
  const admin = state.isAdmin();
  if (!admin && !editing) return;

  const startTs = editing ? editing.rent_start_date_timestamp : Date.now();
  const scooterOptions = state.scooters
    .map(s => `<option value="${s.id}" ${editing?.scooter_id === s.id ? 'selected' : ''}>${escapeHtml(s.name)}</option>`)
    .join('');

  const fin = editing ? calcRenterFinances(editing) : null;
  const statusLabel = editing ? (editing.is_returned ? 'Qaytarilgan' : fin.isOverdue ? 'Qarzdor' : 'Faol') : '';

  const weeklyPrice = parseInt(STORAGE.weeklyPrice, 10) || 350000;

  showModal(`
    <div class="modal-title">${editing ? 'Ijarachini tahrirlash' : 'Yangi ijarachi'}</div>
    ${editing ? `<div style="margin-bottom:12px">
      <div class="renter-detail-row"><span>Holat:</span> <strong>${statusLabel}</strong></div>
      <div class="renter-detail-row"><span>O'tgan haftalar:</span> <strong>${fin ? fin.weeksElapsed : 0}</strong></div>
      <div class="renter-detail-row"><span>Umumiy qarz (${formatNum(weeklyPrice)} × ${fin ? fin.weeksElapsed : 0}):</span> <strong>${fin ? formatNum(fin.totalOwed) : 0} so'm</strong></div>
      <div class="renter-detail-row"><span>To'langan:</span> <strong>${fin ? formatNum(fin.balancePaid) : 0} so'm</strong></div>
      <div class="renter-detail-row"><span>Balans:</span> <strong class="${fin && fin.displayBalance < 0 ? 'text-red' : 'text-green'}">${fin ? formatBalance(fin.displayBalance) : 0} so'm</strong></div>
    </div>` : ''}
    <form id="renter-form" class="modal-form">
      <div class="field">
        <label class="field-label">To'liq ism</label>
        <input class="field-input" name="name" required value="${editing ? escapeHtml(editing.name) : ''}" ${!admin ? 'readonly' : ''} />
      </div>
      <div class="field">
        <label class="field-label">Telefon raqami</label>
        <input class="field-input" name="phone" required value="${editing ? escapeHtml(editing.phone_number) : ''}" placeholder="+998 90 123 45 67" ${!admin ? 'readonly' : ''} />
      </div>
      <div class="form-row">
        <div class="field">
          <label class="field-label">Ijara boshlash sanasi</label>
          <input class="field-input" name="start_date" type="date" required value="${toDateInput(startTs)}" ${!admin ? 'readonly' : ''} />
        </div>
        <div class="field">
          <label class="field-label">Ijara muddati</label>
          <select class="field-select" name="duration" ${!admin ? 'disabled' : ''}>
            ${[{v:7,l:'1 Hafta'},{v:14,l:'2 Hafta'},{v:21,l:'3 Hafta'},{v:30,l:'1 Oy'},{v:60,l:'2 Oy'},{v:90,l:'3 Oy'},{v:120,l:'4 Oy'}].map(d =>
              `<option value="${d.v}" ${editing?.rent_duration_days === d.v ? 'selected' : ''}>${d.l}</option>`
            ).join('')}
          </select>
        </div>
      </div>
      <div class="form-row">
        <div class="field">
          <label class="field-label">Skuter</label>
          <select class="field-select" name="scooter_id" ${!admin ? 'disabled' : ''}>
            <option value="">Tanlanmagan</option>
            ${scooterOptions}
          </select>
        </div>
        <div class="field">
          <label class="field-label">Boshlang'ich qarz (UZS)</label>
          <input class="field-input" name="debt" type="number" min="0" step="1000" value="${editing?.debt_amount ?? 0}" ${!admin ? 'readonly' : ''} />
          <div class="field-hint">Agar shartnoma o'tgan sanada boshlangan bo'lsa, qarz avtomatik hisoblanadi</div>
        </div>
      </div>
      ${admin && editing ? `
      <div class="checkbox-row">
        <input type="checkbox" name="returned" ${editing?.is_returned ? 'checked' : ''} />
        <span>Skuter qaytarilgan deb belgilash</span>
      </div>` : ''}
      <div class="modal-actions">
        <button type="button" class="btn-secondary" id="cancel-btn">Bekor qilish</button>
        ${admin ? `<button type="submit" class="btn-primary">Saqlash</button>` : ''}
      </div>
    </form>
  `);

  document.getElementById('cancel-btn').addEventListener('click', closeModal);
  if (admin) {
    document.getElementById('renter-form').addEventListener('submit', async e => {
      e.preventDefault();
      const fd = new FormData(e.target);
      const startDate = parseDateInput(fd.get('start_date'));
      const duration = parseInt(fd.get('duration'), 10);
      const scooterId = fd.get('scooter_id') ? parseInt(fd.get('scooter_id'), 10) : null;
      const manualDebt = parseFloat(fd.get('debt') || '0');

      // Calculate auto debt based on elapsed time
      const now = Date.now();
      const daysElapsed = Math.max(0, Math.floor((now - startDate) / 86400000));
      const weeksElapsed = Math.max(1, Math.ceil(daysElapsed / 7));
      const weeklyPrice = parseInt(STORAGE.weeklyPrice, 10) || 350000;
      const autoDebt = weeksElapsed * weeklyPrice;
      // Use the greater of manual debt or auto-calculated debt
      const finalDebt = Math.max(manualDebt, autoDebt);

      const body = {
        name: fd.get('name').trim(),
        phone_number: fd.get('phone').trim(),
        rent_start_date_timestamp: startDate,
        rent_duration_days: duration,
        scooter_id: scooterId,
        scooter_name: state.scooters.find(s => s.id === scooterId)?.name || null,
        debt_amount: editing ? Math.max(manualDebt, editing.is_returned ? 0 : autoDebt) : (editing ? manualDebt : finalDebt),
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
  if (id && !editing) return;
  const admin = state.isAdmin();
  if (!admin && !editing) return;

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
        <label class="field-label">Skuter nomi</label>
        <input class="field-input" name="name" required value="${editing ? escapeHtml(editing.name) : autoName}" ${!admin ? 'readonly' : ''} />
        ${!editing ? `<div class="field-hint">Avtomatik: ${autoName}. Istalgan nom bilan almashtirishingiz mumkin.</div>` : ''}
      </div>
      <div class="field">
        <label class="field-label">Hujjatlashtirilgan raqami (ixtiyoriy)</label>
        <input class="field-input" name="documented_number" value="${editing?.documented_number || ''}" placeholder="Masalan: 01-234 ABC" ${!admin ? 'readonly' : ''} />
      </div>
      <div class="modal-actions">
        <button type="button" class="btn-secondary" id="cancel-btn">Bekor qilish</button>
        ${admin ? `<button type="submit" class="btn-primary">Saqlash</button>` : ''}
      </div>
    </form>
  `);

  document.getElementById('cancel-btn').addEventListener('click', closeModal);
  if (admin) {
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
// HISTORY MODAL
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
    <div class="modal-actions" style="margin-top:12px">
      <button class="btn-danger-outline" id="clear-history-btn">Tozalash</button>
      <button class="btn-secondary" id="close-hist-btn">Yopish</button>
    </div>
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

  document.getElementById('close-hist-btn').addEventListener('click', closeModal);
  document.getElementById('clear-history-btn').addEventListener('click', async () => {
    if (!confirm('Barcha tarix yozuvlarini o\'chirmoqchimisiz?')) return;
    try {
      await api('/contract-history', { method: 'DELETE' });
      state.history = [];
      toast('Tarix tozalandi', 'success');
      closeModal();
      renderMain();
    } catch (err) { toast(friendlyError(err.message), 'error'); }
  });
}

// ═══════════════════════════════════════════════════════════
// NOTIFICATIONS MODAL
// ═══════════════════════════════════════════════════════════
function openNotificationsModal() {
  const renterMap = Object.fromEntries(state.renters.map(r => [r.id, r.name]));

  if (state.notifications.length === 0) {
    showModal(`
      <div class="modal-title">Bildirishnomalar</div>
      <div class="empty-state">Hech qanday bildirishnoma yo'q</div>
      <div class="modal-actions">
        <button class="btn-secondary" id="close-notif-btn">Yopish</button>
      </div>
    `);
    document.getElementById('close-notif-btn').addEventListener('click', closeModal);
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
    <div class="modal-actions" style="margin-top:12px">
      <button class="btn-danger-outline" id="clear-notif-btn">Tozalash</button>
      <button class="btn-secondary" id="close-notif-btn">Yopish</button>
    </div>
  `);

  document.getElementById('close-notif-btn').addEventListener('click', closeModal);
  document.getElementById('clear-notif-btn').addEventListener('click', async () => {
    if (!confirm('Barcha bildirishnomalarni o\'chirmoqchimisiz?')) return;
    try {
      await api('/notifications', { method: 'DELETE' });
      state.notifications = [];
      toast('Bildirishnomalar tozalandi', 'success');
      closeModal();
      renderMain();
    } catch (err) { toast(friendlyError(err.message), 'error'); }
  });
}

// ═══════════════════════════════════════════════════════════
// SETTINGS MODAL
// ═══════════════════════════════════════════════════════════
async function openSettings() {
  let serverInfo = null;
  try {
    serverInfo = await api('/auth/me');
  } catch (err) { /* token may have expired */ }

  const admin = state.isAdmin();
  const defaultSms = `Assalomu alaykum {name}, sizning skuter ijarangiz {days} kunga kechikdi. Iltimos, to'lovni o'z vaqtida kiriting. Umumiy qarz: {debt}.

https://transfer.paycom.uz/680a40043fc0407a2e48e8fe

Call center: 71 200 55 56.`;

  showModal(`
    <div class="modal-title">Sozlamalar</div>

    <div class="settings-section">
      <div class="settings-section-title">Akkaunt</div>
      <div class="settings-section-content">
        <div class="settings-info-row">
          <span class="label">Server URL</span>
          <span class="value mono">${escapeHtml(STORAGE.base)}</span>
        </div>
        <div class="settings-info-row">
          <span class="label">Email</span>
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

    ${admin ? `
    <div class="settings-section">
      <div class="settings-section-title">Tariflar</div>
      <div class="settings-section-content">
        <div class="form-row" style="display:grid;grid-template-columns:1fr 1fr;gap:8px">
          <div class="field">
            <label class="field-label">Haftalik tarif narxi (UZS)</label>
            <input class="field-input" id="setting-weekly-price" type="number" value="${STORAGE.weeklyPrice}" />
          </div>
          <div class="field">
            <label class="field-label">Oylik tarif narxi (UZS)</label>
            <input class="field-input" id="setting-monthly-price" type="number" value="${STORAGE.monthlyPrice}" />
          </div>
        </div>
        <div class="field-hint" style="margin-top:6px">Balans avtomatik hisoblanadi: haftalar soni × haftalik narx</div>
      </div>
    </div>

    <div class="settings-section">
      <div class="settings-section-title">SMS shabloni</div>
      <div class="settings-section-content">
        <textarea class="field-input" id="setting-sms-template" rows="5" style="resize:vertical;font-family:inherit;min-height:100px">${escapeHtml(STORAGE.smsTemplate || defaultSms)}</textarea>
        <div class="field-hint" style="margin-top:6px">
          Shablon teglari: <code>{name}</code>, <code>{days}</code>, <code>{debt}</code>
        </div>
      </div>
    </div>
    ` : ''}

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
        <div class="settings-info-row">
          <span class="label">Qarzdorlar</span>
          <span class="value text-red">${state.renters.filter(r => !r.is_returned && calcRenterFinances(r).displayBalance < 0).length}</span>
        </div>
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
      ${admin ? `<button class="btn-secondary" id="save-settings-btn">Saqlash</button>` : ''}
      <button class="btn-danger-outline" id="logout-btn">Chiqish (boshqa akkauntga o'tish)</button>
    </div>
  `);

  document.getElementById('refresh-btn').addEventListener('click', async () => {
    const btn = document.getElementById('refresh-btn');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner"></span> Yuklanmoqda...';
    try {
      state.overdueChecked = false;
      await loadAll();
      await checkAndNotifyOverdue();
      state.notifications = await api('/notifications').catch(() => state.notifications);
      toast('Ma\'lumotlar yangilandi', 'success');
      closeModal();
      renderMain();
    } catch (err) {
      toast(friendlyError(err.message), 'error');
      btn.disabled = false;
      btn.innerHTML = '<span class="material-icons" style="font-size:18px">sync</span> Ma\'lumotlarni yangilash';
    }
  });

  document.getElementById('logout-btn').addEventListener('click', () => {
    closeModal();
    doLogout();
  });

  if (admin) {
    document.getElementById('save-settings-btn').addEventListener('click', () => {
      const weekly = document.getElementById('setting-weekly-price').value;
      const monthly = document.getElementById('setting-monthly-price').value;
      const sms = document.getElementById('setting-sms-template').value;
      STORAGE.weeklyPrice = weekly;
      STORAGE.monthlyPrice = monthly;
      STORAGE.smsTemplate = sms;
      toast('Sozlamalar saqlandi', 'success');
      closeModal();
      // Re-render to update balances
      renderCurrentTab();
    });
  }
}

// ═══════════════════════════════════════════════════════════
// BOOT
// ═══════════════════════════════════════════════════════════
(async function init() {
  if (STORAGE.token) {
    try {
      await loadAll();
      await checkAndNotifyOverdue();
      // Reload notifications after creating overdue ones
      if (state.isAdmin()) {
        state.notifications = await api('/notifications').catch(() => state.notifications);
      }
      renderMain();
    } catch (err) {
      STORAGE.clear();
      renderLogin();
    }
  } else {
    renderLogin();
  }
})();
