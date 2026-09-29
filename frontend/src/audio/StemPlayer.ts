/**
 * Motor de reproducción multipista del navegador.
 *
 * Cada pista separada va a su propio nodo Signalsmith Stretch (AudioWorklet + WASM), que
 * permite cambiar la velocidad sin cambiar el tono y el tono sin cambiar la velocidad, en
 * tiempo real. Todos los nodos reciben exactamente la misma programación (mismo instante de
 * salida, misma posición y velocidad), así que las pistas quedan sincronizadas al sample.
 *
 * Después de cada nodo: volumen -> paneo -> mezcla general -> limitador -> salida.
 * El metrónomo y la cuenta regresiva se programan con el reloj de audio sobre los pulsos
 * detectados de la canción (siguen la velocidad y los loops).
 */
import SignalsmithStretch, { type StretchNode } from 'signalsmith-stretch'
import type { MixerChannel } from '../api/types'

const LEAD = 0.04
const TICK_MS = 30

export interface StemSource {
  id: string
  name: string
  url: string
}

export interface BeatGrid {
  beats: number[]
  accents: boolean[]
  beatsPerBar: number
}

interface Channel {
  id: string
  node: StretchNode
  gain: GainNode
  pan: StereoPannerNode
  analyser: AnalyserNode
  scratch: Float32Array<ArrayBuffer>
  state: MixerChannel
}

interface Segment {
  output: number
  input: number
  rate: number
  active: boolean
}

export interface LoopRange {
  start: number
  end: number
}

/** Una voz de la Guía: cuándo suena (segundos de la canción) y su audio. */
export interface GuideVoice {
  time: number
  buffer: AudioBuffer
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))

/** Calidad con la que se cargan las pistas en el reproductor. */
export interface PlaybackQuality {
  sampleRate: number
  mono: boolean
}

/**
 * Las pistas quedan enteras en memoria (16 bits) para cambiar velocidad y tono al instante: una
 * canción de 6 minutos en 6 pistas son unos 380 MB. En celulares con poca memoria, las canciones
 * largas se cargan a 22 kHz (y si hace falta en mono) para que no se cierre la app. Las
 * exportaciones no cambian: salen de las pistas originales.
 */
export function playbackQuality(stems: number, duration: number): PlaybackQuality {
  const reported = (navigator as Navigator & { deviceMemory?: number }).deviceMemory
  const phone = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent)
  const gigabytes = reported ?? (phone ? 3 : 16)
  const budget = gigabytes * 1024 ** 3 * 0.1
  const bytes = (rate: number, channels: number) => stems * duration * rate * channels * 2
  if (bytes(44100, 2) <= budget) return { sampleRate: 44100, mono: false }
  if (bytes(22050, 2) <= budget) return { sampleRate: 22050, mono: false }
  return { sampleRate: 22050, mono: true }
}

/** Filtro de media banda de 47 coeficientes (ventana de Kaiser) para bajar a la mitad la frecuencia. */
const HALF_BAND = (() => {
  const reach = 23
  const beta = 6
  const bessel = (x: number) => {
    let sum = 1
    let term = 1
    for (let k = 1; k < 30; k++) {
      term *= (x / (2 * k)) ** 2
      sum += term
    }
    return sum
  }
  const odd: number[] = []
  for (let k = 1; k <= reach; k += 2) {
    const t = k / (reach + 1)
    odd.push((Math.sin((Math.PI * k) / 2) / (Math.PI * k)) * (bessel(beta * Math.sqrt(1 - t * t)) / bessel(beta)))
  }
  const gain = 0.5 + 2 * odd.reduce((a, b) => a + b, 0)
  return { center: 0.5 / gain, odd: Float64Array.from(odd, (c) => c / gain) }
})()

/** De 44,1 kHz a 22,05 kHz (filtro de media banda y una de cada dos muestras). */
function halve(input: Int16Array): Int16Array {
  const n = input.length
  const out = new Int16Array(n >> 1)
  const { center, odd } = HALF_BAND
  const taps = odd.length
  const reach = 2 * taps - 1
  const at = (i: number) => (i >= 0 && i < n ? input[i] : 0)
  for (let i = 0; i < out.length; i++) {
    const c = 2 * i
    let acc = center * input[c]
    if (c >= reach && c + reach < n) {
      for (let j = 0, k = 1; j < taps; j++, k += 2) acc += odd[j] * (input[c - k] + input[c + k])
    } else {
      for (let j = 0, k = 1; j < taps; j++, k += 2) acc += odd[j] * (at(c - k) + at(c + k))
    }
    out[i] = acc >= 32767 ? 32767 : acc <= -32768 ? -32768 : acc
  }
  return out
}

