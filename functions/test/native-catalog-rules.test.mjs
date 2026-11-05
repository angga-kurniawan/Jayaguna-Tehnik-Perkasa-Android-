import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {initializeTestEnvironment,assertFails,assertSucceeds} from '@firebase/rules-unit-testing';
import {doc,getDoc,getDocs,setDoc,updateDoc,deleteDoc,collection,query,limit,writeBatch,serverTimestamp,Timestamp} from 'firebase/firestore';

if(!process.env.FIRESTORE_EMULATOR_HOST) throw new Error('Run only inside Firebase Firestore Emulator.');
const projectId=process.env.GCLOUD_PROJECT || 'demo-native-catalog';
if(!projectId.startsWith('demo-')) throw new Error('Native rules tests require a demo project.');
const PRODUCT_FOLDER='1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd',PARTNER_FOLDER='1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6';
const HASH='a'.repeat(64),NOW=Math.floor(Date.now()/1000),OWNER='owner-test',ADMIN='admin-test',OTHER='other-test';
const DEVICE={installationId:randomUUID(),deviceName:'Native test',brand:'Test',model:'Model',androidVersion:'16',appVersion:'1.0.4'};
const P_FIELDS=['id','name','brand','model','category','capacityPk','condition','price','description','specifications','images','mainImagePath','status','archived','version'];
const M_FIELDS=['id','name','logo','altText','website','displayOrder','active','archived','version'];
let env;
const db=(uid=ADMIN,claims={})=>env.authenticatedContext(uid,{role:uid===OWNER?'OWNER':'ADMIN',email:uid+'@example.com',auth_time:NOW,...claims}).firestore();
const adminWrite=async(path,value)=>env.withSecurityRulesDisabled(ctx=>setDoc(doc(ctx.firestore(),path),value));
const adminRead=async path=>{let result;await env.withSecurityRulesDisabled(async ctx=>{result=await getDoc(doc(ctx.firestore(),path));});return result;};
const draft=()=>({name:'Daikin 1 PK',brand:'Daikin',model:'FTK',category:'split',capacityPk:'1',condition:'new',price:4500000,description:'AC hemat energi',specifications:{Daya:'350 W'},images:[],mainImagePath:'',status:'draft'});
function snapshot(value,type='product'){return Object.fromEntries((type==='product'?P_FIELDS:M_FIELDS).map(k=>[k,value[k]]));}
function projection(value,type='product'){
 if(value.archived || (type==='product'?value.status!=='active':!value.active))return null;
 const keys=type==='product'?P_FIELDS.filter(k=>!['archived','version'].includes(k)):M_FIELDS.filter(k=>!['archived','version'].includes(k));
 return {...Object.fromEntries(keys.map(k=>[k,value[k]])),archived:false,createdAt:value.createdAt,updatedAt:value.updatedAt,mediaPaths:type==='product'?value.images.map(i=>i.storagePath):[value.logo.storagePath]};
}
function activity(next,old,uid,requestId,type='product'){
 const before=old?snapshot(old,type):{},after=snapshot(next,type),changedFields={};
 for(const key of new Set([...Object.keys(before),...Object.keys(after)]))if(JSON.stringify(before[key])!==JSON.stringify(after[key]))changedFields[key]={before:before[key]??null,after:after[key]??null};
 const statusKey=type==='product'?'status':'active';
 return {actorUid:uid,actorName:uid,actorEmail:uid+'@example.com',actorRole:uid===OWNER?'OWNER':'ADMIN',action:!old?'add':next.archived?'archive':old[statusKey]!==next[statusKey]?'status':'edit',
  entityType:type,entityId:next.id,before,after,changedFields,timestamp:serverTimestamp(),requestId,device:DEVICE,...DEVICE};
}
function entityBatch({client=db(),uid=ADMIN,id=randomUUID(),type='product',old=null,data=draft(),requestId=randomUUID(),omitLog=false,omitReceipt=false,omitProjection=false,mutateLog,mutateReceipt,mutateProjection,assets=[],extra={}}={}){
 const coll=type==='product'?'products':'partners',pub=type==='product'?'publicProducts':'publicPartners';
 const next={...data,id,archived:false,version:(old?.version||0)+1,createdBy:old?.createdBy||uid,createdAt:old?.createdAt||serverTimestamp(),updatedBy:uid,updatedAt:serverTimestamp(),lastRequestId:requestId,...(type==='product'?{searchTokens:['d','da']}:{}),...extra};
 const batch=writeBatch(client);
 // Attachment markers follow the core commit in bounded transactions. The
 // atomic entity write still checks every registry image and its Firebase actor.
 batch.set(doc(client,coll,id),next);
 const log=activity(next,old,uid,requestId,type);if(mutateLog)mutateLog(log);
 if(!omitLog)batch.set(doc(client,'activityLogs',uid+'_'+requestId),log);
 const receipt={hash:HASH,result:{id,version:next.version},entityType:type,entityId:id,operation:(next.archived?'archive':'save')+(type==='product'?'Product':'Partner'),createdAt:serverTimestamp()};
 if(mutateReceipt)mutateReceipt(receipt);
 if(!omitReceipt)batch.set(doc(client,'operationReceipts',uid+'_'+requestId),receipt);
 const publicData=projection(next,type);if(publicData&&mutateProjection)mutateProjection(publicData);
 if(!omitProjection){if(publicData)batch.set(doc(client,pub,id),publicData);else batch.delete(doc(client,pub,id));}
 return {commit:()=>batch.commit(),next,id,requestId,log,receipt,client};
}
function reserved(entityId=randomUUID(),type='product',uid=ADMIN){
 const id=randomUUID(),requestId=randomUUID();
 return {id,driveFileId:'drive_file_'+id.replaceAll('-',''),folder:type==='product'?PRODUCT_FOLDER:PARTNER_FOLDER,entityType:type,entityId,createdBy:uid,device:DEVICE,requestId,status:'reserved',expectedBytes:68,expectedMime:'image/png',originalSha256:HASH,originalFileName:'ac.png',createdAt:serverTimestamp(),expiresAt:Timestamp.fromMillis(Date.now()+86400000),source:'android'};
}
function reservationBatch(a,{client=db(),mutateSlot}={}){
 const batch=writeBatch(client),slot={hash:HASH,assetId:a.id,createdAt:serverTimestamp(),createdBy:a.createdBy,source:'android'};
 if(mutateSlot)mutateSlot(slot);
 batch.set(doc(client,'mediaAssets',a.id),a);
 batch.set(doc(client,'driveUploadRequests',a.createdBy+'_'+a.requestId),slot);
 return batch.commit();
}
function media(a){return {storagePath:'drive/'+a.id,url:'',provider:'drive',driveFileId:a.driveFileId,fileName:a.fileName,mimeType:a.mimeType,sizeBytes:a.sizeBytes};}
function mediaAudit(a,action,{uid=ADMIN,requestId=a.requestId,device=DEVICE}={}){
 return {userId:uid,userEmail:uid+'@example.com',actorName:uid,actorRole:uid===OWNER?'OWNER':'ADMIN',action,targetId:a.entityId,entityType:a.entityType,driveFileId:a.driveFileId,mediaId:a.id,deviceId:device.installationId,device,status:'SUCCESS',timestamp:serverTimestamp(),requestId};
}
async function seedUploaded(entityId=randomUUID(),type='product',uid=ADMIN){
 const a={...reserved(entityId,type,uid),createdAt:Timestamp.now(),status:'uploaded',fileName:'ac.png',mimeType:'image/png',sizeBytes:68,sha256:HASH,uploadedAt:Timestamp.now()};
 await adminWrite('mediaAssets/'+a.id,a);return a;
}
async function readEntity(id,type='product'){return (await adminRead((type==='product'?'products/':'partners/')+id)).data();}
async function attachAssets(assets,requestId,client=db()) { for(const a of assets)await updateDoc(doc(client,'mediaAssets',a.id),{status:'attached',attachedAt:serverTimestamp(),lastRequestId:requestId}); }
before(async()=>{env=await initializeTestEnvironment({projectId,firestore:{rules:await readFile(new URL('../../firestore.rules',import.meta.url),'utf8')}});});
beforeEach(async()=>{await env.clearFirestore();for(const [uid,role]of [[OWNER,'OWNER'],[ADMIN,'ADMIN'],[OTHER,'ADMIN']])await adminWrite('users/'+uid,{uid,name:uid,email:uid+'@example.com',role,active:true,version:1,revokedBeforeSeconds:0});});
after(async()=>{await env?.cleanup();});

