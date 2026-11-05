import {test} from 'node:test';
import assert from 'node:assert/strict';
import {HttpsError} from 'firebase-functions/v2/https';
import module from '../lib/central-server.js';
const id='11111111-1111-4111-8111-111111111111';
const token={uid:'google-fixture', email:'fixture@example.test', email_verified:true, auth_time:100, firebase:{sign_in_provider:'google.com'}};
const headers={Authorization:'Bearer fixture-token','Content-Type':'application/json'};
async function start(t, overrides={}) {
  const seen={verify:[],approve:[],notify:[],drive:[]};
  const server=module.centralServer({
    verifyIdToken:async (...args)=>{seen.verify.push(args);return token;},
    approve:async identity=>{seen.approve.push(identity);return {...identity,role:'ADMIN'};},
    notify:async identity=>{seen.notify.push(identity);return {emailSent:false,emailQueued:false,retryable:true};},
    drive:Object.fromEntries(['status','reserve','upload','metadata','read','trash'].map(name=>[name,async(...args)=>{
      seen.drive.push({name,args});
      return name==='read'?{bytes:Buffer.from('fixture-image'),mimeType:'image/png',sha256:'hash'}:name==='reserve'?{id:'reserved-drive-id'}:{connected:true};
    }])), ...overrides,
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));});
  return {base:'http://127.0.0.1:'+server.address().port,seen};
}

test('central profile excludes old admin, OAuth and arbitrary raw Drive routes before authentication', async t=>{
  const {base,seen}=await start(t);
  assert.equal((await fetch(base+'/healthz')).status,200);
  for (const [method,path] of [['POST','/api/call/manageAdmin'],['GET','/api/drive/oauth/callback'],['GET','/api/drive/files/raw-drive-id'],['GET','/api/media/'+id],['POST','/api/uploads/'+id]]) {
    assert.equal((await fetch(base+path,{method})).status,404);
  }
  assert.deepEqual(seen,{verify:[],approve:[],notify:[],drive:[]});
});

test('pending verified Google users can notify only; Drive requires approved access and revocation verification', async t=>{
  const {base,seen}=await start(t,{approve:async()=>{throw new HttpsError('permission-denied','Approval required');}});
  const notify=await fetch(base+'/api/access/request',{method:'POST',headers,body:'{}'});
  assert.equal(notify.status,200);
  assert.deepEqual(await notify.json(),{result:{emailSent:false,emailQueued:false,retryable:true}});
  assert.equal((await fetch(base+'/api/drive/status',{headers})).status,403);
  assert.deepEqual(seen.verify,[['fixture-token',true],['fixture-token',true]]);
  assert.equal(seen.notify.length,1);assert.equal(seen.drive.length,0);
});

test('missing/revoked tokens and non-Google providers never enter request or Drive handlers', async t=>{
  const missing=await start(t);
  assert.equal((await fetch(missing.base+'/api/drive/status')).status,401);
  const revoked=await start(t,{verifyIdToken:async()=>{throw new Error('private-token-data');}});
  assert.equal((await fetch(revoked.base+'/api/drive/status',{headers})).status,401);
  const password=await start(t,{verifyIdToken:async()=>({...token,firebase:{sign_in_provider:'password'}})});
  assert.equal((await fetch(password.base+'/api/access/request',{method:'POST',headers,body:'{}'})).status,403);
  assert.equal(password.seen.notify.length,0);assert.equal(password.seen.drive.length,0);
});

test('asset and reservation protocol passes only authenticated identity, UUID and bounded image bytes', async t=>{
  const {base,seen}=await start(t);
  const reserve=await fetch(base+'/api/drive/reserve',{method:'POST',headers,body:'{}'});
  assert.deepEqual(await reserve.json(),{result:{id:'reserved-drive-id'}});
  const upload=await fetch(base+'/api/drive/assets/'+id,{method:'PUT',headers:{...headers,'Content-Type':'image/png'},body:'fixture-bytes'});
  assert.deepEqual(await upload.json(),{media:{connected:true}});
  const downloaded=await fetch(base+'/api/drive/assets/'+id,{headers});
  assert.equal(await downloaded.text(),'fixture-image');
  assert.equal(downloaded.headers.get('cache-control'),'private, no-store');
  assert.equal((await fetch(base+'/api/drive/assets/'+id+'/metadata',{headers})).status,200);
  assert.equal((await fetch(base+'/api/drive/assets/'+id,{method:'DELETE',headers,body:'{}'})).status,200);
  assert.deepEqual(seen.drive.map(call=>call.name),['reserve','upload','read','metadata','trash']);
  assert.equal(seen.drive[1].args[0].uid,token.uid);
  assert.equal(seen.drive[1].args[1],id);
  assert.equal(seen.drive[1].args[2],'image/png');
  assert.equal(seen.drive[1].args[3].toString(),'fixture-bytes');
});

test('client recipients/roles, unexpected browser origins, encoded and oversized bodies are rejected', async t=>{
  const {base,seen}=await start(t);
  for(const [customHeaders,body,expected] of [
    [headers,'{"to":"attacker@example.test","role":"OWNER"}',400],
    [headers,'[]',400], [headers,'x'.repeat(4097),429],
    [{...headers,'Content-Encoding':'gzip'},'{}',400],
    [{...headers,Origin:'https://untrusted.example'},'{}',403],
  ]) assert.equal((await fetch(base+'/api/access/request',{method:'POST',headers:customHeaders,body})).status,expected);
  assert.equal(seen.notify.length,0);
  assert.equal((await fetch(base+'/api/drive/assets/'+id,{method:'PUT',headers:{...headers,'Content-Type':'image/png'},body:Buffer.alloc(5*1024*1024+1)})).status,429);
  assert.equal(seen.drive.length,0);
});