function toMono(left: Int16Array, right: Int16Array): Int16Array {
  const out = new Int16Array(left.length)
  for (let i = 0; i < out.length; i++) out[i] = (left[i] + right[i]) >> 1
  return out
}

export const DEFAULT_CHANNEL: MixerChannel = { volume: 1, pan: 0, mute: false, solo: false }

function toInt16(data: Float32Array): Int16Array {
  const out = new Int16Array(data.length)
  for (let i = 0; i < data.length; i++) {
    const v = data[i] * 32768
    out[i] = v >= 32767 ? 32767 : v <= -32768 ? -32768 : v
  }
  return out
}

/**
 * WAV PCM de 16 bits a la frecuencia del reproductor, o al doble (las pistas que separa la app del
 * celular): se leen directo, sin decodeAudioData (que las pasaría a 32 bits y usaría el doble de
 * memoria).
 */
function parseWav16(buffer: ArrayBuffer, sampleRate: number): Int16Array[] | null {
  if (buffer.byteLength < 44) return null
  const view = new DataView(buffer)
  const tag = (offset: number) => String.fromCharCode(view.getUint8(offset), view.getUint8(offset + 1),
    view.getUint8(offset + 2), view.getUint8(offset + 3))
  if (tag(0) !== 'RIFF' || tag(8) !== 'WAVE') return null
  let pos = 12
  let channels = 0
  let rate = 0
  let bits = 0
  let format = 0
  while (pos + 8 <= buffer.byteLength) {
    const id = tag(pos)
    const size = view.getUint32(pos + 4, true)
    if (id === 'fmt ') {
      format = view.getUint16(pos + 8, true)
      channels = view.getUint16(pos + 10, true)
      rate = view.getUint32(pos + 12, true)
      bits = view.getUint16(pos + 22, true)
    } else if (id === 'data') {
      if (format !== 1 || bits !== 16 || (rate !== sampleRate && rate !== 2 * sampleRate) || channels < 1 || channels > 2) return null
      const start = pos + 8
      const frames = Math.floor(Math.min(size, buffer.byteLength - start) / (2 * channels))
      const out = Array.from({ length: channels }, () => new Int16Array(frames))
      const data = new Int16Array(buffer, start, frames * channels)
      if (channels === 1) out[0].set(data)
      else {
        const [l, r] = out
        for (let i = 0, j = 0; i < frames; i++, j += 2) {
          l[i] = data[j]
          r[i] = data[j + 1]
        }
      }
      return rate === sampleRate ? out : out.map(halve)
    }
    pos += 8 + size + (size & 1)
  }
  return null
}

async function fetchWithProgress(
  url: string,
  onProgress: (loaded: number, total: number) => void,
  signal?: AbortSignal,
): Promise<ArrayBuffer> {
  const response = await fetch(url, { signal })
  if (!response.ok) throw new Error(`No se pudo descargar la pista (${response.status})`)
  const total = Number(response.headers.get('content-length')) || 0
  if (!response.body || !total) {
    const buffer = await response.arrayBuffer()
    onProgress(buffer.byteLength, buffer.byteLength)
    return buffer
  }
  const reader = response.body.getReader()
  const out = new Uint8Array(total)
  let received = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    if (received + value.length > out.length) {
      // El servidor mandó más de lo anunciado: se arma de otra forma.
      const bigger = new Uint8Array(Math.max(out.length * 2, received + value.length))
      bigger.set(out.subarray(0, received))
      return finishGrowing(reader, bigger, received, value, onProgress, total)
    }
    out.set(value, received)
    received += value.length
    onProgress(received, total)
  }
  return out.buffer.slice(0, received) as ArrayBuffer
}

