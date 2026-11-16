import { useEffect, useState } from 'react';
import { errorText, get } from './api';

interface Timing { count: number; p50Millis?: number; p95Millis?: number; p99Millis?: number }

interface Summary {
  uptimeSeconds: number;
  reviews: { databaseLive: number; indexedLive: number; outboxPending: number; outboxOldestPendingMillis: number };
  search: Timing;
  searchIndexOnly: Timing;
  indexing: {
    state: string; detail: string; generation: number; eventsConsumed: number; eventsPerSecond: number;
    kafkaReachable: boolean; lagEvents: number; delayP50Millis: number; delayP95Millis: number; delayP99Millis: number;
    deadLettered: number; outcomes: Record<string, number>; lastCheckpointAt: string | null;
  };
  cache: { enabled: boolean; hits: number; misses: number; hitRate: number; circuitOpen: boolean };
}

interface Health { status: string; components: Record<string, { status: string }> }

const ms = (v: number | undefined) => (v === undefined || v < 0 ? '–' : `${v.toFixed(v < 10 ? 2 : 0)} ms`);

function Card({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="panel card">
      <div className="card-label">{label}</div>
      <div className="card-value">{value}</div>
      {hint && <div className="muted small">{hint}</div>}
    </div>
  );
}

export function StatusPage() {
  const [summary, setSummary] = useState<Summary | null>(null);
  const [health, setHealth] = useState<Health | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    async function poll() {
      try {
        const [s, h] = await Promise.all([get<Summary>('/api/metrics/summary'), get<Health>('/api/health').catch(() => null)]);
        if (!active) return;
        setSummary(s.data);
        setHealth(h?.data ?? null);
        setError(null);
      } catch (e) {
        if (active) setError(errorText(e));
      }
    }
    void poll();
    const id = window.setInterval(() => void poll(), 2000);
    return () => { active = false; window.clearInterval(id); };
  }, []);

  if (error && !summary) return <div className="error" role="alert">{error}</div>;
  if (!summary) return <div className="empty">Loading system status…</div>;
  const i = summary.indexing;
  return (
    <div>
      {error && <div className="error" role="alert">{error} (showing last known values)</div>}
      <div className={`panel state state-${(health?.status ?? i.state).toLowerCase()}`}>
        <strong>System {health?.status ?? 'UNKNOWN'}</strong> · index {i.state}: {i.detail}
        {health && <span className="muted"> · {Object.entries(health.components).map(([k, v]) => `${k} ${v.status}`).join(' · ')}</span>}
      </div>
      <div className="cards">
        <Card label="Reviews in PostgreSQL" value={summary.reviews.databaseLive.toLocaleString()} />
        <Card label="Reviews indexed" value={summary.reviews.indexedLive.toLocaleString()} hint={`generation ${i.generation}`} />
        <Card label="Search latency p50 / p95" value={`${ms(summary.search.p50Millis)} / ${ms(summary.search.p95Millis)}`}
              hint={`p99 ${ms(summary.search.p99Millis)} · ${summary.search.count} searches (end to end)`} />
        <Card label="Index-only p50 / p95" value={`${ms(summary.searchIndexOnly.p50Millis)} / ${ms(summary.searchIndexOnly.p95Millis)}`} />
        <Card label="Indexing throughput" value={`${i.eventsPerSecond} events/s`} hint={`${i.eventsConsumed.toLocaleString()} consumed`} />
        <Card label="Backlog" value={`${i.lagEvents < 0 ? '?' : i.lagEvents} Kafka · ${summary.reviews.outboxPending} outbox`}
              hint={i.kafkaReachable ? 'Kafka reachable' : 'Kafka unreachable'} />
        <Card label="Write → searchable p50 / p95"
              value={i.eventsConsumed === 0 ? 'no events yet' : `${ms(i.delayP50Millis)} / ${ms(i.delayP95Millis)}`}
              hint={i.eventsConsumed === 0 ? 'create a review or start a replay' : `p99 ${ms(i.delayP99Millis)}`} />
        <Card label="Cache hit rate" value={summary.cache.enabled ? `${(summary.cache.hitRate * 100).toFixed(1)} %` : 'disabled'}
              hint={summary.cache.circuitOpen ? 'Redis bypassed (unavailable)' : `${summary.cache.hits} hits / ${summary.cache.misses} misses`} />
        <Card label="Dead-lettered events" value={String(i.deadLettered)}
              hint={`stale/duplicate ignored: ${i.outcomes.STALE_IGNORED ?? 0}`} />
        <Card label="Last checkpoint" value={i.lastCheckpointAt ? new Date(i.lastCheckpointAt).toLocaleTimeString() : 'none'} />
      </div>
    </div>
  );
}
