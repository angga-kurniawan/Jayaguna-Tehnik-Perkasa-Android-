import {test,before,after} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID,createHash} from 'node:crypto';
import {initializeApp,deleteApp} from 'firebase-admin/app';
import {getFirestore,Timestamp} from 'firebase-admin/firestore';
import {getAuth} from 'firebase-admin/auth';
import sharp from 'sharp';
import {HttpsError} from 'firebase-functions/v2/https';

if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST || !(process.env.GCLOUD_PROJECT || '').startsWith('demo-')) throw new Error('Test Drive wajib project demo dan emulator; menolak project produksi.');
// Explicit test fixtures; no real OAuth client or token is used in this suite.
process.env.BACKEND_PUBLIC_URL='https://backend.test';
process.env.GOOGLE_DRIVE_PRODUCTS_FOLDER_ID='products-folder-test';
process.env.GOOGLE_DRIVE_PARTNERS_FOLDER_ID='partners-folder-test';
process.env.GOOGLE_OAUTH_CLIENT_ID='test-only.apps.googleusercontent.com';
process.env.GOOGLE_OAUTH_CLIENT_SECRET='unit-test-fixture';
process.env.GOOGLE_DRIVE_ACCOUNT_EMAIL='test@example.com';
process.env.DRIVE_TOKEN_ENCRYPTION_KEY=Buffer.alloc(32,1).toString('base64');
const app=initializeApp({projectId:process.env.GCLOUD_PROJECT});
const {DriveMedia}=await import('../lib/drive/media.js');
const service=await import('../lib/service.js');
const db=getFirestore();
const actor={uid:'drive-owner-test',email:'drive-owner@example.com',name:'Owner test',role:'OWNER',authTime:Math.floor(Date.now()/1000)+1};
const device={installationId:randomUUID(),deviceName:'Test device',brand:'Test',model:'Test',androidVersion:'15',appVersion:'test'};
const req=data=>({auth:{uid:actor.uid,token:{role:actor.role,auth_time:actor.authTime}},data});
let png;
class MemoryDrive {
  files=new Map();uploads=0;throwAfterUpload=false;afterUpload;
  async folder(id){return {id};}
  async reserveId(){return 'test-file-'+randomUUID();}
  async upload(id,folder,assetId,entityId,type,name,mimeType,bytes){
    this.uploads++;
    if(!this.files.has(id))this.files.set(id,{id,name,mimeType,parents:[folder],appProperties:{assetId,entityId,entityType:type,sha256:createHash('sha256').update(bytes).digest('hex')},bytes,md5Checksum:createHash('md5').update(bytes).digest('hex')});
    if(this.afterUpload)await this.afterUpload();
    if(this.throwAfterUpload){this.throwAfterUpload=false;throw new HttpsError('unavailable','Test kehilangan response.');}
    return this.files.get(id);
  }
  async metadata(id){const file=this.files.get(id);if(!file)throw new HttpsError('not-found','Tidak ada.');return file;}
  async read(id){return (await this.metadata(id)).bytes;}
  async trash(id){const file=this.files.get(id);if(file)file.trashed=true;}
}
before(async()=>{
  try{await getAuth().deleteUser(actor.uid);}catch{}
  await getAuth().createUser({uid:actor.uid,email:actor.email});await getAuth().setCustomUserClaims(actor.uid,{role:'OWNER'});
  await db.doc('users/'+actor.uid).set({uid:actor.uid,name:actor.name,email:actor.email,role:'OWNER',active:true});
  png=await sharp({create:{width:32,height:32,channels:4,background:{r:10,g:30,b:60,alpha:0.5}}}).png().toBuffer();
});
after(async()=>{await db.terminate();await deleteApp(app);});
const product=(image)=>({name:'AC Drive Test',brand:'Test',model:'A1',category:'split',capacityPk:'1',condition:'new',price:null,description:'Test',specifications:{},images:image?[image]:[],mainImagePath:image?.storagePath || '',status:image?'active':'draft'});
async function staged(media,type='product',entityId=randomUUID(),requestId=randomUUID()){
  const input={requestId,device,entityType:type,entityId,contentType:'image/png',bytes:png.length,fileName:'test.png',sha256:createHash('sha256').update(png).digest('hex')};
  const slot=await media.begin(actor,input);return {entityId,input,id:slot.storagePath.slice(6),slot};
}
test('upload and retry produce one managed Drive file and one server audit',async()=>{
  const drive=new MemoryDrive(),media=new DriveMedia(db,drive),context=await staged(media);
  assert.deepEqual(await media.begin(actor,context.input),context.slot);
  const first=await media.upload(actor,context.id,'image/png',png),second=await media.upload(actor,context.id,'image/png',png);
  assert.deepEqual(second,first);assert.equal(drive.files.size,1);assert.equal(drive.uploads,1);
  assert.equal(first.media.provider,'drive');assert.ok(first.media.driveFileId);
  assert.equal((await db.doc('audit_logs/upload_'+context.id).get()).get('userId'),actor.uid);
  await assert.rejects(media.begin(actor,{...context.input,fileName:'changed.png'}),e=>e.code==='already-exists');
});
test('lost Drive response can retry same reserved ID without duplicate audit',async()=>{
  const drive=new MemoryDrive(),media=new DriveMedia(db,drive),context=await staged(media);drive.throwAfterUpload=true;
  await assert.rejects(media.upload(actor,context.id,'image/png',png),e=>e.code==='unavailable');
  assert.equal((await db.doc('audit_logs/upload_'+context.id).get()).exists,false);
  await media.upload(actor,context.id,'image/png',png);
  assert.equal(drive.files.size,1);assert.equal((await db.doc('audit_logs/upload_'+context.id).get()).exists,true);
});
test('Firestore entity commit attaches media atomically; draft/replace deny public bytes',async()=>{
  const drive=new MemoryDrive(),media=new DriveMedia(db,drive),context=await staged(media);
  const image=(await media.upload(actor,context.id,'image/png',png)).media;
  const requestId=randomUUID();await service.saveEntity(req({requestId,device,id:context.entityId,expectedVersion:0,data:product(image)}),'product');
  assert.equal((await db.doc('mediaAssets/'+context.id).get()).get('status'),'attached');
  assert.equal((await media.readable(context.id)).id,context.id);
  await assert.rejects(media.remove(context.id,actor,device,randomUUID()),e=>e.code==='failed-precondition');
  await service.saveEntity(req({requestId:randomUUID(),device,id:context.entityId,expectedVersion:1,data:product(null)}),'product');
  await assert.rejects(media.readable(context.id),e=>e.code==='not-found');
  assert.equal((await db.doc('mediaAssets/'+context.id).get()).get('status'),'detached');
  await media.remove(context.id,actor,device,randomUUID());
  assert.equal(drive.files.get(image.driveFileId).trashed,true);
  assert.equal((await db.doc('audit_logs/delete_'+context.id).get()).get('action'),'DELETE_PRODUCT_IMAGE');
});
test('edit conflict cannot delete media referenced by the committed version',async()=>{
  const drive=new MemoryDrive(),media=new DriveMedia(db,drive),context=await staged(media);
  const image=(await media.upload(actor,context.id,'image/png',png)).media;
  await service.saveEntity(req({requestId:randomUUID(),device,id:context.entityId,expectedVersion:0,data:product(image)}),'product');
  await assert.rejects(service.saveEntity(req({requestId:randomUUID(),device,id:context.entityId,expectedVersion:0,data:product(null)}),'product'),e=>e.code==='aborted');
  await assert.rejects(media.remove(context.id,actor,device,randomUUID()),e=>e.code==='failed-precondition');
  assert.equal(drive.files.get(image.driveFileId).trashed,undefined);
});

