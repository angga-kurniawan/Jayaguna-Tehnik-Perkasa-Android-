import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import admin from '../scripts/apps-script-admin.cjs';

// Local parser/crypto fixtures only. Never sent to Google or used for login.
const config={clientId:'179144038580-offline_fixture.apps.googleusercontent.com',clientSecret:'OFFLINE_CLIENT_SECRET'};
const manifest=JSON.parse(fs.readFileSync(new URL('../../apps-script/appsscript.json',import.meta.url),'utf8'));
const source=fs.readFileSync(new URL('../../apps-script/Code.gs',import.meta.url),'utf8');
const files=()=>admin.contentPayload(source,JSON.stringify(manifest));
const scriptId='script_offline_fixture_12345678901234567890';
const deploymentId='AKfycb_offline_fixture_12345678901234567890';
const webAppUrl='https://script.google.com/macros/s/'+deploymentId+'/exec';
const project={scriptId,title:admin.TITLE,creator:{email:admin.OWNER}};
const clone=value=>JSON.parse(JSON.stringify(value));

test('credential parser rejects missing and wrong-project credentials while accepting configured Desktop or Web clients',()=>{
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'jtp-script-client-'));
  const file=path.join(directory,'local.json');
  try{
    for(const credential of [{},{web:{client_secret:'DO_NOT_PRINT'}},{installed:{project_id:'wrong',client_secret:'DO_NOT_PRINT'}}]){
      fs.writeFileSync(file,JSON.stringify(credential));
      assert.throws(()=>admin.clientConfig(file),error=>error.safeCode==='oauth-client' && !error.message.includes('DO_NOT_PRINT'));
    }
    fs.writeFileSync(file,JSON.stringify({installed:{project_id:'jayagunatehnikperkasa-512819',client_id:config.clientId,client_secret:config.clientSecret}}));
    assert.deepEqual(admin.clientConfig(file),config);
    const web={project_id:'jayagunatehnikperkasa-512819',client_id:config.clientId,client_secret:config.clientSecret,redirect_uris:['http://localhost:8787/api/drive/oauth/callback']};
    fs.writeFileSync(file,JSON.stringify({web}));
    assert.deepEqual(admin.clientConfig(file),{...config,redirectUri:web.redirect_uris[0],port:8787});
    fs.writeFileSync(file,JSON.stringify({web:{...web,redirect_uris:['https://attacker.test/callback']}}));
    assert.throws(()=>admin.clientConfig(file),error=>error.safeCode==='oauth-client');
  }finally{fs.rmSync(directory,{recursive:true,force:true});}
});

test('owner identity requires verified email and the exact OAuth audience',()=>{
  const owner={email:admin.OWNER,email_verified:true,aud:config.clientId};
  admin.requireOwner(owner,config.clientId);
  for(const identity of [null,{...owner,email:'other@example.com'},{...owner,email_verified:false},{...owner,aud:'other-client'},{...owner,aud:undefined}]){
    assert.throws(()=>admin.requireOwner(identity,config.clientId),error=>error.safeCode==='wrong-owner');
  }
});

test('encrypted refresh grants are authenticated and bound to the original client and owner',()=>{
  const key=crypto.randomBytes(32), value={ownerEmail:admin.OWNER,clientId:config.clientId,refreshToken:'OFFLINE_REFRESH_SECRET',scopes:admin.SCOPES};
  const sealed=admin.encryptedGrant(value,key,config.clientId);
  assert.equal(JSON.stringify(sealed).includes(value.refreshToken),false);
  assert.deepEqual(admin.decryptedGrant(sealed,key,config.clientId),value);
  for(const attempt of [()=>admin.decryptedGrant(sealed,key,'wrong-client'),()=>admin.decryptedGrant({...sealed,tag:crypto.randomBytes(16).toString('base64')},key,config.clientId),()=>admin.decryptedGrant(admin.encryptedGrant({...value,ownerEmail:'other@example.com'},key,config.clientId),key,config.clientId)]){
    assert.throws(attempt,error=>error.safeCode==='local-grant' && !error.message.includes(value.refreshToken));
  }
});

