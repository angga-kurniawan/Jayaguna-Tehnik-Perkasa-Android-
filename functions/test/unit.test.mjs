import {test} from 'node:test';
import assert from 'node:assert/strict';
import schema from '../lib/schema.js';
const {productSchema,partnerSchema,deviceSchema,adminSchema,fingerprint,changed,publicProjection,searchTokens}=schema;
const product={name:'AC Hemat Energi',brand:'Daikin',model:'FTK',category:'split',capacityPk:'1',condition:'new',price:null,description:'Pendingin ruang',specifications:{Daya:'350 W'},images:[],mainImagePath:'',status:'draft'};
test('produk aktif tanpa gambar, harga negatif, kategori asing dan field privilege ditolak',()=>{
  assert.equal(productSchema.safeParse(product).success,true);
  for(const patch of [{status:'active'},{price:-1},{category:'unknown'},{role:'OWNER'}]) assert.equal(productSchema.safeParse({...product,...patch}).success,false);
});
test('foto utama harus ada dalam daftar unik',()=>{
  const image={storagePath:'staging/a/b/image.png',url:''};
  assert.equal(productSchema.safeParse({...product,images:[image],mainImagePath:'wrong'}).success,false);
  assert.equal(productSchema.safeParse({...product,images:[image,image],mainImagePath:image.storagePath}).success,false);
});
test('logo wajib, website hanya HTTPS tanpa kredensial',()=>{
  const partner={name:'Mitra',logo:{storagePath:'media/partner/a/b.png',url:''},altText:'Logo Mitra',website:'https://example.com',displayOrder:0,active:true};
  assert.equal(partnerSchema.safeParse(partner).success,true);
  for(const website of ['javascript:alert(1)','http://example.com','https://user:pass@example.com']) assert.equal(partnerSchema.safeParse({...partner,website}).success,false);
});
test('metadata perangkat bukan tempat kredensial dan memerlukan UUID instalasi',()=>{
  assert.equal(deviceSchema.safeParse({installationId:'IMEI-123',deviceName:'Kantor',brand:'A',model:'B',androidVersion:'15',appVersion:'1'}).success,false);
});
test('fingerprint stabil terhadap urutan key, berbeda untuk isi lain',()=>{
  assert.equal(fingerprint({a:1,b:{c:2}}),fingerprint({b:{c:2},a:1}));
  assert.notEqual(fingerprint({a:1}),fingerprint({a:2}));
});
test('proyeksi publik tidak membawa UID, metadata perangkat, audit atau draft',()=>{
  const active={...product,status:'active',images:[{storagePath:'media/product/a/image.png',url:''}],mainImagePath:'media/product/a/image.png',createdBy:'secret',updatedBy:'secret',device:{installationId:'secret'},createdAt:1,updatedAt:2,archived:false};
  const p=publicProjection('product','a',active);
  assert.equal(p.status,'active'); assert.equal(p.createdBy,undefined);assert.equal(p.device,undefined);
  assert.deepEqual(p.mediaPaths,['media/product/a/image.png']);
  assert.equal(publicProjection('product','a',{...active,status:'draft'}),null);
  assert.equal(publicProjection('product','a',{...active,archived:true}),null);
});
test('diff mencatat nilai sebelum sesudah tanpa perubahan palsu urutan key',()=>{
  const d=changed({name:'Lama',spec:{a:'1',b:'2'}},{name:'Baru',spec:{b:'2',a:'1'}});
  assert.deepEqual(d.changedFields,['name']);assert.deepEqual(d.before,{name:'Lama'});assert.deepEqual(d.after,{name:'Baru'});
});
test('operasi admin tidak menerima role dan membutuhkan target',()=>{
  const context={requestId:'4e2f8cd5-aac0-4b24-a07a-ff2e716df910',device:{installationId:'4e2f8cd5-aac0-4b24-a07a-ff2e716df911',deviceName:'Kantor',brand:'A',model:'B',androidVersion:'15',appVersion:'1'}};
  assert.equal(adminSchema.safeParse({...context,operation:'create',name:'Admin',email:'admin@example.com',role:'OWNER'}).success,false);
  assert.equal(adminSchema.safeParse({...context,operation:'setActive',active:false}).success,false);
});
test('pencarian prefix nama/merek dibatasi dan bebas duplikat',()=>{
  const tokens=searchTokens('AC Daikin Split','Daikin');
  assert.ok(tokens.includes('daik'));assert.ok(tokens.includes('ac dai'));assert.ok(tokens.includes('spl'));
  assert.equal(tokens.length,new Set(tokens).size);assert.ok(tokens.length<=1000);
});
