import assert from 'node:assert/strict';
import {configureDemoEnvironment, DEMO_PROJECT_ID, DEMO_STORAGE_BUCKET} from './emulator-environment.mjs';

// Validate every endpoint/project before importing an SDK or making any request.
const config = configureDemoEnvironment();
const {initializeApp, deleteApp} = await import('firebase-admin/app');
const {getAuth} = await import('firebase-admin/auth');
const {getFirestore, FieldValue} = await import('firebase-admin/firestore');
const {getStorage} = await import('firebase-admin/storage');
const {default: sharp} = await import('sharp');
const app = initializeApp({
  projectId: DEMO_PROJECT_ID,
  storageBucket: DEMO_STORAGE_BUCKET,
});
const db = getFirestore(app);
const auth = getAuth(app);
const bucket = getStorage(app).bucket();
const service = await import('../lib/service.js');

const accounts = [
  {uid: 'demo-owner', email: 'owner.demo@jayaguna.test', password: 'OwnerDemo!2026', name: 'Owner Contoh', role: 'OWNER'},
  {uid: 'demo-admin', email: 'admin.demo@jayaguna.test', password: 'AdminDemo!2026', name: 'Admin Contoh', role: 'ADMIN'},
];
const ids = {
  active: 'c9d6ef47-8efa-41bc-a812-11cc9f336001',
  draft: 'c9d6ef47-8efa-41bc-a812-11cc9f336002',
  inactive: 'c9d6ef47-8efa-41bc-a812-11cc9f336003',
  partner: 'c9d6ef47-8efa-41bc-a812-11cc9f336004',
};
const requests = {
  active: '562b2936-f67a-47e6-af21-52f4036ef001',
  draft: '562b2936-f67a-47e6-af21-52f4036ef002',
  inactive: '562b2936-f67a-47e6-af21-52f4036ef003',
  partner: '562b2936-f67a-47e6-af21-52f4036ef004',
  productUpload: '562b2936-f67a-47e6-af21-52f4036ef005',
  partnerUpload: '562b2936-f67a-47e6-af21-52f4036ef006',
};
const device = {
  installationId: 'd8b67167-bbe9-45fb-9253-43c70f36b4f8',
  deviceName: 'Seed Firebase Emulator', brand: 'Lokal', model: 'Data contoh',
  androidVersion: 'emulator', appVersion: 'seed-1.0',
};
const status = [];
const preservedAccounts = new Set();

async function ensureAccount(account) {
  let user;
  try { user = await auth.getUser(account.uid); }
  catch (error) { if (error.code !== 'auth/user-not-found') throw error; }
  const existed = Boolean(user);
  if (user) {
    assert.equal(user.email, account.email, `UID ${account.uid} sudah dipakai email lain; data tidak diubah.`);
    preservedAccounts.add(account.uid);
    // Existing access changes and passwords are deliberately retained.
  } else {
    user = await auth.createUser({uid: account.uid, email: account.email, password: account.password, displayName: account.name, emailVerified: true});
    await auth.setCustomUserClaims(account.uid, {role: account.role});
  }
  const profile = db.doc(`users/${account.uid}`);
  await db.runTransaction(async tx => {
    const snapshot = await tx.get(profile);
    if (!snapshot.exists) {
      tx.create(profile, {uid: account.uid, email: account.email, name: account.name, role: account.role, active: true, version: 1, revokedBeforeSeconds: 0, createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp()});
    }
  });
  status.push(`${account.role}: ${existed ? 'dipertahankan' : 'dibuat'}`);
}

async function login(account) {
  const endpoint = `http://${config.endpoints.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo-key`;
  const response = await fetch(endpoint, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({email: account.email, password: account.password, returnSecureToken: true}), signal: AbortSignal.timeout(10000)});
  const data = await response.json();
  if (!response.ok) throw new Error(`Login akun contoh ${account.email} gagal (${data.error?.message ?? response.status}). Seed tidak mereset akun yang sudah diubah; gunakan akun emulator yang tersedia atau mulai ulang emulator kosong.`);
  const token = JSON.parse(Buffer.from(data.idToken.split('.')[1], 'base64url').toString('utf8'));
  return {uid: data.localId, token};
}

async function stageImage(actor, entityType, entityId, requestId, bytes) {
  const slot = await service.beginUpload({auth: actor, data: {requestId, device, entityType, entityId, contentType: 'image/png', bytes: bytes.length}});
  const file = bucket.file(slot.storagePath);
  try {
    await file.save(bytes, {resumable: false, preconditionOpts: {ifGenerationMatch: 0}, metadata: {contentType: 'image/png'}});
  } catch (error) { if (Number(error.code) !== 412) throw error; }
  return {storagePath: slot.storagePath, url: ''};
}

async function seedEntity(actor, key, type, dataFactory) {
  const ref = db.doc(`${type === 'product' ? 'products' : 'partners'}/${ids[key]}`);
  if ((await ref.get()).exists) { status.push(`${type}/${ids[key]}: dipertahankan`); return; }
  const data = await dataFactory();
  await service.saveEntity({auth: actor, data: {requestId: requests[key], device, id: ids[key], expectedVersion: 0, data}}, type);
  status.push(`${type}/${ids[key]}: dibuat`);
}

