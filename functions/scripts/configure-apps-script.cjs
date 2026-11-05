// Publishes only the non-secret Google Apps Script endpoint after a health check.
'use strict';
const PROJECT_ID = 'jayagunatehnikperkasa';
function checkedUrl(input) {
  let url;
  try { url = new URL(input); } catch { throw new Error('URL Apps Script tidak valid.'); }
  if (url.protocol !== 'https:' || url.hostname !== 'script.google.com' || url.port || url.username || url.password ||
      url.search || url.hash || !/^\/macros\/s\/[A-Za-z0-9_-]{20,240}\/exec$/.test(url.pathname)) {
    throw new Error('Gunakan URL deployment https://script.google.com/macros/s/ID/exec.');
  }
  return url.href;
}
async function checkHealth(input, request = fetch) {
  let url = checkedUrl(input);
  for (let hop = 0; hop < 4; hop++) {
    const response = await request(url, {method:'GET', redirect:'manual', signal:AbortSignal.timeout(30000)});
    if ([302,303].includes(response.status)) {
      const next = new URL(response.headers.get('location') || '', url);
      if (next.protocol !== 'https:' || next.hostname !== 'script.googleusercontent.com' || next.port || next.username || next.password ||
          next.pathname !== '/macros/echo' || next.hash) throw new Error('Deployment belum publik atau redirect Google tidak valid.');
      url = next.href;
      continue;
    }
    if (!response.ok || !response.headers.get('content-type')?.toLowerCase().startsWith('application/json')) {
      throw new Error('Deployment belum mengembalikan JSON. Pilih Web app, Execute as Me, akses Anyone.');
    }
    const body = await response.text();
    if (body.length > 4096) throw new Error('Respons health tidak valid.');
    let health;
    try { health = JSON.parse(body); } catch { throw new Error('Respons health bukan JSON.'); }
    if (health.service !== 'jayaguna-google-bridge' || health.projectId !== PROJECT_ID || health.version !== 1) {
      throw new Error('Deployment bukan penghubung Jayaguna untuk project ini.');
    }
    return health;
  }
  throw new Error('Redirect Google terlalu banyak.');
}
async function main() {
  const args = process.argv.slice(2), position = args.indexOf('--url');
  if (position < 0 || !args[position+1]) throw new Error('Gunakan --url URL_DEPLOYMENT_EXEC.');
  if (['FIRESTORE_EMULATOR_HOST','FIREBASE_AUTH_EMULATOR_HOST'].some(key => process.env[key])) throw new Error('Konfigurasi ini hanya untuk Firebase asli.');
  const url = checkedUrl(args[position+1]);
  await checkHealth(url);
  if (!args.includes('--apply')) {
    console.log(JSON.stringify({checked:true, project:PROJECT_ID, changed:false, next:'Tambahkan --apply untuk menyimpan URL yang telah diperiksa.'}));
    return;
  }
  const {initializeApp,deleteApp} = require('firebase-admin/app');
  const {getFirestore,FieldValue} = require('firebase-admin/firestore');
  const {serverAppOptions} = require('../lib/server-config');
  const options = serverAppOptions();
  if (options.projectId !== PROJECT_ID) throw new Error('Project Firebase tidak cocok.');
  const app = initializeApp(options);
  try {
    await getFirestore(app).doc('appConfig/integrations').set({projectId:PROJECT_ID, version:1, driveAppsScriptUrl:url, updatedAt:FieldValue.serverTimestamp()});
    console.log(JSON.stringify({configured:true, project:PROJECT_ID, endpointPublished:true, credentialsStored:false}));
  } finally { await deleteApp(app); }
}
module.exports = {checkedUrl,checkHealth};
if (require.main === module) main().catch(error => {
  // Deliberately omit upstream responses, headers and credential values.
  const safe = error?.message?.startsWith('Gunakan ') || error?.message?.startsWith('Deployment ') || error?.message?.startsWith('Konfigurasi ') || error?.message === 'Project Firebase tidak cocok.';
  console.error(safe ? error.message : 'Konfigurasi gagal. Periksa URL deployment, koneksi dan akses Firebase Admin. Rahasia tidak dicetak.');
  process.exitCode = 1;
});
