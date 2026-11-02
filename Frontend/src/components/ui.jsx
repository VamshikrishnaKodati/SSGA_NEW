import { useEffect, useState } from 'react'
import { humanizeStatus } from '../lib/format.js'

/* ------------------------------------------------------------------ icons -- */

const ICON_PATHS = {
  grid: 'M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z',
  calculator: 'M6 2h12a1 1 0 011 1v18a1 1 0 01-1 1H6a1 1 0 01-1-1V3a1 1 0 011-1zM8 6h8v3H8zM8 12h2v2H8zM12 12h2v2h-2zM16 12h0M8 16h2v2H8zM12 16h2v2h-2z',
  file: 'M6 2h8l5 5v13a1 1 0 01-1 1H6a1 1 0 01-1-1V3a1 1 0 011-1zM13 3v5h5',
  upload: 'M12 16V4m0 0L7 9m5-5l5 5M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2',
  down: 'M12 4v12m0 0l-5-5m5 5l5-5M4 20h16',
  users: 'M16 20v-2a4 4 0 00-4-4H7a4 4 0 00-4 4v2M9.5 10a3.5 3.5 0 100-7 3.5 3.5 0 000 7zM21 20v-2a4 4 0 00-3-3.87M16 3.13A4 4 0 0119 7a4 4 0 01-3 3.87',
  badge: 'M12 2l7 3v6c0 4.4-3 8-7 9-4-1-7-4.6-7-9V5z',
  chart: 'M4 20V10m5 10V4m5 16v-7m5 7V8',
  building: 'M3 21h18M5 21V5a1 1 0 011-1h8a1 1 0 011 1v16M15 21V9h3a1 1 0 011 1v11M8 8h2M8 12h2M8 16h2',
  layers: 'M12 3l9 5-9 5-9-5zM3 13l9 5 9-5M3 17l9 5 9-5',
  shield: 'M12 2l8 3v6c0 4.7-3.2 8.5-8 10-4.8-1.5-8-5.3-8-10V5z',
  history: 'M3 12a9 9 0 109-9 9 9 0 00-7.7 4.3M3 4v4h4M12 8v4l3 2',
  bell: 'M18 16v-5a6 6 0 10-12 0v5l-2 3h16zM10 22a2 2 0 004 0',
  server: 'M4 4h16v6H4zM4 14h16v6H4zM8 7h.01M8 17h.01',
  check: 'M20 6L9 17l-5-5',
  warning: 'M12 3l9 16H3zM12 10v4M12 17h.01',
  info: 'M12 22a10 10 0 100-20 10 10 0 000 20zM12 8h.01M11 12h1v4h1',
  search: 'M11 19a8 8 0 100-16 8 8 0 000 16zM21 21l-4.3-4.3',
  close: 'M6 6l12 12M18 6L6 18',
  chevronRight: 'M9 6l6 6-6 6',
  chevronDown: 'M6 9l6 6 6-6',
  refresh: 'M20 11a8 8 0 10-2.3 5.7M20 4v6h-6',
  plus: 'M12 5v14M5 12h14',
  menu: 'M4 6h16M4 12h16M4 18h16',
  lock: 'M6 11V8a6 6 0 1112 0v3M5 11h14v10H5z',
  logout: 'M16 17l5-5-5-5M21 12H9M12 3H5a1 1 0 00-1 1v16a1 1 0 001 1h7',
  bank: 'M3 10h18M5 10V6l7-4 7 4v4M5 10v9M9 10v9M15 10v9M19 10v9M3 21h18',
  wallet: 'M3 7h14a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2zM3 7V5a2 2 0 012-2h11M16 13h2',
  calendar: 'M4 6h16v15H4zM8 3v4M16 3v4M4 11h16',
  clock: 'M12 22a10 10 0 100-20 10 10 0 000 20zM12 7v5l3 2',
  briefcase: 'M4 7h16v13H4zM9 7V5a1 1 0 011-1h4a1 1 0 011 1v2M3 12h18',
  trendUp: 'M3 17l6-6 4 4 7-7M21 8v5h-5',
}

export function Icon({ name, size = 16, strokeWidth = 1.7, className = '' }) {
  const d = ICON_PATHS[name] || ICON_PATHS.info
  const solid = name === 'grid' || name === 'file' || name === 'badge' || name === 'building' || name === 'layers' || name === 'shield' || name === 'server' || name === 'lock'

  return (
    <svg
      className={`nav-icon ${className}`.trim()}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill={solid ? 'currentColor' : 'none'}
      stroke={solid ? 'none' : 'currentColor'}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d={d} />
    </svg>
  )
}

