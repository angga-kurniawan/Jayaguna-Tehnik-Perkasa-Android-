import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Timestamp} from 'firebase-admin/firestore';
import module from '../lib/access-request.js';
import {MemoryFirestore} from './helpers/memory-firestore.mjs';
const {handleAccessRequest, ACCESS_REQUEST_RECIPIENT} = module;
const identity = {uid:'fixture-google-user', email:'fixture@example.test', name:'Fixture', authTime:100, claims:{}};
const request = {uid:identity.uid, name:'Fixture <img src=x>', email:identity.email, status:'pending', requestedAt:Timestamp.fromMillis(1000)};
const environment = {RESEND_API_KEY:'unit-test-provider-secret', ACCESS_REQUEST_FROM:'access@example.test'};
const seed = extra => new MemoryFirestore({['accessRequests/'+identity.uid]:request, ...extra});

test('mail notification requires an existing immutable request matching verified Google identity', async () => {
  for (const data of [undefined, {...request, uid:'other'}, {...request, email:'other@example.test'}, {...request, status:'approved'}, {...request, name:'Header\r\nInjection'}]) {
    const db = new MemoryFirestore(data ? {['accessRequests/'+identity.uid]:data} : {});
    await assert.rejects(handleAccessRequest(identity, db, environment, async () => {throw new Error('must-not-send');}), error => error.code === 'failed-precondition');
    assert.deepEqual(db.writes, []);
  }
});

test('fixed recipient, escaped content and stable provider key notify once without changing approval', async () => {
  const db = seed({['users/'+identity.uid]:{status:'pending'}});
  const calls = [];
  const send = async (url, options) => {calls.push({url, options}); return Response.json({id:'provider-message'});};
  const first = await handleAccessRequest(identity, db, environment, send);
  const second = await handleAccessRequest(identity, db, environment, send);
  assert.deepEqual(first, {emailSent:true,emailQueued:false,retryable:false});
  assert.deepEqual(second, first);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'https://api.resend.com/emails');
  const body = JSON.parse(calls[0].options.body);
  assert.deepEqual(body.to, [ACCESS_REQUEST_RECIPIENT]);
  assert.ok(body.html.includes('&lt;img src=x&gt;') && !body.html.includes('<img'));
  assert.ok(body.text.includes('WIB') && body.html.includes('1970'));
  assert.match(body.text, /07[.:]00[.:]01/);
  assert.ok(body.text.includes('users/'+identity.uid) && body.text.includes('approved'));
  assert.match(calls[0].options.headers['Idempotency-Key'], /^jtp-access-[a-f0-9]{64}$/);
  assert.ok(db.writes.every(path => path === 'accessRequestMail/'+identity.uid));
  assert.deepEqual(db.data.get('users/'+identity.uid), {status:'pending'});
  assert.equal(JSON.stringify(db.data.get('accessRequestMail/'+identity.uid)).includes('unit-test-provider-secret'), false);
});

test('missing config and provider failure persist notSent and retry with the same idempotency key', async () => {
  const db = seed();
  assert.equal((await handleAccessRequest(identity, db, {}, async () => {throw new Error('must-not-send');})).emailSent, false);
  assert.equal(db.data.get('accessRequestMail/'+identity.uid).status, 'notSent');
  const keys = [];
  const failed = await handleAccessRequest(identity, db, environment, async (_url, options) => {keys.push(options.headers['Idempotency-Key']);return new Response('private-provider-error', {status:500});});
  assert.deepEqual(failed, {emailSent:false,emailQueued:false,retryable:true});
  assert.equal(db.data.get('accessRequestMail/'+identity.uid).status, 'notSent');
  const retried = await handleAccessRequest(identity, db, environment, async (_url, options) => {keys.push(options.headers['Idempotency-Key']);return Response.json({});});
  assert.equal(retried.emailSent, true);
  assert.equal(keys[0], keys[1]);
});

test('concurrent notifications share a lease and never send two provider requests', async () => {
  const db = seed();let release, entered;
  const ready = new Promise(resolve => {entered=resolve;});
  const hold = new Promise(resolve => {release=resolve;});
  let calls=0;
  const first = handleAccessRequest(identity, db, environment, async () => {calls++;entered();await hold;return Response.json({});});
  await ready;
  const concurrent = await handleAccessRequest(identity, db, environment, async () => {calls++;return Response.json({});});
  assert.equal(concurrent.emailSent, false);
  assert.equal(concurrent.retryable, true);
  release();await first;
  assert.equal(calls, 1);
});

test('notification accepts the same 160-character name bound as access request security rules', async () => {
  const db = new MemoryFirestore({['accessRequests/'+identity.uid]:{...request,name:'A'.repeat(160)}});
  assert.equal((await handleAccessRequest(identity,db,environment,async()=>Response.json({}))).emailSent,true);
});
