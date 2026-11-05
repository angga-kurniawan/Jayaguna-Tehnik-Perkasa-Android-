// Copy to firebase-config.js. These client identifiers are public, not Admin SDK credentials.
export const catalogConfig = {
  // Public HTTPS origin of the trusted backend, never a Google Drive token URL.
  backendBaseUrl: "https://YOUR_BACKEND_DOMAIN",
  firebase: {
    apiKey: "REPLACE_WITH_FIREBASE_WEB_API_KEY",
    authDomain: "YOUR_PROJECT_ID.firebaseapp.com",
    projectId: "YOUR_PROJECT_ID",
    storageBucket: "YOUR_PROJECT_ID.firebasestorage.app",
    appId: "REPLACE_WITH_FIREBASE_WEB_APP_ID",
  },
  // Register the WEB app with App Check / reCAPTCHA Enterprise first.
  recaptchaEnterpriseSiteKey: "REPLACE_WITH_PUBLIC_RECAPTCHA_ENTERPRISE_SITE_KEY",
  // Explicit localhost-only option. Never enable on a hosted production website.
  useEmulators: false,
  // Development only: set true to generate a debug token, then register that token
  // in Firebase Console. Do not put a debug-token secret in this public module.
  appCheckDebug: false,
};
