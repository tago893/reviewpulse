import { useEffect, useState } from 'react';
import { del, errorText, get, post, put, type Review } from './api';

interface ReplayStatus {
  state: string; file: string | null; position: number; total: number; targetRate: number; achievedRate: number;
  created: number; skippedExisting: number; injectedDuplicates: number; injectedOutOfOrder: number; failures: number;
  lastError: string | null;
}

/** Polls GET /api/reviews/{id} until the index reflects the version, returning the observed delay. */
async function awaitSearchable(id: string, version: number): Promise<number> {
  const start = performance.now();
  for (let i = 0; i < 200; i++) {
    const review = (await get<Review>(`/api/reviews/${id}`)).data as Review;
    if (review.searchable && review.version >= version) return performance.now() - start;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error('not searchable after 10 s; check the status page');
}

function ReplayControls() {
  const [files, setFiles] = useState<string[]>([]);
  const [file, setFile] = useState('');
  const [rate, setRate] = useState(50);
  const [dup, setDup] = useState(0.05);
  const [ooo, setOoo] = useState(0.05);
  const [status, setStatus] = useState<ReplayStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    get<string[]>('/api/admin/replay/files').then((r) => { setFiles(r.data ?? []); setFile((r.data ?? [])[0] ?? ''); })
      .catch((e) => setError(errorText(e)));
    const id = window.setInterval(() => {
      get<ReplayStatus>('/api/admin/replay/status').then((r) => setStatus(r.data)).catch(() => undefined);
    }, 1000);
    return () => window.clearInterval(id);
  }, []);

  async function act(fn: () => Promise<{ data: ReplayStatus | null }>) {
    try { setError(null); setStatus((await fn()).data); } catch (e) { setError(errorText(e)); }
  }

  return (
    <section className="panel">
      <h2>Live replay of real reviews</h2>
      <p className="muted small">Streams a timestamp-sorted slice of not-yet-imported dataset reviews through the outbox → Kafka → index path.
        Duplicate/out-of-order injection exercises the indexer's version checks.</p>
      <div className="filter-row">
        <label>Slice<select value={file} onChange={(e) => setFile(e.target.value)}>{files.map((f) => <option key={f}>{f}</option>)}</select></label>
        <label>Events/s<input type="number" min={1} max={2000} value={rate} onChange={(e) => setRate(Number(e.target.value))} /></label>
        <label>Duplicate rate<input type="number" step={0.05} min={0} max={0.5} value={dup} onChange={(e) => setDup(Number(e.target.value))} /></label>
        <label>Out-of-order rate<input type="number" step={0.05} min={0} max={0.5} value={ooo} onChange={(e) => setOoo(Number(e.target.value))} /></label>
      </div>
      <div className="button-row">
        <button disabled={!file} onClick={() => void act(() => post<ReplayStatus>('/api/admin/replay/start',
          { file, eventsPerSecond: rate, seed: 42, duplicateRate: dup, outOfOrderRate: ooo }))}>Start</button>
        <button className="secondary" onClick={() => void act(() => post<ReplayStatus>('/api/admin/replay/stop'))}>Stop</button>
        <button className="secondary" onClick={() => void act(() => post<ReplayStatus>('/api/admin/replay/resume'))}>Resume</button>
        <button className="secondary" onClick={() => void act(() => put<ReplayStatus>(`/api/admin/replay/rate?eventsPerSecond=${rate}`))}>Apply rate</button>
      </div>
      {files.length === 0 && <p className="muted small">No slices found. Create one with <code>python prepare_dataset.py export-replay …</code></p>}
      {error && <div className="error" role="alert">{error}</div>}
      {status && (
        <p className="small">
          <strong>{status.state}</strong> {status.file ?? ''} · {status.position}/{status.total} · target {status.targetRate}/s, achieved {status.achievedRate}/s ·
          created {status.created}, already present {status.skippedExisting}, injected duplicates {status.injectedDuplicates},
          out-of-order {status.injectedOutOfOrder}, failures {status.failures}{status.lastError ? ` · ${status.lastError}` : ''}
        </p>
      )}
    </section>
  );
}

