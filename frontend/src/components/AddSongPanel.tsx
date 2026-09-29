import { useEffect, useRef, useState } from 'react'
import { Link2, Loader2, Search, Upload, Wand2 } from 'lucide-react'
import { api } from '../api/client'
import { isStandalone } from '../api/base'
import { askNotifications } from '../api/local'
import type { PresetId, Quality, SearchResult, UrlInfo } from '../api/types'
import { useApp } from '../context'
import { formatTime } from '../music/theory'
import { PresetPicker } from './PresetPicker'
import { useToast } from './Toasts'

type Tab = 'link' | 'search' | 'upload'

const URL_RE = /https?:\/\/[^\s]+/g

export function AddSongPanel({ onAdded, sharedLink }: { onAdded: () => void; sharedLink?: string | null }) {
  const { settings, health } = useApp()
  const toast = useToast()
  const standalone = isStandalone()
  // En el celular (sin links todavía) se empieza por "Subir archivo".
  const [tab, setTab] = useState<Tab>(standalone ? 'upload' : 'link')
  const [preset, setPreset] = useState<PresetId>(settings.defaultPreset)
  const [quality, setQuality] = useState<Quality>(settings.defaultQuality)
  const touched = useRef(false)

  useEffect(() => {
    if (!touched.current) {
      setPreset(settings.defaultPreset)
      setQuality(settings.defaultQuality)
    }
  }, [settings.defaultPreset, settings.defaultQuality])

  const youtube = health?.features.youtube ?? !standalone
  const showLinks = youtube || !standalone

  useEffect(() => {
    if (sharedLink && showLinks) setTab('link')
    else if (sharedLink) toast.show('Los links de YouTube todavía no funcionan en la app del celular (sí en MoiMoi para computadora). Por ahora, elige el archivo de audio.', 'info', 8000)
  }, [sharedLink, showLinks, toast])

  useEffect(() => {
    if (!showLinks && tab !== 'upload') setTab('upload')
  }, [showLinks, tab])

  return (
    <section className="card add-card" aria-label="Agregar canción">
      <div className="row wrap add-tabs">
        <h2 className="grow">Agregar canción</h2>
        {showLinks && (
          <div className="segmented" role="tablist">
            <button role="tab" className={tab === 'link' ? 'active' : ''} onClick={() => setTab('link')}>
              <Link2 size={14} style={{ verticalAlign: -2, marginRight: 6 }} />Link
            </button>
            <button role="tab" className={tab === 'search' ? 'active' : ''} onClick={() => setTab('search')}>
              <Search size={14} style={{ verticalAlign: -2, marginRight: 6 }} />Buscar en YouTube
            </button>
            <button role="tab" className={tab === 'upload' ? 'active' : ''} onClick={() => setTab('upload')}>
              <Upload size={14} style={{ verticalAlign: -2, marginRight: 6 }} />Subir archivo
            </button>
          </div>
        )}
      </div>

      {!youtube && tab !== 'upload' && (
        <div className="banner err small">
          Para usar links hace falta <code>yt-dlp</code>: ejecuta <code>pip install -U "yt-dlp[default]"</code>.
        </div>
      )}

      {tab === 'link' && <LinkTab preset={preset} quality={quality} onAdded={onAdded} disabled={!youtube} initial={sharedLink} />}
      {tab === 'search' && <SearchTab preset={preset} quality={quality} onAdded={onAdded} disabled={!youtube} />}
      {tab === 'upload' && (standalone
        ? <PhoneUploadTab preset={preset} quality={quality} onAdded={onAdded} />
        : <UploadTab preset={preset} quality={quality} onAdded={onAdded} />)}

      <PresetPicker
        preset={preset}
        quality={quality}
        onPreset={(p) => { touched.current = true; setPreset(p) }}
        onQuality={(q) => { touched.current = true; setQuality(q); if (q === 'alta') toast.show('Calidad alta: separa mejor, pero tarda entre 2 y 4 veces más.') }}
      />
    </section>
  )
}