async function finishGrowing(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  buffer: Uint8Array,
  received: number,
  pending: Uint8Array,
  onProgress: (loaded: number, total: number) => void,
  total: number,
): Promise<ArrayBuffer> {
  let out = buffer
  let chunk: Uint8Array | undefined = pending
  while (chunk) {
    if (received + chunk.length > out.length) {
      const bigger = new Uint8Array((received + chunk.length) * 2)
      bigger.set(out.subarray(0, received))
      out = bigger
    }
    out.set(chunk, received)
    received += chunk.length
    onProgress(Math.min(received, total), total)
    const next = await reader.read()
    chunk = next.done ? undefined : next.value
  }
  return out.buffer.slice(0, received) as ArrayBuffer
}

function makeClick(ctx: BaseAudioContext, accent: boolean): AudioBuffer {
  const sr = ctx.sampleRate
  const length = Math.round(0.05 * sr)
  const buffer = ctx.createBuffer(1, length, sr)
  const data = buffer.getChannelData(0)
  const freq = accent ? 1800 : 1250
  const decay = accent ? 0.012 : 0.009
  const level = accent ? 0.9 : 0.6
  for (let i = 0; i < length; i++) {
    const t = i / sr
    const env = Math.exp(-t / decay) * Math.min(1, t / 0.0005)
    data[i] = (Math.sin(2 * Math.PI * freq * t) + 0.35 * Math.sin(2 * Math.PI * freq * 2.01 * t)) * env * level
  }
  return buffer
}

export class StemPlayer {
  readonly ctx: AudioContext
  duration = 0
  private channels: Channel[] = []
  private master: GainNode
  private clickBus: GainNode
  private limiter: DynamicsCompressorNode
  private seg: Segment = { output: 0, input: 0, rate: 1, active: false }
  private rate = 1
  private pitch = 0
  private loopRange: LoopRange | null = null
  private grid: BeatGrid = { beats: [], accents: [], beatsPerBar: 4 }
  private metronomeOn = false
  private countInBars = 0
  private clicks: { accent: AudioBuffer; normal: AudioBuffer }
  private defaultClicks: { accent: AudioBuffer; normal: AudioBuffer }
  private guideBus: GainNode
  private guide: GuideVoice[] = []
  private guideTimes: number[] = []
  private countVoices: (AudioBuffer | undefined)[] = []
  private guideOn = false
  private scheduled: { node: AudioBufferSourceNode; time: number }[] = []
  private scheduledUntil = 0
  private timer: number | undefined
  private listeners = new Set<() => void>()
  private destroyed = false

  /** mono: las pistas estéreo se escuchan en mono (para ahorrar memoria, ver playbackQuality). */
  readonly mono: boolean

  static create(quality: PlaybackQuality = { sampleRate: 44100, mono: false }): StemPlayer {
    let ctx: AudioContext
    try {
      ctx = new AudioContext({ sampleRate: quality.sampleRate, latencyHint: 'interactive' })
    } catch {
      ctx = new AudioContext({ latencyHint: 'interactive' })
    }
    return new StemPlayer(ctx, quality.mono)
  }

  private constructor(ctx: AudioContext, mono: boolean) {
    this.ctx = ctx
    this.mono = mono
    this.limiter = ctx.createDynamicsCompressor()
    this.limiter.threshold.value = -1.5
    this.limiter.knee.value = 0
    this.limiter.ratio.value = 20
    this.limiter.attack.value = 0.002
    this.limiter.release.value = 0.15
    this.master = ctx.createGain()
    this.clickBus = ctx.createGain()
    this.clickBus.gain.value = 0.7
    this.guideBus = ctx.createGain()
    this.guideBus.gain.value = 0.9
    this.master.connect(this.limiter)
    this.clickBus.connect(this.limiter)
    this.guideBus.connect(this.limiter)
    this.limiter.connect(ctx.destination)
    this.defaultClicks = { accent: makeClick(ctx, true), normal: makeClick(ctx, false) }
    this.clicks = this.defaultClicks
    this.timer = window.setInterval(() => this.tick(), TICK_MS)
  }

  // ---- carga ------------------------------------------------------------------------------

