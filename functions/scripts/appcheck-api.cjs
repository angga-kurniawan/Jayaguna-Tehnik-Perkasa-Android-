// Enable only App Check for the fixed production project. Billing is unchanged.
const path = require('node:path');
const project = 'jayagunatehnikperkasa';
async function main() {
  const cli = process.env.FIREBASE_TOOLS_ROOT || path.join(process.env.APPDATA,'npm/node_modules/firebase-tools/lib');
  const account = require(path.join(cli,'auth.js')).getGlobalDefaultAccount();
  if (!account) throw new Error('Jalankan firebase login terlebih dahulu.');
  await require(path.join(cli,'requireAuth.js')).requireAuth({project,user:account.user,tokens:account.tokens,nonInteractive:true});
  const {Client} = require(path.join(cli,'apiv2.js'));
  const client = new Client({urlPrefix:'https://serviceusage.googleapis.com',apiVersion:'v1'});
  const endpoint = '/projects/889896586147/services/firebaseappcheck.googleapis.com';
  const current = await client.get(endpoint);
  console.log(JSON.stringify({project,service:'firebaseappcheck.googleapis.com',state:current.body.state}));
  if (current.body.state === 'ENABLED' || !process.argv.includes('--apply')) return;
  let operation = (await client.post(endpoint+':enable',{})).body;
  for (let attempt=0; !operation.done && attempt<12; attempt++) {
    await new Promise(resolve=>setTimeout(resolve,3000));
    operation = (await client.get('/'+operation.name)).body;
  }
  if (operation.error) throw new Error(operation.error.message || 'Aktivasi API gagal.');
  const after = await client.get(endpoint);
  console.log(JSON.stringify({project,service:'firebaseappcheck.googleapis.com',state:after.body.state,billingChanged:false}));
  if (after.body.state !== 'ENABLED') throw new Error('Aktivasi masih diproses. Periksa ulang status API.');
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});