test('multi-image removal and replacement have distinct server audit actions',async()=>{
  const media=new DriveMedia(db,new MemoryDrive()),first=await staged(media),second=await staged(media,'product',first.entityId);
  const a=(await media.upload(actor,first.id,'image/png',png)).media,b=(await media.upload(actor,second.id,'image/png',png)).media;
  await service.saveEntity(req({requestId:randomUUID(),device,id:first.entityId,expectedVersion:0,data:{...product(a),images:[a,b]}}),'product');
  const removeId=randomUUID();
  await service.saveEntity(req({requestId:removeId,device,id:first.entityId,expectedVersion:1,data:product(b)}),'product');
  assert.equal((await db.doc(`audit_logs/${actor.uid}_${removeId}_remove_${first.id}`).get()).get('action'),'REMOVE_PRODUCT_IMAGE');
  const third=await staged(media,'product',first.entityId),c=(await media.upload(actor,third.id,'image/png',png)).media,replaceId=randomUUID();
  await service.saveEntity(req({requestId:replaceId,device,id:first.entityId,expectedVersion:2,data:product(c)}),'product');
  assert.equal((await db.doc(`audit_logs/${actor.uid}_${replaceId}_remove_${second.id}`).get()).get('action'),'REPLACE_PRODUCT_IMAGE');
  await assert.rejects(media.readable(second.id),e=>e.code==='not-found');
  assert.equal((await media.readable(third.id)).id,third.id);
});
test('Drive success followed by revoked Firestore access retains safe orphan for cleanup',async()=>{
  const drive=new MemoryDrive(),media=new DriveMedia(db,drive),context=await staged(media);
  drive.afterUpload=()=>db.doc('users/'+actor.uid).update({active:false});
  try {await assert.rejects(media.upload(actor,context.id,'image/png',png),e=>e.code==='permission-denied');}
  finally {await db.doc('users/'+actor.uid).update({active:true});drive.afterUpload=undefined;}
  assert.equal((await db.doc('audit_logs/upload_'+context.id).get()).exists,false);
  assert.equal(drive.files.size,1);
  await db.doc('mediaAssets/'+context.id).update({expiresAt:Timestamp.fromMillis(Date.now()-1000)});
  await media.cleanup(actor,device);
  assert.equal((await db.doc('mediaAssets/'+context.id).get()).get('status'),'deleted');
});
test('logo replacement preserves old reference when the new upload fails',async()=>{
  const drive=new MemoryDrive(),media=new DriveMedia(db,drive),first=await staged(media,'partner');
  const logo=(await media.upload(actor,first.id,'image/png',png)).media;
  const partner={name:'Partner Drive Test',logo,altText:'Logo test',website:'',displayOrder:1,active:true};
  await service.saveEntity(req({requestId:randomUUID(),device,id:first.entityId,expectedVersion:0,data:partner}),'partner');
  const next=await staged(media,'partner',first.entityId);
  await assert.rejects(media.upload(actor,next.id,'image/png',Buffer.alloc(png.length)),e=>e.code==='invalid-argument');
  assert.equal((await db.doc('partners/'+first.entityId).get()).get('logo.driveFileId'),logo.driveFileId);
  assert.equal((await media.readable(first.id)).id,first.id);
});
