'use strict';
// Live verification only. Uses an existing Firebase SDK session in memory;
// never creates an Auth identity, prints credentials, or writes token files.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const {checkedUrl, checkHealth} = require('./configure-apps-script.cjs');

const PROJECT = 'jayagunatehnikperkasa';
const OWNER_UID = 'S28l85ry6EhyEzg5F4c6oweNyJC3';
const OWNER_EMAIL = 'mtahakurniawan@gmail.com';
const PACKAGE = 'com.jayaguna.admin';
const MAX_IMAGE = 5 * 1024 * 1024;
const MAX_JSON = 8 * 1024 * 1024;
const EXISTING_MEDIA = '795ac6a1-47c0-4b1f-ab11-6c938fa96534';
const PRODUCT_FOLDER = '1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MIME = new Set(['image/png', 'image/jpeg', 'image/webp']);
const SAFE_CODES = new Set(['unauthenticated','permission-denied','failed-precondition','not-found','invalid-argument','already-exists','aborted','resource-exhausted','unavailable','deadline-exceeded','unimplemented']);
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64');

class CheckError extends Error {
  constructor(code) { super('Pemeriksaan belum selesai; credential dan token tidak dicetak.'); this.safeCode = code; }
}
const fail = code => { throw new CheckError(code); };
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