/* -------------------------------------------------------------- page head -- */

export function PageHeader({ title, subtitle, breadcrumbs = [], actions }) {
  return (
    <header className="page-header">
      <div>
        {breadcrumbs.length > 0 && (
          <nav className="breadcrumbs" aria-label="Breadcrumb">
            {breadcrumbs.map((crumb, index) => (
              <span key={`${crumb}-${index}`}>
                {index > 0 && <span className="sep"> / </span>}
                {index === breadcrumbs.length - 1 ? <strong>{crumb}</strong> : crumb}
              </span>
            ))}
          </nav>
        )}
        <h1 className="page-title">{title}</h1>
        {subtitle && <p className="page-subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  )
}

/* ------------------------------------------------------------------ chips -- */

export function StatusChip({ value }) {
  if (!value) return <span className="muted">—</span>

  return (
    <span className={`chip ${String(value).toLowerCase()}`}>
      {humanizeStatus(value)}
    </span>
  )
}

export function Chip({ children, tone }) {
  return <span className={`chip plain${tone ? ` ${tone}` : ''}`}>{children}</span>
}

/* ---------------------------------------------------------------- alerts -- */

export function Alert({ kind = 'info', title, children, onDismiss }) {
  if (!children && !title) return null

  const role = kind === 'error' ? 'alert' : 'status'

  return (
    <div className={`alert ${kind}`} role={role}>
      <Icon name={kind === 'error' || kind === 'warning' ? 'warning' : kind === 'success' ? 'check' : 'info'} size={15} />
      <div>
        {title && <strong>{title}</strong>}
        {title && children ? ' ' : null}
        {children}
      </div>
      {onDismiss && (
        <button type="button" className="icon-button" onClick={onDismiss} aria-label="Dismiss" style={{ marginLeft: 'auto' }}>
          <Icon name="close" size={14} />
        </button>
      )}
    </div>
  )
}

/* ------------------------------------------------------------ load states -- */

export function LoadingState({ label = 'Loading…', rows = 4 }) {
  return (
    <div className="skeleton-lines" aria-busy="true" aria-label={label}>
      {Array.from({ length: rows }).map((_, index) => (
        <div key={index} className="skeleton" style={{ height: 14, width: `${100 - index * 8}%` }} />
      ))}
    </div>
  )
}

export function EmptyState({ title = 'Nothing to show', description, icon = 'info', action }) {
  return (
    <div className="state">
      <div className="state-icon"><Icon name={icon} size={18} /></div>
      <h4>{title}</h4>
      {description && <p>{description}</p>}
      {action}
    </div>
  )
}

export function ErrorState({ title = 'Could not load this view', description, onRetry }) {
  return (
    <div className="state">
      <div className="state-icon"><Icon name="warning" size={18} /></div>
      <h4>{title}</h4>
      {description && <p>{description}</p>}
      {onRetry && (
        <button type="button" className="btn ghost sm" onClick={onRetry}>
          <Icon name="refresh" size={13} /> Retry
        </button>
      )}
    </div>
  )
}

export function Unavailable({ title, description, missingEndpoint }) {
  return (
    <div className="availability-note">
      <Icon name="info" size={17} />
      <div>
        <strong>{title}</strong>
        <p>{description}</p>
        {missingEndpoint && (
          <p className="inline-meta">Required backend endpoint: <code>{missingEndpoint}</code> — not implemented yet.</p>
        )}
      </div>
    </div>
  )
}

/* ----------------------------------------------------------------- table -- */

export function DataTable({
  columns,
  rows,
  rowKey = (row, index) => row?.id ?? index,
  onRowClick,
  selectedKey,
  sort,
  onSort,
  emptyTitle,
  emptyDescription,
  emptyAction,
  footer,
}) {
  if (!rows || rows.length === 0) {
    return (
      <EmptyState title={emptyTitle || 'No records'} description={emptyDescription} action={emptyAction} />
    )
  }

  return (
    <div className="table-scroll">
      <table className="ui-table">
        <thead>
          <tr>
            {columns.map((column) => {
              const isSorted = sort?.key === column.key
              return (
                <th
                  key={column.key}
                  className={`${column.align === 'right' ? 'num' : ''} ${column.sortable ? 'sortable' : ''}`.trim()}
                  onClick={column.sortable && onSort ? () => onSort(column.key) : undefined}
                  aria-sort={isSorted ? (sort.direction === 'asc' ? 'ascending' : 'descending') : undefined}
                >
                  {column.label}
                  {column.sortable && isSorted && (
                    <span className="sort-caret">{sort.direction === 'asc' ? '▲' : '▼'}</span>
                  )}
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => {
            const key = rowKey(row, index)
            return (
              <tr
                key={key}
                className={`${onRowClick ? 'clickable' : ''} ${selectedKey === key ? 'selected' : ''}`.trim()}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
              >
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={`${column.align === 'right' ? 'num' : ''} ${column.nowrap ? 'nowrap' : ''}`.trim()}
                  >
                    {column.render ? column.render(row) : row[column.key]}
                  </td>
                ))}
              </tr>
            )
          })}
        </tbody>
        {footer && <tfoot>{footer}</tfoot>}
      </table>
    </div>
  )
}

/* ----------------------------------------------------------- pagination -- */

export function Pagination({ page, totalPages, total, pageSize, onChange }) {
  if (total === 0) return null

  const start = (page - 1) * pageSize + 1
  const end = Math.min(total, page * pageSize)

  return (
    <div className="pagination">
      <span>Showing {start}–{end} of {total}</span>
      <div className="pagination-controls">
        <button type="button" onClick={() => onChange(1)} disabled={page <= 1} aria-label="First page">«</button>
        <button type="button" onClick={() => onChange(page - 1)} disabled={page <= 1} aria-label="Previous page">Prev</button>
        <span className="inline-meta">Page {page} of {totalPages}</span>
        <button type="button" onClick={() => onChange(page + 1)} disabled={page >= totalPages} aria-label="Next page">Next</button>
        <button type="button" onClick={() => onChange(totalPages)} disabled={page >= totalPages} aria-label="Last page">»</button>
      </div>
    </div>
  )
}

/* --------------------------------------------------------------- dialog -- */

export function ConfirmDialog({ open, title, description, confirmLabel = 'Confirm', tone = 'default', busy, onConfirm, onCancel, children }) {
  useEffect(() => {
    if (!open) return undefined

    function onKey(event) {
      if (event.key === 'Escape') onCancel()
    }

    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onCancel])

  if (!open) return null

  return (
    <div className="dialog-backdrop" role="presentation" onClick={onCancel}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label={title} onClick={(event) => event.stopPropagation()}>
        <div className="dialog-head">
          <div>
            <h3>{title}</h3>
            {description && <p>{description}</p>}
          </div>
          <button type="button" className="icon-button" onClick={onCancel} aria-label="Close"><Icon name="close" size={15} /></button>
        </div>
        {children && <div className="dialog-body">{children}</div>}
        <div className="dialog-foot">
          <button type="button" className="btn ghost" onClick={onCancel} disabled={busy}>Cancel</button>
          <button
            type="button"
            className={`btn ${tone === 'danger' ? 'danger' : ''}`}
            onClick={onConfirm}
            disabled={busy}
          >
            {busy ? 'Working…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}

export function ReasonDialog({ open, title, description, label, required = true, confirmLabel = 'Submit', busy, onConfirm, onCancel }) {
  const [reason, setReason] = useState('')

  useEffect(() => {
    if (open) setReason('') // eslint-disable-line react-hooks/set-state-in-effect
  }, [open])

  if (!open) return null

  const invalid = required && reason.trim().length === 0

  return (
    <ConfirmDialog
      open={open}
      title={title}
      description={description}
      confirmLabel={confirmLabel}
      tone="danger"
      busy={busy}
      onCancel={onCancel}
      onConfirm={() => onConfirm(reason.trim())}
    >
      <label className="field">
        <span>{label}{required ? ' (required)' : ''}</span>
        <textarea value={reason} onChange={(event) => setReason(event.target.value)} rows={3} disabled={busy} autoFocus />
      </label>
      {invalid && <p className="field-hint" style={{ color: 'var(--danger-700)' }}>A reason is required by the server.</p>}
    </ConfirmDialog>
  )
}

/* --------------------------------------------------------------- drawer -- */

export function Drawer({ open, title, subtitle, onClose, footer, children }) {
  useEffect(() => {
    if (!open) return undefined

    function onKey(event) {
      if (event.key === 'Escape') onClose()
    }

    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null

  return (
    <>
      <div className="drawer-backdrop" role="presentation" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-modal="true" aria-label={title}>
        <div className="drawer-head">
          <div>
            <h3>{title}</h3>
            {subtitle && <p className="inline-meta" style={{ marginTop: 4 }}>{subtitle}</p>}
          </div>
          <button type="button" className="icon-button" onClick={onClose} aria-label="Close"><Icon name="close" size={15} /></button>
        </div>
        <div className="drawer-body">{children}</div>
        {footer && <div className="drawer-foot">{footer}</div>}
      </aside>
    </>
  )
}

/* -------------------------------------------------------------- toolbar -- */

export function SearchField({ value, onChange, placeholder = 'Search…' }) {
  return (
    <label className="field grow">
      <span className="sr-only">{placeholder}</span>
      <div className="search-field">
        <span className="search-icon"><Icon name="search" size={14} /></span>
        <input
          type="search"
          value={value}
          placeholder={placeholder}
          onChange={(event) => onChange(event.target.value)}
          aria-label={placeholder}
        />
      </div>
    </label>
  )
}

export function SelectField({ label, value, onChange, options, placeholder = 'All', disabled }) {
  return (
    <label className="field">
      <span>{label}</span>
      <select value={value} onChange={(event) => onChange(event.target.value)} disabled={disabled}>
        {placeholder !== null && <option value="">{placeholder}</option>}
        {options.map((option) => (
          <option key={option.value} value={option.value} disabled={option.disabled}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  )
}

/* ------------------------------------------------------- permission gate -- */

export function PermissionGate({ allow, children, fallback = null }) {
  return allow ? children : fallback
}

/* ------------------------------------------------------------------ kpi -- */

export function Kpi({ label, value, foot, tone, unavailable, unavailableReason, icon, onClick }) {
  const body = (
    <>
      <div className="kpi-head">
        {icon && <span className="kpi-icon"><Icon name={icon} size={15} /></span>}
        <span className="kpi-label">{label}</span>
        {onClick && <Icon name="chevronRight" size={13} className="kpi-arrow" />}
      </div>
      <div className="kpi-value">{unavailable ? 'Unavailable' : value}</div>
      {(unavailable ? (unavailableReason || 'Backend metric not available') : foot) && (
        <div className="kpi-foot">
          {unavailable ? (unavailableReason || 'Backend metric not available') : foot}
        </div>
      )}
    </>
  )

  const className = `kpi${tone ? ` ${tone}` : ''}${unavailable ? ' unavailable' : ''}${onClick && !unavailable ? ' kpi-action' : ''}`

  if (onClick && !unavailable) {
    return (
      <button type="button" className={className} onClick={onClick} title={`Open ${label}`}>
        {body}
      </button>
    )
  }

  return <div className={className}>{body}</div>
}

/* -------------------------------------------------------------- timeline -- */

export function WorkflowTimeline({ steps }) {
  return (
    <ol className="timeline" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
      {steps.map((step) => (
        <li key={step.label} className={`timeline-step ${step.state}`}>
          <div className="timeline-marker"><span className="timeline-dot" /></div>
          <div className="timeline-content">
            <h5>{step.label}</h5>
            {step.detail && <p>{step.detail}</p>}
          </div>
        </li>
      ))}
    </ol>
  )
}

/* ------------------------------------------------------------- dl table -- */

export function DefinitionList({ items }) {
  return (
    <dl className="definition-list">
      {items.filter((item) => item && item.value !== undefined && item.value !== null && item.value !== '').map((item) => (
        <div className="dl-row" key={item.term}>
          <dt>{item.term}</dt>
          <dd>{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}

/* ------------------------------------------------------------ count-up -- */

/**
 * Small restrained counter animation (local replacement for a library
 * counter component). Respects reduced-motion and skips animation for
 * non-numeric values.
 */
export function CountUp({ value, duration = 500, format }) {
  const numeric = typeof value === 'number' ? value : Number(value)
  const [display, setDisplay] = useState(() => (Number.isNaN(numeric) ? value : 0))

  useEffect(() => {
    if (Number.isNaN(numeric)) return undefined

    const reduce = typeof window !== 'undefined'
      && window.matchMedia
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches

    if (reduce) {
      setDisplay(numeric) // eslint-disable-line react-hooks/set-state-in-effect
      return undefined
    }

    let frame
    const start = performance.now()

    function tick(now) {
      const progress = Math.min(1, (now - start) / duration)
      setDisplay(Math.round(numeric * (1 - Math.pow(1 - progress, 3))))
      if (progress < 1) frame = requestAnimationFrame(tick)
    }

    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [numeric, duration])

  return format ? format(display) : display
}
