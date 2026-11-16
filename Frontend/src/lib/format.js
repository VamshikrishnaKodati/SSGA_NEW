const EM_DASH = '\u2014'

export function formatAmount(value) {
  if (value === null || value === undefined || value === '') return EM_DASH

  const number = Number(value)

  if (Number.isNaN(number)) return EM_DASH

  return number.toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

export function formatCurrency(value) {
  if (value === null || value === undefined || value === '') return EM_DASH

  const number = Number(value)

  if (Number.isNaN(number)) return EM_DASH

  return number.toLocaleString('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  })
}

export function formatCompactCurrency(value) {
  if (value === null || value === undefined || value === '') return EM_DASH

  const number = Number(value)

  if (Number.isNaN(number)) return EM_DASH

  if (Math.abs(number) >= 1e7) return `\u20b9${(number / 1e7).toFixed(2)} Cr`
  if (Math.abs(number) >= 1e5) return `\u20b9${(number / 1e5).toFixed(2)} L`

  return formatCurrency(number)
}

export function formatNumber(value) {
  if (value === null || value === undefined || value === '') return EM_DASH

  const number = Number(value)

  if (Number.isNaN(number)) return EM_DASH

  return number.toLocaleString('en-IN')
}

export function formatPercent(value, fractionDigits = 1) {
  if (value === null || value === undefined || value === '') return EM_DASH

  const number = Number(value) * 100

  if (Number.isNaN(number)) return EM_DASH

  return `${number.toFixed(fractionDigits)}%`
}

export function formatDate(value) {
  if (!value) return EM_DASH

  const date = new Date(value)

  if (Number.isNaN(date.getTime())) return String(value)

  return date.toLocaleDateString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  })
}

export function formatDateTime(value) {
  if (!value) return EM_DASH

  const date = new Date(value)

  if (Number.isNaN(date.getTime())) return String(value)

  return date.toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function formatPeriod(value) {
  if (!value) return EM_DASH

  return String(value)
}

export function initials(name) {
  if (!name) return '?'

  return String(name)
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')
}

export function humanizeStatus(status) {
  if (!status) return EM_DASH

  return String(status)
    .replace(/[._]+/g, ' ')
    .toLowerCase()
    .trim()
    .split(/\s+/)
    .map((part, index) => (index === 0 ? part.charAt(0).toUpperCase() + part.slice(1) : part))
    .join(' ')
}

export function friendlyError(error, action) {
  const status = error?.status
  let message

  if (status === 400) message = error?.message || 'The request was not valid.'
  else if (status === 401) message = 'Your session has expired. Please sign in again.'
  else if (status === 403) {
    message = (
      error?.message && error.message !== 'Access denied'
        ? error.message
        : 'You do not have permission to view or perform this action.'
    )
  } else if (status === 404) message = 'The requested record was not found or is not in your scope.'
  else if (status === 409) message = error?.message || 'This action conflicts with the current state.'
  else if (status === 413) message = 'The uploaded file is larger than the server limit.'
  else if (status === 500) message = 'The server encountered an error. Please try again.'
  else message = error?.message || 'Something went wrong. Please try again.'

  // Only callers that name their action get the status appended; everything else
  // keeps the plain message it rendered before.
  if (!action) return message
  return `${message} (${action} failed${status ? ` · HTTP ${status}` : ''})`
}