  async load(
    stems: StemSource[],
    initial: Partial<Record<string, MixerChannel>>,
    onProgress?: (fraction: number, message: string) => void,
    signal?: AbortSignal,
  ): Promise<void> {
    const loaded = new Map<string, number>()
    const totals = new Map<string, number>()
    const report = () => {
      let done = 0
      let total = 0
      for (const stem of stems) {
        done += loaded.get(stem.id) ?? 0
        total += totals.get(stem.id) ?? 1
      }
      onProgress?.(0.75 * (total ? done / total : 0), 'Descargando pistas…')
    }
    onProgress?.(0, 'Descargando pistas…')
    // Pistas del mismo celular: de a una (así nunca están todas juntas en memoria dos veces).
    const local = stems.every((stem) => stem.url.includes('/_capacitor_file_'))
    const fetchStem = (stem: StemSource, index: number) => fetchWithProgress(stem.url, (l, t) => {
      if (local) {
        onProgress?.((index + 0.9 * (t ? l / t : 1)) / stems.length, `Cargando ${stem.name}…`)
        return
      }
      loaded.set(stem.id, l)
      totals.set(stem.id, t)
      report()
    }, signal)
    const buffers: (Promise<ArrayBuffer> | null)[] = local ? stems.map(() => null) : stems.map(fetchStem)
    for (let i = 0; i < stems.length; i++) {
      if (signal?.aborted || this.destroyed) throw new DOMException('Cancelado', 'AbortError')
      const stem = stems[i]
      const buffer = await (buffers[i] ?? fetchStem(stem, i))
      buffers[i] = null
      onProgress?.(local ? (i + 0.9) / stems.length : 0.75 + (0.25 * i) / stems.length, `Preparando ${stem.name}…`)
      let data = parseWav16(buffer, this.ctx.sampleRate)
      if (data) {
        this.duration = Math.max(this.duration, data[0].length / this.ctx.sampleRate)
      } else {
        const decoded = await this.ctx.decodeAudioData(buffer)
        this.duration = Math.max(this.duration, decoded.duration)
        data = []
        for (let c = 0; c < Math.min(2, decoded.numberOfChannels); c++) data.push(toInt16(decoded.getChannelData(c)))
      }
      if (this.mono && data.length === 2) data = [toMono(data[0], data[1])]
      const node = await SignalsmithStretch(this.ctx)
      await node.addBuffers(data, data.map((d) => d.buffer))
      const gain = this.ctx.createGain()
      const pan = this.ctx.createStereoPanner()
      const analyser = this.ctx.createAnalyser()
      analyser.fftSize = 512
      node.connect(gain)
      gain.connect(pan)
      pan.connect(this.master)
      pan.connect(analyser)
      this.channels.push({
        id: stem.id, node, gain, pan, analyser,
        scratch: new Float32Array(analyser.fftSize),
        state: { ...DEFAULT_CHANNEL, ...(initial[stem.id] ?? {}) },
      })
    }
    this.applyGains(true)
    this.applySegment({ output: this.ctx.currentTime, input: 0, rate: this.rate, active: false })
    onProgress?.(1, 'Listo')
  }

  // ---- línea de tiempo ------------------------------------------------------------------------

  private positionAt(t: number): number {
    const s = this.seg
    let p = s.input
    if (s.active && t > s.output) p = s.input + (t - s.output) * s.rate
    const loop = this.loopRange
    if (loop && s.active && s.input < loop.end && p >= loop.end) {
      const len = loop.end - loop.start
      if (len > 0.05) p = loop.start + ((p - loop.start) % len)
    }
    return p
  }

  private outputDelay(): number {
    const ctx = this.ctx as AudioContext & { outputLatency?: number }
    return ctx.outputLatency || ctx.baseLatency || 0
  }

  /** Posición que se está escuchando ahora (segundos de la canción original). */
  get position(): number {
    return clamp(this.positionAt(this.ctx.currentTime - this.outputDelay()), 0, this.duration)
  }

  /** Si se carga con menos calidad que la original (canción larga en un celular con poca memoria). */
  get reduced(): boolean {
    return this.mono || this.ctx.sampleRate < 44100
  }

  get playing(): boolean {
    return this.seg.active
  }

  /** True durante la cuenta regresiva (antes de que arranque la música). */
  get countingIn(): boolean {
    return this.seg.active && this.seg.output > this.ctx.currentTime
  }

  get loop(): LoopRange | null {
    return this.loopRange
  }

