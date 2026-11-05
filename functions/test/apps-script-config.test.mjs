import {test} from 'node:test';
import assert from 'node:assert/strict';
import config from '../scripts/configure-apps-script.cjs';
const endpoint = 'https://script.google.com/macros/s/AKfycb_jayaguna_example_deployment/exec';
const health = {service:'jayaguna-google-bridge',projectId:'jayagunatehnikperkasa',version:1};
const response = value => new Response(JSON.stringify(value), {headers:{'content-type':'application/json; charset=utf-8'}});
test('Apps Script configuration accepts only deployed Google HTTPS endpoints', () => {
  assert.equal(config.checkedUrl(endpoint), endpoint);
  for (const value of ['http://script.google.com/macros/s/123/exec',endpoint+'?idToken=secret',endpoint+'#secret',endpoint.replace('/exec','/dev'),endpoint.replace('script.google.com','script.google.com.attacker.test'),endpoint.replace('https://','https://user:secret@')]) {
    assert.throws(() => config.checkedUrl(value));
  }
});
test('health follows Google content redirect without credentials', async () => {
  const calls = [];
  await config.checkHealth(endpoint, async (url, options) => {
    calls.push({url,options});
    return calls.length === 1 ? new Response('', {status:302,headers:{location:'https://script.googleusercontent.com/macros/echo?user_content_key=example'}}) : response(health);
  });
  assert.equal(calls.length, 2);
  for (const call of calls) { assert.equal(call.options.method,'GET'); assert.equal(call.options.headers,undefined); assert.equal(call.options.body,undefined); }
});
test('health rejects account login pages and foreign project deployments', async () => {
  await assert.rejects(config.checkHealth(endpoint, async () => new Response('', {status:302,headers:{location:'https://accounts.google.com/login'}})));
  await assert.rejects(config.checkHealth(endpoint, async () => response({...health,projectId:'different-project'})));
});
test('health rejects redirect loops and HTML responses', async () => {
  await assert.rejects(config.checkHealth(endpoint, async () => new Response('', {status:302,headers:{location:'https://script.googleusercontent.com/macros/echo?key=loop'}})));
  await assert.rejects(config.checkHealth(endpoint, async () => new Response('<html>Login</html>',{headers:{'content-type':'text/html'}})));
});
