// Validate metadata only; keep the private-key JSON outside the repository.
const fs=require('node:fs'),path=require('node:path');
try{
  const args=process.argv.slice(2),at=args.indexOf('--credential-file'),source=at>=0?args[at+1]:'';
  if(!source)throw new Error('Gunakan --credential-file PATH_JSON_FIREBASE_ADMIN.');
  const file=path.resolve(source),credential=JSON.parse(fs.readFileSync(file,'utf8'));
  if(credential.type!=='service_account' || credential.project_id!=='jayagunatehnikperkasa' || !credential.client_email?.endsWith('@jayagunatehnikperkasa.iam.gserviceaccount.com') || !credential.private_key?.startsWith('-----BEGIN PRIVATE KEY-----'))throw new Error('JSON harus credential Firebase Admin asli untuk project jayagunatehnikperkasa.');
  const target=path.resolve(__dirname,'../.env');
  let text=fs.readFileSync(target,'utf8'),line='GOOGLE_APPLICATION_CREDENTIALS='+JSON.stringify(file.replace(/\\/g,'/'));
  text=/^GOOGLE_APPLICATION_CREDENTIALS=.*$/m.test(text)?text.replace(/^GOOGLE_APPLICATION_CREDENTIALS=.*$/m,()=>line):text+'\n'+line+'\n';
  fs.writeFileSync(target+'.tmp',text,{mode:0o600});fs.renameSync(target+'.tmp',target);
  console.log(JSON.stringify({configured:true,project:credential.project_id,credentialStoredOutsideSource:true,jsonCopied:false,secretPrinted:false}));
}catch{console.error('Konfigurasi gagal. Periksa lokasi JSON Firebase Admin dan kesamaan project; private key tidak dicetak.');process.exitCode=1;}
