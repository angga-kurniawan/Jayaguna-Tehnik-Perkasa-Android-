import test from "node:test";
import assert from "node:assert/strict";
import { nextWindow, PAGE_SIZE, MAX_WINDOW, rupiah, safeWebsite, mainImage, mediaPath, driveMediaUrl, catalogError } from "../helpers.js";

test("pagination grows a bounded realtime window", () => {
  assert.equal(nextWindow(PAGE_SIZE), 24);
  assert.equal(nextWindow(MAX_WINDOW), MAX_WINDOW);
});
test("price handles missing, zero and invalid values", () => {
  assert.match(rupiah(0), /Rp/);
  assert.match(rupiah(1500000), /1\.500\.000/);
  for (const value of [null, undefined, NaN, -1, "0"]) assert.equal(rupiah(value), "Hubungi kami untuk harga");
});
test("partner links reject script/data/plain HTTP and malformed protocols", () => {
  for (const value of ["javascript:alert(1)", "data:text/html,hi", "http://example.com", "https://user:password@example.com", "no-url", null, ""]) assert.equal(safeWebsite(value), null);
  assert.equal(safeWebsite("https://example.com/path"), "https://example.com/path");
});
test("main image honors saved order and handles empty collections", () => {
  const images = [{ storagePath: "a" }, { storagePath: "b" }];
  assert.equal(mainImage({ images, mainImagePath: "b" }), images[1]);
  assert.equal(mainImage({ images }), images[0]);
  assert.equal(mainImage({}), null);
});
test("storage path stays in the public entity scope and never accepts token URLs", () => {
  assert.equal(mediaPath("media/product/id/a.webp", "product", "id"), "media/product/id/a.webp");
  for (const path of ["staging/uid/file", "media/product/other/a.webp", "media/product/id/../a", "https://example.com/image?token=secret"]) assert.equal(mediaPath(path, "product", "id"), null);
});
test("errors provide Indonesian feedback without raw backend internals", () => {
  assert.match(catalogError({ code: "unavailable" }), /Koneksi/);
  assert.doesNotMatch(catalogError({ message: "secret" }), /secret/);
});
test("Drive images derive only from a configured backend and opaque UUID",()=>{
  const media={storagePath:'drive/11111111-1111-4111-8111-111111111111',url:'https://evil.test/token'};
  assert.equal(driveMediaUrl(media,'https://api.example.com'),'https://api.example.com/api/media/11111111-1111-4111-8111-111111111111');
  for(const url of ['http://api.example.com','https://user:secret@api.example.com','https://api.example.com/path','https://api.example.com?token=secret'])assert.equal(driveMediaUrl(media,url),null);
  assert.equal(driveMediaUrl({storagePath:'drive/../../secret'},'https://api.example.com'),null);
});
