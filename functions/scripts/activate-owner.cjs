// One-time local operator helper. Opens the official Firebase password setup page;
// never sends mail, changes a password itself, or prints/stores the reset URL.
const {initializeApp,deleteApp}=require('firebase-admin/app');
const {getAuth}=require('firebase-admin/auth');
const {getFirestore}=require('firebase-admin/firestore');
const {execFile}=require('node:child_process');
let app;
(async()=>{
  if(process.platform!=='win32' || ['FIRESTORE_EMULATOR_HOST','FIREBASE_AUTH_EMULATOR_HOST'].some(key=>process.env[key]))throw new Error('local-windows-cloud-only');
  if(process.env.FIREBASE_PROJECT_ID!=='jayagunatehnikperkasa')throw new Error('wrong-project');
  app=initializeApp({projectId:process.env.FIREBASE_PROJECT_ID});
  const auth=getAuth(app),owner=await auth.getUserByEmail('mtahakurniawan@gmail.com');
  const profile=await getFirestore(app).doc('users/'+owner.uid).get();
  if(owner.disabled || owner.customClaims?.role!=='OWNER' || !profile.get('active') || profile.get('role')!=='OWNER')throw new Error('owner-not-active');
  const link=await auth.generatePasswordResetLink(owner.email),url=new URL(link);
  if(url.protocol!=='https:' || url.hostname!=='jayagunatehnikperkasa.firebaseapp.com' || url.searchParams.get('mode')!=='resetPassword')throw new Error('unexpected-handler');
  await new Promise((resolve,reject)=>execFile('rundll32.exe',['url.dll,FileProtocolHandler',link],{windowsHide:true},error=>error?reject(error):resolve()));
  console.log(JSON.stringify({activationPageOpened:true,email:owner.email,passwordChangedByScript:false,emailSent:false,secretPrinted:false}));
})().catch(()=>{console.error('Halaman aktivasi belum dapat dibuka. Periksa Firebase Admin, akun OWNER dan browser Windows. Token tidak dicetak.');process.exitCode=1;})
.finally(async()=>{if(app)await deleteApp(app);});
