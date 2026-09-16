// 백오피스 화면(D-32). 숫자는 전부 서버가 만든다 — 여기서 계산하지 않는다.
// 회원 화면(account.js)과 같은 방식으로 쿠키 세션 + CSRF 헤더를 쓴다.
const $ = id => document.getElementById(id);
const say = text => { $('message').textContent = text; };

async function call(path, { method = 'GET', body } = {}) {
  const headers = {};
  if (method !== 'GET') {
    const csrf = await fetch('/api/v1/auth/csrf', { credentials: 'include' });
    if (!csrf.ok) throw new Error('요청을 준비하지 못했어요. 다시 시도해 주세요.');
    const issued = await csrf.json();
    headers[issued.data.headerName] = issued.data.token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(path, {
    method, credentials: 'include', headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : {};
  if (!response.ok) throw new Error(payload.error?.message || `요청이 실패했어요 (${response.status})`);
  return payload.data;
}

/** 로그인 여부로 화면을 가른다. 관리자가 아니면 지표·검수는 아예 보이지 않는다. */
async function showSession() {
  try {
    const session = await call('/api/v1/admin/session');
    $('login-section').hidden = true;
    $('dashboard-section').hidden = false;
    $('review-section').hidden = false;
    $('who').textContent = `${session.loginId} (회원번호 ${session.userId})로 로그인했어요.`;
    await Promise.all([loadDashboard(), loadRequests()]);
    return true;
  } catch {
    $('login-section').hidden = false;
    $('dashboard-section').hidden = true;
    $('review-section').hidden = true;
    return false;
  }
}

const CARDS = [
  ['members.total', '전체 회원'],
  ['members.signedUp24h', '24시간 가입'],
  ['members.signedUp7d', '7일 가입'],
  ['members.withSubscription', '구독 등록 회원'],
  ['members.activeSessions', '활성 세션'],
  ['catalog.mobilePlans', '요금제'],
  ['catalog.subscriptionTiers', '구독 등급'],
  ['catalog.planBenefits', '제휴 혜택'],
  ['review.pendingRequests', '검수 대기'],
  ['review.mismatched', '불일치(차단)'],
  ['review.appliedToday', '오늘 반영'],
  ['reports.pending', '제보 대기'],
  ['gaps', '카탈로그 결손'],
];

const pick = (data, path) => path.split('.').reduce((value, key) => (value ?? {})[key], data);

async function loadDashboard() {
  const data = await call('/api/v1/admin/dashboard');
  // -1 은 서버가 "알 수 없음"으로 표시한 값이다. 0 으로 보여주면 거짓말이 된다.
  $('cards').replaceChildren(...CARDS.map(([path, label]) => {
    const value = pick(data, path);
    const item = document.createElement('li');
    const number = document.createElement('b');
    number.textContent = value === -1 || value === undefined ? '—' : Number(value).toLocaleString('ko-KR');
    const caption = document.createElement('span');
    caption.textContent = label;
    item.append(number, caption);
    return item;
  }));
  $('trend').replaceChildren(...(data.signupTrend || []).map(row => tr([row.date, row.count])));
  $('endpoints').replaceChildren(...(data.endpoints || []).map(row =>
    tr([row.uri, row.method, row.status, row.count, row.avgMs])));
}

function tr(cells) {
  const row = document.createElement('tr');
  for (const cell of cells) {
    const td = document.createElement('td');
    td.textContent = cell ?? '';
    row.append(td);
  }
  return row;
}

async function loadRequests() {
  const status = $('status-filter').value;
  const rows = await call(`/api/v1/admin/catalog/requests${status ? `?status=${status}` : ''}`);
  $('requests').replaceChildren(...rows.map(request => requestRow(request, status)));
  if (!rows.length) $('requests').append(tr(['', '대기 중인 항목이 없어요.', '', '', '', '']));
}

function requestRow(request, status) {
  const row = document.createElement('tr');
  row.append(cell(`#${request.id}`), cell(`${request.dataset}\n${request.row_key ?? ''}`), cell(request.payload ?? ''));

  const review = document.createElement('td');
  const tag = document.createElement('span');
  tag.className = `tag ${request.review_status || 'SKIPPED'}`;
  tag.textContent = request.review_status || '—';
  const detail = document.createElement('div');
  detail.textContent = request.review_detail ?? '';
  review.append(tag, detail);
  row.append(review, cell(request.reason ?? ''));

  const actions = document.createElement('td');
  actions.className = 'row-actions';
  if (request.status === 'PENDING') {
    const approve = document.createElement('button');
    approve.textContent = '승인';
    approve.disabled = request.review_status === 'MISMATCH';   // 서버도 막지만 화면에서도 먼저 막는다
    approve.addEventListener('click', () => decide(request.id, 'approve'));
    const reject = document.createElement('button');
    reject.textContent = '거절';
    reject.className = 'danger';
    reject.addEventListener('click', () => decide(request.id, 'reject'));
    actions.append(approve, reject);
  } else {
    actions.textContent = request.status;
  }
  row.append(actions);
  if (status === '') row.title = request.status;
  return row;
}

function cell(text) {
  const td = document.createElement('td');
  td.textContent = text;
  return td;
}

async function decide(id, action) {
  const reason = action === 'reject' ? window.prompt('거절 사유를 적어주세요.') : null;
  if (action === 'reject' && reason === null) return;
  try {
    await call(`/api/v1/admin/catalog/requests/${id}/${action}`, {
      method: 'POST', body: action === 'reject' ? { reason } : {},
    });
    say(`#${id} ${action === 'approve' ? '승인했어요. 카탈로그에 반영됐습니다.' : '거절했어요.'}`);
    await Promise.all([loadRequests(), loadDashboard()]);
  } catch (error) {
    say(error.message);
  }
}

$('login-form').addEventListener('submit', async event => {
  event.preventDefault();
  say('');
  try {
    await call('/api/v1/admin/login', {
      method: 'POST', body: { id: $('admin-id').value, password: $('admin-password').value },
    });
    $('admin-password').value = '';
    await showSession();
  } catch (error) {
    say(error.message);
  }
});

$('logout').addEventListener('click', async () => {
  try { await call('/api/v1/auth/logout', { method: 'POST', body: {} }); } catch { /* 이미 만료됐을 수 있다 */ }
  await showSession();
});

$('refresh').addEventListener('click', () => loadDashboard().catch(error => say(error.message)));
$('status-filter').addEventListener('change', () => loadRequests().catch(error => say(error.message)));

$('harvest').addEventListener('click', async () => {
  say('수집하는 중이에요…');
  try {
    const result = await call('/api/v1/admin/harvest/run', { method: 'POST', body: {} });
    say(`수집 완료 — 요금제 ${result.proposedMobilePlans}건 · 구독 ${result.proposedSubscriptionTiers}건 제안,`
      + ` 검수 대기 ${result.pending}건`);
    await Promise.all([loadRequests(), loadDashboard()]);
  } catch (error) {
    say(error.message);
  }
});

showSession();
