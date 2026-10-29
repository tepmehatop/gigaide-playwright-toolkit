// Playwright Toolkit locator picker: a Chromium of the project's Playwright with a persistent profile (logins are kept).
// stdin, one JSON per line: {cmd:"navigate", url} | {cmd:"pick"} | {cmd:"cancel"}
//                           | {cmd:"candidatesFor", selector} | {cmd:"testClick", selector} (tests)
// stdout, one JSON per line: {event:"ready"} | {event:"picked", url, candidates:[{rank, kind, selector, code, count}]}
//                            | {event:"cancelled"} | {event:"error", message}
// argv: workDir userDataDir attributes(comma separated, best first) headless(0/1)
const path = require('path');
const readline = require('readline');
const { createRequire } = require('module');

const [workDir, userDataDir, attributeList, headless] = process.argv.slice(2);
const attributes = attributeList.split(',').map(s => s.trim()).filter(Boolean);
const emit = message => process.stdout.write(JSON.stringify(message) + '\n');

function playwright() {
  const req = createRequire(path.join(workDir, 'package.json'));
  for (const name of ['playwright-core', '@playwright/test', 'playwright']) {
    try { return req(name); } catch (e) { /* next */ }
  }
  throw new Error('Playwright is not installed in ' + workDir + ' (npm install)');
}

