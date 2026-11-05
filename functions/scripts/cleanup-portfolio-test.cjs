// Guarded cleanup for the explicitly opted-in NativePortfolioCloudTest only.
const fs = require('node:fs');
const {createHash} = require('node:crypto');
const {initializeApp, deleteApp} = require('firebase-admin/app');
const {getFirestore} = require('firebase-admin/firestore');
const {serverAppOptions} = require('../lib/server-config');
const {DriveApi} = require('../lib/drive/transport');
const OWNER = 'S28l85ry6EhyEzg5F4c6oweNyJC3';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const TYPES = {
  residential: {type: 'portfolioResidential', folder: '1kOqO3wgNmUCujrhb1tqY3kB_kG7jTEjx'},
  corporate: {type: 'portfolioCorporate', folder: '10Z9US8nxqTV5AkJrRrEURRsWNhOpvHvK'},
};
let app, stage = 'report';
async function main() {
  const at = process.argv.indexOf('--report');
  if (at < 0) throw new Error('report-required');
  const report = JSON.parse(fs.readFileSync(process.argv[at + 1], 'utf8').replace(/^\uFEFF/, ''));
  if (!Array.isArray(report.portfolioEntries) || report.portfolioEntries.length !== 2) throw new Error('invalid-report');
  const seen = new Set();
  for (const e of report.portfolioEntries) {
    if (!UUID.test(e.id) || !TYPES[e.category] || seen.has(e.category) ||
        !Array.isArray(e.requests) || e.requests.length > 3 || !e.requests.every(v => UUID.test(v)) ||
        !Array.isArray(e.mediaIds) || e.mediaIds.length > 2 || !e.mediaIds.every(v => UUID.test(v))) throw new Error('invalid-entry');
    seen.add(e.category);
  }
  const options = serverAppOptions();
  if (options.projectId !== 'jayagunatehnikperkasa') throw new Error('wrong-project');
  app = initializeApp(options);
  const db = getFirestore(app), drive = new DriveApi(), records = new Map();
  let mediaCount = 0;
  const add = ref => records.set(ref.path, ref);
  for (const e of report.portfolioEntries) {
    const expected = TYPES[e.category], ref = db.doc('portfolioImages/' + e.id);
    stage = 'catalog-ownership';
    const item = await ref.get();
    if (item.exists && (item.get('createdBy') !== OWNER || item.get('category') !== e.category ||
        !e.requests.includes(item.get('lastRequestId')) || item.get('version') > 3)) throw new Error('not-test-entry');
    const assets = await db.collection('mediaAssets').where('entityId', '==', e.id).get();
    if (assets.size > 2) throw new Error('unexpected-assets');
    for (const doc of assets.docs) {
      const a = doc.data();
      if (a.createdBy !== OWNER || a.source !== 'android' || a.entityType !== expected.type || a.folder !== expected.folder ||
          a.expectedMime !== 'image/png' || !Number.isInteger(a.expectedBytes) || a.expectedBytes < 1 || a.expectedBytes > 1024) throw new Error('not-test-media');
      stage = 'temporary-file-cleanup';
      try {
        const file = await drive.metadata(a.driveFileId);
        if (!file.parents?.includes(expected.folder)) throw new Error('wrong-parent');
        if (!file.trashed) {
          const bytes = await drive.read(a.driveFileId);
          if (bytes.length > 1024 || createHash('sha256').update(bytes).digest('hex') !== (a.sha256 || a.originalSha256)) throw new Error('wrong-bytes');
          await drive.trash(a.driveFileId);
        }
      } catch (error) { if (error.code !== 'not-found') throw error; }
      add(doc.ref);
      add(db.doc('driveUploadRequests/' + OWNER + '_' + a.requestId));
      const audits = await db.collection('audit_logs').where('mediaId', '==', doc.id).get();
      for (const audit of audits.docs) {
        if (audit.get('userId') !== OWNER || audit.get('targetId') !== e.id || audit.get('entityType') !== expected.type) throw new Error('not-test-audit');
        add(audit.ref);
      }
      mediaCount++;
    }
    stage = 'temporary-record-cleanup';
    const logs = await db.collection('activityLogs').where('entityId', '==', e.id).get();
    for (const log of logs.docs) {
      if (log.get('actorUid') !== OWNER || log.get('entityType') !== expected.type || !e.requests.includes(log.get('requestId'))) throw new Error('not-test-log');
      add(log.ref);
    }
    for (const request of e.requests) {
      const receipt = db.doc('operationReceipts/' + OWNER + '_' + request), doc = await receipt.get();
      if (doc.exists) {
        if (doc.get('entityId') !== e.id || doc.get('entityType') !== expected.type) throw new Error('not-test-receipt');
        add(receipt);
      }
    }
    add(ref); add(db.doc('publicPortfolioImages/' + e.id));
  }
  const batch = db.batch();
  for (const ref of records.values()) batch.delete(ref);
  await batch.commit();
  console.log(JSON.stringify({cleaned: true, temporaryEntries: 2, temporaryMedia: mediaCount,
    userCatalogUnchanged: true, testCompleted: report.completed === true}));
}
main().catch(() => { console.error(JSON.stringify({cleaned: false, stage,
  message: 'Pembersihan dihentikan; data di luar ID laporan tidak diubah.'})); process.exitCode = 1; })
  .finally(async () => { if (app) await deleteApp(app); });
