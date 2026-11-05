// Run only on a trusted workstation/CI with Application Default Credentials.
// No service-account key is generated or written by this script.
import {initializeApp, applicationDefault} from 'firebase-admin/app';
import {getAuth} from 'firebase-admin/auth';
import {getFirestore, FieldValue} from 'firebase-admin/firestore';
import {createInterface} from 'node:readline/promises';

const [projectId, email, ...nameParts] = process.argv.slice(2);
const name = nameParts.join(' ');
if (!projectId || !email || !name) throw new Error('Usage: npm run bootstrap -- PROJECT_ID owner@example.com "Nama Owner"');
if (process.env.FIRESTORE_EMULATOR_HOST || process.env.FIREBASE_AUTH_EMULATOR_HOST) throw new Error('Bootstrap produksi menolak variabel emulator. Gunakan seed emulator terpisah.');
const prompt = createInterface({input: process.stdin, output: process.stdout});
const confirmation = await prompt.question(`Membuat OWNER pertama ${email} pada ${projectId}. Ketik project ID untuk melanjutkan: `);
prompt.close();
if (confirmation !== projectId) throw new Error('Konfirmasi tidak cocok. Tidak ada perubahan.');
initializeApp({credential: applicationDefault(), projectId});
const auth = getAuth(), db = getFirestore();
// A transaction lock prevents two simultaneous bootstrap runs creating two first owners.
const lockRef = db.doc('system/bootstrapOwner');
await db.runTransaction(async tx => {
  const [lock, owners] = await Promise.all([tx.get(lockRef), tx.get(db.collection('users').where('role','==','OWNER').limit(1))]);
  if (lock.exists || !owners.empty) throw new Error('Bootstrap sudah dikunci atau OWNER sudah ada. Gunakan pemulihan terkontrol oleh operator.');
  tx.create(lockRef, {email, lockedAt: FieldValue.serverTimestamp(), completed: false});
});
// Fail closed: the lock remains on error. Investigate IAM/Auth/profile before manual recovery.
let user;
try { user = await auth.getUserByEmail(email); }
catch (error) { if (error.code !== 'auth/user-not-found') throw error; user = await auth.createUser({email, displayName: name, disabled: true}); }
await auth.updateUser(user.uid, {disabled: true, displayName: name});
await auth.setCustomUserClaims(user.uid, {...user.customClaims, role: 'OWNER'});
const batch = db.batch();
batch.create(db.doc(`users/${user.uid}`), {uid:user.uid, email, name, role:'OWNER', active:true, version:1, createdAt:FieldValue.serverTimestamp(), updatedAt:FieldValue.serverTimestamp()});
batch.update(lockRef, {completed:true, uid:user.uid, completedAt:FieldValue.serverTimestamp()});
await batch.commit();
await auth.updateUser(user.uid, {disabled:false});
// Do not print a reset link; owner requests one through the application's forgot-password flow.
console.log(`OWNER ${user.uid} dibuat. Gunakan Lupa Password pada aplikasi untuk mengatur password melalui email.`);
