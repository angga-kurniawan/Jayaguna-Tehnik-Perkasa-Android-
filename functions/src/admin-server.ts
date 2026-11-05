import {IncomingMessage, Server, ServerResponse, createServer} from 'node:http';
import {randomUUID} from 'node:crypto';
import {accessSync, constants} from 'node:fs';
import {getApps, initializeApp} from 'firebase-admin/app';
import {DecodedIdToken, getAuth} from 'firebase-admin/auth';
import {CallableRequest, HttpsError} from 'firebase-functions/v2/https';
import {RateLimit, requestBytes} from './server';
import {serverAppOptions} from './server-config';

type AdminRequest = Pick<CallableRequest, 'auth' | 'data'>;
export type AdminServerDependencies = {
  verifyIdToken: (token: string, checkRevoked: boolean) => Promise<DecodedIdToken>;
  manageAdmin: (request: AdminRequest) => Promise<unknown>;
};
const MAX_REQUEST_BYTES = 16 * 1024;
const statuses: Record<string, number> = {
  'invalid-argument': 400, unauthenticated: 401, 'permission-denied': 403,
  'not-found': 404, 'already-exists': 409, aborted: 409, 'failed-precondition': 412,
  'resource-exhausted': 429, unavailable: 503, 'deadline-exceeded': 504,
};

function json(response: ServerResponse, status: number, body: unknown) {
  response.writeHead(status, {'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store'});
  response.end(JSON.stringify(body));
}

/** Android-only adapter: no media, OAuth, catalog, or anonymous account creation routes. */
export function adminServer(deps: AdminServerDependencies): Server {
  const limiter = new RateLimit();
  const server = createServer(async (request: IncomingMessage, response: ServerResponse) => {
    const requestId = randomUUID();
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Referrer-Policy', 'no-referrer');
    response.setHeader('X-Request-Id', requestId);
    try {
      const path = new URL(request.url || '/', 'http://localhost').pathname;
      const health = request.method === 'GET' && path === '/healthz';
      if (!health && !(request.method === 'POST' && path === '/api/call/manageAdmin')) {
        throw new HttpsError('not-found', 'Endpoint tidak ditemukan.');
      }
      // This profile is for the Android app; it does not expose browser CORS access.
      if (request.headers.origin) throw new HttpsError('permission-denied', 'Origin tidak diizinkan.');
      if (!limiter.allow('ip:' + (request.socket.remoteAddress || 'unknown'), 60)) {
        response.setHeader('Retry-After', '60');
        throw new HttpsError('resource-exhausted', 'Terlalu banyak permintaan. Coba lagi sebentar.');
      }
      if (health) { json(response, 200, {status: 'ok', service: 'jayaguna-admin-api'}); return; }
      const bearer = /^Bearer ([^\s]+)$/.exec(request.headers.authorization || '');
      if (!bearer) throw new HttpsError('unauthenticated', 'Silakan masuk terlebih dahulu.');
      let decoded: DecodedIdToken;
      try { decoded = await deps.verifyIdToken(bearer[1]!, true); }
      catch { throw new HttpsError('unauthenticated', 'Token login tidak valid atau sudah dicabut.'); }
      if (!decoded.uid) throw new HttpsError('unauthenticated', 'Identitas login tidak tersedia.');
      if (decoded.role !== 'OWNER') throw new HttpsError('permission-denied', 'Hanya OWNER dapat mengelola admin.');
      if (!limiter.allow('owner:' + decoded.uid, 30)) {
        response.setHeader('Retry-After', '60');
        throw new HttpsError('resource-exhausted', 'Terlalu banyak permintaan. Coba lagi sebentar.');
      }
      if (request.headers['content-type']?.split(';')[0]?.trim().toLowerCase() !== 'application/json') {
        throw new HttpsError('invalid-argument', 'Gunakan Content-Type application/json.');
      }
      const bytes = await requestBytes(request, MAX_REQUEST_BYTES);
      let data: unknown;
      try { data = JSON.parse(bytes.toString('utf8')); }
      catch { throw new HttpsError('invalid-argument', 'JSON tidak valid.'); }
      if (!data || typeof data !== 'object' || Array.isArray(data)) {
        throw new HttpsError('invalid-argument', 'Payload harus berupa objek JSON.');
      }
      // The existing service re-checks OWNER/profile/revocation and validates the operation schema.
      json(response, 200, {result: await deps.manageAdmin({auth: {uid: decoded.uid, token: decoded}, data})});
    } catch (error) {
      const known = error instanceof HttpsError;
      const code = known ? error.code : 'internal';
      if (!known) console.error(JSON.stringify({event: 'admin_backend_error', code, requestId}));
      if (!response.headersSent) json(response, statuses[code] || 500, {error: {
        code, requestId, message: known ? error.message : 'Operasi belum dapat diselesaikan. Coba lagi dengan requestId yang sama.',
      }});
      else response.end();
    }
  });
  server.requestTimeout = 30_000;
  server.headersTimeout = 15_000;
  server.keepAliveTimeout = 5_000;
  return server;
}

/** A free external host needs an explicit server credential; attached ADC is an explicit opt-in. */
export function validateAdminEnvironment(environment: NodeJS.ProcessEnv) {
  if (environment.ENFORCE_APP_CHECK === 'true') {
    throw new Error('Profil admin Android belum mendukung App Check. Gunakan konfigurasi khusus profil admin; jangan memakai environment bridge lama.');
  }
  if (environment.GOOGLE_APPLICATION_CREDENTIALS) {
    try { accessSync(environment.GOOGLE_APPLICATION_CREDENTIALS, constants.R_OK); }
    catch { throw new Error('File kredensial server tidak tersedia pada host ini.'); }
  }
  if (environment.NODE_ENV === 'production' && (environment.FIREBASE_AUTH_EMULATOR_HOST || environment.FIRESTORE_EMULATOR_HOST)) {
    throw new Error('Profil produksi tidak boleh memakai endpoint emulator Firebase.');
  }
  const explicit = Boolean(environment.FIREBASE_SERVICE_ACCOUNT_JSON?.trim() || environment.GOOGLE_APPLICATION_CREDENTIALS);
  if (environment.NODE_ENV === 'production' && !explicit && environment.JTP_ADMIN_ATTACHED_ADC !== 'true') {
    throw new Error('Kredensial Firebase server diperlukan: isi secret FIREBASE_SERVICE_ACCOUNT_JSON atau gunakan ADC pada runtime tepercaya.');
  }
}

export async function productionAdminServer(environment: NodeJS.ProcessEnv = process.env): Promise<Server> {
  validateAdminEnvironment(environment);
  if (!getApps().length) initializeApp(serverAppOptions(environment));
  const service = await import('./service');
  const auth = getAuth();
  return adminServer({
    verifyIdToken: (token, checkRevoked) => auth.verifyIdToken(token, checkRevoked),
    manageAdmin: service.manageAdmin,
  });
}

if (require.main === module) {
  productionAdminServer().then(server => {
    const port = Number(process.env.PORT || 8787);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('invalid-port');
    server.on('error', () => { console.error('Backend admin gagal membuka port.'); process.exitCode = 1; });
    server.listen(port, process.env.HOST || '0.0.0.0', () => console.log(JSON.stringify({event: 'admin_api_started', port})));
  }).catch(() => {
    console.error('Backend admin belum dapat dimulai. Periksa konfigurasi khusus admin dan kredensial server.');
    process.exitCode = 1;
  });
}
