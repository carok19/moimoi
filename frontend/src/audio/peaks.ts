/** Formas de onda precalculadas por el servidor (pico por tramo, uint8 en base64). */
export function decodePeaks(b64: string | undefined): Uint8Array {
  if (!b64) return new Uint8Array(0)
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

export interface WaveStyle {
  color: string
  /** Color de la parte ya reproducida (opcional). */
  playedColor?: string
  /** Escala vertical: 1 = picos reales; >1 realza pistas suaves. */
  gain?: number
  /** Tramos activos [inicio, fin] en segundos: fuera de ellos se dibuja más tenue. */
  active?: [number, number][]
  duration?: number
}

/** Dibuja la forma de onda (barras simétricas) ocupando todo el canvas. */
export function drawWave(canvas: HTMLCanvasElement, peaks: Uint8Array, style: WaveStyle): void {
  const dpr = window.devicePixelRatio || 1
  const width = Math.max(1, Math.round(canvas.clientWidth * dpr))
  const height = Math.max(1, Math.round(canvas.clientHeight * dpr))
  if (canvas.width !== width) canvas.width = width
  if (canvas.height !== height) canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, width, height)
  if (!peaks.length) return
  const bar = Math.max(1, Math.round(2 * dpr))
  const gap = Math.max(1, Math.round(1 * dpr))
  const step = bar + gap
  const columns = Math.floor(width / step)
  const mid = height / 2
  const gain = style.gain ?? 1
  let max = 0
  for (let i = 0; i < peaks.length; i++) if (peaks[i] > max) max = peaks[i]
  const norm = max > 0 ? 255 / max : 1
  for (let c = 0; c < columns; c++) {
    const a = Math.floor((c / columns) * peaks.length)
    const b = Math.max(a + 1, Math.floor(((c + 1) / columns) * peaks.length))
    let value = 0
    for (let i = a; i < b && i < peaks.length; i++) if (peaks[i] > value) value = peaks[i]
    const amp = Math.min(1, Math.pow((value * norm) / 255, 0.8) * gain)
    const h = Math.max(dpr, amp * (height - 2 * dpr))
    let alpha = 1
    if (style.active && style.duration) {
      const t = ((c + 0.5) / columns) * style.duration
      alpha = style.active.some(([s, e]) => t >= s && t <= e) ? 1 : 0.35
    }
    ctx.globalAlpha = alpha
    ctx.fillStyle = style.color
    ctx.fillRect(c * step, mid - h / 2, bar, h)
  }
  ctx.globalAlpha = 1
}