test('native draft saves atomically with server time, immutable actor and receipt',async()=>{
 const value=entityBatch();await assertSucceeds(value.commit());
 const saved=await readEntity(value.id);assert.equal(saved.createdBy,ADMIN);assert.equal(saved.updatedBy,ADMIN);assert.equal(saved.version,1);assert.ok(saved.updatedAt instanceof Timestamp);
 const log=(await adminRead('activityLogs/'+ADMIN+'_'+value.requestId)).data();assert.equal(log.actorUid,ADMIN);assert.deepEqual(log.after,snapshot(saved));assert.equal(log.timestamp.toMillis(),saved.updatedAt.toMillis());
 await assertFails(updateDoc(doc(value.client,'activityLogs',ADMIN+'_'+value.requestId),{actorUid:OWNER}));
 await assertFails(deleteDoc(doc(value.client,'operationReceipts',ADMIN+'_'+value.requestId)));
});

test('native writes deny no role, inactive profile, mismatched claims and revoked auth time',async()=>{
 const clients=[env.unauthenticatedContext().firestore(),db(ADMIN,{role:'OWNER'}),db('missing-user'),db(ADMIN,{auth_time:0})];
 for(const client of clients)await assertFails(entityBatch({client}).commit());
 await adminWrite('users/'+OTHER,{name:OTHER,email:OTHER+'@example.com',role:'ADMIN',active:false,revokedBeforeSeconds:0});
 await assertFails(entityBatch({client:db(OTHER),uid:OTHER}).commit());
 await adminWrite('users/'+OTHER,{name:OTHER,email:OTHER+'@example.com',role:'ADMIN',active:true,revokedBeforeSeconds:NOW});
 await assertFails(entityBatch({client:db(OTHER),uid:OTHER}).commit());
 await assertSucceeds(entityBatch({client:db(OTHER,{auth_time:NOW+1}),uid:OTHER}).commit());
});