test('grant storage persists no plaintext refresh, access, ID token or client secret',()=>{
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'jtp-script-grant-'));
  try{
    assert.throws(()=>admin.loadGrant(config,directory),error=>error.safeCode==='authorization-required');
    admin.saveGrant({refresh_token:'OFFLINE_REFRESH_SECRET',access_token:'OFFLINE_ACCESS_SECRET',id_token:'OFFLINE_ID_SECRET'},admin.SCOPES,config,directory);
    const saved=fs.readdirSync(directory).map(file=>fs.readFileSync(path.join(directory,file),'utf8')).join('\n');
    for(const secret of ['OFFLINE_REFRESH_SECRET','OFFLINE_ACCESS_SECRET','OFFLINE_ID_SECRET',config.clientSecret])assert.equal(saved.includes(secret),false);
    assert.equal(admin.loadGrant(config,directory).ownerEmail,admin.OWNER);
    assert.throws(()=>admin.saveGrant({refresh_token:'do-not-print'},['openid','email'],config,directory),error=>error.safeCode==='oauth-scope');
  }finally{fs.rmSync(directory,{recursive:true,force:true});}
});

test('OAuth URL uses loopback, offline consent, exact scopes, unpredictable state and PKCE',()=>{
  const state=crypto.randomBytes(32).toString('base64url'), verifier=crypto.randomBytes(48).toString('base64url');
  const request=admin.authorizationRequest(config,54321,state,verifier), url=new URL(request.authorizationUrl);
  assert.equal(url.origin,'https://accounts.google.com');
  assert.equal(url.searchParams.get('redirect_uri'),'http://127.0.0.1:54321/oauth2callback');
  assert.equal(url.searchParams.get('access_type'),'offline'); assert.equal(url.searchParams.get('prompt'),'consent');
  assert.equal(url.searchParams.get('code_challenge_method'),'S256');
  assert.equal(url.searchParams.get('code_challenge'),crypto.createHash('sha256').update(verifier).digest('base64url'));
  assert.deepEqual(url.searchParams.get('scope').split(' '),admin.SCOPES);
  assert.equal(url.searchParams.get('login_hint'),admin.OWNER);
  assert.equal(url.toString().includes(config.clientSecret),false);
  assert.equal(url.toString().includes(verifier),false);
  assert.equal(admin.validState(state,state),true);
  for(const wrong of ['',null,'x'.repeat(43),'é'.repeat(43),state+'x'])assert.equal(admin.validState(wrong,state),false);
});

test('existing Web client uses only its registered fixed localhost callback',()=>{
  const state=crypto.randomBytes(32).toString('base64url'),verifier=crypto.randomBytes(48).toString('base64url');
  const web={...config,redirectUri:'http://localhost:8787/api/drive/oauth/callback',port:8787};
  const request=admin.authorizationRequest(web,8787,state,verifier);
  assert.equal(new URL(request.authorizationUrl).searchParams.get('redirect_uri'),web.redirectUri);
  assert.throws(()=>admin.authorizationRequest(web,54321,state,verifier),error=>error.safeCode==='local-oauth');
  assert.throws(()=>admin.authorizationRequest({...web,redirectUri:'https://attacker.test/callback'},8787,state,verifier),error=>error.safeCode==='local-oauth');
});

test('deployment content preserves source and rejects unexpected runtime access/scopes',()=>{
  const payload=files();
  assert.deepEqual(payload.files.map(value=>[value.name,value.type]),[['Code','SERVER_JS'],['appsscript','JSON']]);
  assert.equal(JSON.parse(payload.files[1].source).webapp.executeAs,'USER_DEPLOYING');
  assert.equal(admin.contentHash(payload),admin.contentHash({files:[...payload.files].reverse()}));
  for(const changed of [{...manifest,webapp:{access:'ANYONE_ANONYMOUS',executeAs:'USER_ACCESSING'}},{...manifest,oauthScopes:[...manifest.oauthScopes,'https://mail.google.com/']}]){
    assert.throws(()=>admin.contentPayload(source,JSON.stringify(changed)),error=>error.safeCode==='manifest');
  }
  assert.throws(()=>admin.contentPayload('wrong project',JSON.stringify(manifest)),error=>error.safeCode==='source');
});

