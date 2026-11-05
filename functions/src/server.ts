import {createServer,IncomingMessage,ServerResponse,Server} from 'node:http';
import {randomUUID} from 'node:crypto';
import {isIP} from 'node:net';
import {getApps,initializeApp} from 'firebase-admin/app';
import {getAuth} from 'firebase-admin/auth';
import {getAppCheck} from 'firebase-admin/app-check';
import {HttpsError} from 'firebase-functions/v2/https';
import {DriveMedia} from './drive/media';
import {DriveActor,oauthStart,oauthStatus,oauthComplete} from './drive/oauth';
import {MAX_BYTES} from './drive/image';
import {backendUrl} from './drive/config';
import {contextSchema} from './schema';
import {serverAppOptions} from './server-config';

export class RateLimit {
  private entries=new Map<string,{used:number;expires:number}>();
  constructor(private readonly now=()=>Date.now()) {}
  allow(key:string,max:number,window=60000):boolean {
    const time=this.now();
    if (this.entries.size>10000) for (const [id,entry] of this.entries) if (entry.expires<=time) this.entries.delete(id);
    if (this.entries.size>=20000 && !this.entries.has(key)) return false;
    let entry=this.entries.get(key);
    if (!entry || entry.expires<=time) {entry={used:0,expires:time+window};this.entries.set(key,entry);}
    return ++entry.used<=max;
  }
}
export async function requestBytes(request:IncomingMessage,max:number):Promise<Buffer> {
  if (request.headers['content-encoding'] && request.headers['content-encoding']!=='identity') throw new HttpsError('invalid-argument','Content-Encoding tidak didukung.');
  if (Number(request.headers['content-length'] || 0)>max) throw new HttpsError('resource-exhausted','Berkas/request melebihi batas ukuran.');
  const chunks:Buffer[]=[];let size=0;
  for await (const chunk of request) {
    const bytes=Buffer.from(chunk);size+=bytes.length;
    if (size>max) throw new HttpsError('resource-exhausted','Berkas/request melebihi batas ukuran.');
    chunks.push(bytes);
  }
  return Buffer.concat(chunks);
}
const statuses:Record<string,number>={'invalid-argument':400,unauthenticated:401,'permission-denied':403,'not-found':404,'already-exists':409,aborted:409,'failed-precondition':412,'resource-exhausted':429,unavailable:503,'deadline-exceeded':504};
function json(response:ServerResponse,status:number,body:unknown) {response.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});response.end(JSON.stringify(body));}
function safeError(response:ServerResponse,error:unknown,requestId:string) {
  const known=error instanceof HttpsError;
  const code=known?error.code:'internal';
  if (!known) console.error(JSON.stringify({event:'backend_error',code,requestId}));
  json(response,statuses[code] || 500,{error:{code,message:known?error.message:'Operasi belum dapat diselesaikan. Coba lagi dengan requestId yang sama.',requestId}});
}
export type ApiDependencies={
  authenticate:(request:IncomingMessage,required:boolean)=>Promise<DriveActor|undefined>;
  call:(name:string,actor:DriveActor,data:any)=>Promise<any>;
  media:Pick<DriveMedia,'upload'|'readable'|'bytes'|'metadata'|'remove'|'cleanup'>;
  oauthComplete:(state:string,code:string)=>Promise<any>;
};
export function apiServer(deps:ApiDependencies):Server {
  const limiter=new RateLimit();
  const cache=new Map<string,{bytes:Buffer;until:number}>();let cacheBytes=0;
  const origins=new Set((process.env.ALLOWED_ORIGINS || '').split(',').map(v=>v.trim()).filter(Boolean));
  let uploads=0;
  const server=createServer(async(request,response)=>{
    const requestId=randomUUID();
    response.setHeader('X-Content-Type-Options','nosniff');response.setHeader('Referrer-Policy','no-referrer');
    response.setHeader('X-Request-Id',requestId);
    try {
      const url=new URL(request.url || '/','http://localhost');
      const origin=request.headers.origin;
      if (origin && !origins.has(origin)) throw new HttpsError('permission-denied','Origin tidak diizinkan.');
      if (origin) {response.setHeader('Access-Control-Allow-Origin',origin);response.setHeader('Vary','Origin');}
      if (request.method==='OPTIONS') {
        response.setHeader('Access-Control-Allow-Methods','GET,HEAD,POST,PUT,DELETE,OPTIONS');
        response.setHeader('Access-Control-Allow-Headers','Authorization,Content-Type,X-Firebase-AppCheck,If-None-Match');
        response.setHeader('Access-Control-Max-Age','300');response.writeHead(204);response.end();return;
      }
      let ip=request.socket.remoteAddress || 'unknown';
      const hops=Number(process.env.TRUST_PROXY_HOPS || 0),forwarded=request.headers['x-forwarded-for'];
      if (hops>0 && Number.isInteger(hops) && typeof forwarded==='string') {
        const chain=forwarded.split(',').map(v=>v.trim());const candidate=chain[Math.max(0,chain.length-hops)];
        if (candidate && isIP(candidate)) ip=candidate;
      }
      const imageRoute=/^\/api\/media\/[0-9a-f-]{36}$/.test(url.pathname) && ['GET','HEAD'].includes(request.method || '');
      if (!limiter.allow((imageRoute?'image:':'api:')+ip,imageRoute?120:60)) {response.setHeader('Retry-After','60');throw new HttpsError('resource-exhausted','Terlalu banyak permintaan. Coba lagi sebentar.');}
      if (url.pathname==='/healthz' && request.method==='GET') {json(response,200,{status:'ok',service:'jayaguna-api'});return;}
      if (url.pathname==='/api/drive/oauth/callback' && request.method==='GET') {
        if (url.searchParams.has('error')) throw new HttpsError('failed-precondition','Otorisasi dibatalkan. Hubungkan ulang dari aplikasi.');
        await deps.oauthComplete(url.searchParams.get('state') || '',url.searchParams.get('code') || '');
        response.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','Content-Security-Policy':"default-src 'none'; style-src 'unsafe-inline'"});
        response.end('<!doctype html><html lang="id"><meta name="viewport" content="width=device-width"><title>Google Drive terhubung</title><body style="font:18px system-ui;padding:40px;color:#11265d"><h1>Google Drive terhubung.</h1><p>Kembali ke aplikasi Jayaguna Admin untuk mengunggah gambar.</p></body></html>');return;
      }
      const actor=await deps.authenticate(request,!imageRoute);
      const readJson=async()=>{
        if (!(request.headers['content-type'] || '').startsWith('application/json')) throw new HttpsError('invalid-argument','Gunakan Content-Type application/json.');
        try {return JSON.parse((await requestBytes(request,256*1024)).toString('utf8'));}
        catch(error) {if(error instanceof HttpsError) throw error;throw new HttpsError('invalid-argument','JSON tidak valid.');}
      };
      const readContext=async()=>{const value=contextSchema.strict().safeParse(await readJson());if(!value.success) throw new HttpsError('invalid-argument','requestId dan metadata perangkat wajib valid.');return value.data;};
      const call=/^\/api\/call\/([A-Za-z]+)$/.exec(url.pathname);
      if (call && request.method==='POST') {
        if (call[1]==='startDriveOAuth' && !limiter.allow('oauth:'+actor!.uid,10,3600000)) throw new HttpsError('resource-exhausted','Terlalu banyak permintaan OAuth. Coba lagi nanti.');
        json(response,200,{result:await deps.call(call[1]!,actor!,await readJson())});return;
      }
      const upload=/^\/api\/uploads\/([0-9a-f-]{36})$/.exec(url.pathname);
      if (upload && request.method==='PUT') {
        if (!limiter.allow('upload:'+actor!.uid,20) || uploads>=4) throw new HttpsError('resource-exhausted','Unggahan sedang penuh. Coba lagi.');
        uploads++;
        try {json(response,200,await deps.media.upload(actor!,upload[1]!,String(request.headers['content-type'] || '').split(';')[0]!,await requestBytes(request,MAX_BYTES)));}
        finally {uploads--;}
        return;
      }
      const metadata=/^\/api\/media\/([0-9a-f-]{36})\/metadata$/.exec(url.pathname);
      if (metadata && request.method==='GET') {json(response,200,await deps.media.metadata(metadata[1]!,actor!));return;}
      if (url.pathname==='/api/media/cleanup' && request.method==='POST') {
        const body=await readContext();
        json(response,200,await deps.media.cleanup(actor!,body.device));return;
      }
      const media=/^\/api\/media\/([0-9a-f-]{36})$/.exec(url.pathname);
      if (media && request.method==='DELETE') {
        const body=await readContext();
        json(response,200,await deps.media.remove(media[1]!,actor!,body.device,body.requestId));return;
      }
      if (media && imageRoute) {
        // Always re-check the current public projection BEFORE cache/304. A stale
        // byte cache can never grant access after unpublish or image replacement.
        const asset=await deps.media.readable(media[1]!,actor);
        const etag='"'+asset.sha256+'"';
        const headers={'Content-Type':asset.mimeType,'Cache-Control':actor?'no-store':'private, no-cache, must-revalidate','ETag':etag,'Content-Disposition':'inline'};
        if (!actor && request.headers['if-none-match']===etag) {response.writeHead(304,headers);response.end();return;}
        if (request.method==='HEAD') {response.writeHead(200,{...headers,'Content-Length':asset.sizeBytes});response.end();return;}
        let entry=cache.get(asset.id);
        if (!entry || entry.until<Date.now()) {
          if (entry) {cacheBytes-=entry.bytes.length;cache.delete(asset.id);}
          const bytes=await deps.media.bytes(asset);
          while (cacheBytes+bytes.length>32*1024*1024 && cache.size) {const first=cache.entries().next().value!;cacheBytes-=first[1].bytes.length;cache.delete(first[0]);}
          entry={bytes,until:Date.now()+5*60*1000};cache.set(asset.id,entry);cacheBytes+=bytes.length;
        }
        response.writeHead(200,{...headers,'Content-Length':entry.bytes.length});response.end(entry.bytes);return;
      }
      throw new HttpsError('not-found','Endpoint tidak ditemukan.');
    } catch(error) {if(!response.headersSent) safeError(response,error,requestId);else response.end();}
  });
  server.requestTimeout=120000;server.headersTimeout=15000;server.keepAliveTimeout=5000;
  return server;
}
export async function productionServer() {
  if (!getApps().length) initializeApp(serverAppOptions());
  process.env.MEDIA_PROVIDER ||= 'drive';
  backendUrl();
  const service=await import('./service');
  const handlers:Record<string,(request:any)=>Promise<any>>={getSession:service.getSession,getDashboard:service.getDashboard,beginUpload:service.beginUpload,
    saveProduct:r=>service.saveEntity(r,'product'),savePartner:r=>service.saveEntity(r,'partner'),archiveProduct:r=>service.archiveEntity(r,'product'),archivePartner:r=>service.archiveEntity(r,'partner'),manageAdmin:service.manageAdmin};
  const tokens=new WeakMap<DriveActor,any>();
  return apiServer({
    authenticate:async(request,required)=>{
      const bearer=/^Bearer ([^\s]+)$/.exec(request.headers.authorization || '');
      if (!bearer) {if(required) throw new HttpsError('unauthenticated','Silakan masuk terlebih dahulu.');return undefined;}
      let decoded;
      try {decoded=await getAuth().verifyIdToken(bearer[1]!,true);} catch {throw new HttpsError('unauthenticated','Token login tidak valid atau sudah dicabut.');}
      const appCheck=request.headers['x-firebase-appcheck'];
      if (process.env.ENFORCE_APP_CHECK==='true') {
        if (typeof appCheck!=='string') throw new HttpsError('failed-precondition','Verifikasi keamanan aplikasi diperlukan. Coba lagi dari aplikasi yang terdaftar.');
        try {await getAppCheck().verifyToken(appCheck);} catch {throw new HttpsError('failed-precondition','Verifikasi keamanan aplikasi belum berhasil. Periksa registrasi perangkat lalu coba lagi.');}
      }
      const actor=await service.requireActor({auth:{uid:decoded.uid,token:decoded},data:{}});
      tokens.set(actor,decoded);return actor;
    },
    call:async(name,actor,data)=>{
      if (name==='getDriveStatus' || name==='startDriveOAuth') {
        if (actor.role!=='OWNER') throw new HttpsError('permission-denied','Hanya OWNER dapat menghubungkan Drive.');
        return name==='getDriveStatus'?oauthStatus():oauthStart(actor);
      }
      const handler=handlers[name];if(!handler) throw new HttpsError('not-found','Operasi tidak tersedia.');
      return handler({auth:{uid:actor.uid,token:tokens.get(actor)},data});
    },media:new DriveMedia(),oauthComplete,
  });
}
if (require.main===module) productionServer().then(server=>{
  const port=Number(process.env.PORT || 8787);
  if (!Number.isInteger(port) || port<1 || port>65535) throw new Error('invalid-port');
  server.on('error',()=>{console.error('Backend gagal membuka port. Periksa HOST/PORT dan proses backend yang masih berjalan.');process.exitCode=1;});
  server.listen(port,process.env.HOST || '127.0.0.1',()=>console.log(JSON.stringify({event:'api_started',port,mediaProvider:process.env.MEDIA_PROVIDER})));
}).catch(()=>{console.error('Backend belum dapat dimulai. Periksa environment, Firebase ADC, dan BACKEND_PUBLIC_URL.');process.exitCode=1;});
