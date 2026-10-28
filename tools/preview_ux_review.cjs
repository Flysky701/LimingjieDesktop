// Checkpoint A only: derive non-sensitive catalog samples and render the local HTML mockup.
// No application, ADB, account, network API or production data is accessed.
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '..');
const design = path.join(root, 'docs/archive/desktop/design');
const assetRoot = path.join(root, 'android/app/src/main/assets/resource-packs/cn-bilibili');
const catalog = JSON.parse(fs.readFileSync(path.join(assetRoot, 'labyrinth-role-decision.json'), 'utf8'));
const icons = JSON.parse(fs.readFileSync(path.join(assetRoot, 'icons.json'), 'utf8')).characterIcons;
const attributes = { FIRE: '火', WATER: '水', WIND: '风', LIGHT: '光', DARK: '暗' };
const ids = new Set(['1013', '1063', '1278']);
const sample = [...catalog.characters.filter(r => ids.has(r.characterId)),
  ...catalog.characters.filter(r => r.characterId !== '1000' && !ids.has(r.characterId))].slice(0, 24).map(r => {
  const candidates = icons.filter(i => i.ownerId === r.characterId);
  const icon = candidates.find(i => i.variant === '31') || candidates[0];
  assert(icon && fs.existsSync(path.join(assetRoot, icon.file)), `Missing portrait ${r.characterId}`);
  return { id: r.characterId, name: r.displayName, attribute: attributes[r.attribute] || '',
    job: r.roleClass || '', score: r.userScore ?? r.baseQuality ?? null, icon: icon.file };
});
fs.writeFileSync(path.join(design, 'ux-sample-roles.js'),
  '// Catalog and portraits are existing project assets. Personal scores in the mockup are examples.\n' +
  'const UX_SAMPLE_ROLES = ' + JSON.stringify(sample, null, 2) + ';\n');

const output = path.join(root, 'docs/archive/desktop/evidence/ux/design');
fs.mkdirSync(output, { recursive: true });
const views = ['idle', 'running', 'stopped', 'strategy', 'ratings'];
const report = { source: 'HTML mockup only; all run data is illustrative; no emulator connection', layouts: [], interactions: [] };

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage();
    const failures = [];
    page.on('pageerror', e => failures.push(e.message));
    for (const width of [1280, 1100]) for (const theme of ['light', 'dark']) for (const view of views) {
      const height = width === 1280 ? 800 : 700;
      await page.setViewportSize({ width, height });
      await page.goto(pathToFileURL(path.join(design, 'ux-review.html')).href + `?view=${view}&theme=${theme}&capture`);
      await page.evaluate(() => document.fonts.ready);
      const layout = await page.evaluate(() => ({
        overflow: document.documentElement.scrollWidth > innerWidth,
        footerBottom: document.querySelector('.footer').getBoundingClientRect().bottom,
        footerTop: document.querySelector('.footer').getBoundingClientRect().top,
        brokenImages: [...document.images].filter(i => !i.complete || i.naturalWidth === 0).length,
        horizontalOverflow: [...document.querySelectorAll('.card,.top,.footer,.ratings-tools,.page-heading')]
          .filter(e => e.scrollWidth > e.clientWidth + 1).map(e => e.className)
      }));
      report.layouts.push({ width, height, theme, view, ...layout });
      assert(!layout.overflow && !layout.brokenImages, JSON.stringify(report.layouts.at(-1)));
      assert.equal(layout.horizontalOverflow.length, 0, JSON.stringify(report.layouts.at(-1)));
      assert(Math.abs(layout.footerBottom - height) < 1 && layout.footerTop > 0, 'Footer must remain visible');
      if (width === 1280 || ['running', 'ratings'].includes(view))
        await page.screenshot({ path: path.join(output, `${view}-${theme}-${width}.png`) });
    }
    const url = pathToFileURL(path.join(design, 'ux-review.html')).href;
    await page.goto(url + '?view=idle');
    assert.equal(await page.locator('.task').count(), 5);
    await page.getByRole('button', { name: /执行入口流程/ }).click();
    assert.equal(await page.locator('.task.selected strong').textContent(), '执行入口流程');
    await page.getByRole('button', { name: '切换浅色与深色' }).click();
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark');
    await page.getByRole('button', { name: /编辑完整配置/ }).click();
    await page.getByRole('dialog').waitFor();
    await page.keyboard.press('Escape');
    assert.equal(await page.getByRole('dialog').count(), 0);
    report.interactions.push('five task entries; selection; theme; config drawer; Escape');
    await page.goto(url + '?view=running');
    await page.keyboard.press('F6');
    assert.equal(await page.locator('.stage.now').textContent(), '已暂停');
    await page.keyboard.press('Control+f');
    assert.equal(await page.locator('#log-query').evaluate(e => e === document.activeElement), true);
    await page.locator('#log-query').fill('商店');
    assert.equal(await page.locator('.log').count(), 1);
    await page.locator('#log-query').blur();
    await page.keyboard.press('F7');
    assert.equal(await page.locator('.alert h2').textContent(), '本次执行已停止');
    await page.locator('.alert summary').click();
    assert(await page.locator('.raw-reason').isVisible());
    report.interactions.push('F6 pause; Ctrl+F search; F7 stopped state; expandable original reason');
    await page.goto(url + '?view=ratings');
    await page.locator('#modified-only').check();
    assert.equal(await page.locator('tbody tr').count(), 3);
    await page.locator('#modified-only').uncheck();
    const score = page.locator('input[data-id="1013"]');
    await score.fill('101');
    await score.dispatchEvent('input');
    await score.press('Enter');
    assert.equal(await page.evaluate(() => draftPersonal['1013']), 82);
    await score.fill('0');
    await score.dispatchEvent('input');
    await score.press('Enter');
    assert.equal(await page.evaluate(() => draftPersonal['1013']), 0);
    const nextRoleId = await score.evaluate(e => e.closest('tr').nextElementSibling.dataset.roleId);
    await score.press('ArrowDown');
    assert.equal(await page.locator(`input[data-id="${nextRoleId}"]`).evaluate(e => e === document.activeElement), true);
    await page.locator('tr[data-role-id="1013"]').getByRole('button', { name: '恢复原评分' }).click();
    assert.equal(await page.evaluate(() => draftPersonal['1013']), undefined);
    report.interactions.push('rating modified filter; reject 101; accept 0; arrow navigation; explicit restore');
    assert.deepEqual(failures, [], 'No browser script errors');
    report.browserScriptErrors = failures;
    fs.writeFileSync(path.join(output, 'preview-check.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify({ layouts: report.layouts.length, screenshots: fs.readdirSync(output).filter(f => f.endsWith('.png')).length, interactions: report.interactions, result: 'passed (HTML mockup only)' }, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