// Runs in the page: highlight under the mouse, a click picks; candidates with their match counts.
const PAGE_SCRIPT = (attributes) => {
  if (window.__pwtkPicker) return;
  const box = document.createElement('div');
  box.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;background:rgba(111,168,220,.35);outline:2px solid #3b82f6;transition:all .05s';
  const label = document.createElement('div');
  label.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;background:#1e293b;color:#fff;font:12px monospace;padding:2px 6px;border-radius:3px';
  document.documentElement.append(box, label);
  const move = e => {
    const r = e.target.getBoundingClientRect();
    Object.assign(box.style, { left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px' });
    label.textContent = e.target.tagName.toLowerCase() + (e.target.id ? '#' + e.target.id : '');
    Object.assign(label.style, { left: r.left + 'px', top: Math.max(0, r.top - 20) + 'px' });
  };
  const stop = () => {
    document.removeEventListener('mousemove', move, true);
    document.removeEventListener('click', click, true);
    document.removeEventListener('keydown', key, true);
    box.remove(); label.remove();
    window.__pwtkPicker = undefined;
  };
  const click = e => {
    e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
    stop();
    window.__pwtkPicked(window.__pwtkCandidates(e.target, attributes));
  };
  const key = e => { if (e.key === 'Escape') { stop(); window.__pwtkPicked(null); } };
  document.addEventListener('mousemove', move, true);
  document.addEventListener('click', click, true);
  document.addEventListener('keydown', key, true);
  window.__pwtkPicker = { stop };
};

// Runs in the page: locator candidates for an element, best first, each with its number of matches on the page.
const CANDIDATES_SCRIPT = () => {
  const q = s => "'" + s.replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "'";
  const attr = s => '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const css = s => { try { return document.querySelectorAll(s).length; } catch (e) { return -1; } };
  const xpath = s => { try { return document.evaluate('count(' + s + ')', document, null, XPathResult.NUMBER_TYPE, null).numberValue; } catch (e) { return -1; } };
  const cssIdent = s => /^-?[A-Za-z_][\w-]*$/.test(s);
  const generated = s => /\d{4,}|^:r|[0-9a-f]{8}-[0-9a-f]{4}|^(ember|ext-gen|react-select-)\d|__[a-z0-9]{5,}$|_[a-zA-Z0-9]{5,}$/.test(s);
  const text = el => (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
  const ownText = el => Array.from(el.childNodes).filter(n => n.nodeType === 3).map(n => n.textContent).join(' ').replace(/\s+/g, ' ').trim();
  const xq = s => s.includes("'") ? 'concat(\'' + s.split("'").join("', \"'\", '") + '\')' : "'" + s + "'";
  const implicitRole = el => {
    const tag = el.tagName.toLowerCase(), type = (el.getAttribute('type') || 'text').toLowerCase();
    if (el.getAttribute('role')) return el.getAttribute('role').split(/\s+/)[0];
    if (tag === 'button' || (tag === 'input' && ['button', 'submit', 'reset', 'image'].includes(type))) return 'button';
    if (tag === 'a' && el.hasAttribute('href')) return 'link';
    if (tag === 'input' && type === 'checkbox') return 'checkbox';
    if (tag === 'input' && type === 'radio') return 'radio';
    if (tag === 'input' && ['text', 'email', 'tel', 'url', 'search', 'password', 'number'].includes(type)) return type === 'search' ? 'searchbox' : type === 'number' ? 'spinbutton' : 'textbox';
    if (tag === 'textarea') return 'textbox';
    if (tag === 'select') return el.multiple || el.size > 1 ? 'listbox' : 'combobox';
    if (/^h[1-6]$/.test(tag)) return 'heading';
    if (tag === 'img' && el.getAttribute('alt')) return 'img';
    if (tag === 'li') return 'listitem';
    if (tag === 'option') return 'option';
    return null;
  };
  const accessibleName = el => {
    if (el.getAttribute('aria-label')) return el.getAttribute('aria-label').trim();
    const by = el.getAttribute('aria-labelledby');
    if (by) return by.split(/\s+/).map(id => document.getElementById(id)).filter(Boolean).map(text).join(' ').trim();
    if (el.labels && el.labels.length) return text(el.labels[0]);
    if (el.tagName === 'IMG') return (el.getAttribute('alt') || '').trim();
    if (el.tagName === 'INPUT' && ['button', 'submit', 'reset'].includes((el.getAttribute('type') || '').toLowerCase())) return (el.value || '').trim();
    if (el.getAttribute('title') && !text(el)) return el.getAttribute('title').trim();
    return text(el);
  };

  return (el, attributes) => {
    const tag = el.tagName.toLowerCase();
    const result = [];
    const add = (rank, kind, selector, code, count) => { if (!result.some(c => c.selector === selector)) result.push({ rank, kind, selector, code, count }); };
    // 1-2. attributes for tests, in the configured order (data-qa first, data-testid later: front-end unit tests repeat it)
    attributes.forEach((name, i) => {
      const value = el.getAttribute(name);
      if (value) { const s = '[' + name + '=' + attr(value) + ']'; add(i < attributes.length - 1 ? 1 : 2, name, s, 'locator(' + q(s) + ')', css(s)); }
    });
    // 3. id
    if (el.id && !generated(el.id)) {
      const s = cssIdent(el.id) ? '#' + el.id : '[id=' + attr(el.id) + ']';
      add(3, 'id', s, 'locator(' + q(s) + ')', css(s));
    }
    // 4. name, placeholder, a short class
    const nameAttr = el.getAttribute('name');
    if (nameAttr) { const s = tag + '[name=' + attr(nameAttr) + ']'; add(4, 'name', s, 'locator(' + q(s) + ')', css(s)); }
    for (const cls of Array.from(el.classList)) {
      if (!cssIdent(cls) || generated(cls) || cls.length > 30) continue;
      const plain = '.' + cls, withTag = tag + '.' + cls, count = css(plain);
      // "button.primary" only when ".primary" alone is not unique
      if (count === 1) add(4, 'class', plain, 'locator(' + q(plain) + ')', 1);
      else add(4, 'class', withTag, 'locator(' + q(withTag) + ')', css(withTag));
    }
    // 5. role and accessible name (the count is checked by Playwright itself afterwards)
    const role = implicitRole(el), name = accessibleName(el);
    if (role && name && name.length <= 80) {
      add(5, 'role', 'role=' + role + '[name=' + attr(name) + ']', 'getByRole(' + q(role) + ', { name: ' + q(name) + ' })', null);
    }
    const placeholder = el.getAttribute('placeholder');
    if (placeholder) add(5, 'placeholder', 'internal:attr=[placeholder=' + attr(placeholder) + 'i]', 'getByPlaceholder(' + q(placeholder) + ')', null);
    // 6. XPath by text
    const own = ownText(el);
    if (own && own.length <= 80) {
      const s = '//' + tag + '[contains(text(), ' + xq(own) + ')]';
      const count = xpath(s);
      add(6, 'xpath', s, 'locator(' + q(s) + ')', count);
      // the exact text only helps when "contains" also matches longer texts
      if (count !== 1) {
        const exact = '//' + tag + '[normalize-space()=' + xq(own) + ']';
        add(6, 'xpath', exact, 'locator(' + q(exact) + ')', xpath(exact));
      }
    }
    // 7. inside the closest ancestor with a unique test attribute or id
    for (let parent = el.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
      let scope = null;
      for (const a of attributes) if (parent.getAttribute(a)) { const s = '[' + a + '=' + attr(parent.getAttribute(a)) + ']'; if (css(s) === 1) { scope = s; break; } }
      if (!scope && parent.id && !generated(parent.id) && cssIdent(parent.id) && css('#' + parent.id) === 1) scope = '#' + parent.id;
      if (!scope) continue;
      const inner = [];
      if (nameAttr) inner.push(tag + '[name=' + attr(nameAttr) + ']');
      for (const cls of Array.from(el.classList)) if (cssIdent(cls) && !generated(cls)) inner.push(tag + '.' + cls);
      inner.push(tag);
      for (const i of inner) { const s = scope + ' ' + i; if (css(s) === 1) { add(7, 'scoped', s, 'locator(' + q(s) + ')', 1); break; } }
      break;
    }
    // 8. nothing unique: by position (fragile, the last resort)
    if (!result.some(c => c.count === 1)) {
      if (own && own.length <= 80) {
        const all = '//' + tag + '[contains(text(), ' + xq(own) + ')]';
        const snapshot = document.evaluate(all, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
        for (let i = 0; i < snapshot.snapshotLength; i++) {
          if (snapshot.snapshotItem(i) === el) { const s = '(' + all + ')[' + (i + 1) + ']'; add(8, 'position', s, 'locator(' + q(s) + ')', xpath(s)); break; }
        }
      }
      const path = [];
      for (let node = el; node && node !== document.documentElement; node = node.parentElement) {
        const parent = node.parentElement;
        const same = parent ? Array.from(parent.children).filter(c => c.tagName === node.tagName) : [node];
        path.unshift(node.tagName.toLowerCase() + (same.length > 1 ? ':nth-of-type(' + (same.indexOf(node) + 1) + ')' : ''));
        const s = path.join(' > ');
        if (css(s) === 1) { add(8, 'position', s, 'locator(' + q(s) + ')', 1); break; }
      }
    }
    return result;
  };
};

(async () => {
  const { chromium } = playwright();
  const context = await chromium.launchPersistentContext(userDataDir, { headless: headless === '1', viewport: null });
  let page = context.pages()[0] || await context.newPage();
  let picking = null;
  context.on('page', p => { page = p; if (picking) arm(p).catch(() => {}); });
  context.on('close', () => process.exit(0));

  await context.exposeBinding('__pwtkPicked', async (source, candidates) => {
    if (!picking) return;
    picking = null;
    for (const p of context.pages()) p.evaluate(() => window.__pwtkPicker && window.__pwtkPicker.stop()).catch(() => {});
    if (!candidates) { emit({ event: 'cancelled' }); return; }
    emit({ event: 'picked', url: source.page.url(), candidates: await verify(source.page, candidates) });
  });
  await context.addInitScript(`window.__pwtkCandidates = (${CANDIDATES_SCRIPT})();`);
  for (const p of context.pages()) await p.evaluate(`window.__pwtkCandidates = (${CANDIDATES_SCRIPT})();`).catch(() => {});

  async function arm(p) {
    await p.evaluate(`window.__pwtkCandidates = window.__pwtkCandidates || (${CANDIDATES_SCRIPT})();`);
    await p.evaluate(`(${PAGE_SCRIPT})(${JSON.stringify(attributes)})`);
  }

  /** Role / placeholder counts by Playwright itself; unique candidates first, then by rank. */
  async function verify(p, candidates) {
    for (const c of candidates) {
      if (c.count !== null) continue;
      try {
        c.count = c.kind === 'role'
          ? await p.getByRole(c.selector.replace(/^role=([^\[]+).*$/, '$1'), { name: JSON.parse(c.selector.replace(/^[^\[]+\[name=(.*)\]$/, '$1')), exact: true }).count()
          : await p.locator(c.selector).count();
      } catch (e) { c.count = -1; }
    }
    const valid = candidates.filter(c => c.count > 0);
    valid.sort((a, b) => (a.count === 1 ? 0 : 1) - (b.count === 1 ? 0 : 1) || a.rank - b.rank || a.selector.length - b.selector.length);
    // rule number one: a locator must not repeat on the page; repeating ones only when there is nothing else
    const unique = valid.filter(c => c.count === 1);
    return unique.length ? unique : valid;
  }

  readline.createInterface({ input: process.stdin }).on('line', async line => {
    let command;
    try { command = JSON.parse(line); } catch (e) { return; }
    try {
      if (command.cmd === 'navigate') {
        await page.goto(command.url);
        emit({ event: 'navigated', url: page.url() });
      } else if (command.cmd === 'pick') {
        picking = true;
        for (const p of context.pages()) await arm(p).catch(() => {});
        await page.bringToFront().catch(() => {});
      } else if (command.cmd === 'cancel') {
        picking = null;
        for (const p of context.pages()) await p.evaluate(() => window.__pwtkPicker && window.__pwtkPicker.stop()).catch(() => {});
        emit({ event: 'cancelled' });
      } else if (command.cmd === 'candidatesFor') {
        const candidates = await page.locator(command.selector).first().evaluate((el, attrs) => window.__pwtkCandidates(el, attrs), attributes);
        emit({ event: 'picked', url: page.url(), candidates: await verify(page, candidates) });
      } else if (command.cmd === 'testClick') {
        // tests: a real mouse click in the middle of the element, while picking
        const box = await page.locator(command.selector).first().boundingBox();
        await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
        await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
      } else if (command.cmd === 'exit') {
        await context.close();
      }
    } catch (e) {
      emit({ event: 'error', message: String(e && e.message || e) });
    }
  });
  emit({ event: 'ready' });
})().catch(e => { emit({ event: 'error', message: String(e && e.message || e) }); process.exit(1); });
