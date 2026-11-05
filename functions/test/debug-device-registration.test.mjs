import {test} from 'node:test';
import assert from 'node:assert/strict';
import registration from '../scripts/debug-device-registration.cjs';
const {DebugDeviceError,displayNameFor,adbFailure,exchangeToken,registerIdentity}=registration;
const fixtureToken='00000000-0000-4000-8000-000000000001';
const missing=()=>new DebugDeviceError('unregistered','fixture-unregistered');

test('a second installation registers without replacing the old shared-name device',async()=>{
  let exchanges=0,created;
  const result=await registerIdentity({token:fixtureToken,apply:true,verify:true,
    exchange:async()=>{if(++exchanges===1)throw missing();return {ttl:'3600s'};},
    list:async()=>[{name:'old-device',displayName:'Jayaguna Android development device'}],
    create:async value=>{created=value;return {name:'new-device'};},
  });
  assert.equal(result.registered,true);assert.equal(result.changed,true);assert.equal(result.verified,true);
  assert.equal(created.token,fixtureToken);assert.equal(created.displayName,displayNameFor(fixtureToken));
  assert.equal(created.displayName.includes(fixtureToken),false);assert.equal(result.name,'new-device');
});

test('already-valid masked registrations never create duplicates',async()=>{
  const result=await registerIdentity({token:fixtureToken,apply:true,
    exchange:async()=>({ttl:'3600s'}),list:async()=>{throw new Error('must not list');},create:async()=>{throw new Error('must not create');},
  });
  assert.equal(result.changed,false);assert.equal(result.verified,true);
});

test('network, quota and API-key rejection never cause device-registration writes',async()=>{
  for(const kind of ['network','quota','api-key','configuration']){
    let writes=0;
    await assert.rejects(registerIdentity({token:fixtureToken,apply:true,
      exchange:async()=>{throw new DebugDeviceError(kind,'safe-fixture-message');},list:async()=>[],create:async()=>{writes++;},
    }),error=>error.kind===kind);
    assert.equal(writes,0);
  }
});

test('an existing hashed identity waits for verification instead of registering again',async()=>{
  let exchanges=0,writes=0;
  const result=await registerIdentity({token:fixtureToken,apply:true,
    exchange:async()=>{if(++exchanges<3)throw missing();return {ttl:'3600s'};},
    list:async()=>[{name:'same-device',displayName:displayNameFor(fixtureToken)}],create:async()=>{writes++;},pause:async()=>{},
  });
  assert.equal(writes,0);assert.equal(result.changed,false);assert.equal(result.name,'same-device');
});

test('concurrent creation conflict is resolved by proving the same identity works',async()=>{
  let exchanges=0,writes=0;
  const result=await registerIdentity({token:fixtureToken,apply:true,
    exchange:async()=>{if(++exchanges===1)throw missing();return {ttl:'3600s'};},list:async()=>[],
    create:async()=>{writes++;throw {status:409};},
  });
  assert.equal(writes,1);assert.equal(result.changed,false);assert.equal(result.verified,true);
});

test('exchange classifies only known unregistered attestation and sanitizes upstream errors',async()=>{
  const input={token:fixtureToken,apiKey:'test-only-api-key',projectNumber:'test-project',appId:'test-app'};
  await assert.rejects(exchangeToken({...input,request:async()=>Response.json({error:{status:'PERMISSION_DENIED',message:'App attestation failed.'}},{status:403})}),error=>error.kind==='unregistered');
  await assert.rejects(exchangeToken({...input,request:async()=>Response.json({error:{status:'PERMISSION_DENIED',message:'test-only-secret',details:[{reason:'API_KEY_SERVICE_BLOCKED'}]}},{status:403})}),error=>error.kind==='api-key' && !error.message.includes('test-only-secret'));
  await assert.rejects(exchangeToken({...input,request:async()=>Response.json({error:{status:'PERMISSION_DENIED',message:'test-only-secret'}},{status:403})}),error=>error.kind==='configuration' && !error.message.includes('test-only-secret'));
  await assert.rejects(exchangeToken({...input,request:async()=>{throw new Error('test-only-secret');}}),error=>error.kind==='network' && !error.message.includes('test-only-secret'));
  assert.deepEqual(await exchangeToken({...input,request:async()=>Response.json({token:'test-only-attestation-result',ttl:'3600s'})}),{ttl:'3600s'});
});

test('verification and inspection cannot register a missing token without --apply',async()=>{
  let writes=0;
  const options={token:fixtureToken,exchange:async()=>{throw missing();},list:async()=>[],create:async()=>{writes++;}};
  assert.equal((await registerIdentity(options)).registered,false);
  await assert.rejects(registerIdentity({...options,verify:true}),error=>error.kind==='unregistered');
  assert.equal(writes,0);
});

test('adb failures explain phone authorization and release APK without raw output',()=>{
  for(const [stderr,kind] of [['device unauthorized test-only-secret','device-unauthorized'],['run-as: package not debuggable: test-only-secret','release-apk'],["device 'test-only-secret' not found",'device-offline']]){
    const error=adbFailure({stderr});assert.equal(error.kind,kind);assert.equal(error.message.includes('test-only-secret'),false);
  }
});
