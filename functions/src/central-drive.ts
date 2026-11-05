import {createHash} from 'node:crypto';
import {Firestore,FieldValue,Timestamp} from 'firebase-admin/firestore';
import {HttpsError} from 'firebase-functions/v2/https';
import sharp from 'sharp';
import {ApprovedActor} from './approved-access';
import {ASSET_ID,DRIVE_FILE_ID,COMPANY_DRIVE_EMAIL,COMPANY_DRIVE_FOLDERS,CompanyEntityType,centralDriveConfig} from './drive/config';
import {DriveApi,DriveFile} from './drive/transport';
import {IMAGE_TYPES,MAX_BYTES,imageSignature} from './drive/image';
import {oauthStatus} from './drive/oauth';

type Asset=Record<string,any> & {id:string;driveFileId:string;entityType:CompanyEntityType;entityId:string};
type Media={storagePath:string;url:string;provider:string;driveFileId:string;fileName:string;mimeType:string;sizeBytes:number};
type DriveOperations=Pick<DriveApi,'privateMetadata'|'reserveId'|'upload'|'read'|'trash'>;
const hash=(bytes:Buffer)=>createHash('sha256').update(bytes).digest('hex');
const invalid=()=>new HttpsError('failed-precondition','Registrasi gambar tidak valid atau tidak cocok dengan folder perusahaan.');
const collection=(type:CompanyEntityType)=>type==='product'?'products':type==='partner'?'partners':'portfolioImages';
const references=(data:Record<string,any>|undefined):any[]=>Array.isArray(data?.images)?data.images:[data?.logo || data?.image].filter(Boolean);

/** A Drive credential never leaves this adapter; every image operation uses a Firestore asset UUID. */
export class CentralDrive {
  constructor(private readonly db:Firestore,private readonly drive:DriveOperations=new DriveApi(),
    private readonly connection=oauthStatus,private readonly validateConfig:()=>unknown=centralDriveConfig,private readonly now=Date.now) {}

  private async folder(id:string) {
    const folder=await this.drive.privateMetadata(id);
    if (folder.id!==id || folder.trashed || folder.mimeType!=='application/vnd.google-apps.folder' || !folder.capabilities?.canAddChildren ||
        !folder.owners?.some(owner=>owner.emailAddress?.toLowerCase()===COMPANY_DRIVE_EMAIL) ||
        folder.permissions?.some(permission=>['anyone','domain'].includes(permission.type))) throw invalid();
  }

  async status() {
    this.validateConfig();
    const connection=await this.connection();
    if (!connection.connected) return {...connection,foldersAccessible:false};
    await Promise.all(Object.values(COMPANY_DRIVE_FOLDERS).map(id=>this.folder(id)));
    return {...connection,foldersAccessible:true};
  }

  async reserve(actor:ApprovedActor) {
    this.validateConfig();
    const id=await this.drive.reserveId();
    if (!DRIVE_FILE_ID.test(id)) throw invalid();
    await this.db.doc('driveReservations/'+id).create({uid:actor.uid,createdAt:FieldValue.serverTimestamp(),expiresAt:Timestamp.fromMillis(this.now()+24*60*60*1000)});
    return {id};
  }

  private async asset(id:string):Promise<Asset> {
    this.validateConfig();
    if (!ASSET_ID.test(id)) throw new HttpsError('invalid-argument','ID gambar tidak valid.');
    const doc=await this.db.doc('mediaAssets/'+id).get(),data=doc.data();
    if (!data) throw new HttpsError('not-found','Gambar tidak ditemukan.');
    if (data.id!==id || !DRIVE_FILE_ID.test(data.driveFileId || '') || !ASSET_ID.test(data.entityId || '') ||
        !Object.hasOwn(COMPANY_DRIVE_FOLDERS,data.entityType) || data.folder!==COMPANY_DRIVE_FOLDERS[data.entityType as CompanyEntityType] ||
        data.source!=='android') throw invalid();
    return {...data,id} as Asset;
  }

  private assertFile(asset:Asset,file:DriveFile,allowTrashed=false) {
    const mime=asset.mimeType || asset.expectedMime,size=asset.sizeBytes || asset.expectedBytes,sha=asset.sha256 || asset.originalSha256;
    if (file.id!==asset.driveFileId || file.trashed && !allowTrashed || !IMAGE_TYPES.includes(file.mimeType) || file.mimeType!==mime ||
        !file.parents?.includes(asset.folder) || Number(file.size)!==size || size<1 || size>MAX_BYTES || !/^[a-f0-9]{64}$/.test(sha || '') ||
        file.permissions?.some(permission=>['anyone','domain'].includes(permission.type))) throw invalid();
    // Old native OAuth clients have private appProperties invisible to this OAuth client.
    // Registry + exact parent, size and downloaded bytes remain mandatory in that case.
    const properties=file.appProperties || {};
    if (Object.keys(properties).length && (properties.application!=='jayaguna-admin' || properties.assetId!==asset.id ||
        properties.entityId!==asset.entityId || properties.entityType!==asset.entityType || properties.sha256!==sha)) throw invalid();
  }

