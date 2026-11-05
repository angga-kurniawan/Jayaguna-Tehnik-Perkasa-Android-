// Read-only check. No credentials, tokens, or upstream error objects are printed.
const {initializeApp,deleteApp}=require('firebase-admin/app');
const {getFirestore}=require('firebase-admin/firestore');
const {getAuth}=require('firebase-admin/auth');
let stage='configuration',app;
async function main(){
  if(['FIRESTORE_EMULATOR_HOST','FIREBASE_AUTH_EMULATOR_HOST'].some(key=>process.env[key]))throw new Error('cloud-check-only');
  const {driveConfig}=require('../lib/drive/config');
  driveConfig();
  const {serverAppOptions}=require('../lib/server-config');
  app=initializeApp(serverAppOptions());
  const db=getFirestore(app);
  stage='firestore';
  const owners=await db.collection('users').where('role','==','OWNER').limit(2).get();
  if(owners.size!==1 || owners.docs[0].get('active')!==true)throw new Error('owner-profile');
  stage='firebase-auth';
  const owner=await getAuth(app).getUser(owners.docs[0].id);
  if(owner.disabled || owner.customClaims?.role!=='OWNER')throw new Error('owner-auth');
  stage='drive-grant';
  const grant=await db.doc('backendSecrets/googleDrive').get();
  const connected=grant.exists && grant.get('accountEmail')===process.env.GOOGLE_DRIVE_ACCOUNT_EMAIL;
  let foldersVerified=false;
  if(process.argv.includes('--drive')){
    if(!connected)throw new Error('drive-not-connected');
    stage='drive-folders';
    const {DriveApi}=require('../lib/drive/transport'),config=driveConfig(),drive=new DriveApi();
    await drive.folder(config.products);await drive.folder(config.partners);foldersVerified=true;
  }
  console.log(JSON.stringify({checked:true,project:app.options.projectId,firebaseFirestore:true,firebaseAuth:true,ownerActive:true,driveConnected:connected,foldersVerified,readOnly:true}));
}
main().catch(()=>{
  console.error(JSON.stringify({checked:false,stage,message:'Periksa konfigurasi backend, ADC/IAM Firebase, profil OWNER, atau otorisasi Drive sesuai tahap yang dilaporkan. Secret tidak dicetak.'}));process.exitCode=1;
}).finally(async()=>{if(app)await deleteApp(app);});