test('missing atomic audit or receipt and forged actor or timestamp are rejected',async()=>{
 for(const options of [{omitLog:true},{omitReceipt:true},{mutateLog:d=>{d.actorUid=OWNER;}},{mutateLog:d=>{d.before={name:'fake'};}},{mutateLog:d=>{d.after.name='fake';}},{mutateLog:d=>{d.deviceName='forged device';}},{mutateLog:d=>{d.timestamp=Timestamp.fromMillis(1);}},{mutateReceipt:d=>{d.result.version=9;}},{extra:{updatedBy:OWNER}},{extra:{createdAt:Timestamp.fromMillis(1)}}])await assertFails(entityBatch(options).commit());
 await assertFails(setDoc(doc(db(),'users',ADMIN),{role:'OWNER',active:true}));
 await assertFails(setDoc(doc(db(),'activityLogs',ADMIN+'_'+randomUUID()),{actorUid:ADMIN}));
});

test('strict product schema rejects injected role, invalid price and nonstring specifications',async()=>{
 for(const data of [{...draft(),role:'OWNER'},{...draft(),price:-1},{...draft(),price:Infinity},{...draft(),category:'foreign'},{...draft(),specifications:{Daya:{nested:'object'}}},{...draft(),specifications:{['x'.repeat(81)]:'bad'}}])await assertFails(entityBatch({data}).commit());
 for(const value of [99,true,['text'],null,{nested:'text'},'x'.repeat(1001),'x\u001fy'])await assertFails(entityBatch({data:{...draft(),specifications:{Daya:value}}}).commit());
});

