import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {Timestamp} from 'firebase-admin/firestore';
import {HttpsError} from 'firebase-functions/v2/https';
import sharp from 'sharp';
import module from '../lib/central-drive.js';
import config from '../lib/drive/config.js';
import transport from '../lib/drive/transport.js';
import {MemoryFirestore} from './helpers/memory-firestore.mjs';
const {CentralDrive}=module,{COMPANY_DRIVE_FOLDERS,COMPANY_DRIVE_EMAIL}=config;
const id='11111111-1111-4111-8111-111111111111',entityId='22222222-2222-4222-8222-222222222222';
const actor={uid:'approved-fixture',email:'fixture@example.test',name:'Fixture',role:'ADMIN'};
const bytes=await sharp({create:{width:2,height:2,channels:4,background:{r:1,g:2,b:3,alpha:0.5}}}).png().toBuffer();
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const asset={id,driveFileId:'fixture-issued-drive-id',entityType:'product',entityId,folder:COMPANY_DRIVE_FOLDERS.product,
  createdBy:actor.uid,source:'android',status:'reserved',expectedBytes:bytes.length,expectedMime:'image/png',
  originalSha256:hash(bytes),originalFileName:'gambar1.png',expiresAt:Timestamp.fromMillis(100000)};
const uploaded={...asset,status:'uploaded',mimeType:'image/png',sizeBytes:bytes.length,sha256:hash(bytes),fileName:'gambar1.png'};
const file={id:asset.driveFileId,name:'gambar1.png',mimeType:'image/png',size:String(bytes.length),parents:[asset.folder],permissions:[],
  md5Checksum:createHash('md5').update(bytes).digest('hex'),capabilities:{canTrash:true},
  appProperties:{application:'jayaguna-admin',assetId:id,entityId,entityType:'product',sha256:hash(bytes)}};
const folder=id=>({id,name:'Company folder',mimeType:'application/vnd.google-apps.folder',owners:[{emailAddress:COMPANY_DRIVE_EMAIL}],permissions:[],capabilities:{canAddChildren:true}});
function fixture(data=asset,overrides={},extra={}) {
  const db=new MemoryFirestore({['mediaAssets/'+id]:data,['driveReservations/'+asset.driveFileId]:{uid:actor.uid,expiresAt:Timestamp.fromMillis(100000)},...extra});
  const calls=[];
  let trashed=false;
  const drive={privateMetadata:async driveId=>{calls.push(['metadata',driveId]);return Object.values(COMPANY_DRIVE_FOLDERS).includes(driveId)?folder(driveId):{...file,trashed};},
    reserveId:async()=>{calls.push(['reserve']);return 'fresh-issued-drive-id';},
    upload:async(...args)=>{calls.push(['upload',...args]);return file;},read:async driveId=>{calls.push(['read',driveId]);return bytes;},
    trash:async driveId=>{calls.push(['trash',driveId]);trashed=true;},...overrides};
  const central=new CentralDrive(db,drive,async()=>({connected:true,accountEmail:COMPANY_DRIVE_EMAIL}),()=>{},()=>5000);
  return {central,db,calls};
}

test('connection checks all four exact folders, owner identity and private write capability',async()=>{
  const {central,calls}=fixture();
  assert.deepEqual(await central.status(),{connected:true,accountEmail:COMPANY_DRIVE_EMAIL,foldersAccessible:true});
  assert.deepEqual(new Set(calls.map(call=>call[1])),new Set(Object.values(COMPANY_DRIVE_FOLDERS)));
  for(const bad of [{owners:[{emailAddress:'someone@example.test'}]},{permissions:[{type:'anyone'}]},{permissions:[{type:'domain'}]},{capabilities:{canAddChildren:false}}]) {
    const denied=fixture(asset,{privateMetadata:async id=>({...folder(id),...bad})});
    await assert.rejects(denied.central.status(),error=>error.code==='failed-precondition');
  }
});

test('reserve persists issued IDs for the caller; upload preserves exact bytes and existing media schema',async()=>{
  const {central,db,calls}=fixture();
  assert.deepEqual(await central.reserve(actor),{id:'fresh-issued-drive-id'});
  assert.equal(db.data.get('driveReservations/fresh-issued-drive-id').uid,actor.uid);
  const media=await central.upload(actor,id,'image/png',bytes);
  assert.deepEqual(media,{storagePath:'drive/'+id,url:'',provider:'drive',driveFileId:asset.driveFileId,fileName:'gambar1.png',mimeType:'image/png',sizeBytes:bytes.length});
  assert.ok(calls.find(call=>call[0]==='upload')[8].equals(bytes));
  assert.equal(db.data.get('mediaAssets/'+id).status,'reserved'); // Android owns the existing audited metadata transaction.
  const extensionless=fixture({...asset,originalFileName:'gambar1'});
  await extensionless.central.upload(actor,id,'image/png',bytes);
  assert.equal(extensionless.calls.find(call=>call[0]==='upload')[6],'gambar1.png');
});

