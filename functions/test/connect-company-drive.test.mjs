import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,readFileSync,statSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import tool from '../scripts/connect-company-drive.cjs';
import oauth from '../lib/drive/oauth.js';
const config={origin:'http://localhost:8787',redirectUri:'http://localhost:8787/api/drive/oauth/callback',accountEmail:'mtahakurniawan@gmail.com'};
const state='fixture-state-'.repeat(4);

test('operator requires the exact registered loopback callback and never accepts a public listener',()=>{
  assert.equal(tool.validateLoopbackConfig(config).hostname,'localhost');
  assert.equal(tool.validateLoopbackConfig({...config,origin:'http://127.0.0.1:8787',redirectUri:'http://127.0.0.1:8787/api/drive/oauth/callback'}).hostname,'127.0.0.1');
  for(const redirectUri of ['https://localhost:8787/api/drive/oauth/callback','http://0.0.0.0:8787/api/drive/oauth/callback',
    'http://localhost.attacker.test:8787/api/drive/oauth/callback','http://localhost:8788/api/drive/oauth/callback',
    config.redirectUri+'?code=private',config.redirectUri+'#private',config.redirectUri.replace('/callback','/other')]) {
    assert.throws(()=>tool.validateLoopbackConfig({...config,redirectUri}));
  }
});

test('authorization artifact accepts Google generated state only and is private on disk',()=>{
  const authorizationUrl='https://accounts.google.com/o/oauth2/v2/auth?state='+state;
  assert.equal(tool.authorizationState(authorizationUrl),state);
  for(const url of [authorizationUrl.replace('accounts.google.com','accounts.google.com.attacker.test'),authorizationUrl+'&state=duplicate',authorizationUrl.replace('https://','http://')]) assert.throws(()=>tool.authorizationState(url));
  const root=mkdtempSync(path.join(tmpdir(),'jtp-owner-drive-test-'));
  try {
    const file=tool.writePrivateAuthorization(root,authorizationUrl);
    assert.equal(JSON.parse(readFileSync(file,'utf8')).authorizationUrl,authorizationUrl);
    if(process.platform!=='win32') {
      assert.equal(statSync(file).mode & 0o777,0o600);
      assert.equal(statSync(path.dirname(file)).mode & 0o777,0o700);
    }
  } finally {rmSync(root,{recursive:true,force:true});}
});

test('operator uses the existing configured OWNER only and cannot create or approve an account',async()=>{
  const calls=[];
  const user={uid:'existing-owner',email:config.accountEmail,disabled:false,customClaims:{role:'OWNER'}};
  const auth={getUserByEmail:async email=>{calls.push(email);return user;}};
  const profile={active:true,role:'OWNER'};
  const db={doc:document=>{assert.equal(document,'users/existing-owner');return {get:async()=>({data:()=>profile})};}};
  const actor=await tool.ownerActor(config,auth,db,oauth.assertOAuthOwner,()=>101000);
  assert.deepEqual(calls,[config.accountEmail]);assert.equal(actor.role,'OWNER');assert.equal(actor.authTime,101);
  for(const bad of [{...profile,active:false},{...profile,status:'pending'},{...profile,status:'rejected'},{...profile,active:'true'},{...profile,role:'ADMIN'}]) {
    await assert.rejects(tool.ownerActor(config,auth,{doc:()=>({get:async()=>({data:()=>bad})})},oauth.assertOAuthOwner,()=>101000),/OWNER/);
  }
  await assert.rejects(tool.ownerActor(config,{getUserByEmail:async()=>({...user,customClaims:{role:'ADMIN'}})},db,oauth.assertOAuthOwner),/OWNER/);
});

test('manual UID OWNER approval works without claims while explicit revocation always wins',()=>{
  const user={disabled:false,customClaims:{}};
  assert.doesNotThrow(()=>oauth.assertOAuthOwner({status:'approved',role:'OWNER'},user,101));
  for(const profile of [{status:'pending',role:'OWNER'},{status:'approved',role:'OWNER',active:false},
    {status:'approved',role:'OWNER',revokedBeforeSeconds:101},{status:'approved',role:'OWNER',revokedBeforeSeconds:'100'},
    {status:'approved',role:'OWNER',revokedBeforeSeconds:null},{status:'approved',role:'ADMIN'}]) {
    assert.throws(()=>oauth.assertOAuthOwner(profile,user,101),error=>error.code==='permission-denied');
  }
});

test('loopback ignores foreign, invalid and duplicate requests; exact callback invokes existing completion once',async t=>{
  const calls=[];
  const listener=await tool.listenForCallback(new URL(config.redirectUri),state,async(...args)=>{calls.push(args);},{listenPort:0,timeoutMs:5000});
  t.after(()=>listener.close());
  const base='http://127.0.0.1:'+listener.port+'/api/drive/oauth/callback';
  assert.equal((await fetch(base+'?state=wrong&code=private-code')).status,400);
  assert.equal((await fetch(base+'?state='+state+'&code=one&code=two')).status,400);
  assert.equal((await fetch(base+'?state='+state+'&code=private-code',{headers:{Origin:'https://attacker.test'}})).status,404);
  assert.equal(calls.length,0);
  const response=await fetch(base+'?state='+state+'&code=private-code');
  assert.equal(response.status,200);
  const text=await response.text();assert.ok(!text.includes(state) && !text.includes('private-code'));
  assert.equal(response.headers.get('referrer-policy'),'no-referrer');
  await listener.completion;
  assert.deepEqual(calls,[[state,'private-code']]);
});

test('failed consent and completion errors return generic HTML without reflecting credentials',async t=>{
  for(const query of ['&error=access_denied','&code=private-code']) {
    const listener=await tool.listenForCallback(new URL(config.redirectUri),state,async()=>{throw new Error('private-provider-secret');},{listenPort:0,timeoutMs:5000});
    t.after(()=>listener.close());
    const response=await fetch('http://127.0.0.1:'+listener.port+'/api/drive/oauth/callback?state='+state+query);
    assert.equal(response.status,400);
    const html=await response.text();assert.ok(!html.includes('private-provider-secret') && !html.includes(state) && !html.includes('private-code'));
    await assert.rejects(listener.completion,/Persetujuan/);
  }
});