test('optimistic versions, creator preservation and receipts prevent stale replay',async()=>{
 const first=entityBatch();await assertSucceeds(first.commit());const old=await readEntity(first.id);
 await assertFails(entityBatch({id:first.id,data:draft(),extra:{version:1}}).commit());
 await assertFails(entityBatch({id:first.id,old,data:draft(),extra:{createdBy:OWNER}}).commit());
 const second=entityBatch({id:first.id,old,data:{...draft(),name:'Updated AC'}});await assertSucceeds(second.commit());
 await assertFails(entityBatch({id:first.id,old,data:draft()}).commit());
 await assertFails(entityBatch({id:randomUUID(),requestId:first.requestId}).commit());
});

test('reservation is atomic, owner-bound, size-bounded and restricted to the company folders',async()=>{
 const a=reserved();await assertSucceeds(reservationBatch(a));
 await assertFails(setDoc(doc(db(),'mediaAssets',a.id),a));
 for(const patch of [{folder:'attacker_folder_12345'},{createdBy:OTHER},{expectedBytes:5242881},{expectedMime:'image/svg+xml'},{originalSha256:'bad'},{source:'server'},{expiresAt:Timestamp.fromMillis(Date.now()+172800000)}])await assertFails(reservationBatch({...reserved(),...patch}));
 await assertFails(setDoc(doc(db(),'mediaAssets',reserved().id),reserved()));
 await assertFails(reservationBatch(reserved(),{mutateSlot:d=>{d.assetId=randomUUID();}}));
});

test('upload completion requires its matching immutable audit and original checksum',async()=>{
 const a=reserved();await assertSucceeds(reservationBatch(a));const client=db();
 const patch={status:'uploaded',fileName:'ac.png',mimeType:'image/png',sizeBytes:68,sha256:HASH,uploadedAt:serverTimestamp()};
 await assertFails(updateDoc(doc(client,'mediaAssets',a.id),patch));
 for(const changes of [{sha256:'b'.repeat(64)},{driveFileId:'foreign_file_12345'},{sizeBytes:70}]){
  const batch=writeBatch(client);batch.update(doc(client,'mediaAssets',a.id),{...patch,...changes});batch.set(doc(client,'audit_logs','upload_'+a.id),mediaAudit(a,'UPLOAD_PRODUCT_IMAGE'));await assertFails(batch.commit());
 }
 const batch=writeBatch(client);batch.update(doc(client,'mediaAssets',a.id),patch);batch.set(doc(client,'audit_logs','upload_'+a.id),mediaAudit(a,'UPLOAD_PRODUCT_IMAGE'));await assertSucceeds(batch.commit());
 await assertFails(setDoc(doc(client,'audit_logs','upload_'+a.id),mediaAudit(a,'UPLOAD_PRODUCT_IMAGE')));
});

test('active product requires matching uploaded registry and exact public projection',async()=>{
 const id=randomUUID(),a=await seedUploaded(id),data={...draft(),images:[media(a)],mainImagePath:'drive/'+a.id,status:'active'};
 await assertFails(entityBatch({id,data:{...data,images:[{...media(a),fileName:'forged.png'}]}}).commit());
 await assertFails(entityBatch({id,data,assets:[a],omitProjection:true}).commit());
 await assertFails(entityBatch({id,data,assets:[a],mutateProjection:p=>{p.createdBy=ADMIN;}}).commit());
 const write=entityBatch({id,data,assets:[a]});await assertSucceeds(write.commit());
 await assertSucceeds(attachAssets([a],write.requestId));
 const publicDoc=(await adminRead('publicProducts/'+id)).data();assert.equal(publicDoc.createdBy,undefined);assert.equal(publicDoc.images[0].driveFileId,a.driveFileId);
 await assertSucceeds(getDoc(doc(env.unauthenticatedContext().firestore(),'publicProducts',id)));
});