  private applySegment(seg: Segment, resetClicks = true) {
    const pausing = !seg.active && this.seg.active
    this.seg = seg
    const loop = this.loopRange
    const payload = {
      output: seg.output, input: seg.input, rate: seg.rate, active: seg.active, semitones: this.pitch,
      loopStart: loop?.start ?? 0, loopEnd: loop?.end ?? 0,
    }
    for (const ch of this.channels) void ch.node.schedule(payload)
    if (resetClicks || pausing) this.resetClicks(pausing)
    this.emit()
  }

  /** Continuar la línea de tiempo con nuevos parámetros sin saltos. */
  private continuePoint(): { t: number; p: number } {
    const t = this.ctx.currentTime + LEAD
    if (this.seg.active && this.seg.output > t) return { t: this.seg.output, p: this.seg.input }
    return { t, p: this.positionAt(t) }
  }

  private beatPeriodAt(p: number): number {
    const beats = this.grid.beats
    if (beats.length >= 2) {
      let i = lowerBound(beats, p)
      i = clamp(i, 1, beats.length - 1)
      const period = beats[i] - beats[i - 1]
      if (period > 0.2 && period < 2) return period
    }
    return 0.5
  }

  async play(): Promise<void> {
    if (this.seg.active || !this.channels.length) return
    if (this.ctx.state !== 'running') await this.ctx.resume()
    let p = this.seg.input
    if (p >= this.duration - 0.05) p = this.loopRange?.start ?? 0
    const now = this.ctx.currentTime
    let start = now + LEAD
    const countIn: { time: number; accent: boolean }[] = []
    if (this.countInBars > 0) {
      const perBar = this.grid.beatsPerBar || 4
      const n = this.countInBars * perBar
      const beat = this.beatPeriodAt(p) / this.rate
      // La cuenta termina justo un pulso antes del próximo pulso de la canción.
      const next = this.grid.beats[lowerBound(this.grid.beats, p)]
      const delta = next !== undefined && next - p < beat * this.rate * 1.01 ? (next - p) / this.rate : beat
      start = now + LEAD + n * beat - delta
      if (start < now + LEAD) start = now + LEAD
      for (let k = n; k >= 1; k--) {
        countIn.push({ time: start + delta - k * beat, accent: (n - k) % perBar === 0 })
      }
    }
    this.applySegment({ output: start, input: p, rate: this.rate, active: true })
    for (const c of countIn) if (c.time >= now) this.scheduleClick(c.time, c.accent)
    this.scheduleCountVoices(countIn.map((c) => c.time), now)
  }

  /** Con la Guía: en el último compás de la cuenta un número por pulso; en los anteriores "1 … 2 …". */
  private scheduleCountVoices(times: number[], now: number): void {
    const perBar = this.grid.beatsPerBar || 4
    if (!this.guideOn || !times.length) return
    for (let i = 1; i <= perBar; i++) if (!this.countVoices[i - 1]) return
    const bars = Math.max(1, Math.floor(times.length / perBar))
    times.forEach((time, index) => {
      const bar = Math.floor(index / perBar)
      const beat = index % perBar
      let number = beat + 1
      if (bar < bars - 1) {
        const half = perBar % 2 === 0 ? perBar / 2 : perBar
        if (beat % half !== 0) return
        number = beat / half + 1
      }
      const voice = this.countVoices[number - 1]
      if (voice && time >= now) this.schedule(voice, this.guideBus, time)
    })
  }

  pause(): void {
    if (!this.seg.active) return
    const t = this.ctx.currentTime + LEAD
    const p = this.seg.output > t ? this.seg.input : this.positionAt(t)
    this.applySegment({ output: t, input: clamp(p, 0, this.duration), rate: this.rate, active: false })
  }

  toggle(): void {
    if (this.seg.active) this.pause()
    else void this.play()
  }

  seek(position: number): void {
    const p = clamp(position, 0, Math.max(0, this.duration - 0.01))
    const loop = this.loopRange
    if (loop && (p < loop.start - 0.05 || p >= loop.end)) this.loopRange = null
    if (this.seg.active) {
      this.applySegment({ output: this.ctx.currentTime + LEAD, input: p, rate: this.rate, active: true })
    } else {
      this.applySegment({ output: this.ctx.currentTime, input: p, rate: this.rate, active: false })
    }
  }

  skip(seconds: number): void {
    this.seek(this.position + seconds * this.rate)
  }

