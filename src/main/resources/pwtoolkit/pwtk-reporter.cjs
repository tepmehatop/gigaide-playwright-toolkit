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

function location(file, line) {
  return line ? `pwtest://${file}:${line}` : `pwtest://${file}`;
}

/** First line of the error and the "Expected:/Received:" pair, if any (for the IDE diff viewer). */
function describeError(error) {
  const text = String(error?.message ?? error?.value ?? 'Test failed').replace(ANSI, '');
  const first = text.split('\n').find(l => l.trim()) ?? 'Test failed';
  const expected = /^\s*Expected(?: [a-z ]+)?:\s?(.*)$/mi.exec(text);
  const received = /^\s*Received(?: [a-z ]+)?:\s?(.*)$/mi.exec(text);
  const stack = String(error?.stack ?? '').replace(ANSI, '');
  const details = stack.includes(first) ? stack : [text, stack].filter(Boolean).join('\n');
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
      status: result.status, retry: result.retry, attachments,
    };
    for (const a of attachments) message('testStdOut', { nodeId: test.id, out: `${a.name}: ${a.path}\n` });

    const willRetry = result.status !== 'passed' && result.status !== 'skipped' && result.retry < test.retries;
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
    const file = process.env.PWTK_RESULTS_FILE;
    if (file) {
      try {
        fs.mkdirSync(path.dirname(file), { recursive: true });
        fs.writeFileSync(file, JSON.stringify({ rootDir: this.rootDir, tests: this.results }, null, 1));
      } catch (e) {
        process.stderr.write(`[playwright-toolkit] cannot write ${file}: ${e}\n`);
      }
    }
  }
}

module.exports = PlaywrightToolkitReporter;