test('foreign uploads and mismatched entity references cannot be adopted',async()=>{
 const id=randomUUID(),foreign=await seedUploaded(id,'product',OTHER),wrongEntity=await seedUploaded(randomUUID());
 for(const a of [foreign,wrongEntity])await assertFails(entityBatch({id,data:{...draft(),images:[media(a)],mainImagePath:'drive/'+a.id,status:'active'},assets:[a]}).commit());
 const own=await seedUploaded(id);await assertFails(entityBatch({id,data:{...draft(),images:[{...media(own),driveFileId:'foreign_file_12345'}],mainImagePath:'drive/'+own.id,status:'active'},assets:[own]}).commit());
});

test('eight image product and eight image replacement stay within Firestore access limits',async()=>{
 const id=randomUUID(),images=[];for(let i=0;i<8;i++)images.push(await seedUploaded(id));
 const first=entityBatch({id,data:{...draft(),images:images.map(media),mainImagePath:'drive/'+images[0].id,status:'active'},assets:images});await assertSucceeds(first.commit());
 await assertSucceeds(attachAssets(images,first.requestId));
 const old=await readEntity(id),next=[];for(let i=0;i<8;i++)next.push(await seedUploaded(id));
 const replacement=entityBatch({id,old,data:{...draft(),images:next.map(media),mainImagePath:'drive/'+next[0].id,status:'active'},assets:next});await assertSucceeds(replacement.commit());await assertSucceeds(attachAssets(next,replacement.requestId));
});

test('eight image product also supports the full fifty specification entries',async()=>{
 const id=randomUUID(),images=[];for(let i=0;i<8;i++)images.push(await seedUploaded(id));
 const specifications=Object.fromEntries(Array.from({length:50},(_,i)=>[String(i).padStart(2,'0')+'x'.repeat(78),'v'.repeat(1000)]));
 await assertSucceeds(entityBatch({id,data:{...draft(),specifications,images:images.map(media),mainImagePath:'drive/'+images[0].id,status:'active'},assets:images}).commit());
});

test('the final image and specification entry cannot bypass split atomic validation',async()=>{
 const id=randomUUID(),images=[];for(let i=0;i<7;i++)images.push(await seedUploaded(id));
 const foreign=await seedUploaded(randomUUID()),data={...draft(),images:[...images,foreign].map(media),mainImagePath:'drive/'+images[0].id,status:'active'};
 await assertFails(entityBatch({id,data}).commit());
 const own=await seedUploaded(id),last={...media(own),driveFileId:'forged_drive_id_12345'};
 await assertFails(entityBatch({id,data:{...data,images:[...images.map(media),last]}}).commit());
 const specifications=Object.fromEntries(Array.from({length:50},(_,i)=>['Spesifikasi '+i,'Valid']));specifications['Spesifikasi 49']={forged:'value'};
 await assertFails(entityBatch({id,data:{...draft(),specifications,images:[...images,own].map(media),mainImagePath:'drive/'+images[0].id,status:'active'}}).commit());
});

test('an existing audit or receipt cannot be replayed to authorize another entity mutation',async()=>{
 const first=entityBatch();await assertSucceeds(first.commit());const old=await readEntity(first.id),client=db();
 const next={...old,name:'Unaudited change',version:2,updatedBy:ADMIN,updatedAt:serverTimestamp()};
 await assertFails(setDoc(doc(client,'products',first.id),next));
 await assertFails(entityBatch({id:first.id,old,data:{...draft(),name:'Reused request'},requestId:first.requestId}).commit());
});

test('partner logo publication and archive have atomic projections and unforgeable snapshots',async()=>{
 const id=randomUUID(),a=await seedUploaded(id,'partner'),data={name:'Mitra AC',logo:media(a),altText:'Logo mitra',website:'https://example.com',displayOrder:1,active:true};
 const first=entityBatch({id,type:'partner',data,assets:[a]});await assertSucceeds(first.commit());const old=await readEntity(id,'partner');
 await assertFails(entityBatch({id,type:'partner',old,data:{...data,website:'javascript:alert(1)'}}).commit());
 await assertSucceeds(entityBatch({id,type:'partner',old,data,extra:{archived:true}}).commit());
 assert.equal((await adminRead('publicPartners/'+id)).exists(),false);
});