function decodeXml(value) {
  return value.replace(/&#x([0-9a-f]+);|&#([0-9]+);|&(quot|apos|lt|gt|amp);/gi, (match, hex, decimal, named) => {
    if (hex || decimal) {
      const code = Number.parseInt(hex || decimal, hex ? 16 : 10);
      if (!Number.isInteger(code) || code < 0 || code > 0x10ffff) fail('emulator-session-unavailable');
      return String.fromCodePoint(code);
    }
    return {quot:'"',apos:"'",lt:'<',gt:'>',amp:'&'}[named.toLowerCase()];
  });
}
function cachedIdToken(xml) {
  if (typeof xml !== 'string' || xml.length > 2 * 1024 * 1024) fail('emulator-session-unavailable');
  const strings = xml.matchAll(/<string\b([^>]*)>([\s\S]*?)<\/string>/g);
  for (const match of strings) {
    const name = /\bname=(?:"([^"]*)"|'([^']*)')/.exec(match[1]);
    if (!name || decodeXml(name[1] || name[2]) !== 'com.google.firebase.auth.FIREBASE_USER') continue;
    try {
      const user = JSON.parse(decodeXml(match[2]));
      const state = typeof user.cachedTokenState === 'string' ? JSON.parse(user.cachedTokenState) : user.cachedTokenState;
      const token = state?.access_token;
      if (typeof token !== 'string' || token.length > 8192 || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(token)) fail('emulator-session-unavailable');
      return token;
    } catch { fail('emulator-session-unavailable'); }
  }
  fail('emulator-session-unavailable');
}
function emulatorToken(device) {
  if (!/^emulator-\d+$/.test(device || '')) fail('emulator-required');
  const candidates = [process.env.ANDROID_HOME && path.join(process.env.ANDROID_HOME,'platform-tools/adb.exe'), process.env.ANDROID_SDK_ROOT && path.join(process.env.ANDROID_SDK_ROOT,'platform-tools/adb.exe'), 'C:/Users/User/AppData/Local/Android/Sdk/platform-tools/adb.exe'].filter(Boolean);
  const adb = candidates.find(file => fs.existsSync(file));
  if (!adb) fail('adb-unavailable');
  const environment = {...process.env};
  if (!environment.ANDROID_USER_HOME && environment.USERPROFILE) environment.ANDROID_USER_HOME = path.join(environment.USERPROFILE,'.android');
  const execute = args => {
    try { return execFileSync(adb, ['-s',device,'shell',...args], {encoding:'utf8', timeout:20000, maxBuffer:2*1024*1024, windowsHide:true, env:environment, stdio:['ignore','pipe','pipe']}); }
    catch { fail('emulator-session-unavailable'); }
  };
  const names = execute(['run-as',PACKAGE,'ls','shared_prefs']).split(/\r?\n/).map(value => value.trim())
    .filter(value => /^com\.google\.firebase\.auth\.api\.Store[A-Za-z0-9._+=\[\]-]*\.xml$/.test(value));
  for (const name of names) {
    // The validated filename is quoted for Android's shell; no caller-controlled
    // shell syntax or credential value is interpolated into this command.
    const xml = execute([`run-as ${PACKAGE} cat 'shared_prefs/${name}'`]);
    try { return cachedIdToken(xml); } catch { /* Other Firebase app store. */ }
  }
  fail('emulator-session-unavailable');
}
function checkedRedirect(input) {
  let url;
  try { url = new URL(input); } catch { fail('unexpected-google-redirect'); }
  if (url.protocol !== 'https:' || url.hostname !== 'script.googleusercontent.com' || url.port || url.username || url.password || url.hash || url.pathname !== '/macros/echo') fail('unexpected-google-redirect');
  return url.href;
}
async function boundedBody(response, maximum = MAX_JSON) {
  if (!response.body) fail('invalid-google-response');
  const chunks = [], reader = response.body.getReader();
  let size = 0;
  try {
    for (;;) {
      const {done,value} = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maximum) { await reader.cancel(); fail('response-too-large'); }
      chunks.push(Buffer.from(value));
    }
  } finally { reader.releaseLock(); }
  return Buffer.concat(chunks,size).toString('utf8');
}
async function scriptRequest(url, idToken, action, payload = {}, request = fetch) {
  let target = checkedUrl(url), body = JSON.stringify({idToken, action, payload});
  for (let hop = 0; hop < 4; hop++) {
    let response;
    try {
      response = await request(target, body === null
        ? {method:'GET', redirect:'manual', signal:AbortSignal.timeout(90000), headers:{Accept:'application/json'}}
        : {method:'POST', redirect:'manual', signal:AbortSignal.timeout(90000), headers:{Accept:'application/json','Content-Type':'application/json'}, body});
    } catch { fail('google-network'); }
    if ([302,303].includes(response.status)) {
      target = checkedRedirect(response.headers.get('location'));
      body = null; // Never forward the Firebase token/body to the result URL.
      continue;
    }
    if (!response.ok || response.headers.get('content-type')?.split(';')[0].trim().toLowerCase() !== 'application/json') fail('runtime-authorization-or-deployment');
    try {
      const value = JSON.parse(await boundedBody(response));
      if (!value || typeof value !== 'object' || Array.isArray(value)) fail('invalid-google-response');
      return value;
    } catch (error) { if (error instanceof CheckError) throw error; fail('invalid-google-response'); }
  }
  fail('too-many-google-redirects');
}
async function call(url, idToken, action, payload) {
  const value = await scriptRequest(url,idToken,action,payload);
  if (value.error) fail('bridge-' + (SAFE_CODES.has(value.error.code) ? value.error.code : 'unavailable'));
  if (!value.result || typeof value.result !== 'object' || Array.isArray(value.result)) fail('invalid-google-response');
  return value.result;
}
function imageBytes(result, expectedMime, expectedHash, expectedSize) {
  const encoded = result?.base64;
  if (!MIME.has(result?.mimeType) || result.mimeType !== expectedMime || typeof encoded !== 'string' || encoded.length > Math.ceil(MAX_IMAGE/3)*4 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encoded)) fail('image-response-mismatch');
  const bytes = Buffer.from(encoded,'base64');
  if (!bytes.length || bytes.length > MAX_IMAGE || bytes.length !== expectedSize || sha256(bytes) !== expectedHash) fail('image-response-mismatch');
  return bytes;
}
function validAudit(snapshot, id, action, deviceId) {
  const value = snapshot.data();
  return snapshot.exists && value.userId === OWNER_UID && value.mediaId === id && value.status === 'SUCCESS' && value.action === action && value.deviceId === deviceId && typeof value.timestamp?.toMillis === 'function';
}
async function temporaryUpload({url,token,db,mark}) {
  const entityId = crypto.randomUUID(), requestId = crypto.randomUUID();
  const device = {installationId:crypto.randomUUID(),deviceName:'Pemeriksaan integrasi Apps Script',brand:'Jayaguna',model:'Technical integration check',androidVersion:'backend-check',appVersion:'1.0.2-check'};
  let mediaId, problem, result;
  try {
    mark('reserve-temporary-image');
    const reservation = await call(url,token,'beginUpload',{requestId,device,entityType:'product',entityId,contentType:'image/png',bytes:PNG.length,sha256:sha256(PNG),fileName:'pemeriksaan-integrasi.png'});
    if (typeof reservation.storagePath !== 'string' || !reservation.storagePath.startsWith('drive/') || !UUID.test(reservation.storagePath.slice(6))) fail('invalid-reservation');
    mediaId = reservation.storagePath.slice(6);
    const assetBefore = await db.doc('mediaAssets/'+mediaId).get();
    if (!assetBefore.exists || assetBefore.get('createdBy') !== OWNER_UID || assetBefore.get('entityId') !== entityId || assetBefore.get('entityType') !== 'product' || assetBefore.get('folder') !== PRODUCT_FOLDER) fail('temporary-asset-mismatch');
    mark('upload-temporary-image');
    const uploaded = await call(url,token,'uploadMedia',{mediaId,contentType:'image/png',base64:PNG.toString('base64')});
    if (uploaded.media?.storagePath !== 'drive/'+mediaId || uploaded.media?.driveFileId !== assetBefore.get('driveFileId')) fail('upload-metadata-mismatch');
    mark('read-temporary-image');
    imageBytes(await call(url,token,'readMedia',{mediaId}),'image/png',sha256(PNG),PNG.length);
    const metadata = await call(url,token,'mediaMetadata',{mediaId});
    if (metadata.media?.storagePath !== 'drive/'+mediaId || metadata.media?.driveFileId !== assetBefore.get('driveFileId') || metadata.state !== 'uploaded') fail('upload-metadata-mismatch');
    const audit = await db.doc('audit_logs/upload_'+mediaId).get();
    if (!validAudit(audit,mediaId,'UPLOAD_PRODUCT_IMAGE',device.installationId)) fail('upload-audit-mismatch');
    result = {uploaded:true,downloadHashMatches:true,metadataVerified:true,uploadAuditVerified:true,bytes:PNG.length,mediaId};
  } catch (error) { problem = error; }
  finally {
    if (mediaId) {
      try {
        mark('cleanup-temporary-image');
        const asset = await db.doc('mediaAssets/'+mediaId).get();
        if (!asset.exists || asset.get('createdBy') !== OWNER_UID || asset.get('entityId') !== entityId || asset.get('entityType') !== 'product' || asset.get('folder') !== PRODUCT_FOLDER) fail('temporary-cleanup-refused');
        const deleted = await call(url,token,'deleteMedia',{requestId:crypto.randomUUID(),device,mediaId});
        if (deleted.deleted !== true) fail('temporary-cleanup-failed');
        const [after,audit,entity] = await Promise.all([db.doc('mediaAssets/'+mediaId).get(),db.doc('audit_logs/delete_'+mediaId).get(),db.doc('products/'+entityId).get()]);
        if (after.get('status') !== 'deleted' || !validAudit(audit,mediaId,'DELETE_PRODUCT_IMAGE',device.installationId) || entity.exists) fail('temporary-cleanup-verification');
        const hidden = await scriptRequest(url,token,'readMedia',{mediaId});
        if (hidden.error?.code !== 'not-found') fail('deleted-image-still-readable');
        if (result) Object.assign(result,{deleted:true,deleteAuditVerified:true,catalogUntouched:true});
        console.log(JSON.stringify({temporaryImageCleanup:true,mediaId,catalogUntouched:true}));
      } catch (cleanupError) {
        console.error(JSON.stringify({temporaryImageCleanup:false,mediaId,code:cleanupError instanceof CheckError ? cleanupError.safeCode : 'temporary-cleanup-failed'}));
        if (!problem) problem = cleanupError;
      }
    }
  }
  if (problem) throw problem;
  return result;
}

