import { useEffect, useState } from 'react'
import { CheckCircle2, Laptop, Loader2, Smartphone, XCircle } from 'lucide-react'
import { api } from '../api/client'
import { apiUrl, isNativeApp, isStandalone, serverBase, setServerBase } from '../api/base'
import { navigate } from '../hooks/useHashRoute'
import type { MultitrackStatus, PresetId, Quality, StemId } from '../api/types'
import { GuideVoices } from '../components/GuideVoices'
import { PhoneAccess } from '../components/PhoneAccess'
import { PresetPicker } from '../components/PresetPicker'
import { useToast } from '../components/Toasts'
import { useApp } from '../context'
import { BAND_INSTRUMENTS, STEM_INFO } from '../stems'

function Feature({ ok, label, hint }: { ok: boolean; label: string; hint?: string }) {
  return (
    <div className="row" style={{ alignItems: 'flex-start' }}>
      {ok ? <CheckCircle2 size={18} color="#3fd9b0" /> : <XCircle size={18} color="#ff8f8f" />}
      <div>
        <div>{label}</div>
        {!ok && hint && <div className="tiny muted">{hint}</div>}
      </div>
    </div>
  )
}

/** App del celular: todo se hace acá; opcionalmente se puede usar MoiMoi de una computadora. */
function PhoneMode() {
  if (isStandalone()) {
    return (
      <div className="stack">
        <div className="small muted">
          La separación, las pistas y las exportaciones se hacen <b>en este celular</b>: no hace falta computadora ni
          internet. Si tienes MoiMoi abierto en una computadora (más rápido con tarjeta gráfica NVIDIA), también puedes
          usarlo desde acá.
        </div>
        <button className="btn" style={{ alignSelf: 'flex-start' }} onClick={() => navigate('#/conectar')}>
          <Laptop size={16} />Usar MoiMoi de la computadora
        </button>
      </div>
    )
  }
  return (
    <div className="stack">
      <PhoneAccess />
      <button className="btn" style={{ alignSelf: 'flex-start' }} onClick={() => {
        setServerBase(null)
        window.location.hash = '#/'
        window.location.reload()
      }}>
        <Smartphone size={16} />Usar solo el celular (sin computadora)
      </button>
    </div>
  )
}

