import { randomBytes, createHash } from "node:crypto";
import { initializeApp, getApps } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldValue, Timestamp, Transaction } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { CallableRequest, HttpsError } from "firebase-functions/v2/https";
import sharp from "sharp";
import { z } from "zod";
import { archiveSchema, adminSchema, changed, contextSchema, Device, EntityType, fingerprint, Image, MAX_IMAGE_BYTES, partnerSchema, productSchema, publicProjection, saveEnvelope, searchTokens, uploadSchema } from "./schema";
import {DriveMedia} from './drive/media';

if (!getApps().length) initializeApp();
const db = getFirestore();
const auth = getAuth();
const bucket = () => getStorage().bucket();
type Actor = {uid: string; name: string; email: string; role: "OWNER" | "ADMIN"; authTime: number};
type Request = Pick<CallableRequest, "auth" | "data">;
const fail = (code: ConstructorParameters<typeof HttpsError>[0], message: string): never => {throw new HttpsError(code, message);};
export function parse<T extends z.ZodTypeAny>(schema: T, value: unknown): z.infer<T> {
  const parsed = schema.safeParse(value);
  if (!parsed.success) return fail("invalid-argument", parsed.error.issues.map(i => `${i.path.join(".")}: ${i.message}`).join("; ").slice(0, 800));
  return parsed.data;
}
export async function requireActor(request: Request, owner = false): Promise<Actor> {
  if (!request.auth) return fail("unauthenticated", "Silakan masuk kembali.");
  const role = request.auth.token.role;
  if (role !== "OWNER" && role !== "ADMIN") return fail("permission-denied", "Akun tidak memiliki peran yang diizinkan.");
  const [profile, user] = await Promise.all([db.doc(`users/${request.auth.uid}`).get(), auth.getUser(request.auth.uid)]);
  const p = profile.data();
  const validAfter = new Date(user.tokensValidAfterTime ?? 0).getTime() / 1000;
  if (!p?.active || p.role !== role || user.disabled || Number(request.auth.token.auth_time) < validAfter || (p.revokedBeforeSeconds && Number(request.auth.token.auth_time) <= p.revokedBeforeSeconds)) return fail("permission-denied", "Akses akun tidak aktif. Masuk kembali atau hubungi owner.");
  if (owner && role !== "OWNER") return fail("permission-denied", "Hanya owner yang dapat mengelola admin.");
  return {uid: user.uid, email: user.email ?? "", name: p.name || user.displayName || user.email || user.uid, role, authTime: Number(request.auth.token.auth_time)};
}
async function checkActorTransaction(tx: Transaction, actor: Actor) {
  const profile = (await tx.get(db.doc(`users/${actor.uid}`))).data();
  if (!profile?.active || profile.role !== actor.role || (profile.revokedBeforeSeconds && actor.authTime <= profile.revokedBeforeSeconds)) fail("permission-denied", "Akses akun telah berubah.");
}
function receiptRef(actor: Actor, requestId: string) { return db.doc(`operationReceipts/${actor.uid}_${requestId}`); }
function duplicate(data: FirebaseFirestore.DocumentData | undefined, hash: string) {
  if (!data) return null;
  if (data.hash !== hash) fail("already-exists", "requestId sudah digunakan untuk permintaan berbeda. Muat ulang lalu coba lagi.");
  return data.result;
}
function recordAudit(tx: Transaction, actor: Actor, requestId: string, device: Device, type: string, id: string, action: string, before: Record<string, unknown>, after: Record<string, unknown>) {
  const difference = changed(before, after);
  tx.create(db.doc(`activityLogs/${actor.uid}_${requestId}`), {
    actorUid: actor.uid, actorName: actor.name, actorEmail: actor.email, actorRole: actor.role,
    action, entityType: type, entityId: id, before: difference.before, after: difference.after,
    changedFields: Object.fromEntries(difference.changedFields.map(k => [k, {before: difference.before[k], after: difference.after[k]}])),
    timestamp: FieldValue.serverTimestamp(), requestId, device, ...device,
  });
}
function relevant(data: Record<string, any> | undefined): Record<string, any> {
  if (!data) return {};
  const ignored = ["createdAt", "updatedAt", "createdBy", "updatedBy", "searchTokens"];
  return Object.fromEntries(Object.entries(data).filter(([k]) => !ignored.includes(k)));
}
async function promoteImage(actor: Actor, type: EntityType, id: string, image: Image): Promise<Image> {
  if (image.storagePath.startsWith('drive/')) return new DriveMedia(db).promote(actor,type,id,image);
  const mediaPrefix = `media/${type}/${id}/`;
  if (image.storagePath.startsWith(mediaPrefix) && /^media\/(product|partner)\/[0-9a-f-]{36}\/[0-9a-f]{64}\.(png|jpeg|webp)$/.test(image.storagePath)) {
    const [exists] = await bucket().file(image.storagePath).exists();
    if (!exists) fail("failed-precondition", "Gambar tersimpan tidak ditemukan. Unggah kembali.");
    return {storagePath: image.storagePath, url: ""};
  }
  const match = /^staging\/([^/]+)\/([0-9a-f-]{36})\/image\.(png|jpeg|webp)$/.exec(image.storagePath);
  if (!match || match[1] !== actor.uid) return fail("permission-denied", "Referensi unggahan tidak valid.");
  const session = (await db.doc(`uploadSessions/${actor.uid}_${match[2]}`).get()).data();
  if (!session || session.entityType !== type || session.entityId !== id || session.storagePath !== image.storagePath || session.expiresAt.toMillis() <= Date.now()) return fail("failed-precondition", "Sesi unggah berakhir atau tidak cocok.");
  const file = bucket().file(image.storagePath);
  const [metadata] = await file.getMetadata().catch(() => fail("failed-precondition", "Unggahan belum selesai. Coba kembali."));
  if (Number(metadata.size) !== session.bytes || Number(metadata.size) > MAX_IMAGE_BYTES || metadata.contentType !== session.contentType) return fail("invalid-argument", "Ukuran atau jenis gambar tidak sesuai sesi unggah.");
  const [raw] = await file.download();
  const mime = session.contentType;
  const validMagic = mime === "image/png" ? raw.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])) : mime === "image/jpeg" ? raw[0] === 255 && raw[1] === 216 && raw[2] === 255 : raw.subarray(0,4).toString() === "RIFF" && raw.subarray(8,12).toString() === "WEBP";
  if (!validMagic) return fail("invalid-argument", "Isi berkas bukan gambar PNG, JPEG, atau WebP yang valid.");
  const ext = mime === "image/png" ? "png" : mime === "image/jpeg" ? "jpeg" : "webp";
  let bytes: Buffer;
  try {
    const processor = sharp(raw, {limitInputPixels: 40_000_000, animated: false, failOn: "warning"}).rotate().resize({width: 4096, height: 4096, fit: "inside", withoutEnlargement: true});
    bytes = await processor.toFormat(ext, ext === "png" ? {compressionLevel: 9} : {quality: 85}).toBuffer();
  } catch { return fail("invalid-argument", "Gambar rusak atau dimensinya terlalu besar."); }
  if (bytes.length > MAX_IMAGE_BYTES) return fail("invalid-argument", "Hasil gambar melebihi 5 MiB. Kompres gambar lalu coba lagi.");
  const digest = createHash("sha256").update(bytes).digest("hex");
  const storagePath = `${mediaPrefix}${digest}.${ext}`;
  try {
    await bucket().file(storagePath).save(bytes, {resumable: false, preconditionOpts: {ifGenerationMatch: 0}, metadata: {contentType: mime, cacheControl: "private,max-age=300", metadata: {entityType: type, entityId: id}}});
  } catch (error) {
    if ((error as {code?: number}).code !== 412) throw error;
  }
  return {storagePath, url: ""};
}
export async function getSession(request: Request) { const {authTime: _authTime, ...session} = await requireActor(request); return session; }
export async function beginUpload(request: Request) {
  const actor = await requireActor(request);
  const value = parse(uploadSchema, request.data);
  if (process.env.MEDIA_PROVIDER==='drive') return new DriveMedia(db).begin(actor,value);
  const hash = fingerprint(value);
  const ext = value.contentType === "image/png" ? "png" : value.contentType === "image/jpeg" ? "jpeg" : "webp";
  const storagePath = `staging/${actor.uid}/${value.requestId}/image.${ext}`;
  await db.runTransaction(async tx => {
    await checkActorTransaction(tx, actor);
    const ref = db.doc(`uploadSessions/${actor.uid}_${value.requestId}`);
    const old = await tx.get(ref);
    if (old.exists) {
      if (old.data()?.hash !== hash || old.data()?.expiresAt.toMillis() <= Date.now()) fail("already-exists", "Sesi unggah telah dipakai atau berakhir. Buat permintaan baru.");
      return;
    }
    tx.create(ref, {uid: actor.uid, hash, storagePath, entityType: value.entityType, entityId: value.entityId, contentType: value.contentType, bytes: value.bytes, createdAt: FieldValue.serverTimestamp(), expiresAt: Timestamp.fromMillis(Date.now() + 24*60*60*1000)});
  });
  return {storagePath};
}
export async function saveEntity(request: Request, type: EntityType) {
  const actor = await requireActor(request);
  const value = parse(saveEnvelope, request.data);
  const data: Record<string, any> = type === "product" ? parse(productSchema, value.data) : parse(partnerSchema, value.data);
  const hash = fingerprint({operation: `save${type}`, ...value});
  const receipt = receiptRef(actor, value.requestId);
  const previousResult = duplicate((await receipt.get()).data(), hash);
  if (previousResult) return previousResult;
  // Promotion is immutable and private. A failed/conflicting transaction leaves only a safe orphan.
  if (type === "product") {
    const oldPaths: string[] = data.images.map((i: Image) => i.storagePath);
    const promoted: Image[] = [];
    // Decode one bounded image at a time; eight parallel 40MP decodes can exceed function memory.
    for (const image of data.images as Image[]) promoted.push(await promoteImage(actor, type, value.id, image));
    data.images = promoted;
    data.mainImagePath = data.images[oldPaths.indexOf(data.mainImagePath)]?.storagePath ?? "";
    data.searchTokens = searchTokens(data.name, data.brand);
  } else data.logo = await promoteImage(actor, type, value.id, data.logo);
  const collection = type === "product" ? "products" : "partners";
  const publicCollection = type === "product" ? "publicProducts" : "publicPartners";
  return db.runTransaction(async tx => {
    await checkActorTransaction(tx, actor);
    const [receiptDoc, oldDoc] = await Promise.all([tx.get(receipt), tx.get(db.doc(`${collection}/${value.id}`))]);
    const result = duplicate(receiptDoc.data(), hash);
    if (result) return result;
    const old = oldDoc.data();
    if ((old?.version ?? 0) !== value.expectedVersion || old?.archived) return fail("aborted", "Data telah diubah pengguna lain atau diarsipkan. Muat ulang sebelum menyimpan.");
    const version = value.expectedVersion + 1;
    const next = {...data, id: value.id, archived: false, version, createdAt: old?.createdAt ?? FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp(), createdBy: old?.createdBy ?? actor.uid, updatedBy: actor.uid};
    const mediaChanges=await new DriveMedia(db).transactionChanges(tx,actor,value.device,type,value.id,value.requestId,old,next);
    mediaChanges();
    tx.set(oldDoc.ref, next);
    const projection = publicProjection(type, value.id, next);
    const publicRef = db.doc(`${publicCollection}/${value.id}`);
    if (projection) tx.set(publicRef, projection); else tx.delete(publicRef);
    const statusField = type === "product" ? "status" : "active";
    recordAudit(tx, actor, value.requestId, value.device, type, value.id, !old ? "add" : old[statusField] !== data[statusField] ? "status" : "edit", relevant(old), relevant(next));
    const response = {id: value.id, version};
    tx.create(receipt, {hash, result: response, createdAt: FieldValue.serverTimestamp()});
    return response;
  });
}
export async function archiveEntity(request: Request, type: EntityType) {
  const actor = await requireActor(request);
  const value = parse(archiveSchema, request.data);
  const hash = fingerprint({operation: `archive${type}`, ...value});
  const receipt = receiptRef(actor, value.requestId);
  const collection = type === "product" ? "products" : "partners";
  return db.runTransaction(async tx => {
    await checkActorTransaction(tx, actor);
    const [r, oldDoc] = await Promise.all([tx.get(receipt), tx.get(db.doc(`${collection}/${value.id}`))]);
    const duplicateResult = duplicate(r.data(), hash);
    if (duplicateResult) return duplicateResult;
    const old = oldDoc.data();
    if (!old) return fail("not-found", "Data tidak ditemukan.");
    if (old.version !== value.expectedVersion || old.archived) return fail("aborted", "Versi berubah. Muat ulang data.");
    const next = {...old, archived: true, version: old.version + 1, updatedAt: FieldValue.serverTimestamp(), updatedBy: actor.uid};
    tx.set(oldDoc.ref, next);
    tx.delete(db.doc(`${type === "product" ? "publicProducts" : "publicPartners"}/${value.id}`));
    recordAudit(tx, actor, value.requestId, value.device, type, value.id, "archive", relevant(old), relevant(next));
    const response = {id: value.id, version: next.version};
    tx.create(receipt, {hash, result: response, createdAt: FieldValue.serverTimestamp()});
    return response;
  });
}
export async function getDashboard(request: Request) {
  const actor = await requireActor(request);
  const products = db.collection("products").where("archived", "==", false);
  const logs = actor.role === "OWNER" ? db.collection("activityLogs") : db.collection("activityLogs").where("actorUid", "==", actor.uid);
  const [active, drafts, partners, recent] = await Promise.all([
    products.where("status", "==", "active").count().get(),
    products.where("status", "in", ["draft", "inactive"]).count().get(),
    db.collection("partners").where("archived", "==", false).where("active", "==", true).count().get(),
    logs.orderBy("timestamp", "desc").limit(6).get(),
  ]);
  return {activeProducts: active.data().count, draftProducts: drafts.data().count, activePartners: partners.data().count, recent: recent.docs.map(d => ({id: d.id, ...d.data(), timestamp: d.get("timestamp")?.toMillis() ?? 0}))};
}
export async function manageAdmin(request: Request) {
  const actor = await requireActor(request, true);
  const value = parse(adminSchema, request.data);
  const hash = fingerprint({endpoint: "manageAdmin", ...value});
  const receipt = receiptRef(actor, value.requestId);
  const existingResult = duplicate((await receipt.get()).data(), hash);
  const targetUid = value.operation === "create" ? `admin_${fingerprint(`${actor.uid}_${value.requestId}`).slice(0, 40)}` : value.uid!;
  if (targetUid === actor.uid) return fail("permission-denied", "Tidak dapat mengubah akses akun sendiri.");
  if (!existingResult && value.operation === "create") {
    try { await auth.createUser({uid: targetUid, email: value.email!, displayName: value.name!, disabled: true, password: randomBytes(48).toString("base64url")}); }
    catch (error) {
      if ((error as {code?: string}).code !== "auth/uid-already-exists") return fail("already-exists", "Email sudah terdaftar atau akun belum dapat dibuat.");
      const partial = await auth.getUser(targetUid);
      if (partial.email?.toLowerCase() !== value.email?.toLowerCase() || partial.displayName !== value.name || partial.customClaims?.role === "OWNER") return fail("already-exists", "Permintaan parsial memiliki identitas berbeda. Gunakan isi permintaan semula.");
    }
    await auth.setCustomUserClaims(targetUid, {role: "ADMIN"});
  }
  // Profile is the immediate authority for reads and writes. Auth is reconciled afterwards.
  const result = await db.runTransaction(async tx => {
    await checkActorTransaction(tx, actor);
    const [r, target] = await Promise.all([tx.get(receipt), tx.get(db.doc(`users/${targetUid}`))]);
    const repeated = duplicate(r.data(), hash);
    if (repeated) return repeated as {uid: string; email: string};
    const old = target.data();
    if (old && old.role !== "ADMIN") return fail("permission-denied", "Akun owner tidak dapat diubah melalui aplikasi.");
    if (value.operation === "setActive" && !old) return fail("not-found", "Admin tidak ditemukan.");
    if (value.operation === "create" && old) return fail("already-exists", "Admin telah dibuat melalui permintaan lain.");
    const next = value.operation === "create" ? {uid: targetUid, name: value.name!, email: value.email!, role: "ADMIN", active: true, version: 1, revokedBeforeSeconds: 0, createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp()} : {...old!, active: value.active!, revokedBeforeSeconds: !value.active ? Math.floor(Date.now() / 1000) : old!.revokedBeforeSeconds ?? 0, version: (old!.version ?? 0) + 1, updatedAt: FieldValue.serverTimestamp()};
    tx.set(target.ref, next);
    recordAudit(tx, actor, value.requestId, value.device, "user", targetUid, old ? "status" : "add", relevant(old), relevant(next));
    const response = {uid: targetUid, email: next.email};
    tx.create(receipt, {hash, result: response, createdAt: FieldValue.serverTimestamp()});
    return response;
  });
  const profile = (await db.doc(`users/${targetUid}`).get()).data();
  await auth.updateUser(targetUid, {disabled: !profile?.active});
  if (!profile?.active) await auth.revokeRefreshTokens(targetUid);
  return result;
}
export async function cleanupStaging() {
  // At 48h the 24h upload lease is expired, so no valid save can still consume it.
  const cutoff = Date.now() - 48*60*60*1000;
  const [files] = await bucket().getFiles({prefix: "staging/"});
  for (const file of files) {
    if (new Date(file.metadata.timeCreated ?? "").getTime() < cutoff) await file.delete({ignoreNotFound: true});
  }
  const expired = await db.collection("uploadSessions").where("expiresAt", "<", Timestamp.fromMillis(cutoff)).limit(400).get();
  if (!expired.empty) { const batch = db.batch(); expired.docs.forEach(d => batch.delete(d.ref)); await batch.commit(); }
}
