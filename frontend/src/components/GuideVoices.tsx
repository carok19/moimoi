import { useCallback, useEffect, useRef, useState } from 'react'
import { AlertTriangle, Check, Loader2, Mic, Play, RotateCcw, Square, Trash2, Upload } from 'lucide-react'
import { api } from '../api/client'
import { apiUrl, isStandalone } from '../api/base'
import type { ClickStyle, CueGroup, GuideImportSummary, GuideKit, GuideNumbering, GuideUploadResult } from '../api/types'
import { useApp } from '../context'
import { confirmDialog } from './Modal'
import { useToast } from './Toasts'

const GROUPS: { id: CueGroup; label: string }[] = [
  { id: 'partes', label: 'Partes' },
  { id: 'cuenta', label: 'Cuenta' },
  { id: 'indicaciones', label: 'Avisos' },
]

const NUMBERING: { id: GuideNumbering; label: string; hint: string }[] = [
  { id: 'verses', label: 'Solo versos', hint: '"Verso 1", "Verso 2"… y "Coro" en cada coro' },
  { id: 'all', label: 'Todas', hint: '"Coro 1", "Coro 2"… si el paquete tiene esas voces' },
  { id: 'none', label: 'Nunca', hint: '"Verso", "Coro"… sin números' },
]

const MAX_RECORD_MS = 6000

