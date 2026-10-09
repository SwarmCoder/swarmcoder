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
 *                                { "label": "select ...", "select": "#state", "value": "Open" },
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
          await seen(page, step.click).click({ timeout: stepTimeout });
          await page.waitForLoadState('networkidle', { timeout: stepTimeout }).catch(() => {});
        } else if (step.fill != null) {
          await seen(page, step.fill).fill(step.value || '', { timeout: stepTimeout });
        } else if (step.select != null) {
          await choose(page, step.select, step.value || '', stepTimeout);
          await page.waitForLoadState('networkidle', { timeout: stepTimeout }).catch(() => {});
        } else if (step.press != null) {
          await page.keyboard.press(step.press);
          await page.waitForLoadState('networkidle', { timeout: stepTimeout }).catch(() => {});
        } else if (step.expectVisible != null) {
          await seen(page, step.expectVisible)
            .waitFor({ state: 'visible', timeout: stepTimeout });
        } else if (step.expectHidden != null) {
          await seen(page, step.expectHidden)
            .waitFor({ state: 'hidden', timeout: stepTimeout });
        } else if (step.expectValue != null) {
          await holds(page, step.expectValue, step.value || '', stepTimeout);
        } else {
          throw new Error('a step must click, fill, select, press, expectVisible, expectHidden '
            + 'or expectValue');
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
        // What the page showed when the step failed, for the journey's author: the journey was
        // written before the screen existed, so its selectors are guesses. Not a result, and
        // marked passed so that nothing counts it as a failure.
        const seen = await pageSeen(page);
        if (seen) {
          assertions.push({ selector: 'page-seen', passed: true, message: seen });
        }
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

/*
 * What a step acts on or looks at: the first match A PERSON CAN SEE. A selector's first match in
 * the document may be one nobody sees - the option of a closed drop-down list that reads the same
 * as a row of the table below it (live run 100: the row was there and `text=...` found the
 * option first, so the journey failed on a screen that was right). So: click and fill take the
 * first visible match, expectVisible passes when any match is visible, and expectHidden passes
 * when none is.
 */
function seen(page, selector) {
  return page.locator(selector).locator('visible=true').first();
}

// The drop-down list a selector names: the element itself, or the one list inside it.
const LIST_OF = `(el) => {
  if (el.tagName === 'SELECT') { return el; }
  const inside = el.querySelectorAll ? el.querySelectorAll('select') : [];
  if (inside.length === 1) { return inside[0]; }
  if (el.shadowRoot && el.shadowRoot.querySelectorAll('select').length === 1) {
    return el.shadowRoot.querySelector('select');
  }
  return null;
}`;

/*
 * Chooses one option, as a person does. A drop-down list of the browser's own is chosen in by
 * the option's text (or its value); what it offers is in the failure when it has no such option.
 * A control that is not one - a combobox the application draws itself - is opened by a click and
 * the option is clicked where it then appears.
 */
async function choose(page, selector, wanted, timeout) {
  const control = seen(page, selector);
  await control.waitFor({ state: 'visible', timeout });
  const same = (a, b) => String(a).trim().toLowerCase() === String(b).trim().toLowerCase();
  const options = await control.evaluate(new Function('el', `
    const list = (${LIST_OF})(el);
    return list === null ? null : Array.from(list.options).map((option) =>
      [(option.label || option.textContent || '').replace(/\\s+/g, ' ').trim(), option.value]);
  `));
  if (options === null) {
    await control.click({ timeout });
    const option = page.locator('role=option[name=' + JSON.stringify(wanted) + ']')
      .locator('visible=true').first();
    try {
      await option.click({ timeout });
    } catch (e) {
      throw new Error('the control opened and showed no option "' + wanted + '" to click');
    }
    return;
  }
  let index = options.findIndex((option) => option[0] === wanted.trim());
  if (index < 0) {
    index = options.findIndex((option) => option[1] === wanted);
  }
  if (index < 0) {
    index = options.findIndex((option) => same(option[0], wanted));
  }
  if (index < 0) {
    throw new Error('the list has no option "' + wanted + '"; it offers: '
      + options.slice(0, 20).map((option) => '"' + option[0] + '"').join(', '));
  }
  await control.evaluate(new Function('el', 'index', `
    const list = (${LIST_OF})(el);
    if (list.disabled) { throw new Error("the list is disabled: nothing can be chosen in it"); }
    list.selectedIndex = index;
    list.dispatchEvent(new Event('input', { bubbles: true }));
    list.dispatchEvent(new Event('change', { bubbles: true }));
  `), index);
}

/*
 * Waits until a field or a control holds a value: what a text field contains, or the option a
 * drop-down list shows as chosen (by its text or its value). What it held instead is in the
 * failure.
 */
async function holds(page, selector, wanted, timeout) {
  const control = seen(page, selector);
  await control.waitFor({ state: 'visible', timeout });
  const deadline = Date.now() + timeout;
  for (;;) {
    const held = await control.evaluate(new Function('el', `
      const list = (${LIST_OF})(el);
      if (list !== null) {
        const chosen = list.selectedOptions[0];
        return chosen ? [(chosen.label || chosen.textContent || ''), chosen.value] : [''];
      }
      const all = [];
      if (typeof el.value === 'string' && el.value !== '') { all.push(el.value); }
      if (el.getAttribute('aria-valuetext')) { all.push(el.getAttribute('aria-valuetext')); }
      if (all.length === 0) { all.push(el.innerText || el.textContent || ''); }
      return all;
    `));
    const clean = held.map((one) => String(one).replace(/\s+/g, ' ').trim());
    if (clean.includes(wanted.replace(/\s+/g, ' ').trim())) {
      return;
    }
    if (Date.now() >= deadline) {
      throw new Error('it holds "' + clean[0].slice(0, 120) + '", not "' + wanted + '"');
    }
    await page.waitForTimeout(200);
  }
}

/*
 * The page as a person and a selector meet it, read from the browser with no model: the roles
 * and accessible names of what is on it, the placeholders of its fields (a placeholder is often
 * taken for a name), what each drop-down list offers and shows as chosen (an author cannot
 * correct a journey that chooses without knowing the options, and the text of the page lists
 * them as if they were on it), and its visible text. Bounded, because it travels through the
 * exec channel and is then shown to a model: at most 100 elements, 10 lists of 20 options, 20
 * placeholders, 1,200 characters of text and 4,000 characters in all. Never throws; '' when
 * nothing could be read.
 */
async function pageSeen(page) {
  const parts = [];
  const clean = (text, max) =>
    String(text == null ? '' : text).replace(/\s+/g, ' ').trim().slice(0, max);
  try {
    const lines = [];
    if (page.accessibility && typeof page.accessibility.snapshot === 'function') {
      const tree = await page.accessibility.snapshot({ interestingOnly: true });
      const walk = (node, depth) => {
        if (!node || lines.length >= 100) {
          return;
        }
        const name = clean(node.name, 120);
        const value = clean(node.value, 60);
        const root = node.role === 'WebArea' || node.role === 'RootWebArea';
        if (node.role && !root && (name || value)) {
          lines.push('  '.repeat(Math.min(depth, 5)) + node.role + (name ? ' "' + name + '"' : '')
            + (value ? ' value="' + value + '"' : '') + (node.disabled ? ' (disabled)' : ''));
        }
        for (const child of node.children || []) {
          walk(child, root ? depth : depth + 1);
        }
      };
      walk(tree, 0);
    } else if (typeof page.locator('body').ariaSnapshot === 'function') {
      // A newer driver has no accessibility.snapshot; its own outline says the same.
      const outline = await page.locator('body').ariaSnapshot();
      for (const line of String(outline).split('\n').slice(0, 100)) {
        lines.push(line.slice(0, 200));
      }
    }
    if (lines.length) {
      parts.push('elements, as role "accessible name":\n' + lines.join('\n'));
    }
  } catch (ignored) {
    // a reading, not a result
  }
  try {
    const lists = await page.evaluate(() => {
      const found = [];
      const walk = (root) => {
        for (const el of root.querySelectorAll('*')) {
          if (el.tagName === 'SELECT' && found.length < 10) {
            found.push(el);
          }
          if (el.shadowRoot) {
            walk(el.shadowRoot);
          }
        }
      };
      walk(document);
      return found.map((list) => {
        const label = list.getAttribute('aria-label')
          || (list.labels && list.labels[0] ? list.labels[0].innerText : '')
          || list.getAttribute('name') || list.id || '';
        const chosen = list.selectedOptions[0];
        return [label, chosen ? (chosen.label || chosen.textContent) : '',
          Array.from(list.options).slice(0, 20).map((o) => o.label || o.textContent),
          list.options.length];
      });
    });
    const lines = lists.map((list) =>
      '  ' + (clean(list[0], 120) ? '"' + clean(list[0], 120) + '"' : 'a list with no label')
        + ' shows "' + clean(list[1], 60) + '"; it offers '
        + list[2].map((option) => '"' + clean(option, 60) + '"').join(', ')
        + (list[3] > list[2].length ? ' and ' + (list[3] - list[2].length) + ' more' : ''));
    if (lines.length) {
      parts.push('drop-down lists (a `select` step chooses in one; an option is not on the '
        + 'page for a click or an expectVisible to find, and what a list shows as chosen is '
        + 'read with expectValue):\n' + lines.join('\n'));
    }
  } catch (ignored) {
    // a reading, not a result
  }
  try {
    const placeholders = await page.evaluate(() => Array.from(
      document.querySelectorAll('[placeholder]')).slice(0, 20).map((field) => {
        const label = field.getAttribute('aria-label')
          || (field.labels && field.labels[0] ? field.labels[0].innerText : '');
        return [field.getAttribute('placeholder'), label];
      }));
    const lines = placeholders.filter((pair) => clean(pair[0], 120)).map((pair) =>
      '  placeholder "' + clean(pair[0], 120) + '"'
        + (clean(pair[1], 120) ? ' on the field labelled "' + clean(pair[1], 120) + '"'
          : ' on a field with no label'));
    if (lines.length) {
      parts.push('placeholders (a placeholder is not a label):\n' + lines.join('\n'));
    }
  } catch (ignored) {
    // a reading, not a result
  }
  try {
    const text = clean(await page.locator('body').innerText(), 1200);
    parts.push(text ? 'visible text: ' + text : 'visible text: (none)');
  } catch (ignored) {
    // a reading, not a result
  }
  return parts.join('\n').slice(0, 4000);
}

main().catch((e) => {
  emit({ ok: false, error: String(e && e.stack ? e.stack : e), pages: [] });
  process.exitCode = 1;
});