  setRate(rate: number): void {
    this.rate = clamp(rate, 0.25, 2)
    if (this.seg.active) {
      const { t, p } = this.continuePoint()
      this.applySegment({ output: t, input: p, rate: this.rate, active: true })
    } else {
      this.applySegment({ ...this.seg, output: this.ctx.currentTime, rate: this.rate })
    }
  }

  get currentRate(): number {
    return this.rate
  }

  setPitch(semitones: number): void {
    this.pitch = clamp(semitones, -24, 24)
    if (this.seg.active) {
      const { t, p } = this.continuePoint()
      this.applySegment({ output: t, input: p, rate: this.rate, active: true }, false)
    } else {
      this.applySegment({ ...this.seg, output: this.ctx.currentTime }, false)
    }
  }

  setLoop(range: LoopRange | null): void {
    const valid = range && range.end - range.start >= 0.25 ? { start: range.start, end: Math.min(range.end, this.duration) } : null
    this.loopRange = valid
    const pos = this.position
    if (valid && (pos < valid.start || pos >= valid.end)) {
      this.seek(valid.start)
      return
    }
    if (this.seg.active) {
      const { t, p } = this.continuePoint()
      this.applySegment({ output: t, input: p, rate: this.rate, active: true })
    } else {
      this.applySegment({ ...this.seg, output: this.ctx.currentTime })
    }
  }

  // ---- mezclador ----------------------------------------------------------------------------

  setChannel(id: string, patch: Partial<MixerChannel>): void {
    const ch = this.channels.find((c) => c.id === id)
    if (!ch) return
    ch.state = { ...ch.state, ...patch }
    this.applyGains()
  }

  setChannels(states: Partial<Record<string, MixerChannel>>): void {
    for (const ch of this.channels) ch.state = { ...DEFAULT_CHANNEL, ...(states[ch.id] ?? {}) }
    this.applyGains()
  }

  private applyGains(immediate = false): void {
    const anySolo = this.channels.some((c) => c.state.solo)
    const now = this.ctx.currentTime
    for (const ch of this.channels) {
      const audible = anySolo ? ch.state.solo : !ch.state.mute
      const gain = audible ? ch.state.volume : 0
      if (immediate) {
        ch.gain.gain.value = gain
        ch.pan.pan.value = ch.state.pan
      } else {
        ch.gain.gain.setTargetAtTime(gain, now, 0.015)
        ch.pan.pan.setTargetAtTime(ch.state.pan, now, 0.015)
      }
    }
  }

  setMasterVolume(volume: number): void {
    this.master.gain.setTargetAtTime(clamp(volume, 0, 2), this.ctx.currentTime, 0.02)
  }

  /** Nivel de cada pista (0-1, escala logarítmica de -60 a 0 dBFS). */
  levels(): Record<string, number> {
    const out: Record<string, number> = {}
    for (const ch of this.channels) {
      ch.analyser.getFloatTimeDomainData(ch.scratch)
      let sum = 0
      for (let i = 0; i < ch.scratch.length; i++) sum += ch.scratch[i] * ch.scratch[i]
      const rms = Math.sqrt(sum / ch.scratch.length)
      const db = 20 * Math.log10(rms + 1e-9)
      out[ch.id] = clamp((db + 60) / 60, 0, 1)
    }
    return out
  }

  // ---- metrónomo --------------------------------------------------------------------------

  setGrid(grid: BeatGrid): void {
    this.grid = grid
    this.resetClicks()
  }

  setMetronome(on: boolean): void {
    this.metronomeOn = on
    this.resetClicks()
  }

  setMetronomeVolume(volume: number): void {
    this.clickBus.gain.setTargetAtTime(clamp(volume, 0, 2), this.ctx.currentTime, 0.02)
  }

  setCountIn(bars: number): void {
    this.countInBars = Math.max(0, Math.round(bars))
  }

  /** Sonidos del click (los del paquete de voces); sin alguno, los de MoiMoi. */
  setClickSounds(accent: AudioBuffer | null, beat: AudioBuffer | null): void {
    this.clicks = accent && beat ? { accent, normal: beat } : this.defaultClicks
    this.resetClicks()
  }

