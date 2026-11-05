// Removes only records from the explicitly opted-in Android cloud test.
const fs=require('node:fs');
const {initializeApp,deleteApp}=require('firebase-admin/app');
const {getFirestore}=require('firebase-admin/firestore');
const {serverAppOptions}=require('../lib/server-config');
const {DriveApi}=require('../lib/drive/transport');
const {createHash}=require('node:crypto');
const OWNER='S28l85ry6EhyEzg5F4c6oweNyJC3';
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
let app,stage='report';
async function main(){
  const at=process.argv.indexOf('--report');
  if(at<0)throw new Error('report-required');
  const report=JSON.parse(fs.readFileSync(process.argv[at+1],'utf8').replace(/^\uFEFF/,''));
  if(!UUID.test(report.entityId)||!Array.isArray(report.mediaIds)||!report.mediaIds.every(v=>UUID.test(v))||!Array.isArray(report.requests)||!report.requests.every(v=>UUID.test(v)))throw new Error('invalid-report');
  const options=serverAppOptions();if(options.projectId!=='jayagunatehnikperkasa')throw new Error('wrong-project');
  app=initializeApp(options);const db=getFirestore(app),drive=new DriveApi(),productRef=db.doc('products/'+report.entityId);
  stage='catalog-ownership';const product=await productRef.get();
  if(product.exists&&(product.get('name')!=='Uji koneksi native '+report.entityId||product.get('createdBy')!==OWNER))throw new Error('not-test-product');
  const assetsByEntity=await db.collection('mediaAssets').where('entityId','==',report.entityId).get(),assets=new Map(assetsByEntity.docs.map(d=>[d.id,d]));
  for(const id of report.mediaIds){const doc=await db.doc('mediaAssets/'+id).get();if(doc.exists)assets.set(id,doc);}
  const records=[];
  for(const doc of assets.values()){
    const a=doc.data();if(a.createdBy!==OWNER||a.source!=='android'||(a.entityId!==report.entityId&&!(report.mediaIds.includes(doc.id)&&a.originalFileName==='uji-logo')))throw new Error('not-test-media');
    const folder=a.entityType==='product'?'1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd':'1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6';
    if(a.folder!==folder)throw new Error('wrong-folder');
    stage='temporary-file-cleanup';
    try{const file=await drive.metadata(a.driveFileId);if(!file.parents?.includes(folder))throw new Error('wrong-parent');if(!file.trashed){const bytes=await drive.read(a.driveFileId);if(createHash('sha256').update(bytes).digest('hex')!==(a.sha256||a.originalSha256))throw new Error('wrong-bytes');await drive.trash(a.driveFileId);}}
    catch(error){if(error.code!=='not-found')throw error;}
    records.push(doc.ref,db.doc('driveUploadRequests/'+OWNER+'_'+a.requestId));
    const audits=await db.collection('audit_logs').where('mediaId','==',doc.id).get();
    for(const audit of audits.docs){if(audit.get('userId')!==OWNER||audit.get('targetId')!==a.entityId)throw new Error('not-test-audit');records.push(audit.ref);}
  }
  stage='temporary-record-cleanup';const logs=await db.collection('activityLogs').where('entityId','==',report.entityId).get();
  for(const log of logs.docs){if(log.get('actorUid')!==OWNER)throw new Error('not-test-log');records.push(log.ref);}
  for(const request of report.requests){const ref=db.doc('operationReceipts/'+OWNER+'_'+request),doc=await ref.get();if(doc.exists){if(doc.get('entityId')!==report.entityId)throw new Error('not-test-receipt');records.push(ref);}}
  records.push(productRef,db.doc('publicProducts/'+report.entityId));
  const batch=db.batch();for(const ref of records)batch.delete(ref);await batch.commit();
  console.log(JSON.stringify({cleaned:true,temporaryMedia:assets.size,temporaryCatalogRemoved:true,userCatalogUnchanged:true,testCompleted:report.completed===true}));
}
main().catch(()=>{console.error(JSON.stringify({cleaned:false,stage,message:'Pembersihan teknis dihentikan; data di luar ID laporan tidak diubah.'}));process.exitCode=1;}).finally(async()=>{if(app)await deleteApp(app);});
