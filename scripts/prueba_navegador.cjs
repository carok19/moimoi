/*
 * Prueba de punta a punta de la interfaz en Chromium (Playwright).
 *
 * 1. Levanta el servidor de prueba (motor de separación falso, no necesita modelos):
 *      .venv/bin/python backend/tests/e2e_server.py --port 4799 --data /tmp/moimoi-e2e --wav /tmp/demo.wav
 * 2. Corre esta prueba (Playwright instalado de forma global: npm i -g playwright):
 *      NODE_PATH="$(npm root -g)" node scripts/prueba_navegador.cjs /tmp/demo.wav
 *
 * Sube la canción, espera a que se procese, la abre, reproduce, cambia velocidad y tono,
 * usa mute/solo, metrónomo y loop, exporta el paquete para Multitrack y revisa ajustes.
 * Guarda capturas en ./capturas-prueba/.
 */
const { chromium } = require('playwright')
const fs = require('fs')
const path = require('path')

const WAV = process.argv[2]
const BASE = process.env.MOIMOI_URL || 'http://127.0.0.1:4799'
const OUT = path.resolve('capturas-prueba')

if (!WAV || !fs.existsSync(WAV)) {
  console.error('Uso: node scripts/prueba_navegador.cjs <archivo.wav>')
  process.exit(2)
}
fs.mkdirSync(OUT, { recursive: true })

;(async () => {
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))
  let failed = false
  const step = async (name, fn) => {
    const started = Date.now()
    try {
      await fn()
      console.log(`OK    ${name} (${Date.now() - started} ms)`)
    } catch (err) {
      failed = true
      console.log(`FALLA ${name}: ${String(err.message || err).split('\n')[0]}`)
      await page.screenshot({ path: path.join(OUT, `falla-${name.replace(/\W+/g, '_')}.png`) })
      throw err
    }
  }
  try {
    await step('biblioteca', async () => {
      await page.goto(BASE + '/')
      await page.getByRole('heading', { name: 'Biblioteca' }).waitFor()
    })
    await step('subir archivo', async () => {
      await page.getByRole('tab', { name: /Subir archivo/ }).click()
      await page.setInputFiles('input[type=file]', WAV)
      await page.getByText(/subida: separando/).waitFor({ timeout: 60000 })
    })
    await step('canción lista', async () => {
      await page.locator('.song-card .chip.accent').first().waitFor({ timeout: 300000 })
      await page.screenshot({ path: path.join(OUT, 'biblioteca.png') })
    })
    await step('abrir reproductor', async () => {
      await page.locator('.song-card').first().click()
      await page.locator('button.play').waitFor({ timeout: 120000 })
    })
    await step('reproducir', async () => {
      await page.locator('button.play').click()
      await page.waitForTimeout(2500)
      const t = await page.locator('.transport .time span').textContent()
      if (!t || t === '0:00') throw new Error('el tiempo no avanza: ' + t)
    })
    await step('velocidad y tono', async () => {
      await page.getByRole('button', { name: /Más rápido/ }).click()
      await page.getByRole('button', { name: /Subir medio tono/ }).click()
      const speed = await page.locator('.stepper .label b').first().textContent()
      if (!speed.includes('105%')) throw new Error('velocidad: ' + speed)
    })
    await step('mute y solo', async () => {
      const rows = page.locator('.stem-row')
      await rows.nth(0).locator('button.mute').click()
      await rows.nth(1).locator('button.solo').click()
      if (!(await rows.nth(2).getAttribute('class')).includes('dim')) throw new Error('solo no silenció las demás')
      await rows.nth(1).locator('button.solo').click()
    })
    await step('metrónomo y loop', async () => {
      await page.getByRole('button', { name: /Metrónomo/ }).first().click()
      await page.locator('.timeline .section').nth(1).dblclick()
      await page.waitForTimeout(500)
      if (!/Loop/.test(await page.locator('.timeline .times').textContent())) throw new Error('no se marcó el loop')
      await page.screenshot({ path: path.join(OUT, 'reproductor.png') })
    })
    await step('exportar para Multitrack', async () => {
      await page.getByRole('button', { name: /^Exportar$/ }).click()
      const [download] = await Promise.all([
        page.waitForEvent('download', { timeout: 300000 }),
        page.getByRole('button', { name: /Exportar para Multitrack/ }).click(),
      ])
      const target = path.join(OUT, download.suggestedFilename())
      await download.saveAs(target)
      console.log('      →', target)
      await page.keyboard.press('Escape')
    })
    await step('ajustes', async () => {
      await page.goto(BASE + '/#/ajustes')
      await page.getByRole('heading', { name: 'Mi banda' }).waitFor()
    })
  } catch {
    // ya se informó el paso que falló
  } finally {
    console.log('Errores en la consola del navegador:', errors.length ? errors : 'ninguno')
    await browser.close()
    process.exit(failed || errors.length ? 1 : 0)
  }
})()
