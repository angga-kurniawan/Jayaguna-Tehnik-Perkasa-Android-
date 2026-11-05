// Trusted operator tool. Uses the existing Firebase CLI login in memory.
// No service-account key, password, refresh token, or reset link is written.
const path = require('node:path');
const fs = require('node:fs');
const {execFileSync} = require('node:child_process');
const {randomBytes} = require('node:crypto');
const {initializeApp, deleteApp} = require('firebase-admin/app');
const {getAuth} = require('firebase-admin/auth');
const {Firestore, FieldValue} = require('@google-cloud/firestore');
const {GoogleAuth, OAuth2Client} = require('google-auth-library');

async function main() {
  const args = process.argv.slice(2);
  const option = key => args[args.indexOf(key) + 1];
  const project = args.includes('--project') ? option('--project') : '';
  const email = args.includes('--email') ? option('--email')?.trim().toLowerCase() : '';
  const name = args.includes('--name') ? option('--name')?.trim() : 'Owner Jayaguna';
  const apply = args.includes('--apply');
  if (project !== 'jayagunatehnikperkasa' || !email || !email.includes('@') || !name) throw new Error('Gunakan --project jayagunatehnikperkasa --email EMAIL_OWNER --name "Nama Owner" [--apply].');
  for (const key of ['FIRESTORE_EMULATOR_HOST','FIREBASE_AUTH_EMULATOR_HOST','FIREBASE_STORAGE_EMULATOR_HOST']) {
    if (process.env[key]) throw new Error('Bootstrap cloud menolak konfigurasi emulator.');
  }
  const windowsCli = process.env.APPDATA && path.join(process.env.APPDATA, 'npm/node_modules/firebase-tools/lib');
  const cli = process.env.FIREBASE_TOOLS_ROOT || (windowsCli && fs.existsSync(windowsCli) ? windowsCli : path.join(execFileSync('npm', ['root','-g'], {encoding:'utf8'}).trim(), 'firebase-tools/lib'));
  const account = require(path.join(cli, 'auth.js')).getGlobalDefaultAccount();
  if (!account) throw new Error('Jalankan firebase login terlebih dahulu.');
  if (account.user.email?.toLowerCase() !== email) throw new Error('Bootstrap ini hanya membuat OWNER dengan email akun operator Firebase yang sedang login.');
  await require(path.join(cli, 'requireAuth.js')).requireAuth({project, user:account.user, tokens:account.tokens, nonInteractive:true});
  const cliApi = require(path.join(cli, 'apiv2.js'));
  const oauth = new OAuth2Client();
  oauth.setCredentials({access_token:await cliApi.getAccessToken(), expiry_date:Date.now()+45*60*1000});
  const googleAuth = new GoogleAuth({projectId:project, authClient:oauth});
  const db = new Firestore({projectId:project, auth:googleAuth});
  const app = initializeApp({projectId:project, credential:{getAccessToken:async()=>({access_token:await cliApi.getAccessToken(), expires_in:2700})}}, 'owner-cli-bootstrap');
  const auth = getAuth(app);
  try {
    const lockRef = db.doc('system/bootstrapOwner');
    const [lock, owners] = await Promise.all([lockRef.get(), db.collection('users').where('role','==','OWNER').limit(2).get()]);
    let user;
    try { user = await auth.getUserByEmail(email); }
    catch (error) { if (error.code !== 'auth/user-not-found') throw error; }
    const ownerProfile = user && owners.docs.find(doc=>doc.id===user.uid);
    console.log(JSON.stringify({project, operatorEmail:account.user.email, mode:apply?'apply':'inspect', authUserExists:!!user,
      authRole:user?.customClaims?.role || null, authDisabled:user?.disabled ?? null, profileActive:ownerProfile?.get('active') ?? null,
      ownerCount:owners.size, bootstrapLocked:lock.exists}));
    if (!apply) return;
    if (lock.exists || !owners.empty) {
      if (lock.get('completed') === true && owners.size === 1 && owners.docs[0].get('email') === email && user?.customClaims?.role === 'OWNER') {
        console.log(JSON.stringify({result:'OWNER sudah tersedia; tidak ada perubahan.',uid:user.uid,email})); return;
      }
      throw new Error('OWNER atau lock bootstrap sudah ada. Periksa keadaan akun sebelum pemulihan manual.');
    }
    await db.runTransaction(async tx => {
      const [existingLock, existingOwners] = await Promise.all([tx.get(lockRef), tx.get(db.collection('users').where('role','==','OWNER').limit(1))]);
      if (existingLock.exists || !existingOwners.empty) throw new Error('Bootstrap telah dimulai operator lain.');
      tx.create(lockRef, {email, operatorEmail:account.user.email, lockedAt:FieldValue.serverTimestamp(), completed:false});
    });
    if (!user) user = await auth.createUser({email,displayName:name,disabled:true,password:randomBytes(48).toString('base64url')});
    else await auth.updateUser(user.uid,{disabled:true,displayName:name});
    await auth.setCustomUserClaims(user.uid,{...user.customClaims,role:'OWNER'});
    const batch = db.batch();
    batch.create(db.doc(`users/${user.uid}`),{uid:user.uid,email,name,role:'OWNER',active:true,version:1,revokedBeforeSeconds:0,createdAt:FieldValue.serverTimestamp(),updatedAt:FieldValue.serverTimestamp()});
    const schemaRef = db.doc('system/schema');
    if (!(await schemaRef.get()).exists) batch.create(schemaRef,{
      schemaVersion:1,projectId:project,environment:'production',initializedAt:FieldValue.serverTimestamp(),
      collections:['products','partners','publicProducts','publicPartners','users','activityLogs','operationReceipts','uploadSessions','system'],
    });
    batch.update(lockRef,{completed:true,uid:user.uid,completedAt:FieldValue.serverTimestamp()});
    await batch.commit();
    await auth.updateUser(user.uid,{disabled:false});
    const verified = await auth.getUser(user.uid);
    if (verified.customClaims?.role !== 'OWNER' || verified.disabled) throw new Error('Verifikasi OWNER tidak berhasil; periksa Auth dan profil.');
    console.log(JSON.stringify({result:'OWNER dibuat dan diverifikasi',uid:user.uid,email,role:verified.customClaims.role,active:true,passwordSetup:'Gunakan Lupa kata sandi pada aplikasi.'}));
  } finally { await db.terminate(); await deleteApp(app); }
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});
