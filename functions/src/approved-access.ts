import {DecodedIdToken} from 'firebase-admin/auth';
import {Firestore} from 'firebase-admin/firestore';
import {HttpsError} from 'firebase-functions/v2/https';

export type GoogleIdentity = {uid:string; email:string; name:string; authTime:number; claims:DecodedIdToken};
export type ApprovedActor = GoogleIdentity & {role:'OWNER'|'ADMIN'};
type Profile = Record<string,unknown> | undefined;
const denied = () => new HttpsError('permission-denied','Akun belum disetujui atau aksesnya telah dinonaktifkan.');

/** A verified Firebase token must originate from Google, not a client-supplied email. */
export function googleIdentity(token:DecodedIdToken):GoogleIdentity {
  const email=typeof token.email==='string'?token.email.trim().toLowerCase():'';
  if (!token.uid || token.email_verified!==true || token.firebase?.sign_in_provider!=='google.com' ||
      !email || email.length>254 || /[\s/\\\r\n]/.test(email) || !email.includes('@') || !Number.isInteger(token.auth_time) || token.auth_time<=0) {
    throw new HttpsError('permission-denied','Gunakan akun Google dengan email terverifikasi.');
  }
  return {uid:token.uid,email,name:typeof token.name==='string'?token.name.slice(0,120):email,authTime:token.auth_time,claims:token};
}

/** UID decisions always override email grants; a pending/rejected UID cannot bypass approval. */
export function approvedActor(identity:GoogleIdentity, profile:Profile, grant:Profile):ApprovedActor {
  if (profile && (Object.hasOwn(profile,'active') && profile.active!==true || Object.hasOwn(profile,'status') && profile.status!=='approved')) throw denied();
  let approved:Profile;
  if (profile?.status==='approved') approved=profile;
  else if (profile?.active===true && ['OWNER','ADMIN'].includes(String(profile.role)) && identity.claims.role===profile.role) approved=profile;
  else if (grant?.status==='approved') approved=grant;
  if (!approved || Object.hasOwn(approved,'active') && approved.active!==true ||
      Object.hasOwn(approved,'uid') && approved.uid!==identity.uid ||
      Object.hasOwn(approved,'email') && (typeof approved.email!=='string' || approved.email.toLowerCase()!==identity.email)) throw denied();
  for (const value of [profile,approved]) {
    if (!value || !Object.hasOwn(value,'revokedBeforeSeconds')) continue;
    const cutoff=value.revokedBeforeSeconds;
    if (typeof cutoff!=='number' || !Number.isFinite(cutoff) || cutoff!==0 && identity.authTime<=cutoff) throw denied();
  }
  const role=Object.hasOwn(approved,'role')?approved.role:'ADMIN';
  if (role!=='OWNER' && role!=='ADMIN') throw denied();
  return {...identity,name:typeof approved.name==='string' && approved.name.trim()?approved.name.slice(0,120):identity.name,role};
}

export async function requireApprovedActor(identity:GoogleIdentity, db:Firestore):Promise<ApprovedActor> {
  const [profile,grant]=await Promise.all([
    db.doc('users/'+identity.uid).get(), db.doc('accessGrants/'+identity.email).get(),
  ]);
  return approvedActor(identity,profile.data(),grant.data());
}
