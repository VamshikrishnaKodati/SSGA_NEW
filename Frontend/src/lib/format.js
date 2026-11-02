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
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}

export function friendlyError(error) {
  const status = error?.status

  if (status === 400) return error?.message || 'The request was not valid.'
  if (status === 401) return 'Your session has expired. Please sign in again.'
  if (status === 403) {
    return (
      error?.message && error.message !== 'Access denied'
        ? error.message
        : 'You do not have permission to view or perform this action.'
    )
  }
  if (status === 404) return 'The requested record was not found or is not in your scope.'
  if (status === 409) return error?.message || 'This action conflicts with the current state.'
  if (status === 413) return 'The uploaded file is larger than the server limit.'
  if (status === 500) return 'The server encountered an error. Please try again.'

  return error?.message || 'Something went wrong. Please try again.'
}
