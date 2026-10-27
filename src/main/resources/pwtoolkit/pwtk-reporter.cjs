// Playwright reporter of the Playwright Toolkit IDE plugin.
// Added through PW_TEST_REPORTER, so it runs next to the reporters from playwright.config (html, extended report...).
// Prints IntelliJ test-tree messages ("TeamCity service messages", ID-based tree) to stdout and, when
// PWTK_RESULTS_FILE is set, writes test attachments (screenshots, traces) there for the IDE actions.
'use strict';

const fs = require('fs');
const path = require('path');

const ANSI = /\u001b\[[0-9;]*m/g;

function escape(value) {
  return String(value ?? '')
      .replace(ANSI, '')
      .replace(/\|/g, '||')
      .replace(/'/g, "|'")
      .replace(/\n/g, '|n')
      .replace(/\r/g, '|r')
      .replace(/\[/g, '|[')
      .replace(/]/g, '|]')
      .replace(/[\u0085\u2028\u2029]/g, c => '|0x' + c.charCodeAt(0).toString(16).padStart(4, '0'));
}

function message(name, attributes) {
  const attrs = Object.entries(attributes)
      .filter(([, v]) => v !== undefined && v !== null)
      .map(([k, v]) => `${k}='${escape(v)}'`)
      .join(' ');
  process.stdout.write(`\n##teamcity[${name} ${attrs}]\n`);
}

/**
 * Run history of playwright-extended-report (insights.history): EXTENDED_REPORT_HISTORY_FILE or
 * extended-report-history.json next to the report folder. Read-only: the extended report maintains it.
 */
function loadHistory(configDir) {
  const candidates = [
    process.env.EXTENDED_REPORT_HISTORY_FILE,
    configDir && path.join(configDir, 'extended-report-history.json'),
    configDir && path.join(configDir, 'extended-report', 'history.json'),
  ].filter(Boolean);
  for (const file of candidates) {
    try {
      const data = JSON.parse(fs.readFileSync(file, 'utf-8'));
      if (data && Array.isArray(data.runs)) return data.runs;
    } catch {
    }
  }
  return [];
}

const OUTCOME_ICON = { p: '✓', f: '✗', k: '~', s: '-' };

/** "History (last 5 runs): ✓✓✗✓✗ — failed 2 of 5, new failure" for a finished test, or undefined. */
function describeHistory(runs, testId, failedNow) {
  const outcomes = runs.map(r => r.tests && r.tests[testId]).filter(Boolean).map(t => t[0]);
  if (!outcomes.length) return undefined;
  const executed = outcomes.filter(o => o !== 's');
  const failed = executed.filter(o => o === 'f').length;
  const flaky = executed.filter(o => o === 'k').length;
  const notes = [`failed ${failed} of ${executed.length}`];
  if (flaky) notes.push(`flaky ${flaky}`);
  if (failedNow && executed.length && executed.every(o => o === 'p')) notes.push('NEW failure');
  let streak = 0;
  for (let i = executed.length - 1; i >= 0 && executed[i] === 'f'; i--) streak++;
  if (failedNow && streak) notes.push(`failing ${streak + 1} runs in a row`);
  return `History (previous ${outcomes.length} runs, extended report): ${outcomes.map(o => OUTCOME_ICON[o] ?? '?').join('')} — ${notes.join(', ')}`;
}

function location(file, line) {
  return line ? `pwtest://${file}:${line}` : `pwtest://${file}`;
}

/** Paths relative to the project (shorter, clickable against the working directory, no user names in screenshots). */
let projectDir;
function relativize(text) {
  return projectDir ? String(text).split(projectDir + path.sep).join('') : String(text);
}

/** First line of the error and the "Expected:/Received:" pair, if any (for the IDE diff viewer). */
function describeError(error) {
  const text = relativize(String(error?.message ?? error?.value ?? 'Test failed').replace(ANSI, ''));
  const first = text.split('\n').find(l => l.trim()) ?? 'Test failed';
  const expected = /^\s*Expected(?: [a-z ]+)?:\s?(.*)$/mi.exec(text);
  const received = /^\s*Received(?: [a-z ]+)?:\s?(.*)$/mi.exec(text);
  const stack = relativize(String(error?.stack ?? '').replace(ANSI, ''));
  let details = stack.includes(first) ? stack : [text, stack].filter(Boolean).join('\n');
  // the IDE prints the message above the details: do not repeat it
  const lines = details.split('\n');
  if (lines[0]?.trim() === first.trim()) details = lines.slice(1).join('\n');
  return { message: first.trim(), details, expected: expected?.[1], actual: received?.[1] };
}

class PlaywrightToolkitReporter {
  constructor() {
    this.started = new Set();     // suite node ids already announced
    this.testsStarted = new Set();
    this.suites = new Map();      // node id -> suite
    this.results = {};
  }

  printsToStdio() {
    // the tree is the progress output; prevents Playwright from adding its "line" reporter (cursor escape codes)
    return true;
  }

  onBegin(config, suite) {
    this.rootDir = config.rootDir;
    projectDir = config.configFile ? path.dirname(config.configFile) : process.cwd();
    this.history = loadHistory(config.configFile ? path.dirname(config.configFile) : process.cwd());
    message('enteredTheMatrix', {});
    message('testingStarted', {});
    message('testCount', { count: suite.allTests().length });
  }

  suiteId(suite) {
    const parts = [];
    for (let s = suite; s && s.type !== 'root'; s = s.parent) parts.unshift(`${s.type}:${s.title}`);
    return 'suite:' + parts.join('/');
  }

  /** Announces the suites from the root down to the test's parent (lazily: parallel workers are fine with node ids). */
  ensureSuite(suite) {
    if (!suite || suite.type === 'root') return '0';
    const id = this.suiteId(suite);
    if (this.started.has(id)) return id;
    const parentId = this.ensureSuite(suite.parent);
    this.started.add(id);
    this.suites.set(id, suite);
    const file = suite.location?.file;
    let name = suite.title;
    if (suite.type === 'file' && file) name = path.relative(this.rootDir ?? '', file) || suite.title;
    if (suite.type === 'project') name = suite.title || 'default';
    message('testSuiteStarted', {
      name,
      nodeId: id,
      parentNodeId: parentId,
      locationHint: file ? location(file, suite.type === 'describe' ? suite.location.line : undefined) : undefined,
    });
    return id;
  }

  onTestBegin(test, result) {
    const parentId = this.ensureSuite(test.parent);
    if (this.testsStarted.has(test.id)) {
      message('testStdOut', { nodeId: test.id, out: `\nRetry #${result.retry}\n` });
      return;
    }
    this.testsStarted.add(test.id);
    message('testStarted', {
      name: test.title,
      nodeId: test.id,
      parentNodeId: parentId,
      locationHint: location(test.location.file, test.location.line),
    });
  }

  onStdOut(chunk, test) {
    if (test) message('testStdOut', { nodeId: test.id, out: chunk.toString() });
    else process.stdout.write(chunk);
  }

  onStdErr(chunk, test) {
    if (test) message('testStdErr', { nodeId: test.id, out: chunk.toString() });
    else process.stderr.write(chunk);
  }

  onTestEnd(test, result) {
    const attachments = result.attachments.filter(a => a.path).map(a => ({ name: a.name, path: a.path, contentType: a.contentType }));
    this.results[test.id] = {
      file: test.location.file, line: test.location.line, title: test.titlePath().slice(1).join(' › '),
      project: test.parent?.project()?.name,
      status: result.status, retry: result.retry, attachments,
    };
    for (const a of attachments) message('testStdOut', { nodeId: test.id, out: `${a.name}: ${relativize(a.path)}\n` });

    const willRetry = result.status !== 'passed' && result.status !== 'skipped' && result.retry < test.retries;
    if (!willRetry) {
      this.saveResults();
      const history = describeHistory(this.history, test.id, test.outcome() === 'unexpected');
      if (history) message('testStdOut', { nodeId: test.id, out: history + '\n' });
    }
    if (willRetry) {
      for (const error of result.errors) message('testStdErr', { nodeId: test.id, out: describeError(error).details + '\n' });
      return;
    }
    if (result.status === 'skipped') {
      message('testIgnored', { nodeId: test.id, message: test.annotations.map(a => a.description || a.type).join(', ') || 'skipped' });
    } else if (test.outcome() === 'unexpected') {
      const error = describeError(result.errors[0] ?? result.error ?? { message: `Test ${result.status}` });
      const extra = result.errors.slice(1).map(e => describeError(e).details).join('\n\n');
      message('testFailed', {
        nodeId: test.id,
        message: error.message,
        details: extra ? `${error.details}\n\n${extra}` : error.details,
        expected: error.expected,
        actual: error.actual,
        type: error.expected !== undefined && error.actual !== undefined ? 'comparisonFailure' : undefined,
      });
    } else if (test.outcome() === 'flaky') {
      message('testStdOut', { nodeId: test.id, out: `Flaky: passed on retry #${result.retry}\n` });
    }
    message('testFinished', { nodeId: test.id, duration: Math.round(result.duration) });
  }

  onError(error) {
    process.stderr.write(describeError(error).details + '\n');
  }

  onEnd() {
    for (const id of [...this.started].reverse()) message('testSuiteFinished', { nodeId: id });
    message('testingFinished', {});
    this.saveResults();
  }

  /** Written after every test, so the IDE actions work even if the run is stopped. */
  saveResults() {
    const file = process.env.PWTK_RESULTS_FILE;
    if (!file) return;
    try {
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, JSON.stringify({ rootDir: this.rootDir, tests: this.results }, null, 1));
    } catch (e) {
      process.stderr.write(`[playwright-toolkit] cannot write ${file}: ${e}\n`);
    }
  }
}

module.exports = PlaywrightToolkitReporter;
