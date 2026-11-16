// Typed client for the ReviewPulse API. Every response uses the same envelope.

export interface ApiError {
  code: string;
  message: string;
  details: string[];
}

export interface Envelope<T> {
  success: boolean;
  data: T | null;
  error: ApiError | null;
  meta: Record<string, unknown>;
}

export interface Range {
  start: number;
  end: number;
}

export interface HighlightedText {
  text: string;
  highlights: Range[];
}

export interface SearchResult {
  id: string;
  parentAsin: string;
  rating: number;
  helpfulVotes: number;
  verifiedPurchase: boolean;
  reviewedAt: string;
  version: number;
  indexedVersion: number;
  score: number;
  title: HighlightedText;
  snippet: HighlightedText;
}

export interface SearchResponse {
  query: { q: string; terms: string[]; operator: string; sort: string };
  totalHits: number;
  results: SearchResult[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface Review {
  id: string;
  source: string;
  parentAsin: string;
  title: string;
  body: string;
  rating: number;
  helpfulVotes: number;
  verifiedPurchase: boolean;
  reviewedAt: string;
  createdAt: string;
  updatedAt: string;
  version: number;
  indexedVersion: number | null;
  searchable: boolean;
}

export class ApiFailure extends Error {
  constructor(
    readonly status: number,
    readonly error: ApiError,
  ) {
    super(error.details.length ? `${error.message}: ${error.details.join('; ')}` : error.message);
  }
}

export async function call<T>(method: string, path: string, body?: unknown): Promise<Envelope<T>> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiFailure(0, { code: 'NETWORK', message: 'Backend unreachable. Is it running on port 8080?', details: [] });
  }
  let envelope: Envelope<T>;
  try {
    envelope = (await response.json()) as Envelope<T>;
  } catch {
    throw new ApiFailure(response.status, { code: 'BAD_RESPONSE', message: `Unexpected response (HTTP ${response.status})`, details: [] });
  }
  if (!response.ok || !envelope.success) {
    throw new ApiFailure(response.status, envelope.error ?? { code: 'UNKNOWN', message: `HTTP ${response.status}`, details: [] });
  }
  return envelope;
}

export const get = <T,>(path: string) => call<T>('GET', path);
export const post = <T,>(path: string, body?: unknown) => call<T>('POST', path, body ?? {});
export const put = <T,>(path: string, body?: unknown) => call<T>('PUT', path, body ?? {});
export const del = <T,>(path: string) => call<T>('DELETE', path);

export function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
