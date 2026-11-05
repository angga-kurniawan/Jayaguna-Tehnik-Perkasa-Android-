// Register only the installed development APK's existing App Check identity.
// The secret stays in process memory; it is never printed or written to disk.
const path = require('node:path');
const fs = require('node:fs');
const {execFileSync} = require('node:child_process');
const {DebugDeviceError,adbFailure,exchangeToken,registerIdentity}=require('./debug-device-registration.cjs');
const project = 'jayagunatehnikperkasa';
const projectNumber = '889896586147';
const appId = '1:889896586147:android:d8ae90ae78cf46963ea816';

async function main() {
  const args = process.argv.slice(2);
  const deviceIndex = args.indexOf('--device');
  const device = deviceIndex >= 0 ? args[deviceIndex+1] : 'emulator-5554';
  if (!device || !/^[A-Za-z0-9._:-]+$/.test(device)) throw new DebugDeviceError('device-id','ID perangkat tidak valid. Pilih serial dari adb devices.');
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || path.join(process.env.LOCALAPPDATA || '', 'Android/Sdk');
  const adb = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  if (!fs.existsSync(adb)) throw new DebugDeviceError('adb-missing','ADB tidak ditemukan. Atur ANDROID_HOME ke folder Android SDK.');
  const exec = adbArgs=>{
    try{return execFileSync(adb,['-s',device,...adbArgs],{encoding:'utf8',maxBuffer:1024*1024,stdio:['ignore','pipe','pipe']});}
    catch(error){throw adbFailure(error);}
  };
  const prefs = exec(['shell','run-as','com.jayaguna.admin','ls','shared_prefs']).split(/\r?\n/).map(v=>v.trim());
  const encodedAppId = Buffer.from(appId).toString('base64').replace(/=+$/,'');
  const file = prefs.find(v=>v.startsWith('com.google.firebase.appcheck.debug.store.W0RFRkFVTFRd+') && v.includes(encodedAppId) && v.endsWith('.xml'));
  if (!file) throw new DebugDeviceError('identity-missing','Buka APK debug project asli pada perangkat dan coba login sekali agar SDK membuat identitas App Check, lalu ulangi perintah ini.');
  const xml = exec(['shell','run-as','com.jayaguna.admin','cat','shared_prefs/'+file]);
  const token = xml.match(/<string\b[^>]*>\s*([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\s*<\/string>/i)?.[1];
  if (!token) throw new DebugDeviceError('identity-missing','SDK belum menghasilkan identitas App Check debug. Buka aplikasi dan coba login sekali, lalu ulangi perintah ini.');
  const cli = process.env.FIREBASE_TOOLS_ROOT || (process.env.APPDATA && path.join(process.env.APPDATA,'npm/node_modules/firebase-tools/lib'));
  if (!cli) throw new DebugDeviceError('operator-cli','Atur FIREBASE_TOOLS_ROOT ke folder lib firebase-tools.');
  const account = require(path.join(cli,'auth.js')).getGlobalDefaultAccount();
  if (!account) throw new DebugDeviceError('operator-login','Jalankan firebase login dengan akun operator project terlebih dahulu.');
  await require(path.join(cli,'requireAuth.js')).requireAuth({project,user:account.user,tokens:account.tokens,nonInteractive:true});
  const {Client} = require(path.join(cli,'apiv2.js'));
  const client = new Client({urlPrefix:'https://firebaseappcheck.googleapis.com',apiVersion:'v1'});
  const config=JSON.parse(fs.readFileSync(path.resolve(__dirname,'../../app/google-services.json'),'utf8'));
  const android=config.client?.find(v=>v.client_info?.mobilesdk_app_id===appId),apiKey=android?.api_key?.[0]?.current_key;
  if(config.project_info?.project_id!==project || !apiKey)throw new DebugDeviceError('configuration','Konfigurasi Android tidak cocok dengan project asli.');
  const endpoint = `/projects/${projectNumber}/apps/${appId}/debugTokens`;
  const result=await registerIdentity({token,apply:args.includes('--apply'),verify:args.includes('--verify'),
    exchange:()=>exchangeToken({token,apiKey,projectNumber,appId}),
    list:async()=>{
      const registrations=[];let pageToken;
      do{
        const response=await client.get(endpoint,{queryParams:pageToken?{pageToken}:{},skipLog:{resBody:true}});
        registrations.push(...(response.body.debugTokens || []));pageToken=response.body.nextPageToken;
      }while(pageToken);
      return registrations;
    },
    create:async record=>(await client.post(endpoint,record,{skipLog:{reqBody:true,resBody:true}})).body,
  });
  console.log(JSON.stringify({project,device,registered:result.registered,changed:result.changed,appCheckTokenVerified:result.verified,
    ...(result.name?{name:result.name}:{}),...(result.ttl?{ttl:result.ttl}:{}),secretPrinted:false}));
}
main().catch(error=>{
  console.error(error instanceof DebugDeviceError?error.message:'Pengaturan App Check belum selesai. Periksa ADB, APK debug, konfigurasi Firebase dan login operator; secret tidak dicetak.');
  process.exitCode=1;
});
