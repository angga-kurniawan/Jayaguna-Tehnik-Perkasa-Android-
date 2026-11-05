// Minimal local static preview, no dependency installation needed. Not a production server.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { resolve, extname, sep } from "node:path";

const root = fileURLToPath(new URL(".", import.meta.url));
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json" };
createServer(async (request, response) => {
  try {
    const path = resolve(root, `.${decodeURIComponent(new URL(request.url, "http://localhost").pathname === "/" ? "/index.html" : new URL(request.url, "http://localhost").pathname)}`);
    if (!path.startsWith(root.endsWith(sep) ? root : `${root}${sep}`) || path.includes(`${sep}.`)) {
      response.writeHead(403).end();
      return;
    }
    const data = await readFile(path);
    response.writeHead(200, { "Content-Type": `${types[extname(path)] || "application/octet-stream"}; charset=utf-8`, "Cache-Control": "no-store" }).end(data);
  } catch { response.writeHead(404).end("File tidak ditemukan"); }
}).listen(5173, "127.0.0.1", () => process.stdout.write("Katalog: http://localhost:5173\n"));
