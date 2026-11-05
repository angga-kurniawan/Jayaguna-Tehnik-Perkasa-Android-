import {test} from 'node:test';
import assert from 'node:assert/strict';
import module from '../lib/approved-access.js';
const {googleIdentity, approvedActor} = module;
const token = {uid:'google-user', email:'Fixture@Example.test', email_verified:true,
  name:'Fixture', auth_time:100, firebase:{sign_in_provider:'google.com'}};
const identity = googleIdentity(token);
const grant = {status:'approved'};

test('central access requires a verified Google identity, independent of client email or role', () => {
  assert.equal(identity.email, 'fixture@example.test');
  for (const bad of [{...token, email_verified:false}, {...token, email:undefined}, {...token, auth_time:NaN}, {...token, auth_time:0}, {...token, auth_time:1.5},
    {...token, firebase:{sign_in_provider:'password'}}, {...token, email:'bad/path@example.test'}]) {
    assert.throws(() => googleIdentity(bad), error => error.code === 'permission-denied');
  }
  assert.throws(() => approvedActor(identity, undefined, undefined), error => error.code === 'permission-denied');
  assert.throws(() => approvedActor({...identity, claims:{role:'OWNER'}}, undefined, undefined));
});

test('UID decisions veto grants; explicit approval defaults to ADMIN and cannot self-elevate', () => {
  assert.equal(approvedActor(identity, {status:'approved'}, undefined).role, 'ADMIN');
  assert.equal(approvedActor(identity, undefined, grant).role, 'ADMIN');
  for (const profile of [{status:'pending'}, {status:'rejected'}, {active:false}, {status:'approved', active:false}, {active:null}, {active:'true'},
    {status:'approved', role:'SUPERADMIN'}, {status:'approved', uid:'someone-else'}, {status:'approved', uid:null}, {status:'approved', email:null},
    {status:'approved', email:'someone@example.test'}]) {
    assert.throws(() => approvedActor(identity, profile, {...grant, role:'OWNER'}), error => error.code === 'permission-denied');
  }
  assert.throws(() => approvedActor(identity, undefined, {...grant, active:false}));
  assert.throws(() => approvedActor(identity, undefined, {...grant, active:null}));
  assert.throws(() => approvedActor(identity, {revokedBeforeSeconds:100}, grant));
  assert.throws(() => approvedActor(identity, {revokedBeforeSeconds:'100'}, grant));
  assert.throws(() => approvedActor(identity, {revokedBeforeSeconds:null}, grant));
});

test('trusted legacy UID role takes priority over email grants and honors profile revocation', () => {
  const owner = {...identity, claims:{role:'OWNER'}};
  assert.equal(approvedActor(owner, {active:true, role:'OWNER'}, grant).role, 'OWNER');
  assert.equal(approvedActor({...identity, claims:{role:'ADMIN'}}, {active:true, role:'ADMIN'}, grant).role, 'ADMIN');
  assert.throws(() => approvedActor(owner, {active:true, role:'OWNER', revokedBeforeSeconds:100}, grant));
  assert.throws(() => approvedActor(owner, {active:true, role:'OWNER', status:'pending'}, grant));
});
