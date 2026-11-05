/**
 * Jayaguna's owner-run Google Apps Script bridge. Never paste OAuth credentials,
 * Firebase Admin keys, or refresh tokens here. Google manages the owner's OAuth.
 * Deploy as the Drive owner, execute as Me, access Anyone. Every private request
 * is independently authenticated by Firestore Security Rules using its ID token.
 */
const JTP = Object.freeze({
  projectId: 'jayagunatehnikperkasa',
  ownerEmail: 'mtahakurniawan@gmail.com',
  productsFolder: '1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd',
  partnersFolder: '1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6',
  maxImageBytes: 5 * 1024 * 1024,
  maxBodyBytes: 7200000,
  maxPixels: 40000000,
});
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DRIVE_ID_RE = /^[A-Za-z0-9_-]{10,200}$/;
const SAFE_UID_RE = /^[A-Za-z0-9_-]{1,128}$/;
const MIMES = ['image/jpeg', 'image/png', 'image/webp'];
const SERVER_TIME = Object.freeze({ serverTime: true });
const ACTIONS = ['getDriveStatus', 'startDriveOAuth', 'beginUpload', 'uploadMedia', 'readMedia', 'mediaMetadata', 'deleteMedia', 'saveProduct', 'savePartner', 'archiveProduct', 'archivePartner', 'manageAdmin'];

function BridgeError(code, message) { this.bridgeCode = code; this.message = message; }
function reject_(code, message) { throw new BridgeError(code, message); }
function check_(condition, message) { if (!condition) reject_('invalid-argument', message); }
function json_(value) { return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON); }

/** Health only: GET never returns private metadata, media, or credentials. */
function doGet() { return json_({ service: 'jayaguna-google-bridge', projectId: JTP.projectId, version: 1 }); }

function doPost(event) {
  let lock;
  try {
    const body = event && event.postData && event.postData.contents;
    check_(typeof body === 'string' && body.length > 0 && body.length <= JTP.maxBodyBytes && (!event.contentLength || Number(event.contentLength) <= JTP.maxBodyBytes), 'Ukuran permintaan tidak valid.');
    let input;
    try { input = JSON.parse(body); } catch (_) { reject_('invalid-argument', 'Permintaan JSON tidak valid.'); }
    strict_(input, ['idToken', 'action', 'payload']);
    check_(ACTIONS.indexOf(input.action) >= 0, 'Operasi tidak didukung.');
    const actor = authenticate_(input.idToken);
    assertOwnerRuntime_();
    lock = LockService.getScriptLock();
    if (!lock.tryLock(5000)) reject_('resource-exhausted', 'Ada proses lain. Coba lagi dengan permintaan yang sama.');
    limit_(actor.uid);
    return json_({ result: dispatch_(actor, input.action, input.payload || {}) });
  } catch (error) {
    // Deliberately do not log exceptions, tokens, request bodies, or upstream data.
    return json_({ error: error && error.bridgeCode ? { code: error.bridgeCode, message: error.message } : { code: 'unavailable', message: 'Layanan Google belum dapat menyelesaikan permintaan. Coba lagi dengan data yang sama.' } });
  } finally { if (lock) { try { lock.releaseLock(); } catch (_) {} } }
}

function assertOwnerRuntime_() {
  if (String(Session.getEffectiveUser().getEmail()).toLowerCase() !== JTP.ownerEmail) reject_('failed-precondition', 'Penghubung harus dijalankan sebagai pemilik Google Drive perusahaan.');
}

function authenticate_(idToken) {
  if (typeof idToken !== 'string' || idToken.length > 8192 || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(idToken)) reject_('unauthenticated', 'Silakan masuk kembali.');
  let claims;
  try { claims = JSON.parse(Utilities.newBlob(Utilities.base64DecodeWebSafe(idToken.split('.')[1])).getDataAsString()); } catch (_) { reject_('unauthenticated', 'Sesi masuk tidak valid.'); }
  if (!SAFE_UID_RE.test(claims.sub || '')) reject_('unauthenticated', 'Sesi masuk tidak valid.');
  // The decoded UID is used ONLY to select the protected document path. No
  // decoded role/email/time is trusted before Firestore validates this exact
  // signed token and applies staff() Rules (profile/role/revocation/active).
  const profile = getDoc_('users/' + claims.sub, idToken, false);
  if (!profile) reject_('permission-denied', 'Profil akun tidak tersedia.');
  const p = profile.data, now = Math.floor(Date.now() / 1000);
  if (claims.aud !== JTP.projectId || claims.iss !== 'https://securetoken.google.com/' + JTP.projectId || !(claims.exp > now) || !(claims.auth_time > 0) || ['OWNER', 'ADMIN'].indexOf(claims.role) < 0 || p.active !== true || p.role !== claims.role || (p.revokedBeforeSeconds && claims.auth_time <= p.revokedBeforeSeconds)) reject_('permission-denied', 'Akses akun tidak aktif atau sudah dicabut.');
  return { uid: claims.sub, role: claims.role, email: String(p.email || claims.email || ''), name: String(p.name || claims.name || claims.email || claims.sub), authTime: claims.auth_time, profile: profile };
}

function limit_(uid) {
  const cache = CacheService.getScriptCache(), key = 'request:' + uid + ':' + Math.floor(Date.now() / 60000);
  const count = Number(cache.get(key) || 0);
  if (count >= 120) reject_('resource-exhausted', 'Terlalu banyak permintaan. Coba lagi sebentar.');
  cache.put(key, String(count + 1), 90);
}

