import { createCatalogFirebase } from "./firebase-client.js";
import { mountCatalog } from "./catalog.js";

const status = document.getElementById("configuration-status");
try {
  const { catalogConfig } = await import("./firebase-config.js");
  const services = createCatalogFirebase(catalogConfig);
  const catalog = mountCatalog({
    ...services,
    productsRoot: document.getElementById("jt-products"),
    partnersRoot: document.getElementById("jt-partners"),
  });
  // pagehide can be persisted by bfcache; destroy only on actual route disposal.
  window.addEventListener("pagehide", (event) => { if (!event.persisted) catalog.destroy(); });
} catch (error) {
  status.hidden = false;
  status.textContent = error?.message || "Isi firebase-config.js terlebih dahulu.";
}