test('referenced images cannot be trashed, including archived entity references',async()=>{
 const id=randomUUID(),a=await seedUploaded(id),first=entityBatch({id,data:{...draft(),images:[media(a)],mainImagePath:'drive/'+a.id},assets:[a]});await assertSucceeds(first.commit());
 await assertSucceeds(attachAssets([a],first.requestId));
 const old=await readEntity(id);await assertSucceeds(entityBatch({id,old,data:{...draft(),images:[media(a)],mainImagePath:'drive/'+a.id},extra:{archived:true}}).commit());
 await assertFails(updateDoc(doc(db(),'mediaAssets',a.id),{status:'deleting',deleteRequestId:randomUUID(),deleteDevice:DEVICE,deleteActorUid:ADMIN,deleteStartedAt:serverTimestamp()}));
});

test('post-save detach and physical deletion require immutable linked audits',async()=>{
 const id=randomUUID(),a=await seedUploaded(id),first=entityBatch({id,data:{...draft(),images:[media(a)],mainImagePath:'drive/'+a.id},assets:[a]});await assertSucceeds(first.commit());
 await assertSucceeds(attachAssets([a],first.requestId));
 const old=await readEntity(id),second=entityBatch({id,old,data:draft()});await assertSucceeds(second.commit());const client=db();
 const detach={status:'detached',detachedAt:serverTimestamp(),deleteAfter:Timestamp.fromMillis(Date.now()+86400000),lastRequestId:second.requestId};
 await assertFails(updateDoc(doc(client,'mediaAssets',a.id),detach));
 let batch=writeBatch(client);batch.update(doc(client,'mediaAssets',a.id),detach);batch.set(doc(client,'audit_logs',ADMIN+'_'+second.requestId+'_remove_'+a.id),mediaAudit(a,'REMOVE_PRODUCT_IMAGE',{requestId:second.requestId}));await assertSucceeds(batch.commit());
 const deleteRequest=randomUUID();await assertSucceeds(updateDoc(doc(client,'mediaAssets',a.id),{status:'deleting',deleteRequestId:deleteRequest,deleteDevice:DEVICE,deleteActorUid:ADMIN,deleteStartedAt:serverTimestamp()}));
 await assertFails(updateDoc(doc(client,'mediaAssets',a.id),{status:'deleted',deletedAt:serverTimestamp()}));
 batch=writeBatch(client);batch.update(doc(client,'mediaAssets',a.id),{status:'deleted',deletedAt:serverTimestamp()});batch.set(doc(client,'audit_logs','delete_'+a.id),mediaAudit(a,'DELETE_PRODUCT_IMAGE',{requestId:deleteRequest}));await assertSucceeds(batch.commit());
 await assertFails(updateDoc(doc(client,'mediaAssets',a.id),{status:'uploaded'}));
});

test('unrelated admins cannot delete an unattached upload and owner can clean it',async()=>{
 const a=await seedUploaded(randomUUID(),'product',OTHER),requestId=randomUUID(),patch={status:'deleting',deleteRequestId:requestId,deleteDevice:DEVICE,deleteActorUid:ADMIN,deleteStartedAt:serverTimestamp()};
 await assertFails(updateDoc(doc(db(),'mediaAssets',a.id),patch));
 await assertSucceeds(updateDoc(doc(db(OWNER),'mediaAssets',a.id),{...patch,deleteActorUid:OWNER}));
});

test('media registry is readable only by active staff while OAuth secrets remain denied',async()=>{
 const a=await seedUploaded();await assertSucceeds(getDoc(doc(db(),'mediaAssets',a.id)));
 await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),'mediaAssets',a.id)));
 await assertFails(getDoc(doc(db(),'backendSecrets','googleDrive')));
 await assertFails(getDocs(query(collection(db(),'users'),limit(10))));
 await assertFails(setDoc(doc(db(),'audit_logs',randomUUID()),mediaAudit(a,'UPLOAD_PRODUCT_IMAGE')));
});
