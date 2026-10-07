import { DatabaseSync } from 'node:sqlite';
import type { AuditEvent } from './event.js';

export class EventConflict extends Error {}
export class AuditStore {
  private db: DatabaseSync;
  constructor(path: string) {
    this.db = new DatabaseSync(path);
    this.db.exec(`PRAGMA journal_mode=WAL;
      CREATE TABLE IF NOT EXISTS events (
        sequence INTEGER PRIMARY KEY AUTOINCREMENT,
        event_id TEXT NOT NULL UNIQUE,
        payload TEXT NOT NULL,
        recorded_at TEXT NOT NULL
      );`);
  }
  ready(): void { this.db.prepare('SELECT 1').get(); }
  record(event: AuditEvent): { created: boolean; event: AuditEvent & { recordedAt: string } } {
    const payload = JSON.stringify(event);
    const existing = this.db.prepare('SELECT payload, recorded_at FROM events WHERE event_id = ?').get(event.eventId);
    if (existing) {
      if (existing.payload !== payload) throw new EventConflict();
      return { created: false, event: { ...event, recordedAt: existing.recorded_at as string } };
    }
    const recordedAt = new Date().toISOString();
    this.db.prepare('INSERT INTO events (event_id, payload, recorded_at) VALUES (?, ?, ?)').run(event.eventId, payload, recordedAt);
    return { created: true, event: { ...event, recordedAt } };
  }
  list(): unknown[] {
    return this.db.prepare('SELECT payload, recorded_at FROM events ORDER BY sequence DESC LIMIT 100').all()
      .map(row => ({ ...JSON.parse(row.payload as string), recordedAt: row.recorded_at }));
  }
  close(): void { this.db.close(); }
}
