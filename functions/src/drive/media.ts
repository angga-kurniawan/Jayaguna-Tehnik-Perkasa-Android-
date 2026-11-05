import {randomUUID,createHash} from 'node:crypto';
import {getFirestore,Firestore,Transaction,FieldValue,Timestamp} from 'firebase-admin/firestore';
import {HttpsError} from 'firebase-functions/v2/https';
import {DriveActor} from './oauth';
import {DriveApi} from './transport';
import {driveConfig,backendUrl,ASSET_ID} from './config';
import {normalizeImage} from './image';
import {Image,Device,EntityType,fingerprint} from '../schema';

type UploadInput={requestId:string;device:Device;entityType:EntityType;entityId:string;contentType:string;bytes:number;fileName?:string;sha256?:string};
export const driveAssetId=(path:string):string|null=>path.startsWith('drive/') && ASSET_ID.test(path.slice(6))?path.slice(6):null;
function references(data:any):Image[] { return data?.images || (data?.logo?[data.logo]:[]); }
export function isReferenced(data:any,path:string):boolean { return references(data).some(image=>image.storagePath===path); }
function imageRecord(id:string,asset:any):Image {
  return {storagePath:'drive/'+id,url:backendUrl()+'/api/media/'+id,provider:'drive',driveFileId:asset.driveFileId,fileName:asset.fileName,mimeType:asset.mimeType,sizeBytes:asset.sizeBytes};
}
export async function assertActor(tx:Transaction,db:Firestore,actor:DriveActor) {
  const profile=(await tx.get(db.doc('users/'+actor.uid))).data();
  if (!profile?.active || profile.role!==actor.role || (profile.revokedBeforeSeconds && actor.authTime<=profile.revokedBeforeSeconds)) throw new HttpsError('permission-denied','Akses akun sudah berubah.');
}
export function mediaAudit(tx:Transaction,db:Firestore,id:string,actor:DriveActor,device:Device,action:string,asset:any,extra:Record<string,unknown>={}) {
  tx.create(db.doc('audit_logs/'+id),{userId:actor.uid,userEmail:actor.email,actorName:actor.name,actorRole:actor.role,
    action,targetId:asset.entityId,entityType:asset.entityType,driveFileId:asset.driveFileId || '',mediaId:asset.id,
    deviceId:device.installationId,device,status:'SUCCESS',timestamp:FieldValue.serverTimestamp(),...extra});
}

