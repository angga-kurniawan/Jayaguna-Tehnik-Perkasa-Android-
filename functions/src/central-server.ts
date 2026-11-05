import {Server,ServerResponse,createServer} from 'node:http';
import {randomUUID} from 'node:crypto';
import {getApps,initializeApp} from 'firebase-admin/app';
import {DecodedIdToken,getAuth} from 'firebase-admin/auth';
import {getFirestore} from 'firebase-admin/firestore';
import {HttpsError} from 'firebase-functions/v2/https';
import {RateLimit,requestBytes} from './server';
import {serverAppOptions} from './server-config';
import {validateAdminEnvironment} from './admin-server';
import {ApprovedActor,GoogleIdentity,googleIdentity,requireApprovedActor} from './approved-access';
import {handleAccessRequest,NotificationResult} from './access-request';
import {CentralDrive} from './central-drive';
import {MAX_BYTES} from './drive/image';

export type CentralServerDependencies={
  verifyIdToken:(token:string,checkRevoked:boolean)=>Promise<DecodedIdToken>;
  approve:(identity:GoogleIdentity)=>Promise<ApprovedActor>;
  notify:(identity:GoogleIdentity)=>Promise<NotificationResult>;
  drive:Pick<CentralDrive,'status'|'reserve'|'upload'|'metadata'|'read'|'trash'>;
};
const statuses:Record<string,number>={'invalid-argument':400,unauthenticated:401,'permission-denied':403,'not-found':404,'already-exists':409,aborted:409,'failed-precondition':412,'resource-exhausted':429,unavailable:503,'deadline-exceeded':504};
function json(response:ServerResponse,status:number,body:unknown) {
  response.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});
  response.end(JSON.stringify(body));
}

/** A small native adapter alongside the existing website bridge. No client Drive tokens are accepted. */
export function centralServer(deps:CentralServerDependencies):Server {
  const limiter=new RateLimit();
  const server=createServer(async(request,response)=>{
    const requestId=randomUUID();
    response.setHeader('X-Content-Type-Options','nosniff');
    response.setHeader('Referrer-Policy','no-referrer');
    response.setHeader('X-Request-Id',requestId);
    try {
      const path=new URL(request.url || '/','http://localhost').pathname,method=request.method;
      const asset=/^\/api\/drive\/assets\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(\/metadata)?$/i.exec(path);
      const health=path==='/healthz' && method==='GET';
      const notify=path==='/api/access/request' && method==='POST';
      const status=path==='/api/drive/status' && method==='GET';
      const reserve=path==='/api/drive/reserve' && method==='POST';
      const assetRoute=asset && (asset[2]?method==='GET':['GET','PUT','DELETE'].includes(method || ''));
      if (!health && !notify && !status && !reserve && !assetRoute) throw new HttpsError('not-found','Endpoint tidak ditemukan.');
      if (request.headers.origin) throw new HttpsError('permission-denied','Origin tidak diizinkan.');
      if (!limiter.allow('ip:'+(request.socket.remoteAddress || 'unknown'),120)) {
        response.setHeader('Retry-After','60');throw new HttpsError('resource-exhausted','Terlalu banyak permintaan.');
      }
      if (health) {json(response,200,{status:'ok',service:'jayaguna-central-api'});return;}
      const bearer=/^Bearer ([^\s]+)$/.exec(request.headers.authorization || '');
      if (!bearer) throw new HttpsError('unauthenticated','Silakan masuk terlebih dahulu.');
      let decoded:DecodedIdToken;
      try {decoded=await deps.verifyIdToken(bearer[1]!,true);}
      catch {throw new HttpsError('unauthenticated','Token login tidak valid atau sudah dicabut.');}
      const identity=googleIdentity(decoded);
      if (!limiter.allow((notify?'notify:':'user:')+identity.uid,notify?5:120)) throw new HttpsError('resource-exhausted','Terlalu banyak permintaan dari akun ini.');
      if (notify || reserve || method==='DELETE') {
        if (request.headers['content-type']?.split(';')[0]?.trim().toLowerCase()!=='application/json') throw new HttpsError('invalid-argument','Gunakan Content-Type application/json.');
        const bytes=await requestBytes(request,4096);
        let data:unknown;
        try {data=JSON.parse(bytes.toString('utf8'));} catch {throw new HttpsError('invalid-argument','JSON tidak valid.');}
        if (!data || typeof data!=='object' || Array.isArray(data) || Object.keys(data).length) throw new HttpsError('invalid-argument','Payload endpoint ini harus objek JSON kosong.');
      }
      if (notify) {json(response,200,{result:await deps.notify(identity)});return;}
      const actor=await deps.approve(identity);
      if (status) {json(response,200,{result:await deps.drive.status()});return;}
      if (reserve) {
        if (!limiter.allow('reserve:'+actor.uid,20)) throw new HttpsError('resource-exhausted','Terlalu banyak reservasi gambar.');
        json(response,200,{result:await deps.drive.reserve(actor)});return;
      }
      const id=asset![1]!;
      if (asset![2]) {json(response,200,{result:await deps.drive.metadata(actor,id)});return;}
      if (method==='PUT') {
        const mime=request.headers['content-type']?.split(';')[0]?.trim().toLowerCase() || '';
        if (!['image/png','image/jpeg','image/webp'].includes(mime)) throw new HttpsError('invalid-argument','Gunakan PNG, JPEG, atau WebP.');
        json(response,200,{media:await deps.drive.upload(actor,id,mime,await requestBytes(request,MAX_BYTES))});return;
      }
      if (method==='DELETE') {json(response,200,{result:await deps.drive.trash(actor,id)});return;}
      const image=await deps.drive.read(actor,id);
      response.writeHead(200,{'Content-Type':image.mimeType,'Content-Length':image.bytes.length,'Cache-Control':'private, no-store'});
      response.end(image.bytes);
    } catch(error) {
      const known=error instanceof HttpsError,code=known?error.code:'internal';
      if (!known) console.error(JSON.stringify({event:'central_backend_error',code,requestId}));
      if (!response.headersSent) json(response,statuses[code] || 500,{error:{code,requestId,message:known?error.message:'Layanan belum dapat menyelesaikan permintaan. Coba lagi.'}});
      else response.end();
    }
  });
  server.requestTimeout=120_000;server.headersTimeout=15_000;server.keepAliveTimeout=5_000;
  return server;
}

export async function productionCentralServer(environment:NodeJS.ProcessEnv=process.env):Promise<Server> {
  validateAdminEnvironment(environment);
  if (!getApps().length) initializeApp(serverAppOptions(environment));
  const db=getFirestore(),auth=getAuth();
  return centralServer({verifyIdToken:(token,revoked)=>auth.verifyIdToken(token,revoked),
    approve:identity=>requireApprovedActor(identity,db),notify:identity=>handleAccessRequest(identity,db,environment),drive:new CentralDrive(db)});
}

if (require.main===module) productionCentralServer().then(server=>{
  const port=Number(process.env.PORT || 8787);
  if (!Number.isInteger(port) || port<1 || port>65535) throw new Error('invalid-port');
  server.on('error',()=>{console.error('Layanan pusat gagal membuka port.');process.exitCode=1;});
  server.listen(port,process.env.HOST || '0.0.0.0',()=>console.log(JSON.stringify({event:'central_api_started',port})));
}).catch(()=>{console.error('Layanan pusat belum dapat dimulai. Periksa konfigurasi dan kredensial server.');process.exitCode=1;});
