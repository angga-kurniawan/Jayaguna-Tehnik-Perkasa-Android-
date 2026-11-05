import {test} from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync} from 'node:crypto';
import appConfig from '../lib/server-config.js';
import driveConfig from '../lib/drive/config.js';

test('server keeps ADC when no JSON secret is supplied',()=>{
  assert.deepEqual(appConfig.serverAppOptions({FIREBASE_PROJECT_ID:'demo-render-config'}),{projectId:'demo-render-config'});
});
test('server accepts a matching service-account secret without exposing it',()=>{
  // Ephemeral parser fixture only: never used to request a real Google token.
  const {privateKey}=generateKeyPairSync('rsa',{modulusLength:2048,privateKeyEncoding:{type:'pkcs8',format:'pem'},publicKeyEncoding:{type:'spki',format:'pem'}});
  const value={type:'service_account',project_id:'demo-render-config',client_email:'test-only@demo-render-config.iam.gserviceaccount.com',private_key:privateKey};
  const options=appConfig.serverAppOptions({FIREBASE_PROJECT_ID:value.project_id,FIREBASE_SERVICE_ACCOUNT_JSON:JSON.stringify(value)});
  assert.equal(options.projectId,value.project_id);
  assert.ok(options.credential);
});
test('wrong-project or malformed server secrets fail without echoing input',()=>{
  for(const input of ['private-key-secret-not-json',JSON.stringify({type:'service_account',project_id:'another-project',private_key:'private-key-secret'})]){
    assert.throws(()=>appConfig.serverAppOptions({FIREBASE_PROJECT_ID:'demo-render-config',FIREBASE_SERVICE_ACCOUNT_JSON:input}),error=>!error.message.includes('private-key-secret') && error.message.includes('project'));
  }
});
test('Render injected HTTPS origin supplies backend URL; explicit origin has priority',()=>{
  const saved={BACKEND_PUBLIC_URL:process.env.BACKEND_PUBLIC_URL,RENDER_EXTERNAL_URL:process.env.RENDER_EXTERNAL_URL,NODE_ENV:process.env.NODE_ENV};
  try{
    delete process.env.BACKEND_PUBLIC_URL;process.env.RENDER_EXTERNAL_URL='https://jtp-test.onrender.com';process.env.NODE_ENV='production';
    assert.equal(driveConfig.backendUrl(),'https://jtp-test.onrender.com');
    process.env.BACKEND_PUBLIC_URL='https://api.example.test';
    assert.equal(driveConfig.backendUrl(),'https://api.example.test');
    process.env.BACKEND_PUBLIC_URL='http://localhost:8787';
    assert.throws(()=>driveConfig.backendUrl(),error=>error.code==='failed-precondition');
  }finally{for(const [key,value] of Object.entries(saved)){if(value===undefined)delete process.env[key];else process.env[key]=value;}}
});
