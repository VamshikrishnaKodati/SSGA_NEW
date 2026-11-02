import { useEffect, useMemo, useState } from 'react'

export function usePagedRows(rows, pageSize = 15) {
  const [page, setPage] = useState(1)
  const totalPages = Math.max(1, Math.ceil(rows.length / pageSize))

  useEffect(() => {
    if (page > totalPages) setPage(1) // eslint-disable-line react-hooks/set-state-in-effect
  }, [page, totalPages])

  const paged = useMemo(
    () => rows.slice((page - 1) * pageSize, page * pageSize),
    [rows, page, pageSize],
  )

  return { page, setPage, totalPages, paged, total: rows.length, pageSize }
}
