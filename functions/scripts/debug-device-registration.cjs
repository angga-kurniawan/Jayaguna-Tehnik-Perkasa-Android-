const {createHash}=require('node:crypto');

class DebugDeviceError extends Error {
  constructor(kind,message){super(message);this.kind=kind;}
}
const displayNameFor=token=>'Jayaguna Android development '+createHash('sha256').update(token).digest('hex').slice(0,16);

function adbFailure(error){
  const detail=String(error?.stderr || '');
  if(/unauthorized/i.test(detail))return new DebugDeviceError('device-unauthorized','Izinkan USB debugging untuk komputer ini pada layar HP.');
  if(/not debuggable/i.test(detail))return new DebugDeviceError('release-apk','APK ini bukan debug. Gunakan APK debug untuk uji USB, atau konfigurasikan SHA-256 dan Play Integrity untuk release.');
  if(/offline|not found|no devices|more than one device/i.test(detail))return new DebugDeviceError('device-offline','Perangkat belum siap. Periksa kabel, USB debugging, lalu pilih serial dari adb devices.');
  if(/unknown package|package.*not installed/i.test(detail))return new DebugDeviceError('app-missing','Pasang dan buka APK debug Jayaguna pada HP yang dipilih terlebih dahulu.');
  return new DebugDeviceError('device-read','Identitas debug belum dapat dibaca. Pastikan perangkat tersambung, APK debug terpasang dan aplikasi sudah dibuka.');
}

async function exchangeToken({token,apiKey,projectNumber,appId,request=fetch}){
  let response,body;
  try{
    response=await request(`https://firebaseappcheck.googleapis.com/v1/projects/${projectNumber}/apps/${appId}:exchangeDebugToken`,{
      method:'POST',headers:{'Content-Type':'application/json','X-Goog-Api-Key':apiKey},
      body:JSON.stringify({debugToken:token}),signal:AbortSignal.timeout(15000),redirect:'error',
    });
    body=await response.json();
  }catch{throw new DebugDeviceError('network','Verifikasi App Check belum dapat menghubungi Google. Periksa koneksi lalu coba lagi; registrasi tidak diubah.');}
  if(response.ok){
    if(typeof body.token!=='string' || !body.token)throw new DebugDeviceError('invalid-response','Respons App Check belum valid. Coba lagi; token tidak dicetak.');
    return {ttl:typeof body.ttl==='string' && /^\d+(?:\.\d+)?s$/.test(body.ttl)?body.ttl:undefined};
  }
  const reasons=(body.error?.details || []).map(detail=>detail.reason || '');
  if(reasons.some(reason=>/^API_KEY_/.test(reason)) || /API[_ ]KEY|API key/i.test(body.error?.message || '')){
    throw new DebugDeviceError('api-key','API key Android ditolak. Periksa project dan pembatasan API key; registrasi perangkat tidak diubah.');
  }
  if(response.status===403 && body.error?.status==='PERMISSION_DENIED' && /\bApp attestation failed\b/i.test(body.error?.message || '')){
    throw new DebugDeviceError('unregistered','Identitas App Check instalasi ini belum diterima. Daftarkan perangkat debug dengan --apply, lalu verifikasi ulang.');
  }
  if(response.status===429)throw new DebugDeviceError('quota','Kuota App Check sementara tercapai. Coba lagi nanti; registrasi perangkat tidak diubah.');
  if(response.status>=500)throw new DebugDeviceError('service','Layanan App Check sementara gagal. Coba lagi nanti; registrasi perangkat tidak diubah.');
  throw new DebugDeviceError('configuration','App Check menolak verifikasi. Periksa konfigurasi aplikasi Android dan izin operator; registrasi perangkat tidak diubah.');
}

async function registerIdentity({token,apply=false,verify=false,exchange,list,create,pause=ms=>new Promise(resolve=>setTimeout(resolve,ms))}){
  try{return {registered:true,changed:false,verified:true,...await exchange()};}
  catch(error){if(!(error instanceof DebugDeviceError) || error.kind!=='unregistered')throw error;}
  if(!apply){
    if(verify)throw new DebugDeviceError('unregistered','Identitas App Check instalasi ini belum diterima. Jalankan registrasi dengan --apply --verify untuk perangkat yang dipilih.');
    return {registered:false,changed:false,verified:false};
  }
  const displayName=displayNameFor(token),existing=await list();
  // A previous installation or device may have the legacy shared display name.
  // Only this token's hash label (or an exact token returned by Google) matters.
  const same=existing.find(item=>item.displayName===displayName || item.token===token);
  let changed=false,name=same?.name;
  if(!same){
    try{const record=await create({displayName,token});name=record.name;changed=true;}
    catch(error){if(error?.status!==409 && error?.context?.response?.statusCode!==409)throw new DebugDeviceError('registration','Registrasi debug belum berhasil. Periksa izin operator dan kuota; secret tidak dicetak.');}
  }
  for(let attempt=0;attempt<3;attempt++){
    try{return {registered:true,changed,verified:true,name,...await exchange()};}
    catch(error){
      if(!(error instanceof DebugDeviceError) || error.kind!=='unregistered')throw error;
      if(attempt<2)await pause(500*(attempt+1));
    }
  }
  throw new DebugDeviceError('propagation','Registrasi debug sudah ada tetapi belum diterima Google. Tunggu sebentar lalu jalankan --verify lagi; tidak membuat registrasi duplikat.');
}
module.exports={DebugDeviceError,displayNameFor,adbFailure,exchangeToken,registerIdentity};
