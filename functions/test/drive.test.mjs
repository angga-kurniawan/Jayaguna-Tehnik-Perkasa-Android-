import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes,createHash} from 'node:crypto';
import sharp from 'sharp';
import cryptoModule from '../lib/drive/crypto.js';
import imageModule from '../lib/drive/image.js';
import transportModule from '../lib/drive/transport.js';
import serverModule from '../lib/server.js';
import oauthModule from '../lib/drive/oauth.js';
import {HttpsError} from 'firebase-functions/v2/https';
const {seal,unseal}=cryptoModule,{normalizeImage,MAX_BYTES}=imageModule,{DriveApi}=transportModule,{RateLimit,apiServer}=serverModule;

test('OAuth callback rejects revoked, disabled or demoted initiating OWNER',()=>{
  const profile={active:true,role:'OWNER'},user={disabled:false,customClaims:{role:'OWNER'},tokensValidAfterTime:new Date(100000).toISOString()};
  assert.doesNotThrow(()=>oauthModule.assertOAuthOwner(profile,user,101));
  for(const [p,u,time] of [[{...profile,active:false},user,101],[{...profile,role:'ADMIN'},user,101],
    [profile,{...user,disabled:true},101],[profile,{...user,customClaims:{role:'ADMIN'}},101],
    [profile,user,99],[{...profile,revokedBeforeSeconds:101},user,101],[profile,user,NaN]]) {
    assert.throws(()=>oauthModule.assertOAuthOwner(p,u,time),e=>e.code==='permission-denied');
  }
});

test('encrypted OAuth grant rejects tampering, wrong purpose and wrong key',()=>{
  const key=randomBytes(32),sealed=seal('unit-test-grant',key,'refresh-token');
  assert.equal(unseal(sealed,key,'refresh-token'),'unit-test-grant');
  assert.equal(JSON.stringify(sealed).includes('unit-test-grant'),false);
  assert.throws(()=>unseal(sealed,key,'pkce'));
  assert.throws(()=>unseal(sealed,randomBytes(32),'refresh-token'));
  assert.throws(()=>unseal({...sealed,ciphertext:Buffer.from('tampered').toString('base64')},key,'refresh-token'));
});
test('image validation checks bytes, not only client MIME, and retains transparent PNG',async()=>{
  await assert.rejects(normalizeImage(Buffer.from('not-image'),'image/png'),e=>e.code==='invalid-argument');
  await assert.rejects(normalizeImage(Buffer.alloc(MAX_BYTES+1),'image/jpeg'),e=>e.code==='invalid-argument');
  const png=await sharp({create:{width:32,height:32,channels:4,background:{r:1,g:2,b:3,alpha:0.3}}}).png().toBuffer();
  const normalized=await normalizeImage(png,'image/png');
  assert.equal((await sharp(normalized.bytes).metadata()).hasAlpha,true);
  assert.match(normalized.sha256,/^[a-f0-9]{64}$/);
  await assert.rejects(normalizeImage(png,'image/jpeg'));
});
const client={getAccessToken:async()=>({token:'unit-test-access-token'}),refreshAccessToken:async()=>({})};
test('Drive retries reuse a reserved file ID; 409 verifies exact metadata and bytes',async()=>{
  const bytes=Buffer.from('bounded-test-image'),id='drive-reserved-file-id',folder='drive-products-folder',assetId='11111111-1111-4111-8111-111111111111';
  const sha=createHash('sha256').update(bytes).digest('hex');let uploads=0;
  const request=async(url,options)=>{
    assert.match(options.headers.Authorization,/^Bearer /);
    if(url.includes('/upload/')) {uploads++;assert.ok(Buffer.from(options.body).includes(Buffer.from(id)));return new Response('{}',{status:uploads===1?503:409});}
    return Response.json({id,name:'image.png',mimeType:'image/png',parents:[folder],appProperties:{assetId,sha256:sha},md5Checksum:createHash('md5').update(bytes).digest('hex')});
  };
  const drive=new DriveApi(async()=>client,request,async()=>{});
  assert.equal((await drive.upload(id,folder,assetId,'entity','product','image.png','image/png',bytes)).id,id);
  assert.equal(uploads,2);
});
test('Drive rejects a public folder and a duplicate ID with different contents',async()=>{
  const drive=new DriveApi(async()=>client,async()=>Response.json({id:'drive-folder-id',mimeType:'application/vnd.google-apps.folder',capabilities:{canAddChildren:true},permissions:[{type:'anyone'}]}),async()=>{});
  await assert.rejects(drive.folder('drive-folder-id'),e=>e.code==='failed-precondition');
  const conflict=new DriveApi(async()=>client,async url=>url.includes('/upload/')?new Response('{}',{status:409}):Response.json({id:'drive-reserved-id',mimeType:'image/png',parents:['folder'],appProperties:{assetId:'wrong'}}),async()=>{});
  await assert.rejects(conflict.upload('drive-reserved-id','folder','asset','entity','product','image.png','image/png',Buffer.from('data')),e=>e.code==='failed-precondition');
});
test('Drive refresh failure is sanitized and never exposes upstream credentials',async()=>{
  const drive=new DriveApi(async()=>({getAccessToken:async()=>{throw new Error('secret-refresh-token')}}),fetch,async()=>{});
  await assert.rejects(drive.reserveId(),e=>e.code==='failed-precondition' && !e.message.includes('secret-refresh-token'));
});
test('rate limit expires and cannot be bypassed inside one window',()=>{
  let now=0;const limiter=new RateLimit(()=>now);
  assert.equal(limiter.allow('ip',2),true);assert.equal(limiter.allow('ip',2),true);assert.equal(limiter.allow('ip',2),false);
  now=60001;assert.equal(limiter.allow('ip',2),true);
});
test('HTTP reader checks publication again before cached bytes or 304',async()=>{
  let visible=true,reads=0;
  const asset={id:'11111111-1111-4111-8111-111111111111',sha256:'hash',mimeType:'image/png',sizeBytes:3};
  const server=apiServer({authenticate:async(req,required)=>{if(required)throw new HttpsError('unauthenticated','Login wajib.');return undefined;},call:async()=>({}),oauthComplete:async()=>({}),
    media:{readable:async()=>{if(!visible)throw new HttpsError('not-found','Gambar tidak tersedia.');return asset;},bytes:async()=>{reads++;return Buffer.from('png');}}});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try {
    const base='http://127.0.0.1:'+server.address().port;
    assert.equal((await fetch(base+'/api/call/getSession',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).status,401);
    const image=await fetch(base+'/api/media/'+asset.id);assert.equal(image.status,200);assert.equal(await image.text(),'png');
    visible=false;
    assert.equal((await fetch(base+'/api/media/'+asset.id,{headers:{'If-None-Match':'"hash"'}})).status,404);
    assert.equal(reads,1);
    assert.equal((await fetch(base+'/healthz',{headers:{Origin:'https://untrusted.test'}})).status,403);
  } finally {await new Promise(resolve=>server.close(resolve));}
});
