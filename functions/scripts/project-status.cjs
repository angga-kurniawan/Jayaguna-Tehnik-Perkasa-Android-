// Read-only inspection using the operator's existing Firebase CLI session.
// Tokens remain in memory and are never printed or copied into project files.
const path = require('node:path');
const fs = require('node:fs');
const {createHash} = require('node:crypto');
const project = 'jayagunatehnikperkasa';
const projectNumber = '889896586147';
const appId = '1:889896586147:android:d8ae90ae78cf46963ea816';

async function main() {
  const cli = process.env.FIREBASE_TOOLS_ROOT || (process.env.APPDATA && path.join(process.env.APPDATA, 'npm/node_modules/firebase-tools/lib'));
  if (!cli || !fs.existsSync(cli)) throw new Error('Firebase CLI tidak ditemukan; atur FIREBASE_TOOLS_ROOT ke folder lib firebase-tools.');
  const account = require(path.join(cli, 'auth.js')).getGlobalDefaultAccount();
  if (!account) throw new Error('Jalankan firebase login terlebih dahulu.');
  await require(path.join(cli, 'requireAuth.js')).requireAuth({project, user:account.user, tokens:account.tokens, nonInteractive:true});
  const {Client} = require(path.join(cli, 'apiv2.js'));
  const read = async (label, origin, version, endpoint, summarize, options) => {
    try {
      const response = await new Client({urlPrefix:origin,apiVersion:version}).get(endpoint,options || {});
      const result = await summarize(response.body);
      console.log(JSON.stringify({label,...result}));
    } catch (error) {
      console.log(JSON.stringify({label,error:error.message?.slice(0,650)}));
    }
  };
  console.log(JSON.stringify({project,operatorEmail:account.user.email,readOnly:true}));
  await read('billing','https://cloudbilling.googleapis.com','v1',`/projects/${project}/billingInfo`,v=>({billingEnabled:v.billingEnabled === true}));
  await read('database','https://firestore.googleapis.com','v1',`/projects/${project}/databases/(default)`,v=>({name:v.name,location:v.locationId,type:v.type}));
  await read('indexes','https://firestore.googleapis.com','v1',`/projects/${project}/databases/(default)/collectionGroups/-/indexes`,v=>({count:v.indexes?.length || 0,states:(v.indexes || []).reduce((all,index)=>{all[index.state]=(all[index.state]||0)+1;return all;},{})}));
  await read('storage','https://storage.googleapis.com','storage/v1','/b',v=>({buckets:(v.items || []).map(b=>({name:b.name,location:b.location}))}),{queryParams:{project}});
  await read('functions','https://cloudfunctions.googleapis.com','v2',`/projects/${project}/locations/-/functions`,v=>({functions:(v.functions || []).map(f=>({name:f.name,state:f.state}))}));
  await read('appCheck','https://firebaseappcheck.googleapis.com','v1',`/projects/${projectNumber}/services`,v=>({services:(v.services || []).map(s=>({name:s.name,enforcementMode:s.enforcementMode}))}));
  await read('appCheckDebug','https://firebaseappcheck.googleapis.com','v1',`/projects/${projectNumber}/apps/${appId}/debugTokens`,v=>({registrations:(v.debugTokens || []).map(t=>({name:t.name,displayName:t.displayName}))}));
  await read('firestoreRules','https://firebaserules.googleapis.com','v1',`/projects/${project}/releases/cloud.firestore`,async release=>{
    const response = await new Client({urlPrefix:'https://firebaserules.googleapis.com',apiVersion:'v1'}).get('/'+release.rulesetName,{skipLog:{resBody:true}});
    const source = response.body.source?.files?.find(f=>f.name==='firestore.rules')?.content;
    const local = fs.readFileSync(path.resolve(__dirname,'../../firestore.rules'),'utf8');
    const normalized = value=>value.replace(/\r\n/g,'\n').trim();
    return {ruleset:release.rulesetName,matchesLocal:typeof source==='string'&&normalized(source)===normalized(local),sha256:typeof source==='string'?createHash('sha256').update(normalized(source)).digest('hex'):null};
  });
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});
