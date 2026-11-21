import type { ReactNode } from "react";
import type { MetricRow, ResolvedWindow } from "./monitoring-api";
import { Badge } from "./components";
import type { Row } from "./api";

type Point = { x: number; y: number };
type Series = { name: string; points: number[]; color?: string };

const W = 560;
const H = 160;
const PAD = 28;

function scale(points: number[]): { min: number; max: number; y: (v: number) => number } {
  const nums = points.filter(v => Number.isFinite(v));
  const min = nums.length ? Math.min(...nums) : 0;
  const max = nums.length ? Math.max(...nums) : 1;
  const lo = min === max ? min - 1 : min;
  const hi = min === max ? max + 1 : max;
  return { min: lo, max: hi, y: v => H - PAD - ((v - lo) / (hi - lo)) * (H - 2 * PAD) };
}

function path(points: number[], y: (v: number) => number): string {
  const step = points.length > 1 ? (W - 2 * PAD) / (points.length - 1) : 0;
  return points.map((v, i) => (i === 0 ? "M" : "L") + (PAD + i * step).toFixed(1) + "," + y(v).toFixed(1)).join(" ");
}

/** Line chart over bucketed samples (current window, left→right in time). */
export function TimeSeries({ series, label }: { series: Series[]; label?: string }) {
  const all = series.flatMap(s => s.points);
  const { min, max, y } = scale(all);
  const colors = ["#2563eb", "#9333ea", "#059669", "#d97706"];
  return (
    <figure className="chart" aria-label={label || "Time series"}>
      <svg viewBox={"0 0 " + W + " " + H} role="img">
        {[0, 0.5, 1].map(t => {
          const v = min + (max - min) * t;
          const yy = y(v);
          return <g key={t}><line x1={PAD} x2={W - PAD} y1={yy} y2={yy} className="chart-grid" />
            <text x={2} y={yy + 4} className="chart-label">{formatNum(v)}</text></g>;
        })}
        {series.map((s, i) => s.points.length > 1 && (
          <path key={i} d={path(s.points, y)} fill="none"
            stroke={s.color || colors[i % colors.length]} strokeWidth={1.8} />
        ))}
      </svg>
      {label && <figcaption className="muted">{label}</figcaption>}
    </figure>
  );
}

/** Overlay chart: baseline series shifted onto current-window offsets. */
export function OverlayChart({ current, baselines, label }: {
  current: number[]; baselines: number[][]; label?: string;
}) {
  const series: Series[] = [
    { name: "current", points: current, color: "#2563eb" },
    ...baselines.map((points, i) => ({ name: "baseline " + (i + 1), points, color: "#a3a3a3" })),
  ];
  return <TimeSeries series={series} label={label} />;
}

function formatNum(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return "—";
  if (Math.abs(v) >= 1000) return v.toLocaleString(undefined, { maximumFractionDigits: 0 });
  if (Number.isInteger(v)) return String(v);
  return v.toLocaleString(undefined, { maximumFractionDigits: 3 });
}
export { formatNum };

