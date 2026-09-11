import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const script = readFileSync(new URL('../src/main/resources/static/account.js', import.meta.url), 'utf8');
const page = readFileSync(new URL('../src/main/resources/static/account.html', import.meta.url), 'utf8');
assert.ok(page.includes('content="no-referrer"') && page.includes("script-src 'self'"));
const payload = '<img src=x onerror=alert(1)>';
async function browser(action, loggedIn = false) {
  const elements = new Map(), calls = [], history = [];
  const element = () => ({ value: '', hidden: true, textContent: '', handlers: {}, children: [], button: {},
    addEventListener(type, fn) { this.handlers[type] = fn; }, querySelector() { return this.button; },
    append(child) { this.children.push(child); }, replaceChildren() { this.children = []; }, reset() {},
    set innerHTML(_) { throw new Error('Unsafe HTML insertion'); },
  });
  const get = id => { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); };
  const context = vm.createContext({ URLSearchParams, TextEncoder, Date, encodeURIComponent,
    location: { hash: `#action=${action}&token=proof-in-fragment`, pathname: '/account.html' },
    history: { replaceState(...args) { history.push(args); } },
    document: { getElementById: get, createElement: element },
    fetch: async (path, options) => {
      calls.push({ path, ...options });
      const ok = path !== '/api/v1/me' || loggedIn;
      const data = path.endsWith('/csrf') ? { headerName: 'X-CSRF-TOKEN', token: 'csrf-value' }
        : path === '/api/v1/me' ? { email: payload, localLogin: true, googleLogin: false }
        : path.endsWith('/sessions') ? [{ id: 'session-id', userAgent: payload, current: true, createdAt: '2026-09-11' }] : {};
      return { ok, json: async () => ({ data }) };
    },
  });
  vm.runInContext(script, context);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(history[0][2], '/account.html');
  assert.ok(calls.every(c => !c.path.includes('proof-in-fragment')));
  return { get, calls, async submit(id) { const target = get(id); await target.handlers.submit({ target, preventDefault() {} }); } };
}
for (const action of ['signup', 'reset']) {
  const b = await browser(action);
  b.get('new-password').value = 'a sufficiently long password';
  b.get('confirm-password').value = 'different';
  await b.submit('confirm-form');
  assert.ok(!b.calls.some(c => c.method === 'POST'));
  b.get('confirm-password').value = b.get('new-password').value;
  await b.submit('confirm-form');
  const request = b.calls.find(c => c.method === 'POST');
  assert.equal(request.path, action === 'signup' ? '/api/v1/auth/signup' : '/api/v1/auth/password/reset');
  assert.equal(request.headers['X-CSRF-TOKEN'], 'csrf-value');
  assert.equal(request.credentials, 'include');
  assert.equal(JSON.parse(request.body).token, 'proof-in-fragment');
  await b.submit('confirm-form');
  assert.equal(b.calls.filter(c => c.method === 'POST').length, 1); // In-memory proof cleared after success.
}
const b = await browser('none', true);
assert.equal(b.get('member-email').textContent, payload);
assert.ok(b.get('sessions').children[0].textContent.includes(payload));
await b.get('sessions').children[0].children[0].handlers.click();
const deletion = b.calls.find(c => c.method === 'DELETE');
assert.equal(deletion.path, '/api/v1/me/sessions/session-id');
assert.equal(deletion.headers['X-CSRF-TOKEN'], 'csrf-value');
console.log('Account security: signup/reset proof handling, XSS-safe rendering and CSRF revocation passed');
