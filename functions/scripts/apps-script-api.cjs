// Only the existing OAuth project's Apps Script management API is enabled.
// This does not change billing, Firebase plans, or existing Drive authorization.
'use strict';
const path = require('node:path');
const PROJECT = 'jayagunatehnikperkasa-512819';
const PROJECT_NUMBER = '179144038580';
async function main() {
  const cli = process.env.FIREBASE_TOOLS_ROOT || path.join(process.env.APPDATA, 'npm/node_modules/firebase-tools/lib');
  const account = require(path.join(cli, 'auth.js')).getGlobalDefaultAccount();
  if (!account || account.user.email !== 'mtahakurniawan@gmail.com') throw new Error('operator-required');
  await require(path.join(cli, 'requireAuth.js')).requireAuth({project: PROJECT, user: account.user, tokens: account.tokens, nonInteractive: true});
  const {Client} = require(path.join(cli, 'apiv2.js'));
  const client = new Client({urlPrefix:'https://serviceusage.googleapis.com', apiVersion:'v1'});
  const endpoint = `/projects/${PROJECT_NUMBER}/services/script.googleapis.com`;
  let service = (await client.get(endpoint, {skipLog:{resBody:true}})).body;
  console.log(JSON.stringify({project:PROJECT, service:'script.googleapis.com', state:service.state, billingChanged:false}));
  if (service.state === 'ENABLED' || !process.argv.includes('--apply')) return;
  let operation = (await client.post(endpoint+':enable', {}, {skipLog:{resBody:true}})).body;
  for (let attempt=0; !operation.done && attempt<15; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 2000));
    operation = (await client.get('/'+operation.name, {skipLog:{resBody:true}})).body;
  }
  if (operation.error) throw new Error('enable-failed');
  service = (await client.get(endpoint, {skipLog:{resBody:true}})).body;
  console.log(JSON.stringify({project:PROJECT, service:'script.googleapis.com', state:service.state, billingChanged:false}));
  if (service.state !== 'ENABLED') throw new Error('enable-pending');
}
main().catch(() => {
  console.error('Akses Apps Script API belum siap. Periksa login operator dan izin Google Cloud project OAuth; tidak ada secret dicetak.');
  process.exitCode=1;
});
