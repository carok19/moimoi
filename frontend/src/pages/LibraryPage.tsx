import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Search, Smartphone } from 'lucide-react'
import { api } from '../api/client'
import { isNativeApp, serverBase } from '../api/base'
import type { Song } from '../api/types'
import { AddSongPanel } from '../components/AddSongPanel'
import { SongCard } from '../components/SongCard'
import { useToast } from '../components/Toasts'
import { useApp } from '../context'
import { hashParams, navigate } from '../hooks/useHashRoute'

const PROCESSING = new Set(['queued', 'downloading', 'separating', 'analyzing'])

function EngineBanner() {
  const { health, offline } = useApp()
  if (offline && isNativeApp) {
    return (
      <div className="banner err">
        <div className="stack" style={{ gap: 8 }}>
          <b>No hay conexión con MoiMoi en {serverBase().replace(/^https?:\/\//, '')}.</b>
          <div className="small muted">Revisa que MoiMoi esté abierto en la computadora y que el celular esté en la misma red WiFi.</div>
          <button className="btn small" style={{ alignSelf: 'flex-start' }} onClick={() => navigate('#/conectar')}>
            <Smartphone size={14} />Cambiar de computadora
          </button>
        </div>
      </div>
    )
  }
  if (offline) {
    return (
      <div className="banner err">
        <div>
          <b>No hay conexión con el servidor de MoiMoi.</b>
          <div className="small muted">Abre MoiMoi con <code>iniciar.bat</code> (Windows) o <code>./iniciar.sh</code> (Mac/Linux) y recarga esta página.</div>
        </div>
      </div>
    )
  }
  if (!health) return null
  if (!health.engine.available) {
    return (
      <div className="banner err">
        <div>
          <b>Falta instalar el motor de IA.</b>
          <div className="small muted">{health.engine.detail} Vuelve a ejecutar el instalador (<code>iniciar.bat</code> o <code>./iniciar.sh</code>).</div>
        </div>
      </div>
    )
  }
  if (health.engine.device === 'cpu') {
    return (
      <div className="banner info small">
        <div>
          La separación corre en el procesador de esta computadora: cada canción tarda unos minutos (depende del equipo).
          Con una tarjeta gráfica NVIDIA es mucho más rápido.
        </div>
      </div>
    )
  }
  return null
}

export function LibraryPage() {
  const toast = useToast()
  const [songs, setSongs] = useState<Song[] | null>(null)
  const [filter, setFilter] = useState('')
  const [sharedLink, setSharedLink] = useState<string | null>(() => hashParams().get('link'))
  const failures = useRef(0)

  // Link compartido desde otra app (YouTube → Compartir → MoiMoi) o abierto con #/?link=…
  useEffect(() => {
    const onHash = () => {
      const link = hashParams().get('link')
      if (link) setSharedLink(link)
    }
    onHash()
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])
  useEffect(() => {
    if (sharedLink && hashParams().get('link')) window.history.replaceState(null, '', '#/')
  }, [sharedLink])

  const refresh = useCallback(async () => {
    try {
      setSongs(await api.songs())
      failures.current = 0
    } catch (err) {
      failures.current += 1
      if (failures.current === 2) toast.error(err)
    }
  }, [toast])

  const processing = songs?.some((s) => PROCESSING.has(s.status)) ?? false

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    const timer = window.setInterval(() => void refresh(), processing ? 1500 : 8000)
    return () => window.clearInterval(timer)
  }, [refresh, processing])

  const visible = useMemo(() => {
    const q = filter.trim().toLowerCase()
    if (!songs) return []
    if (!q) return songs
    return songs.filter((s) => `${s.title} ${s.artist ?? ''}`.toLowerCase().includes(q))
  }, [songs, filter])

  return (
    <main className="page">
      <EngineBanner />
      <AddSongPanel onAdded={() => void refresh()} sharedLink={sharedLink} />
      <div className="library-head">
        <div>
          <h1>Biblioteca</h1>
          <div className="small muted">
            {songs ? `${songs.length} ${songs.length === 1 ? 'canción' : 'canciones'}` : 'Cargando…'}
            {processing ? ' · procesando en segundo plano' : ''}
          </div>
        </div>
        {songs && songs.length > 3 && (
          <div style={{ position: 'relative', width: 280 }}>
            <Search size={16} style={{ position: 'absolute', left: 12, top: 13, color: 'var(--text-3)' }} />
            <input className="input" style={{ paddingLeft: 36 }} placeholder="Filtrar canciones" value={filter}
              onChange={(e) => setFilter(e.target.value)} aria-label="Filtrar canciones" />
          </div>
        )}
      </div>
      {songs && songs.length === 0 && (
        <div className="empty">
          <h2 style={{ marginBottom: 6 }}>Todavía no hay canciones</h2>
          Pega un link de YouTube o sube un archivo: MoiMoi separa la voz, la batería, el bajo, la guitarra, el
          piano y lo demás, y detecta tempo, tonalidad, acordes y partes de la canción.
        </div>
      )}
      <div className="songs">
        {visible.map((song) => <SongCard key={song.id} song={song} onChange={() => void refresh()} />)}
      </div>
    </main>
  )
}
