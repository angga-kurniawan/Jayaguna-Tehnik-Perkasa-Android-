import {before, after, test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {initializeTestEnvironment, assertFails, assertSucceeds} from '@firebase/rules-unit-testing';
import {getFirestore as adminFirestore} from 'firebase-admin/firestore';
import {getAuth as adminAuth} from 'firebase-admin/auth';
import {collection, doc, getDoc, getDocs, query, where, limit, setDoc, updateDoc, onSnapshot} from 'firebase/firestore';
import {ref, uploadBytes, getBytes} from 'firebase/storage';
import sharp from 'sharp';
import service from '../lib/service.js';

if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST || !process.env.FIREBASE_STORAGE_EMULATOR_HOST) throw new Error('Jalankan hanya melalui Firebase emulators:exec --only auth,firestore,storage.');
const projectId=process.env.GCLOUD_PROJECT || 'demo-jayaguna';
const storageBucket=`${projectId}.appspot.com`;
let env;
const ownerUid='owner-test', adminUid='admin-test', otherUid='admin-other';
const device={installationId:randomUUID(),deviceName:'Ponsel Kantor',brand:'Test',model:'Model',androidVersion:'15',appVersion:'1.0'};
const request=(uid,role,data)=>({auth:{uid,token:{role,auth_time:Math.floor(Date.now()/1000)+1}},data});
const context=()=>({requestId:randomUUID(),device});
const draft=()=>({name:'AC Inverter',brand:'Daikin',model:'FTK',category:'split',capacityPk:'1',condition:'new',price:3500000,description:'Unit uji emulator',specifications:{Daya:'350 W'},images:[],mainImagePath:'',status:'draft'});
before(async()=>{
  env=await initializeTestEnvironment({projectId,firestore:{rules:await readFile('../firestore.rules','utf8')},storage:{rules:await readFile('../storage.rules','utf8')}});
  await env.clearFirestore();
  for(const [uid,role] of [[ownerUid,'OWNER'],[adminUid,'ADMIN'],[otherUid,'ADMIN'],['no-role',null]]) {
    try{await adminAuth().deleteUser(uid);}catch{}
    await adminAuth().createUser({uid,email:`${uid}@example.com`});
    if(role) await adminAuth().setCustomUserClaims(uid,{role});
    if(role) await adminFirestore().doc(`users/${uid}`).set({uid,name:uid,email:`${uid}@example.com`,role,active:true,version:1});
  }
});
after(async()=>{await env?.cleanup();});