interface TabProps {
  preset: PresetId
  quality: Quality
  onAdded: () => void
  disabled?: boolean
  initial?: string | null
}

function LinkTab({ preset, quality, onAdded, disabled, initial }: TabProps) {
  const toast = useToast()
  const [text, setText] = useState(initial ?? '')
  useEffect(() => {
    if (initial) setText(initial)
  }, [initial])
  const [info, setInfo] = useState<UrlInfo | null>(null)
  const [checking, setChecking] = useState(false)
  const [busy, setBusy] = useState(false)
  const urls = text.match(URL_RE) ?? []

  useEffect(() => {
    setInfo(null)
    if (urls.length !== 1 || disabled) return
    const url = urls[0]
    const timer = window.setTimeout(() => {
      setChecking(true)
      api.urlInfo(url).then(setInfo).catch(() => setInfo(null)).finally(() => setChecking(false))
    }, 600)
    return () => window.clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, disabled])

  const submit = async () => {
    if (!urls.length) {
      toast.show('Pega un link de YouTube (o de otro sitio con música).', 'err')
      return
    }
    setBusy(true)
    try {
      for (const url of urls) {
        const title = urls.length === 1 && info ? info.title : undefined
        const artist = urls.length === 1 && info ? info.artist ?? undefined : undefined
        await api.addUrl(url, preset, quality, title, artist)
      }
      toast.show(urls.length > 1 ? `${urls.length} canciones en la cola` : 'Canción agregada: se está descargando y separando', 'ok')
      setText('')
      setInfo(null)
      onAdded()
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <div className="add-row">
        <input
          className="input"
          placeholder="Pega uno o varios links: https://www.youtube.com/watch?v=…"
          value={text}
          disabled={disabled}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && void submit()}
          aria-label="Link de la canción"
        />
        <button className="btn primary" disabled={busy || disabled || !urls.length} onClick={() => void submit()}>
          {busy ? <Loader2 size={16} className="spin" /> : <Wand2 size={16} />}Separar pistas
        </button>
      </div>
      {checking && <div className="small faint" style={{ marginTop: 10 }}>Buscando información del link…</div>}
      {info && (
        <div className="preview">
          {info.thumbnail && <img src={info.thumbnail} alt="" referrerPolicy="no-referrer" />}
          <div className="grow">
            <div style={{ fontWeight: 700 }} className="ellipsis">{info.title}</div>
            <div className="small muted">{[info.artist, info.duration ? formatTime(info.duration) : null].filter(Boolean).join(' · ')}</div>
          </div>
        </div>
      )}
      <p className="tiny faint" style={{ margin: '10px 2px 0' }}>
        Usa solo música que tengas permiso para usar (tus propias grabaciones, práctica personal, ensayo).
      </p>
    </div>
  )
}

