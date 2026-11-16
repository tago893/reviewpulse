import { useState } from 'react';
import { DemoPage } from './DemoPage';
import { SearchPage } from './SearchPage';
import { StatusPage } from './StatusPage';

type Tab = 'search' | 'status' | 'demo';

const TABS: { id: Tab; label: string }[] = [
  { id: 'search', label: 'Search' },
  { id: 'status', label: 'System status' },
  { id: 'demo', label: 'Demo controls' },
];

function initialTab(): Tab {
  const requested = new URLSearchParams(window.location.search).get('tab');
  return TABS.some((t) => t.id === requested) ? (requested as Tab) : 'search';
}

export function App() {
  const [tab, setTab] = useState<Tab>(initialTab);
  return (
    <div className="app">
      <header className="header">
        <div>
          <h1>ReviewPulse</h1>
          <p className="muted small">Real-time search over Amazon Reviews 2023 (All_Beauty) with a custom inverted index</p>
        </div>
        <nav className="tabs" role="tablist">
          {TABS.map((t) => (
            <button key={t.id} role="tab" aria-selected={tab === t.id} className={tab === t.id ? 'tab active' : 'tab'}
                    onClick={() => setTab(t.id)}>{t.label}</button>
          ))}
        </nav>
      </header>
      <main>
        {tab === 'search' && <SearchPage />}
        {tab === 'status' && <StatusPage />}
        {tab === 'demo' && <DemoPage />}
      </main>
    </div>
  );
}