function dispatch_(actor, action, payload) {
  if (action === 'getDriveStatus' || action === 'startDriveOAuth') {
    if (actor.role !== 'OWNER') reject_('permission-denied', 'Hanya OWNER dapat memeriksa sambungan Drive.');
    folder_(JTP.productsFolder); folder_(JTP.partnersFolder);
    return { connected: true, accountEmail: JTP.ownerEmail };
  }
  if (action === 'beginUpload') return beginUpload_(actor, payload);
  if (action === 'uploadMedia') return uploadMedia_(actor, payload);
  if (action === 'readMedia') return readMedia_(actor, payload);
  if (action === 'mediaMetadata') return metadata_(actor, payload);
  if (action === 'deleteMedia') return deleteMedia_(actor, payload);
  if (action === 'saveProduct') return saveEntity_(actor, payload, 'product');
  if (action === 'savePartner') return saveEntity_(actor, payload, 'partner');
  if (action === 'archiveProduct') return archiveEntity_(actor, payload, 'product');
  if (action === 'archivePartner') return archiveEntity_(actor, payload, 'partner');
  reject_('unimplemented', 'Pengelolaan akun admin tetap memakai integrasi pengelola yang sudah ada.');
}

/** Run once from the editor before deployment. Returns no tokens or secrets. */
function verifySetup() {
  assertOwnerRuntime_(); folder_(JTP.productsFolder); folder_(JTP.partnersFolder);
  const owner = getDoc_('users/S28l85ry6EhyEzg5F4c6oweNyJC3');
  if (!owner || owner.data.active !== true || owner.data.role !== 'OWNER') reject_('failed-precondition', 'Profil OWNER Firebase tidak tersedia.');
  return { projectId: JTP.projectId, accountEmail: JTP.ownerEmail, foldersVerified: true, firestoreReadable: true };
}

function strict_(value, keys) {
  check_(value && typeof value === 'object' && !Array.isArray(value), 'Objek permintaan tidak valid.');
  check_(Object.keys(value).every(function (key) { return keys.indexOf(key) >= 0; }), 'Field permintaan tidak dikenal.');
}
function text_(value, max, min) { check_(typeof value === 'string' && value.trim().length <= max && value.trim().length >= (min || 0), 'Teks tidak valid atau terlalu panjang.'); return value.trim(); }
function uuid_(value) { check_(typeof value === 'string' && UUID_RE.test(value), 'ID permintaan tidak valid.'); return value; }
function integer_(value, min, max) { check_(Number.isSafeInteger(value) && value >= min && value <= max, 'Nilai angka tidak valid.'); return value; }
function boolean_(value) { check_(typeof value === 'boolean', 'Nilai status tidak valid.'); return value; }
function enum_(value, values) { check_(values.indexOf(value) >= 0, 'Nilai pilihan tidak valid.'); return value; }
function device_(value) {
  strict_(value, ['installationId', 'deviceName', 'brand', 'model', 'androidVersion', 'appVersion']);
  return { installationId: uuid_(value.installationId), deviceName: text_(value.deviceName, 100, 1), brand: text_(value.brand, 80), model: text_(value.model, 100), androidVersion: text_(value.androidVersion, 40), appVersion: text_(value.appVersion, 40) };
}
function envelope_(value) { return { requestId: uuid_(value.requestId), device: device_(value.device) }; }
function canonical_(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical_).join(',') + ']';
  if (value && typeof value === 'object') return '{' + Object.keys(value).sort(function (a, b) { return a.localeCompare(b); }).map(function (key) { return JSON.stringify(key) + ':' + canonical_(value[key]); }).join(',') + '}';
  return JSON.stringify(value) === undefined ? 'null' : JSON.stringify(value);
}
function digest_(bytes, algorithm) { return Utilities.computeDigest(algorithm || Utilities.DigestAlgorithm.SHA_256, bytes).map(function (v) { return ('0' + ((v + 256) % 256).toString(16)).slice(-2); }).join(''); }
function fingerprint_(value) { return digest_(Utilities.newBlob(canonical_(value)).getBytes()); }
function references_(value) { return value ? value.images || (value.logo ? [value.logo] : []) : []; }
function assetId_(path) { const id = typeof path === 'string' && path.indexOf('drive/') === 0 ? path.slice(6) : ''; check_(UUID_RE.test(id), 'Referensi gambar Drive tidak valid.'); return id; }

