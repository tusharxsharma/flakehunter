import { useCallback, useEffect, useRef, useState } from 'react'

export interface AsyncState<T> {
  data: T | undefined
  error: Error | undefined
  loading: boolean
  reload: () => void
}

interface Settled<T> {
  requestKey: string
  data?: T
  error?: Error
}

/**
 * Runs `loader` whenever `key` changes (or `reload()` is called).
 *
 * - Responses that arrive after the key changed again are dropped, so a slow request for
 *   filter A can never overwrite the results for filter B.
 * - The previous data stays visible while the next request is in flight (stale-while-revalidate),
 *   which avoids flashing empty states on every filter change.
 */
export function useAsync<T>(key: string, loader: () => Promise<T>): AsyncState<T> {
  const [settled, setSettled] = useState<Settled<T>>({ requestKey: '' })
  const [nonce, setNonce] = useState(0)
  const loaderRef = useRef(loader)

  useEffect(() => {
    loaderRef.current = loader
  })

  const requestKey = `${key}#${nonce}`

  useEffect(() => {
    let cancelled = false
    loaderRef.current().then(
      (data) => {
        if (!cancelled) setSettled({ requestKey, data })
      },
      (e: unknown) => {
        if (!cancelled) setSettled({ requestKey, error: e instanceof Error ? e : new Error(String(e)) })
      },
    )
    return () => {
      cancelled = true
    }
  }, [requestKey])

  const reload = useCallback(() => setNonce((n) => n + 1), [])
  const current = settled.requestKey === requestKey
  return {
    data: settled.data,
    error: current ? settled.error : undefined,
    loading: !current,
    reload,
  }
}
