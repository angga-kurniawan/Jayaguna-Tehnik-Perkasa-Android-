import sharp from 'sharp';
import {createHash} from 'node:crypto';
import {HttpsError} from 'firebase-functions/v2/https';
export const MAX_BYTES = 5*1024*1024;
export const IMAGE_TYPES = ['image/png','image/jpeg','image/webp'];
export function imageSignature(raw: Buffer, mime: string): boolean {
  if (mime === 'image/png') return raw.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10]));
  if (mime === 'image/jpeg') return raw[0]===255 && raw[1]===216 && raw[2]===255;
  return mime==='image/webp' && raw.subarray(0,4).toString()==='RIFF' && raw.subarray(8,12).toString()==='WEBP';
}
export async function normalizeImage(raw: Buffer, mime: string) {
  if (!raw.length || raw.length>MAX_BYTES || !imageSignature(raw,mime)) throw new HttpsError('invalid-argument','Gunakan PNG, JPEG, atau WebP valid dengan ukuran maksimal 5 MiB.');
  const extension = mime==='image/png'?'png':mime==='image/jpeg'?'jpeg':'webp';
  let bytes: Buffer;
  try {
    bytes = await sharp(raw,{limitInputPixels:40_000_000,animated:false,failOn:'warning'}).rotate()
      .resize({width:4096,height:4096,fit:'inside',withoutEnlargement:true})
      .toFormat(extension,extension==='png'?{compressionLevel:9}:{quality:85}).toBuffer();
  } catch { throw new HttpsError('invalid-argument','Gambar rusak atau dimensinya terlalu besar.'); }
  if (bytes.length>MAX_BYTES) throw new HttpsError('invalid-argument','Hasil gambar melebihi 5 MiB; kompres gambar terlebih dahulu.');
  return {bytes,extension,mimeType:mime,sha256:createHash('sha256').update(bytes).digest('hex')};
}
