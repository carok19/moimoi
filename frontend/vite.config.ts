import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * Parche a signalsmith-stretch (MIT) para que acepte pistas en PCM de 16 bits (Int16Array):
 * así cada canción ocupa la mitad de memoria en el navegador; y para que a velocidad normal y en
 * el tono original la pista salga directa, sin procesar. El código del procesador se
 * convierte a texto y se ejecuta dentro del AudioWorklet, por eso el parche va sobre el
 * código fuente del paquete.
 */
function signalsmithInt16(): Plugin {
  const original = `buffer.subarray(blockSamples).set(channelBuffer.subarray(startIndex, startIndex + count));`
  const patched = `let source = channelBuffer.subarray(startIndex, startIndex + count);
							if (source instanceof Int16Array) {
								let target = buffer.subarray(blockSamples, blockSamples + count);
								for (let i = 0; i < count; ++i) target[i] = source[i]*(1/32768);
							} else {
								buffer.subarray(blockSamples).set(source);
							}`
  // Sin cambio de velocidad ni de tono (lo más común), la pista sale tal cual, sin pasar por el
  // estirador: casi no gasta procesador (en el celular son 6 pistas) y suena igual al original.
  // Lo que suena en currentTime es la posición del mapa en ese instante (con el tramo anterior si
  // el nuevo todavía no empezó), igual que con el estirador, que compensa sus latencias. Al volver
  // al estirador se reinicia para no mezclar audio viejo.
  const shiftPattern = /(let outputTime = currentTime \+ this\.outputLatencySeconds;\s*while \(this\.timeMap\.length > 1 && this\.timeMap\[1\]\.output <= outputTime\) \{\s*)this\.timeMap\.shift\(\);/
  const directAnchor = 'let inputTime = currentMapSegment.input + (outputTime - currentMapSegment.output)*currentMapSegment.rate;'
  // En pausa, el estirador procesa silencio sin parar (en las 6 pistas): pasado medio segundo, cuando
  // ya terminó de sonar lo último, se deja de procesar; al volver a sonar se reinicia.
  const idleAnchor = 'if (!currentMapSegment.active) {'
  const idle = `if (!currentMapSegment.active && (this.moimoiIdle = (this.moimoiIdle || 0) + outputBlockSize) > sampleRate*0.5) {
          outputList[0].forEach(out => out.fill(0));
          return true;
        }
        ${idleAnchor}`
  const wake = `if (this.moimoiIdle > sampleRate*0.5 && !this.moimoiDirect) wasmModule._reset();
        this.moimoiIdle = 0;
        `
  const direct = `${wake}if (currentMapSegment.rate == 1 && !currentMapSegment.semitones && !currentMapSegment.formantSemitones) {
          let seg = currentTime < currentMapSegment.output && this.moimoiPrev ? this.moimoiPrev : currentMapSegment;
          let fadeIn = !this.moimoiDirect;
          this.moimoiDirect = true;
          if (!seg.active) {
            outputList[0].forEach(out => out.fill(0));
            return true;
          }
          let pos = seg.input + (currentTime - seg.output)*seg.rate;
          let loopLength = seg.loopEnd - seg.loopStart;
          if (loopLength > 0 && pos >= seg.loopEnd) pos = seg.loopStart + ((pos - seg.loopStart) % loopLength);
          let first = Math.round(pos*sampleRate);
          let loopStartSamples = Math.round(seg.loopStart*sampleRate);
          let loopEndSamples = Math.round(seg.loopEnd*sampleRate);
          let looping = loopEndSamples - loopStartSamples > 0;
          outputList[0].forEach((out, c) => {
            out.fill(0);
            let written = 0;
            while (written < out.length) {
              let at = first + written;
              let room = out.length - written;
              if (looping && at >= loopEndSamples) at -= loopEndSamples - loopStartSamples;
              if (looping && at < loopEndSamples) room = Math.min(room, loopEndSamples - at);
              if (at < this.audioBuffersStart) {
                written += Math.min(room, this.audioBuffersStart - at);
                continue;
              }
              let chunkStart = this.audioBuffersStart;
              let k = 0;
              while (k < this.audioBuffers.length && at >= chunkStart + this.audioBuffers[k][0].length) {
                chunkStart += this.audioBuffers[k][0].length;
                ++k;
              }
              if (k >= this.audioBuffers.length) break;
              let chunk = this.audioBuffers[k];
              let source = chunk[c%chunk.length];
              let offset = at - chunkStart;
              let count = Math.min(room, source.length - offset);
              if (source instanceof Int16Array) {
                for (let i = 0; i < count; ++i) out[written + i] = source[offset + i]*(1/32768);
              } else {
                out.set(source.subarray(offset, offset + count), written);
              }
              written += count;
            }
            if (fadeIn) for (let i = 0; i < out.length; ++i) out[i] *= i/out.length;
          });
          this.timeIntervalCounter -= outputBlockSize;
          if (this.timeIntervalCounter <= 0) {
            this.timeIntervalCounter = this.timeIntervalSamples;
            this.port.postMessage(['time', pos]);
          }
          return true;
        }
        if (this.moimoiDirect) {
          this.moimoiDirect = false;
          wasmModule._reset();
        }
        ${directAnchor}`
  return {
    name: 'moimoi-signalsmith-int16',
    enforce: 'pre',
    transform(code, id) {
      if (!id.includes('signalsmith-stretch') || !id.includes('SignalsmithStretch')) return null
      if (code.includes('source instanceof Int16Array')) return null
      if (!code.includes(original) || code.split(directAnchor).length !== 2 || code.split(idleAnchor).length !== 2
          || !shiftPattern.test(code)) {
        throw new Error('signalsmith-stretch cambió: revisar el parche en vite.config.ts')
      }
      const out = code.replace(original, patched).replace(shiftPattern, '$1this.moimoiPrev = this.timeMap.shift();')
        .replace(directAnchor, direct).replace(idleAnchor, idle)
      return { code: out, map: null }
    },
  }
}

const backend = process.env.MOIMOI_BACKEND ?? 'http://127.0.0.1:4747'

export default defineConfig({
  plugins: [signalsmithInt16(), react()],
  // El procesador del AudioWorklet se serializa como texto: no se puede transpilar a
  // sintaxis vieja (los helpers que agregaría no existen dentro del worklet).
  build: { target: 'es2022', chunkSizeWarningLimit: 900 },
  esbuild: { target: 'es2022' },
  optimizeDeps: { exclude: ['signalsmith-stretch'], esbuildOptions: { target: 'es2022' } },
  server: {
    port: 5173,
    proxy: { '/api': backend },
  },
})
