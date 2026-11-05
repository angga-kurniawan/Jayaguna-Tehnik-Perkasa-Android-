// Local OWNER-only maintenance. Never run this script from the APK or a public web route.
const {createServer}=require('node:http');
const {spawn}=require('node:child_process');
const fs=require('node:fs');
const path=require('node:path');
class OperatorError extends Error {}
const CALLBACK='/api/drive/oauth/callback';

function validateLoopbackConfig(config) {
  let redirect;
  try {redirect=new URL(config.redirectUri);} catch {throw new OperatorError('Konfigurasi callback OAuth lokal tidak valid.');}
  if (redirect.protocol!=='http:' || !['localhost','127.0.0.1'].includes(redirect.hostname) || redirect.port!=='8787' ||
      redirect.pathname!==CALLBACK || redirect.username || redirect.password || redirect.search || redirect.hash || config.origin!==redirect.origin) {
    throw new OperatorError('Tool lokal memerlukan callback Web OAuth terdaftar http://localhost:8787/api/drive/oauth/callback (atau 127.0.0.1) dan origin backend lokal yang sama.');
  }
  return redirect;
}

function authorizationState(value) {
  let url;
  try {url=new URL(value);} catch {throw new OperatorError('Tautan otorisasi Google tidak valid.');}
  const state=url.searchParams.get('state');
  if (url.protocol!=='https:' || url.hostname!=='accounts.google.com' || url.username || url.password ||
      url.searchParams.getAll('state').length!==1 || !/^[A-Za-z0-9_-]{40,100}$/.test(state || '')) throw new OperatorError('Tautan otorisasi Google tidak valid.');
  return state;
}

async function ownerActor(config,auth,db,assertOwner,now=Date.now) {
  let user,profile;
  try {
    user=await auth.getUserByEmail(config.accountEmail);
    profile=(await db.doc('users/'+user.uid).get()).data();
    const authTime=Math.floor(now()/1000);
    if (user.email?.toLowerCase()!==config.accountEmail || profile && (
      Object.hasOwn(profile,'uid') && profile.uid!==user.uid || Object.hasOwn(profile,'email') &&
        (typeof profile.email!=='string' || profile.email.toLowerCase()!==config.accountEmail))) throw new Error('identity');
    assertOwner(profile,user,authTime);
    return {uid:user.uid,name:typeof profile.name==='string'?profile.name:config.accountEmail,email:config.accountEmail,role:'OWNER',authTime};
  } catch {throw new OperatorError('Akun utama harus memiliki profil UID OWNER yang disetujui dan aktif di Firebase. Profil lama tanpa status juga memerlukan claim OWNER existing. Tool ini tidak membuat atau menyetujui akun.');}
}

async function listenForCallback(redirect,state,complete,{timeoutMs=10*60*1000,listenPort=8787}={}) {
  let resolve,reject,busy=false,timer;
  const completion=new Promise((yes,no)=>{resolve=yes;reject=no;});
  completion.catch(()=>{});
  const server=createServer(async(request,response)=>{
    response.setHeader('Cache-Control','no-store');response.setHeader('Referrer-Policy','no-referrer');
    response.setHeader('X-Content-Type-Options','nosniff');response.setHeader('Content-Security-Policy',"default-src 'none'; frame-ancestors 'none'");
    response.setHeader('Connection','close');
    let url;
    try {url=new URL(request.url || '/',redirect.origin);} catch {response.writeHead(400);response.end();return;}
    const port=server.address()?.port;
    if (request.method!=='GET' || url.pathname!==CALLBACK || request.headers.origin ||
        !['localhost:'+port,'127.0.0.1:'+port].includes((request.headers.host || '').toLowerCase())) {response.writeHead(404);response.end();return;}
    if (url.searchParams.getAll('state').length!==1 || url.searchParams.get('state')!==state || busy) {response.writeHead(400);response.end('Permintaan tidak valid.');return;}
    const code=url.searchParams.get('code');
    if (!url.searchParams.has('error') && (!code || code.length>4096 || url.searchParams.getAll('code').length!==1)) {response.writeHead(400);response.end('Permintaan tidak valid.');return;}
    busy=true;
    let success=false;
    try {if (!url.searchParams.has('error')) {await complete(state,code);success=true;}} catch { /* Never log code, query string, tokens or upstream errors. */ }
    response.writeHead(success?200:400,{'Content-Type':'text/html; charset=utf-8'});
    response.end(success?'<p>Drive perusahaan sudah dihubungkan. Tutup tab ini.</p>':'<p>Persetujuan belum berhasil. Tutup tab ini dan jalankan tool kembali.</p>',()=>{
      clearTimeout(timer);server.close();success?resolve():reject(new OperatorError('Persetujuan Drive belum berhasil. Mulai ulang tool dan pilih akun utama.'));
    });
  });
  server.requestTimeout=15_000;server.headersTimeout=5_000;server.keepAliveTimeout=1_000;
  await new Promise((yes,no)=>{server.once('error',no);server.listen(listenPort,'127.0.0.1',yes);});
  timer=setTimeout(()=>{server.closeAllConnections();server.close();reject(new OperatorError('Waktu persetujuan berakhir. Jalankan tool kembali.'));},timeoutMs);
  return {port:server.address().port,completion,close:()=>{clearTimeout(timer);server.closeAllConnections();server.close();}};
}

