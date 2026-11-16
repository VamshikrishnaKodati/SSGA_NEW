import { useMemo, useState } from 'react'
import { buildDashboardAnalytics, getDashboardFilterOptions } from '../lib/dashboardAnalytics.js'
import { formatCompactCurrency, formatNumber, humanizeStatus } from '../lib/format.js'
import { EmptyState, Icon } from '../components/ui.jsx'

const COLORS = ['#064734', '#0f8360', '#1f5c9e', '#9a6208', '#b93a34', '#74859a', '#51647c']
const EMPTY_ROWS = []

export default function AdminDashboardAnalytics({ data, ability }) {
  const [filters, setFilters] = useState({ fromDate: '', toDate: '', wingId: '', divisionId: '' })
  const employees = data.employees || EMPTY_ROWS
  const divisions = data.divisions || EMPTY_ROWS
  const batches = data.batches || EMPTY_ROWS
  const periods = data.periods || EMPTY_ROWS
  const canEmployees = ability.has('employee.view')
  const canPayroll = ability.hasAny(
    'payroll.view',
    'payroll.prepare',
    'payroll.verify',
    'payroll.submit',
    'payroll.approve',
    'payroll.finalize',
    'payroll.reopen',
  )

  const options = useMemo(
    () => getDashboardFilterOptions({ employees, divisions, batches }),
    [employees, divisions, batches],
  )
  const analytics = useMemo(
    () => buildDashboardAnalytics({ employees, divisions, batches, periods, filters }),
    [employees, divisions, batches, periods, filters],
  )
  const invalidDateRange = Boolean(filters.fromDate && filters.toDate && filters.fromDate > filters.toDate)

  function setWing(wingId) {
    setFilters((current) => ({ ...current, wingId, divisionId: '' }))
  }

  function setDivision(divisionId) {
    const division = options.divisions.find((option) => option.id === divisionId)
    setFilters((current) => ({
      ...current,
      divisionId,
      wingId: division?.wingId || current.wingId,
    }))
  }

  function setDate(key, value) {
    setFilters((current) => ({ ...current, [key]: value }))
  }

  const visibleDivisions = filters.wingId
    ? options.divisions.filter((division) => division.wingId === filters.wingId)
    : options.divisions
  const hasCharts = canEmployees || canPayroll

  return (
    <section className="panel section-gap" aria-label="Organization analytics">
      <div className="panel-head">
        <div>
          <h3>Organization analytics</h3>
          <p>Live data limited to your assigned role and scope. Date range filters payroll periods; employee charts show the current employee roster.</p>
        </div>
        <span className="panel-icon"><Icon name="chart" size={16} /></span>
      </div>
      <div className="panel-body">
        <div className="dashboard-analytics-filters" aria-label="Analytics filters">
          <label className="field">
            <span>From date</span>
            <input
              type="date"
              value={filters.fromDate}
              max={filters.toDate || undefined}
              onChange={(event) => setDate('fromDate', event.target.value)}
            />
          </label>
          <label className="field">
            <span>To date</span>
            <input
              type="date"
              value={filters.toDate}
              min={filters.fromDate || undefined}
              onChange={(event) => setDate('toDate', event.target.value)}
            />
          </label>
          <label className="field">
            <span>Wing</span>
            <select value={filters.wingId} onChange={(event) => setWing(event.target.value)}>
              <option value="">All visible wings</option>
              {options.wings.map((wing) => (
                <option key={wing.id} value={wing.id}>{wing.name}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Division</span>
            <select value={filters.divisionId} onChange={(event) => setDivision(event.target.value)}>
              <option value="">All visible divisions</option>
              {visibleDivisions.map((division) => (
                <option key={division.id} value={division.id}>{division.name}</option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="btn subtle sm dashboard-analytics-reset"
            onClick={() => setFilters({ fromDate: '', toDate: '', wingId: '', divisionId: '' })}
          >
            Reset filters
          </button>
        </div>
        {invalidDateRange && (
          <p className="dashboard-analytics-error" role="alert">From date must be on or before the to date.</p>
        )}
        {!hasCharts ? (
          <EmptyState
            title="Analytics unavailable"
            description="Your current permissions do not include employee or payroll dashboard data."
            icon="lock"
          />
        ) : (
          <div className="chart-grid dashboard-analytics-grid">
            {canEmployees && (
              <>
                <article className="chart-card">
                  <ChartHeading title="Employee count by Wing" note="Horizontal bar" />
                  <HorizontalBarChart data={invalidDateRange ? [] : analytics.employeesByWing} />
                </article>
                <article className="chart-card">
                  <ChartHeading title="Active vs inactive employees" note="Donut · current roster" />
                  <DonutChart
                    title="Active vs inactive employees"
                    value={formatNumber(analytics.employeeCount)}
                    segments={[
                      { label: 'Active', value: analytics.activeEmployees, color: COLORS[0] },
                      { label: 'Inactive', value: analytics.inactiveEmployees, color: COLORS[6] },
                    ]}
                  />
                </article>
              </>
            )}
            {canPayroll && (
              <>
                <article className="chart-card">
                  <ChartHeading title="Monthly net salary trend" note="Approved/finalized payroll only" />
                  <LineChart data={invalidDateRange ? [] : analytics.monthlyPayroll} />
                </article>
                <article className="chart-card">
                  <ChartHeading title="Earnings vs deductions" note="Stacked bar · approved/finalized payroll" />
                  <StackedBarChart data={invalidDateRange ? [] : analytics.monthlyPayroll} />
                </article>
                <article className="chart-card">
                  <ChartHeading title="Payroll batch status" note="Batches in scope and selected dates" />
                  <DonutChart
                    title="Payroll batch status"
                    value={formatNumber(analytics.batchStatuses.reduce((total, [, count]) => total + count, 0))}
                    segments={analytics.batchStatuses.map(([status, value], index) => ({
                      label: humanizeStatus(status),
                      value,
                      color: COLORS[index % COLORS.length],
                    }))}
                  />
                </article>
                <article className="chart-card">
                  <ChartHeading title="Pending approvals by age" note="Age since submission" />
                  <VerticalBarChart data={invalidDateRange ? [] : analytics.approvalAges} />
                </article>
              </>
            )}
          </div>
        )}
      </div>
    </section>
  )
}

function ChartHeading({ title, note }) {
  return (
    <div className="chart-head">
      <h4>{title}</h4>
      <span className="chart-note">{note}</span>
    </div>
  )
}

function ChartEmpty() {
  return <div className="chart-empty">No data available for the current scope and filters.</div>
}

function HorizontalBarChart({ data }) {
  if (!data.length) return <ChartEmpty />
  const max = Math.max(1, ...data.map(([, value]) => value))
  const height = Math.max(112, data.length * 30 + 16)

  return (
    <svg
      className="chart-svg dashboard-chart-svg"
      style={{ height: `${height}px` }}
      viewBox={`0 0 380 ${height}`}
      role="img"
      aria-label="Employee count by Wing"
    >
      {data.map(([label, count], index) => {
        const y = 8 + index * 30
        const barWidth = (count / max) * 250
        return (
          <g key={label}>
            <text x="104" y={y + 17} textAnchor="end" fontSize="11" fill="#53665d">{label}</text>
            <rect x="114" y={y} width={barWidth} height="21" rx="3" fill={COLORS[index % COLORS.length]} />
            <text x={Math.min(370, 120 + barWidth)} y={y + 16} fontSize="11" fontWeight="600" fill="#1b3a57">{count}</text>
          </g>
        )
      })}
    </svg>
  )
}

function DonutChart({ title, value, segments }) {
  const total = segments.reduce((sum, segment) => sum + segment.value, 0)
  const circumference = 2 * Math.PI * 48
  const visibleSegments = segments.filter((segment) => segment.value > 0)

  return (
    <div className="dashboard-donut">
      <svg className="chart-svg dashboard-donut-svg" viewBox="0 0 120 120" role="img" aria-label={title}>
        <circle cx="60" cy="60" r="48" fill="none" stroke="#e1e8f0" strokeWidth="14" />
        {visibleSegments.map((segment, index) => {
          const length = (segment.value / total) * circumference
          const offset = visibleSegments
            .slice(0, index)
            .reduce((sum, preceding) => sum + (preceding.value / total) * circumference, 0)
          const circle = (
            <circle
              key={segment.label}
              cx="60"
              cy="60"
              r="48"
              fill="none"
              stroke={segment.color}
              strokeWidth="14"
              strokeDasharray={`${length} ${circumference - length}`}
              strokeDashoffset={-offset}
              transform="rotate(-90 60 60)"
            />
          )
          return circle
        })}
        <text x="60" y="57" textAnchor="middle" fontSize="16" fontWeight="600" fill="#1b3a57">{value}</text>
        <text x="60" y="73" textAnchor="middle" fontSize="9" fill="#53665d">total</text>
      </svg>
      <ul className="dashboard-chart-legend">
        {segments.map((segment) => (
          <li key={segment.label}>
            <span style={{ backgroundColor: segment.color }} />
            {segment.label}<strong>{formatNumber(segment.value)}</strong>
          </li>
        ))}
        {total === 0 && <li className="muted">No data available</li>}
      </ul>
    </div>
  )
}

function LineChart({ data }) {
  if (!data.length) return <ChartEmpty />
  const width = 380
  const height = 172
  const padX = 36
  const padY = 24
  const max = Math.max(1, ...data.map((row) => row.net))
  const point = (index, value) => ({
    x: padX + (data.length === 1 ? (width - 2 * padX) / 2 : (index / (data.length - 1)) * (width - 2 * padX)),
    y: height - padY - (value / max) * (height - 2 * padY),
  })
  const points = data.map((row, index) => point(index, row.net))

  return (
    <svg className="chart-svg dashboard-chart-svg" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Monthly net salary trend">
      <text x={padX} y="14" fontSize="10" fill="#74859a">{formatCompactCurrency(max)}</text>
      <polyline points={points.map(({ x, y }) => `${x},${y}`).join(' ')} fill="none" stroke={COLORS[0]} strokeWidth="3" />
      {points.map(({ x, y }, index) => (
        <g key={data[index].label}>
          <circle cx={x} cy={y} r="4" fill={COLORS[0]} />
          <text x={x} y={height - 4} textAnchor="middle" fontSize="9" fill="#53665d">{data[index].label}</text>
        </g>
      ))}
    </svg>
  )
}

function StackedBarChart({ data }) {
  if (!data.length) return <ChartEmpty />
  const width = 380
  const height = 172
  const pad = 28
  const chartHeight = height - pad - 22
  const max = Math.max(1, ...data.map((row) => row.gross + row.deductions))
  const slot = (width - pad * 2) / data.length
  const barWidth = Math.min(42, slot * 0.62)

  return (
    <>
      <svg className="chart-svg dashboard-chart-svg" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Monthly earnings and deductions stacked bar chart">
        {data.map((row, index) => {
          const x = pad + index * slot + (slot - barWidth) / 2
          const earningsHeight = (row.gross / max) * chartHeight
          const deductionsHeight = (row.deductions / max) * chartHeight
          const baseline = height - pad
          return (
            <g key={row.label}>
              <rect x={x} y={baseline - earningsHeight} width={barWidth} height={earningsHeight} fill={COLORS[0]} />
              <rect x={x} y={baseline - earningsHeight - deductionsHeight} width={barWidth} height={deductionsHeight} fill={COLORS[3]} />
              <text x={x + barWidth / 2} y={height - 5} textAnchor="middle" fontSize="9" fill="#53665d">{row.label}</text>
            </g>
          )
        })}
      </svg>
      <ChartLegend earningsLabel="Earnings" deductionsLabel="Deductions" />
    </>
  )
}

function ChartLegend({ earningsLabel, deductionsLabel }) {
  return (
    <ul className="dashboard-chart-legend dashboard-chart-legend-inline">
      <li><span style={{ backgroundColor: COLORS[0] }} />{earningsLabel}</li>
      <li><span style={{ backgroundColor: COLORS[3] }} />{deductionsLabel}</li>
    </ul>
  )
}

function VerticalBarChart({ data }) {
  if (!data.length) return <ChartEmpty />
  const width = 380
  const height = 172
  const pad = 28
  const chartHeight = height - pad - 24
  const max = Math.max(1, ...data.map((row) => row.count))
  const slot = (width - pad * 2) / data.length
  const barWidth = Math.min(48, slot * 0.58)

  return (
    <svg className="chart-svg dashboard-chart-svg" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Pending approvals by age">
      {data.map((row, index) => {
        const barHeight = (row.count / max) * chartHeight
        const x = pad + index * slot + (slot - barWidth) / 2
        const y = height - pad - barHeight
        return (
          <g key={row.label}>
            <rect x={x} y={y} width={barWidth} height={barHeight} rx="3" fill={COLORS[2]} />
            <text x={x + barWidth / 2} y={y - 5} textAnchor="middle" fontSize="10" fontWeight="600" fill="#1b3a57">{row.count}</text>
            <text x={x + barWidth / 2} y={height - 5} textAnchor="middle" fontSize="9" fill="#53665d">{row.label}</text>
          </g>
        )
      })}
    </svg>
  )
}