// Firestore values retain their type through reads and later atomic rewrites.
function FireTimestamp(value) { this.value = value; }
function encode_(value) {
  if (value instanceof FireTimestamp) return { timestampValue: value.value };
  if (value === null) return { nullValue: null };
  if (typeof value === 'boolean') return { booleanValue: value };
  if (typeof value === 'string') return { stringValue: value };
  if (typeof value === 'number' && Number.isFinite(value)) return Number.isSafeInteger(value) ? { integerValue: String(value) } : { doubleValue: value };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(encode_) } };
  if (value && typeof value === 'object' && value !== SERVER_TIME) {
    const fields = Object.create(null); Object.keys(value).forEach(function (key) { fields[key] = encode_(value[key]); }); return { mapValue: { fields: fields } };
  }
  reject_('invalid-argument', 'Nilai dokumen tidak valid.');
}
function decode_(value) {
  if ('timestampValue' in value) return new FireTimestamp(value.timestampValue);
  if ('nullValue' in value) return null;
  if ('booleanValue' in value) return value.booleanValue;
  if ('stringValue' in value) return value.stringValue;
  if ('integerValue' in value) return Number(value.integerValue);
  if ('doubleValue' in value) return value.doubleValue;
  if ('arrayValue' in value) return (value.arrayValue.values || []).map(decode_);
  if ('mapValue' in value) return decodeFields_(value.mapValue.fields || {});
  reject_('failed-precondition', 'Tipe data dokumen belum didukung penghubung.');
}
function decodeFields_(fields) { const data = Object.create(null); Object.keys(fields).forEach(function (key) { data[key] = decode_(fields[key]); }); return data; }
function documentRoot_() { return 'projects/' + JTP.projectId + '/databases/(default)/documents/'; }
function documentName_(path) { check_(/^[A-Za-z][A-Za-z0-9_]*\/[A-Za-z0-9_-]{1,260}$/.test(path), 'Alamat dokumen tidak valid.'); return documentRoot_() + path; }
function getDoc_(path, token, caller) {
  const response = request_('https://firestore.googleapis.com/v1/' + documentName_(path), { method: 'get' }, token, true);
  const status = response.getResponseCode();
  if (status === 404) return null;
  ensureResponse_(response, caller === false);
  const raw = JSON.parse(response.getContentText()); return { path: path, updateTime: raw.updateTime, data: decodeFields_(raw.fields || {}) };
}
function fieldPath_(key) { return '`' + key.replace(/\\/g, '\\\\').replace(/`/g, '\\`') + '`'; }
function write_(path, data, old, mode) {
  const fields = Object.create(null), transforms = [];
  Object.keys(data).forEach(function (key) { if (data[key] === SERVER_TIME) transforms.push({ fieldPath: fieldPath_(key), setToServerValue: 'REQUEST_TIME' }); else fields[key] = encode_(data[key]); });
  const result = { update: { name: documentName_(path), fields: fields } };
  if (transforms.length) result.updateTransforms = transforms;
  if (mode === 'create') result.currentDocument = { exists: false };
  else if (old) result.currentDocument = { updateTime: old.updateTime };
  return result;
}
function delete_(path) { return { delete: documentName_(path) }; }
function profileGuard_(actor) {
  return { update: { name: documentName_('users/' + actor.uid), fields: { active: { booleanValue: true } } }, updateMask: { fieldPaths: ['active'] }, currentDocument: { updateTime: actor.profile.updateTime } };
}
function commit_(actor, writes) {
  const response = request_('https://firestore.googleapis.com/v1/' + documentRoot_().slice(0, -1) + ':commit', { method: 'post', contentType: 'application/json', payload: JSON.stringify({ writes: [profileGuard_(actor)].concat(writes) }) }, undefined, false);
  ensureResponse_(response, false);
}

function request_(url, options, token, safeRetry) {
  const opts = Object.assign({}, options || {}, { muteHttpExceptions: true, followRedirects: false });
  opts.headers = Object.assign({}, opts.headers || {}, { Authorization: 'Bearer ' + (token || ScriptApp.getOAuthToken()) });
  for (let attempt = 0; attempt < (safeRetry ? 3 : 1); attempt++) {
    let response;
    try { response = UrlFetchApp.fetch(url, opts); } catch (_) { if (safeRetry && attempt < 2) { Utilities.sleep(200 * Math.pow(2, attempt)); continue; } reject_('unavailable', 'Koneksi layanan Google terputus. Coba lagi dengan permintaan yang sama.'); }
    const status = response.getResponseCode();
    if (safeRetry && (status === 429 || status >= 500) && attempt < 2) { Utilities.sleep(200 * Math.pow(2, attempt)); continue; }
    return response;
  }
}
function ensureResponse_(response, caller) {
  const status = response.getResponseCode();
  if (status >= 200 && status < 300) return;
  if (status === 401) reject_(caller ? 'unauthenticated' : 'failed-precondition', caller ? 'Sesi masuk berakhir. Silakan masuk kembali.' : 'Persetujuan akses Google perlu diperiksa oleh pemilik penghubung.');
  if (status === 403) {
    if (!caller) {
      let body;
      try { body = JSON.parse(response.getContentText()); } catch (_) {}
      const error = body && body.error || {};
      if ((error.errors || []).some(function (item) { return item.reason === 'accessNotConfigured'; }) || (error.details || []).some(function (item) { return item.reason === 'SERVICE_DISABLED'; })) reject_('failed-precondition', 'Aktifkan Google Drive API dan Firestore API pada project Google Cloud penghubung, lalu coba lagi.');
    }
    reject_('permission-denied', caller ? 'Akun tidak memiliki akses OWNER/ADMIN aktif.' : 'Pemilik penghubung belum memiliki izin layanan Google yang diperlukan.');
  }
  if (status === 404) reject_('not-found', 'Berkas, folder, atau dokumen tidak ditemukan.');
  if (status === 409 || status === 412) reject_('aborted', 'Data berubah atau permintaan sudah diproses. Coba lagi dengan permintaan yang sama.');
  if (status === 429) reject_('resource-exhausted', 'Batas pemakaian Google tercapai. Coba lagi nanti.');
  reject_('unavailable', 'Layanan Google belum dapat menyelesaikan permintaan. Coba lagi dengan data yang sama.');
}

const DRIVE_FIELDS = 'id,name,mimeType,size,trashed,parents,md5Checksum,appProperties,capabilities,permissions(type),owners(emailAddress)';
function driveMetadata_(id) { check_(DRIVE_ID_RE.test(id || ''), 'ID berkas Drive tidak valid.'); const response = request_('https://www.googleapis.com/drive/v3/files/' + id + '?supportsAllDrives=true&fields=' + encodeURIComponent(DRIVE_FIELDS), { method: 'get' }, undefined, true); ensureResponse_(response); return JSON.parse(response.getContentText()); }
function folder_(id) {
  const folder = driveMetadata_(id);
  if (folder.trashed || folder.mimeType !== 'application/vnd.google-apps.folder' || !folder.capabilities || !folder.capabilities.canAddChildren || !(folder.owners || []).some(function (owner) { return owner.emailAddress.toLowerCase() === JTP.ownerEmail; })) reject_('failed-precondition', 'Folder tidak dimiliki akun Drive utama atau tidak menerima unggahan.');
  if ((folder.permissions || []).some(function (permission) { return ['anyone', 'domain'].indexOf(permission.type) >= 0; })) reject_('failed-precondition', 'Folder penyimpanan harus privat. Hapus akses publik atau domain dari folder.');
  return folder;
}
function reserveDriveId_() { const response = request_('https://www.googleapis.com/drive/v3/files/generateIds?count=1&space=drive&type=files', { method: 'get' }, undefined, true); ensureResponse_(response); const id = (JSON.parse(response.getContentText()).ids || [])[0]; if (!DRIVE_ID_RE.test(id || '')) reject_('unavailable', 'Drive belum memberikan ID berkas.'); return id; }
function bytes_(text) { return Utilities.newBlob(text).getBytes(); }
function verifyDriveFile_(asset, file, md5) {
  const props = file.appProperties || {};
  const hasProps = Object.prototype.hasOwnProperty.call(props, 'assetId') || Object.prototype.hasOwnProperty.call(props, 'sha256');
  if (file.id !== asset.driveFileId || file.trashed || file.mimeType !== asset.mimeType || !(file.parents || []).includes(asset.folder) || (hasProps && (props.assetId !== asset.id || props.sha256 !== asset.sha256)) || (md5 && (props.assetId !== asset.id || props.sha256 !== asset.sha256 || file.md5Checksum !== md5))) reject_('failed-precondition', 'Berkas Drive berubah atau retry tidak sesuai unggahan semula.');
  return hasProps;
}
function createDriveFile_(asset, raw) {
  const boundary = 'jtp_' + Utilities.getUuid().replace(/-/g, '');
  const metadata = { id: asset.driveFileId, name: asset.fileName, mimeType: asset.mimeType, parents: [asset.folder], appProperties: { application: 'jayaguna-admin', assetId: asset.id, entityId: asset.entityId, entityType: asset.entityType, sha256: asset.sha256 } };
  const start = bytes_('--' + boundary + '\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n' + JSON.stringify(metadata) + '\r\n--' + boundary + '\r\nContent-Type: ' + asset.mimeType + '\r\n\r\n');
  const body = start.concat(raw, bytes_('\r\n--' + boundary + '--\r\n'));
  const response = request_('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true&fields=' + encodeURIComponent(DRIVE_FIELDS), { method: 'post', contentType: 'multipart/related; boundary=' + boundary, payload: body }, undefined, true);
  let file;
  if (response.getResponseCode() === 409) file = driveMetadata_(asset.driveFileId);
  else { ensureResponse_(response); file = JSON.parse(response.getContentText()); }
  verifyDriveFile_(asset, file, digest_(raw, Utilities.DigestAlgorithm.MD5));
}

function beginUpload_(actor, input) {
  strict_(input, ['requestId', 'device', 'entityType', 'entityId', 'contentType', 'bytes', 'fileName', 'sha256']);
  const value = Object.assign(envelope_(input), { entityType: enum_(input.entityType, ['product', 'partner']), entityId: uuid_(input.entityId), contentType: enum_(input.contentType, MIMES), bytes: integer_(input.bytes, 1, JTP.maxImageBytes) });
  if (input.fileName !== undefined) value.fileName = text_(input.fileName, 120);
  if (input.sha256 !== undefined) { check_(/^[0-9a-f]{64}$/.test(input.sha256), 'Hash berkas tidak valid.'); value.sha256 = input.sha256; }
  const path = 'driveUploadRequests/' + actor.uid + '_' + value.requestId, hash = fingerprint_(value), existing = getDoc_(path);
  if (existing) { if (existing.data.hash !== hash) reject_('already-exists', 'ID permintaan sudah dipakai untuk gambar berbeda.'); return { storagePath: 'drive/' + existing.data.assetId }; }
  const folder = value.entityType === 'product' ? JTP.productsFolder : JTP.partnersFolder;
  folder_(folder);
  const id = Utilities.getUuid(), driveFileId = reserveDriveId_();
  const asset = { id: id, driveFileId: driveFileId, folder: folder, entityType: value.entityType, entityId: value.entityId, createdBy: actor.uid, device: value.device, requestId: value.requestId, status: 'reserved', expectedBytes: value.bytes, expectedMime: value.contentType, originalSha256: value.sha256 || '', originalFileName: value.fileName || 'gambar', createdAt: SERVER_TIME, expiresAt: new FireTimestamp(new Date(Date.now() + 86400000).toISOString()) };
  commit_(actor, [write_(path, { hash: hash, assetId: id, createdAt: SERVER_TIME }, null, 'create'), write_('mediaAssets/' + id, asset, null, 'create')]);
  return { storagePath: 'drive/' + id };
}
function mediaRecord_(asset) { return { storagePath: 'drive/' + asset.id, url: '', provider: 'drive', driveFileId: asset.driveFileId, fileName: asset.fileName, mimeType: asset.mimeType, sizeBytes: asset.sizeBytes }; }
function uploadMedia_(actor, input) {
  strict_(input, ['mediaId', 'contentType', 'base64']);
  const id = uuid_(input.mediaId), mime = enum_(input.contentType, MIMES);
  check_(typeof input.base64 === 'string' && input.base64.length <= Math.ceil(JTP.maxImageBytes / 3) * 4 && /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(input.base64), 'Isi unggahan tidak valid.');
  const raw = Utilities.base64Decode(input.base64);
  check_(raw.length > 0 && raw.length <= JTP.maxImageBytes, 'Ukuran gambar maksimal 5 MiB.');
  validateImage_(raw, mime);
  const doc = getDoc_('mediaAssets/' + id), asset = doc && doc.data, hash = digest_(raw);
  if (!asset || asset.createdBy !== actor.uid) reject_('permission-denied', 'Sesi unggah bukan milik akun ini.');
  if (asset.expectedBytes !== raw.length || asset.expectedMime !== mime) reject_('invalid-argument', 'Ukuran atau jenis gambar tidak cocok dengan sesi unggah.');
  if (asset.originalSha256 && asset.originalSha256 !== hash) reject_('already-exists', 'Retry harus menggunakan berkas yang sama.');
  if (['uploaded', 'attached'].includes(asset.status)) return { media: mediaRecord_(asset) };
  if (asset.status !== 'reserved' || !(asset.expiresAt instanceof FireTimestamp) || new Date(asset.expiresAt.value).getTime() < Date.now()) reject_('failed-precondition', 'Sesi unggah berakhir. Pilih ulang gambar.');
  const extension = mime === 'image/jpeg' ? 'jpg' : mime === 'image/png' ? 'png' : 'webp';
  const name = asset.originalFileName.replace(/[^a-zA-Z0-9._ -]/g, '_').replace(/\.[^.]*$/, '').slice(0, 90) + '.' + extension;
  const next = Object.assign({}, asset, { originalSha256: hash, fileName: name, mimeType: mime, sizeBytes: raw.length, sha256: hash, status: 'uploaded', uploadedAt: SERVER_TIME });
  // The reserved ID is already durable in Firestore before this side effect.
  // If the response or subsequent commit is lost, retry uploads the same ID.
  createDriveFile_(next, raw);
  commit_(actor, [write_(doc.path, next, doc), mediaAudit_('upload_' + id, actor, asset.device, asset.entityType === 'product' ? 'UPLOAD_PRODUCT_IMAGE' : 'UPLOAD_PARTNER_LOGO', next, asset.requestId)]);
  return { media: mediaRecord_(next) };
}

function dimensions_(w, h) { check_(w > 0 && h > 0 && w <= 40000 && h <= 40000 && w * h <= JTP.maxPixels, 'Gambar rusak atau dimensinya melebihi 40 megapiksel.'); }
function validateImage_(raw, mime) {
  const b = raw.map(function (v) { return (v + 256) % 256; }), n = b.length;
  const u16 = function (i) { return b[i] * 256 + b[i + 1]; };
  const u32 = function (i) { return b[i] * 16777216 + b[i + 1] * 65536 + b[i + 2] * 256 + b[i + 3]; };
  const ascii = function (i, count) { return String.fromCharCode.apply(null, b.slice(i, i + count)); };
  if (mime === 'image/png') {
    check_(n >= 45 && ascii(1, 3) === 'PNG' && b[0] === 137 && ascii(4, 4) === '\r\n\x1a\n' && u32(8) === 13 && ascii(12, 4) === 'IHDR', 'Isi gambar PNG tidak valid.');
    dimensions_(u32(16), u32(20));
    let offset = 8, data = false, end = false;
    while (offset + 12 <= n) { const length = u32(offset), type = ascii(offset + 4, 4); check_(length <= n - offset - 12, 'Struktur PNG terpotong.'); if (type === 'IDAT') data = true; offset += length + 12; if (type === 'IEND') { check_(length === 0 && offset === n, 'Struktur PNG tidak valid.'); end = true; break; } }
    check_(data && end, 'Struktur PNG tidak lengkap.'); return;
  }
  if (mime === 'image/jpeg') {
    check_(n >= 12 && b[0] === 255 && b[1] === 216 && b[n - 2] === 255 && b[n - 1] === 217, 'Isi gambar JPEG tidak valid.');
    let offset = 2, found = false, scan = false;
    while (offset + 4 <= n) { check_(b[offset] === 255, 'Struktur JPEG tidak valid.'); while (b[offset] === 255) offset++; const marker = b[offset++]; if (marker === 218) { scan = true; break; } const length = u16(offset); check_(length >= 2 && offset + length <= n, 'Struktur JPEG terpotong.'); if ([192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207].indexOf(marker) >= 0) { check_(length >= 8, 'Dimensi JPEG tidak valid.'); dimensions_(u16(offset + 5), u16(offset + 3)); found = true; } offset += length; }
    check_(found && scan, 'Struktur JPEG tidak lengkap.'); return;
  }
  check_(n >= 30 && ascii(0, 4) === 'RIFF' && ascii(8, 4) === 'WEBP' && (b[4] + b[5] * 256 + b[6] * 65536 + b[7] * 16777216) + 8 === n, 'Isi gambar WebP tidak valid.');
  const type = ascii(12, 4);
  if (type === 'VP8X') { check_(n >= 30 && !(b[20] & 2), 'Gunakan gambar WebP statis.'); dimensions_(1 + b[24] + b[25] * 256 + b[26] * 65536, 1 + b[27] + b[28] * 256 + b[29] * 65536); }
  else if (type === 'VP8L') { check_(n >= 25 && b[20] === 47, 'Struktur WebP tidak valid.'); dimensions_(1 + b[21] + ((b[22] & 63) << 8), 1 + (b[22] >> 6) + (b[23] << 2) + ((b[24] & 15) << 10)); }
  else { check_(type === 'VP8 ' && ascii(23, 3) === '\x9d\x01\x2a', 'Struktur WebP tidak valid.'); dimensions_((b[26] + b[27] * 256) & 16383, (b[28] + b[29] * 256) & 16383); }
}

function readable_(actor, id) {
  uuid_(id);
  if (!actor) reject_('unauthenticated', 'Silakan masuk kembali.');
  const doc = getDoc_('mediaAssets/' + id), asset = doc && doc.data;
  if (!asset || ['uploaded', 'attached', 'detached'].indexOf(asset.status) < 0 || MIMES.indexOf(asset.mimeType) < 0) reject_('not-found', 'Gambar tidak tersedia.');
  return asset;
}
function metadata_(actor, input) { strict_(input, ['mediaId']); const asset = readable_(actor, input.mediaId); if (!verifyDriveFile_(asset, driveMetadata_(asset.driveFileId))) downloadVerified_(asset); return { media: mediaRecord_(asset), state: asset.status }; }
function downloadVerified_(asset) {
  // appProperties can be invisible across OAuth applications. A registry entry
  // is written only by trusted code; its immutable file ID/folder/MIME plus the
  // complete recorded SHA-256 safely identifies pre-existing Node uploads.
  const response = request_('https://www.googleapis.com/drive/v3/files/' + asset.driveFileId + '?alt=media&supportsAllDrives=true', { method: 'get' }, undefined, true); ensureResponse_(response);
  const raw = response.getContent();
  if (!raw.length || raw.length > JTP.maxImageBytes || raw.length !== asset.sizeBytes || digest_(raw) !== asset.sha256) reject_('failed-precondition', 'Isi gambar Drive berubah atau ukurannya tidak valid.');
  return raw;
}
function readMedia_(actor, input) {
  strict_(input, ['mediaId']); const asset = readable_(actor, input.mediaId);
  verifyDriveFile_(asset, driveMetadata_(asset.driveFileId));
  const raw = downloadVerified_(asset);
  return { base64: Utilities.base64Encode(raw), mimeType: asset.mimeType };
}

function imageInput_(value) {
  strict_(value, ['storagePath', 'url', 'provider', 'driveFileId', 'fileName', 'mimeType', 'sizeBytes']);
  const result = { storagePath: text_(value.storagePath, 500, 1), url: value.url === undefined ? '' : text_(value.url, 2048) };
  if (value.provider !== undefined) result.provider = enum_(value.provider, ['drive', 'firebase']);
  if (value.driveFileId !== undefined) { check_(DRIVE_ID_RE.test(value.driveFileId), 'ID Drive tidak valid.'); result.driveFileId = value.driveFileId; }
  if (value.fileName !== undefined) result.fileName = text_(value.fileName, 160);
  if (value.mimeType !== undefined) result.mimeType = enum_(value.mimeType, MIMES);
  if (value.sizeBytes !== undefined) result.sizeBytes = integer_(value.sizeBytes, 1, JTP.maxImageBytes);
  return result;
}
function product_(input) {
  strict_(input, ['name', 'brand', 'model', 'category', 'capacityPk', 'condition', 'price', 'description', 'specifications', 'images', 'mainImagePath', 'status']);
  check_(input.price === null || typeof input.price === 'number' && Number.isFinite(input.price) && input.price >= 0 && input.price <= 1e12, 'Harga tidak valid.');
  check_(input.specifications && typeof input.specifications === 'object' && !Array.isArray(input.specifications) && Object.keys(input.specifications).length <= 50, 'Spesifikasi tidak valid.');
  const specs = Object.create(null); Object.keys(input.specifications).forEach(function (key) { text_(key, 80, 1); specs[key] = text_(input.specifications[key], 1000); });
  check_(Array.isArray(input.images) && input.images.length <= 8, 'Maksimal 8 gambar produk.');
  const images = input.images.map(imageInput_), main = text_(input.mainImagePath, 500), status = enum_(input.status, ['draft', 'active', 'inactive']);
  check_(new Set(images.map(function (v) { return v.storagePath; })).size === images.length, 'Gambar duplikat.');
  check_(images.length ? images.some(function (v) { return v.storagePath === main; }) : !main, 'Gambar utama harus ada dalam daftar gambar.');
  check_(status !== 'active' || images.length > 0, 'Produk aktif memerlukan gambar.');
  return { name: text_(input.name, 160, 2), brand: text_(input.brand, 100, 1), model: text_(input.model, 100), category: enum_(input.category, ['split', 'cassette', 'standing', 'ducted', 'central', 'other']), capacityPk: text_(input.capacityPk, 40), condition: enum_(input.condition, ['new', 'used']), price: input.price, description: text_(input.description, 10000), specifications: specs, images: images, mainImagePath: main, status: status };
}
function partner_(input) {
  strict_(input, ['name', 'logo', 'altText', 'website', 'displayOrder', 'active']);
  const website = text_(input.website, 2048);
  // Apps Script does not expose the browser URL class. Reject credentials,
  // whitespace, escapes and non-HTTPS schemes instead of trusting URLs as HTML.
  check_(!website || /^https:\/\/[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:[/?#][^\s\\<>]*)?$/.test(website), 'Website harus berupa URL HTTPS.');
  return { name: text_(input.name, 160, 2), logo: imageInput_(input.logo), altText: text_(input.altText, 250, 1), website: website, displayOrder: integer_(input.displayOrder, 0, 1000000), active: boolean_(input.active) };
}
function searchTokens_(name, brand) {
  const result = [];
  [name, brand].forEach(function (value) { const lower = value.toLowerCase().trim(); [lower].concat(lower.split(/\s+/)).forEach(function (part) { for (let i = 1; i <= Math.min(part.length, 50); i++) if (result.indexOf(part.slice(0, i)) < 0 && result.length < 1000) result.push(part.slice(0, i)); }); });
  return result;
}
function receipt_(actor, id, hash) { const doc = getDoc_('operationReceipts/' + actor.uid + '_' + id); if (doc && doc.data.hash !== hash) reject_('already-exists', 'ID permintaan sudah digunakan untuk data berbeda.'); return doc && doc.data.result; }
function promote_(actor, type, entityId, image, old) {
  if (image.storagePath.indexOf('drive/') !== 0) {
    const existing = references_(old).find(function (item) { return item.storagePath === image.storagePath; });
    if (!existing) reject_('failed-precondition', 'Gambar lama tidak tersedia. Pilih gambar melalui pemilih foto.');
    return existing;
  }
  const id = assetId_(image.storagePath), doc = getDoc_('mediaAssets/' + id), asset = doc && doc.data;
  if (!asset || asset.entityType !== type || asset.entityId !== entityId || ['uploaded', 'attached'].indexOf(asset.status) < 0 || asset.status === 'uploaded' && asset.createdBy !== actor.uid) reject_('failed-precondition', 'Gambar belum selesai diunggah atau tidak cocok dengan data ini.');
  return mediaRecord_(asset);
}
function mediaChanges_(actor, type, entityId, envelope, before, after) {
  const previous = references_(before).filter(function (i) { return i.storagePath.indexOf('drive/') === 0; }), next = references_(after).filter(function (i) { return i.storagePath.indexOf('drive/') === 0; });
  const paths = Array.from(new Set(previous.concat(next).map(function (i) { return i.storagePath; }))), writes = [];
  const replacing = next.some(function (image) { return !previous.some(function (old) { return old.storagePath === image.storagePath; }); });
  paths.forEach(function (path) {
    const id = assetId_(path), doc = getDoc_('mediaAssets/' + id), asset = doc && doc.data, included = next.some(function (image) { return image.storagePath === path; });
    if (!asset || asset.entityId !== entityId || asset.entityType !== type) reject_('failed-precondition', 'Referensi gambar tidak sesuai entitas.');
    if (included) {
      if (!['uploaded', 'attached'].includes(asset.status) || asset.status === 'uploaded' && asset.createdBy !== actor.uid) reject_('aborted', 'Gambar berubah atau sedang dihapus. Muat ulang data.');
      writes.push(write_(doc.path, Object.assign({}, asset, { status: 'attached', attachedAt: SERVER_TIME }), doc));
    } else if (asset.status !== 'deleted') {
      writes.push(write_(doc.path, Object.assign({}, asset, { status: 'detached', detachedAt: SERVER_TIME, deleteAfter: new FireTimestamp(new Date(Date.now() + 86400000).toISOString()) }), doc));
      writes.push(mediaAudit_(actor.uid + '_' + envelope.requestId + '_remove_' + id, actor, envelope.device, (replacing ? 'REPLACE_' : 'REMOVE_') + (type === 'product' ? 'PRODUCT_IMAGE' : 'PARTNER_LOGO'), asset, envelope.requestId));
    }
  });
  return writes;
}
function projection_(type, id, value) {
  if (value.archived || (type === 'product' ? value.status !== 'active' : !value.active)) return null;
  const fields = type === 'product' ? ['name', 'brand', 'model', 'category', 'capacityPk', 'condition', 'price', 'description', 'specifications', 'images', 'mainImagePath', 'status'] : ['name', 'logo', 'altText', 'website', 'displayOrder', 'active'];
  const data = { id: id, archived: false, createdAt: value.createdAt, updatedAt: value.updatedAt, mediaPaths: references_(value).map(function (i) { return i.storagePath; }) };
  fields.forEach(function (key) { data[key] = value[key]; }); return data;
}
function relevant_(value) { const data = Object.create(null); Object.keys(value || {}).forEach(function (key) { if (['createdAt', 'updatedAt', 'createdBy', 'updatedBy', 'searchTokens'].indexOf(key) < 0) data[key] = value[key]; }); return data; }
function activityAudit_(actor, envelope, type, id, action, before, after) {
  before = relevant_(before); after = relevant_(after);
  const changed = Object.create(null), old = Object.create(null), next = Object.create(null);
  Array.from(new Set(Object.keys(before).concat(Object.keys(after)))).forEach(function (key) { if (canonical_(before[key]) !== canonical_(after[key])) { old[key] = before[key] === undefined ? null : before[key]; next[key] = after[key] === undefined ? null : after[key]; changed[key] = { before: old[key], after: next[key] }; } });
  return write_('activityLogs/' + actor.uid + '_' + envelope.requestId, Object.assign({ actorUid: actor.uid, actorName: actor.name, actorEmail: actor.email, actorRole: actor.role, action: action, entityType: type, entityId: id, before: old, after: next, changedFields: changed, timestamp: SERVER_TIME, requestId: envelope.requestId, device: envelope.device }, envelope.device), null, 'create');
}
function mediaAudit_(id, actor, device, action, asset, requestId) { return write_('audit_logs/' + id, { userId: actor.uid, userEmail: actor.email, actorName: actor.name, actorRole: actor.role, action: action, targetId: asset.entityId, entityType: asset.entityType, driveFileId: asset.driveFileId || '', mediaId: asset.id, deviceId: device.installationId, device: device, status: 'SUCCESS', timestamp: SERVER_TIME, requestId: requestId }, null, 'create'); }
function saveEntity_(actor, input, type) {
  strict_(input, ['requestId', 'device', 'id', 'expectedVersion', 'data']);
  const envelope = envelope_(input), id = uuid_(input.id), expectedVersion = integer_(input.expectedVersion, 0, Number.MAX_SAFE_INTEGER - 1), data = type === 'product' ? product_(input.data) : partner_(input.data);
  const value = Object.assign({}, envelope, { id: id, expectedVersion: expectedVersion, data: data }), hash = fingerprint_(Object.assign({ operation: 'save' + type }, value)), repeated = receipt_(actor, envelope.requestId, hash);
  if (repeated) return repeated;
  const path = (type === 'product' ? 'products/' : 'partners/') + id, doc = getDoc_(path), old = doc && doc.data;
  if ((old ? old.version : 0) !== expectedVersion || old && old.archived) reject_('aborted', 'Data telah diubah pengguna lain. Muat ulang sebelum menyimpan.');
  if (type === 'product') { data.images = data.images.map(function (image) { return promote_(actor, type, id, image, old); }); data.searchTokens = searchTokens_(data.name, data.brand); }
  else data.logo = promote_(actor, type, id, data.logo, old);
  const next = Object.assign({}, data, { id: id, archived: false, version: expectedVersion + 1, createdAt: old && old.createdAt || SERVER_TIME, updatedAt: SERVER_TIME, createdBy: old && old.createdBy || actor.uid, updatedBy: actor.uid });
  const projection = projection_(type, id, next), publicPath = (type === 'product' ? 'publicProducts/' : 'publicPartners/') + id, result = { id: id, version: next.version };
  const writes = mediaChanges_(actor, type, id, envelope, old, next);
  writes.push(write_(path, next, doc, doc ? undefined : 'create'));
  writes.push(projection ? write_(publicPath, projection) : delete_(publicPath));
  const statusField = type === 'product' ? 'status' : 'active';
  writes.push(activityAudit_(actor, envelope, type, id, !old ? 'add' : old[statusField] !== next[statusField] ? 'status' : 'edit', old, next));
  writes.push(write_('operationReceipts/' + actor.uid + '_' + envelope.requestId, { hash: hash, result: result, createdAt: SERVER_TIME }, null, 'create'));
  commit_(actor, writes);
  return result;
}
function archiveEntity_(actor, input, type) {
  strict_(input, ['requestId', 'device', 'id', 'expectedVersion']);
  const envelope = envelope_(input), id = uuid_(input.id), expectedVersion = integer_(input.expectedVersion, 1, Number.MAX_SAFE_INTEGER - 1), value = Object.assign({}, envelope, { id: id, expectedVersion: expectedVersion }), hash = fingerprint_(Object.assign({ operation: 'archive' + type }, value)), repeated = receipt_(actor, envelope.requestId, hash);
  if (repeated) return repeated;
  const path = (type === 'product' ? 'products/' : 'partners/') + id, doc = getDoc_(path), old = doc && doc.data;
  if (!old) reject_('not-found', 'Data tidak ditemukan.');
  if (old.version !== expectedVersion || old.archived) reject_('aborted', 'Versi berubah. Muat ulang data.');
  const next = Object.assign({}, old, { archived: true, version: old.version + 1, updatedAt: SERVER_TIME, updatedBy: actor.uid }), result = { id: id, version: next.version };
  commit_(actor, [write_(path, next, doc), delete_((type === 'product' ? 'publicProducts/' : 'publicPartners/') + id), activityAudit_(actor, envelope, type, id, 'archive', old, next), write_('operationReceipts/' + actor.uid + '_' + envelope.requestId, { hash: hash, result: result, createdAt: SERVER_TIME }, null, 'create')]);
  return result;
}
function deleteMedia_(actor, input) {
  strict_(input, ['requestId', 'device', 'mediaId']);
  const envelope = envelope_(input), id = uuid_(input.mediaId), path = 'mediaAssets/' + id;
  let doc = getDoc_(path), asset = doc && doc.data;
  if (!asset) reject_('not-found', 'Media tidak ditemukan.');
  if (asset.createdBy !== actor.uid && actor.role !== 'OWNER') reject_('permission-denied', 'Hanya pembuat unggahan atau OWNER dapat membersihkan media.');
  const entity = getDoc_((asset.entityType === 'product' ? 'products/' : 'partners/') + asset.entityId);
  if (entity && references_(entity.data).some(function (image) { return image.storagePath === 'drive/' + id; })) reject_('failed-precondition', 'Gambar masih dipakai. Simpan penggantian terlebih dahulu.');
  if (asset.status === 'deleted') return { deleted: true };
  commit_(actor, [write_(path, Object.assign({}, asset, { status: 'deleting' }), doc)]);
  try {
    const file = driveMetadata_(asset.driveFileId);
    if (asset.mimeType && asset.sha256) {
      if (!verifyDriveFile_(asset, file)) downloadVerified_(asset);
    } else if (!(file.parents || []).includes(asset.folder) || !file.appProperties || file.appProperties.assetId !== id) reject_('failed-precondition', 'Berkas bukan bagian dari media terkelola.');
    const response = request_('https://www.googleapis.com/drive/v3/files/' + asset.driveFileId + '?supportsAllDrives=true', { method: 'patch', contentType: 'application/json', payload: JSON.stringify({ trashed: true }) }, undefined, true);
    if (response.getResponseCode() !== 404) ensureResponse_(response);
  } catch (error) { if (!error || error.bridgeCode !== 'not-found') throw error; }
  // The first commit advanced the profile updateTime; refresh authorization so
  // revocation during a slow Drive call cannot commit the final audit.
  const latestProfile = getDoc_('users/' + actor.uid);
  if (!latestProfile || !latestProfile.data.active || latestProfile.data.role !== actor.role || latestProfile.data.revokedBeforeSeconds && actor.authTime <= latestProfile.data.revokedBeforeSeconds) reject_('permission-denied', 'Akses akun telah berubah.');
  actor.profile = latestProfile;
  doc = getDoc_(path);
  if (doc.data.status === 'deleted') return { deleted: true };
  commit_(actor, [write_(path, Object.assign({}, doc.data, { status: 'deleted', deletedAt: SERVER_TIME }), doc), mediaAudit_('delete_' + id, actor, envelope.device, asset.entityType === 'product' ? 'DELETE_PRODUCT_IMAGE' : 'DELETE_PARTNER_LOGO', asset, envelope.requestId)]);
  return { deleted: true };
}
