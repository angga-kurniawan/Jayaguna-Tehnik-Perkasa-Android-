// Pinned official modular browser SDK; keep all module versions identical.
import { initializeApp, getApps } from "https://www.gstatic.com/firebasejs/13.1.0/firebase-app.js";
import { initializeAppCheck, ReCaptchaEnterpriseProvider } from "https://www.gstatic.com/firebasejs/13.1.0/firebase-app-check.js";
import { getFirestore, connectFirestoreEmulator } from "https://www.gstatic.com/firebasejs/13.1.0/firebase-firestore.js";
import { getStorage, connectStorageEmulator } from "https://www.gstatic.com/firebasejs/13.1.0/firebase-storage.js";

export function createCatalogFirebase(config) {
  const existing = getApps().find((app) => app.name === "jayaguna-public-catalog");
  if (existing) throw new Error("Katalog sudah diinisialisasi. Gunakan kembali objek Firebase yang ada.");
  for (const key of ["apiKey", "projectId", "storageBucket", "appId"]) {
    if (!config?.firebase?.[key] || /REPLACE|YOUR_PROJECT/.test(config.firebase[key])) {
      throw new Error("Lengkapi firebase-config.js dengan konfigurasi aplikasi web Firebase.");
    }
  }
  const local = ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname);
  if ((config.useEmulators || config.appCheckDebug) && !local) {
    throw new Error("Konfigurasi emulator/debug hanya boleh digunakan di localhost.");
  }
  if (!config.useEmulators && (!config.recaptchaEnterpriseSiteKey || config.recaptchaEnterpriseSiteKey.startsWith("REPLACE"))) {
    throw new Error("Isi site key reCAPTCHA Enterprise untuk App Check.");
  }
  const app = initializeApp(config.firebase, "jayaguna-public-catalog");
  if (!config.useEmulators) {
    if (config.appCheckDebug) self.FIREBASE_APPCHECK_DEBUG_TOKEN = true;
    initializeAppCheck(app, {
      provider: new ReCaptchaEnterpriseProvider(config.recaptchaEnterpriseSiteKey),
      isTokenAutoRefreshEnabled: true,
    });
  }
  const db = getFirestore(app);
  const storage = getStorage(app);
  if (config.useEmulators) {
    connectFirestoreEmulator(db, "127.0.0.1", 8080);
    connectStorageEmulator(storage, "127.0.0.1", 9199);
  }
  // No Firebase Auth initialization: public catalog never signs a visitor in.
  return { db, storage, backendBaseUrl: config.backendBaseUrl || "" };
}