function SearchTab({ preset, quality, onAdded, disabled }: TabProps) {
  const toast = useToast()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<SearchResult[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [adding, setAdding] = useState<string | null>(null)

  const run = async () => {
    if (!query.trim()) return
    setLoading(true)
    try {
      setResults(await api.search(query))
    } catch (err) {
      toast.error(err)
    } finally {
      setLoading(false)
    }
  }

  const add = async (r: SearchResult) => {
    setAdding(r.id)
    try {
      await api.addUrl(r.url, preset, quality, r.title, r.artist ?? undefined)
      toast.show(`"${r.title}" agregada`, 'ok')
      onAdded()
    } catch (err) {
      toast.error(err)
    } finally {
      setAdding(null)
    }
  }

  return (
    <div>
      <div className="add-row">
        <input className="input" placeholder="Nombre de la canción y artista" value={query} disabled={disabled}
          onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && void run()}
          aria-label="Buscar canción" />
        <button className="btn" disabled={loading || disabled || !query.trim()} onClick={() => void run()}>
          {loading ? <Loader2 size={16} className="spin" /> : <Search size={16} />}Buscar
        </button>
      </div>
      {results && results.length === 0 && <div className="small muted" style={{ marginTop: 12 }}>Sin resultados.</div>}
      {results && results.length > 0 && (
        <div className="results">
          {results.map((r) => (
            <div key={r.id} className="result">
              <img src={r.thumbnail} alt="" loading="lazy" referrerPolicy="no-referrer" />
              <div className="body">
                <div style={{ fontWeight: 700, fontSize: 14 }} className="ellipsis" title={r.rawTitle ?? r.title}>{r.title}</div>
                <div className="tiny muted ellipsis">{[r.artist ?? r.channel, r.duration ? formatTime(r.duration) : null].filter(Boolean).join(' · ')}</div>
                <button className="btn primary small" style={{ marginTop: 'auto' }} disabled={adding === r.id} onClick={() => void add(r)}>
                  {adding === r.id ? <Loader2 size={14} className="spin" /> : <Wand2 size={14} />}Separar
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** Modo celular: el selector de archivos de Android (la app copia la canción y la separa). */
function PhoneUploadTab({ preset, onAdded }: TabProps) {
  const toast = useToast()
  const [busy, setBusy] = useState(false)

  const pick = async () => {
    setBusy(true)
    askNotifications()
    try {
      const { songs, errors } = await api.pickFiles(preset)
      if (songs.length) {
        toast.show(songs.length === 1 ? `"${songs[0].title}" agregada: separando pistas…`
          : `${songs.length} canciones agregadas: se separan de a una`, 'ok')
        onAdded()
      }
      for (const e of errors) toast.show(`${e.name}: ${e.error}`, 'err', 7000)
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <div className="dropzone" onClick={() => !busy && void pick()} role="button" tabIndex={0}
        onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && !busy && void pick()}>
        {busy ? <Loader2 size={26} className="spin" color="#b7a3ff" /> : <Upload size={26} color="#b7a3ff" />}
        <b>{busy ? 'Copiando la canción…' : 'Elegir canciones del celular'}</b>
        <span className="small muted">MP3, WAV, audios de WhatsApp o videos · hasta 20 min</span>
      </div>
      <p className="tiny faint" style={{ margin: '10px 2px 0' }}>Usa solo música que tengas permiso para usar.</p>
    </div>
  )
}

function UploadTab({ preset, quality, onAdded }: TabProps) {
  const toast = useToast()
  const input = useRef<HTMLInputElement>(null)
  const [over, setOver] = useState(false)
  const [uploading, setUploading] = useState<{ name: string; progress: number } | null>(null)

  const send = async (files: File[]) => {
    for (const file of files) {
      setUploading({ name: file.name, progress: 0 })
      try {
        await api.upload(file, preset, quality, (progress) => setUploading({ name: file.name, progress }))
        toast.show(`"${file.name}" subida: separando pistas…`, 'ok')
        onAdded()
      } catch (err) {
        toast.error(err)
      }
    }
    setUploading(null)
  }

  return (
    <div>
      <div
        className={`dropzone${over ? ' over' : ''}`}
        onClick={() => input.current?.click()}
        onDragOver={(e) => { e.preventDefault(); setOver(true) }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => {
          e.preventDefault()
          setOver(false)
          void send(Array.from(e.dataTransfer.files))
        }}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && input.current?.click()}
      >
        <Upload size={26} color="#b7a3ff" />
        {uploading ? (
          <div style={{ width: 'min(360px, 100%)' }}>
            <div className="small ellipsis">Subiendo {uploading.name}…</div>
            <div className="progress" style={{ marginTop: 8 }}><div style={{ width: `${uploading.progress * 100}%` }} /></div>
          </div>
        ) : (
          <>
            <b>Arrastra tus canciones aquí o haz clic para elegirlas</b>
            <span className="small muted">MP3, WAV, FLAC, M4A, OGG, o videos (MP4, MOV…) · hasta 20 minutos</span>
          </>
        )}
        <input ref={input} type="file" multiple hidden accept="audio/*,video/*,.flac,.m4a,.opus,.mkv"
          onChange={(e) => { void send(Array.from(e.target.files ?? [])); e.target.value = '' }} />
      </div>
    </div>
  )
}
