import {test} from 'node:test';
import assert from 'node:assert/strict';
import {configureDemoEnvironment} from '../scripts/emulator-environment.mjs';

test('seed menetapkan semua emulator loopback dan project demo sebelum SDK dimuat', () => {
  const env = {};
  const result = configureDemoEnvironment(env);
  assert.equal(result.projectId, 'demo-jayaguna');
  assert.equal(env.FIREBASE_AUTH_EMULATOR_HOST, '127.0.0.1:9099');
  assert.equal(env.FIRESTORE_EMULATOR_HOST, '127.0.0.1:8080');
  assert.equal(env.FIREBASE_STORAGE_EMULATOR_HOST, '127.0.0.1:9199');
});
test('seed menolak project dan bucket produksi', () => {
  for (const key of ['GCLOUD_PROJECT', 'GOOGLE_CLOUD_PROJECT']) assert.throws(() => configureDemoEnvironment({[key]: 'jayagunatehnikperkasa'}), /produksi/);
  assert.throws(() => configureDemoEnvironment({FIREBASE_CONFIG: JSON.stringify({projectId: 'jayagunatehnikperkasa'})}), /project\/bucket/);
  assert.throws(() => configureDemoEnvironment({FIREBASE_CONFIG: JSON.stringify({projectId: 'demo-jayaguna', storageBucket: 'production.firebasestorage.app'})}), /project\/bucket/);
});
test('seed menolak endpoint jarak jauh, kredensial URL, path dan port invalid', () => {
  for (const key of ['FIREBASE_AUTH_EMULATOR_HOST', 'FIRESTORE_EMULATOR_HOST', 'FIREBASE_STORAGE_EMULATOR_HOST']) {
    for (const host of ['firebase.googleapis.com:443', '127.0.0.1:0', '127.0.0.1:99999', 'http://127.0.0.1:8080', 'user@127.0.0.1:8080', '127.0.0.1:8080/path']) assert.throws(() => configureDemoEnvironment({[key]: host}), /loopback/);
  }
});
test('seed menerima localhost dan IPv6 loopback yang valid', () => {
  const result = configureDemoEnvironment({FIRESTORE_EMULATOR_HOST: '[::1]:8080', FIREBASE_AUTH_EMULATOR_HOST: 'localhost:9099'});
  assert.equal(result.endpoints.FIRESTORE_EMULATOR_HOST, '[::1]:8080');
});
