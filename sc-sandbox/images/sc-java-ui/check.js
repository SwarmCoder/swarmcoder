#!/usr/bin/env node
/*
 * In-container page checker for SwarmCoder sandboxes.
 *
 * Reads one JSON spec on stdin, drives headless Chromium at the application running on the
 * container's own loopback, and prints one JSON result on stdout between two markers. Screenshots
 * come back base64-encoded INSIDE that JSON, so the whole exchange travels over the Docker exec
 * channel: no port is published, no volume is written, and the container still needs no network.
 *
 * Input:
 *   {
 *     "baseUrl": "http://127.0.0.1:8080",
 *     "viewport": { "width": 1440, "height": 900 },
 *     "navigationTimeoutMs": 30000,
 *     "checks": [
 *       { "url": "/", "assertNoConsoleErrors": true, "assertVisible": ["h1"], "screenshot": true },
 *       { "url": "/", "steps": [ { "label": "click text=Orders", "click": "text=Orders" },
 *                                { "label": "expect visible #new", "expectVisible": "#new" } ] }
 *     ]
 *   }
 *
 * Output (between the markers, so interleaved stderr from Chromium cannot corrupt it):
 *   <<<SC-BROWSER-JSON
 *   { "ok": true, "pages": [ { "url": "/", "loaded": true, "consoleErrors": [],
 *       "assertions": [ { "selector": "h1", "passed": true, "message": "visible" } ],
 *       "screenshotBase64": "iVBOR..." } ] }
 *   SC-BROWSER-JSON>>>
 *
 * The shape deliberately mirrors com.swarmcoder.domain.PageCheck so wiring it into
 * BrowserVerifier later is a mapping, not a redesign.
 */
const BEGIN = '<<<SC-BROWSER-JSON';
const END = 'SC-BROWSER-JSON>>>';

function emit(obj) {
  process.stdout.write('\n' + BEGIN + '\n' + JSON.stringify(obj) + '\n' + END + '\n');
}

async function main() {
  let raw = '';
  for await (const chunk of process.stdin) {
    raw += chunk;
  }
  const spec = JSON.parse(raw || '{}');
  const baseUrl = spec.baseUrl || 'http://127.0.0.1:8080';
  const width = (spec.viewport && spec.viewport.width) || 1440;
  const height = (spec.viewport && spec.viewport.height) || 900;
  const navTimeout = spec.navigationTimeoutMs || 30000;
  const checks = spec.checks && spec.checks.length ? spec.checks : [{ url: '/', screenshot: true }];

  const { chromium } = require('playwright');
  // --no-sandbox: the container already drops every Linux capability and sets no-new-privileges,
  //   so Chromium's own setuid/user-namespace sandbox cannot start. The confinement it would add
  //   is already provided, more strictly, by the container itself.
  // --disable-dev-shm-usage: Docker gives /dev/shm 64 MB by default and Chromium's default
  //   shared-memory use exceeds it, which shows up as tabs dying mid-navigation.
  const browser = await chromium.launch({
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu']
  });
  const pages = [];
  try {
    const context = await browser.newContext({
      viewport: { width, height },
      reducedMotion: 'reduce'
    });
    for (const check of checks) {
      pages.push(await checkOne(context, check, baseUrl, navTimeout));
    }
    await context.close();
  } finally {
    await browser.close();
  }
  emit({ ok: true, pages });
}

async function checkOne(context, check, baseUrl, navTimeout) {
  const page = await context.newPage();
  const consoleErrors = [];
  const assertions = [];
  let loaded = false;
  let screenshotBase64 = null;
  let title = null;
  let bodyText = null;

  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      consoleErrors.push(msg.text());
    }
  });
  page.on('pageerror', (err) => consoleErrors.push(String(err)));

  const url = baseUrl + (check.url || '/');
  try {
    await page.goto(url, { timeout: navTimeout, waitUntil: 'load' });
    await page.waitForLoadState('networkidle', { timeout: navTimeout });
    loaded = true;

    for (const selector of check.assertVisible || []) {
      try {
        const visible = await page.locator(selector).first().isVisible();
        assertions.push({ selector, passed: visible, message: visible ? 'visible' : 'not visible' });
      } catch (e) {
        assertions.push({ selector, passed: false, message: 'selector error: ' + e.message });
      }
    }
    // A journey: from the page just loaded, only what a user can do. No step loads an address,
    // so a screen is reached through the application's own navigation or not at all. The first
    // step that fails ends it; the steps after it are reported as not reached.
    const steps = check.steps || [];
    const stepTimeout = check.stepTimeoutMs || 10000;
    let broken = false;
    for (let i = 0; i < steps.length; i++) {
      const step = steps[i];
      const name = 'step ' + (i + 1) + ': ' + (step.label || JSON.stringify(step));
      if (broken) {
        assertions.push({ selector: name, passed: false, message: 'not reached' });
        continue;
      }
      try {
        if (step.click != null) {
          await page.locator(step.click).first().click({ timeout: stepTimeout });
          await page.waitForLoadState('networkidle', { timeout: stepTimeout }).catch(() => {});
        } else if (step.fill != null) {
          await page.locator(step.fill).first().fill(step.value || '', { timeout: stepTimeout });
        } else if (step.press != null) {
          await page.keyboard.press(step.press);
          await page.waitForLoadState('networkidle', { timeout: stepTimeout }).catch(() => {});
        } else if (step.expectVisible != null) {
          await page.locator(step.expectVisible).first()
            .waitFor({ state: 'visible', timeout: stepTimeout });
        } else if (step.expectHidden != null) {
          await page.locator(step.expectHidden).first()
            .waitFor({ state: 'hidden', timeout: stepTimeout });
        } else {
          throw new Error('a step must click, fill, press, expectVisible or expectHidden');
        }
        assertions.push({ selector: name, passed: true, message: 'done' });
      } catch (e) {
        broken = true;
        let where = '';
        try {
          where = ' (the browser was at ' + new URL(page.url()).pathname + ')';
        } catch (ignored) {
          // the address is a help, not a result
        }
        assertions.push({
          selector: name,
          passed: false,
          message: String(e && e.message ? e.message : e).split('\n')[0] + where
        });
      }
    }
    if (check.assertNoConsoleErrors) {
      assertions.push({
        selector: 'console',
        passed: consoleErrors.length === 0,
        message: consoleErrors.length === 0
          ? 'no console errors'
          : consoleErrors.length + ' console error(s)'
      });
    }
    // Cheap evidence that the page is the application and not an error page the harness
    // mistook for one. Trimmed hard: this travels back through the exec channel.
    title = await page.title();
    bodyText = (await page.locator('body').innerText()).replace(/\s+/g, ' ').trim().slice(0, 500);
    if (check.screenshot) {
      const png = await page.screenshot({ fullPage: true, animations: 'disabled' });
      screenshotBase64 = png.toString('base64');
    }
  } catch (e) {
    assertions.push({ selector: 'page-load', passed: false, message: e.message });
  } finally {
    await page.close();
  }
  return {
    url: check.url || '/',
    loaded,
    title,
    bodyText,
    consoleErrors,
    assertions,
    screenshotBase64
  };
}

main().catch((e) => {
  emit({ ok: false, error: String(e && e.stack ? e.stack : e), pages: [] });
  process.exitCode = 1;
});
