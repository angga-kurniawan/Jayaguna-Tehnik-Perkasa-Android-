import {
  collection, query, where, orderBy, documentId, limit, onSnapshot, startAfter,
} from "https://www.gstatic.com/firebasejs/13.1.0/firebase-firestore.js";
import { ref, getBlob } from "https://www.gstatic.com/firebasejs/13.1.0/firebase-storage.js";
import { PAGE_SIZE, MAX_WINDOW, nextWindow, rupiah, safeWebsite, mainImage, mediaPath, driveMediaUrl, catalogError } from "./helpers.js";

const node = (tag, className, content) => {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (content !== undefined) element.textContent = String(content ?? "");
  return element;
};

/** Mount into an existing website without changing its page/header/footer.
 * Return { destroy } and call it when a SPA route unmounts.
 */
export function mountCatalog({ db, storage, backendBaseUrl = "", productsRoot, partnersRoot }) {
  if (!(productsRoot instanceof HTMLElement) || !(partnersRoot instanceof HTMLElement)) {
    throw new Error("Sediakan elemen productsRoot dan partnersRoot.");
  }
  const productList = mountSection({
    db, storage, backendBaseUrl, root: productsRoot, kind: "product", collectionName: "publicProducts",
    constraints: [where("status", "==", "active"), where("archived", "==", false), orderBy("updatedAt", "desc"), orderBy(documentId(), "desc")],
    emptyMessage: "Produk belum tersedia. Silakan hubungi kami untuk kebutuhan AC Anda.",
  });
  const partnerList = mountSection({
    db, storage, backendBaseUrl, root: partnersRoot, kind: "partner", collectionName: "publicPartners",
    constraints: [where("active", "==", true), where("archived", "==", false), orderBy("displayOrder", "asc"), orderBy(documentId(), "asc")],
    emptyMessage: "Daftar mitra akan segera tersedia.",
  });
  return { destroy() { productList.destroy(); partnerList.destroy(); } };
}