export class DriveMedia {
  constructor(readonly db:Firestore=getFirestore(),readonly drive:DriveApi=new DriveApi()) {}
  async begin(actor:DriveActor,value:UploadInput) {
    const ref=this.db.doc(`driveUploadRequests/${actor.uid}_${value.requestId}`), hash=fingerprint(value);
    const existing=await ref.get();
    if (existing.exists) {
      if (existing.get('hash')!==hash) throw new HttpsError('already-exists','requestId unggahan sudah digunakan untuk berkas berbeda.');
      return {storagePath:'drive/'+existing.get('assetId')};
    }
    const config=driveConfig(), folder=value.entityType==='product'?config.products:config.partners;
    await this.drive.folder(folder);
    const driveFileId=await this.drive.reserveId(), id=randomUUID();
    return this.db.runTransaction(async tx=>{
      await assertActor(tx,this.db,actor);
      const old=await tx.get(ref);
      if (old.exists) {
        if (old.get('hash')!==hash) throw new HttpsError('already-exists','requestId unggahan berbeda.');
        return {storagePath:'drive/'+old.get('assetId')};
      }
      tx.create(ref,{hash,assetId:id,createdAt:FieldValue.serverTimestamp()});
      tx.create(this.db.doc('mediaAssets/'+id),{id,driveFileId,folder,entityType:value.entityType,entityId:value.entityId,createdBy:actor.uid,
        device:value.device,requestId:value.requestId,status:'reserved',expectedBytes:value.bytes,expectedMime:value.contentType,
        originalSha256:value.sha256 || '',originalFileName:(value.fileName || 'gambar').slice(0,120),
        createdAt:FieldValue.serverTimestamp(),expiresAt:Timestamp.fromMillis(Date.now()+24*60*60*1000)});
      return {storagePath:'drive/'+id};
    });
  }
  async upload(actor:DriveActor,id:string,mime:string,raw:Buffer) {
    if (!ASSET_ID.test(id)) throw new HttpsError('invalid-argument','ID media tidak valid.');
    const ref=this.db.doc('mediaAssets/'+id), snapshot=await ref.get(), asset=snapshot.data();
    if (!asset || asset.createdBy!==actor.uid) throw new HttpsError('permission-denied','Sesi unggah bukan milik akun ini.');
    if (asset.expectedBytes!==raw.length || asset.expectedMime!==mime) throw new HttpsError('invalid-argument','Ukuran atau format berkas tidak cocok dengan sesi unggah.');
    const originalHash=createHash('sha256').update(raw).digest('hex');
    const normalized=await normalizeImage(raw,mime);
    await this.db.runTransaction(async tx=>{
      await assertActor(tx,this.db,actor);
      const latest=(await tx.get(ref)).data()!;
      if (['deleting','deleted'].includes(latest.status) || (!['uploaded','attached'].includes(latest.status) && latest.expiresAt.toMillis()<Date.now())) throw new HttpsError('failed-precondition','Sesi unggah berakhir; pilih ulang gambar.');
      if (latest.originalSha256 && latest.originalSha256!==originalHash) throw new HttpsError('already-exists','Retry harus memakai berkas yang sama.');
      tx.update(ref,{originalSha256:originalHash});
    });
    if (['uploaded','attached'].includes(asset.status)) return {media:imageRecord(id,asset)};
    const fileName=asset.originalFileName.replace(/[^a-zA-Z0-9._ -]/g,'_').replace(/\.[^.]*$/,'').slice(0,90)+'.'+normalized.extension;
    // The Drive file ID was reserved before sending bytes. A lost response can
    // safely retry that same ID; Drive returns409 and metadata is verified.
    await this.drive.upload(asset.driveFileId,asset.folder,id,asset.entityId,asset.entityType,fileName,mime,normalized.bytes);
    return this.db.runTransaction(async tx=>{
      await assertActor(tx,this.db,actor);
      const latest=(await tx.get(ref)).data()!;
      if (['uploaded','attached'].includes(latest.status)) return {media:imageRecord(id,latest)};
      if (['deleting','deleted'].includes(latest.status)) throw new HttpsError('aborted','Sesi media sedang dibersihkan.');
      const next={...latest,fileName,mimeType:mime,sizeBytes:normalized.bytes.length,sha256:normalized.sha256,status:'uploaded',uploadedAt:FieldValue.serverTimestamp()};
      tx.update(ref,{fileName,mimeType:mime,sizeBytes:normalized.bytes.length,sha256:normalized.sha256,status:'uploaded',uploadedAt:FieldValue.serverTimestamp()});
      mediaAudit(tx,this.db,'upload_'+id,actor,asset.device,asset.entityType==='product'?'UPLOAD_PRODUCT_IMAGE':'UPLOAD_PARTNER_LOGO',next,{requestId:asset.requestId});
      return {media:imageRecord(id,next)};
    });
  }
  async promote(actor:DriveActor,type:EntityType,entityId:string,image:Image):Promise<Image> {
    const id=driveAssetId(image.storagePath);
    if (!id) throw new HttpsError('invalid-argument','Referensi media Drive tidak valid.');
    const asset=(await this.db.doc('mediaAssets/'+id).get()).data();
    if (!asset || asset.entityType!==type || asset.entityId!==entityId || !['uploaded','attached'].includes(asset.status)) throw new HttpsError('failed-precondition','Gambar belum selesai diunggah atau sudah dihapus.');
    if (asset.status==='uploaded' && asset.createdBy!==actor.uid) throw new HttpsError('permission-denied','Unggahan belum dimiliki entitas ini.');
    return imageRecord(id,asset);
  }
  async transactionChanges(tx:Transaction,actor:DriveActor,device:Device,type:EntityType,entityId:string,requestId:string,before:any,after:any):Promise<()=>void> {
    const previous=references(before).filter(i=>driveAssetId(i.storagePath)), next=references(after).filter(i=>driveAssetId(i.storagePath));
    const hasReplacement=next.some(image=>!previous.some(old=>old.storagePath===image.storagePath));
    const paths=[...new Set([...previous,...next].map(i=>i.storagePath))];
    const docs=await Promise.all(paths.map(path=>tx.get(this.db.doc('mediaAssets/'+driveAssetId(path)))));
    const updates:{ref:FirebaseFirestore.DocumentReference;patch:FirebaseFirestore.UpdateData<FirebaseFirestore.DocumentData>}[]=[], audits:{id:string;action:string;asset:any}[]=[];
    for (const doc of docs) {
      const asset=doc.data(), path='drive/'+doc.id, included=next.some(i=>i.storagePath===path), was=previous.some(i=>i.storagePath===path);
      if (!asset || asset.entityId!==entityId || asset.entityType!==type) throw new HttpsError('failed-precondition','Referensi gambar tidak cocok dengan entitas.');
      if (included) {
        if (!['uploaded','attached'].includes(asset.status) || (asset.status==='uploaded' && asset.createdBy!==actor.uid)) throw new HttpsError('aborted','Media berubah atau sedang dihapus; muat ulang data.');
        updates.push({ref:doc.ref,patch:{status:'attached',attachedAt:FieldValue.serverTimestamp()}});
      } else if (was && asset.status!=='deleted') {
        updates.push({ref:doc.ref,patch:{status:'detached',detachedAt:FieldValue.serverTimestamp(),deleteAfter:Timestamp.fromMillis(Date.now()+24*60*60*1000)}});
        audits.push({id:`${actor.uid}_${requestId}_remove_${doc.id}`,action:hasReplacement?(type==='product'?'REPLACE_PRODUCT_IMAGE':'REPLACE_PARTNER_LOGO'):(type==='product'?'REMOVE_PRODUCT_IMAGE':'REMOVE_PARTNER_LOGO'),asset});
      }
    }
    // Defer writes until the caller has completed all Firestore reads.
    return ()=>{
      for (const update of updates) tx.update(update.ref,update.patch);
      for (const audit of audits) mediaAudit(tx,this.db,audit.id,actor,device,audit.action,audit.asset,{requestId});
    };
  }
  async readable(id:string,actor?:DriveActor) {
    if (!ASSET_ID.test(id)) throw new HttpsError('not-found','Gambar tidak ditemukan.');
    const asset=(await this.db.doc('mediaAssets/'+id).get()).data();
    if (!asset || ['reserved','deleting','deleted'].includes(asset.status) || !asset.mimeType) throw new HttpsError('not-found','Gambar tidak tersedia.');
    if (!actor) {
      const projection=await this.db.doc(`${asset.entityType==='product'?'publicProducts':'publicPartners'}/${asset.entityId}`).get();
      const data=projection.data();
      if (!data || data.archived || (asset.entityType==='product'?data.status!=='active':data.active!==true) || !isReferenced(data,'drive/'+id)) throw new HttpsError('not-found','Gambar tidak tersedia.');
    }
    return asset;
  }
  async metadata(id:string,actor:DriveActor) {
    const asset=await this.readable(id,actor);
    const file=await this.drive.metadata(asset.driveFileId);
    this.verifyFile(asset,file);
    return {media:imageRecord(id,asset),state:asset.status};
  }
  private verifyFile(asset:any,file:any) {
    if (file.trashed || !file.parents?.includes(asset.folder) || file.appProperties?.assetId!==asset.id || file.appProperties?.sha256!==asset.sha256 || file.mimeType!==asset.mimeType) throw new HttpsError('failed-precondition','Berkas Drive berubah atau tidak lagi tersedia.');
  }
  async bytes(asset:any) {
    const file=await this.drive.metadata(asset.driveFileId); this.verifyFile(asset,file);
    const bytes=await this.drive.read(asset.driveFileId);
    if (createHash('sha256').update(bytes).digest('hex')!==asset.sha256) throw new HttpsError('failed-precondition','Isi berkas berubah di Google Drive.');
    return bytes;
  }
  async remove(id:string,actor:DriveActor,device:Device,requestId:string) {
    if (!ASSET_ID.test(id)) throw new HttpsError('invalid-argument','ID media tidak valid.');
    const ref=this.db.doc('mediaAssets/'+id);
    const asset=await this.db.runTransaction(async tx=>{
      await assertActor(tx,this.db,actor);
      const doc=await tx.get(ref), asset=doc.data();
      if (!asset) throw new HttpsError('not-found','Media tidak ditemukan.');
      if (asset.createdBy!==actor.uid && actor.role!=='OWNER') throw new HttpsError('permission-denied','Hanya pembuat unggahan atau OWNER dapat membersihkan media ini.');
      const entity=(await tx.get(this.db.doc(`${asset.entityType==='product'?'products':'partners'}/${asset.entityId}`))).data();
      // Archived documents count as references too; no image in use is deleted.
      if (isReferenced(entity,'drive/'+id)) throw new HttpsError('failed-precondition','Gambar masih digunakan. Simpan penggantian/penghapusan referensi lebih dahulu.');
      if (asset.status!=='deleted') tx.update(ref,{status:'deleting',deleteActor:actor,deleteDevice:device,deleteRequestId:requestId});
      return asset;
    });
    if (asset.status==='deleted') return {deleted:true};
    try { const file=await this.drive.metadata(asset.driveFileId); if (file.appProperties?.assetId!==id || !file.parents?.includes(asset.folder)) throw new HttpsError('failed-precondition','Berkas bukan milik media terkelola.'); await this.drive.trash(asset.driveFileId); }
    catch (error) { if (!(error instanceof HttpsError) || error.code!=='not-found') throw error; }
    await this.db.runTransaction(async tx=>{
      await assertActor(tx,this.db,actor);
      const latest=(await tx.get(ref)).data()!;
      if (latest.status==='deleted') return;
      tx.update(ref,{status:'deleted',deletedAt:FieldValue.serverTimestamp()});
      mediaAudit(tx,this.db,'delete_'+id,actor,device,asset.entityType==='product'?'DELETE_PRODUCT_IMAGE':'DELETE_PARTNER_LOGO',asset,{requestId});
    });
    return {deleted:true};
  }
  async cleanup(actor:DriveActor,device:Device) {
    if (actor.role!=='OWNER') throw new HttpsError('permission-denied','Hanya OWNER dapat menjalankan cleanup.');
    // Bounded maintenance batches; safe to repeat and schedule from trusted host.
    const now=Timestamp.now();
    const batches=await Promise.all([
      this.db.collection('mediaAssets').where('status','in',['reserved','uploaded']).where('expiresAt','<=',now).limit(40).get(),
      this.db.collection('mediaAssets').where('status','==','detached').where('deleteAfter','<=',now).limit(40).get(),
      this.db.collection('mediaAssets').where('status','==','deleting').limit(20).get(),
    ]);
    const docs=batches.flatMap(batch=>batch.docs);
    let deleted=0,retained=0;
    for (const doc of docs) {
      const asset=doc.data();
      const due=asset.status==='deleting' || (asset.status==='detached'?asset.deleteAfter?.toMillis():asset.expiresAt?.toMillis())<Date.now();
      if (!due) { retained++;continue; }
      try { await this.remove(doc.id,actor,device,randomUUID());deleted++; }
      catch (error) { if (error instanceof HttpsError && ['failed-precondition','permission-denied'].includes(error.code)) retained++; else throw error; }
    }
    return {deleted,retained,scanned:docs.length};
  }
}