function writePrivateAuthorization(root,authorizationUrl) {
  fs.mkdirSync(root,{recursive:true,mode:0o700});
  const directory=fs.mkdtempSync(path.join(root,'owner-drive-'));
  fs.chmodSync(directory,0o700);
  const file=path.join(directory,'authorization.json');
  fs.writeFileSync(file,JSON.stringify({authorizationUrl,expiresAt:new Date(Date.now()+10*60*1000).toISOString()},null,2)+'\n',{mode:0o600,flag:'wx'});
  return file;
}

function openBrowser(url,platform=process.platform) {
  const command=platform==='darwin'?['open',[url]]:platform==='win32'?['rundll32.exe',['url.dll,FileProtocolHandler',url]]:['xdg-open',[url]];
  return new Promise(resolve=>{
    const child=spawn(command[0],command[1],{shell:false,stdio:'ignore'});
    child.once('error',()=>resolve(false));child.once('exit',code=>resolve(code===0));
  });
}

async function main(args=process.argv.slice(2)) {
  if (args.some(arg=>arg!=='--open')) throw new OperatorError('Gunakan npm run connect:company-drive -- [--open].');
  const {centralDriveConfig}=require('../lib/drive/config.js');
  const {oauthStart,oauthComplete,assertOAuthOwner}=require('../lib/drive/oauth.js');
  const {getApps,initializeApp}=require('firebase-admin/app');
  const {getAuth}=require('firebase-admin/auth');
  const {getFirestore}=require('firebase-admin/firestore');
  const {serverAppOptions}=require('../lib/server-config.js');
  const config=centralDriveConfig(),redirect=validateLoopbackConfig(config);
  if (!getApps().length) initializeApp(serverAppOptions());
  const actor=await ownerActor(config,getAuth(),getFirestore(),assertOAuthOwner);
  const {authorizationUrl}=await oauthStart(actor),state=authorizationState(authorizationUrl);
  const listener=await listenForCallback(redirect,state,oauthComplete);
  let file;
  try {
    file=writePrivateAuthorization(path.resolve(__dirname,'../../.artifacts/drive-connect'),authorizationUrl);
    console.log('Tautan persetujuan privat tersimpan di '+file);
    console.log('Buka authorizationUrl dari berkas tersebut dan pilih akun Google Drive utama. Callback hanya berjalan di laptop ini selama 10 menit.');
    if (args.includes('--open') && !await openBrowser(authorizationUrl)) console.log('Browser belum terbuka. Buka tautan dari berkas privat secara manual.');
    await listener.completion;
    console.log('Drive perusahaan telah terhubung. Aplikasi memakai sambungan pusat ini.');
  } finally {listener.close();if(file) fs.rmSync(file,{force:true});}
}

module.exports={validateLoopbackConfig,authorizationState,ownerActor,listenForCallback,writePrivateAuthorization};
if(require.main===module) main().catch(error=>{
  console.error(error instanceof OperatorError?error.message:'Sambungan Drive gagal. Periksa build backend, kredensial server, konfigurasi OAuth lokal, dan profil OWNER. Detail rahasia tidak dicetak.');
  process.exitCode=1;
});