test('unissued IDs, another uploader, changed bytes and wrong folders cannot upload',async()=>{
  const cases=[
    fixture(asset,{}, {['driveReservations/'+asset.driveFileId]:{uid:'another-user'}}),
    fixture({...asset,createdBy:'another-user'}),
    fixture({...asset,originalSha256:'0'.repeat(64)}),
    fixture({...asset,folder:COMPANY_DRIVE_FOLDERS.partner}),
    fixture(asset,{privateMetadata:async id=>({...folder(id),permissions:[{type:'anyone'}]})}),
  ];
  for(const {central,calls} of cases) {
    await assert.rejects(central.upload(actor,id,'image/png',bytes));
    assert.equal(calls.filter(call=>call[0]==='upload').length,0);
  }
  const invalidBytes=bytes.subarray(0,32),bad=fixture({...asset,expectedBytes:invalidBytes.length,originalSha256:hash(invalidBytes)});
  await assert.rejects(bad.central.upload(actor,id,'image/png',invalidBytes),error=>error.code==='invalid-argument');
});

test('legacy private appProperties can be absent only with trusted registry, parent and exact byte hash',async()=>{
  const legacy=fixture(uploaded,{privateMetadata:async()=>({...file,appProperties:{}})});
  assert.ok((await legacy.central.read(actor,id)).bytes.equals(bytes));
  for(const overrides of [{read:async()=>Buffer.from('wrong-image')},
    {privateMetadata:async()=>({...file,parents:[COMPANY_DRIVE_FOLDERS.partner]})},
    {privateMetadata:async()=>({...file,permissions:[{type:'anyone'}]})},
    {privateMetadata:async()=>({...file,appProperties:{...file.appProperties,entityId:id}})}]) {
    await assert.rejects(fixture(uploaded,overrides).central.read(actor,id),error=>error.code==='failed-precondition');
  }
  await assert.rejects(fixture(asset).central.read(actor,id),error=>error.code==='not-found');
});

test('trash requires reserved deletion ownership, no catalogue references and verified bytes',async()=>{
  const deleting={...uploaded,status:'deleting',deleteActorUid:actor.uid};
  const ready=fixture(deleting);
  assert.deepEqual(await ready.central.trash(actor,id),{success:true});
  assert.equal(ready.calls.filter(call=>call[0]==='trash').length,1);
  for(const data of [uploaded,{...deleting,deleteActorUid:'someone-else'},{...deleting,createdBy:'someone-else'}]) {
    const denied=fixture(data);
    await assert.rejects(denied.central.trash(actor,id),error=>error.code==='permission-denied');
    assert.equal(denied.calls.length,0);
  }
  const referenced=fixture(deleting,{}, {['products/'+entityId]:{images:[{storagePath:'drive/'+id}]}});
  await assert.rejects(referenced.central.trash(actor,id),error=>error.code==='failed-precondition');
  assert.equal(referenced.calls.length,0);
});

test('ambiguous Drive 404 and unconfirmed trash retain retryable deletion metadata',async()=>{
  const deleting={...uploaded,status:'deleting',deleteActorUid:actor.uid};
  const missing=fixture(deleting,{privateMetadata:async()=>{throw new HttpsError('not-found','Drive hides inaccessible files.');}});
  await assert.rejects(missing.central.trash(actor,id),error=>error.code==='failed-precondition');
  assert.equal(missing.calls.filter(call=>call[0]==='trash').length,0);
  assert.equal(missing.db.data.get('mediaAssets/'+id).status,'deleting');

  let reads=0;
  const lostAfterPatch=fixture(deleting,{privateMetadata:async()=>{
    if (++reads===1) return file;
    throw new HttpsError('not-found','Access disappeared after PATCH.');
  }});
  await assert.rejects(lostAfterPatch.central.trash(actor,id),error=>error.code==='failed-precondition');
  assert.equal(lostAfterPatch.calls.filter(call=>call[0]==='trash').length,1);
  assert.equal(lostAfterPatch.db.data.get('mediaAssets/'+id).status,'deleting');

  const unconfirmed=fixture(deleting,{trash:async()=>{}});
  await assert.rejects(unconfirmed.central.trash(actor,id),error=>error.code==='failed-precondition');
  const alreadyTrashed=fixture(deleting,{privateMetadata:async()=>({...file,trashed:true})});
  assert.deepEqual(await alreadyTrashed.central.trash(actor,id),{success:true});
  assert.equal(alreadyTrashed.calls.filter(call=>call[0]==='trash').length,0);
});

test('Drive permission inspection follows pagination instead of trusting embedded partial ACLs',async()=>{
  const urls=[];
  const drive=new transport.DriveApi(async()=>({getAccessToken:async()=>({token:'fixture-access'})}),async url=>{
    urls.push(url);
    if(!url.includes('/permissions?')) return Response.json(file);
    return url.includes('pageToken=next-page')?Response.json({permissions:[{type:'anyone'}]}):Response.json({permissions:[{type:'user'}],nextPageToken:'next-page'});
  });
  const metadata=await drive.privateMetadata(asset.driveFileId);
  assert.deepEqual(metadata.permissions,[{type:'user'},{type:'anyone'}]);
  assert.equal(urls.length,3);
});
