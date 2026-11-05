'use strict';
// Local owner-only deployment helper. No tokens are printed or sent to Android.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const {OAuth2Client} = require('google-auth-library');

const OWNER = 'mtahakurniawan@gmail.com';
const OAUTH_PROJECT = 'jayagunatehnikperkasa-512819';
const TITLE = 'Jayaguna Drive Bridge';
const WEB_REDIRECT = 'http://localhost:8787/api/drive/oauth/callback';
const REPOSITORY = path.resolve(__dirname, '../..');
const ARTIFACTS = path.join(REPOSITORY, '.artifacts/apps-script-admin');
const SCOPES = ['openid', 'email', 'https://www.googleapis.com/auth/script.projects', 'https://www.googleapis.com/auth/script.deployments'];
const RUNTIME_SCOPES = ['https://www.googleapis.com/auth/drive', 'https://www.googleapis.com/auth/datastore', 'https://www.googleapis.com/auth/script.external_request', 'https://www.googleapis.com/auth/userinfo.email'];

class SetupError extends Error { constructor(code, message) { super(message); this.safeCode = code; } }
const fail = (code, message) => { throw new SetupError(code, message); };
function privateWrite(file, value) {
  fs.mkdirSync(path.dirname(file), {recursive:true, mode:0o700});
  const temporary = file + '.tmp';
  fs.writeFileSync(temporary, typeof value === 'string' ? value : JSON.stringify(value, null, 2), {mode:0o600});
  fs.renameSync(temporary, file);
}
function readJson(file) { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { fail('local-config', 'File konfigurasi lokal tidak dapat dibaca. Periksa lokasi file yang diberikan.'); } }
function clientConfig(file) {
  const json = readJson(file), value = json?.installed || json?.web;
  if (!value || value.project_id !== OAUTH_PROJECT || !/^[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$/.test(value.client_id || '') || typeof value.client_secret !== 'string' || !value.client_secret) {
    fail('oauth-client', 'Gunakan JSON OAuth Desktop atau Web asli dengan client secret pada project jayagunatehnikperkasa-512819.');
  }
  if (json.web) {
    if (!Array.isArray(value.redirect_uris) || !value.redirect_uris.includes(WEB_REDIRECT)) fail('oauth-client', 'OAuth Web harus memiliki redirect http://localhost:8787/api/drive/oauth/callback yang sudah terdaftar.');
    return {clientId:value.client_id, clientSecret:value.client_secret, redirectUri:WEB_REDIRECT, port:8787};
  }
  return {clientId:value.client_id, clientSecret:value.client_secret};
}
function requireOwner(identity, clientId) {
  if (!identity || identity.email_verified !== true || String(identity.email || '').toLowerCase() !== OWNER ||
      identity.aud !== clientId) fail('wrong-owner', 'Pilih akun pemilik Drive mtahakurniawan@gmail.com; akun lain tidak disimpan.');
}
function normalizedScopes(scope) {
  const values = Array.isArray(scope) ? scope : String(scope || '').split(/\s+/);
  return [...new Set(values.filter(Boolean))].sort();
}
function requireScopes(scopes) {
  const values = normalizedScopes(scopes);
  if (!SCOPES.slice(2).every(scope => values.includes(scope))) fail('oauth-scope', 'Izin pengelolaan Apps Script belum lengkap. Ulangi --authorize dan tinjau izin Google.');
  return values;
}
function encryptedGrant(value, key, clientId) {
  const iv = crypto.randomBytes(12), cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(OWNER + '\n' + clientId));
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()]);
  return {version:1, iv:iv.toString('base64'), tag:cipher.getAuthTag().toString('base64'), ciphertext:ciphertext.toString('base64')};
}
function decryptedGrant(envelope, key, clientId) {
  try {
    if (envelope.version !== 1 || key.length !== 32) throw new Error();
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(envelope.iv, 'base64'));
    decipher.setAAD(Buffer.from(OWNER + '\n' + clientId));
    decipher.setAuthTag(Buffer.from(envelope.tag, 'base64'));
    const value = JSON.parse(Buffer.concat([decipher.update(Buffer.from(envelope.ciphertext, 'base64')), decipher.final()]).toString('utf8'));
    if (value.ownerEmail !== OWNER || value.clientId !== clientId || typeof value.refreshToken !== 'string' || !value.refreshToken) throw new Error();
    requireScopes(value.scopes);
    return value;
  } catch { fail('local-grant', 'Otorisasi lokal tidak dapat dibuka atau tidak cocok. Ulangi --authorize; rahasia tidak dicetak.'); }
}
function saveGrant(tokens, scopes, config, directory = ARTIFACTS) {
  if (typeof tokens.refresh_token !== 'string' || !tokens.refresh_token) fail('offline-access', 'Google belum memberikan akses offline. Ulangi --authorize dengan akun pemilik.');
  const keyFile = path.join(directory, '.key');
  fs.mkdirSync(directory, {recursive:true, mode:0o700});
  let key;
  if (fs.existsSync(keyFile)) key = Buffer.from(fs.readFileSync(keyFile, 'utf8'), 'base64');
  else { key = crypto.randomBytes(32); privateWrite(keyFile, key.toString('base64')); }
  if (key.length !== 32) fail('local-key', 'Kunci otorisasi lokal tidak valid. Periksa penyimpanan lokal.');
  privateWrite(path.join(directory, 'grant.json'), encryptedGrant({ownerEmail:OWNER, clientId:config.clientId, refreshToken:tokens.refresh_token, scopes:requireScopes(scopes), authorizedAt:new Date().toISOString()}, key, config.clientId));
}
function loadGrant(config, directory = ARTIFACTS) {
  const grantFile = path.join(directory, 'grant.json'), keyFile = path.join(directory, '.key');
  if (!fs.existsSync(grantFile) || !fs.existsSync(keyFile)) fail('authorization-required', 'Jalankan --authorize dahulu; Google meminta persetujuan pemilik akun.');
  return decryptedGrant(readJson(grantFile), Buffer.from(fs.readFileSync(keyFile, 'utf8'), 'base64'), config.clientId);
}
function validState(actual, expected) {
  if (typeof actual !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(actual) || actual.length !== expected.length) return false;
  return crypto.timingSafeEqual(Buffer.from(actual), Buffer.from(expected));
}
function authorizationRequest(config, port, state, verifier) {
  if (!Number.isInteger(port) || port < 1 || port > 65535 || !/^[A-Za-z0-9_-]{43}$/.test(state) || !/^[A-Za-z0-9_-]{64}$/.test(verifier)) fail('local-oauth', 'Sesi otorisasi lokal tidak valid.');
  if (config.redirectUri && (config.redirectUri !== WEB_REDIRECT || port !== 8787)) fail('local-oauth', 'Port redirect OAuth Web tidak sesuai konfigurasi yang terdaftar.');
  const redirectUri = config.redirectUri || 'http://127.0.0.1:' + port + '/oauth2callback';
  const client = new OAuth2Client(config.clientId, config.clientSecret, redirectUri);
  const authorizationUrl = client.generateAuthUrl({access_type:'offline', prompt:'consent', login_hint:OWNER, scope:SCOPES, state,
    code_challenge:crypto.createHash('sha256').update(verifier).digest('base64url'), code_challenge_method:'S256'});
  return {client, redirectUri, authorizationUrl};
}
async function authorize(config, timeoutMs = 600000) {
  if (!Number.isInteger(timeoutMs) || timeoutMs < 10000 || timeoutMs > 900000) fail('timeout', 'Durasi otorisasi harus antara 10000 dan 900000 milidetik.');
  const state = crypto.randomBytes(32).toString('base64url'), verifier = crypto.randomBytes(48).toString('base64url');
  const authorizationFile = path.join(ARTIFACTS, 'authorization-url.json');
  let transaction, processing = false, timer, settle;
  const completed = new Promise((resolve, reject) => { settle = {resolve,reject}; });
  // Attach a handler before listen/file I/O, so callback errors are never unhandled.
  completed.catch(() => {});
  const server = http.createServer(async (request, response) => {
    const reply = (status, heading) => { response.writeHead(status, {'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','Referrer-Policy':'no-referrer','Content-Security-Policy':"default-src 'none'; style-src 'unsafe-inline'"}); response.end('<!doctype html><html lang="id"><meta name="viewport" content="width=device-width"><title>Jayaguna</title><body style="font:18px system-ui;padding:40px;color:#11265d"><h1>' + heading + '</h1><p>Kembali ke aplikasi Codex. Jangan salin kode atau token dari halaman Google.</p></body></html>'); };
    if (request.method !== 'GET' || request.url.length > 16384 || request.socket.remoteAddress !== '127.0.0.1') return reply(400, 'Permintaan tidak valid.');
    let callback;
    try { callback = new URL(request.url, transaction.redirectUri); } catch { return reply(400, 'Permintaan tidak valid.'); }
    if (callback.pathname !== new URL(transaction.redirectUri).pathname) return reply(404, 'Halaman tidak ditemukan.');
    if (!validState(callback.searchParams.get('state'), state)) return reply(400, 'Sesi persetujuan tidak cocok.');
    if (processing) return reply(409, 'Persetujuan sedang diproses.');
    processing = true;
    try {
      const code = callback.searchParams.get('code');
      if (callback.searchParams.has('error') || !code || code.length > 4096) fail('consent-denied', 'Persetujuan Google belum diselesaikan. Jalankan --authorize untuk mencoba lagi.');
      const {tokens} = await transaction.client.getToken({code, codeVerifier:verifier, redirect_uri:transaction.redirectUri});
      if (!tokens.id_token || !tokens.access_token) fail('identity-missing', 'Google belum memberikan identitas akun yang diperlukan. Ulangi persetujuan.');
      const ticket = await transaction.client.verifyIdToken({idToken:tokens.id_token, audience:config.clientId});
      requireOwner(ticket.getPayload(), config.clientId);
      const info = await transaction.client.getTokenInfo(tokens.access_token);
      saveGrant(tokens, info.scopes, config);
      reply(200, 'Otorisasi pengelolaan Apps Script berhasil.');
      settle.resolve({authorized:true, ownerEmail:OWNER, scopes:requireScopes(info.scopes), credentialStoredEncrypted:true});
    } catch (error) { reply(400, 'Otorisasi belum selesai. Periksa hasil pada Codex.'); settle.reject(error instanceof SetupError ? error : new SetupError('oauth-exchange', 'Otorisasi Google gagal. Mulai ulang --authorize; kode dan token tidak dicetak.')); }
  });
  try {
    await new Promise((resolve,reject) => { server.once('error',reject); server.listen(config.port || 0,'127.0.0.1',resolve); });
    const port = server.address().port;
    transaction = authorizationRequest(config, port, state, verifier);
    privateWrite(authorizationFile, {authorizationUrl:transaction.authorizationUrl, ownerEmail:OWNER, expiresAt:new Date(Date.now()+timeoutMs).toISOString()});
    console.log(JSON.stringify({waitingForOwnerConsent:true, port, authorizationUrlFile:path.relative(REPOSITORY,authorizationFile).replace(/\\/g,'/'), tokenPrinted:false}));
    timer = setTimeout(() => settle.reject(new SetupError('oauth-timeout', 'Waktu persetujuan Google habis. Mulai ulang --authorize bila siap.')), timeoutMs);
    return await completed;
  } finally {
    clearTimeout(timer);
    server.closeAllConnections?.();
    await new Promise(resolve => server.close(resolve));
    if (fs.existsSync(authorizationFile)) fs.unlinkSync(authorizationFile);
  }
}

