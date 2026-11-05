import {HttpsError} from 'firebase-functions/v2/https';

export const DRIVE_FILE_ID = /^[A-Za-z0-9_-]{10,200}$/;
export const ASSET_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const COMPANY_DRIVE_EMAIL = 'mtahakurniawan@gmail.com';
export const COMPANY_DRIVE_FOLDERS = {
  product:'1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd',
  partner:'1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6',
  portfolioResidential:'1kOqO3wgNmUCujrhb1tqY3kB_kG7jTEjx',
  portfolioCorporate:'10Z9US8nxqTV5AkJrRrEURRsWNhOpvHvK',
} as const;
export type CompanyEntityType = keyof typeof COMPANY_DRIVE_FOLDERS;
export function backendUrl(value = process.env.BACKEND_PUBLIC_URL || process.env.RENDER_EXTERNAL_URL || ''): string {
  let url: URL;
  try { url = new URL(value); } catch { throw new HttpsError('failed-precondition','Isi BACKEND_PUBLIC_URL backend HTTPS.'); }
  const local = ['localhost','127.0.0.1','[::1]'].includes(url.hostname);
  if ((url.protocol !== 'https:' && !(local && url.protocol === 'http:' && process.env.NODE_ENV !== 'production')) || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new HttpsError('failed-precondition','BACKEND_PUBLIC_URL harus origin HTTPS; HTTP hanya untuk localhost pengembangan.');
  }
  return url.origin;
}
export function driveConfig() {
  const products = process.env.GOOGLE_DRIVE_PRODUCTS_FOLDER_ID || '';
  const partners = process.env.GOOGLE_DRIVE_PARTNERS_FOLDER_ID || '';
  const portfolioResidential = process.env.GOOGLE_DRIVE_PORTFOLIO_RESIDENTIAL_FOLDER_ID || COMPANY_DRIVE_FOLDERS.portfolioResidential;
  const portfolioCorporate = process.env.GOOGLE_DRIVE_PORTFOLIO_CORPORATE_FOLDER_ID || COMPANY_DRIVE_FOLDERS.portfolioCorporate;
  const clientId = process.env.GOOGLE_OAUTH_CLIENT_ID || '';
  const clientSecret = process.env.GOOGLE_OAUTH_CLIENT_SECRET || '';
  const accountEmail = (process.env.GOOGLE_DRIVE_ACCOUNT_EMAIL || '').toLowerCase();
  const key = Buffer.from(process.env.DRIVE_TOKEN_ENCRYPTION_KEY || '', 'base64');
  if (!DRIVE_FILE_ID.test(products) || !DRIVE_FILE_ID.test(partners) || products === partners) throw new HttpsError('failed-precondition','Isi dua Folder ID Drive yang berbeda: Produk-AC dan Logo-Mitra.');
  if (![portfolioResidential,portfolioCorporate].every(id=>DRIVE_FILE_ID.test(id)) || new Set([products,partners,portfolioResidential,portfolioCorporate]).size!==4) throw new HttpsError('failed-precondition','Keempat folder Drive harus valid dan berbeda.');
  if (!clientId.endsWith('.apps.googleusercontent.com') || !clientSecret || !accountEmail.includes('@') || key.length !== 32) throw new HttpsError('failed-precondition','Konfigurasi OAuth Drive belum lengkap. Hubungi OWNER.');
  const origin = backendUrl();
  const redirectUri = process.env.GOOGLE_OAUTH_REDIRECT_URI || origin+'/api/drive/oauth/callback';
  if (redirectUri !== origin+'/api/drive/oauth/callback') throw new HttpsError('failed-precondition','OAuth redirect URI harus sama dengan origin backend dan /api/drive/oauth/callback.');
  return {products,partners,portfolioResidential,portfolioCorporate,clientId,clientSecret,accountEmail,key,origin,redirectUri};
}

export function centralDriveConfig() {
  const config=driveConfig();
  if (config.accountEmail!==COMPANY_DRIVE_EMAIL || config.products!==COMPANY_DRIVE_FOLDERS.product ||
      config.partners!==COMPANY_DRIVE_FOLDERS.partner || config.portfolioResidential!==COMPANY_DRIVE_FOLDERS.portfolioResidential ||
      config.portfolioCorporate!==COMPANY_DRIVE_FOLDERS.portfolioCorporate) {
    throw new HttpsError('failed-precondition','Drive pusat harus menggunakan akun pemilik dan keempat folder perusahaan yang sudah ditetapkan.');
  }
  return config;
}
