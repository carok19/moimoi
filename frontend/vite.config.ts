import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * Parche a signalsmith-stretch (MIT) para que acepte pistas en PCM de 16 bits (Int16Array):
 * así cada canción ocupa la mitad de memoria en el navegador. El código del procesador se
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
  return {
    name: 'moimoi-signalsmith-int16',
    enforce: 'pre',
    transform(code, id) {
      if (!id.includes('signalsmith-stretch') || !id.includes('SignalsmithStretch')) return null
      if (code.includes('source instanceof Int16Array')) return null
      if (!code.includes(original)) {
        throw new Error('signalsmith-stretch cambió: revisar el parche Int16 en vite.config.ts')
      }
      return { code: code.replace(original, patched), map: null }
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
