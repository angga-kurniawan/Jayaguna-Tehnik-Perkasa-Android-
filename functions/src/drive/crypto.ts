import {createCipheriv, createDecipheriv, randomBytes} from 'node:crypto';
export type Sealed = {version:1;iv:string;tag:string;ciphertext:string};
export function seal(value: string, key: Buffer, purpose: string): Sealed {
  if (key.length !== 32) throw new Error('Kunci enkripsi harus 32 byte.');
  const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm',key,iv);
  cipher.setAAD(Buffer.from('jayaguna-drive:'+purpose));
  const encrypted = Buffer.concat([cipher.update(value,'utf8'),cipher.final()]);
  return {version:1,iv:iv.toString('base64'),tag:cipher.getAuthTag().toString('base64'),ciphertext:encrypted.toString('base64')};
}
export function unseal(value: Sealed, key: Buffer, purpose: string): string {
  if (value.version !== 1) throw new Error('Versi enkripsi tidak didukung.');
  const cipher = createDecipheriv('aes-256-gcm',key,Buffer.from(value.iv,'base64'));
  cipher.setAAD(Buffer.from('jayaguna-drive:'+purpose));
  cipher.setAuthTag(Buffer.from(value.tag,'base64'));
  return Buffer.concat([cipher.update(Buffer.from(value.ciphertext,'base64')),cipher.final()]).toString('utf8');
}
