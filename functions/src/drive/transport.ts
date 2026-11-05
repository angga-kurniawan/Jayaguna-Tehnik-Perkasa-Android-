import {OAuth2Client} from 'google-auth-library';
import {HttpsError} from 'firebase-functions/v2/https';
import {createHash,randomBytes} from 'node:crypto';
import {DRIVE_FILE_ID} from './config';
import {MAX_BYTES} from './image';
import {driveClient} from './oauth';

export type DriveFile = {id:string;name:string;mimeType:string;size?:string;trashed?:boolean;parents?:string[];md5Checksum?:string;appProperties?:Record<string,string>;owners?:{emailAddress?:string}[];capabilities?:{canAddChildren?:boolean;canDownload?:boolean;canTrash?:boolean};permissions?:{type:string}[]};
const fields='id,name,mimeType,size,trashed,parents,md5Checksum,appProperties,owners(emailAddress),capabilities,permissions(type)';
export async function boundedBytes(response: Response, limit=MAX_BYTES): Promise<Buffer> {
  if (Number(response.headers.get('content-length') || 0)>limit) throw new HttpsError('resource-exhausted','Berkas melebihi batas ukuran.');
  const chunks:Buffer[]=[]; let size=0;
  if (response.body) for await (const chunk of response.body as any) {
    const buffer=Buffer.from(chunk); size+=buffer.length;
    if (size>limit) { await response.body.cancel().catch(()=>{}); throw new HttpsError('resource-exhausted','Berkas melebihi batas ukuran.'); }
    chunks.push(buffer);
  }
  return Buffer.concat(chunks);
}
export class DriveApi {
  constructor(private readonly client:()=>Promise<OAuth2Client>=driveClient,private readonly request:typeof fetch=fetch,private readonly pause=(ms:number)=>new Promise<void>(r=>setTimeout(r,ms))) {}
  private async send(url:string,options:RequestInit={},allowMissing=false):Promise<Response> {
    for (let attempt=0;attempt<4;attempt++) {
      const client=await this.client();
      let token:string|null|undefined;
      try { token=(await client.getAccessToken()).token; }
      catch { throw new HttpsError('failed-precondition','GOOGLE_DRIVE_REAUTH_REQUIRED: OWNER perlu menghubungkan ulang Drive.'); }
      if (!token) throw new HttpsError('failed-precondition','Google Drive belum diotorisasi.');
      let response:Response;
      try { response=await this.request(url,{...options,headers:{...options.headers,Authorization:'Bearer '+token},signal:AbortSignal.timeout(30000),redirect:'error'}); }
      catch { if (attempt<3) { await this.pause(200*2**attempt);continue; } throw new HttpsError('unavailable','Koneksi Google Drive gagal. Coba lagi dengan permintaan yang sama.'); }
      if (response.ok || (allowMissing && response.status===404) || (response.status===409 && options.method==='POST' && url.startsWith('https://www.googleapis.com/upload/'))) return response;
      if (response.status===401 && attempt===0) {
        await response.arrayBuffer();
        try { await client.refreshAccessToken(); } catch { throw new HttpsError('failed-precondition','GOOGLE_DRIVE_REAUTH_REQUIRED: Otorisasi Drive dicabut atau berakhir.'); }
        continue;
      }
      let reason='';
      try { const body:any=await response.json(); reason=body.error?.errors?.[0]?.reason || ''; } catch { /* Never log upstream token/request objects. */ }
      if ((response.status===429 || response.status>=500 || ['rateLimitExceeded','userRateLimitExceeded','backendError'].includes(reason)) && attempt<3) { await this.pause(200*2**attempt+Math.floor(Math.random()*100));continue; }
      if (response.status===404) throw new HttpsError('not-found','Berkas atau folder Google Drive tidak ditemukan.');
      if (reason==='storageQuotaExceeded') throw new HttpsError('resource-exhausted','Kapasitas Google Drive utama penuh.');
      if (response.status===403) throw new HttpsError('permission-denied','Akun Drive tidak memiliki izin folder/berkas yang diperlukan.');
      throw new HttpsError('unavailable','Google Drive menolak operasi. Coba lagi atau hubungi OWNER.');
    }
    throw new HttpsError('unavailable','Google Drive belum merespons.');
  }
  async metadata(id:string):Promise<DriveFile> {
    if (!DRIVE_FILE_ID.test(id)) throw new HttpsError('invalid-argument','Google Drive File ID tidak valid.');
    const response=await this.send(`https://www.googleapis.com/drive/v3/files/${encodeURIComponent(id)}?supportsAllDrives=true&fields=${encodeURIComponent(fields)}`);
    return response.json() as Promise<DriveFile>;
  }
  async folder(id:string) {
    const folder=await this.metadata(id);
    if (folder.trashed || folder.mimeType!=='application/vnd.google-apps.folder' || !folder.capabilities?.canAddChildren) throw new HttpsError('failed-precondition','Folder Drive tidak tersedia atau tidak dapat menerima unggahan.');
    if (folder.permissions?.some(p=>['anyone','domain'].includes(p.type))) throw new HttpsError('failed-precondition','Folder penyimpanan harus privat; hapus akses publik/domain dari folder Drive.');
    return folder;
  }
  /** File.get embeds only part of a large ACL; central policy checks every page. */
  async privateMetadata(id:string):Promise<DriveFile> {
    const file=await this.metadata(id);
    let pageToken=''; const permissions:{type:string}[]=[];
    for(let page=0;page<20;page++) {
      const response=await this.send(`https://www.googleapis.com/drive/v3/files/${encodeURIComponent(id)}/permissions?supportsAllDrives=true&pageSize=100&fields=permissions(type),nextPageToken${pageToken?'&pageToken='+encodeURIComponent(pageToken):''}`);
      const body:any=await response.json();
      if (!Array.isArray(body.permissions)) throw new HttpsError('failed-precondition','Izin berkas Drive belum dapat diverifikasi.');
      permissions.push(...body.permissions);
      if (!body.nextPageToken) return {...file,permissions};
      pageToken=String(body.nextPageToken);
    }
    throw new HttpsError('failed-precondition','Daftar izin berkas Drive melebihi batas pemeriksaan.');
  }
  async reserveId():Promise<string> {
    const response=await this.send('https://www.googleapis.com/drive/v3/files/generateIds?count=1&space=drive&type=files');
    const body:any=await response.json();
    if (!DRIVE_FILE_ID.test(body.ids?.[0] || '')) throw new HttpsError('unavailable','Google Drive tidak memberikan File ID.');
    return body.ids[0];
  }
  async upload(id:string,folder:string,assetId:string,entityId:string,type:string,name:string,mime:string,bytes:Buffer):Promise<DriveFile> {
    const sha=createHash('sha256').update(bytes).digest('hex'),boundary='jayaguna_'+randomBytes(16).toString('hex');
    const metadata={id,name,mimeType:mime,parents:[folder],appProperties:{application:'jayaguna-admin',assetId,entityId,entityType:type,sha256:sha}};
    const body=Buffer.concat([Buffer.from(`--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(metadata)}\r\n--${boundary}\r\nContent-Type: ${mime}\r\n\r\n`),bytes,Buffer.from(`\r\n--${boundary}--\r\n`)]);
    const response=await this.send(`https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true&fields=${encodeURIComponent(fields)}`,{method:'POST',headers:{'Content-Type':`multipart/related; boundary=${boundary}`},body});
    const file=response.status===409?await this.metadata(id):await response.json() as DriveFile;
    if (file.id!==id || file.trashed || file.mimeType!==mime || !file.parents?.includes(folder) || file.appProperties?.assetId!==assetId || file.appProperties?.sha256!==sha || file.md5Checksum!==createHash('md5').update(bytes).digest('hex')) throw new HttpsError('failed-precondition','Retry unggahan tidak cocok dengan berkas yang tersimpan.');
    return file;
  }
  async read(id:string):Promise<Buffer> {
    return boundedBytes(await this.send(`https://www.googleapis.com/drive/v3/files/${encodeURIComponent(id)}?alt=media&supportsAllDrives=true`));
  }
  async trash(id:string):Promise<void> {
    const response=await this.send(`https://www.googleapis.com/drive/v3/files/${encodeURIComponent(id)}?supportsAllDrives=true`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify({trashed:true})},true);
    await response.arrayBuffer();
  }
}
