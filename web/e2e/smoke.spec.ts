import { expect, test } from '@playwright/test'

test('alarmas: adherencia e historial se ven con datos del contrato', async ({ page }) => {
  await page.goto('/')
  await expect(page).toHaveURL(/\/alarmas\/adherencia$/)
  const adherencia = page.getByRole('table', { name: 'Adherencia por tratamiento' })
  await expect(adherencia.getByRole('row', { name: /Juan/ })).toContainText('Amoxicilina')
  await page.screenshot({ path: 'e2e/screenshots/alarmas.png', fullPage: true })

  await page.getByRole('link', { name: 'Historial' }).click()
  const historial = page.getByRole('table', { name: 'Historial de dosis' })
  await expect(historial.getByRole('row')).toHaveCount(3)
  await page.screenshot({ path: 'e2e/screenshots/alarmas-historial.png', fullPage: true })
})
