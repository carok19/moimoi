import type { CapacitorConfig } from '@capacitor/cli'

// App de Android: la misma interfaz web, empaquetada. Se conecta a MoiMoi en la computadora
// por la red WiFi (http://IP:4747), donde se hace la separación.
const config: CapacitorConfig = {
  appId: 'com.moimoi.app',
  appName: 'MoiMoi',
  webDir: 'dist',
  backgroundColor: '#0d0f13',
  server: {
    // Origen http://localhost: es un "contexto seguro" (motor de audio, micrófono) y puede
    // hablar con la computadora por http sin bloqueos de contenido mixto.
    androidScheme: 'http',
    cleartext: true,
  },
  android: {
    allowMixedContent: true,
  },
  plugins: {
    // Pantalla de borde a borde (Android 15+): la interfaz usa env(safe-area-inset-*).
    SystemBars: { style: 'DARK', initialViewportFitValueHint: 'cover' },
  },
}

export default config