function MultitrackSettings() {
  const { settings, saveSettings } = useApp()
  const toast = useToast()
  const [address, setAddress] = useState(settings.multitrackUrl)
  const [status, setStatus] = useState<MultitrackStatus | null>(null)
  const [checking, setChecking] = useState(false)
  const save = (patch: Parameters<typeof saveSettings>[0]) => saveSettings(patch).catch((err) => toast.error(err))

  useEffect(() => setAddress(settings.multitrackUrl), [settings.multitrackUrl])

  const check = async (url?: string) => {
    setChecking(true)
    try {
      setStatus(await api.multitrackStatus(url))
    } catch (err) {
      toast.error(err)
    } finally {
      setChecking(false)
    }
  }

  useEffect(() => {
    if (!isStandalone()) void check()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const commit = async () => {
    if (!address.trim() || address.trim() === settings.multitrackUrl) return
    await save({ multitrackUrl: address.trim() })
    void check()
  }

  const standalone = isStandalone()
  return (
    <>
      {standalone ? (
        <div className="small muted">
          <b>Exportar → Multitrack</b> arma un .zip con una pista por instrumento (y el <b>Click</b>, la <b>Guía</b> y las
          partes de la canción cuando estén en el celular). Compártelo por WhatsApp y ábrelo en Multitrack Alabanza con
          <i> Cargar canción (.zip)</i>.
        </div>
      ) : (
        <div className="small muted">
          <b>Exportar → Multitrack</b> arma un .zip con una pista por instrumento, el <b>Click</b>, la <b>Guía</b> y las partes de
          la canción (se abren como marcadores). Con <b>Enviar a Multitrack Alabanza</b> la canción se abre directo en el
          programa, sin copiar archivos. También puedes compartir el .zip por WhatsApp.
        </div>
      )}
      {!standalone && <div className="row wrap">
        <span className="grow">Dirección de Multitrack Alabanza</span>
        <input className="input" style={{ maxWidth: 260 }} value={address} onChange={(e) => setAddress(e.target.value)}
          onBlur={() => void commit()} onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()}
          aria-label="Dirección de Multitrack Alabanza" spellCheck={false} />
        <button className="btn small" disabled={checking} onClick={() => void check(address)}>
          {checking && <Loader2 size={14} className="spin" />}Probar
        </button>
      </div>}
      {status && !standalone && (
        <div className="row small" style={{ gap: 8 }}>
          <span className={`dot${status.ok ? '' : ' err'}`} />
          {status.ok ? `Multitrack Alabanza está abierto${status.bloqueado ? ' (bloqueado: solo acepta canciones desde su computadora)' : ''}.`
            : `${status.error ?? 'No responde'}. Si está en la misma computadora, deja http://127.0.0.1:4848.`}
        </div>
      )}
      <div className="small muted">Al exportar para Multitrack, por defecto:</div>
      <label className="toggle">
        <input type="checkbox" checked={settings.exportClick} onChange={(e) => void save({ exportClick: e.target.checked })} />
        <span className="track" />
        <span>Incluir la pista de Click</span>
      </label>
      <label className="toggle">
        <input type="checkbox" checked={settings.exportGuide} onChange={(e) => void save({ exportGuide: e.target.checked })} />
        <span className="track" />
        <span>Incluir la pista Guía (voz que anuncia las partes)</span>
      </label>
      <div className="row wrap">
        <span className="grow">Cuenta antes de empezar</span>
        <div className="segmented">
          {[0, 1, 2].map((bars) => (
            <button key={bars} className={settings.exportPreRollBars === bars ? 'active' : ''}
              onClick={() => void save({ exportPreRollBars: bars })}>{bars === 0 ? 'Sin cuenta' : bars === 1 ? '1 compás' : '2 compases'}</button>
          ))}
        </div>
      </div>
    </>
  )
}

export function SettingsPage() {
  const { settings, saveSettings, health } = useApp()
  const toast = useToast()
  const save = (patch: Parameters<typeof saveSettings>[0]) => saveSettings(patch).catch((err) => toast.error(err))
  const toggleBand = (id: StemId) => {
    const band = settings.band.includes(id) ? settings.band.filter((s) => s !== id) : [...settings.band, id]
    void save({ band })
  }
  const origin = isNativeApp ? serverBase() : window.location.origin

  return (
    <main className="page">
      <h1 style={{ marginBottom: 18 }}>Ajustes</h1>
      <div className="settings">
        <section className="card">
          <h2>Mi banda</h2>
          <div className="small muted">
            Marca los instrumentos que <b>sí tiene</b> tu banda. En el reproductor, <i>Tocar con mi banda</i> silencia esas
            pistas y deja sonar solo lo que les falta; al exportar para Multitrack puedes elegir solo esas pistas.
          </div>
          <div className="band-grid">
            {BAND_INSTRUMENTS.map((id) => (
              <label key={id} className={`check${settings.band.includes(id) ? ' on' : ''}`}>
                <input type="checkbox" checked={settings.band.includes(id)} onChange={() => toggleBand(id)} />
                <span style={{ width: 9, height: 9, borderRadius: 3, background: STEM_INFO[id].color }} />
                {id === 'piano' ? 'Piano / teclados' : id === 'other' ? 'Otros (cuerdas, pads…)' : STEM_INFO[id].name}
              </label>
            ))}
          </div>
        </section>

        <section className="card">
          <h2>Voz guía y click</h2>
          {health?.features.guide === false ? (
            <div className="small muted">
              La voz guía (que anuncia "Verso 1, Coro…") llega a la app del celular en la próxima versión. El click y la
              cuenta inicial ya se pueden agregar al paquete para Multitrack.
            </div>
          ) : <GuideVoices />}
        </section>

        <section className="card">
          <h2>Multitrack Alabanza (AI Tracks)</h2>
          <MultitrackSettings />
        </section>

        <section className="card">
          <h2>{isNativeApp ? 'Celular o computadora' : 'Celulares y tablets'}</h2>
          {isNativeApp ? <PhoneMode /> : <PhoneAccess />}
        </section>

        <section className="card">
          <h2>Separación por defecto</h2>
          <PresetPicker
            preset={settings.defaultPreset}
            quality={settings.defaultQuality}
            onPreset={(p: PresetId) => void save({ defaultPreset: p })}
            onQuality={(q: Quality) => void save({ defaultQuality: q })}
          />
        </section>

        <section className="card">
          <h2>Acordes y metrónomo</h2>
          <div className="row wrap">
            <span className="grow">Nombres de las notas</span>
            <div className="segmented">
              <button className={settings.notation === 'american' ? 'active' : ''} onClick={() => void save({ notation: 'american' })}>C D E F G A B</button>
              <button className={settings.notation === 'latin' ? 'active' : ''} onClick={() => void save({ notation: 'latin' })}>Do Re Mi Fa Sol</button>
            </div>
          </div>
          <div className="row wrap">
            <span className="grow">Volumen del metrónomo</span>
            <input type="range" min={0} max={150} value={Math.round(settings.metronomeVolume * 100)} style={{ maxWidth: 260 }}
              onChange={(e) => void save({ metronomeVolume: Number(e.target.value) / 100 })} aria-label="Volumen del metrónomo" />
          </div>
        </section>

        <section className="card">
          <h2>Motor de IA</h2>
          {health ? (
            <>
              <dl className="kv">
                <dt>Separación</dt><dd>{health.engine.detail}</dd>
                <dt>Procesa con</dt>
                <dd>{health.engine.device === 'cuda' ? `GPU NVIDIA${health.engine.gpu ? ` (${health.engine.gpu})` : ''}`
                  : health.engine.device === 'mps' ? 'GPU de Apple' : health.engine.device === 'phone' ? 'Este celular'
                    : 'Procesador (CPU)'}</dd>
                <dt>Versión</dt><dd>MoiMoi {health.version}</dd>
                {!health.standalone && <><dt>Carpeta de datos</dt><dd>{health.dataDir}</dd></>}
              </dl>
              {health.standalone ? (
                <div className="stack" style={{ gap: 8 }}>
                  <Feature ok label="Separación de pistas en 2, 4 o 6 (Demucs, en el celular)" />
                  <Feature ok={health.features.analysis !== false} label="Tempo, compás, tonalidad, acordes y partes" hint="actualiza MoiMoi" />
                  <Feature ok label="Exportar pistas, mezcla y paquete para Multitrack con click (WAV)" />
                  <Feature ok={health.features.guide !== false} label="Voz guía que anuncia cada parte" hint="actualiza MoiMoi" />
                  <Feature ok={health.features.stretchExport} label="Exportar con otra velocidad o tono" hint="actualiza MoiMoi" />
                  <Feature ok={false} label="Links de YouTube, MP3 y letra automática" hint="Por ahora solo en MoiMoi para computadora" />
                </div>
              ) : <div className="stack" style={{ gap: 8 }}>
                <Feature ok={health.engine.available} label="Separación de pistas (Demucs)" hint="Ejecuta el instalador: iniciar.bat / ./iniciar.sh" />
                <Feature ok={health.features.youtube} label="Links de YouTube y otros sitios (yt-dlp)" hint='pip install -U "yt-dlp[default]"' />
                <Feature ok={health.features.ffmpeg} label="Lectura de cualquier formato de audio o video (ffmpeg)" hint="pip install imageio-ffmpeg" />
                <Feature ok={health.features.stretchExport} label="Exportar con otra velocidad o tono" hint="actualiza MoiMoi" />
                <Feature ok={health.features.lyrics} label="Transcripción de letras (opcional)" hint="pip install faster-whisper" />
              </div>}
            </>
          ) : <div className="muted">Sin conexión con el servidor.</div>}
        </section>

        {!isNativeApp && <section className="card">
          <h2>API para otras apps</h2>
          <div className="small muted">Otras apps pueden usar la API de MoiMoi en esta dirección:</div>
          <code className="block">{`${origin}/api/songs                         lista de canciones
${origin}/api/songs/{id}                    detalle (pistas, tonalidad, BPM)
${origin}/api/songs/{id}/analysis           acordes, secciones, pulsos
${origin}/api/songs/{id}/download/{pista}.wav  pista: vocals, drums, bass, guitar, piano, other
POST ${origin}/api/songs/{id}/exports       {"type": "multitrack", "click": true, "guide": true}`}</code>
          <a href={apiUrl('/docs')} target="_blank" rel="noopener" className="small">Documentación completa de la API</a>
        </section>}
      </div>
    </main>
  )
}
