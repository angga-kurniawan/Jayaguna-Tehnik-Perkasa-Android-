const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const OWNER = 'S28l85ry6EhyEzg5F4c6oweNyJC3';
const ENTITY = '11111111-1111-4111-8111-111111111111';
const REQUEST = '22222222-2222-4222-8222-222222222222';
const SECOND = '33333333-3333-4333-8333-333333333333';
const DEVICE = { installationId: '44444444-4444-4444-8444-444444444444', deviceName: 'HP Kantor', brand: 'Example', model: 'Example', androidVersion: '15', appVersion: '1.0.2' };
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64');
const SOURCE = fs.readFileSync(path.join(__dirname, '..', 'Code.gs'), 'utf8');
const ROOT = 'projects/jayagunatehnikperkasa/databases/(default)/documents/';
const copy = value => JSON.parse(JSON.stringify(value));
const response = (code, body) => ({ getResponseCode: () => code, getContentText: () => typeof body === 'string' ? body : JSON.stringify(body), getContent: () => Array.from(Buffer.isBuffer(body) ? body : Buffer.from(typeof body === 'string' ? body : JSON.stringify(body))) });

function harness() {
  const store = new Map(), files = new Map(), tokens = new Map(), requests = [], commits = [], cache = new Map();
  const state = { failCommit: false, lostCommit: false, lostUpload: false, denyCaller: false, uploads: [], ownerEmail: 'mtahakurniawan@gmail.com' };
  let ticks = 0, ids = 0;
  const stamp = () => new Date(Date.UTC(2026, 0, 1, 0, 0, 0, ++ticks)).toISOString();
  const blob = input => { const value = Buffer.isBuffer(input) ? input : Array.isArray(input) ? Buffer.from(input) : Buffer.from(String(input)); return { getBytes: () => Array.from(value), getDataAsString: () => value.toString('utf8') }; };
  const ctx = vm.createContext({
    Utilities: { newBlob: blob, base64DecodeWebSafe: value => Array.from(Buffer.from(value, 'base64url')), base64Decode: value => Array.from(Buffer.from(value, 'base64')), base64Encode: value => Buffer.from(value).toString('base64'), getUuid: () => `a0000000-0000-4000-8000-${String(++ids).padStart(12, '0')}`, sleep() {}, DigestAlgorithm: { SHA_256: 'sha256', MD5: 'md5' }, computeDigest: (algorithm, bytes) => Array.from(crypto.createHash(algorithm).update(Buffer.from(bytes)).digest()) },
    ContentService: { MimeType: { JSON: 'application/json' }, createTextOutput: body => ({ body, setMimeType() { return this; } }) },
    Session: { getEffectiveUser: () => ({ getEmail: () => state.ownerEmail }) },
    ScriptApp: { getOAuthToken: () => 'OWNER_OAUTH_MUST_NOT_LEAK' },
    LockService: { getScriptLock: () => ({ tryLock: () => true, releaseLock() {} }) },
    CacheService: { getScriptCache: () => ({ get: key => cache.get(key), put: (key, value) => cache.set(key, value) }) },
    UrlFetchApp: { fetch: (url, options) => fetch(url, options) },
  });
  vm.runInContext(SOURCE, ctx, { filename: 'Code.gs' });
  function fields(data) { const result = {}; for (const [key, value] of Object.entries(data)) result[key] = ctx.encode_(value); return result; }
  function put(path, data) { store.set(path, { name: ROOT + path, fields: fields(data), createTime: stamp(), updateTime: stamp() }); }
  function data(path) { const doc = store.get(path); return doc ? ctx.decodeFields_(doc.fields) : null; }
  function token(overrides = {}) {
    const now = Math.floor(Date.now() / 1000);
    const claims = { sub: OWNER, aud: 'jayagunatehnikperkasa', iss: 'https://securetoken.google.com/jayagunatehnikperkasa', exp: now + 3600, auth_time: now - 100, role: 'OWNER', email: 'mtahakurniawan@gmail.com', ...overrides };
    const value = Buffer.from('{"alg":"RS256"}').toString('base64url') + '.' + Buffer.from(JSON.stringify(claims)).toString('base64url') + '.FAKE_SIGNATURE_FOR_OFFLINE_TEST_ONLY';
    tokens.set(value, claims); return value;
  }
  function rulesPermit(token, path) {
    const claim = tokens.get(token), profile = claim && data('users/' + claim.sub);
    return !state.denyCaller && claim && profile && profile.active === true && profile.role === claim.role && ['OWNER', 'ADMIN'].includes(claim.role) && (!profile.revokedBeforeSeconds || claim.auth_time > profile.revokedBeforeSeconds) && path === 'users/' + claim.sub;
  }
  function commit(writes) {
    commits.push(copy(writes));
    if (state.failCommit) return response(503, {});
    for (const write of writes) {
      const name = write.update?.name || write.delete, current = store.get(name.slice(ROOT.length)), pre = write.currentDocument;
      if (pre && ('exists' in pre ? pre.exists !== Boolean(current) : pre.updateTime !== current?.updateTime)) return response(409, {});
    }
    const snapshot = new Map(store);
    for (const write of writes) {
      const name = write.update?.name || write.delete, key = name.slice(ROOT.length), current = snapshot.get(key);
      if (write.delete) snapshot.delete(key);
      else {
        const nextFields = write.updateMask ? { ...current.fields } : {};
        Object.assign(nextFields, copy(write.update.fields));
        for (const transform of write.updateTransforms || []) { assert.equal(transform.setToServerValue, 'REQUEST_TIME'); const field = transform.fieldPath.replace(/^`|`$/g, ''); nextFields[field] = { timestampValue: stamp() }; }
        snapshot.set(key, { name, fields: nextFields, createTime: current?.createTime || stamp(), updateTime: stamp() });
      }
    }
    store.clear(); for (const [key, value] of snapshot) store.set(key, value);
    if (state.lostCommit) { state.lostCommit = false; throw new Error('Commit reply lost after apply'); }
    return response(200, { commitTime: stamp() });
  }
  function fetch(url, options) {
    requests.push({ url, options: { ...options } });
    const bearer = options.headers.Authorization.slice(7);
    if (url.startsWith('https://firestore.googleapis.com/v1/')) {
      const resource = url.slice('https://firestore.googleapis.com/v1/'.length);
      if (resource.endsWith(':commit')) { assert.equal(bearer, 'OWNER_OAUTH_MUST_NOT_LEAK'); return commit(JSON.parse(options.payload).writes); }
      const key = resource.slice(ROOT.length);
      if (bearer !== 'OWNER_OAUTH_MUST_NOT_LEAK' && !rulesPermit(bearer, key)) return response(403, { error: 'FORGED_TOKEN_PRIVATE_ERROR' });
      return store.has(key) ? response(200, store.get(key)) : response(404, {});
    }
    assert.equal(bearer, 'OWNER_OAUTH_MUST_NOT_LEAK');
    if (url.includes('/generateIds?')) return response(200, { ids: ['drive_file_' + (++ids)] });
    if (url.startsWith('https://www.googleapis.com/upload/')) {
      const body = Buffer.from(options.payload), text = body.toString('latin1'), first = text.indexOf('\r\n\r\n') + 4, end = text.indexOf('\r\n--', first), metadata = JSON.parse(text.slice(first, end));
      state.uploads.push(metadata.id);
      if (files.has(metadata.id)) return response(409, {});
      const binaryStart = text.indexOf('\r\n\r\n', end + 4) + 4, binaryEnd = text.lastIndexOf('\r\n--'), raw = body.subarray(binaryStart, binaryEnd);
      files.set(metadata.id, { ...metadata, raw, size: String(raw.length), md5Checksum: crypto.createHash('md5').update(raw).digest('hex') });
      if (state.lostUpload) { state.lostUpload = false; throw new Error('Drive reply lost after file creation'); }
      return response(200, files.get(metadata.id));
    }
    const match = /\/files\/([A-Za-z0-9_-]+)\?/.exec(url);
    if (match) {
      const id = match[1];
      if (id === '1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd' || id === '1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6') return response(200, { id, mimeType: 'application/vnd.google-apps.folder', owners: [{ emailAddress: 'mtahakurniawan@gmail.com' }], capabilities: { canAddChildren: true }, permissions: [{ type: 'user' }] });
      const file = files.get(id);
      if (!file) return response(404, {});
      if (options.method === 'patch') { Object.assign(file, JSON.parse(options.payload)); return response(200, file); }
      return response(200, url.includes('alt=media') ? file.raw : file);
    }
    throw new Error('Unexpected URL');
  }
  put('users/' + OWNER, { active: true, role: 'OWNER', name: 'Owner', email: 'mtahakurniawan@gmail.com', revokedBeforeSeconds: 0 });
  const ownerToken = token();
  function call(action, payload = {}, idToken = ownerToken) { const body = JSON.stringify({ idToken, action, payload }); return JSON.parse(ctx.doPost({ contentLength: Buffer.byteLength(body), postData: { contents: body } }).body); }
  function begin(requestId = REQUEST, bytes = PNG) { return call('beginUpload', { requestId, device: DEVICE, entityType: 'product', entityId: ENTITY, contentType: 'image/png', bytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex'), fileName: 'ac.png' }); }
  function upload(id, bytes = PNG) { return call('uploadMedia', { mediaId: id, contentType: 'image/png', base64: bytes.toString('base64') }); }
  function product(images = [], status = 'draft') { return { name: 'Daikin 1 PK', brand: 'Daikin', model: '', category: 'split', capacityPk: '1', condition: 'new', price: 4500000, description: '', specifications: {}, images, mainImagePath: images[0]?.storagePath || '', status }; }
  function save(value, requestId = SECOND, expectedVersion = 0) { return call('saveProduct', { requestId, device: DEVICE, id: ENTITY, expectedVersion, data: value }); }
  return { ctx, state, store, files, requests, commits, put, data, token, call, begin, upload, product, save, ownerToken };
}

test('health exposes no private state; missing and forged tokens never reach Drive', () => {
  const h = harness();
  assert.deepEqual(JSON.parse(h.ctx.doGet().body), { service: 'jayaguna-google-bridge', projectId: 'jayagunatehnikperkasa', version: 1 });
  assert.equal(h.call('readMedia', { mediaId: ENTITY }, '').error.code, 'unauthenticated');
  h.state.denyCaller = true;
  const result = h.call('getDriveStatus');
  assert.equal(result.error.code, 'permission-denied');
  assert.ok(!JSON.stringify(result).includes('FORGED_TOKEN'));
  assert.ok(h.requests.every(r => r.url.startsWith('https://firestore.googleapis.com/')));
  assert.equal(h.requests.at(-1).options.headers.Authorization, 'Bearer ' + h.ownerToken);
});

test('inactive role mismatch and revoked sessions are rejected before privileged operations', () => {
  for (const patch of [{ active: false }, { role: 'ADMIN' }, { revokedBeforeSeconds: Math.floor(Date.now() / 1000) }]) {
    const h = harness(); h.put('users/' + OWNER, { ...h.data('users/' + OWNER), ...patch });
    assert.equal(h.call('getDriveStatus').error.code, 'permission-denied');
    assert.equal(h.commits.length, 0);
  }
});

test('UID path injection and foreign-project tokens are denied', () => {
  const h = harness();
  assert.equal(h.call('getDriveStatus', {}, h.token({ sub: '../backendSecrets/googleDrive' })).error.code, 'unauthenticated');
  assert.equal(h.call('getDriveStatus', {}, h.token({ aud: 'foreign-project' })).error.code, 'permission-denied');
  assert.equal(h.commits.length, 0);
});

test('drive status checks actual folders and rejects wrong script owner', () => {
  const h = harness();
  assert.deepEqual(h.call('getDriveStatus').result, { connected: true, accountEmail: 'mtahakurniawan@gmail.com' });
  assert.deepEqual(h.call('startDriveOAuth').result, { connected: true, accountEmail: 'mtahakurniawan@gmail.com' });
  h.state.ownerEmail = 'other@example.com';
  assert.equal(h.call('getDriveStatus').error.code, 'failed-precondition');
});

test('schema injection invalid image and oversized request are rejected', () => {
  const h = harness();
  assert.equal(h.save({ ...h.product(), createdBy: 'attacker' }).error.code, 'invalid-argument');
  assert.equal(h.call('uploadMedia', { mediaId: ENTITY, contentType: 'image/png', base64: Buffer.from('not a png').toString('base64') }).error.code, 'invalid-argument');
  const body = JSON.stringify({ idToken: h.ownerToken, action: 'getDriveStatus' });
  assert.equal(JSON.parse(h.ctx.doPost({ contentLength: 7200001, postData: { contents: body } }).body).error.code, 'invalid-argument');
  assert.equal(h.commits.length, 0);
});

test('upload reservation is idempotent and rejects a changed request', () => {
  const h = harness(), first = h.begin(), second = h.begin();
  assert.deepEqual(first, second);
  assert.equal(h.store.size, 3);
  assert.equal(h.begin(REQUEST, Buffer.concat([PNG, Buffer.from([0])])).error.code, 'already-exists');
  const asset = h.data('mediaAssets/' + first.result.storagePath.slice(6));
  assert.equal(asset.status, 'reserved');
  assert.equal(asset.folder, '1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd');
  assert.equal(asset.createdAt.constructor.name, 'FireTimestamp');
  assert.equal(asset.expiresAt.constructor.name, 'FireTimestamp');
  assert.ok(h.commits[0][0].currentDocument.updateTime);
});

test('lost Drive response retries reserved ID without a duplicate file or audit', () => {
  const h = harness(), id = h.begin().result.storagePath.slice(6);
  h.state.lostUpload = true;
  const first = h.upload(id);
  assert.ok(first.result?.media, JSON.stringify(first));
  const again = h.upload(id);
  assert.deepEqual(again.result.media, first.result.media);
  assert.equal(h.files.size, 1);
  assert.equal(new Set(h.state.uploads).size, 1);
  assert.equal(h.state.uploads.length, 2);
  assert.equal([...h.store.keys()].filter(key => key.startsWith('audit_logs/')).length, 1);
  assert.equal(h.data('mediaAssets/' + id).status, 'uploaded');
  assert.equal(h.data('audit_logs/upload_' + id).timestamp.constructor.name, 'FireTimestamp');
});

test('upload then failed database commit leaves durable reservation and safe retry', () => {
  const h = harness(), id = h.begin().result.storagePath.slice(6);
  h.state.failCommit = true;
  assert.equal(h.upload(id).error.code, 'unavailable');
  assert.equal(h.files.size, 1);
  assert.equal(h.data('mediaAssets/' + id).status, 'reserved');
  h.state.failCommit = false;
  assert.ok(h.upload(id).result.media);
  assert.equal(h.files.size, 1);
  assert.equal(h.data('mediaAssets/' + id).status, 'uploaded');
});

test('save is atomic, protects creator/version, publishes and returns receipt after lost response', () => {
  const h = harness(), id = h.begin().result.storagePath.slice(6), image = h.upload(id).result.media;
  h.state.lostCommit = true;
  assert.equal(h.save(h.product([image], 'active')).error.code, 'unavailable');
  const stored = h.data('products/' + ENTITY);
  assert.equal(stored.version, 1);
  assert.equal(stored.createdBy, OWNER);
  assert.equal(stored.createdAt.constructor.name, 'FireTimestamp');
  assert.equal(stored.updatedAt.constructor.name, 'FireTimestamp');
  assert.equal(h.data('publicProducts/' + ENTITY).status, 'active');
  assert.equal(h.data('mediaAssets/' + id).status, 'attached');
  assert.deepEqual(h.save(h.product([image], 'active')).result, { id: ENTITY, version: 1 });
  assert.equal([...h.store.keys()].filter(key => key.startsWith('activityLogs/')).length, 1);
  assert.equal(h.save(h.product([image], 'active'), REQUEST, 0).error.code, 'aborted');
  assert.equal(h.data('products/' + ENTITY).version, 1);
});

test('failed replacement retains previous product and image, successful replacement detaches old safely', () => {
  const h = harness(), oldId = h.begin().result.storagePath.slice(6), oldImage = h.upload(oldId).result.media;
  assert.ok(h.save(h.product([oldImage], 'active')).result);
  const newId = h.begin('55555555-5555-4555-8555-555555555555').result.storagePath.slice(6), newImage = h.upload(newId).result.media;
  h.state.failCommit = true;
  assert.equal(h.save(h.product([newImage], 'active'), '66666666-6666-4666-8666-666666666666', 1).error.code, 'unavailable');
  assert.equal(h.data('products/' + ENTITY).images[0].storagePath, oldImage.storagePath);
  assert.equal(h.data('mediaAssets/' + oldId).status, 'attached');
  assert.ok(!h.files.get(oldImage.driveFileId).trashed);
  h.state.failCommit = false;
  assert.ok(h.save(h.product([newImage], 'active'), '66666666-6666-4666-8666-666666666666', 1).result);
  assert.equal(h.data('mediaAssets/' + oldId).status, 'detached');
  assert.equal(h.data('mediaAssets/' + newId).status, 'attached');
  assert.ok(!h.files.get(oldImage.driveFileId).trashed);
  assert.equal(h.data('products/' + ENTITY).createdAt.constructor.name, 'FireTimestamp');
});

test('media read authenticates, preserves hash and supports invisible legacy appProperties safely', () => {
  const h = harness(), id = h.begin().result.storagePath.slice(6), image = h.upload(id).result.media;
  assert.equal(h.call('readMedia', { mediaId: id }).result.base64, PNG.toString('base64'));
  delete h.files.get(image.driveFileId).appProperties;
  assert.ok(h.call('mediaMetadata', { mediaId: id }).result.media);
  h.files.get(image.driveFileId).raw = Buffer.concat([PNG.subarray(0, -1), Buffer.from([0])]);
  assert.equal(h.call('readMedia', { mediaId: id }).error.code, 'failed-precondition');
  assert.equal(h.call('mediaMetadata', { mediaId: id }).error.code, 'failed-precondition');
  assert.equal(h.call('readMedia', { mediaId: id }, '').error.code, 'unauthenticated');
});

test('deletion refuses attached media and removes detached media only after hash verification', () => {
  const h = harness(), id = h.begin().result.storagePath.slice(6), image = h.upload(id).result.media;
  h.save(h.product([image], 'active'));
  const payload = { requestId: REQUEST, device: DEVICE, mediaId: id };
  assert.equal(h.call('deleteMedia', payload).error.code, 'failed-precondition');
  assert.ok(h.save(h.product([], 'draft'), '77777777-7777-4777-8777-777777777777', 1).result);
  delete h.files.get(image.driveFileId).appProperties;
  assert.deepEqual(h.call('deleteMedia', payload).result, { deleted: true });
  assert.equal(h.files.get(image.driveFileId).trashed, true);
  assert.equal(h.data('mediaAssets/' + id).status, 'deleted');
  assert.equal(h.data('audit_logs/delete_' + id).userId, OWNER);
});

test('profile changes during a side effect abort metadata commit instead of overriding revocation', () => {
  const h = harness(), id = h.begin().result.storagePath.slice(6);
  const actor = h.ctx.authenticate_(h.ownerToken);
  h.put('users/' + OWNER, { ...h.data('users/' + OWNER), active: false });
  assert.throws(() => h.ctx.uploadMedia_(actor, { mediaId: id, contentType: 'image/png', base64: PNG.toString('base64') }), error => error.bridgeCode === 'aborted');
  assert.equal(h.data('users/' + OWNER).active, false);
  assert.equal(h.data('mediaAssets/' + id).status, 'reserved');
});

test('new admin creation is explicitly unsupported rather than silently granting privileges', () => {
  const h = harness();
  assert.equal(h.call('manageAdmin', { operation: 'create', email: 'new@example.com' }).error.code, 'unimplemented');
  assert.equal(h.commits.length, 0);
});

test('partner logo upload uses its designated folder and save/archive preserves timestamp types', () => {
  const h = harness();
  const slot = h.call('beginUpload', { requestId: REQUEST, device: DEVICE, entityType: 'partner', entityId: ENTITY, contentType: 'image/png', bytes: PNG.length, sha256: crypto.createHash('sha256').update(PNG).digest('hex'), fileName: 'partner.png' });
  const id = slot.result.storagePath.slice(6), logo = h.upload(id).result.media;
  assert.equal(h.data('mediaAssets/' + id).folder, '1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6');
  const partner = { name: 'PT Contoh', logo, altText: 'PT Contoh', website: 'https://example.com/', displayOrder: 1, active: true };
  assert.ok(h.call('savePartner', { requestId: SECOND, device: DEVICE, id: ENTITY, expectedVersion: 0, data: partner }).result);
  assert.equal(h.data('publicPartners/' + ENTITY).logo.driveFileId, logo.driveFileId);
  const request = { requestId: '88888888-8888-4888-8888-888888888888', device: DEVICE, id: ENTITY, expectedVersion: 1 };
  assert.deepEqual(h.call('archivePartner', request).result, { id: ENTITY, version: 2 });
  assert.deepEqual(h.call('archivePartner', request).result, { id: ENTITY, version: 2 });
  assert.equal(h.data('publicPartners/' + ENTITY), null);
  assert.equal(h.data('partners/' + ENTITY).createdAt.constructor.name, 'FireTimestamp');
  assert.equal(h.data('partners/' + ENTITY).archived, true);
  assert.equal(h.call('deleteMedia', { requestId: REQUEST, device: DEVICE, mediaId: id }).error.code, 'failed-precondition');
});

test('foreign image reference cannot be adopted and wrong-folder media cannot be read or deleted', () => {
  const h = harness(), id = h.begin().result.storagePath.slice(6), image = h.upload(id).result.media;
  const foreign = { ...h.product([image], 'active') };
  const result = h.call('saveProduct', { requestId: SECOND, device: DEVICE, id: SECOND, expectedVersion: 0, data: foreign });
  assert.equal(result.error.code, 'failed-precondition');
  delete h.files.get(image.driveFileId).appProperties;
  h.files.get(image.driveFileId).parents = ['another_persons_folder'];
  assert.equal(h.call('readMedia', { mediaId: id }).error.code, 'failed-precondition');
  assert.equal(h.call('deleteMedia', { requestId: REQUEST, device: DEVICE, mediaId: id }).error.code, 'failed-precondition');
  assert.ok(!h.files.get(image.driveFileId).trashed);
});

test('truncated image containers and pixel bombs fail before creating Drive data', () => {
  const h = harness();
  const hugePng = Buffer.from(PNG); hugePng.writeUInt32BE(100000, 16);
  for (const bytes of [PNG.subarray(0, -10), hugePng]) {
    const result = h.call('uploadMedia', { mediaId: ENTITY, contentType: 'image/png', base64: bytes.toString('base64') });
    assert.equal(result.error.code, 'invalid-argument');
  }
  assert.equal(h.files.size, 0);
  assert.equal(h.commits.length, 0);
});
