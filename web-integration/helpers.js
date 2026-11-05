export const PAGE_SIZE = 12;
// Firestore Rules bound every list query to <=100 records, including sentinel.
export const MAX_WINDOW = 96;

export function nextWindow(current, size = PAGE_SIZE) {
  return Math.min(MAX_WINDOW, Math.max(PAGE_SIZE, current + size));
}

export function rupiah(value) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
    ? new Intl.NumberFormat("id-ID", { style: "currency", currency: "IDR", maximumFractionDigits: 0 }).format(value)
    : "Hubungi kami untuk harga";
}

export function safeWebsite(value) {
  if (typeof value !== "string" || value.length > 2000) return null;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && !url.username && !url.password ? url.href : null;
  } catch {
    return null;
  }
}

export function mainImage(product) {
  const images = Array.isArray(product.images) ? product.images : [];
  return images.find((image) => image.storagePath === product.mainImagePath) ?? images[0] ?? null;
}

export function mediaPath(value, entityType, entityId) {
  // Public projection is trusted to select paths, but never accept external/bearer URLs.
  const prefix = `media/${entityType}/${entityId}/`;
  return typeof value === "string" && value.startsWith(prefix) && !value.includes("..")
    ? value
    : null;
}

export function driveMediaUrl(media, backendBaseUrl) {
  if (!media || !/^drive\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(media.storagePath || "")) return null;
  try {
    const url=new URL(backendBaseUrl);
    const local=["localhost","127.0.0.1","[::1]"].includes(url.hostname);
    if ((url.protocol!=="https:" && !(local && url.protocol==="http:")) || url.username || url.password || url.search || url.hash || url.pathname!=="/") return null;
    return url.origin+"/api/media/"+media.storagePath.slice(6);
  } catch { return null; }
}

export function catalogError(error) {
  if (error?.code === "permission-denied" || error?.code === "storage/unauthorized") {
    return "Katalog belum dapat diakses. Silakan coba lagi atau hubungi pengelola.";
  }
  if (error?.code === "unavailable") return "Koneksi terputus. Periksa internet lalu coba lagi.";
  return "Katalog gagal dimuat. Silakan coba lagi.";
}