async function main() {
  const args = process.argv.slice(2), argument = name => { const index=args.indexOf(name); return index < 0 ? undefined : args[index+1]; };
  if (args.some((value,index) => !['--url','--device','--upload-check'].includes(value) && !['--url','--device'].includes(args[index-1]))) fail('arguments');
  const device = argument('--device'), url = checkedUrl(argument('--url'));
  if (!/^emulator-\d+$/.test(device || '')) fail('emulator-required');
  if (['FIRESTORE_EMULATOR_HOST','FIREBASE_AUTH_EMULATOR_HOST'].some(key => process.env[key])) fail('cloud-project-required');
  let stage = 'health', app;
  const mark = value => { stage = value; console.log(JSON.stringify({checking:true,stage})); };
  try {
    try { await checkHealth(url); } catch { fail('runtime-authorization-or-deployment'); }
    mark('reject-anonymous');
    const anonymous = await scriptRequest(url,'','getDriveStatus');
    if (anonymous.error?.code !== 'unauthenticated') fail('anonymous-access-not-denied');
    mark('read-existing-emulator-session');
    const token = emulatorToken(device);
    const {initializeApp,deleteApp} = require('firebase-admin/app');
    const {getAuth} = require('firebase-admin/auth');
    const {getFirestore} = require('firebase-admin/firestore');
    const {serverAppOptions} = require('../lib/server-config');
    const options = serverAppOptions();
    if (options.projectId !== PROJECT) fail('cloud-project-required');
    app = initializeApp(options);
    const db = getFirestore(app);
    mark('verify-existing-owner-session');
    let identity;
    try { identity = await getAuth(app).verifyIdToken(token,true); }
    catch { fail('emulator-session-expired-or-revoked'); }
    const profile = await db.doc('users/'+OWNER_UID).get();
    if (identity.uid !== OWNER_UID || identity.email !== OWNER_EMAIL || identity.role !== 'OWNER' || !(identity.auth_time > 0) || profile.get('active') !== true || profile.get('role') !== 'OWNER' || (profile.get('revokedBeforeSeconds') && identity.auth_time <= profile.get('revokedBeforeSeconds'))) fail('owner-session-required');
    mark('drive-folders');
    const status = await call(url,token,'getDriveStatus');
    if (status.connected !== true || status.accountEmail !== OWNER_EMAIL) fail('drive-status-mismatch');
    const report = {checked:true,project:PROJECT,health:true,anonymousDenied:true,existingFirebaseOwnerSession:true,driveConnected:true,foldersVerified:true};
    mark('existing-media');
    const existing = await db.doc('mediaAssets/'+EXISTING_MEDIA).get();
    if (!existing.exists || !['uploaded','attached','detached'].includes(existing.get('status'))) report.existingMediaSkipped = true;
    else {
      imageBytes(await call(url,token,'readMedia',{mediaId:EXISTING_MEDIA}),existing.get('mimeType'),existing.get('sha256'),existing.get('sizeBytes'));
      report.existingMediaReadHashMatches = true;
    }
    if (args.includes('--upload-check')) report.temporaryImage = await temporaryUpload({url,token,db,mark});
    await deleteApp(app); app = undefined;
    return report;
  } catch (error) {
    const safe = new CheckError(error instanceof CheckError ? error.safeCode : 'check-failed'); safe.stage = stage; throw safe;
  } finally {
    if (app) { try { await require('firebase-admin/app').deleteApp(app); } catch {} }
  }
}

module.exports = {decodeXml,cachedIdToken,checkedRedirect,boundedBody,scriptRequest,imageBytes,validAudit};
if (require.main === module) main().then(result => console.log(JSON.stringify(result))).catch(error => {
  console.error(JSON.stringify({checked:false,stage:error.stage || 'configuration',code:error instanceof CheckError ? error.safeCode : 'check-failed',message:'Pemeriksaan belum selesai. Periksa persetujuan runtime Google dan sesi OWNER pada emulator. Token tidak dicetak.'}));
  process.exitCode=1;
});
