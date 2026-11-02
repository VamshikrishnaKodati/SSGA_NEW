// Client-side format validation for the employee master form.
// Uniqueness of employee code, HRMS id, statutory identifiers and bank
// accounts is enforced by the backend / database (HTTP 409), not here.

export const PATTERNS = {
  pan: /^[A-Z]{5}[0-9]{4}[A-Z]$/,
  aadhaar: /^\d{12}$/,
  ifsc: /^[A-Z]{4}0[A-Z0-9]{6}$/,
  email: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
  account: /^\d{9,18}$/,
}

const upper = (value) => (value || '').trim().toUpperCase()
const trimmed = (value) => (value || '').trim()

export function validateEmployeeForm(form, isEdit) {
  if (!isEdit && !trimmed(form.employeeCode)) return 'Employee code is required.'
  if (!trimmed(form.name)) return 'Employee name is required.'
  if (!isEdit && !form.divisionId) return 'Select a division.'

  if (trimmed(form.pan) && !PATTERNS.pan.test(upper(form.pan))) {
    return 'PAN must look like ABCDE1234F.'
  }
  if (trimmed(form.aadhaar) && !PATTERNS.aadhaar.test(trimmed(form.aadhaar))) {
    return 'Aadhaar must be 12 digits.'
  }
  if (trimmed(form.email) && !PATTERNS.email.test(trimmed(form.email))) {
    return 'Enter a valid email address.'
  }
  if (trimmed(form.ifscCode) && !PATTERNS.ifsc.test(upper(form.ifscCode))) {
    return 'IFSC must look like SBIN0001234.'
  }
  if (trimmed(form.bankAccountNumber) && !PATTERNS.account.test(trimmed(form.bankAccountNumber))) {
    return 'Account number must be 9 to 18 digits.'
  }

  const hasBankName = Boolean(trimmed(form.bankName))
  const hasAccount = Boolean(trimmed(form.bankAccountNumber))
  if (hasBankName !== hasAccount) {
    return 'Bank name and account number must be provided together.'
  }

  return ''
}
