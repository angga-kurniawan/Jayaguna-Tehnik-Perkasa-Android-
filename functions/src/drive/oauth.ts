import {randomBytes,createHash} from 'node:crypto';
import {OAuth2Client} from 'google-auth-library';
import {getFirestore,FieldValue,Timestamp} from 'firebase-admin/firestore';
import {getAuth} from 'firebase-admin/auth';
import {HttpsError} from 'firebase-functions/v2/https';
import {driveConfig} from './config';
import {seal,unseal,Sealed} from './crypto';

export type DriveActor = {uid:string;name:string;email:string;role:'OWNER'|'ADMIN';authTime:number};
export function assertOAuthOwner(profile:any,user:{disabled:boolean;customClaims?:Record<string,any>;tokensValidAfterTime?:string},authTime:number) {
  const explicit=profile && Object.hasOwn(profile,'status');
  const approved=explicit ? profile.status==='approved' && (!Object.hasOwn(profile,'active') || profile.active===true)
    : profile?.active===true && user.customClaims?.role==='OWNER';
  const cutoff=profile && Object.hasOwn(profile,'revokedBeforeSeconds')?profile.revokedBeforeSeconds:0;
  if (!Number.isInteger(authTime) || authTime<=0 || !approved || profile.role!=='OWNER' || user.disabled ||
    typeof cutoff!=='number' || !Number.isFinite(cutoff) ||
    authTime<new Date(user.tokensValidAfterTime || 0).getTime()/1000 || (cutoff!==0 && authTime<=cutoff)) throw new HttpsError('permission-denied','Sesi OWNER tidak berlaku.');
}
const grantRef = () => getFirestore().doc('backendSecrets/googleDrive');
let cached: {updated:number;client:OAuth2Client}|undefined;
export function clearDriveClient() { cached=undefined; }
export async function driveClient(): Promise<OAuth2Client> {
  const config = driveConfig();
  const grant = await grantRef().get();
  if (!grant.exists || grant.get('accountEmail')!==config.accountEmail) throw new HttpsError('failed-precondition','GOOGLE_DRIVE_NOT_CONNECTED: OWNER perlu menghubungkan akun Drive utama.');
  const updated = grant.updateTime!.toMillis();
  if (cached?.updated===updated) return cached.client;
  let refreshToken: string;
  try { refreshToken=unseal(grant.get('refreshToken') as Sealed,config.key,'refresh-token'); }
  catch { throw new HttpsError('failed-precondition','Kredensial Drive tidak dapat dibuka. Periksa kunci enkripsi backend.'); }
  const client = new OAuth2Client(config.clientId,config.clientSecret,config.redirectUri);
  client.setCredentials({refresh_token:refreshToken});
  cached={updated,client};
  return client;
}
export async function oauthStart(actor: DriveActor) {
  if (actor.role!=='OWNER') throw new HttpsError('permission-denied','Hanya OWNER dapat menghubungkan Drive.');
  const config = driveConfig(), state=randomBytes(32).toString('base64url'), verifier=randomBytes(48).toString('base64url');
  const hash=createHash('sha256').update(state).digest('hex');
  await getFirestore().doc('driveOAuthStates/'+hash).create({ownerUid:actor.uid,authTime:actor.authTime,verifier:seal(verifier,config.key,'pkce'),expiresAt:Timestamp.fromMillis(Date.now()+10*60*1000),createdAt:FieldValue.serverTimestamp()});
  const client=new OAuth2Client(config.clientId,config.clientSecret,config.redirectUri);
  return {authorizationUrl:client.generateAuthUrl({access_type:'offline',prompt:'consent',scope:['openid','email','https://www.googleapis.com/auth/drive'],
    state,code_challenge:createHash('sha256').update(verifier).digest('base64url'),code_challenge_method:'S256' as any,login_hint:config.accountEmail})};
}
export async function oauthComplete(state: string, code: string) {
  const config=driveConfig();
  if (!/^[A-Za-z0-9_-]{40,100}$/.test(state) || !code || code.length>4096) throw new HttpsError('invalid-argument','Sesi OAuth tidak valid.');
  const ref=getFirestore().doc('driveOAuthStates/'+createHash('sha256').update(state).digest('hex'));
  const saved=await getFirestore().runTransaction(async tx=>{
    const doc=await tx.get(ref); if (!doc.exists || doc.get('expiresAt').toMillis()<Date.now()) throw new HttpsError('permission-denied','Sesi OAuth berakhir. Hubungkan ulang dari aplikasi.');
    tx.delete(ref); return doc.data()!;
  });
  const [profile,user]=await Promise.all([getFirestore().doc('users/'+saved.ownerUid).get(),getAuth().getUser(saved.ownerUid)]);
  assertOAuthOwner(profile.data(),user,saved.authTime);
  const client=new OAuth2Client(config.clientId,config.clientSecret,config.redirectUri);
  let tokens;
  try { tokens=(await client.getToken({code,codeVerifier:unseal(saved.verifier,config.key,'pkce'),redirect_uri:config.redirectUri})).tokens; }
  catch { throw new HttpsError('failed-precondition','Pertukaran OAuth gagal. Mulai ulang dari aplikasi.'); }
  if (!tokens.id_token) throw new HttpsError('permission-denied','Identitas akun Google tidak tersedia.');
  let identity;
  try { identity=(await client.verifyIdToken({idToken:tokens.id_token,audience:config.clientId})).getPayload(); }
  catch { throw new HttpsError('permission-denied','Identitas akun Google tidak valid.'); }
  if (!identity?.email_verified || identity.email?.toLowerCase()!==config.accountEmail) throw new HttpsError('permission-denied','Pilih akun Google Drive utama yang sudah dikonfigurasi.');
  const old=await grantRef().get();
  if (old.exists && old.get('subject')!==identity.sub) throw new HttpsError('failed-precondition','Migrasi akun Drive memerlukan peninjauan operator.');
  const refreshToken=tokens.refresh_token || (old.exists?unseal(old.get('refreshToken'),config.key,'refresh-token'):null);
  if (!refreshToken) throw new HttpsError('failed-precondition','Google tidak memberikan offline access. Cabut izin aplikasi lalu hubungkan ulang.');
  client.setCredentials({...tokens,refresh_token:refreshToken});
  // Verify all pre-existing company folders before persisting a central credential.
  const {DriveApi}=await import('./transport');
  const drive=new DriveApi(async()=>client);
  await Promise.all([config.products,config.partners,config.portfolioResidential,config.portfolioCorporate].map(id=>drive.folder(id)));
  // Consent and folder checks may take time: revalidate the initiating OWNER and
  // serialize account selection with concurrent callbacks before saving secrets.
  const currentUser=await getAuth().getUser(saved.ownerUid);
  await getFirestore().runTransaction(async tx=>{
    const [currentProfile,currentGrant]=await Promise.all([tx.get(getFirestore().doc('users/'+saved.ownerUid)),tx.get(grantRef())]);
    assertOAuthOwner(currentProfile.data(),currentUser,saved.authTime);
    if (currentGrant.exists && currentGrant.get('subject')!==identity.sub) throw new HttpsError('failed-precondition','Migrasi akun Drive memerlukan peninjauan operator.');
    tx.set(grantRef(),{accountEmail:config.accountEmail,subject:identity.sub,refreshToken:seal(refreshToken,config.key,'refresh-token'),connectedBy:saved.ownerUid,updatedAt:FieldValue.serverTimestamp()});
  });
  clearDriveClient();
  return {accountEmail:config.accountEmail};
}
export async function oauthStatus() {
  const config=driveConfig(), grant=await grantRef().get();
  return {connected:grant.exists && grant.get('accountEmail')===config.accountEmail,accountEmail:config.accountEmail};
}
