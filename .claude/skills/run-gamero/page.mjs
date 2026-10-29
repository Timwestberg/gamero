// Drive the catalog web UI that the Gamero desktop app embeds, in a headless Chromium.
// Usage: node page.mjs <url> <out.png> [click text ...]
// Clicks each text in order (role button/link/tab first, then any text), then screenshots.
// No native host is attached, so desktop-only features show their "not available" states.
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const platform = process.env.GAME_PLATFORM_ROOT ?? resolve(here, '../../../../game-platform');
const { chromium } = createRequire(resolve(platform, 'apps/game-bridge-host/package.json'))('playwright');

const [url, out, ...clicks] = process.argv.slice(2);
if (!url || !out) {
  console.error('usage: node page.mjs <url> <out.png> [click text ...]');
  process.exit(2);
}

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { height: 900, width: 1280 } });
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(url, { waitUntil: 'networkidle' });
  for (const text of clicks) {
    const byRole = page.getByRole('button', { name: text }).or(page.getByRole('link', { name: text })).or(page.getByRole('tab', { name: text }));
    const target = (await byRole.count()) > 0 ? byRole.first() : page.getByText(text, { exact: false }).first();
    await target.click({ timeout: 10_000 });
    await page.waitForLoadState('networkidle');
  }
  await page.screenshot({ path: out });
  const heading = await page.locator('h1, h2').first().textContent().catch(() => null);
  console.log(JSON.stringify({ errors, heading: heading?.trim() ?? null, screenshot: out, title: await page.title(), url: page.url() }));
} finally {
  await browser.close();
}
