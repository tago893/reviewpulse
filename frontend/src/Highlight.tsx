import type { ReactNode } from 'react';
import type { HighlightedText } from './api';

/**
 * Renders server-provided highlight ranges as <mark> elements. Text is rendered as text nodes only,
 * never as HTML, so review content cannot inject markup. Offsets are UTF-16 code units, which is
 * exactly how JavaScript strings index.
 */
export function Highlight({ value }: { value: HighlightedText }) {
  const parts: ReactNode[] = [];
  let cursor = 0;
  value.highlights.forEach((range, i) => {
    if (range.start < cursor || range.end > value.text.length) {
      return;
    }
    if (range.start > cursor) {
      parts.push(value.text.slice(cursor, range.start));
    }
    parts.push(<mark key={i}>{value.text.slice(range.start, range.end)}</mark>);
    cursor = range.end;
  });
  parts.push(value.text.slice(cursor));
  return <>{parts}</>;
}

export function Stars({ rating }: { rating: number }) {
  return (
    <span className="stars" aria-label={`${rating} out of 5 stars`}>
      {'★'.repeat(rating)}
      <span className="stars-off">{'★'.repeat(5 - rating)}</span>
    </span>
  );
}