function ReviewEditor() {
  const [id, setId] = useState('');
  const [review, setReview] = useState<Review | null>(null);
  const [form, setForm] = useState({ parentAsin: 'B0DEMO0001', title: '', body: '', rating: 5 });
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function guarded(label: string, fn: () => Promise<Review | null>) {
    setError(null);
    setMessage(`${label}…`);
    try {
      const updated = await fn();
      if (updated) {
        setReview(updated);
        setId(updated.id);
        const delay = await awaitSearchable(updated.id, updated.version);
        setMessage(`${label} committed (version ${updated.version}); searchable after ${delay.toFixed(0)} ms (browser-observed).`);
        setReview((await get<Review>(`/api/reviews/${updated.id}`)).data);
      }
    } catch (e) {
      setMessage(null);
      setError(errorText(e));
    }
  }

  async function load() {
    try {
      setError(null);
      const r = (await get<Review>(`/api/reviews/${id.trim()}`)).data as Review;
      setReview(r);
      setForm({ parentAsin: r.parentAsin, title: r.title, body: r.body, rating: r.rating });
    } catch (e) { setReview(null); setError(errorText(e)); }
  }

  return (
    <section className="panel">
      <h2>Create, edit or delete a review</h2>
      <div className="filter-row">
        <label>Product ASIN<input value={form.parentAsin} maxLength={10} onChange={(e) => setForm({ ...form, parentAsin: e.target.value.toUpperCase() })} /></label>
        <label>Rating<select value={form.rating} onChange={(e) => setForm({ ...form, rating: Number(e.target.value) })}>{[1, 2, 3, 4, 5].map((r) => <option key={r}>{r}</option>)}</select></label>
      </div>
      <label className="block">Title<input value={form.title} maxLength={500} onChange={(e) => setForm({ ...form, title: e.target.value })} /></label>
      <label className="block">Body<textarea rows={4} maxLength={32000} value={form.body} onChange={(e) => setForm({ ...form, body: e.target.value })} /></label>
      <div className="button-row">
        <button onClick={() => void guarded('Create', async () => (await post<Review>('/api/reviews', { ...form, verifiedPurchase: false })).data)}>Create new</button>
        <input aria-label="Review id" placeholder="review id" value={id} onChange={(e) => setId(e.target.value)} />
        <button className="secondary" disabled={!id.trim()} onClick={() => void load()}>Load</button>
        <button className="secondary" disabled={!review} onClick={() => review && void guarded('Update', async () =>
          (await put<Review>(`/api/reviews/${review.id}`, { expectedVersion: review.version, title: form.title, body: form.body, rating: form.rating })).data)}>Save edit</button>
        <button className="secondary" disabled={!review} onClick={() => review && void guarded('Helpful vote', async () =>
          (await post<Review>(`/api/reviews/${review.id}/helpful`)).data)}>+1 helpful</button>
        <button className="danger" disabled={!review} onClick={async () => {
          if (!review) return;
          try {
            await del(`/api/reviews/${review.id}?expectedVersion=${review.version}`);
            setMessage(`Deleted review ${review.id}. Search for its words: it disappears once the delete is indexed.`);
            setReview(null);
          } catch (e) { setError(errorText(e)); }
        }}>Delete</button>
      </div>
      {message && <p className="ok small">{message}</p>}
      {error && <div className="error" role="alert">{error}</div>}
      {review && <p className="muted small">Loaded <code>{review.id}</code> · version {review.version} · indexed {review.indexedVersion ?? '–'} · {review.searchable ? 'searchable' : 'pending'} · {review.helpfulVotes} helpful</p>}
    </section>
  );
}

export function DemoPage() {
  return (
    <div className="demo">
      <ReviewEditor />
      <ReplayControls />
    </div>
  );
}
