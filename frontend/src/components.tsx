import { useState, type ReactNode } from "react";
import { X, Plus, ChevronRight } from "lucide-react";
import { pretty, type Row } from "./api";
export function Badge({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: string;
}) {
  return <span className={"badge " + tone}>{children}</span>;
}
export function Panel({
  title,
  action,
  children,
}: {
  title?: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="panel">
      {title && (
        <div className="panel-title">
          <h3>{title}</h3>
          {action}
        </div>
      )}
      {children}
    </section>
  );
}
export function Empty({
  text = "No records yet. Create one to get started.",
}: {
  text?: string;
}) {
  return (
    <div className="empty">
      <Plus size={24} />
      <p>{text}</p>
    </div>
  );
}
export function JsonView({ value }: { value: unknown }) {
  return (
    <pre className="code output">
      {typeof value === "string" ? value : pretty(value)}
    </pre>
  );
}
export function Editor({
  title,
  value,
  onClose,
  onSave,
}: {
  title: string;
  value: Row;
  onClose: () => void;
  onSave: (value: Row) => Promise<unknown>;
}) {
  const [text, setText] = useState(pretty(value));
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <div className="modal-backdrop">
      <section
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <header>
          <h2>{title}</h2>
          <button onClick={onClose} aria-label="Close">
            <X />
          </button>
        </header>
        <p className="muted">
          Edit the record. Save checks its revision to prevent overwriting newer
          changes.
        </p>
        <textarea
          aria-label="Record JSON"
          className="code tall"
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <footer>
          <button onClick={onClose}>Cancel</button>
          <button
            className="primary"
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              try {
                await onSave(JSON.parse(text));
                onClose();
              } catch (e) {
                setError(String(e));
              } finally {
                setBusy(false);
              }
            }}
          >
            Save record
          </button>
        </footer>
      </section>
    </div>
  );
}
export function Records({
  rows,
  columns,
  onEdit,
  onAdd,
  actions,
}: {
  rows: Row[];
  columns: string[];
  onEdit?: (r: Row) => void;
  onAdd?: () => void;
  actions?: (r: Row) => ReactNode;
}) {
  return (
    <>
      <div className="table-tools">
        <span className="muted">{rows.length} records</span>
        {onAdd && (
          <button onClick={onAdd}>
            <Plus size={15} /> Add record
          </button>
        )}
      </div>
      {!rows.length ? (
        <Empty />
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                {columns.map((c) => (
                  <th key={c}>{c.replace(/([A-Z])/g, " $1")}</th>
                ))}
                {(onEdit || actions) && <th>Actions</th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={r.id || i}>
                  {columns.map((c) => (
                    <td key={c}>
                      {[
                        "status",
                        "priority",
                        "type",
                        "region",
                        "role",
                      ].includes(c) ? (
                        <Badge
                          tone={
                            ["APPROVED", "PASS", "Done"].includes(r[c])
                              ? "green"
                              : ["FAIL", "High", "REJECTED"].includes(r[c])
                                ? "red"
                                : "neutral"
                          }
                        >
                          {String(r[c] ?? "—")}
                        </Badge>
                      ) : typeof r[c] === "object" ? (
                        pretty(r[c])
                      ) : (
                        String(r[c] ?? "—")
                      )}
                    </td>
                  ))}
                  {(onEdit || actions) && (
                    <td>
                      <div className="row-actions">
                        {actions?.(r)}
                        {onEdit && (
                          <button
                            className="icon-button"
                            title="Edit record"
                            onClick={() => onEdit(r)}
                          >
                            <ChevronRight size={16} />
                          </button>
                        )}
                      </div>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
export function Field({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  );
}
