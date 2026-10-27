// Babel plugin loaded by Playwright through PW_TEST_SOURCE_TRANSFORM while debugging.
// Wraps every `call();` / `await call();` statement into
//   try { <statement> } catch (__playwright_error__) { debugger; throw __playwright_error__; }
// so the debugger pauses on the exact line of a failing step (expect, locator timeout, ...).
// Idea and logic adapted from microsoft/playwright-vscode src/debugTransform.ts (Apache License 2.0).
module.exports = function playwrightToolkitDebugTransform(api) {
  const t = api.types;
  return {
    name: 'playwright-toolkit-debug-transform',
    visitor: {
      ExpressionStatement(path) {
        const expression = path.node.expression;
        const isAwaitExpression = t.isAwaitExpression(expression);
        const isCallExpression = t.isCallExpression(expression);
        if (!isAwaitExpression && !isCallExpression)
          return;
        // Prevent re-entering the statement we have just wrapped.
        if (path.parentPath.isBlockStatement() && path.parentPath.parentPath.isTryStatement())
          return;
        if (isAwaitExpression && !t.isCallExpression(expression.argument))
          return;
        path.replaceWith(t.tryStatement(
            t.blockStatement([path.node]),
            t.catchClause(
                t.identifier('__playwright_error__'),
                t.blockStatement([
                  t.debuggerStatement(),
                  t.throwStatement(t.identifier('__playwright_error__')),
                ]))));
        // Keep source maps pointing at the original statement.
        path.node.start = expression.start;
        path.node.end = expression.end;
        path.node.loc = expression.loc;
        path.node.range = expression.range;
      },
    },
  };
};
