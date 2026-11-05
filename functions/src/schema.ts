import { createHash } from "node:crypto";
import { z } from "zod";

export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
export const uuid = z.string().uuid();
const text = (max: number) => z.string().trim().max(max);
export const deviceSchema = z.object({
  installationId: uuid,
  deviceName: text(100).min(1),
  brand: text(80),
  model: text(100),
  androidVersion: text(40),
  appVersion: text(40),
}).strict();
export const contextSchema = z.object({ requestId: uuid, device: deviceSchema });
const imageSchema = z.object({ storagePath: text(500).min(1), url: text(2048).optional().default(""),
  provider:z.enum(['drive','firebase']).optional(),driveFileId:z.string().regex(/^[A-Za-z0-9_-]{10,200}$/).optional(),
  fileName:text(160).optional(),mimeType:z.enum(['image/png','image/jpeg','image/webp']).optional(),sizeBytes:z.number().int().positive().max(MAX_IMAGE_BYTES).optional(),
}).strict();
export const productSchema = z.object({
  name: text(160).min(2), brand: text(100).min(1), model: text(100),
  category: z.enum(["split", "cassette", "standing", "ducted", "central", "other"]),
  capacityPk: text(40), condition: z.enum(["new", "used"]),
  price: z.number().finite().nonnegative().max(1e12).nullable(),
  description: text(10000),
  specifications: z.record(text(80).min(1), text(1000)).refine(v => Object.keys(v).length <= 50),
  images: z.array(imageSchema).max(8), mainImagePath: text(500),
  status: z.enum(["draft", "active", "inactive"]),
}).strict().superRefine((v, ctx) => {
  if (new Set(v.images.map(i => i.storagePath)).size !== v.images.length) ctx.addIssue({code: "custom", message: "Foto duplikat."});
  if (v.images.length && !v.images.some(i => i.storagePath === v.mainImagePath)) ctx.addIssue({code: "custom", message: "Foto utama tidak ada dalam daftar foto."});
  if (!v.images.length && v.mainImagePath) ctx.addIssue({code: "custom", message: "Foto utama tidak valid."});
  if (v.status === "active" && !v.images.length) ctx.addIssue({code: "custom", message: "Produk aktif membutuhkan foto."});
});
export const partnerSchema = z.object({
  name: text(160).min(2), logo: imageSchema, altText: text(250).min(1),
  website: text(2048).refine(v => !v || /^https:\/\//i.test(v) && (() => {try { const u = new URL(v); return !!u.hostname && !u.username && !u.password; } catch { return false; }})(), "Website harus berupa URL HTTPS."),
  displayOrder: z.number().int().min(0).max(1e6), active: z.boolean(),
}).strict();
export const saveEnvelope = contextSchema.extend({ id: uuid, expectedVersion: z.number().int().nonnegative(), data: z.unknown() }).strict();
export const archiveSchema = contextSchema.extend({ id: uuid, expectedVersion: z.number().int().positive() }).strict();
export const uploadSchema = contextSchema.extend({
  entityType: z.enum(["product", "partner"]), entityId: uuid,
  contentType: z.enum(["image/png", "image/jpeg", "image/webp"]),
  bytes: z.number().int().positive().max(MAX_IMAGE_BYTES),
  fileName:text(120).optional(),sha256:z.string().regex(/^[0-9a-f]{64}$/).optional(),
}).strict();
export const adminSchema = contextSchema.extend({
  operation: z.enum(["create", "setActive"]), uid: text(128).optional(),
  name: text(160).optional(), email: z.string().trim().email().max(254).optional(), active: z.boolean().optional(),
}).strict().superRefine((v, c) => {
  if (v.operation === "create" && (!v.name || !v.email)) c.addIssue({code: "custom", message: "Nama dan email diperlukan."});
  if (v.operation === "setActive" && (!v.uid || v.active === undefined)) c.addIssue({code: "custom", message: "UID dan status diperlukan."});
});
export type Device = z.infer<typeof deviceSchema>;
export type Image = z.infer<typeof imageSchema>;
export type EntityType = "product" | "partner";
export function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") return "{" + Object.entries(value).sort(([a],[b]) => a.localeCompare(b)).map(([k,v]) => `${JSON.stringify(k)}:${canonical(v)}`).join(",") + "}";
  return JSON.stringify(value) ?? "null";
}
export function searchTokens(name: string, brand: string): string[] {
  const source = [name, brand].flatMap(v => [v.toLowerCase().trim(), ...v.toLowerCase().trim().split(/\s+/)]);
  return [...new Set(source.flatMap(v => Array.from({length: Math.min(v.length, 50)}, (_, i) => v.slice(0, i + 1))))].slice(0, 1000);
}
export function fingerprint(value: unknown): string { return createHash("sha256").update(canonical(value)).digest("hex"); }
export function changed(before: Record<string, unknown>, after: Record<string, unknown>) {
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter(k => canonical(before[k]) !== canonical(after[k]));
  return { changedFields: keys, before: Object.fromEntries(keys.map(k => [k, before[k] ?? null])), after: Object.fromEntries(keys.map(k => [k, after[k] ?? null])) };
}
export function publicProjection(type: EntityType, id: string, value: Record<string, any>) {
  if (value.archived || (type === "product" ? value.status !== "active" : !value.active)) return null;
  const keys = type === "product" ? ["name","brand","model","category","capacityPk","condition","price","description","specifications","images","mainImagePath","status"] : ["name","logo","altText","website","displayOrder","active"];
  return {
    id, ...Object.fromEntries(keys.map(k => [k, value[k]])), archived: false,
    createdAt: value.createdAt, updatedAt: value.updatedAt,
    mediaPaths: type === "product" ? value.images.map((i: Image) => i.storagePath) : [value.logo.storagePath],
  };
}
