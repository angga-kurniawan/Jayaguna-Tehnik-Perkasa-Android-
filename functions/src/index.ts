import { setGlobalOptions } from "firebase-functions/v2";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as service from "./service";

setGlobalOptions({region: "asia-southeast2", maxInstances: 10, timeoutSeconds: 120, memory: "512MiB"});
const options = {enforceAppCheck: process.env.ENFORCE_APP_CHECK === "true" && process.env.FUNCTIONS_EMULATOR !== "true"};
function callable(handler: (request: any) => Promise<any>) {
  return onCall(options, async request => {
    try { return await handler(request); }
    catch (error) {
      if (error instanceof HttpsError) throw error;
      // Avoid exposing object contents, credentials, tokens, or SDK internals.
      throw new HttpsError("internal", "Operasi belum dapat diselesaikan. Coba lagi dengan requestId yang sama.");
    }
  });
}
export const getSession = callable(service.getSession);
export const beginUpload = callable(service.beginUpload);
export const saveProduct = callable(r => service.saveEntity(r, "product"));
export const savePartner = callable(r => service.saveEntity(r, "partner"));
export const archiveProduct = callable(r => service.archiveEntity(r, "product"));
export const archivePartner = callable(r => service.archiveEntity(r, "partner"));
export const getDashboard = callable(service.getDashboard);
export const manageAdmin = callable(service.manageAdmin);
export const cleanupUploads = onSchedule({schedule: "every 24 hours", timeZone: "Asia/Jakarta"}, service.cleanupStaging);
