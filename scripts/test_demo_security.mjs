import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const page = readFileSync(new URL('../src/main/resources/static/index.html', import.meta.url), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/loadServices\(\);\s*$/, '');
const elements = new Map();
let response;
const context = vm.createContext({
  document: { getElementById(id) {
    if (!elements.has(id)) elements.set(id, { innerHTML: '', value: '' });
    return elements.get(id);
  } },
  fetch: async () => ({ ok: true, status: 200, json: async () => response }),
});
vm.runInContext(script, context);
const payload = '<img src=x onerror="window.STOLEN=true">';
const injection = '"><svg onload="window.STOLEN=true">';
context.payload = payload;
for (const expression of [
  'raw({body:{text:payload}})',
  'statusLine({ok:false,body:{error:{code:payload,message:payload}}})',
  'missBox([{field:payload}])',
  'renderResult({planName:payload,carrier:payload,monthlyTotal:1,baseline:1,monthlySavings:0,annualSavings:0,breakdown:[{label:payload,note:payload,amount:1}]})',
]) {
  const html = vm.runInContext(expression, context);
  assert.ok(!html.includes('<img'), expression);
  assert.ok(html.includes('&lt;img'), expression);
}
response = { data: [{ id: injection, name: payload }] };
await vm.runInContext('loadServices()', context);
assert.ok(!elements.get('recSvc').innerHTML.includes('<svg'));
assert.ok(elements.get('recSvc').innerHTML.includes('&quot;&gt;&lt;svg'));
response = { data: [{ name: payload, carrier: payload, networkType: payload, basePrice: 1 }] };
await vm.runInContext('loadPlans()', context);
assert.ok(!elements.get('catOut').innerHTML.includes('<img'));
console.log('Demo security: 6 rendering scenarios passed');
