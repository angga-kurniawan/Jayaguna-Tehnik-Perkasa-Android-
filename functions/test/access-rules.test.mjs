import {before, after, beforeEach, test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {initializeTestEnvironment, assertFails, assertSucceeds} from '@firebase/rules-unit-testing';
import {doc, getDoc, getDocs, setDoc, updateDoc, deleteDoc, collection, query, limit, writeBatch, serverTimestamp, Timestamp} from 'firebase/firestore';
import {ref, uploadBytes, getBytes} from 'firebase/storage';

if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_STORAGE_EMULATOR_HOST) {
  throw new Error('Access rules tests require isolated Firestore and Storage emulators.');
}
const projectId = process.env.GCLOUD_PROJECT || 'demo-access-rules';
if (!projectId.startsWith('demo-')) throw new Error('Access rules tests refuse production projects.');
const BUCKET = `gs://${projectId}.appspot.com`, NOW = Math.floor(Date.now() / 1000);
const UID = 'google-admin', EMAIL = 'google-admin@example.com', NAME = 'Google Admin';
const DEVICE = {installationId: randomUUID(), deviceName: 'Access rule test', brand: 'Test', model: 'Test', androidVersion: '16', appVersion: 'access-test'};
const FIELDS = ['id','name','brand','model','category','capacityPk','condition','price','description','specifications','images','mainImagePath','status','archived','version'];
let env;
const claims = (uid = UID, extra = {}) => ({email: uid === UID ? EMAIL : uid + '@example.com', email_verified: true,
  name: NAME, auth_time: NOW, firebase: {sign_in_provider: 'google.com'}, ...extra});
const google = (uid = UID, extra = {}) => env.authenticatedContext(uid, claims(uid, extra));
const db = (uid = UID, extra = {}) => google(uid, extra).firestore();
const seed = (path, data) => env.withSecurityRulesDisabled(ctx => setDoc(doc(ctx.firestore(), path), data));
const read = async path => {
  let value;
  await env.withSecurityRulesDisabled(async ctx => {value = (await getDoc(doc(ctx.firestore(), path))).data();});
  return value;
};
const pending = (uid = UID, email = EMAIL) => ({uid, email, name: NAME, status: 'pending', requestedAt: serverTimestamp()});
const catalogQuery = client => getDocs(query(collection(client, 'products'), limit(20)));
const adminQuery = client => getDocs(query(collection(client, 'users'), limit(20)));

function productBatch({client = db(), uid = UID, email = EMAIL, actorName = NAME, role = 'ADMIN', imageCount = 0,
  id = randomUUID(), requestId = randomUUID(), old = null, omitLog = false, omitReceipt = false} = {}) {
  const imageIds = Array.from({length: imageCount}, () => randomUUID());
  const images = imageIds.map(assetId => ({storagePath: 'drive/' + assetId, url: '', provider: 'drive',
    driveFileId: 'drive_file_' + assetId.replaceAll('-', ''), fileName: 'fixture.png', mimeType: 'image/png', sizeBytes: 68}));
  const next = {id, name: 'Approved Google product', brand: 'Test', model: 'One', category: 'split', capacityPk: '1',
    condition: 'new', price: null, description: '', specifications: {}, images, mainImagePath: images[0]?.storagePath || '',
    status: images.length ? 'active' : 'draft', archived: false, version: (old?.version || 0) + 1,
    createdBy: old?.createdBy || uid, createdAt: old?.createdAt || serverTimestamp(),
    updatedBy: uid, updatedAt: serverTimestamp(), lastRequestId: requestId, searchTokens: ['a']};
  const after = Object.fromEntries(FIELDS.map(key => [key, next[key]]));
  const batch = writeBatch(client);
  batch.set(doc(client, 'products', id), next);
  if (!omitLog) batch.set(doc(client, 'activityLogs', uid + '_' + requestId), {actorUid: uid, actorName, actorEmail: email, actorRole: role,
    action: old ? 'edit' : 'add', entityType: 'product', entityId: id,
    before: old ? Object.fromEntries(FIELDS.map(key => [key, old[key]])) : {}, after, changedFields: {}, timestamp: serverTimestamp(), requestId,
    device: DEVICE, ...DEVICE});
  if (!omitReceipt) batch.set(doc(client, 'operationReceipts', uid + '_' + requestId), {hash: 'a'.repeat(64), result: {id, version: next.version},
    entityType: 'product', entityId: id, operation: 'saveProduct', createdAt: serverTimestamp()});
  if (images.length) {
    const projection = Object.fromEntries(FIELDS.filter(key => key !== 'version').map(key => [key, next[key]]));
    batch.set(doc(client, 'publicProducts', id), {...projection, createdAt: next.createdAt, updatedAt: next.updatedAt,
      mediaPaths: images.map(image => image.storagePath)});
  } else batch.delete(doc(client, 'publicProducts', id));
  return {id, requestId, commit: () => batch.commit(), seedImages: async () => {
    for (let i = 0; i < imageIds.length; i++) await seed('mediaAssets/' + imageIds[i], {id: imageIds[i],
      entityType: 'product', entityId: id, status: 'uploaded', createdBy: uid, ...images[i]});
  }};
}