function mountSection({ db, storage, backendBaseUrl, root, kind, collectionName, constraints, emptyMessage }) {
  const message = node("p", "jt-catalog-message", "Memuat…");
  message.setAttribute("role", "status");
  message.setAttribute("aria-live", "polite");
  const list = node("div", kind === "product" ? "jt-product-grid" : "jt-partner-grid");
  const more = node("button", "jt-catalog-more", "Muat lebih banyak");
  more.type = "button";
  more.hidden = true;
  const retry = node("button", "jt-catalog-retry", "Coba lagi");
  retry.type = "button";
  retry.hidden = true;
  const next = node("button", "jt-catalog-more", "Halaman berikutnya");
  next.type = "button";
  next.hidden = true;
  const first = node("button", "jt-catalog-more", "Kembali ke awal");
  first.type = "button";
  first.hidden = true;
  root.replaceChildren(message, list, more, next, first, retry);
  let windowSize = PAGE_SIZE;
  let cursor = null;
  let lastVisible = null;
  let unsubscribe;
  let disposed = false;
  let subscriptionId = 0;
  let renderGeneration = 0;
  let blobUrls = new Set();

  const cleanupImages = () => {
    for (const url of blobUrls) URL.revokeObjectURL(url);
    blobUrls = new Set();
  };

  async function imageFor(media, id, alt, generation) {
    const frame = node("div", "jt-image-frame");
    const drive = driveMediaUrl(media,backendBaseUrl);
    const valid = mediaPath(media?.storagePath, kind, id);
    if (!valid && !drive) {
      frame.append(node("span", "jt-image-fallback", "Gambar belum tersedia"));
      return frame;
    }
    const image = node("img", kind === "partner" ? "jt-partner-logo" : "jt-product-image");
    image.alt = String(alt ?? "");
    image.loading = "lazy";
    image.decoding = "async";
    frame.append(node("span", "jt-image-fallback", "Memuat gambar…"));
    // Drive remains private. The backend checks the live public projection before
    // serving bytes; legacy Storage images still use SDK Rules-checked getBlob.
    const download=drive ? fetch(drive,{credentials:"omit",redirect:"error",signal:AbortSignal.timeout(15000)}).then(async response=>{
      if (!response.ok || !["image/png","image/jpeg","image/webp"].includes(response.headers.get("content-type"))) throw new Error("Gambar tidak tersedia");
      if (Number(response.headers.get("content-length") || 0)>5*1024*1024) throw new Error("Gambar terlalu besar");
      const blob=await response.blob();if(blob.size>5*1024*1024)throw new Error("Gambar terlalu besar");return blob;
    }) : getBlob(ref(storage, valid), 5 * 1024 * 1024);
    download.then((blob) => {
      if (disposed || generation !== renderGeneration) return;
      const url = URL.createObjectURL(blob);
      blobUrls.add(url);
      image.src = url;
      frame.replaceChildren(image);
      image.onerror = () => { frame.replaceChildren(node("span", "jt-image-fallback", "Gambar gagal ditampilkan")); };
    }).catch(() => {
      if (!disposed && generation === renderGeneration) {
        frame.replaceChildren(node("span", "jt-image-fallback", "Gambar tidak tersedia"));
      }
    });
    return frame;
  }

  async function render(documents, generation) {
    const cards = await Promise.all(documents.map(async (document) => {
      const data = document.data();
      if (kind === "product") {
        const card = node("article", "jt-product-card");
        card.append(await imageFor(mainImage(data), document.id, data.name, generation));
        const body = node("div", "jt-product-body");
        body.append(node("p", "jt-product-brand", data.brand), node("h3", "jt-product-name", data.name));
        const details = [data.model, data.capacityPk ? `${data.capacityPk} PK` : "", data.condition === "used" ? "Bekas" : "Baru"].filter(Boolean).join(" · ");
        body.append(node("p", "jt-product-details", details), node("p", "jt-product-price", rupiah(data.price)), node("p", "jt-product-description", data.description));
        const specs = Object.entries(data.specifications ?? {}).slice(0, 30);
        if (specs.length) {
          const definition = node("dl", "jt-product-specifications");
          for (const [key, value] of specs) definition.append(node("dt", "", key), node("dd", "", value));
          body.append(definition);
        }
        card.append(body);
        return card;
      }
      const card = node("article", "jt-partner-card");
      card.append(await imageFor(data.logo, document.id, data.altText || data.name, generation));
      const website = safeWebsite(data.website);
      if (website) {
        const link = node("a", "jt-partner-name", data.name);
        link.href = website;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        card.append(link);
      } else card.append(node("p", "jt-partner-name", data.name));
      return card;
    }));
    if (!disposed && generation === renderGeneration) list.replaceChildren(...cards);
  }

  function subscribe() {
    unsubscribe?.();
    const currentSubscription = ++subscriptionId;
    ++renderGeneration;
    cleanupImages();
    list.replaceChildren();
    root.setAttribute("aria-busy", "true");
    message.textContent = "Memuat…";
    more.hidden = true;
    next.hidden = true;
    first.hidden = cursor === null;
    retry.hidden = true;
    // A cumulative realtime window avoids holes/duplicates when order changes between
    // pages. Fetch one extra record as a has-more sentinel; never fetch private docs.
    const request = query(collection(db, collectionName), ...constraints, ...(cursor ? [startAfter(cursor)] : []), limit(windowSize + 1));
    unsubscribe = onSnapshot(request, { includeMetadataChanges: true }, (snapshot) => {
      if (disposed || currentSubscription !== subscriptionId) return;
      // Memory cache may contain a product that was unpublished since the previous
      // visit. Wait for a server-confirmed snapshot before displaying anything.
      if (snapshot.metadata.fromCache) {
        ++renderGeneration;
        cleanupImages();
        list.replaceChildren();
        more.hidden = true;
        next.hidden = true;
        root.setAttribute("aria-busy", "true");
        message.textContent = "Pembaruan katalog menunggu koneksi server…";
        return;
      }
      const generation = ++renderGeneration;
      cleanupImages();
      const documents = snapshot.docs.slice(0, windowSize);
      lastVisible = documents.at(-1) ?? null;
      root.setAttribute("aria-busy", "false");
      message.textContent = documents.length ? "" : emptyMessage;
      more.hidden = snapshot.size <= windowSize || windowSize >= MAX_WINDOW;
      next.hidden = snapshot.size <= windowSize || windowSize < MAX_WINDOW;
      more.disabled = false;
      retry.hidden = true;
      void render(documents, generation);
    }, (error) => {
      if (disposed || currentSubscription !== subscriptionId) return;
      ++renderGeneration;
      cleanupImages();
      list.replaceChildren();
      root.setAttribute("aria-busy", "false");
      message.textContent = catalogError(error);
      retry.hidden = false;
      more.hidden = true;
      next.hidden = true;
    });
  }

  more.onclick = () => { more.disabled = true; windowSize = nextWindow(windowSize); subscribe(); };
  next.onclick = () => {
    if (!lastVisible) return;
    cursor = lastVisible;
    windowSize = PAGE_SIZE;
    subscribe();
  };
  first.onclick = () => { cursor = null; windowSize = PAGE_SIZE; subscribe(); };
  retry.onclick = subscribe;
  subscribe();
  return {
    destroy() {
      disposed = true;
      ++renderGeneration;
      unsubscribe?.();
      cleanupImages();
      root.replaceChildren();
      root.removeAttribute("aria-busy");
    },
  };
}
