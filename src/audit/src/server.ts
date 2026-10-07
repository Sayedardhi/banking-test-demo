import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { normalizeEvent, InvalidEvent } from './event.js';
import { AuditStore, EventConflict } from './store.js';

const token = process.env.AUDIT_TOKEN;
if (!token) throw new Error('AUDIT_TOKEN is required');
const store = new AuditStore(process.env.AUDIT_DB_PATH ?? '/data/audit.sqlite');
const reply = (res: ServerResponse, status: number, data: unknown) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(data));
};
function authorized(req: IncomingMessage): boolean {
  const supplied = Buffer.from(req.headers.authorization ?? '');
  const expected = Buffer.from(`Bearer ${token}`);
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}
const server = createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') {
      store.ready(); return reply(res, 200, { status: 'ok' });
    }
    if (!authorized(req)) return reply(res, 401, { error: 'Unauthorized' });
    if (req.url !== '/events') return reply(res, 404, { error: 'Not found' });
    if (req.method === 'GET') return reply(res, 200, { events: store.list() });
    if (req.method !== 'POST') return reply(res, 405, { error: 'Method not allowed' });
    if (req.headers['content-type']?.split(';')[0].trim() !== 'application/json') {
      return reply(res, 415, { error: 'Expected application/json' });
    }
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const chunk of req) {
      size += chunk.length;
      if (size > 16384) { reply(res, 413, { error: 'Event too large' }); return; }
      chunks.push(Buffer.from(chunk));
    }
    let input: unknown;
    try { input = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
    catch { return reply(res, 400, { error: 'Invalid JSON' }); }
    const result = store.record(normalizeEvent(input));
    return reply(res, result.created ? 201 : 200, result);
  } catch (error) {
    if (error instanceof InvalidEvent) return reply(res, 400, { error: error.message });
    if (error instanceof EventConflict) return reply(res, 409, { error: 'Event ID conflicts with an existing record' });
    // Avoid leaking payloads, credentials, or database details into logs/responses.
    console.error('Audit storage operation failed');
    return reply(res, 503, { error: 'Audit storage unavailable' });
  }
});
server.requestTimeout = 10000;
server.listen(Number(process.env.PORT ?? 8080), '0.0.0.0', () => console.info('Audit service ready'));
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => server.close(() => { store.close(); process.exit(0); }));
}