test('backend menolak tanpa autentikasi, tanpa role dan akun dinonaktifkan',async()=>{
  await assert.rejects(service.getSession({data:{}}),e=>e.code==='unauthenticated');
  await assert.rejects(service.getSession(request('no-role',undefined,{})),e=>e.code==='permission-denied');
  await adminFirestore().doc(`users/${otherUid}`).update({active:false});
  await assert.rejects(service.getSession(request(otherUid,'ADMIN',{})),e=>e.code==='permission-denied');
  await adminFirestore().doc(`users/${otherUid}`).update({active:true});
});
test('ADMIN tidak dapat mengelola akun, memperbarui profil sendiri atau memalsukan audit',async()=>{
  await assert.rejects(service.manageAdmin(request(adminUid,'ADMIN',{...context(),operation:'create',email:'evil@example.com',name:'Evil'})),e=>e.code==='permission-denied');
  const db=env.authenticatedContext(adminUid,{role:'ADMIN'}).firestore();
  await assertFails(updateDoc(doc(db,'users',adminUid),{role:'OWNER'}));
  await assertFails(setDoc(doc(db,'activityLogs','forged'),{actorUid:adminUid,action:'add'}));
  await assertFails(setDoc(doc(db,'audit_logs','forged'),{userId:adminUid,action:'UPLOAD_PRODUCT_IMAGE'}));
  await assertFails(getDoc(doc(db,'backendSecrets','googleDrive')));
  // Native clients need registry metadata; OAuth secrets remain denied above.
  await assertSucceeds(getDoc(doc(db,'mediaAssets',randomUUID())));
  await assertFails(setDoc(doc(db,'products',randomUUID()),draft()));
});
test('simpan atomic, timestamp server, identitas terverifikasi, retry satu audit, konflik ditolak',async()=>{
  const id=randomUUID(), data={...context(),id,expectedVersion:0,data:draft()};
  const first=await service.saveEntity(request(adminUid,'ADMIN',data),'product');
  assert.equal(first.version,1);
  assert.deepEqual(await service.saveEntity(request(adminUid,'ADMIN',data),'product'),first);
  const logs=await adminFirestore().collection('activityLogs').where('requestId','==',data.requestId).get();
  assert.equal(logs.size,1);
  const audit=logs.docs[0].data();
  assert.equal(audit.actorUid,adminUid);assert.equal(audit.actorRole,'ADMIN');
  assert.equal(audit.device.installationId,device.installationId);assert.equal(audit.deviceName,device.deviceName);
  assert.ok(audit.timestamp.toMillis()<=Date.now());assert.equal(audit.changedFields.name.after,'AC Inverter');
  await assert.rejects(service.saveEntity(request(adminUid,'ADMIN',{...data,data:{...data.data,name:'Berbeda'}}),'product'),e=>e.code==='already-exists');
  await assert.rejects(service.saveEntity(request(adminUid,'ADMIN',{...data,...context(),data:{...data.data,name:'Stale'}}),'product'),e=>e.code==='aborted');
  assert.equal((await adminFirestore().doc(`products/${id}`).get()).get('name'),'AC Inverter');
});
test('pengunjung hanya membaca proyeksi aktif; query tanpa batas/status dan metadata internal ditolak',async()=>{
  const db=env.unauthenticatedContext().firestore();
  await assertFails(getDocs(query(collection(db,'products'),limit(20))));
  await assertFails(getDocs(query(collection(db,'users'),limit(20))));
  await assertFails(getDocs(query(collection(db,'activityLogs'),limit(20))));
  await assertFails(getDocs(query(collection(db,'publicProducts'),limit(20))));
  await assertFails(getDocs(query(collection(db,'publicProducts'),where('status','==','active'),where('archived','==',false))));
  await assertSucceeds(getDocs(query(collection(db,'publicProducts'),where('status','==','active'),where('archived','==',false),limit(20))));
});
test('ADMIN membaca audit dirinya sendiri; OWNER dapat membaca semua',async()=>{
  const a=env.authenticatedContext(adminUid,{role:'ADMIN'}).firestore();
  await assertSucceeds(getDocs(query(collection(a,'activityLogs'),where('actorUid','==',adminUid),limit(20))));
  await assertFails(getDocs(query(collection(a,'activityLogs'),limit(20))));
  const o=env.authenticatedContext(ownerUid,{role:'OWNER'}).firestore();
  await assertSucceeds(getDocs(query(collection(o,'activityLogs'),limit(20))));
});
test('token sesi lama tetap ditolak setelah akun dinonaktifkan lalu diaktifkan',async()=>{
  const cutoff=Math.floor(Date.now()/1000);
  await adminFirestore().doc(`users/${otherUid}`).update({active:true,revokedBeforeSeconds:cutoff});
  const stale=env.authenticatedContext(otherUid,{role:'ADMIN',auth_time:cutoff-10}).firestore();
  const fresh=env.authenticatedContext(otherUid,{role:'ADMIN',auth_time:cutoff+1}).firestore();
  await assertFails(getDocs(query(collection(stale,'products'),limit(20))));
  await assertSucceeds(getDocs(query(collection(fresh,'products'),limit(20))));
  await adminFirestore().doc(`users/${otherUid}`).update({revokedBeforeSeconds:0});
});
async function stagedImage(id,type='product') {
  const png=await sharp({create:{width:32,height:32,channels:4,background:{r:0,g:140,b:160,alpha:0.5}}}).png().toBuffer();
  const upload=await service.beginUpload(request(adminUid,'ADMIN',{...context(),entityType:type,entityId:id,contentType:'image/png',bytes:png.length}));
  const storage=env.authenticatedContext(adminUid,{role:'ADMIN'}).storage(`gs://${storageBucket}`);
  await assertSucceeds(uploadBytes(ref(storage,upload.storagePath),png,{contentType:'image/png'}));
  await assertFails(uploadBytes(ref(storage,upload.storagePath),png,{contentType:'image/png'}));
  return {storagePath:upload.storagePath,url:''};
}
function waitForPublic(type,id) {
  const db=env.unauthenticatedContext().firestore();
  const isProduct=type==='product';
  const q=query(collection(db,isProduct?'publicProducts':'publicPartners'),where(isProduct?'status':'active','==',isProduct?'active':true),where('archived','==',false),limit(100));
  let unsub;
  const promise=new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{unsub?.();reject(new Error('Listener tidak menerima perubahan dalam 10 detik'));},10000);
    unsub=onSnapshot(q,s=>{const found=s.docs.find(d=>d.id===id);if(found){clearTimeout(timer);unsub?.();resolve(found.data());}},e=>{clearTimeout(timer);reject(e);});
  });
  return promise;
}
test('unggah staging, publikasi realtime produk, cabut publikasi menolak foto publik',async()=>{
  const id=randomUUID(),image=await stagedImage(id), observed=waitForPublic('product',id);
  await service.saveEntity(request(adminUid,'ADMIN',{...context(),id,expectedVersion:0,data:{...draft(),status:'active',images:[image],mainImagePath:image.storagePath}}),'product');
  const publicDoc=await observed;
  assert.equal(publicDoc.updatedBy,undefined);assert.equal(publicDoc.device,undefined);assert.equal(publicDoc.images[0].url,'');
  assert.ok(publicDoc.mainImagePath.startsWith(`media/product/${id}/`));
  const storage=env.unauthenticatedContext().storage(`gs://${storageBucket}`);
  await assertSucceeds(getBytes(ref(storage,publicDoc.mainImagePath),5*1024*1024));
  const saved=(await adminFirestore().doc(`products/${id}`).get()).data();
  await service.saveEntity(request(adminUid,'ADMIN',{...context(),id,expectedVersion:1,data:{...draft(),status:'draft',images:saved.images,mainImagePath:saved.mainImagePath}}),'product');
  await assertFails(getBytes(ref(storage,publicDoc.mainImagePath),5*1024*1024));
  const privateStorage=env.authenticatedContext(adminUid,{role:'ADMIN'}).storage(`gs://${storageBucket}`);
  await assertSucceeds(getBytes(ref(privateStorage,publicDoc.mainImagePath),5*1024*1024));
});
test('mitra muncul realtime, arsip atomik menyembunyikan proyeksi dan logo',async()=>{
  const id=randomUUID(),logo=await stagedImage(id,'partner'),observed=waitForPublic('partner',id);
  await service.saveEntity(request(adminUid,'ADMIN',{...context(),id,expectedVersion:0,data:{name:'Mitra Uji',logo,altText:'Logo Mitra Uji',website:'https://example.com',displayOrder:0,active:true}}),'partner');
  const publicDoc=await observed;
  assert.equal(publicDoc.createdBy,undefined);assert.equal(publicDoc.logo.url,'');
  await service.archiveEntity(request(adminUid,'ADMIN',{...context(),id,expectedVersion:1}),'partner');
  assert.equal((await adminFirestore().doc(`publicPartners/${id}`).get()).exists,false);
  const storage=env.unauthenticatedContext().storage(`gs://${storageBucket}`);
  await assertFails(getBytes(ref(storage,publicDoc.logo.storagePath),5*1024*1024));
});
test('unggahan belum selesai dan berkas palsu tidak meninggalkan produk/audit',async()=>{
  const id=randomUUID(),ctx=context();
  const upload=await service.beginUpload(request(adminUid,'ADMIN',{...ctx,entityType:'product',entityId:id,contentType:'image/png',bytes:16}));
  const data={...context(),id,expectedVersion:0,data:{...draft(),status:'active',images:[{storagePath:upload.storagePath,url:''}],mainImagePath:upload.storagePath}};
  await assert.rejects(service.saveEntity(request(adminUid,'ADMIN',data),'product'));
  const storage=env.authenticatedContext(adminUid,{role:'ADMIN'}).storage(`gs://${storageBucket}`);
  await uploadBytes(ref(storage,upload.storagePath),Buffer.alloc(16),{contentType:'image/png'});
  await assert.rejects(service.saveEntity(request(adminUid,'ADMIN',data),'product'),e=>e.code==='invalid-argument');
  assert.equal((await adminFirestore().doc(`products/${id}`).get()).exists,false);
  assert.equal((await adminFirestore().collection('activityLogs').where('requestId','==',data.requestId).get()).size,0);
});
test('OWNER membuat ADMIN idempoten, menonaktifkan dan mengaktifkan tanpa menghidupkan sesi lama',async()=>{
  const create={...context(),operation:'create',name:'Admin Baru',email:`admin-${randomUUID()}@example.com`};
  const first=await service.manageAdmin(request(ownerUid,'OWNER',create));
  assert.deepEqual(await service.manageAdmin(request(ownerUid,'OWNER',create)),first);
  const user=await adminAuth().getUser(first.uid);
  assert.equal(user.customClaims.role,'ADMIN');assert.equal(user.disabled,false);
  const logs=await adminFirestore().collection('activityLogs').where('requestId','==',create.requestId).get();
  assert.equal(logs.size,1);
  await service.manageAdmin(request(ownerUid,'OWNER',{...context(),operation:'setActive',uid:first.uid,active:false}));
  await assert.rejects(service.getSession(request(first.uid,'ADMIN',{})),e=>e.code==='permission-denied');
  await service.manageAdmin(request(ownerUid,'OWNER',{...context(),operation:'setActive',uid:first.uid,active:true}));
  assert.equal((await adminAuth().getUser(first.uid)).disabled,false);
  await assert.rejects(service.getSession({auth:{uid:first.uid,token:{role:'ADMIN',auth_time:Math.floor(Date.now()/1000)-10}},data:{}}),e=>e.code==='permission-denied');
  await assert.rejects(service.manageAdmin(request(ownerUid,'OWNER',{...context(),operation:'setActive',uid:ownerUid,active:false})),e=>e.code==='permission-denied');
  assert.equal((await adminAuth().getUser(ownerUid)).disabled,false);
});
