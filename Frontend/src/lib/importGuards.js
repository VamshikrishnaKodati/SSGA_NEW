export const MAX_UPLOAD_BYTES = 25 * 1024 * 1024
export const ACCEPTED_EXTENSIONS = ['.xlsx', '.xlsm', '.xls', '.csv']

export function uploadRejection(file) {
  const dot = file.name.lastIndexOf('.')
  const extension = dot < 0 ? '' : file.name.slice(dot).toLowerCase()
  if (!ACCEPTED_EXTENSIONS.includes(extension)) {
    return 'Only .xlsx, .xlsm, .xls and .csv files are supported.'
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return `The file is ${(file.size / 1024 / 1024).toFixed(1)} MB; the limit is 25 MB.`
  }
  return ''
}

export function approveBlocked(actorId, identity) {
  return Boolean(
    actorId && (identity?.uploadedBy === actorId || identity?.submittedBy === actorId),
  )
}

export function finalizeBlocked(actorId, identity) {
  return Boolean(
    actorId
      && (identity?.uploadedBy === actorId
        || identity?.submittedBy === actorId
        || identity?.approvedBy === actorId),
  )
}
