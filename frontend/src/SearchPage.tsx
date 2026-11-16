import { useEffect, useState } from 'react';
import { ApiFailure, errorText, get, type Review, type SearchResponse, type SearchResult } from './api';
import { Highlight, Stars } from './Highlight';

interface Filters {
  q: string;
  operator: 'AND' | 'OR';
  ratingMin: string;
  ratingMax: string;
  verified: '' | 'true' | 'false';
  parentAsin: string;
  sort: 'relevance' | 'recency' | 'helpfulness';
}

interface PageMeta {
  tookMicros?: number;
  indexMicros?: number;
  cache?: string;
  indexState?: string;
  indexLagEvents?: number;
  staleHitsDropped?: number;
  warning?: string;
}

const INITIAL: Filters = { q: 'dry skin', operator: 'AND', ratingMin: '', ratingMax: '', verified: '', parentAsin: '', sort: 'relevance' };

function queryString(f: Filters, cursor: string | null): string {
  const p = new URLSearchParams({ q: f.q, operator: f.operator, sort: f.sort, pageSize: '10' });
  if (f.ratingMin) p.set('ratingMin', f.ratingMin);
  if (f.ratingMax) p.set('ratingMax', f.ratingMax);
  if (f.verified) p.set('verifiedPurchase', f.verified);
  if (f.parentAsin.trim()) p.set('parentAsin', f.parentAsin.trim().toUpperCase());
  if (cursor) p.set('cursor', cursor);
  return p.toString();
}

/** Reads ?q=&operator=&sort= so searches are shareable links. */
function filtersFromUrl(): Filters {
  const p = new URLSearchParams(window.location.search);
  const operator = p.get('operator') === 'OR' ? 'OR' : 'AND';
  const sortParam = p.get('sort');
  const sort = sortParam === 'recency' || sortParam === 'helpfulness' ? sortParam : 'relevance';
  return { ...INITIAL, q: p.get('q') ?? INITIAL.q, operator, sort };
}