function checkedId(value, kind) { if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{20,300}$/.test(value)) fail('deployment-id', 'ID ' + kind + ' pada konfigurasi lokal tidak valid.'); return value; }
function contentPayload(source, manifestSource) {
  let manifest;
  try { manifest = JSON.parse(manifestSource); } catch { fail('manifest', 'Manifest Apps Script tidak valid.'); }
  if (manifest.runtimeVersion !== 'V8' || manifest.webapp?.executeAs !== 'USER_DEPLOYING' || manifest.webapp?.access !== 'ANYONE_ANONYMOUS' ||
      JSON.stringify(normalizedScopes(manifest.oauthScopes)) !== JSON.stringify(normalizedScopes(RUNTIME_SCOPES))) fail('manifest', 'Manifest Apps Script tidak sesuai penghubung Drive perusahaan.');
  if (typeof source !== 'string' || !source.includes("projectId: 'jayagunatehnikperkasa'")) fail('source', 'Source penghubung tidak sesuai project Firebase.');
  return {files:[{name:'Code',type:'SERVER_JS',source:source.replace(/\r\n/g,'\n')},{name:'appsscript',type:'JSON',source:JSON.stringify(manifest,null,2)}]};
}
function contentHash(payload) {
  if (!Array.isArray(payload?.files)) return '';
  const files = payload.files.map(file => ({name:file.name,type:file.type,source:file.type === 'JSON' ? JSON.stringify(JSON.parse(file.source)) : String(file.source).replace(/\r\n/g,'\n')})).sort((a,b)=>a.name.localeCompare(b.name));
  return crypto.createHash('sha256').update(JSON.stringify(files)).digest('hex');
}
function deploymentPayload(versionNumber) {
  if (!Number.isSafeInteger(versionNumber) || versionNumber < 1) fail('version', 'Nomor versi Apps Script tidak valid.');
  return {versionNumber,manifestFileName:'appsscript',description:'Jayaguna Drive Bridge managed deployment'};
}
function safeApiError(status, body) {
  const info = body?.error, reasons = (info?.details || []).map(value => value.reason || '').concat(info?.status || '');
  const message = String(info?.message || '');
  if (reasons.some(value => ['SERVICE_DISABLED','GOOGLE_SERVICE_USER_DISABLED'].includes(value)) || /Apps Script API.*(?:not.*enabled|disabled)|has not enabled the Apps Script API/i.test(message)) {
    return new SetupError('apps-script-api-disabled', 'Aktifkan Apps Script API pada Cloud project OAuth dan izinkan API di https://script.google.com/home/usersettings, lalu ulangi.');
  }
  if (status === 401 || reasons.includes('ACCESS_TOKEN_SCOPE_INSUFFICIENT') || /insufficient.*scope/i.test(message)) return new SetupError('authorization-required', 'Otorisasi pengelolaan Apps Script perlu diperbarui. Jalankan --authorize.');
  if (status === 403) return new SetupError('apps-script-access', 'Google menolak pengelolaan Apps Script. Periksa izin API pada https://script.google.com/home/usersettings dan akun pemilik.');
  if (status === 404) return new SetupError('apps-script-not-found', 'Project atau deployment Apps Script tersimpan tidak ditemukan. Tidak ada project lain yang diubah.');
  return new SetupError('apps-script-api', 'Apps Script API belum menyelesaikan operasi (HTTP ' + status + '). Ulangi setelah memeriksa status; respons Google tidak dicetak.');
}
async function apiRequest(client, endpoint, method = 'GET', body, request = fetch) {
  if (!/^\/projects(?:\/[A-Za-z0-9_-]+(?:\/(?:content|versions|deployments(?:\/[A-Za-z0-9_-]+)?))?)?$/.test(endpoint)) fail('api-path', 'Alamat Apps Script API tidak valid.');
  let accessToken;
  try { accessToken = (await client.getAccessToken()).token; } catch { fail('authorization-required', 'Otorisasi pengelolaan Apps Script berakhir. Jalankan --authorize.'); }
  if (!accessToken) fail('authorization-required', 'Persetujuan pengelolaan Apps Script diperlukan.');
  let response;
  try { response = await request('https://script.googleapis.com/v1' + endpoint, {method, redirect:'error', signal:AbortSignal.timeout(45000), headers:{Authorization:'Bearer '+accessToken,'Content-Type':'application/json'}, ...(body ? {body:JSON.stringify(body)} : {})}); }
  catch { fail('apps-script-network', 'Koneksi Apps Script API terputus. Periksa status sebelum mengulangi operasi; token tidak dicetak.'); }
  const text = await response.text();
  if (text.length > 2000000) fail('apps-script-response', 'Respons Apps Script API terlalu besar.');
  let data;
  try { data = JSON.parse(text); } catch { fail('apps-script-response', 'Apps Script API belum memberikan JSON yang valid.'); }
  if (!response.ok) throw safeApiError(response.status,data);
  return data;
}
function assertProject(project) {
  if (project.title !== TITLE || project.creator?.email && project.creator.email.toLowerCase() !== OWNER) fail('wrong-project', 'Project tersimpan bukan Jayaguna Drive Bridge milik akun perusahaan. Tidak ada source yang diubah.');
}
function deploymentUrl(deployment) {
  const url = deployment.entryPoints?.find(entry => entry.entryPointType === 'WEB_APP')?.webApp?.url;
  if (!url) return null;
  const target = new URL(url);
  if (target.protocol !== 'https:' || target.hostname !== 'script.google.com' || target.username || target.password || target.port || target.search || target.hash || !/^\/macros\/s\/[A-Za-z0-9_-]{20,300}\/exec$/.test(target.pathname)) fail('deployment-url', 'Google belum memberikan URL Web app yang valid.');
  return url;
}
async function deploy({api, files, loadState, saveState}) {
  const hash = contentHash(files);
  let record = loadState() || {};
  if (!record.scriptId) {
    const created = await api('/projects','POST',{title:TITLE});
    checkedId(created.scriptId,'script'); assertProject(created);
    record = {scriptId:created.scriptId}; saveState(record);
  }
  const scriptId = checkedId(record.scriptId,'script'), base = '/projects/' + scriptId;
  assertProject(await api(base));
  const remote = await api(base+'/content');
  let existingDeployment;
  if (record.deploymentId) existingDeployment = await api(base+'/deployments/'+checkedId(record.deploymentId,'deployment'));
  if (existingDeployment && record.sourceHash === hash && contentHash(remote) === hash && existingDeployment.deploymentConfig?.versionNumber === record.versionNumber) {
    return {...record,webAppUrl:deploymentUrl(existingDeployment),changed:false,ownerAuthorizationStillRequired:true};
  }
  if (contentHash(remote) !== hash) await api(base+'/content','PUT',files);
  // Persist the version before deployment: if deployment fails, retry uses the
  // same immutable version rather than creating version after version.
  let version = record.pendingSourceHash === hash ? record.pendingVersion : null;
  if (!version) {
    const created = await api(base+'/versions','POST',{description:'Jayaguna bridge '+hash.slice(0,12)});
    version = created.versionNumber; deploymentPayload(version);
    record = {...record,pendingSourceHash:hash,pendingVersion:version}; saveState(record);
  }
  const config = deploymentPayload(version);
  const deployment = existingDeployment ? await api(base+'/deployments/'+checkedId(record.deploymentId,'deployment'),'PUT',{deploymentConfig:config}) : await api(base+'/deployments','POST',config);
  const deploymentId = checkedId(deployment.deploymentId,'deployment');
  const next = {scriptId,deploymentId,versionNumber:version,sourceHash:hash,webAppUrl:deploymentUrl(deployment),updatedAt:new Date().toISOString()};
  saveState(next);
  return {...next,changed:true,ownerAuthorizationStillRequired:true};
}
async function main() {
  const args = process.argv.slice(2), value = name => { const index=args.indexOf(name); return index < 0 ? undefined : args[index+1]; };
  const modes = ['--authorize','--deploy','--status'].filter(mode=>args.includes(mode));
  if (modes.length !== 1 || !value('--oauth-client')) fail('arguments', 'Gunakan --authorize, --deploy, atau --status dengan --oauth-client PATH_JSON_OAUTH.');
  const config = clientConfig(path.resolve(value('--oauth-client')));
  if (modes[0] === '--authorize') { console.log(JSON.stringify(await authorize(config, value('--timeout-ms') ? Number(value('--timeout-ms')) : 600000))); return; }
  const grant = loadGrant(config), client = new OAuth2Client(config.clientId,config.clientSecret);
  client.setCredentials({refresh_token:grant.refreshToken});
  const recordFile = path.join(ARTIFACTS,'deployment.json');
  const api = (endpoint,method,body) => apiRequest(client,endpoint,method,body);
  const record = fs.existsSync(recordFile) ? readJson(recordFile) : null;
  if (modes[0] === '--status') {
    if (!record?.scriptId) { console.log(JSON.stringify({authorized:true,ownerEmail:OWNER,scopes:grant.scopes,scriptCreated:false})); return; }
    const scriptId=checkedId(record.scriptId,'script'); assertProject(await api('/projects/'+scriptId));
    const deployment=record.deploymentId ? await api('/projects/'+scriptId+'/deployments/'+checkedId(record.deploymentId,'deployment')) : null;
    console.log(JSON.stringify({authorized:true,ownerEmail:OWNER,scriptId,editorUrl:'https://script.google.com/home/projects/'+scriptId+'/edit',deploymentId:record.deploymentId || null,versionNumber:deployment?.deploymentConfig?.versionNumber || null,webAppUrl:deployment ? deploymentUrl(deployment) : null,runtimeAuthorizationVerified:false})); return;
  }
  const files = contentPayload(fs.readFileSync(path.join(REPOSITORY,'apps-script/Code.gs'),'utf8'),fs.readFileSync(path.join(REPOSITORY,'apps-script/appsscript.json'),'utf8'));
  const result = await deploy({api,files,loadState:()=>record,saveState:state=>privateWrite(recordFile,state)});
  console.log(JSON.stringify({...result,editorUrl:'https://script.google.com/home/projects/'+result.scriptId+'/edit',stage:'deployed-awaiting-owner-runtime-authorization',tokenPrinted:false}));
}
module.exports = {OWNER,TITLE,SCOPES,clientConfig,requireOwner,requireScopes,encryptedGrant,decryptedGrant,saveGrant,loadGrant,validState,authorizationRequest,contentPayload,contentHash,deploymentPayload,safeApiError,apiRequest,assertProject,deploymentUrl,deploy};
if (require.main === module) main().catch(error=>{console.error(JSON.stringify({completed:false,code:error instanceof SetupError ? error.safeCode : 'local-setup',message:error instanceof SetupError ? error.message : 'Penyiapan Apps Script belum selesai. Periksa file lokal dan koneksi; credential dan token tidak dicetak.'}));process.exitCode=1;});
