import assert from 'node:assert/strict';
import {configureDemoEnvironment, DEMO_PROJECT_ID} from './emulator-environment.mjs';

const config = configureDemoEnvironment();
const functionsHost = process.env.FUNCTIONS_EMULATOR_HOST || '127.0.0.1:5001';
if (!/^(127\.0\.0\.1|localhost|\[::1\]):([0-9]{1,5})$/.test(functionsHost)) throw new Error('Functions emulator wajib memakai loopback.');
const functionsBase = `http://${functionsHost}/${DEMO_PROJECT_ID}/asia-southeast2`;
const accounts = [
  {email: 'owner.demo@jayaguna.test', password: 'OwnerDemo!2026', role: 'OWNER'},
  {email: 'admin.demo@jayaguna.test', password: 'AdminDemo!2026', role: 'ADMIN'},
];

async function callable(name, token) {
  const response = await fetch(`${functionsBase}/${name}`, {
    method: 'POST', headers: {'Content-Type': 'application/json', ...(token ? {Authorization: `Bearer ${token}`} : {})},
    body: JSON.stringify({data: {}}), signal: AbortSignal.timeout(20000),
  });
  let body;
  try { body = await response.json(); } catch { body = {error: {message: 'Respons bukan JSON callable'}}; }
  // Only API results are logged. ID tokens/passwords are never printed.
  const summary = name === 'getDashboard' && body.result
    ? {activeProducts: body.result.activeProducts, draftProducts: body.result.draftProducts, activePartners: body.result.activePartners, recentCount: body.result.recent?.length ?? 0}
    : body;
  console.log(`${name}: HTTP ${response.status}, ${JSON.stringify(summary)}`);
  return {status: response.status, body};
}

for (const account of accounts) {
  const response = await fetch(`http://${config.endpoints.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo-key`, {
    method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({email: account.email, password: account.password, returnSecureToken: true}), signal: AbortSignal.timeout(10000),
  });
  const signIn = await response.json();
  assert.equal(response.status, 200, `Auth emulator login gagal untuk ${account.role}`);
  console.log(`${account.role}: email/password login HTTP ${response.status}`);
  const session = await callable('getSession', signIn.idToken);
  assert.equal(session.status, 200, `${account.role} getSession harus berhasil`);
  assert.equal(session.body.result.role, account.role);
  const dashboard = await callable('getDashboard', signIn.idToken);
  assert.equal(dashboard.status, 200, `${account.role} getDashboard harus berhasil`);
  for (const key of ['activeProducts', 'draftProducts', 'activePartners']) assert.ok(Number.isInteger(dashboard.body.result[key]) && dashboard.body.result[key] >= 0);
}
const denied = await callable('getSession');
assert.equal(denied.status, 401);
assert.equal(denied.body.error.status, 'UNAUTHENTICATED');
console.log('Verifikasi callable emulator berhasil untuk OWNER, ADMIN, dan penolakan tanpa autentikasi.');
