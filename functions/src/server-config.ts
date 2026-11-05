import {AppOptions,cert} from 'firebase-admin/app';

/** Server-only configuration for hosts with environment-based secret storage. */
export function serverAppOptions(environment:NodeJS.ProcessEnv=process.env):AppOptions {
  const projectId=environment.FIREBASE_PROJECT_ID || 'jayagunatehnikperkasa';
  const encoded=environment.FIREBASE_SERVICE_ACCOUNT_JSON;
  if(!encoded)return {projectId}; // ADC / attached IAM / external credential file.
  try {
    const value=JSON.parse(encoded);
    if(value?.type!=='service_account' || value.project_id!==projectId || typeof value.client_email!=='string' ||
      !value.client_email.endsWith('@'+projectId+'.iam.gserviceaccount.com') || typeof value.private_key!=='string') throw new Error();
    return {projectId,credential:cert(value)};
  }catch {
    // JSON/PEM exceptions can contain part of the input. Never propagate them.
    throw new Error('Credential Firebase server tidak valid atau project tidak cocok. Periksa secret FIREBASE_SERVICE_ACCOUNT_JSON.');
  }
}
