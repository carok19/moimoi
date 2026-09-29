import { useEffect, useMemo, useRef, useState } from 'react'
import { CheckCircle2, Download, Loader2, Package, Send, Share2 } from 'lucide-react'
import { api, waitForJob } from '../api/client'
import { isStandalone } from '../api/base'
import type { Analysis, ExportRequest, GuideKit, Job, MixerChannel, MultitrackStatus, SendResult, Song, StemId } from '../api/types'
import { useApp } from '../context'
import { canShare, isNativeApp, saveFile, shareFile } from '../native'
import { bandCovers, STEM_INFO } from '../stems'
import { Modal } from './Modal'
import { useToast } from './Toasts'

type Tab = 'multitrack' | 'stems' | 'mix'

interface Props {
  song: Song
  analysis: Analysis | null
  mixer: Partial<Record<StemId, MixerChannel>>
  rate: number
  semitones: number
  keyLabel: string | null
  band: StemId[]
  onClose: () => void
}

export function formatSize(bytes: number): string {
  if (bytes > 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${Math.round(bytes / 1024)} KB`
}

const PRE_ROLL = [
  { value: 0, label: 'Sin cuenta' },
  { value: 1, label: '1 compás' },
  { value: 2, label: '2 compases' },
]

export function ExportDialog({ song, analysis, mixer, rate, semitones, keyLabel, band, onClose }: Props) {
  const toast = useToast()
  const { settings, health } = useApp()
  // En el celular por ahora: WAV y sin enviar directo a Multitrack (se comparte el .zip).
  const standalone = isStandalone()
  const canStretch = health?.features.stretchExport ?? true
  const stems = song.stems.map((s) => s.id)
  const missing = useMemo(() => stems.filter((s) => !bandCovers(s, band)), [stems, band])
  const hasBeats = Boolean(analysis?.beats.length)
  const [tab, setTab] = useState<Tab>('multitrack')
  const [selected, setSelected] = useState<StemId[]>(band.length && missing.length ? missing : stems)
  const [format, setFormat] = useState<'wav' | 'mp3' | 'flac'>('wav')
  const [packFormat, setPackFormat] = useState<'wav' | 'mp3'>('wav')
  const [click, setClick] = useState(hasBeats && settings.exportClick)
  const [mixClick, setMixClick] = useState(false)
  const [guide, setGuide] = useState(settings.exportGuide)
  const [preRoll, setPreRoll] = useState(hasBeats ? settings.exportPreRollBars : 0)
  const changed = Math.abs(rate - 1) > 0.001 || semitones !== 0
  const [apply, setApply] = useState(changed && canStretch)
  const [job, setJob] = useState<Job | null>(null)
  const [jobTab, setJobTab] = useState<Tab>('multitrack')
  const [busy, setBusy] = useState(false)
  const [kit, setKit] = useState<GuideKit | null>(null)
  const [target, setTarget] = useState<MultitrackStatus | null>(null)
  const [sending, setSending] = useState(false)
  const [sent, setSent] = useState<SendResult | null>(null)
  const [transfer, setTransfer] = useState<{ label: string; fraction: number } | null>(null)
  const status = useRef<HTMLDivElement>(null)

  useEffect(() => {
    api.guide().then(setKit).catch(() => setKit(null))
    if (!standalone) api.multitrackStatus().then(setTarget).catch(() => setTarget(null))
  }, [standalone])

  const hasVoices = Boolean(kit && kit.count > 0)
  const activeSet = kit?.sets.find((s) => s.active)
  const clickStyle = kit?.clicks.find((c) => c.id === settings.exportClickSound)
  const result = job?.status === 'done' ? job.result : null

  // El avance y el resultado quedan al final del contenido: se muestran sin que haya que bajar.
  useEffect(() => {
    if (job) status.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [job?.id, job?.status, tab]) // eslint-disable-line react-hooks/exhaustive-deps

  const toggle = (id: StemId) =>
    setSelected((cur) => (cur.includes(id) ? cur.filter((s) => s !== id) : [...cur, id]))

  const changesText = [
    Math.abs(rate - 1) > 0.001 ? `velocidad ${Math.round(rate * 100)}%` : null,
    semitones ? `tono ${semitones > 0 ? '+' : ''}${semitones}${keyLabel ? ` (${keyLabel})` : ''}` : null,
  ].filter(Boolean).join(' y ')

  const run = async () => {
    const multitrack = tab === 'multitrack'
    const body: ExportRequest = {
      type: tab,
      stems: tab === 'mix' ? stems : selected,
      format: multitrack ? packFormat : format,
      click: multitrack ? click : tab === 'mix' && mixClick,
      clickSound: settings.exportClickSound,
      tempo: apply ? rate : 1,
      semitones: apply ? semitones : 0,
      mixer: tab === 'mix' ? mixer : undefined,
      guide: multitrack && guide && hasVoices,
      guideNumbering: settings.guideNumbering,
      guideKeyChanges: settings.guideKeyChanges,
      preRollBars: multitrack && hasBeats ? preRoll : 0,
    }
    if (tab !== 'mix' && !selected.length) {
      toast.show('Elige al menos una pista', 'err')
      return
    }
    setBusy(true)
    setSent(null)
    setJobTab(tab)
    try {
      const created = await api.createExport(song.id, body)
      setJob(created)
      const done = await waitForJob(created.id, setJob)
      if (done.status === 'done' && done.downloadUrl && done.result) {
        if (!multitrack && !isNativeApp) {
          void saveFile(done.downloadUrl, done.result.name)
          toast.show('Exportación lista: descargando…', 'ok')
        }
      } else if (done.status === 'error') {
        toast.show(done.error || 'No se pudo exportar', 'err')
      }
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  const send = async () => {
    if (!job) return
    setSending(true)
    try {
      const response = await api.sendToMultitrack(job.id)
      setSent(response)
      toast.show(`Enviado a Multitrack Alabanza: ${response.pistas} pistas y ${response.marcadores} partes`, 'ok', 6000)
    } catch (err) {
      toast.error(err)
      api.multitrackStatus().then(setTarget).catch(() => undefined)
    } finally {
      setSending(false)
    }
  }

  const share = async () => {
    if (!job?.downloadUrl || !result) return
    setTransfer({ label: 'Preparando para compartir…', fraction: 0 })
    try {
      const outcome = await shareFile(job.downloadUrl, result.name, (fraction) =>
        setTransfer({ label: 'Preparando para compartir…', fraction }))
      if (outcome === 'unsupported') toast.show('Este navegador no deja compartir este archivo: descárgalo y compártelo desde tus descargas.')
    } catch (err) {
      toast.error(err)
    } finally {
      setTransfer(null)
    }
  }

  const save = async (url: string, name: string) => {
    if (isNativeApp) setTransfer({ label: 'Guardando en el celular…', fraction: 0 })
    try {
      const where = await saveFile(url, name, (fraction) => setTransfer({ label: 'Guardando en el celular…', fraction }))
      if (where) toast.show(`Guardado en ${where}`, 'ok', 6000)
    } catch (err) {
      toast.error(err)
    } finally {
      setTransfer(null)
    }
  }

  const stemPicker = (
    <>
      <div className="row wrap" style={{ gap: 6 }}>
        <span className="small muted grow">Pistas a incluir</span>
        <button className="btn small ghost" onClick={() => setSelected(stems)}>Todas</button>
        {band.length > 0 && missing.length > 0 && (
          <button className="btn small ghost" onClick={() => setSelected(missing)} title="Las que tu banda no toca">
            Lo que le falta a mi banda
          </button>
        )}
        {stems.includes('vocals') && (
          <button className="btn small ghost" onClick={() => setSelected(stems.filter((s) => s !== 'vocals'))}>Sin voz</button>
        )}
      </div>
      <div className="check-grid">
        {stems.map((id) => (
          <label key={id} className={`check${selected.includes(id) ? ' on' : ''}`}>
            <input type="checkbox" checked={selected.includes(id)} onChange={() => toggle(id)} />
            <span className="swatch" style={{ width: 9, height: 9, borderRadius: 3, background: STEM_INFO[id].color }} />
            {STEM_INFO[id].name}
          </label>
        ))}
      </div>
    </>
  )

  const running = job && job.status !== 'done' && job.status !== 'error' && job.status !== 'cancelled'
  const isZip = Boolean(result?.name.toLowerCase().endsWith('.zip'))

  return (
    <Modal
      title="Exportar"
      onClose={onClose}
      width={680}
      footer={
        <>
          <button className="btn" onClick={onClose}>Cerrar</button>
          <button className={`btn${result && jobTab === tab ? '' : ' primary'}`} disabled={busy} onClick={() => void run()}>
            {busy ? <Loader2 size={16} className="spin" /> : tab === 'multitrack' ? <Package size={16} /> : <Download size={16} />}
            {tab === 'multitrack' ? <>Crear paquete<span className="wide-only">&nbsp;para Multitrack</span></>
              : tab === 'stems' ? <>{isNativeApp ? 'Exportar' : 'Descargar'} pistas<span className="wide-only">&nbsp;(.zip)</span></>
                : isNativeApp ? 'Exportar mezcla' : 'Descargar mezcla'}
          </button>
        </>
      }
    >
      <div className="segmented tabs" role="tablist">
        <button className={tab === 'multitrack' ? 'active' : ''} onClick={() => setTab('multitrack')}>Multitrack (AI Tracks)</button>
        <button className={tab === 'stems' ? 'active' : ''} onClick={() => setTab('stems')}>Pistas sueltas</button>
        <button className={tab === 'mix' ? 'active' : ''} onClick={() => setTab('mix')}>Mezcla actual</button>
      </div>

      {tab === 'multitrack' && (
        <>
          <p className="small muted" style={{ margin: 0 }}>
            Un <b>.zip</b> listo para <b>Multitrack Alabanza</b>: una pista por instrumento, el <b>Click</b>, la
            <b> Guía</b> (voz que anuncia cada parte) y las partes de la canción como marcadores.{' '}
            {standalone
              ? <>Compártelo por WhatsApp o guárdalo en el celular, y en Multitrack Alabanza ábrelo con <i>Cargar canción (.zip)</i>.</>
              : <>Se envía directo, se comparte por WhatsApp o se abre con <i>Cargar canción (.zip)</i>.</>}
          </p>
          {stemPicker}
          <label className="toggle">
            <input type="checkbox" checked={click} disabled={!hasBeats} onChange={(e) => setClick(e.target.checked)} />
            <span className="track" />
            <span>
              Pista de <b>Click</b> (metrónomo sobre el pulso)
              <span className="tiny muted"> · sonido {clickStyle ? clickStyle.name : 'MoiMoi'}</span>
            </span>
          </label>
          <label className="toggle">
            <input type="checkbox" checked={guide && hasVoices} disabled={!hasVoices} onChange={(e) => setGuide(e.target.checked)} />
            <span className="track" />
            <span>
              Pista <b>Guía</b>: anuncia cada parte (Verso 1, Coro…) un compás antes
              {kit && !hasVoices && <span className="tiny"> · <a href="#/ajustes">carga las voces en Ajustes → Voz guía</a></span>}
              {hasVoices && activeSet && <span className="tiny muted"> · voces en {activeSet.name}</span>}
            </span>
          </label>
          <div className="row wrap">
            <span className="grow small">Cuenta antes de empezar {preRoll > 0 && guide && hasVoices ? '(la Guía cuenta "1, 2, 3, 4")' : ''}</span>
            <div className="segmented">
              {PRE_ROLL.map((option) => (
                <button key={option.value} className={preRoll === option.value ? 'active' : ''} disabled={!hasBeats && option.value > 0}
                  onClick={() => setPreRoll(option.value)}>{option.label}</button>
              ))}
            </div>
          </div>
          {!standalone && <div className="row wrap">
            <span className="grow small">Formato del audio</span>
            <div className="segmented">
              <button className={packFormat === 'wav' ? 'active' : ''} onClick={() => setPackFormat('wav')}>WAV (mejor calidad)</button>
              <button className={packFormat === 'mp3' ? 'active' : ''} onClick={() => setPackFormat('mp3')}>MP3 (liviano)</button>
            </div>
          </div>}
          {packFormat === 'mp3' && (
            <div className="tiny muted">
              MP3 pesa unas 5 veces menos (mejor para mandarlo por WhatsApp), pero los celulares conectados a
              Multitrack Alabanza solo reproducen WAV.
            </div>
          )}
          {!standalone && <div className="tiny muted row" style={{ gap: 6 }}>
            <span className={`dot${target?.ok ? '' : ' err'}`} />
            {target === null ? 'Buscando Multitrack Alabanza…'
              : target.ok ? `Multitrack Alabanza abierto en ${target.url.replace(/^https?:\/\//, '')}${target.bloqueado ? ' (bloqueado: solo acepta desde esa computadora)' : ''}`
                : `Multitrack Alabanza no está abierto en ${target.url.replace(/^https?:\/\//, '')} (puedes descargar o compartir el .zip)`}
          </div>}
        </>
      )}

      {tab === 'stems' && (
        <>
          <div className="row">
            <span className="small muted grow">Formato</span>
            <div className="segmented">
              {(standalone ? ['wav'] as const : ['wav', 'mp3', 'flac'] as const).map((f) => (
                <button key={f} className={format === f ? 'active' : ''} onClick={() => setFormat(f)}>{f.toUpperCase()}</button>
              ))}
            </div>
          </div>
          {stemPicker}
          <div className="small muted">
            O descarga una sola:{' '}
            {song.stems.map((s, i) => (
              <span key={s.id}>
                {i > 0 && ' · '}
                <button className="link" onClick={() => void save(`/api/songs/${song.id}/download/${s.id}.${format}`,
                  `${song.title} - ${s.name}.${format}`)}>{s.name}</button>
              </span>
            ))}
          </div>
        </>
      )}

      {tab === 'mix' && (
        <>
          <p className="small muted" style={{ margin: 0 }}>
            Un solo archivo con la mezcla que estás escuchando (volumen, paneo, mute y solo de cada pista).
          </p>
          <div className="row">
            <span className="small muted grow">Formato</span>
            <div className="segmented">
              {(standalone ? ['wav'] as const : ['wav', 'mp3'] as const).map((f) => (
                <button key={f} className={format === f ? 'active' : ''} onClick={() => setFormat(f)}>{f.toUpperCase()}</button>
              ))}
            </div>
          </div>
          <label className="toggle">
            <input type="checkbox" checked={mixClick} disabled={!hasBeats} onChange={(e) => setMixClick(e.target.checked)} />
            <span className="track" />
            <span>Agregar el click a la mezcla</span>
          </label>
        </>
      )}

      {changed && canStretch && (
        <label className="toggle">
          <input type="checkbox" checked={apply} onChange={(e) => setApply(e.target.checked)} />
          <span className="track" />
          <span>Aplicar los cambios actuales: <b>{changesText}</b></span>
        </label>
      )}
      {changed && !canStretch && (
        <div className="tiny muted">
          Se exporta con la velocidad y el tono originales ({changesText} no está disponible aquí).
        </div>
      )}

      {job && jobTab === tab && <div ref={status} className="stack">
      {running && (
        <div className="stack" style={{ gap: 6 }}>
          <div className="row small">
            <span className="grow muted">{job.message}</span>
            <span className="faint">{Math.round(job.progress * 100)}%</span>
          </div>
          <div className="progress"><div style={{ width: `${Math.max(4, job.progress * 100)}%` }} /></div>
        </div>
      )}
      {job.status === 'error' && <div className="banner err small" style={{ marginBottom: 0 }}>{job.error}</div>}

      {result && job.downloadUrl && (
        <div className="export-result">
          <div className="row" style={{ gap: 8 }}>
            <CheckCircle2 size={18} color="var(--ok)" />
            <span className="grow small"><b>{result.name}</b> · {formatSize(result.size)}</span>
          </div>
          <div className="row wrap" style={{ gap: 8 }}>
            {isZip && jobTab === 'multitrack' && !standalone && (
              <button className="btn primary" disabled={sending || !target?.ok} onClick={() => void send()}
                title={target?.ok ? 'Abre la canción en Multitrack Alabanza' : 'Multitrack Alabanza no está abierto'}>
                {sending ? <Loader2 size={16} className="spin" /> : <Send size={16} />}
                {sent ? 'Enviado ✓' : 'Enviar a Multitrack Alabanza'}
              </button>
            )}
            {canShare(result.name) && (
              <button className={`btn${standalone ? ' primary' : ''}`} disabled={Boolean(transfer)} onClick={() => void share()}>
                <Share2 size={16} />Compartir{isNativeApp ? ' (WhatsApp…)' : ''}
              </button>
            )}
            <button className="btn" disabled={Boolean(transfer)} onClick={() => void save(job.downloadUrl!, result.name)}>
              <Download size={16} />{isNativeApp ? 'Guardar en el celular' : 'Descargar'}
            </button>
          </div>
          {transfer && (
            <div className="stack" style={{ gap: 4 }}>
              <span className="tiny muted">{transfer.label}</span>
              <div className="progress"><div style={{ width: `${Math.max(4, transfer.fraction * 100)}%` }} /></div>
            </div>
          )}
          {!isNativeApp && isZip && (
            <div className="tiny muted">
              Para mandarlo por WhatsApp: descárgalo y adjúntalo como documento (en la computadora, arrástralo al chat de
              WhatsApp Web). Con la app de MoiMoi para Android se comparte directo.
            </div>
          )}
        </div>
      )}
      </div>}
    </Modal>
  )
}
