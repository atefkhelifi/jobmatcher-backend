// Serves the production build of the Angular app and proxies /api to the API, like the nginx container does.
import { createServer, request } from 'node:http';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'frontend', 'dist', 'frontend', 'browser');
if (!existsSync(join(root, 'index.html'))) {
  console.error(`Missing ${root}. Run: cd frontend && npx ng build`);
  process.exit(1);
}
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.ico': 'image/x-icon', '.json': 'application/json' };

createServer((req, res) => {
  if (req.url.startsWith('/api/')) {
    const headers = { ...req.headers, host: '127.0.0.1:8081', 'x-forwarded-for': req.socket.remoteAddress };
    const upstream = request({ host: '127.0.0.1', port: 8081, path: req.url, method: req.method, headers }, (up) => {
      res.writeHead(up.statusCode ?? 502, up.headers);
      up.pipe(res);
    });
    upstream.on('error', () => res.writeHead(502).end());
    req.pipe(upstream);
    return;
  }
  const clean = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname)).replace(/^(\.\.[/\\])+/, '');
  let file = join(root, clean);
  if (!file.startsWith(root) || !existsSync(file) || statSync(file).isDirectory()) {
    file = join(root, 'index.html'); // single-page app fallback
  }
  res.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' }).end(readFileSync(file));
}).listen(4300, '127.0.0.1', () => console.log('web on :4300'));
