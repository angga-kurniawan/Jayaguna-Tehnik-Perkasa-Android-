import {test} from 'node:test';
import assert from 'node:assert/strict';
import {HttpsError} from 'firebase-functions/v2/https';
import adapter from '../lib/admin-server.js';

const owner = {uid: 'owner-fixture', role: 'OWNER'};
async function start(t, overrides = {}) {
  const seen = {verified: [], operations: []};
  const server = adapter.adminServer({
    verifyIdToken: async (...args) => { seen.verified.push(args); return owner; },
    manageAdmin: async request => { seen.operations.push(request); return {uid: 'admin-fixture', email: 'fixture@example.test'}; },
    ...overrides,
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  });
  return {base: `http://127.0.0.1:${server.address().port}`, seen};
}
const headers = {Authorization: 'Bearer fixture-token', 'Content-Type': 'application/json'};

test('admin profile exposes health only; other routes never reach credentials or business handlers', async t => {
  const {base, seen} = await start(t);
  const health = await fetch(base + '/healthz');
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), {status: 'ok', service: 'jayaguna-admin-api'});
  for (const [method, path] of [['POST', '/api/call/saveProduct'], ['GET', '/api/drive/oauth/callback'], ['PUT', '/api/uploads/anything'], ['GET', '/api/media/anything'], ['GET', '/api/call/manageAdmin'], ['OPTIONS', '/api/call/manageAdmin']]) {
    assert.equal((await fetch(base + path, {method})).status, 404);
  }
  assert.deepEqual(seen, {verified: [], operations: []});
});

test('missing, invalid, and revoked credentials cannot reach admin operations', async t => {
  const {base, seen} = await start(t, {verifyIdToken: async () => { throw new Error('credential-secret'); }});
  const missing = await fetch(base + '/api/call/manageAdmin', {method: 'POST', body: '{}'});
  assert.equal(missing.status, 401);
  const invalid = await fetch(base + '/api/call/manageAdmin', {method: 'POST', headers, body: '{}'});
  assert.equal(invalid.status, 401);
  assert.equal((await invalid.json()).error.code, 'unauthenticated');
  assert.equal(seen.operations.length, 0);
});

test('ADMIN claims and service-level inactive OWNER denial remain forbidden', async t => {
  const admin = await start(t, {verifyIdToken: async () => ({uid: 'admin-fixture', role: 'ADMIN'})});
  assert.equal((await fetch(admin.base + '/api/call/manageAdmin', {method: 'POST', headers, body: '{}'})).status, 403);
  assert.equal(admin.seen.operations.length, 0);
  const inactive = await start(t, {manageAdmin: async () => { throw new HttpsError('permission-denied', 'Sesi OWNER tidak berlaku.'); }});
  assert.equal((await fetch(inactive.base + '/api/call/manageAdmin', {method: 'POST', headers, body: '{}'})).status, 403);
});

test('valid admin request preserves the existing JSON protocol and checks revoked tokens', async t => {
  const {base, seen} = await start(t);
  const payload = {operation: 'create', requestId: 'fixture-request', name: 'Fixture', email: 'fixture@example.test'};
  const response = await fetch(base + '/api/call/manageAdmin', {method: 'POST', headers, body: JSON.stringify(payload)});
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {result: {uid: 'admin-fixture', email: 'fixture@example.test'}});
  assert.deepEqual(seen.verified, [['fixture-token', true]]);
  assert.deepEqual(seen.operations, [{auth: {uid: owner.uid, token: owner}, data: payload}]);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
});

test('oversized, encoded, malformed and non-object payloads never reach the service', async t => {
  const {base, seen} = await start(t);
  for (const [customHeaders, body, status] of [
    [headers, 'x'.repeat(16 * 1024 + 1), 429],
    [{...headers, 'Content-Encoding': 'gzip'}, '{}', 400],
    [headers, '{', 400], [headers, 'null', 400], [headers, '[]', 400],
    [{...headers, 'Content-Type': 'text/plain'}, '{}', 400],
  ]) assert.equal((await fetch(base + '/api/call/manageAdmin', {method: 'POST', headers: customHeaders, body})).status, status);
  assert.equal(seen.operations.length, 0);
});

test('browser origins are denied and unexpected failures never expose secrets', async t => {
  const {base, seen} = await start(t, {manageAdmin: async () => { throw new Error('private-key-fixture bearer-secret'); }});
  const denied = await fetch(base + '/api/call/manageAdmin', {method: 'POST', headers: {...headers, Origin: 'https://untrusted.example'}, body: '{}'});
  assert.equal(denied.status, 403);
  assert.equal(seen.verified.length, 0);
  const messages = [];
  const original = console.error;
  console.error = message => messages.push(message);
  try {
    const response = await fetch(base + '/api/call/manageAdmin', {method: 'POST', headers, body: '{}'});
    assert.equal(response.status, 500);
    const body = await response.text();
    assert.ok(!body.includes('private-key-fixture') && !body.includes('bearer-secret'));
    assert.ok(messages.every(message => !message.includes('private-key-fixture') && !message.includes('bearer-secret')));
  } finally { console.error = original; }
});

test('request limits bound repeated public requests', async t => {
  const {base} = await start(t);
  for (let i = 0; i < 60; i++) assert.equal((await fetch(base + '/healthz')).status, 200);
  const response = await fetch(base + '/healthz');
  assert.equal(response.status, 429);
  assert.equal(response.headers.get('retry-after'), '60');
});

test('production rejects missing server credentials or legacy App Check configuration', () => {
  assert.throws(() => adapter.validateAdminEnvironment({NODE_ENV: 'production'}), /Kredensial Firebase/);
  assert.throws(() => adapter.validateAdminEnvironment({ENFORCE_APP_CHECK: 'true'}), /konfigurasi khusus/);
  assert.throws(() => adapter.validateAdminEnvironment({GOOGLE_APPLICATION_CREDENTIALS: '/missing/private-key-fixture.json'}), error => !error.message.includes('private-key-fixture'));
  assert.throws(() => adapter.validateAdminEnvironment({NODE_ENV: 'production', FIREBASE_SERVICE_ACCOUNT_JSON: 'provided-by-secret-store', FIREBASE_AUTH_EMULATOR_HOST: '127.0.0.1:9099'}), /endpoint emulator/);
  assert.doesNotThrow(() => adapter.validateAdminEnvironment({NODE_ENV: 'production', FIREBASE_SERVICE_ACCOUNT_JSON: 'provided-by-secret-store'}));
  assert.doesNotThrow(() => adapter.validateAdminEnvironment({NODE_ENV: 'production', JTP_ADMIN_ATTACHED_ADC: 'true'}));
  assert.doesNotThrow(() => adapter.validateAdminEnvironment({NODE_ENV: 'test', FIREBASE_AUTH_EMULATOR_HOST: '127.0.0.1:9099', FIRESTORE_EMULATOR_HOST: '127.0.0.1:8080'}));
});