  async upload(actor:ApprovedActor,id:string,mime:string,bytes:Buffer):Promise<Media> {
    const asset=await this.asset(id),reservation=await this.db.doc('driveReservations/'+asset.driveFileId).get();
    if (asset.createdBy!==actor.uid || !['reserved','uploaded','attached'].includes(asset.status) || !reservation.exists || reservation.get('uid')!==actor.uid) {
      throw new HttpsError('permission-denied','Sesi unggah bukan milik akun ini.');
    }
    if (asset.status==='reserved' && (!asset.expiresAt?.toMillis || asset.expiresAt.toMillis()<=this.now() ||
        !reservation.get('expiresAt')?.toMillis || reservation.get('expiresAt').toMillis()<=this.now())) throw new HttpsError('failed-precondition','Sesi unggah telah berakhir.');
    if (!IMAGE_TYPES.includes(mime) || mime!==asset.expectedMime || bytes.length!==asset.expectedBytes || bytes.length<1 || bytes.length>MAX_BYTES ||
        hash(bytes)!==asset.originalSha256 || !imageSignature(bytes,mime)) throw new HttpsError('invalid-argument','Isi gambar tidak cocok dengan reservasi unggahan.');
    try {
      const image=sharp(bytes,{limitInputPixels:40_000_000,animated:false,failOn:'warning'});
      const metadata=await image.metadata();
      if (!metadata.width || !metadata.height || metadata.width*metadata.height>40_000_000 || (metadata.pages || 1)>1) throw new Error('image');
      await image.stats(); // Decode to validate without resizing or changing the reserved bytes/hash.
    } catch { throw new HttpsError('invalid-argument','Gambar rusak atau dimensinya terlalu besar.'); }
    const name=asset.originalFileName;
    if (typeof name!=='string' || !name.trim() || name.length>120 || /[\r\n]/.test(name)) throw invalid();
    const dot=name.lastIndexOf('.'),stem=(dot<0?name:name.slice(0,dot)).replace(/[^A-Za-z0-9._ -]/g,'_').slice(0,90) || 'gambar';
    const storedName=stem+'.'+(mime==='image/png'?'png':mime==='image/jpeg'?'jpg':'webp');
    await this.folder(asset.folder);
    const file=await this.drive.upload(asset.driveFileId,asset.folder,id,asset.entityId,asset.entityType,storedName,mime,bytes);
    this.assertFile(asset,file);
    return {storagePath:'drive/'+id,url:'',provider:'drive',driveFileId:file.id,fileName:file.name,mimeType:mime,sizeBytes:bytes.length};
  }

  async metadata(actor:ApprovedActor,id:string) {
    const asset=await this.asset(id);
    if (asset.status==='deleted' || !['reserved','uploaded','attached','detached','deleting'].includes(asset.status)) throw new HttpsError('not-found','Gambar tidak tersedia.');
    if (asset.status==='reserved' && asset.createdBy!==actor.uid) throw new HttpsError('permission-denied','Sesi unggah bukan milik akun ini.');
    const file=await this.drive.privateMetadata(asset.driveFileId);
    this.assertFile(asset,file);
    return file;
  }

  async read(actor:ApprovedActor,id:string) {
    const asset=await this.asset(id);
    if (!['uploaded','attached','detached'].includes(asset.status)) throw new HttpsError('not-found','Gambar tidak tersedia.');
    const file=await this.drive.privateMetadata(asset.driveFileId);
    this.assertFile(asset,file);
    const bytes=await this.drive.read(asset.driveFileId);
    if (bytes.length!==asset.sizeBytes || hash(bytes)!==asset.sha256 || !imageSignature(bytes,asset.mimeType) ||
        file.md5Checksum && createHash('md5').update(bytes).digest('hex')!==file.md5Checksum) throw invalid();
    return {bytes,mimeType:asset.mimeType,sha256:asset.sha256};
  }

  async trash(actor:ApprovedActor,id:string) {
    const asset=await this.asset(id);
    if (asset.status==='deleted') return {success:true};
    if (asset.status!=='deleting' || asset.deleteActorUid!==actor.uid || asset.createdBy!==actor.uid && actor.role!=='OWNER') {
      throw new HttpsError('permission-denied','Penghapusan gambar belum direservasi oleh akun ini.');
    }
    const entity=await this.db.doc(collection(asset.entityType)+'/'+asset.entityId).get();
    if (references(entity.data()).some(media=>media?.storagePath==='drive/'+id)) throw new HttpsError('failed-precondition','Gambar masih dipakai oleh data katalog.');
    const deletionMetadata=async()=>{
      try { return await this.drive.privateMetadata(asset.driveFileId); }
      catch(error) {
        // Drive returns 404 for both absent files and files hidden by lost access.
        // Retain the deleting registry entry until trash is positively confirmed.
        if (error instanceof HttpsError && error.code==='not-found') throw new HttpsError('failed-precondition','Penghapusan belum dapat diverifikasi. Pulihkan akses Drive lalu coba pembersihan kembali.');
        throw error;
      }
    };
    const file=await deletionMetadata();
    this.assertFile(asset,file,true);
    if (file.trashed) return {success:true};
    if (!file.capabilities?.canTrash) throw new HttpsError('permission-denied','Drive pusat tidak dapat memindahkan gambar ini ke sampah.');
    const bytes=await this.drive.read(asset.driveFileId);
    if (bytes.length!==asset.sizeBytes || hash(bytes)!==asset.sha256 || !imageSignature(bytes,asset.mimeType)) throw invalid();
    await this.drive.trash(asset.driveFileId);
    // The shared legacy transport accepts a missing PATCH target. A matching
    // readable trashed file is required before the Android registry can finish.
    const confirmed=await deletionMetadata();
    this.assertFile(asset,confirmed,true);
    if (confirmed.trashed!==true) throw new HttpsError('failed-precondition','Drive belum mengonfirmasi gambar berada di sampah. Coba pembersihan kembali.');
    return {success:true};
  }
}