const REMOVAL_OWNER = 'removal-owner', REMOVAL_TARGET = 'removal-target';
const removalOwnerProfile = {status:'approved', role:'OWNER', name:'Removal Owner', email:REMOVAL_OWNER+'@example.com'};
const removalTargetProfile = {uid:REMOVAL_TARGET, status:'approved', role:'ADMIN', name:'Former Admin',
  email:REMOVAL_TARGET+'@example.com', active:true, version:4, createdBy:'original-operator'};
function adminSnapshot(profile, uid) {
  return {uid, name:profile.name ?? '', email:profile.email ?? '', role:profile.role ?? 'ADMIN',
    status:profile.status ?? (profile.active === true ? 'approved' : 'inactive'),
    active:(profile.active ?? (profile.status === 'approved')) === true};
}
function removalBatch({client=db(REMOVAL_OWNER), ownerUid=REMOVAL_OWNER, ownerName='Removal Owner',
  ownerEmail=REMOVAL_OWNER+'@example.com', targetUid=REMOVAL_TARGET, old=removalTargetProfile,
  requestId=randomUUID(), patch={}, logPatch={}, omitLog=false, omitTarget=false} = {}) {
  const changes={status:'revoked',active:false,removedBy:ownerUid,removedAt:serverTimestamp(),
    updatedAt:serverTimestamp(),removalRequestId:requestId,...patch};
  const before=adminSnapshot(old,targetUid), after=adminSnapshot({...old,...changes},targetUid);
  const value={actorUid:ownerUid,actorName:ownerName,actorEmail:ownerEmail,actorRole:'OWNER',action:'removeAdmin',
    entityType:'user',entityId:targetUid,before,after,
    changedFields:{status:{before:before.status,after:'revoked'},active:{before:before.active,after:false}},
    timestamp:serverTimestamp(),requestId,device:DEVICE,...DEVICE,...logPatch};
  const batch=writeBatch(client);
  if (!omitTarget) batch.update(doc(client,'users',targetUid),changes);
  if (!omitLog) batch.set(doc(client,'activityLogs',ownerUid+'_'+requestId),value);
  return {requestId,value,commit:()=>batch.commit()};
}

