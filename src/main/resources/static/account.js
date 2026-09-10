(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const fragment = new URLSearchParams(location.hash.slice(1));
  const action = fragment.get('action');
  let proof = fragment.get('token');
  history.replaceState(null, '', location.pathname); // Email proof never stays in browser history or persistent storage.
  let member;
  const show = message => { $('message').textContent = message; };
  const validPassword = password => {
    if ([...password].length < 15 || new TextEncoder().encode(password).length > 72)
      throw new Error('비밀번호를 15자 이상, 72바이트 이하로 입력해 주세요.');
  };
  async function api(path, method = 'GET', body) {
    const headers = {};
    if (method !== 'GET') {
      const csrfResponse = await fetch('/api/v1/auth/csrf', { credentials: 'include' });
      if (!csrfResponse.ok) throw new Error('요청을 준비하지 못했습니다. 다시 시도해 주세요.');
      const csrf = await csrfResponse.json();
      headers[csrf.data.headerName] = csrf.data.token;
      if (body !== undefined) headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(path, { method, credentials: 'include', headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error?.message || '요청을 처리하지 못했습니다.');
    return result.data;
  }
  function handle(id, callback) {
    $(id).addEventListener('submit', async event => {
      event.preventDefault(); const button = event.target.querySelector('button'); button.disabled = true;
      try { await callback(); } catch (error) { show(error.message); } finally { button.disabled = false; }
    });
  }
  async function refresh() {
    try { member = await api('/api/v1/me'); } catch { member = null; }
    $('member').hidden = !member; $('login-section').hidden = Boolean(member);
    if (!member) return;
    $('member-email').textContent = member.email;
    $('link-form').hidden = member.localLogin && member.googleLogin;
    $('link-label').textContent = member.localLogin ? '현재 비밀번호 재확인' : '추가할 새 비밀번호';
    $('link-password').autocomplete = member.localLogin ? 'current-password' : 'new-password';
    $('link-button').textContent = member.localLogin ? 'Google 계정 연결' : '자체 비밀번호 추가';
    const sessions = await api('/api/v1/me/sessions');
    $('sessions').replaceChildren();
    for (const session of sessions) {
      const item = document.createElement('li');
      item.textContent = `${session.current ? '현재 브라우저 · ' : ''}${session.userAgent} · ${new Date(session.createdAt).toLocaleString('ko-KR')}`;
      const button = document.createElement('button'); button.type = 'button'; button.textContent = '이 로그인 종료';
      button.addEventListener('click', async () => {
        button.disabled = true;
        try { await api(`/api/v1/me/sessions/${encodeURIComponent(session.id)}`, 'DELETE'); await refresh(); show('로그인을 종료했습니다.'); }
        catch (error) { show(error.message); button.disabled = false; }
      });
      item.append(button); $('sessions').append(item);
    }
  }
  if (proof && (action === 'signup' || action === 'reset')) {
    $('confirmation').hidden = false;
    $('confirmation-title').textContent = action === 'signup' ? '이메일 확인 · 가입 완료' : '비밀번호 재설정';
  } else {
    proof = null;
    if (fragment.get('auth') === 'success') show('로그인 또는 계정 연결이 완료되었습니다.');
    else if (fragment.has('auth')) show('로그인 정보를 확인해 주세요. 기존 계정이 있으면 로그인 후 연결할 수 있습니다.');
  }
  handle('confirm-form', async () => {
    const password = $('new-password').value; validPassword(password);
    if (password !== $('confirm-password').value) throw new Error('두 비밀번호가 일치하지 않습니다.');
    if (!proof) throw new Error('본인 확인 메일을 다시 요청해 주세요.');
    await api(action === 'signup' ? '/api/v1/auth/signup' : '/api/v1/auth/password/reset', 'POST', { token: proof, password });
    proof = null; $('confirm-form').reset(); $('confirmation').hidden = true;
    show(action === 'signup' ? '가입이 완료되었습니다.' : '비밀번호를 변경했습니다. 새 비밀번호로 로그인해 주세요.');
    await refresh();
  });
  handle('login-form', async () => {
    await api('/api/v1/auth/login', 'POST', { email: $('email').value, password: $('password').value });
    $('password').value = ''; show('로그인했습니다.'); await refresh();
  });
  handle('mail-form', async () => {
    const path = $('mail-action').value === 'verification' ? '/api/v1/auth/email/verification' : '/api/v1/auth/password/reset-request';
    const result = await api(path, 'POST', { email: $('mail-email').value }); show(result.message);
  });
  handle('link-form', async () => {
    const password = $('link-password').value;
    if (!member.localLogin) validPassword(password);
    const result = await api(member.localLogin ? '/api/v1/auth/google/link' : '/api/v1/auth/password', 'POST', { password });
    $('link-password').value = '';
    if (result.authorizationUrl !== '/oauth2/authorization/google') throw new Error('연결을 시작하지 못했습니다.');
    location.assign(result.authorizationUrl);
  });
  for (const id of ['logout', 'logout-all']) $(id).addEventListener('click', async () => {
    try { await api(`/api/v1/auth/${id}`, 'POST', {}); show('로그아웃했습니다.'); await refresh(); }
    catch (error) { show(error.message); }
  });
  refresh().catch(error => show(error.message));
})();