function deploymentHarness(initial={}){
  let local=clone(initial), remote={files:[]}, version=0, deployment;
  const calls=[], states=[];
  const state={failDeployment:false};
  const saveState=value=>{local=clone(value);states.push(clone(value));};
  const api=async(endpoint,method='GET',body)=>{
    calls.push({endpoint,method,body:body&&clone(body)});
    if(endpoint==='/projects'&&method==='POST')return project;
    if(endpoint==='/projects/'+scriptId)return project;
    if(endpoint==='/projects/'+scriptId+'/content'){
      if(method==='PUT')remote=clone(body);
      return remote;
    }
    if(endpoint==='/projects/'+scriptId+'/versions'&&method==='POST')return{versionNumber:++version};
    if(endpoint.startsWith('/projects/'+scriptId+'/deployments')){
      if(method==='GET')return clone(deployment);
      if(state.failDeployment){state.failDeployment=false;throw new Error('offline deployment failure');}
      deployment={deploymentId,deploymentConfig:method==='PUT'?body.deploymentConfig:body,entryPoints:[{entryPointType:'WEB_APP',webApp:{url:webAppUrl}}]};
      return clone(deployment);
    }
    throw new Error('Unexpected mock API endpoint');
  };
  return{api,files:files(),loadState:()=>local,saveState,calls,states,state,get local(){return local;},get version(){return version;}};
}

test('deployment creates only its own project and reuses unchanged version and deployment',async()=>{
  const harness=deploymentHarness();
  const first=await admin.deploy(harness);
  assert.equal(first.changed,true);assert.equal(first.webAppUrl,webAppUrl);
  assert.equal(harness.calls.filter(call=>call.endpoint==='/projects'&&call.method==='POST').length,1);
  assert.equal(harness.states[0].scriptId,scriptId);
  const second=await admin.deploy(harness);
  assert.equal(second.changed,false);assert.equal(second.versionNumber,1);
  assert.equal(harness.version,1);
  assert.equal(harness.calls.filter(call=>call.endpoint.endsWith('/deployments')&&call.method==='POST').length,1);
  harness.files={files:files().files.map(file=>file.name==='Code'?{...file,source:file.source+'\n// updated\n'}:file)};
  const third=await admin.deploy(harness);
  assert.equal(third.versionNumber,2);assert.equal(third.deploymentId,deploymentId);
  assert.equal(harness.calls.at(-1).method,'PUT');
  assert.equal(harness.calls.at(-1).body.deploymentConfig.versionNumber,2);
});

test('failed deployment preserves project and immutable version for safe resume',async()=>{
  const harness=deploymentHarness();harness.state.failDeployment=true;
  await assert.rejects(admin.deploy(harness));
  assert.equal(harness.local.scriptId,scriptId);assert.equal(harness.local.pendingVersion,1);
  const result=await admin.deploy(harness);
  assert.equal(result.versionNumber,1);assert.equal(harness.version,1);
  assert.equal(harness.calls.filter(call=>call.endpoint==='/projects'&&call.method==='POST').length,1);
});

test('persisted project must have expected owner/title before content is overwritten',async()=>{
  const calls=[];
  await assert.rejects(admin.deploy({files:files(),loadState:()=>({scriptId}),saveState(){throw new Error('should not write');},api:async(...args)=>{calls.push(args);return {...project,title:'Unrelated project'};}}),error=>error.safeCode==='wrong-project');
  assert.equal(calls.length,1);assert.equal(calls[0][0],'/projects/'+scriptId);
  assert.throws(()=>admin.assertProject({...project,creator:{email:'other@example.com'}}),error=>error.safeCode==='wrong-project');
});

test('management API uses Google only, disallows redirects and sanitizes upstream credential errors',async()=>{
  const calls=[],client={getAccessToken:async()=>({token:'OFFLINE_ACCESS_SECRET'})};
  const value=await admin.apiRequest(client,'/projects/'+scriptId,'GET',undefined,async(url,options)=>{calls.push({url,options});return new Response(JSON.stringify(project),{headers:{'content-type':'application/json'}});});
  assert.equal(value.title,admin.TITLE);assert.equal(calls[0].url,'https://script.googleapis.com/v1/projects/'+scriptId);
  assert.equal(calls[0].options.redirect,'error');assert.equal(calls[0].options.headers.Authorization,'Bearer OFFLINE_ACCESS_SECRET');
  await assert.rejects(admin.apiRequest(client,'https://attacker.test'),error=>error.safeCode==='api-path');
  const error=admin.safeApiError(403,{error:{message:'User has not enabled the Apps Script API. OFFLINE_ACCESS_SECRET',details:[{reason:'GOOGLE_SERVICE_USER_DISABLED'}]}});
  assert.equal(error.safeCode,'apps-script-api-disabled');assert.equal(error.message.includes('OFFLINE_ACCESS_SECRET'),false);
});
