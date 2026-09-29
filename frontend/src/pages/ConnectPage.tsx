import { useState } from 'react'
import { Loader2, Search, Wifi } from 'lucide-react'
import { normalizeServer, recentServers, serverBase, setServerBase } from '../api/base'

async function probe(url: string, timeoutMs = 3500): Promise<boolean> {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(`${url}/api/health`, { signal: controller.signal, cache: 'no-store' })
    if (!response.ok) return false
    const data = await response.json()
    return data?.app === 'MoiMoi'
  } catch {
    return false
  } finally {
    window.clearTimeout(timer)
  }
}

/** Busca MoiMoi en las redes WiFi más comunes (192.168.0.x, 192.168.1.x…). */
async function scan(onProgress: (done: number, total: number) => void, signal: { stop: boolean }): Promise<string | null> {
  const prefixes = new Set<string>()
  for (const url of recentServers()) {
    const host = url.replace(/^https?:\/\//, '').replace(/:\d+$/, '')
    const parts = host.split('.')
    if (parts.length === 4) prefixes.add(parts.slice(0, 3).join('.'))
  }
  for (const p of ['192.168.1', '192.168.0', '192.168.100', '192.168.18', '192.168.2', '10.0.0']) prefixes.add(p)
  const hosts: string[] = []
  for (const prefix of prefixes) for (let i = 1; i < 255; i++) hosts.push(`http://${prefix}.${i}:4747`)
  let done = 0
  let found: string | null = null
  const worker = async () => {
    while (hosts.length && !found && !signal.stop) {
      const url = hosts.shift()!
      if (await probe(url, 1200)) found = url
      onProgress(++done, done + hosts.length)
    }
  }
  await Promise.all(Array.from({ length: 48 }, worker))
  return found
}

export function ConnectPage({ onConnected, onCancel }: { onConnected: (url: string) => void; onCancel?: () => void }) {
  const current = serverBase()
  const [text, setText] = useState(current.replace(/^http:\/\//, '').replace(/:4747$/, ''))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [scanning, setScanning] = useState<{ done: number; total: number; signal: { stop: boolean } } | null>(null)
  const recent = recentServers()

  const connect = async (value: string) => {
    const url = normalizeServer(value)
    if (!url) {
      setError('Escribe la dirección que aparece en la computadora, por ejemplo 192.168.1.20')
      return
    }
    setBusy(true)
    setError(null)
    const ok = await probe(url)
    setBusy(false)
    if (!ok) {
      setError(`No se encontró MoiMoi en ${url.replace(/^http:\/\//, '')}. Revisa que MoiMoi esté abierto en la computadora, `
        + 'que "Permitir celulares" esté activado y que los dos estén en la misma red WiFi. En Windows, la primera vez '
        + 'aparece el aviso del Firewall: elige "Permitir".')
      return
    }
    setServerBase(url)
    onConnected(url)
  }

  const search = async () => {
    const signal = { stop: false }
    setError(null)
    setScanning({ done: 0, total: 1, signal })
    const found = await scan((done, total) => setScanning((s) => (s ? { ...s, done, total } : s)), signal)
    setScanning(null)
    if (found) {
      setServerBase(found)
      onConnected(found)
    } else if (!signal.stop) {
      setError('No se encontró MoiMoi en la red. Escribe la dirección que aparece en la computadora.')
    }
  }

  return (
    <main className="page connect-page">
      <div className="card pad stack">
        <div className="row">
          <img src="/favicon.svg" alt="" width={40} height={40} />
          <div>
            <h1 style={{ margin: 0 }}>Conectar con MoiMoi</h1>
            <div className="small muted">La separación de pistas corre en tu computadora.</div>
          </div>
        </div>
        <ol className="small muted steps">
          <li>En la computadora, abre MoiMoi (<code>iniciar.bat</code> o <code>./iniciar.sh</code>). En Windows, si
            aparece el aviso del Firewall, elige <i>Permitir</i>.</li>
          <li>Conecta el celular a la <b>misma red WiFi</b>.</li>
          <li>Escribe la dirección que aparece en la ventana de MoiMoi o en <i>Ajustes → Celulares</i>.</li>
        </ol>
        <form className="row wrap" onSubmit={(e) => { e.preventDefault(); void connect(text) }}>
          <input className="input grow" inputMode="url" autoCapitalize="off" autoCorrect="off" spellCheck={false}
            placeholder="192.168.1.20" value={text} onChange={(e) => setText(e.target.value)} aria-label="Dirección de la computadora" />
          <button className="btn primary" type="submit" disabled={busy || Boolean(scanning)}>
            {busy ? <Loader2 size={16} className="spin" /> : <Wifi size={16} />}Conectar
          </button>
        </form>
        {error && <div className="banner err small" style={{ marginBottom: 0 }}>{error}</div>}
        {recent.length > 0 && (
          <div className="row wrap" style={{ gap: 6 }}>
            <span className="tiny muted">Recientes:</span>
            {recent.map((url) => (
              <button key={url} className="chip" onClick={() => void connect(url)}>{url.replace(/^http:\/\//, '').replace(/:4747$/, '')}</button>
            ))}
          </div>
        )}
        {scanning ? (
          <div className="stack" style={{ gap: 6 }}>
            <div className="row small"><span className="grow muted">Buscando en la red…</span>
              <button className="btn small ghost" onClick={() => { scanning.signal.stop = true; setScanning(null) }}>Detener</button></div>
            <div className="progress"><div style={{ width: `${Math.max(3, (scanning.done / scanning.total) * 100)}%` }} /></div>
          </div>
        ) : (
          <button className="btn ghost" style={{ alignSelf: 'flex-start' }} onClick={() => void search()}><Search size={16} />Buscar automáticamente</button>
        )}
        {onCancel && current && <button className="btn ghost small" style={{ alignSelf: 'flex-start' }} onClick={onCancel}>Volver</button>}
      </div>
    </main>
  )
}
