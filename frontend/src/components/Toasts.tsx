import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'
import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react'

type Kind = 'info' | 'ok' | 'err'
interface Toast {
  id: number
  kind: Kind
  text: string
}

interface ToastApi {
  show: (text: string, kind?: Kind, ms?: number) => void
  error: (err: unknown) => void
}

const ToastContext = createContext<ToastApi>({ show: () => {}, error: () => {} })

export function useToast(): ToastApi {
  return useContext(ToastContext)
}

export function ToastProvider({ children, lifted }: { children: ReactNode; lifted: boolean }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const counter = useRef(0)
  const dismiss = useCallback((id: number) => setToasts((all) => all.filter((t) => t.id !== id)), [])
  const show = useCallback((text: string, kind: Kind = 'info', ms = 4500) => {
    const id = ++counter.current
    setToasts((all) => [...all.slice(-3), { id, kind, text }])
    window.setTimeout(() => dismiss(id), ms)
  }, [dismiss])
  const error = useCallback((err: unknown) => {
    const text = err instanceof Error ? err.message : String(err)
    show(text, 'err', 7000)
  }, [show])
  const api = useMemo(() => ({ show, error }), [show, error])
  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className={`toasts${lifted ? ' lifted' : ''}`} role="status" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast ${t.kind}`}>
            {t.kind === 'err' ? <AlertCircle size={18} color="#ff8f8f" /> : t.kind === 'ok'
              ? <CheckCircle2 size={18} color="#6fe6c6" /> : <Info size={18} color="#b7a3ff" />}
            <div className="grow">{t.text}</div>
            <button className="btn ghost icon small" onClick={() => dismiss(t.id)} aria-label="Cerrar"><X size={15} /></button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}