async function seed() {
  // Fail quickly when an emulator is missing instead of waiting for SDK retries.
  for (const [name, endpoint] of Object.entries(config.endpoints)) {
    try { await fetch(`http://${endpoint}/`, {signal: AbortSignal.timeout(3000)}); }
    catch { throw new Error(`${name} tidak merespons pada ${endpoint}. Jalankan seluruh emulator dahulu.`); }
  }
  for (const account of accounts) await ensureAccount(account);
  // Real email/password login supplies the real emulator auth_time and claims.
  const owner = await login(accounts[0]);
  const session = await service.getSession({auth: owner, data: {}});
  assert.equal(session.role, 'OWNER');

  const schemaRef = db.doc('system/schema');
  await db.runTransaction(async tx => {
    if (!(await tx.get(schemaRef)).exists) tx.create(schemaRef, {
      schemaVersion: 1, projectId: DEMO_PROJECT_ID, environment: 'emulator', initializedAt: FieldValue.serverTimestamp(),
      collections: ['products', 'partners', 'publicProducts', 'publicPartners', 'users', 'activityLogs', 'operationReceipts', 'uploadSessions', 'system'],
    });
  });
  const base = {
    brand: 'Merek Contoh', model: 'JT-DEMO', category: 'split', capacityPk: '1', condition: 'new',
    price: 3500000, description: 'Data contoh untuk pengujian Firebase Emulator, bukan katalog produksi.',
    specifications: {Daya: '650 W', Refrigeran: 'R32'}, images: [], mainImagePath: '',
  };
  const ac = await sharp(Buffer.from('<svg width="640" height="360" xmlns="http://www.w3.org/2000/svg"><rect width="640" height="360" fill="#e8f5f6"/><rect x="110" y="95" width="420" height="130" rx="18" fill="white" stroke="#006a72" stroke-width="5"/><path d="M140 185H500M185 245v30m135-30v30m135-30v30" stroke="#006a72" stroke-width="7"/><circle cx="485" cy="130" r="7" fill="#24a6aa"/></svg>')).png().toBuffer();
  const logo = await sharp(Buffer.from('<svg width="420" height="220" xmlns="http://www.w3.org/2000/svg"><rect x="45" y="30" width="330" height="160" rx="30" fill="#006a72"/><path d="M100 155V70h25l40 50 40-50h25v85h-27v-43l-38 45-38-45v43z" fill="white"/><circle cx="297" cy="110" r="37" fill="#9de7dd"/></svg>')).png().toBuffer();
  await seedEntity(owner, 'active', 'product', async () => {
    const image = await stageImage(owner, 'product', ids.active, requests.productUpload, ac);
    return {...base, name: 'Contoh AC Split 1 PK', status: 'active', images: [image], mainImagePath: image.storagePath};
  });
  await seedEntity(owner, 'draft', 'product', async () => ({...base, name: 'Contoh AC Cassette Draft', category: 'cassette', capacityPk: '2', status: 'draft'}));
  await seedEntity(owner, 'inactive', 'product', async () => ({...base, name: 'Contoh AC Standing Nonaktif', category: 'standing', condition: 'used', price: null, status: 'inactive'}));
  await seedEntity(owner, 'partner', 'partner', async () => ({name: 'Contoh Perusahaan Mitra', logo: await stageImage(owner, 'partner', ids.partner, requests.partnerUpload, logo), altText: 'Logo perusahaan mitra contoh untuk emulator', website: '', displayOrder: 0, active: true}));
  const admin = await login(accounts[1]);
  const adminSession = await service.getSession({auth: admin, data: {}});
  assert.equal(adminSession.role, 'ADMIN');
  // Verify all business records exist without requiring their original values,
  // because rerunning seed must preserve edits made through the application.
  for (const [key, id] of Object.entries(ids)) assert.equal((await db.doc(`${key === 'partner' ? 'partners' : 'products'}/${id}`).get()).exists, true);
  assert.equal((await db.doc('system/schema').get()).get('projectId'), DEMO_PROJECT_ID);
  console.log(`\nFirebase Emulator ${DEMO_PROJECT_ID}: seed dan verifikasi berhasil.`);
  console.log(status.join('\n'));
  console.log('\nAkun contoh hanya untuk EMULATOR LOKAL:');
  for (const account of accounts) console.log(`${account.role}: ${account.email} / ${account.password}${preservedAccounts.has(account.uid) ? ' (password lama dipertahankan jika pernah diganti)' : ''}`);
  console.log('Tidak ada database atau akun pada project produksi yang diubah.');
}

try { await seed(); }
catch (error) {
  console.error(`Seed emulator gagal: ${error.message}`);
  console.error('Pastikan emulator auth,firestore,storage berjalan untuk demo-jayaguna. Data yang sudah ada tidak dihapus.');
  process.exitCode = 1;
} finally {
  await db.terminate();
  await deleteApp(app);
}
