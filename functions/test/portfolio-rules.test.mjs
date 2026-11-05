import {before, after, beforeEach, test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {initializeTestEnvironment, assertFails, assertSucceeds} from '@firebase/rules-unit-testing';
import {doc, getDoc, getDocs, setDoc, updateDoc, deleteDoc, collection, query, where, limit, writeBatch, serverTimestamp, Timestamp} from 'firebase/firestore';

if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error('Run only inside Firestore Emulator.');
const projectId = process.env.GCLOUD_PROJECT || 'demo-portfolio';
if (!projectId.startsWith('demo-')) throw new Error('Portfolio rules tests require a demo project.');
const ADMIN = 'portfolio-admin', OWNER = 'portfolio-owner', OTHER = 'portfolio-other';
const NOW = Math.floor(Date.now() / 1000), HASH = 'a'.repeat(64);
const FOLDERS = {residential: '1kOqO3wgNmUCujrhb1tqY3kB_kG7jTEjx', corporate: '10Z9US8nxqTV5AkJrRrEURRsWNhOpvHvK'};
const TYPE = {residential: 'portfolioResidential', corporate: 'portfolioCorporate'};
const DEVICE = {installationId: randomUUID(), deviceName: 'Portfolio test', brand: 'Test', model: 'Model', androidVersion: '16', appVersion: '1.0.5'};
const FIELDS = ['id', 'category', 'image', 'archived', 'version'];
let env;
const db = (uid = ADMIN, claims = {}) => env.authenticatedContext(uid, {role: uid === OWNER ? 'OWNER' : 'ADMIN', email: uid + '@example.com', auth_time: NOW, ...claims}).firestore();
const seed = (path, value) => env.withSecurityRulesDisabled(ctx => setDoc(doc(ctx.firestore(), path), value));
async function read(path) { let result; await env.withSecurityRulesDisabled(async ctx => {result = await getDoc(doc(ctx.firestore(), path));}); return result; }
const snapshot = e => Object.fromEntries(FIELDS.map(k => [k, e[k]]));
const media = a => ({storagePath: 'drive/' + a.id, url: '', provider: 'drive', driveFileId: a.driveFileId, fileName: a.fileName, mimeType: a.mimeType, sizeBytes: a.sizeBytes});
function reservation(entityId = randomUUID(), category = 'residential', uid = ADMIN) {
  const id = randomUUID();
  return {id, driveFileId: 'portfolio_file_' + id.replaceAll('-', ''), folder: FOLDERS[category], entityType: TYPE[category], entityId, createdBy: uid, device: DEVICE, requestId: randomUUID(), status: 'reserved', expectedBytes: 68, expectedMime: 'image/png', originalSha256: HASH, originalFileName: 'portfolio.png', createdAt: serverTimestamp(), expiresAt: Timestamp.fromMillis(Date.now() + 86400000), source: 'android'};
}
async function reserve(a, client = db()) {
  const batch = writeBatch(client);
  batch.set(doc(client, 'mediaAssets', a.id), a);
  batch.set(doc(client, 'driveUploadRequests', a.createdBy + '_' + a.requestId), {hash: HASH, assetId: a.id, createdAt: serverTimestamp(), createdBy: a.createdBy, source: 'android'});
  return batch.commit();
}
async function uploaded(id = randomUUID(), category = 'residential', uid = ADMIN) {
  const a = {...reservation(id, category, uid), createdAt: Timestamp.now(), status: 'uploaded', fileName: 'portfolio.png', mimeType: 'image/png', sizeBytes: 68, sha256: HASH, uploadedAt: Timestamp.now()};
  await seed('mediaAssets/' + a.id, a); return a;
}
function audit(a, action, requestId = a.requestId) {
  return {userId: ADMIN, userEmail: ADMIN + '@example.com', actorName: ADMIN, actorRole: 'ADMIN', action, targetId: a.entityId, entityType: a.entityType, driveFileId: a.driveFileId, mediaId: a.id, deviceId: DEVICE.installationId, device: DEVICE, status: 'SUCCESS', timestamp: serverTimestamp(), requestId};
}
function write({client = db(), uid = ADMIN, id = randomUUID(), category = 'residential', image, old = null, archived = false, requestId = randomUUID(), omitLog = false, omitReceipt = false, omitProjection = false, patch = {}, mutateLog, mutateReceipt, mutateProjection} = {}) {
  const type = TYPE[category];
  const next = {id, category, image: image ?? null, archived, version: (old?.version || 0) + 1, createdBy: old?.createdBy || uid, createdAt: old?.createdAt || serverTimestamp(), updatedBy: uid, updatedAt: serverTimestamp(), lastRequestId: requestId, ...patch};
  const before = old ? snapshot(old) : {}, after = snapshot(next), changedFields = {};
  for (const key of FIELDS) if (JSON.stringify(before[key]) !== JSON.stringify(after[key])) changedFields[key] = {before: before[key] ?? null, after: after[key] ?? null};
  const log = {actorUid: uid, actorName: uid, actorEmail: uid + '@example.com', actorRole: uid === OWNER ? 'OWNER' : 'ADMIN', action: !old ? 'add' : archived ? 'archive' : 'edit', entityType: type, entityId: id, before, after, changedFields, timestamp: serverTimestamp(), requestId, device: DEVICE, ...DEVICE};
  const receipt = {hash: HASH, result: {id, version: next.version}, entityType: type, entityId: id, operation: archived ? 'archivePortfolio' : 'savePortfolio', createdAt: serverTimestamp()};
  const projection = {id, category, image: next.image, archived: false, createdAt: next.createdAt, updatedAt: next.updatedAt, mediaPaths: next.image ? [next.image.storagePath] : []};
  mutateLog?.(log); mutateReceipt?.(receipt); mutateProjection?.(projection);
  const batch = writeBatch(client);
  batch.set(doc(client, 'portfolioImages', id), next);
  if (!omitLog) batch.set(doc(client, 'activityLogs', uid + '_' + requestId), log);
  if (!omitReceipt) batch.set(doc(client, 'operationReceipts', uid + '_' + requestId), receipt);
  if (!omitProjection) { if (archived) batch.delete(doc(client, 'publicPortfolioImages', id)); else batch.set(doc(client, 'publicPortfolioImages', id), projection); }
  return {commit: () => batch.commit(), client, id, requestId, next};
}
async function create(category = 'residential') {
  const id = randomUUID(), a = await uploaded(id, category), first = write({id, category, image: media(a)});
  await assertSucceeds(first.commit());
  await assertSucceeds(updateDoc(doc(first.client, 'mediaAssets', a.id), {status: 'attached', attachedAt: serverTimestamp(), lastRequestId: first.requestId}));
  return {a, first, old: (await read('portfolioImages/' + id)).data()};
}
async function detach(a, requestId, action) {
  const client = db(), batch = writeBatch(client);
  batch.update(doc(client, 'mediaAssets', a.id), {status: 'detached', detachedAt: serverTimestamp(), deleteAfter: Timestamp.fromMillis(Date.now() + 86400000), lastRequestId: requestId});
  batch.set(doc(client, 'audit_logs', ADMIN + '_' + requestId + '_remove_' + a.id), audit(a, action, requestId));
  return batch.commit();
}
before(async () => {env = await initializeTestEnvironment({projectId, firestore: {rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8')}});});
beforeEach(async () => { await env.clearFirestore(); for (const [uid, role] of [[ADMIN, 'ADMIN'], [OWNER, 'OWNER'], [OTHER, 'ADMIN']]) await seed('users/' + uid, {uid, name: uid, email: uid + '@example.com', role, active: true, revokedBeforeSeconds: 0}); });
after(async () => {await env?.cleanup();});

test('both portfolio categories reserve uploads only in their exact existing Drive folder', async () => {
  for (const category of Object.keys(FOLDERS)) {
    await assertSucceeds(reserve(reservation(randomUUID(), category)));
    await assertFails(reserve({...reservation(randomUUID(), category), folder: FOLDERS[category === 'residential' ? 'corporate' : 'residential']}));
  }
  await assertFails(reserve({...reservation(), entityType: 'portfolioOther'}));
});

test('portfolio upload completion requires matching immutable portfolio audit and checksum', async () => {
  const a = reservation(); await assertSucceeds(reserve(a));
  const patch = {status: 'uploaded', fileName: 'portfolio.png', mimeType: 'image/png', sizeBytes: 68, sha256: HASH, uploadedAt: serverTimestamp()};
  await assertFails(updateDoc(doc(db(), 'mediaAssets', a.id), patch));
  for (const [action, changes] of [['UPLOAD_PRODUCT_IMAGE', {}], ['UPLOAD_PORTFOLIO_IMAGE', {sha256: 'b'.repeat(64)}]]) {
    const client = db(), batch = writeBatch(client); batch.update(doc(client, 'mediaAssets', a.id), {...patch, ...changes}); batch.set(doc(client, 'audit_logs', 'upload_' + a.id), audit(a, action)); await assertFails(batch.commit());
  }
  const client = db(), batch = writeBatch(client); batch.update(doc(client, 'mediaAssets', a.id), patch); batch.set(doc(client, 'audit_logs', 'upload_' + a.id), audit(a, 'UPLOAD_PORTFOLIO_IMAGE')); await assertSucceeds(batch.commit());
  await assertFails(updateDoc(doc(client, 'audit_logs', 'upload_' + a.id), {action: 'forged'}));
});

for (const category of Object.keys(FOLDERS)) test(category + ' image saves atomically with exact public metadata and server timestamps', async () => {
  const {a, first, old} = await create(category);
  assert.equal(old.category, category); assert.equal(old.version, 1); assert.equal(old.createdBy, ADMIN); assert.ok(old.updatedAt instanceof Timestamp);
  const publicDoc = (await read('publicPortfolioImages/' + first.id)).data(); assert.equal(publicDoc.createdBy, undefined); assert.equal(publicDoc.image.driveFileId, a.driveFileId); assert.deepEqual(publicDoc.mediaPaths, ['drive/' + a.id]);
  await assertSucceeds(getDoc(doc(env.unauthenticatedContext().firestore(), 'publicPortfolioImages', first.id)));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'portfolioImages', first.id)));
});

test('portfolio gallery queries require limits and public archived filter', async () => {
  await create();
  await assertSucceeds(getDocs(query(collection(db(), 'portfolioImages'), where('category', '==', 'residential'), where('archived', '==', false), limit(25))));
  const publicDb = env.unauthenticatedContext().firestore();
  await assertSucceeds(getDocs(query(collection(publicDb, 'publicPortfolioImages'), where('archived', '==', false), limit(25))));
  await assertFails(getDocs(query(collection(publicDb, 'publicPortfolioImages'), limit(25))));
  await assertFails(getDocs(query(collection(publicDb, 'publicPortfolioImages'), where('archived', '==', false))));
});

test('missing audit, receipt or projection and forged portfolio snapshots are denied', async () => {
  const id = randomUUID(), a = await uploaded(id);
  for (const options of [{omitLog: true}, {omitReceipt: true}, {omitProjection: true}, {mutateLog: l => {l.before = {image: 'forged'};}}, {mutateLog: l => {l.actorUid = OWNER;}}, {mutateLog: l => {l.after.category = 'corporate';}}, {mutateReceipt: r => {r.operation = 'saveProduct';}}, {mutateProjection: p => {p.createdBy = ADMIN;}}]) await assertFails(write({id, image: media(a), ...options}).commit());
});

test('invalid categories, foreign images, nonmanaged URLs and injected data cannot enter portfolio', async () => {
  const id = randomUUID(), a = await uploaded(id), foreign = await uploaded(id, 'residential', OTHER), corporate = await uploaded(id, 'corporate');
  for (const image of [null, media(foreign), media(corporate), {...media(a), driveFileId: 'forged_drive_file'}, {storagePath: 'https://example.com/image.png'}]) await assertFails(write({id, image}).commit());
  for (const patch of [{category: 'other'}, {title: 'unlisted field'}, {createdBy: OWNER}, {createdAt: Timestamp.fromMillis(1)}, {version: 0}]) await assertFails(write({id, image: media(a), patch}).commit());
  await assertFails(write({id, image: media(a), archived: true}).commit());
});

test('portfolio writes deny unauthenticated, inactive, revoked and mismatched role users', async () => {
  const id = randomUUID(), a = await uploaded(id);
  for (const client of [env.unauthenticatedContext().firestore(), db(ADMIN, {role: 'OWNER'}), db(ADMIN, {auth_time: 0}), db('missing-profile')]) await assertFails(write({client, id, image: media(a)}).commit());
  await seed('users/' + ADMIN, {name: ADMIN, role: 'ADMIN', active: false}); await assertFails(write({id, image: media(a)}).commit());
  await seed('users/' + ADMIN, {name: ADMIN, role: 'ADMIN', active: true, revokedBeforeSeconds: NOW}); await assertFails(write({id, image: media(a)}).commit());
});

test('category cannot move on edit and stale versions or reused requests cannot overwrite portfolio', async () => {
  const {a, first, old} = await create(), wrongCategory = await uploaded(first.id, 'corporate');
  await assertFails(write({id: first.id, category: 'corporate', old, image: media(wrongCategory)}).commit());
  await assertFails(write({id: first.id, old, image: media(a), patch: {version: 1}}).commit());
  await assertFails(write({id: first.id, old, image: media(a), requestId: first.requestId}).commit());
  await assertSucceeds(write({id: first.id, old, image: media(a)}).commit());
  await assertFails(write({id: first.id, old, image: media(a)}).commit());
});

test('replacement keeps old reference safe until commit and requires exact replacement audit', async () => {
  const {a, first, old} = await create(), replacement = await uploaded(first.id);
  await assertFails(updateDoc(doc(db(), 'mediaAssets', a.id), {status: 'deleting', deleteRequestId: randomUUID(), deleteDevice: DEVICE, deleteActorUid: ADMIN, deleteStartedAt: serverTimestamp()}));
  const second = write({id: first.id, old, image: media(replacement)}); await assertSucceeds(second.commit());
  await assertFails(detach(a, second.requestId, 'REMOVE_PORTFOLIO_IMAGE'));
  await assertSucceeds(detach(a, second.requestId, 'REPLACE_PORTFOLIO_IMAGE'));
  assert.equal((await read('publicPortfolioImages/' + first.id)).data().image.driveFileId, replacement.driveFileId);
});

test('archive clears image and public projection before logged Drive deletion is allowed', async () => {
  const {a, first, old} = await create('corporate');
  await assertFails(write({id: first.id, category: 'corporate', old, image: media(a), archived: true}).commit());
  const removal = write({id: first.id, category: 'corporate', old, archived: true}); await assertSucceeds(removal.commit());
  const saved = (await read('portfolioImages/' + first.id)).data(); assert.equal(saved.image, null); assert.equal(saved.archived, true); assert.equal((await read('publicPortfolioImages/' + first.id)).exists(), false);
  await assertFails(detach(a, removal.requestId, 'REPLACE_PORTFOLIO_IMAGE'));
  await assertSucceeds(detach(a, removal.requestId, 'REMOVE_PORTFOLIO_IMAGE'));
  const requestId = randomUUID(), client = db();
  await assertSucceeds(updateDoc(doc(client, 'mediaAssets', a.id), {status: 'deleting', deleteRequestId: requestId, deleteDevice: DEVICE, deleteActorUid: ADMIN, deleteStartedAt: serverTimestamp()}));
  await assertFails(updateDoc(doc(client, 'mediaAssets', a.id), {status: 'deleted', deletedAt: serverTimestamp()}));
  const batch = writeBatch(client); batch.update(doc(client, 'mediaAssets', a.id), {status: 'deleted', deletedAt: serverTimestamp()}); batch.set(doc(client, 'audit_logs', 'delete_' + a.id), audit(a, 'DELETE_PORTFOLIO_IMAGE', requestId)); await assertSucceeds(batch.commit());
  await assertFails(write({id: first.id, category: 'corporate', old: saved, image: media(a)}).commit());
  await assertFails(deleteDoc(doc(client, 'portfolioImages', first.id)));
});
