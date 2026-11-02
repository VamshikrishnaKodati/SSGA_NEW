import { useCallback, useEffect, useRef, useState } from 'react'
import { friendlyError } from './format.js'

/**
 * Tiny resource loader shared by pages. Keeps one eslint suppression for the
 * react-hooks/set-state-in-effect rule instead of repeating it in every page.
 *
 * `loader` is read through a ref so it does not need to be memoised; pass the
 * values it depends on through `deps`, which are compared by value.
 */
export function useLoader(loader, deps = [], { enabled = true } = {}) {
  const [state, setState] = useState({ data: null, loading: enabled, error: '' })
  const [nonce, setNonce] = useState(0)
  const mounted = useRef(true)
  const loaderRef = useRef(loader)

  useEffect(() => {
    loaderRef.current = loader
  }, [loader])

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const depsKey = JSON.stringify(deps)

  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    if (!enabled) {
      setState({ data: null, loading: false, error: '' })
      return
    }

    let cancelled = false
    setState((previous) => ({ ...previous, loading: true, error: '' }))

    ;(async () => {
      try {
        const data = await loaderRef.current()
        if (!cancelled && mounted.current) setState({ data, loading: false, error: '' })
      } catch (requestError) {
        if (!cancelled && mounted.current) {
          setState({ data: null, loading: false, error: friendlyError(requestError) })
        }
      }
    })()

    return () => {
      cancelled = true
    }
  }, [enabled, depsKey, nonce])
  /* eslint-enable react-hooks/set-state-in-effect */

  const reload = useCallback(() => setNonce((value) => value + 1), [])

  return { ...state, reload }
}