/** Metric comparison table with MISSING/PARTIAL badges and provenance popover. */
export function MetricTable({ metrics }: { metrics: MetricRow[] }) {
  return (
    <table className="capture-table metric-table">
      <thead><tr>
        <th>Metric</th><th>Current</th><th>Baseline</th><th>Δ abs</th><th>Δ rel</th><th>Δ pp</th><th>Rate/min</th><th>Status</th><th></th>
      </tr></thead>
      <tbody>
        {metrics.map((m, i) => {
          const name = String(m.label || m.name || m.metric || "M" + (i + 1));
          const status = m.status || "MEASURED";
          const prov = m.provenance || {};
          const drill = m.drilldownUrl || m.splunkWebUrl;
          return (
            <tr key={i}>
              <td>
                {m.id && <Badge tone="purple">{m.id}</Badge>} {name}
                {prov != null && <details className="provenance">
                  <summary aria-label={"Provenance for " + name}>ⓘ</summary>
                  <pre className="code output">{JSON.stringify(prov, null, 2)}</pre>
                </details>}
              </td>
              <td>{status === "MISSING" ? <Badge tone="amber">missing</Badge> : formatNum(m.current as number)}</td>
              <td>{m.baseline == null ? <Badge tone="amber">baseline missing</Badge>
                : (m.baseline === 0 ? "0" : formatNum(m.baseline))}</td>
              <td>{formatNum(m.absoluteChange as number)}</td>
              <td>{m.relativeChange == null ? <span className="muted">n/a</span>
                : formatNum((m.relativeChange as number) * 100) + "%"}</td>
              <td>{m.ppChange == null ? "—" : formatNum(m.ppChange as number)}</td>
              <td>{formatNum(m.ratePerMinute as number)}</td>
              <td><Badge tone={status === "MEASURED" ? "green" : status === "PARTIAL" ? "amber" : "red"}>{status}</Badge></td>
              <td>{drill ? <a href={String(drill)} target="_blank" rel="noreferrer">Splunk ↗</a> : null}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/** Horizontal bar chart of top exception signatures. */
export function TopErrors({ items }: { items: { name: string; count: number }[] }) {
  const max = Math.max(1, ...items.map(i => i.count));
  return (
    <figure className="chart" aria-label="Top errors">
      <svg viewBox={"0 0 " + W + " " + Math.max(30, items.length * 22)} role="img">
        {items.map((item, i) => (
          <g key={i}>
            <rect x={0} y={i * 22} width={(item.count / max) * (W - 200)} height={16} className="chart-bar" />
            <text x={(item.count / max) * (W - 200) + 6} y={i * 22 + 12} className="chart-label">
              {item.count}
            </text>
            <text x={W - 180} y={i * 22 + 12} className="chart-label chart-name">{item.name}</text>
          </g>
        ))}
      </svg>
    </figure>
  );
}

/** Resolved comparison windows (local + UTC + duration + notes). */
export function WindowsView({ resolved }: { resolved?: { current?: ResolvedWindow; baselines?: ResolvedWindow[]; notes?: string[] } & Row }) {
  if (!resolved?.current) return null;
  const row = (label: string, w: ResolvedWindow) => (
    <tr key={label}>
      <td>{label}{w.partial ? " (partial)" : ""}</td>
      <td>{w.localStart} → {w.localEnd}</td>
      <td>{w.start} → {w.end}</td>
      <td>{(w.durationSeconds / 3600).toFixed(2)}h</td>
      <td>{w.zone}</td>
    </tr>
  );
  return (
    <div className="windows-view">
      <table className="capture-table">
        <thead><tr><th>Window</th><th>Local</th><th>UTC</th><th>Duration</th><th>Zone</th></tr></thead>
        <tbody>
          {row("current", resolved.current)}
          {(resolved.baselines || []).map((w, i) => row("baseline " + (i + 1), w))}
        </tbody>
      </table>
      {(resolved.notes || []).length > 0 && (
        <ul className="muted">
          {resolved.notes!.map((n, i) => <li key={i}>{n}</li>)}
        </ul>
      )}
    </div>
  );
}

export function ChartByType({ type, snapshot }: { type: string; snapshot: Row }): ReactNode {
  const series = (snapshot.series || {}) as Row;
  const metrics = (snapshot.metrics || []) as MetricRow[];
  if (type === "TIMESERIES" || type === "OVERLAY") {
    const current = asNumArray(series.current || series.currentSeries);
    const baselines = Array.isArray(series.baselines) ? series.baselines.map(asNumArray) : [];
    return type === "OVERLAY"
      ? <OverlayChart current={current} baselines={baselines} label="Current vs baseline (aligned)" />
      : <TimeSeries series={[{ name: "current", points: current }]} label="Current window" />;
  }
  if (type === "TOP_ERRORS") {
    const items = ((series.topExceptions || series.top_errors || []) as Row[]).map(e => ({
      name: String(e.name || e.signature || e.exception || "error"),
      count: Number(e.count || e.total || 0),
    }));
    if (!items.length) {
      const metric = metrics.find(m => /exception|error/i.test(String(m.metric || m.name || "")));
      if (metric && Array.isArray(metric.top)) {
        return <TopErrors items={(metric.top as Row[]).map(e => ({
          name: String(e.name || e.signature), count: Number(e.count || 0) }))} />;
      }
      return <p className="muted">No error signatures in this snapshot.</p>;
    }
    return <TopErrors items={items} />;
  }
  return <MetricTable metrics={metrics} />;
}

function asNumArray(v: unknown): number[] {
  if (!Array.isArray(v)) return [];
  return v.map(item => (typeof item === "number" ? item
    : typeof item === "object" && item !== null ? Number((item as Row).value ?? (item as Row).count ?? NaN)
    : NaN)).filter(n => Number.isFinite(n));
}
