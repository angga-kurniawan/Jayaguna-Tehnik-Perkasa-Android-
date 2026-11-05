import {createHash,randomUUID} from 'node:crypto';
import {Firestore,FieldValue,Timestamp} from 'firebase-admin/firestore';
import {HttpsError} from 'firebase-functions/v2/https';
import {GoogleIdentity} from './approved-access';

export const ACCESS_REQUEST_RECIPIENT='mtahakurniawan@gmail.com';
export type NotificationResult={emailSent:boolean;emailQueued:boolean;retryable:boolean};
const pending:NotificationResult={emailSent:false,emailQueued:false,retryable:true};
const sent:NotificationResult={emailSent:true,emailQueued:false,retryable:false};
const escapeHtml=(value:string)=>value.replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]!));

/** Only notifies an immutable request already persisted by the signed-in Google user. */
export async function handleAccessRequest(identity:GoogleIdentity,db:Firestore,environment:NodeJS.ProcessEnv=process.env,send:typeof fetch=fetch,now=Date.now):Promise<NotificationResult> {
  const requestRef=db.doc('accessRequests/'+identity.uid),outbox=db.doc('accessRequestMail/'+identity.uid);
  const lease=randomUUID();
  const state=await db.runTransaction(async tx=>{
    const [request,mail]=await Promise.all([tx.get(requestRef),tx.get(outbox)]);
    const data=request.data(),existing=mail.data();
    if (!data || data.uid!==identity.uid || data.email!==identity.email || data.status!=='pending' ||
        typeof data.name!=='string' || !data.name.trim() || data.name.length>160 || /[\r\n]/.test(data.name) ||
        !data.requestedAt || typeof data.requestedAt.toMillis!=='function') {
      throw new HttpsError('failed-precondition','Permintaan akses belum tersimpan atau tidak cocok dengan akun Google ini.');
    }
    const key=createHash('sha256').update(identity.uid+':'+data.requestedAt.toMillis()).digest('hex');
    if (existing?.status==='sent' && existing.requestKey===key) return {result:sent};
    if (existing?.status==='processing' && existing.requestKey===key && existing.leaseUntil?.toMillis()>now()) return {result:pending};
    const from=environment.ACCESS_REQUEST_FROM || '',apiKey=environment.RESEND_API_KEY || '';
    const configured=Boolean(apiKey && /^[^\r\n<>\s]+@[^\r\n<>\s]+\.[^\r\n<>\s]+$/.test(from));
    tx.set(outbox,{uid:identity.uid,requestKey:key,status:configured?'processing':'notSent',
      lease:configured?lease:null,leaseUntil:Timestamp.fromMillis(now()+60_000),updatedAt:FieldValue.serverTimestamp(),
      attempts:Number(existing?.attempts || 0)+1}, {merge:true});
    return configured?{name:data.name,requestedAt:new Date(data.requestedAt.toMillis()).toISOString(),key,from,apiKey}:{result:pending};
  });
  if ('result' in state) return state.result!;
  const requestedTime=new Intl.DateTimeFormat('id-ID',{dateStyle:'long',timeStyle:'medium',timeZone:'Asia/Jakarta'}).format(new Date(state.requestedAt!))+' WIB';
  let delivered=false;
  try {
    const response=await send('https://api.resend.com/emails',{
      method:'POST',redirect:'error',signal:AbortSignal.timeout(10_000),
      headers:{Authorization:'Bearer '+state.apiKey,'Content-Type':'application/json','Idempotency-Key':'jtp-access-'+state.key},
      body:JSON.stringify({from:state.from,to:[ACCESS_REQUEST_RECIPIENT],subject:'Permintaan akses admin Jayaguna',
        text:`Permintaan akses admin baru.\nNama: ${state.name}\nEmail Google: ${identity.email}\nUID Firebase: ${identity.uid}\nWaktu permohonan: ${requestedTime}\n\nTinjau users/${identity.uid} dan ubah status menjadi approved di Firebase Console. Email ini tidak memberikan akses otomatis.`,
        html:`<p>Permintaan akses admin baru.</p><p>Nama: ${escapeHtml(state.name!)}<br>Email Google: ${escapeHtml(identity.email)}<br>UID Firebase: ${escapeHtml(identity.uid)}<br>Waktu permohonan: ${escapeHtml(requestedTime)}</p><p>Tinjau users/${escapeHtml(identity.uid)} di Firebase Console dan ubah status menjadi approved. Email ini tidak memberikan akses otomatis.</p>`}),
    });
    delivered=response.ok;
    await response.body?.cancel().catch(()=>{});
  } catch { /* No upstream response, token, or credential is logged. The same key can be retried. */ }
  await db.runTransaction(async tx=>{
    const current=await tx.get(outbox);
    if (current.get('lease')===lease) tx.set(outbox,{status:delivered?'sent':'notSent',lease:null,leaseUntil:Timestamp.fromMillis(0),updatedAt:FieldValue.serverTimestamp()}, {merge:true});
  });
  return delivered?sent:pending;
}
