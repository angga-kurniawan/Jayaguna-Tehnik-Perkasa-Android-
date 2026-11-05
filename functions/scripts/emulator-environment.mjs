// Pure guard shared by seed tooling and its tests. No Firebase imports or writes.
export const DEMO_PROJECT_ID = 'demo-jayaguna';
export const DEMO_STORAGE_BUCKET = `${DEMO_PROJECT_ID}.appspot.com`;
const endpoints = {
  FIREBASE_AUTH_EMULATOR_HOST: '127.0.0.1:9099',
  FIRESTORE_EMULATOR_HOST: '127.0.0.1:8080',
  FIREBASE_STORAGE_EMULATOR_HOST: '127.0.0.1:9199',
};

export function configureDemoEnvironment(env = process.env) {
  for (const key of ['GCLOUD_PROJECT', 'GOOGLE_CLOUD_PROJECT']) {
    if (env[key] && env[key] !== DEMO_PROJECT_ID) throw new Error(`${key} harus ${DEMO_PROJECT_ID}; seed menolak project produksi.`);
  }
  if (env.FIREBASE_CONFIG) {
    let config;
    try { config = JSON.parse(env.FIREBASE_CONFIG); }
    catch { throw new Error('FIREBASE_CONFIG harus berupa JSON konfigurasi emulator yang valid.'); }
    if (config.projectId !== DEMO_PROJECT_ID || (config.storageBucket && config.storageBucket !== DEMO_STORAGE_BUCKET)) {
      throw new Error('FIREBASE_CONFIG tidak menunjuk project/bucket emulator demo-jayaguna.');
    }
  }
  for (const [key, fallback] of Object.entries(endpoints)) {
    const host = env[key] || fallback;
    const match = /^(127\.0\.0\.1|localhost|\[::1\]):([0-9]{1,5})$/.exec(host);
    if (!match || Number(match[2]) < 1 || Number(match[2]) > 65535) throw new Error(`${key} wajib host loopback dan port lokal; endpoint jarak jauh ditolak.`);
    env[key] = host;
  }
  // Explicit demo options and emulator endpoints are used by the caller.
  // The SDK's emulator mode does not need production credentials.
  env.GCLOUD_PROJECT = DEMO_PROJECT_ID;
  env.GOOGLE_CLOUD_PROJECT = DEMO_PROJECT_ID;
  env.FIREBASE_CONFIG = JSON.stringify({projectId: DEMO_PROJECT_ID, storageBucket: DEMO_STORAGE_BUCKET});
  return {projectId: DEMO_PROJECT_ID, storageBucket: DEMO_STORAGE_BUCKET, endpoints: Object.fromEntries(Object.keys(endpoints).map(key => [key, env[key]]))};
}
