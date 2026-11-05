// Import real Web OAuth credentials into the ignored backend .env, never Android.
const fs=require('node:fs');
const path=require('node:path');
const {randomBytes}=require('node:crypto');
const args=process.argv.slice(2),value=key=>{const i=args.indexOf(key);return i<0?'':args[i+1] || '';};
try {
  const source=value('--oauth-file'),email=value('--account-email').trim().toLowerCase();
  if(!source || !email.includes('@'))throw new Error('Gunakan --oauth-file PATH_JSON --account-email EMAIL_DRIVE_UTAMA.');
  const credentials=JSON.parse(fs.readFileSync(path.resolve(source),'utf8'));
  const web=credentials.web;
  if(!web?.client_id?.endsWith('.apps.googleusercontent.com') || !web.client_secret) throw new Error('File harus OAuth Web application dengan client_id dan client_secret; credential installed/Android tidak didukung backend ini.');
  const target=path.resolve(__dirname,'../.env'),example=fs.readFileSync(path.resolve(__dirname,'../.env.example'),'utf8');
  let text=fs.existsSync(target)?fs.readFileSync(target,'utf8'):example;
  const read=key=>text.match(new RegExp('^'+key+'=(.*)$','m'))?.[1]?.trim().replace(/^"|"$/g,'');
  const origin=(read('BACKEND_PUBLIC_URL') || 'http://localhost:8787').replace(/\/$/,'');
  const redirect=origin+'/api/drive/oauth/callback';
  if(!web.redirect_uris?.includes(redirect)) throw new Error('Tambahkan Authorized redirect URI '+redirect+' pada OAuth Web client, lalu unduh JSON terbaru.');
  const updates={GOOGLE_OAUTH_CLIENT_ID:web.client_id,GOOGLE_OAUTH_CLIENT_SECRET:web.client_secret,GOOGLE_OAUTH_REDIRECT_URI:redirect,
    GOOGLE_DRIVE_ACCOUNT_EMAIL:email,GOOGLE_DRIVE_PRODUCTS_FOLDER_ID:'1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd',GOOGLE_DRIVE_PARTNERS_FOLDER_ID:'1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6'};
  if(!read('DRIVE_TOKEN_ENCRYPTION_KEY'))updates.DRIVE_TOKEN_ENCRYPTION_KEY=randomBytes(32).toString('base64');
  for(const [key,val] of Object.entries(updates)) {
    const line=key+'='+JSON.stringify(val),pattern=new RegExp('^'+key+'=.*$','m');
    text=pattern.test(text)?text.replace(pattern,()=>line):text+'\n'+line+'\n';
  }
  fs.writeFileSync(target+'.tmp',text,{mode:0o600});fs.renameSync(target+'.tmp',target);
  console.log(JSON.stringify({configured:true,environmentFile:'functions/.env',accountEmail:email,redirectUri:redirect,secretPrinted:false,jsonCopied:false}));
} catch(error) {console.error(error.message);process.exitCode=1;}