  /** La Guía: las voces en su lugar y los números para la cuenta (count[0] = "1"). */
  setGuide(voices: GuideVoice[], count: (AudioBuffer | undefined)[]): void {
    this.guide = [...voices].sort((a, b) => a.time - b.time)
    this.guideTimes = this.guide.map((v) => v.time)
    this.countVoices = count
    this.resetClicks()
  }

  setGuideOn(on: boolean): void {
    this.guideOn = on
    this.resetClicks()
  }

  setGuideVolume(volume: number): void {
    this.guideBus.gain.setTargetAtTime(clamp(volume, 0, 2), this.ctx.currentTime, 0.02)
  }

  /** Cancela lo programado que todavía no sonó (o todo, al pausar: que no siga una voz). */
  private resetClicks(all = false): void {
    const now = this.ctx.currentTime
    for (const c of this.scheduled) {
      if (all || c.time > now) {
        try {
          c.node.stop()
        } catch {
          // ya terminó
        }
        c.node.disconnect()
      }
    }
    this.scheduled = all ? [] : this.scheduled.filter((c) => c.time <= now)
    this.scheduledUntil = 0
  }

  private scheduleClick(time: number, accent: boolean): void {
    this.schedule(accent ? this.clicks.accent : this.clicks.normal, this.clickBus, time)
  }

  private schedule(buffer: AudioBuffer, bus: GainNode, time: number): void {
    const src = this.ctx.createBufferSource()
    src.buffer = buffer
    src.connect(bus)
    src.start(time)
    const entry = { node: src, time }
    this.scheduled.push(entry)
    src.onended = () => {
      src.disconnect()
      this.scheduled = this.scheduled.filter((c) => c !== entry)
    }
  }

  /** Tramos de la canción que suenan entre `from` y `to` (hora del contexto), con loops. */
  private pieces(from: number, to: number): [number, number, number][] {
    const r = this.seg.rate
    const pFrom = this.positionAt(from)
    const span = (to - from) * r
    const loop = this.loopRange
    if (loop && this.seg.input < loop.end && pFrom < loop.end && pFrom + span >= loop.end) {
      const wrap = from + (loop.end - pFrom) / r
      return [[pFrom, loop.end, from], [loop.start, loop.start + (pFrom + span - loop.end), wrap]]
    }
    return [[pFrom, pFrom + span, from]]
  }

  private tick(): void {
    if (this.destroyed) return
    const now = this.ctx.currentTime
    if (this.seg.active && !this.loopRange && now > this.seg.output && this.positionAt(now) >= this.duration) {
      this.applySegment({ output: now, input: this.duration, rate: this.rate, active: false })
      return
    }
    const clicks = this.metronomeOn && this.grid.beats.length > 0
    const voices = this.guideOn && this.guide.length > 0
    if (!this.seg.active || (!clicks && !voices)) return
    const lookahead = document.visibilityState === 'hidden' ? 2.0 : 0.25
    const from = Math.max(this.scheduledUntil, now, this.seg.output)
    const to = now + lookahead
    if (to <= from) return
    const beats = this.grid.beats
    for (const [a, b, t0] of this.pieces(from, to)) {
      if (clicks) {
        for (let i = lowerBound(beats, a); i < beats.length && beats[i] < b; i++) {
          this.scheduleClick(t0 + (beats[i] - a) / this.seg.rate, this.grid.accents[i] ?? false)
        }
      }
      if (voices) {
        for (let i = lowerBound(this.guideTimes, a); i < this.guide.length && this.guideTimes[i] < b; i++) {
          this.schedule(this.guide[i].buffer, this.guideBus, t0 + (this.guideTimes[i] - a) / this.seg.rate)
        }
      }
    }
    this.scheduledUntil = to
  }

  // ---- eventos ------------------------------------------------------------------------------

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  private emit(): void {
    for (const listener of this.listeners) listener()
  }

  destroy(): void {
    this.destroyed = true
    window.clearInterval(this.timer)
    this.resetClicks()
    for (const ch of this.channels) {
      try {
        ch.node.disconnect()
        void ch.node.dropBuffers()
      } catch {
        // nodo ya liberado
      }
    }
    this.channels = []
    this.listeners.clear()
    void this.ctx.close()
  }
}

export function lowerBound(values: number[], target: number): number {
  let lo = 0
  let hi = values.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (values[mid] < target) lo = mid + 1
    else hi = mid
  }
  return lo
}