/** "1 voz", "3 voces"… */
function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`
}

let previewAudio: HTMLAudioElement | null = null

function play(url: string): void {
  previewAudio?.pause()
  previewAudio = new Audio(apiUrl(url))
  void previewAudio.play().catch(() => undefined)
}

let clickContext: AudioContext | null = null

/** Un compás de muestra con el sonido de click elegido (acento + 3 pulsos a 100 BPM). */
async function previewClick(style: ClickStyle | null): Promise<void> {
  clickContext = clickContext ?? new AudioContext()
  const ctx = clickContext
  await ctx.resume()
  const start = ctx.currentTime + 0.05
  const period = 0.6
  if (!style) {
    for (let i = 0; i < 4; i++) {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.frequency.value = i === 0 ? 1800 : 1250
      gain.gain.setValueAtTime(i === 0 ? 0.8 : 0.5, start + i * period)
      gain.gain.exponentialRampToValueAtTime(0.001, start + i * period + 0.05)
      osc.connect(gain).connect(ctx.destination)
      osc.start(start + i * period)
      osc.stop(start + i * period + 0.06)
    }
    return
  }
  const decode = async (url?: string) => {
    if (!url) return null
    const data = await (await fetch(apiUrl(url))).arrayBuffer()
    return ctx.decodeAudioData(data)
  }
  const beatUrl = style.sounds.beat ?? style.sounds.eighth ?? style.sounds.sixteenth ?? style.sounds.accent
  const [accent, beat] = await Promise.all([decode(style.sounds.accent ?? beatUrl), decode(beatUrl)])
  for (let i = 0; i < 4; i++) {
    const buffer = i === 0 ? accent : beat
    if (!buffer) continue
    const source = ctx.createBufferSource()
    source.buffer = buffer
    source.connect(ctx.destination)
    source.start(start + i * period)
  }
}

function recordingSupported(): boolean {
  return typeof MediaRecorder !== 'undefined' && Boolean(navigator.mediaDevices?.getUserMedia) && window.isSecureContext
}

const LANGUAGE_PREFIX = /^[^-]*\b(spanish|espa[nñ]ol|english|ingl[eé]s|french|franc[eé]s|portug[a-z]*|italian[oa]?|german|alem[aá]n)\b[^-]*-\s*/i

/** Nombre de archivo sin extensión ni el idioma del principio ("Spanish - Coro.wav" -> "Coro"). */
function baseName(name: string | null): string {
  const bare = (name ?? '').replace(/\.[a-z0-9]+$/i, '')
  return bare.replace(LANGUAGE_PREFIX, '').replace(/\s+/g, ' ').trim() || bare
}

export function GuideVoices() {
  const toast = useToast()
  const { settings, saveSettings } = useApp()
  const [kit, setKit] = useState<GuideKit | null>(null)
  const [shown, setShown] = useState<string | null>(null)
  const [group, setGroup] = useState<CueGroup>('partes')
  const [upload, setUpload] = useState<number | null>(null)
  const [summary, setSummary] = useState<GuideImportSummary | null>(null)
  const [recording, setRecording] = useState<string | null>(null)
  const stopRecording = useRef<(() => void) | null>(null)
  const input = useRef<HTMLInputElement>(null)

  const load = useCallback((set?: string | null) => {
    api.guide(set).then((k) => { setKit(k); setShown(k.set) }).catch((err) => toast.error(err))
  }, [toast])

  useEffect(() => load(), [load])
  useEffect(() => () => stopRecording.current?.(), [])

  const save = (patch: Parameters<typeof saveSettings>[0]) => saveSettings(patch).catch((err) => toast.error(err))

  const showImported = (result: GuideUploadResult) => {
    setSummary(result.summary)
    setKit(result)
    setShown(result.set)
  }

  /** En el celular: el selector de archivos de Android (el paquete no pasa por la página). */
  const pickPackage = async () => {
    setUpload(1)
    setSummary(null)
    try {
      const result = await api.pickGuideFiles(null)
      if (result) showImported(result)
    } catch (err) {
      toast.error(err)
    } finally {
      setUpload(null)
    }
  }

  const importFiles = async (files: FileList | null) => {
    if (!files?.length) return
    setUpload(0)
    setSummary(null)
    try {
      showImported(await api.uploadGuide(Array.from(files), { set: null }, setUpload))
    } catch (err) {
      toast.error(err)
    } finally {
      setUpload(null)
      if (input.current) input.current.value = ''
    }
  }

  const assign = async (cue: string, fileId: string) => {
    if (!kit) return
    try {
      const current = kit.cues.find((c) => c.id === cue)?.file
      const next = fileId
        ? await api.assignGuide(fileId, cue, shown)
        : current ? await api.assignGuide(current, null, shown) : kit
      setKit(next)
    } catch (err) {
      toast.error(err)
    }
  }

  const record = async (cue: string) => {
    if (recording) {
      stopRecording.current?.()
      return
    }
    let stream: MediaStream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: true } })
    } catch {
      toast.show('No se pudo usar el micrófono (revisa los permisos).', 'err')
      return
    }
    const recorder = new MediaRecorder(stream)
    const chunks: Blob[] = []
    recorder.ondataavailable = (event) => event.data.size && chunks.push(event.data)
    const timer = window.setTimeout(() => recorder.state === 'recording' && recorder.stop(), MAX_RECORD_MS)
    recorder.onstop = async () => {
      window.clearTimeout(timer)
      stream.getTracks().forEach((t) => t.stop())
      stopRecording.current = null
      setRecording(null)
      const type = recorder.mimeType || 'audio/webm'
      const ext = type.includes('ogg') ? 'ogg' : type.includes('mp4') ? 'm4a' : 'webm'
      const file = new File([new Blob(chunks, { type })], `grabacion-${cue}.${ext}`, { type })
      try {
        const result = await api.uploadGuide([file], { cue, set: shown ?? 'mias' })
        setKit(result)
        setShown(result.set ?? shown)
        if (result.summary.skipped.length || result.summary.errors.length) toast.show('No se escuchó nada: prueba de nuevo más cerca del micrófono.', 'err')
      } catch (err) {
        toast.error(err)
      }
    }
    stopRecording.current = () => recorder.state === 'recording' && recorder.stop()
    setRecording(cue)
    recorder.start()
  }

  const removeSet = async (id: string, name: string) => {
    if (!confirmDialog(`¿Borrar las voces en ${name}?`)) return
    try {
      const next = await api.deleteGuideSet(id)
      setKit(next)
      setShown(next.set)
    } catch (err) {
      toast.error(err)
    }
  }

  if (!kit) return <div className="muted small">Cargando voces…</div>

  const activeSet = kit.sets.find((s) => s.active) ?? null
  const cues = kit.cues.filter((c) => c.group === group)
  const unassigned = kit.files.filter((f) => !f.cue)
  // Si el sonido elegido ya no está, se usa el de MoiMoi (igual que al exportar).
  const clickChoice = kit.clicks.some((c) => c.id === settings.exportClickSound) ? settings.exportClickSound : 'moimoi'
  const selectedClick = kit.clicks.find((c) => c.id === clickChoice) ?? null

  const choose = (id: string) => api.setGuideActive(id).then((k) => { setKit(k); setShown(k.set) }).catch((err) => toast.error(err))
  const restore = () => api.restoreIncludedGuide()
    .then((k) => { setKit(k); setShown(k.set); toast.show('Listo: volvieron las voces incluidas', 'ok') })
    .catch((err) => toast.error(err))

  return (
    <div className="stack guide">
      <div className="small muted">
        La <b>Guía</b> anuncia cada parte un compás antes ("Verso 1", "Coro"…) y cuenta "1, 2, 3, 4". Se escucha en el
        reproductor y va en el paquete para Multitrack.
      </div>

      {kit.sets.length > 0 ? (
        <div className="stack" style={{ gap: 8 }}>
          <div>Idioma de la voz</div>
          <div className="row wrap" style={{ gap: 6 }}>
            {kit.sets.map((s) => (
              <button key={s.id} className={`chip${s.active ? ' on' : ''}`} aria-pressed={s.active} onClick={() => void choose(s.id)}>
                {s.active && <Check size={13} />}{s.name}
              </button>
            ))}
          </div>
        </div>
      ) : (
        <div className="banner info small" style={{ marginBottom: 0 }}>
          <div className="stack" style={{ gap: 8 }}>
            <span>No hay voces guía.</span>
            <button className="btn small" style={{ alignSelf: 'flex-start' }} onClick={() => void restore()}>
              <RotateCcw size={14} />Poner las voces incluidas
            </button>
          </div>
        </div>
      )}

      <div className="stack" style={{ gap: 8 }}>
        <div>Sonido del click</div>
        <div className="row wrap" style={{ gap: 6 }}>
          {[...kit.clicks, null].map((style) => {
            const id = style?.id ?? 'moimoi'
            return (
              <span key={id} className={`chip split${clickChoice === id ? ' on' : ''}`}>
                <button className="chip-main" aria-pressed={clickChoice === id} onClick={() => void save({ exportClickSound: id })}>
                  {style?.name ?? 'MoiMoi'}
                </button>
                <button className="chip-play" onClick={() => void previewClick(style)} aria-label={`Escuchar ${style?.name ?? 'MoiMoi'}`}><Play size={12} /></button>
              </span>
            )
          })}
        </div>
      </div>

      <div className="row wrap">
        <span className="grow">Decir el número de la parte</span>
        <div className="segmented">
          {NUMBERING.map((n) => (
            <button key={n.id} className={settings.guideNumbering === n.id ? 'active' : ''} title={n.hint}
              onClick={() => void save({ guideNumbering: n.id })}>{n.label}</button>
          ))}
        </div>
      </div>
      <div className="tiny muted">{NUMBERING.find((n) => n.id === settings.guideNumbering)?.hint}</div>
      <label className="toggle">
        <input type="checkbox" checked={settings.guideKeyChanges} onChange={(e) => void save({ guideKeyChanges: e.target.checked })} />
        <span className="track" />
        <span>Decir "Sube tono" o "Baja tono" donde cambia la tonalidad</span>
      </label>

      <details className="guide-more">
        <summary>Ver las voces{activeSet ? ` en ${activeSet.name}` : ''} o cargar otras</summary>
        <div className="stack" style={{ marginTop: 12 }}>
          {activeSet && kit.missing.length > 0 && (
            <div className="row small warn-text"><AlertTriangle size={15} style={{ flex: 'none', marginTop: 2 }} />
              <span>
                {kit.missing.length === 1 ? `Sin voz para ${kit.missing[0]}` : `Sin voz para ${kit.missing.join(', ')}`}: esa parte
                no se anuncia.{recordingSupported() ? ' Puedes grabarla con el micrófono.' : ''}
              </span>
            </div>
          )}
          <div className="segmented" role="tablist">
            {GROUPS.map((g) => (
              <button key={g.id} className={group === g.id ? 'active' : ''} onClick={() => setGroup(g.id)}>{g.label}</button>
            ))}
          </div>
          {group === 'indicaciones' && (
            <div className="tiny muted">
              Suenan si las escribes en el nombre de la parte (por ejemplo "Coro (última vez)"). "Sube tono" también suena
              solo donde la canción cambia de tonalidad.
            </div>
          )}
          <div className="cue-list">
            {cues.map((cue) => {
              const file = kit.files.find((f) => f.id === cue.file)
              return (
                <div key={cue.id} className={`cue-row${file ? '' : ' unset'}`}>
                  <span className="cue-name">{cue.name}</span>
                  <select className="select small" value={cue.file ?? ''} disabled={!kit.files.length}
                    onChange={(e) => void assign(cue.id, e.target.value)} aria-label={`Voz para ${cue.name}`}>
                    <option value="">— sin voz —</option>
                    {kit.files.map((f) => <option key={f.id} value={f.id}>{baseName(f.original)}</option>)}
                  </select>
                  <button className="btn icon small ghost" disabled={!file} onClick={() => file && play(file.url)} aria-label={`Escuchar ${cue.name}`}>
                    <Play size={14} />
                  </button>
                  {recordingSupported() && (
                    <button className={`btn icon small${recording === cue.id ? ' on' : ' ghost'}`} disabled={Boolean(recording) && recording !== cue.id}
                      onClick={() => void record(cue.id)} aria-label={recording === cue.id ? 'Terminar grabación' : `Grabar ${cue.name}`}
                      title={recording === cue.id ? 'Tocar para terminar' : 'Grabar con el micrófono'}>
                      {recording === cue.id ? <Square size={13} /> : <Mic size={14} />}
                    </button>
                  )}
                </div>
              )
            })}
          </div>
          {unassigned.length > 0 && (
            <div className="tiny muted">
              {unassigned.length === 1 ? '1 archivo sin asignar: elígelo' : `${unassigned.length} archivos sin asignar: elígelos`} en
              la lista de arriba.
            </div>
          )}

          <div className="row wrap">
            <input ref={input} type="file" multiple hidden accept=".zip,audio/*,.wav,.mp3,.m4a,.aif,.aiff,.flac,.ogg"
              onChange={(e) => void importFiles(e.target.files)} />
            <button className="btn small" disabled={upload !== null}
              onClick={() => (isStandalone() ? void pickPackage() : input.current?.click())}>
              {upload !== null ? <Loader2 size={14} className="spin" /> : <Upload size={14} />}
              {upload !== null ? (upload < 1 ? `Subiendo… ${Math.round(upload * 100)}%` : 'Reconociendo las voces…') : 'Cargar otras voces (.zip o audios)'}
            </button>
            <button className="btn small ghost" onClick={() => void restore()}><RotateCcw size={14} />Restaurar las incluidas</button>
          </div>

          {summary && (
            <div className={`banner small ${summary.errors.length || !(summary.added || summary.clicks) ? 'err' : 'info'}`} style={{ marginBottom: 0 }}
              role="status">
              <div className="stack" style={{ gap: 4 }}>
                {summary.added || summary.clicks ? <div>
                  {summary.added === 1 ? 'Se cargó ' : 'Se cargaron '}<b>{count(summary.added, 'voz', 'voces')}</b>
                  {summary.sets.length > 0 && <> en {count(summary.sets.length, 'idioma', 'idiomas')}</>}
                  {summary.clicks > 0 && <> y <b>{count(summary.clicks, 'sonido', 'sonidos')}</b> de click</>}.
                </div> : <div>
                  No se encontraron voces en esos archivos. MoiMoi las reconoce por el nombre del archivo (por ejemplo
                  "Coro.wav" o "Verso 1.mp3").
                </div>}
                {summary.errors.length > 0 && (
                  <details>
                    <summary>{summary.errors.length === 1 ? '1 archivo venía dañado y se salteó' : `${summary.errors.length} archivos venían dañados y se saltearon`} (vuelve a descargar el paquete)</summary>
                    <div className="tiny muted">{summary.errors.map((e) => e.original).join(' · ')}</div>
                  </details>
                )}
                {summary.skipped.length > 0 && <div className="tiny muted">
                  {summary.skipped.length === 1 ? '1 archivo sin sonido o que no es audio se ignoró.' : `${summary.skipped.length} archivos sin sonido o que no son audio se ignoraron.`}
                </div>}
              </div>
            </div>
          )}

          <div className="row wrap" style={{ gap: 6 }}>
            {activeSet && (
              <button className="btn small ghost danger" onClick={() => void removeSet(activeSet.id, activeSet.name)}>
                <Trash2 size={14} />Borrar las voces en {activeSet.name}
              </button>
            )}
            {selectedClick && (
              <button className="btn small ghost danger"
                onClick={() => api.deleteClickStyle(selectedClick.id).then((k) => { setKit(k); setShown(k.set); void save({ exportClickSound: 'moimoi' }) }).catch((err) => toast.error(err))}>
                <Trash2 size={14} />Borrar el click {selectedClick.name}
              </button>
            )}
          </div>
        </div>
      </details>
    </div>
  )
}