export function SearchPage() {
  const [filters, setFilters] = useState<Filters>(filtersFromUrl);
  const [results, setResults] = useState<SearchResult[]>([]);
  const [response, setResponse] = useState<SearchResponse | null>(null);
  const [meta, setMeta] = useState<PageMeta>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Review | null>(null);

  const set = <K extends keyof Filters>(key: K, value: Filters[K]) => setFilters({ ...filters, [key]: value });

  async function run(cursor: string | null) {
    setLoading(true);
    setError(null);
    try {
      const env = await get<SearchResponse>(`/api/search?${queryString(filters, cursor)}`);
      const data = env.data as SearchResponse;
      setResponse(data);
      setResults(cursor ? [...results, ...data.results] : data.results);
      setMeta(env.meta as PageMeta);
    } catch (e) {
      setError(e instanceof ApiFailure && e.status === 429 ? 'Rate limited, slow down a little.' : errorText(e));
      if (!cursor) setResults([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (new URLSearchParams(window.location.search).has('q')) {
      void run(null);
    }
    // Run once on mount for shared links; later searches are explicit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function open(id: string) {
    try {
      setSelected((await get<Review>(`/api/reviews/${id}`)).data);
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <div className="search-layout">
      <form className="panel filters" onSubmit={(e) => { e.preventDefault(); void run(null); }}>
        <div className="search-row">
          <input aria-label="Search reviews" value={filters.q} maxLength={256} placeholder="Search reviews, e.g. not greasy moisturizer"
                 onChange={(e) => set('q', e.target.value)} />
          <button type="submit" disabled={loading || !filters.q.trim()}>{loading ? 'Searching…' : 'Search'}</button>
        </div>
        <div className="filter-row">
          <label>Match
            <select value={filters.operator} onChange={(e) => set('operator', e.target.value as Filters['operator'])}>
              <option value="AND">all terms</option><option value="OR">any term</option>
            </select>
          </label>
          <label>Sort
            <select value={filters.sort} onChange={(e) => set('sort', e.target.value as Filters['sort'])}>
              <option value="relevance">relevance (BM25F)</option><option value="recency">newest</option><option value="helpfulness">most helpful</option>
            </select>
          </label>
          <label>Rating ≥
            <select value={filters.ratingMin} onChange={(e) => set('ratingMin', e.target.value)}>
              <option value="">any</option>{[1, 2, 3, 4, 5].map((r) => <option key={r}>{r}</option>)}
            </select>
          </label>
          <label>Rating ≤
            <select value={filters.ratingMax} onChange={(e) => set('ratingMax', e.target.value)}>
              <option value="">any</option>{[1, 2, 3, 4, 5].map((r) => <option key={r}>{r}</option>)}
            </select>
          </label>
          <label>Purchase
            <select value={filters.verified} onChange={(e) => set('verified', e.target.value as Filters['verified'])}>
              <option value="">any</option><option value="true">verified</option><option value="false">unverified</option>
            </select>
          </label>
          <label>Product ASIN
            <input value={filters.parentAsin} maxLength={10} placeholder="B00…" onChange={(e) => set('parentAsin', e.target.value)} />
          </label>
        </div>
      </form>

      {error && <div className="error" role="alert">{error}</div>}

      {response && (
        <div className="result-summary">
          <strong>{response.totalHits.toLocaleString()}</strong> matches for <code>{response.query.terms.join(' ') || '(no terms)'}</code>
          <span className="muted"> · {((meta.tookMicros ?? 0) / 1000).toFixed(1)} ms total, {((meta.indexMicros ?? 0) / 1000).toFixed(2)} ms index · cache {meta.cache} · index {meta.indexState}
            {meta.indexLagEvents ? ` · ${meta.indexLagEvents} events behind` : ''}{meta.staleHitsDropped ? ` · ${meta.staleHitsDropped} stale hit(s) hidden` : ''}</span>
          {meta.warning && <div className="muted">{meta.warning}</div>}
        </div>
      )}

      {response && results.length === 0 && !loading && <div className="empty">No reviews match. Try "any term" or fewer filters.</div>}

      <ol className="results">
        {results.map((r) => (
          <li key={r.id} className="panel result" onClick={() => void open(r.id)}>
            <div className="result-head">
              <Stars rating={r.rating} />
              <span className="title"><Highlight value={r.title} /></span>
            </div>
            <p className="snippet"><Highlight value={r.snippet} /></p>
            <div className="muted small">
              {new Date(r.reviewedAt).toLocaleDateString()} · {r.helpfulVotes} helpful · {r.verifiedPurchase ? 'verified purchase' : 'unverified'} · product {r.parentAsin} · score {r.score.toFixed(3)}
              {r.indexedVersion < r.version ? ' · newer version indexing…' : ''}
            </div>
          </li>
        ))}
      </ol>

      {response?.hasMore && (
        <button className="secondary" disabled={loading} onClick={() => void run(response.nextCursor)}>{loading ? 'Loading…' : 'Load more'}</button>
      )}

      {selected && (
        <div className="modal" role="dialog" aria-modal="true" onClick={() => setSelected(null)}>
          <div className="panel modal-body" onClick={(e) => e.stopPropagation()}>
            <button className="close secondary" onClick={() => setSelected(null)}>Close</button>
            <h2>{selected.title || '(no title)'}</h2>
            <Stars rating={selected.rating} />
            <p className="body-text">{selected.body}</p>
            <dl className="meta-grid">
              <dt>Review id</dt><dd><code>{selected.id}</code></dd>
              <dt>Product</dt><dd>{selected.parentAsin}</dd>
              <dt>Reviewed</dt><dd>{new Date(selected.reviewedAt).toLocaleString()}</dd>
              <dt>Helpful votes</dt><dd>{selected.helpfulVotes}</dd>
              <dt>Version</dt><dd>{selected.version} (indexed: {selected.indexedVersion ?? '–'}, {selected.searchable ? 'searchable' : 'pending'})</dd>
              <dt>Source</dt><dd>{selected.source}</dd>
            </dl>
          </div>
        </div>
      )}
    </div>
  );
}