before(async () => {
  env = await initializeTestEnvironment({projectId,
    firestore: {rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8')},
    storage: {rules: await readFile(new URL('../../storage.rules', import.meta.url), 'utf8')}});
});
beforeEach(async () => {await env.clearFirestore();});
after(async () => {await env?.cleanup();});

test('pending Google accounts observe only their own approval and cannot forge the complete entity/activity/receipt batch', async () => {
  const client = db();
  await assertSucceeds(getDoc(doc(client, 'users', UID)));
  await assertSucceeds(getDoc(doc(client, 'accessGrants', EMAIL)));
  await assertSucceeds(getDoc(doc(client, 'accessRequests', UID)));
  await seed('users/' + UID, {status: 'pending'});
  await assertSucceeds(getDoc(doc(client, 'users', UID)));
  for (const [collectionName, key] of [['users','someone-else'], ['accessRequests','someone-else'], ['accessGrants','someone@example.com']]) {
    await assertFails(getDoc(doc(client, collectionName, key)));
    await assertFails(getDocs(query(collection(client, collectionName), limit(20))));
  }
  await assertFails(catalogQuery(client));
  await assertFails(getDoc(doc(client, 'mediaAssets', randomUUID())));
  await assertFails(productBatch({client}).commit());
});

test('revoked Google approval cannot reuse previously approved immutable activity or receipt records', async () => {
  await seed('users/' + UID, {status:'approved'});
  const first = productBatch();
  await assertSucceeds(first.commit());
  const old = await read('products/' + first.id);
  await seed('users/' + UID, {status:'rejected'});
  await assertFails(productBatch({id:first.id, requestId:first.requestId, old, omitLog:true, omitReceipt:true}).commit());
  await assertFails(productBatch({requestId:first.requestId, omitLog:true, omitReceipt:true}).commit());
  await assertFails(productBatch({id:first.id, old}).commit());
  assert.equal((await read('products/' + first.id)).version, 1);
});

test('access requests are create-only pending records tied to the verified Google identity and server time', async () => {
  const client = db(), request = doc(client, 'accessRequests', UID);
  await assertSucceeds(setDoc(request, pending()));
  const stored = await read('accessRequests/' + UID);
  assert.equal(stored.status, 'pending');
  assert.ok(stored.requestedAt instanceof Timestamp);
  await assertFails(setDoc(request, pending()));
  await assertFails(updateDoc(request, {status: 'approved'}));
  await assertFails(deleteDoc(request));
  for (const patch of [{status:'approved'}, {uid:'other'}, {email:'other@example.com'}, {requestedAt:Timestamp.fromMillis(1)},
    {name:''}, {name:' '.repeat(5)}, {name:'x'.repeat(161)}, {to:'attacker@example.com'}, {role:'OWNER'}, {mailSent:true}]) {
    const uid = 'request-' + randomUUID(), forgedClient = db(uid, {email: EMAIL});
    await assertFails(setDoc(doc(forgedClient, 'accessRequests', uid), {...pending(uid), ...patch}));
  }
  await assertFails(setDoc(doc(client, 'accessRequests', 'other'), {...pending(), uid:'other'}));
});

test('the only client profile creation is an own pending profile paired with its identical fresh access request', async () => {
  const client = db(), batch = writeBatch(client), value = pending();
  batch.set(doc(client, 'users', UID), value);
  batch.set(doc(client, 'accessRequests', UID), value);
  await assertSucceeds(batch.commit());
  assert.deepEqual(await read('users/'+UID), await read('accessRequests/'+UID));
  await assertFails(updateDoc(doc(client, 'users', UID), {status:'approved'}));
  await assertFails(updateDoc(doc(client, 'users', UID), {role:'OWNER'}));
  await assertFails(deleteDoc(doc(client, 'users', UID)));
  // A developer changes just this one field; default ADMIN/active values apply.
  await seed('users/'+UID, {...await read('users/'+UID), status:'approved'});
  await assertSucceeds(catalogQuery(client));

  const standalone = 'standalone', standaloneClient = db(standalone);
  await assertFails(setDoc(doc(standaloneClient, 'users', standalone), pending(standalone, standalone+'@example.com')));
  for (const patch of [{status:'approved'}, {role:'OWNER'}, {active:true}, {uid:'other'}, {email:'other@example.com'}]) {
    const uid='profile-'+randomUUID(), forgedClient=db(uid), forged=writeBatch(forgedClient);
    const request=pending(uid, uid+'@example.com');
    forged.set(doc(forgedClient, 'accessRequests', uid), request);
    forged.set(doc(forgedClient, 'users', uid), {...request, ...patch});
    await assertFails(forged.commit());
  }
  const foreign=writeBatch(standaloneClient), request=pending(standalone, standalone+'@example.com');
  foreign.set(doc(standaloneClient, 'accessRequests', standalone), request);
  foreign.set(doc(standaloneClient, 'users', 'someone-else'), request);
  await assertFails(foreign.commit());

  const oldRequestUid='old-request', oldClient=db(oldRequestUid), oldValue=pending(oldRequestUid, oldRequestUid+'@example.com');
  await assertSucceeds(setDoc(doc(oldClient, 'accessRequests', oldRequestUid), oldValue));
  await assertFails(setDoc(doc(oldClient, 'users', oldRequestUid), oldValue));
});

test('password, anonymous and unverified accounts cannot create Google access requests', async () => {
  const clients = [env.unauthenticatedContext().firestore(), db(UID, {email_verified:false}),
    db(UID, {firebase:{sign_in_provider:'password'}}), db(UID, {email_verified:true, firebase:{sign_in_provider:'anonymous'}})];
  for (const client of clients) await assertFails(setDoc(doc(client, 'accessRequests', UID), pending()));
});

test('clients including approved owners cannot approve profiles, grants, requests or mail outboxes', async () => {
  await seed('users/' + UID, {status:'approved', role:'OWNER'});
  const client = db();
  for (const [path, data] of [['users/'+UID,{status:'approved',role:'OWNER'}], ['users/other',{status:'approved'}],
    ['accessGrants/'+EMAIL,{status:'approved'}], ['accessRequestMail/'+UID,{status:'sent'}],
    ['accessRequestMails/'+UID,{status:'sent'}]]) {
    await assertFails(setDoc(doc(client, path), data));
  }
  await assertFails(getDoc(doc(client, 'accessRequestMail', UID)));
  await assertFails(getDoc(doc(client, 'accessRequestMails', UID)));
  await assertFails(getDoc(doc(client, 'backendSecrets', 'googleDrive')));
  await seed('accessRequests/' + UID, {status:'pending'});
  await assertFails(updateDoc(doc(client, 'accessRequests', UID), {status:'approved'}));
});

test('manual UID approval works with no custom claims and defaults to active ADMIN', async () => {
  await seed('users/' + UID, {status:'approved'});
  const client = db(UID, {role:'OWNER'});
  await assertSucceeds(catalogQuery(client));
  await assertFails(adminQuery(client));
  const saved = productBatch({client});
  await assertSucceeds(saved.commit());
  assert.equal((await read('activityLogs/' + UID + '_' + saved.requestId)).actorRole, 'ADMIN');
  await assertFails(productBatch({client, role:'OWNER'}).commit());
});

test('explicit OWNER UID approval authorizes bounded admin reads without custom claims', async () => {
  await seed('users/' + UID, {status:'approved', role:'OWNER'});
  await assertSucceeds(adminQuery(db()));
  await assertFails(getDocs(collection(db(), 'users')));
  await assertSucceeds(productBatch({role:'OWNER'}).commit());
});

test('OWNER can revoke an ADMIN only with its matching immutable removal activity', async () => {
  await seed('users/'+REMOVAL_OWNER,removalOwnerProfile);
  await seed('users/'+REMOVAL_TARGET,removalTargetProfile);
  const removal=removalBatch();
  await assertSucceeds(removal.commit());
  const target=await read('users/'+REMOVAL_TARGET);
  assert.equal(target.status,'revoked');
  assert.equal(target.active,false);
  assert.equal(target.removedBy,REMOVAL_OWNER);
  assert.equal(target.removalRequestId,removal.requestId);
  assert.ok(target.removedAt instanceof Timestamp);
  assert.equal(target.removedAt.toMillis(),target.updatedAt.toMillis());
  assert.equal(target.version,removalTargetProfile.version);
  assert.equal(target.createdBy,removalTargetProfile.createdBy);
  const log=await read('activityLogs/'+REMOVAL_OWNER+'_'+removal.requestId);
  assert.equal(log.action,'removeAdmin');
  assert.equal(log.entityType,'user');
  assert.equal(log.entityId,REMOVAL_TARGET);
  assert.equal(log.timestamp.toMillis(),target.removedAt.toMillis());
  await assertFails(updateDoc(doc(db(REMOVAL_OWNER),'activityLogs',REMOVAL_OWNER+'_'+removal.requestId),{action:'edited'}));
  await assertFails(deleteDoc(doc(db(REMOVAL_OWNER),'activityLogs',REMOVAL_OWNER+'_'+removal.requestId)));
  await assertFails(removalBatch({old:target}).commit());
});

test('admin removal supports claim-free, legacy, pending and inactive ADMIN profiles without approving or upgrading anyone', async () => {
  await seed('users/'+REMOVAL_OWNER,removalOwnerProfile);
  for (const old of [
    {name:'Default Admin',email:REMOVAL_TARGET+'@example.com',status:'approved'},
    {name:'Legacy Admin',email:REMOVAL_TARGET+'@example.com',role:'ADMIN',active:true},
    {name:'Pending Admin',email:REMOVAL_TARGET+'@example.com',status:'pending'},
    {name:'Inactive Admin',email:REMOVAL_TARGET+'@example.com',role:'ADMIN',active:false},
    {name:'Rejected Admin',email:REMOVAL_TARGET+'@example.com',status:'rejected'},
  ]) {
    await seed('users/'+REMOVAL_TARGET,old);
    await assertSucceeds(removalBatch({old}).commit());
    assert.equal((await read('users/'+REMOVAL_TARGET)).status,'revoked');
  }
});

test('legacy OWNER and email-preapproved OWNER can remove ADMINs without changing their existing identity model', async () => {
  await seed('users/'+REMOVAL_OWNER,{name:'Legacy Owner',email:removalOwnerProfile.email,role:'OWNER',active:true});
  await seed('users/'+REMOVAL_TARGET,removalTargetProfile);
  await assertSucceeds(removalBatch({client:db(REMOVAL_OWNER,{role:'OWNER',firebase:{sign_in_provider:'password'}}),
    ownerName:'Legacy Owner'}).commit());
  await env.withSecurityRulesDisabled(ctx=>deleteDoc(doc(ctx.firestore(),'users',REMOVAL_OWNER)));
  await seed('accessGrants/'+removalOwnerProfile.email,removalOwnerProfile);
  await seed('users/'+REMOVAL_TARGET,removalTargetProfile);
  await assertSucceeds(removalBatch().commit());
});

test('ADMIN, unapproved identities, revoked OWNER and forged OWNER claims cannot remove users', async () => {
  await seed('users/'+REMOVAL_TARGET,removalTargetProfile);
  for (const profile of [{status:'approved'}, {status:'approved',role:'ADMIN'}, {status:'pending',role:'OWNER'},
    {status:'revoked',role:'OWNER'}, {status:'approved',role:'OWNER',active:false}]) {
    await seed('users/'+REMOVAL_OWNER,{name:removalOwnerProfile.name,email:removalOwnerProfile.email,...profile});
    await assertFails(removalBatch({client:db(REMOVAL_OWNER,{role:'OWNER'})}).commit());
  }
  await assertFails(removalBatch({client:env.unauthenticatedContext().firestore()}).commit());
});

test('OWNER cannot remove an OWNER, own identity under another UID, or a target with an OWNER email grant', async () => {
  await seed('users/'+REMOVAL_OWNER,removalOwnerProfile);
  for (const old of [
    {...removalTargetProfile,role:'OWNER'},
    {...removalTargetProfile,email:removalOwnerProfile.email.toUpperCase()},
    {...removalTargetProfile,role:'ROOT'},
    {...removalTargetProfile,uid:REMOVAL_OWNER},
    {...removalTargetProfile,status:1},
  ]) {
    await seed('users/'+REMOVAL_TARGET,old);
    await assertFails(removalBatch({old}).commit());
  }
  await assertFails(removalBatch({targetUid:REMOVAL_OWNER,old:removalOwnerProfile}).commit());
  await seed('users/'+REMOVAL_TARGET,removalTargetProfile);
  await seed('accessGrants/'+removalTargetProfile.email,{status:'approved',role:'OWNER'});
  await assertSucceeds(getDoc(doc(db(REMOVAL_OWNER),'accessGrants',removalTargetProfile.email)));
  await assertFails(removalBatch().commit());
  await assertFails(getDocs(query(collection(db(REMOVAL_OWNER),'accessGrants'),limit(20))));
});

test('admin removal rejects missing, fabricated, mismatched and mutable audit transitions', async () => {
  await seed('users/'+REMOVAL_OWNER,removalOwnerProfile);
  await seed('users/'+REMOVAL_TARGET,removalTargetProfile);
  await assertFails(removalBatch({omitLog:true}).commit());
  await assertFails(removalBatch({omitTarget:true}).commit());
  for (const logPatch of [{actorUid:REMOVAL_TARGET},{actorRole:'ADMIN'},{actorName:'Forged name'},
    {actorEmail:'other@example.com'},{action:'edit'},{entityId:'different'}, {before:{}}, {after:{}},
    {changedFields:{}},{requestId:randomUUID()},{timestamp:Timestamp.fromMillis(1)},
    {device:{...DEVICE,deviceName:'forged'}}]) {
    await assertFails(removalBatch({logPatch}).commit());
  }
  const reused=randomUUID();
  await seed('activityLogs/'+REMOVAL_OWNER+'_'+reused,{action:'existing'});
  await assertFails(removalBatch({requestId:reused}).commit());
  assert.deepEqual(await read('users/'+REMOVAL_TARGET),removalTargetProfile);
});

test('OWNER revocation cannot be combined with role, identity, approval or unrelated profile edits', async () => {
  await seed('users/'+REMOVAL_OWNER,removalOwnerProfile);
  await seed('users/'+REMOVAL_TARGET,removalTargetProfile);
  for (const patch of [{status:'approved'},{active:true},{role:'OWNER'},{name:'Changed'},
    {email:'changed@example.com'},{uid:'another-uid'}, {version:5},{createdBy:'changed'},
    {removedBy:REMOVAL_TARGET},{removedAt:Timestamp.fromMillis(1)}, {updatedAt:Timestamp.fromMillis(1)},
    {removalRequestId:'invalid-id'}, {extra:true}]) {
    await assertFails(removalBatch({patch}).commit());
  }
  await assertFails(deleteDoc(doc(db(REMOVAL_OWNER),'users',REMOVAL_TARGET)));
  await assertFails(setDoc(doc(db(REMOVAL_OWNER),'users','uncreated-admin'),removalTargetProfile));
});

test('revoked ADMIN loses catalog and media reads despite grants, cached role claims, new login and repeat requests', async () => {
  await seed('users/'+REMOVAL_OWNER,removalOwnerProfile);
  await seed('users/'+REMOVAL_TARGET,removalTargetProfile);
  await seed('accessGrants/'+removalTargetProfile.email,{status:'approved',role:'ADMIN'});
  const target=db(REMOVAL_TARGET,{role:'ADMIN'});
  await assertSucceeds(catalogQuery(target));
  await assertSucceeds(removalBatch().commit());
  for (const client of [target,db(REMOVAL_TARGET,{auth_time:NOW+3600,role:'ADMIN'}),
    db(REMOVAL_TARGET,{role:'ADMIN',firebase:{sign_in_provider:'password'}})]) {
    await assertFails(catalogQuery(client));
    await assertFails(getDoc(doc(client,'mediaAssets',randomUUID())));
    await assertSucceeds(getDoc(doc(client,'users',REMOVAL_TARGET)));
    await assertFails(setDoc(doc(client,'accessRequests',REMOVAL_TARGET),pending(REMOVAL_TARGET,removalTargetProfile.email)));
    const reapply=writeBatch(client), value=pending(REMOVAL_TARGET,removalTargetProfile.email);
    reapply.set(doc(client,'users',REMOVAL_TARGET),value);
    reapply.set(doc(client,'accessRequests',REMOVAL_TARGET),value);
    await assertFails(reapply.commit());
  }
  assert.equal((await read('accessGrants/'+removalTargetProfile.email)).status,'approved');
});

test('new approvals require a verified Google provider and matching optional UID/email fields', async () => {
  await seed('users/' + UID, {status:'approved'});
  for (const extra of [{email_verified:false}, {firebase:{sign_in_provider:'password'}}, {auth_time:0}]) {
    await assertFails(catalogQuery(db(UID, extra)));
  }
  for (const patch of [{uid:'different'}, {email:'different@example.com'}, {role:'SUPERADMIN'}, {active:'true'}, {active:false}]) {
    await seed('users/' + UID, {status:'approved', ...patch});
    await assertFails(catalogQuery(db()));
  }
  await seed('users/' + UID, {status:'approved', uid:UID, email:EMAIL.toUpperCase()});
  await assertSucceeds(catalogQuery(db()));
});

test('email preapproval is tied to the exact normalized verified Google email', async () => {
  await seed('accessGrants/' + EMAIL, {status:'approved'});
  await assertSucceeds(catalogQuery(db()));
  await assertSucceeds(catalogQuery(db(UID, {email:EMAIL.toUpperCase()})));
  await assertSucceeds(getDoc(doc(db(UID, {email:EMAIL.toUpperCase()}), 'accessGrants', EMAIL)));
  await assertFails(catalogQuery(db('other')));
  await assertFails(catalogQuery(db(UID, {email_verified:false})));
  await assertFails(catalogQuery(db(UID, {firebase:{sign_in_provider:'password'}})));
  await assertSucceeds(productBatch().commit());
  for (const patch of [{uid:'different'}, {email:'different@example.com'}, {role:'ROOT'}, {active:false}, {active:'true'}]) {
    await seed('accessGrants/' + EMAIL, {status:'approved', ...patch});
    await assertFails(catalogQuery(db()));
  }
});

test('explicit nonapproved UID status and disabled UID override grants and legacy role claims', async () => {
  await seed('accessGrants/' + EMAIL, {status:'approved', role:'OWNER'});
  for (const profile of [{status:'pending'}, {status:'rejected'}, {status:'disabled'}, {status:'unknown'}, {status:1},
    {status:'approved',active:false}, {active:false}, {active:'true'}]) {
    await seed('users/' + UID, {name:NAME, role:'OWNER', ...profile});
    await assertFails(catalogQuery(db(UID, {role:'OWNER'})));
    await assertFails(productBatch({client:db(UID, {role:'OWNER'}), role:'OWNER'}).commit());
  }
});

test('a pending request or approved request document never grants catalog access', async () => {
  for (const status of ['pending', 'approved']) {
    await seed('accessRequests/' + UID, {status, uid:UID, email:EMAIL, name:NAME});
    await assertFails(catalogQuery(db()));
  }
});

test('legacy profiles preserve their matching claim identity before considering an email grant', async () => {
  await seed('users/' + UID, {name:'Legacy Owner', role:'OWNER', active:true});
  await seed('accessGrants/' + EMAIL, {status:'approved', role:'ADMIN', name:'Email Admin'});
  const client = db(UID, {role:'OWNER'});
  await assertSucceeds(adminQuery(client));
  await assertSucceeds(productBatch({client, role:'OWNER', actorName:'Legacy Owner'}).commit());
  await assertFails(productBatch({client, role:'ADMIN', actorName:'Email Admin'}).commit());
});

test('legacy password OWNER and ADMIN retain access while revocation and explicit UID decisions still veto it', async () => {
  for (const role of ['OWNER', 'ADMIN']) {
    const uid = 'legacy-password-' + role.toLowerCase(), email = uid + '@example.com', actorName = 'Legacy ' + role;
    const profile = {uid, email, name:actorName, role, active:true, revokedBeforeSeconds:0};
    const client = db(uid, {role, email_verified:false, firebase:{sign_in_provider:'password'}});
    const mutation = () => productBatch({client, uid, email, role, actorName}).commit();
    await seed('users/' + uid, profile);
    await assertSucceeds(catalogQuery(client));
    await assertSucceeds(getDoc(doc(client, 'users', uid)));
    await assertSucceeds(mutation());
    if (role === 'OWNER') await assertSucceeds(adminQuery(client));
    else await assertFails(adminQuery(client));

    await seed('users/' + uid, {...profile, revokedBeforeSeconds:NOW});
    await assertFails(catalogQuery(client));
    await assertFails(mutation());
    await assertSucceeds(catalogQuery(db(uid, {role, auth_time:NOW+1, firebase:{sign_in_provider:'password'}})));

    // Neither matching claims nor a preapproved email can bypass a new UID decision.
    await seed('accessGrants/' + email, {status:'approved', role});
    for (const patch of [{status:'pending'}, {status:'rejected'}, {active:false}, {role:role === 'OWNER' ? 'ADMIN' : 'OWNER'}]) {
      await seed('users/' + uid, {...profile, ...patch});
      await assertFails(catalogQuery(client));
      await assertFails(mutation());
    }
  }
});

test('live revocation fences remain effective for UID, grant and legacy approval paths', async () => {
  await seed('users/' + UID, {status:'approved', revokedBeforeSeconds:NOW});
  await assertFails(catalogQuery(db()));
  await assertSucceeds(catalogQuery(db(UID, {auth_time:NOW+1})));
  await env.withSecurityRulesDisabled(ctx => deleteDoc(doc(ctx.firestore(), 'users', UID)));
  await seed('accessGrants/' + EMAIL, {status:'approved', revokedBeforeSeconds:NOW});
  await assertFails(catalogQuery(db()));
  await assertSucceeds(catalogQuery(db(UID, {auth_time:NOW+1})));
  await seed('users/' + UID, {role:'ADMIN', active:true, revokedBeforeSeconds:NOW});
  await assertFails(catalogQuery(db(UID, {role:'ADMIN'})));
  await assertSucceeds(catalogQuery(db(UID, {role:'ADMIN', auth_time:NOW+1})));
});

test('audit names use the effective trusted profile, then Google token name, then verified email', async () => {
  await seed('users/' + UID, {status:'approved', name:'Trusted Admin'});
  await assertSucceeds(productBatch({actorName:'Trusted Admin'}).commit());
  await assertFails(productBatch({actorName:'Forged name'}).commit());
  await seed('users/' + UID, {status:'approved', name:'   '});
  await assertSucceeds(productBatch().commit());
  await assertSucceeds(productBatch({client:db(UID, {name:''}), actorName:EMAIL}).commit());
});

test('claim-free UID approval supports the existing atomic eight-image write budget', async () => {
  await seed('users/' + UID, {status:'approved'});
  const saved = productBatch({imageCount:8});
  await saved.seedImages();
  await assertSucceeds(saved.commit());
});

test('email-only approval supports atomic eight-image writes without a UID profile', async () => {
  await seed('accessGrants/' + EMAIL, {status:'approved'});
  const saved = productBatch({imageCount:8});
  await saved.seedImages();
  await assertSucceeds(saved.commit());
});

test('claim-free Google approval validates native upload reservations and the selected profile media audit', async () => {
  await seed('accessGrants/' + EMAIL, {status:'approved', name:'Trusted uploader'});
  const client = db(), id = randomUUID(), requestId = randomUUID();
  const asset = {id, requestId, driveFileId:'drive_file_' + id.replaceAll('-', ''),
    folder:'1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd', entityType:'product', entityId:randomUUID(),
    createdBy:UID, device:DEVICE, status:'reserved', expectedBytes:68, expectedMime:'image/png',
    originalSha256:'a'.repeat(64), originalFileName:'fixture.png', createdAt:serverTimestamp(),
    expiresAt:Timestamp.fromMillis(Date.now()+86400000), source:'android'};
  const reservation = writeBatch(client);
  reservation.set(doc(client, 'mediaAssets', id), asset);
  reservation.set(doc(client, 'driveUploadRequests', UID+'_'+requestId),
    {hash:'a'.repeat(64), assetId:id, createdAt:serverTimestamp(), createdBy:UID, source:'android'});
  await assertSucceeds(reservation.commit());
  const finish = (actorName, actorRole = 'ADMIN') => {
    const batch = writeBatch(client);
    batch.update(doc(client, 'mediaAssets', id), {status:'uploaded', fileName:'fixture.png',
      mimeType:'image/png', sizeBytes:68, sha256:'a'.repeat(64), uploadedAt:serverTimestamp()});
    batch.set(doc(client, 'audit_logs', 'upload_'+id), {userId:UID, userEmail:EMAIL, actorName, actorRole,
      action:'UPLOAD_PRODUCT_IMAGE', targetId:asset.entityId, entityType:'product', driveFileId:asset.driveFileId,
      mediaId:id, deviceId:DEVICE.installationId, device:DEVICE, status:'SUCCESS', timestamp:serverTimestamp(), requestId});
    return batch.commit();
  };
  await assertFails(finish(NAME));
  await assertFails(finish('Trusted uploader', 'OWNER'));
  await assertSucceeds(finish('Trusted uploader'));
  assert.equal((await read('mediaAssets/'+id)).status, 'uploaded');
});

test('trusted OWNER approval permits foreign unattached media cleanup without an OWNER token claim', async () => {
  const id=randomUUID();
  await seed('mediaAssets/'+id, {id, status:'uploaded', createdBy:'another-user', entityType:'product', entityId:randomUUID()});
  await seed('users/'+UID, {status:'approved'});
  const patch = () => ({status:'deleting', deleteRequestId:randomUUID(), deleteDevice:DEVICE,
    deleteActorUid:UID, deleteStartedAt:serverTimestamp()});
  await assertFails(updateDoc(doc(db(UID, {role:'OWNER'}), 'mediaAssets', id), patch()));
  await seed('users/'+UID, {status:'approved', role:'OWNER'});
  await assertSucceeds(updateDoc(doc(db(), 'mediaAssets', id), patch()));
});

test('Storage private reads use the same developer approval and retain public projection rules', async () => {
  const id=randomUUID(), path=`media/product/${id}/fixture.png`;
  await env.withSecurityRulesDisabled(ctx => uploadBytes(ref(ctx.storage(BUCKET), path), Buffer.from('private-fixture'), {contentType:'image/png'}));
  await assertFails(getBytes(ref(google().storage(BUCKET), path), 1024));
  await seed('users/' + UID, {status:'approved'});
  await assertSucceeds(getBytes(ref(google().storage(BUCKET), path), 1024));
  await seed('users/' + UID, {status:'rejected'});
  await seed('accessGrants/' + EMAIL, {status:'approved'});
  await assertFails(getBytes(ref(google().storage(BUCKET), path), 1024));
  await env.withSecurityRulesDisabled(ctx => deleteDoc(doc(ctx.firestore(), 'users', UID)));
  await assertSucceeds(getBytes(ref(google().storage(BUCKET), path), 1024));
  await assertFails(getBytes(ref(env.unauthenticatedContext().storage(BUCKET), path), 1024));
  await seed('publicProducts/' + id, {status:'active', archived:false, mediaPaths:[path]});
  await assertSucceeds(getBytes(ref(env.unauthenticatedContext().storage(BUCKET), path), 1024));
});
