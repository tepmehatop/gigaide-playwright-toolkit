#!/usr/bin/env python3
"""Generates src/main/resources/liveTemplates/{TypeScript,Playwright}.xml - live templates of the plugin.

Names follow WebStorm / IntelliJ IDEA Ultimate (fori, itar, ritar, iter, itin) and popular VS Code snippets
(forof, foreach, trycatch, fn, afn, arrow, ctor, imp...); Playwright ones start with "pw".
Contexts: PLAYWRIGHT - code of TS/JS files; PWTK_TYPESCRIPT - code of TypeScript files only.
"""
from xml.sax.saxutils import escape
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/liveTemplates"

def q(text):
    return '"' + text + '"'

# (name, value, description, [(variable, expression, default)], context)
CODE = [
    # loops - WebStorm names
    ("fori", "for (let $INDEX$ = 0; $INDEX$ < $LIMIT$; $INDEX$++) {\n    $END$\n}", "for loop with an index",
     [("INDEX", "", q("i")), ("LIMIT", "", "")], "PLAYWRIGHT"),
    ("itar", "for (let $INDEX$ = 0; $INDEX$ < $ARRAY$.length; $INDEX$++) {\n    const $ELEMENT$ = $ARRAY$[$INDEX$];\n    $END$\n}",
     "Iterate elements of an array", [("INDEX", "", q("i")), ("ARRAY", "", q("array")), ("ELEMENT", "", q("element"))], "PLAYWRIGHT"),
    ("ritar", "for (let $INDEX$ = $ARRAY$.length - 1; $INDEX$ >= 0; $INDEX$--) {\n    const $ELEMENT$ = $ARRAY$[$INDEX$];\n    $END$\n}",
     "Iterate elements of an array in reverse order", [("INDEX", "", q("i")), ("ARRAY", "", q("array")), ("ELEMENT", "", q("element"))], "PLAYWRIGHT"),
    ("iter", "for (const $ITEM$ of $ITERABLE$) {\n    $END$\n}", "for...of loop",
     [("ITEM", "", q("item")), ("ITERABLE", "", q("items"))], "PLAYWRIGHT"),
    ("itin", "for (const $KEY$ in $OBJECT$) {\n    if (Object.prototype.hasOwnProperty.call($OBJECT$, $KEY$)) {\n        const $VALUE$ = $OBJECT$[$KEY$];\n        $END$\n    }\n}",
     "for...in loop over own properties", [("KEY", "", q("key")), ("OBJECT", "", q("object")), ("VALUE", "", q("value"))], "PLAYWRIGHT"),
    # loops and statements - VS Code snippet names
    ("forof", "for (const $ITEM$ of $ITERABLE$) {\n    $END$\n}", "for...of loop", [("ITEM", "", q("item")), ("ITERABLE", "", q("items"))], "PLAYWRIGHT"),
    ("forin", "for (const $KEY$ in $OBJECT$) {\n    $END$\n}", "for...in loop", [("KEY", "", q("key")), ("OBJECT", "", q("object"))], "PLAYWRIGHT"),
    ("forawait", "for await (const $ITEM$ of $ITERABLE$) {\n    $END$\n}", "for await...of loop", [("ITEM", "", q("item")), ("ITERABLE", "", q("items"))], "PLAYWRIGHT"),
    ("foreach", "$ARRAY$.forEach(($ITEM$) => {\n    $END$\n});", "array.forEach(...)", [("ARRAY", "", q("array")), ("ITEM", "", q("item"))], "PLAYWRIGHT"),
    ("whilel", "while ($CONDITION$) {\n    $END$\n}", "while loop", [("CONDITION", "", "")], "PLAYWRIGHT"),
    ("dowhile", "do {\n    $END$\n} while ($CONDITION$);", "do...while loop", [("CONDITION", "", "")], "PLAYWRIGHT"),
    ("ifelse", "if ($CONDITION$) {\n    $END$\n} else {\n    \n}", "if...else", [("CONDITION", "", "")], "PLAYWRIGHT"),
    ("switchc", "switch ($VALUE$) {\n    case $CASE$:\n        $END$\n        break;\n    default:\n        break;\n}", "switch with a case",
     [("VALUE", "", ""), ("CASE", "", "")], "PLAYWRIGHT"),
    ("trycatch", "try {\n    $END$\n} catch ($ERROR$) {\n    \n}", "try...catch", [("ERROR", "", q("error"))], "PLAYWRIGHT"),
    ("tryf", "try {\n    $END$\n} catch ($ERROR$) {\n    \n} finally {\n    \n}", "try...catch...finally", [("ERROR", "", q("error"))], "PLAYWRIGHT"),
    ("thr", "throw new Error($MESSAGE$);", "throw new Error(...)", [("MESSAGE", "", q("\\\"\\\""))], "PLAYWRIGHT"),
    # functions and classes
    ("fn", "function $NAME$($PARAMS$) {\n    $END$\n}", "function", [("NAME", "", q("name")), ("PARAMS", "", "")], "PLAYWRIGHT"),
    ("afn", "async function $NAME$($PARAMS$) {\n    $END$\n}", "async function", [("NAME", "", q("name")), ("PARAMS", "", "")], "PLAYWRIGHT"),
    ("arrow", "($PARAMS$) => {\n    $END$\n}", "arrow function", [("PARAMS", "", "")], "PLAYWRIGHT"),
    ("aarrow", "async ($PARAMS$) => {\n    $END$\n}", "async arrow function", [("PARAMS", "", "")], "PLAYWRIGHT"),
    ("constfn", "const $NAME$ = async ($PARAMS$) => {\n    $END$\n};", "const name = async (...) => {...}",
     [("NAME", "", q("name")), ("PARAMS", "", "")], "PLAYWRIGHT"),
    ("cls", "class $NAME$ {\n    constructor($PARAMS$) {\n        $END$\n    }\n}", "class with a constructor",
     [("NAME", "", q("Name")), ("PARAMS", "", "")], "PLAYWRIGHT"),
    ("ctor", "constructor($PARAMS$) {\n    $END$\n}", "constructor", [("PARAMS", "", "")], "PLAYWRIGHT"),
    ("met", "async $NAME$($PARAMS$) {\n    $END$\n}", "async method of a class", [("NAME", "", q("name")), ("PARAMS", "", "")], "PLAYWRIGHT"),
    ("getter", "get $NAME$() {\n    return $END$;\n}", "getter", [("NAME", "", q("name"))], "PLAYWRIGHT"),
    ("setter", "set $NAME$($VALUE$) {\n    $END$\n}", "setter", [("NAME", "", q("name")), ("VALUE", "", q("value"))], "PLAYWRIGHT"),
    # modules
    ("imp", "import { $MEMBER$ } from \"$MODULE$\";$END$", "import { member } from \"module\"", [("MODULE", "", ""), ("MEMBER", "", "")], "PLAYWRIGHT"),
    ("impd", "import $NAME$ from \"$MODULE$\";$END$", "import default", [("MODULE", "", ""), ("NAME", "", "")], "PLAYWRIGHT"),
    ("impa", "import * as $NAME$ from \"$MODULE$\";$END$", "import * as name", [("MODULE", "", ""), ("NAME", "", "")], "PLAYWRIGHT"),
    ("expd", "export default $END$", "export default", [], "PLAYWRIGHT"),
    ("expc", "export const $NAME$ = $END$;", "export const", [("NAME", "", q("name"))], "PLAYWRIGHT"),
    # timers and console
    ("st", "setTimeout(() => {\n    $END$\n}, $DELAY$);", "setTimeout", [("DELAY", "", q("1000"))], "PLAYWRIGHT"),
    ("si", "setInterval(() => {\n    $END$\n}, $DELAY$);", "setInterval", [("DELAY", "", q("1000"))], "PLAYWRIGHT"),
    ("sleep", "await new Promise((resolve) => setTimeout(resolve, $DELAY$));$END$", "await a delay", [("DELAY", "", q("1000"))], "PLAYWRIGHT"),
    ("clg", "console.log($END$);", "console.log()", [], "PLAYWRIGHT"),
    ("logv", "console.log(\"$EXPR$:\", $EXPR$);$END$", "console.log(\"name:\", name)", [("EXPR", "", "")], "PLAYWRIGHT"),
    ("logj", "console.log(JSON.stringify($EXPR$, null, 2));$END$", "console.log(JSON.stringify(value, null, 2))", [("EXPR", "", "")], "PLAYWRIGHT"),
    ("logw", "console.warn($END$);", "console.warn()", [], "PLAYWRIGHT"),
    ("loge", "console.error($END$);", "console.error()", [], "PLAYWRIGHT"),
    ("logt", "console.table($END$);", "console.table()", [], "PLAYWRIGHT"),
    ("todo", "// TODO: $END$", "TODO comment", [], "PLAYWRIGHT"),
    # TypeScript only
    ("intf", "interface $NAME$ {\n    $END$\n}", "interface", [("NAME", "", q("Name"))], "PWTK_TYPESCRIPT"),
    ("typ", "type $NAME$ = $END$;", "type alias", [("NAME", "", q("Name"))], "PWTK_TYPESCRIPT"),
    ("enm", "enum $NAME$ {\n    $END$\n}", "enum", [("NAME", "", q("Name"))], "PWTK_TYPESCRIPT"),
    ("prop", "private $NAME$: $TYPE$;$END$", "private field", [("NAME", "", q("name")), ("TYPE", "", q("string"))], "PWTK_TYPESCRIPT"),
]

ROLES = 'enum("button", "link", "textbox", "checkbox", "radio", "combobox", "heading", "listitem", "option", "tab", "dialog", "img", "row", "cell")'
METHODS = 'enum("get", "post", "put", "patch", "delete")'

PLAYWRIGHT = [
    ("pwba", "test.beforeAll(async ({ $FIXTURES$ }) => {\n    $END$\n});", "test.beforeAll", [("FIXTURES", "", q("browser"))], "PLAYWRIGHT"),
    ("pwae", "test.afterEach(async ({ page$FIXTURES$ }) => {\n    $END$\n});", "test.afterEach", [("FIXTURES", "", q(""))], "PLAYWRIGHT"),
    ("pwaa", "test.afterAll(async () => {\n    $END$\n});", "test.afterAll", [], "PLAYWRIGHT"),
    ("pwonly", "test.only(\"$NAME$\", async ({ page$FIXTURES$ }) => {\n    $END$\n});", "test.only - run only this test",
     [("NAME", "", q("test name")), ("FIXTURES", "", q(""))], "PLAYWRIGHT"),
    ("pwskip", "test.skip(\"$NAME$\", async ({ page$FIXTURES$ }) => {\n    $END$\n});", "test.skip",
     [("NAME", "", q("test name")), ("FIXTURES", "", q(""))], "PLAYWRIGHT"),
    ("pwfixme", "test.fixme(\"$NAME$\", async ({ page$FIXTURES$ }) => {\n    $END$\n});", "test.fixme",
     [("NAME", "", q("test name")), ("FIXTURES", "", q(""))], "PLAYWRIGHT"),
    ("pwdser", "test.describe.serial(\"$NAME$\", () => {\n    $END$\n});", "test.describe.serial", [("NAME", "", q("suite name"))], "PLAYWRIGHT"),
    ("pwuse", "test.use({ $END$ });", "test.use({ ... })", [], "PLAYWRIGHT"),
    ("pwslow", "test.slow();$END$", "test.slow() - triple the timeout", [], "PLAYWRIGHT"),
    ("pwpause", "await page.pause();$END$", "await page.pause() - stop in the Playwright Inspector", [], "PLAYWRIGHT"),
    ("pwgoto", "await page.goto(\"$URL$\");$END$", "await page.goto(...)", [("URL", "", q("/"))], "PLAYWRIGHT"),
    ("pwclick", "await page.locator(\"$SELECTOR$\").click();$END$", "await page.locator(...).click()", [("SELECTOR", "", "")], "PLAYWRIGHT"),
    ("pwfill", "await page.locator(\"$SELECTOR$\").fill(\"$VALUE$\");$END$", "await page.locator(...).fill(...)",
     [("SELECTOR", "", ""), ("VALUE", "", "")], "PLAYWRIGHT"),
    ("pwrole", "page.getByRole(\"$ROLE$\", { name: \"$NAME$\" })$END$", "page.getByRole(...)", [("ROLE", ROLES, q("button")), ("NAME", "", "")], "PLAYWRIGHT"),
    ("pwtid", "page.getByTestId(\"$ID$\")$END$", "page.getByTestId(...)", [("ID", "", "")], "PLAYWRIGHT"),
    ("pwtext", "page.getByText(\"$TEXT$\")$END$", "page.getByText(...)", [("TEXT", "", "")], "PLAYWRIGHT"),
    ("pwlabel", "page.getByLabel(\"$LABEL$\")$END$", "page.getByLabel(...)", [("LABEL", "", "")], "PLAYWRIGHT"),
    ("pwvis", "await expect(page.locator(\"$SELECTOR$\")).toBeVisible();$END$", "expect(locator).toBeVisible()", [("SELECTOR", "", "")], "PLAYWRIGHT"),
    ("pwhave", "await expect(page.locator(\"$SELECTOR$\")).toHaveText(\"$TEXT$\");$END$", "expect(locator).toHaveText(...)",
     [("SELECTOR", "", ""), ("TEXT", "", "")], "PLAYWRIGHT"),
    ("pwurl", "await expect(page).toHaveURL($URL$);$END$", "expect(page).toHaveURL(...)", [("URL", "", q("/\\\\/path/"))], "PLAYWRIGHT"),
    ("pwtitle", "await expect(page).toHaveTitle(\"$TITLE$\");$END$", "expect(page).toHaveTitle(...)", [("TITLE", "", "")], "PLAYWRIGHT"),
    ("pwsoft", "await expect.soft($ACTUAL$).$ASSERT$($END$);", "expect.soft(...) - the test goes on after a failure",
     [("ACTUAL", "", ""), ("ASSERT", 'enum("toBe", "toEqual", "toHaveText", "toBeVisible", "toContainText")', q("toBe"))], "PLAYWRIGHT"),
    ("pwpoll", "await expect.poll(async () => {\n    $END$\n}, { timeout: $TIMEOUT$ }).toBe($EXPECTED$);", "expect.poll - retry until the value matches",
     [("TIMEOUT", "", q("10000")), ("EXPECTED", "", "")], "PLAYWRIGHT"),
    ("pwwait", "const $NAME$Promise = page.waitForResponse((response) => response.url().includes(\"$URL$\") && response.status() === 200);\n$END$\nconst $NAME$ = await $NAME$Promise;",
     "Wait for an API response: the promise first, then the action that triggers it", [("NAME", "", q("response")), ("URL", "", q("/api/"))], "PLAYWRIGHT"),
    ("pwroute", "await page.route(\"$PATTERN$\", async (route) => {\n    await route.fulfill({ json: $END$ });\n});", "Mock an API response with page.route",
     [("PATTERN", "", q("**/api/**"))], "PLAYWRIGHT"),
    ("pwapi", "const $NAME$ = await request.$METHOD$(\"$URL$\"$END$);\nexpect($NAME$.ok()).toBeTruthy();", "API request with the request fixture",
     [("NAME", "", q("response")), ("METHOD", METHODS, q("get")), ("URL", "", q("/api/"))], "PLAYWRIGHT"),
    ("pwshot", "await page.screenshot({ path: \"$PATH$\", fullPage: true });$END$", "page.screenshot(...)", [("PATH", "", q("screenshot.png"))], "PLAYWRIGHT"),
    ("pwattach", "await test.info().attach(\"$NAME$\", { body: $BODY$, contentType: \"$TYPE$\" });$END$", "Attach data to the report",
     [("NAME", "", q("data")), ("BODY", "", ""), ("TYPE", 'enum("application/json", "text/plain", "image/png")', q("application/json"))], "PLAYWRIGHT"),
    ("pwann", "test.info().annotations.push({ type: \"$TYPE$\", description: \"$DESCRIPTION$\" });$END$", "Annotation of the test (issue, link...)",
     [("TYPE", "", q("issue")), ("DESCRIPTION", "", "")], "PLAYWRIGHT"),
]

def template(name, value, description, variables, context):
    lines = ['  <template name="%s" value=%s description=%s toReformat="false" toShortenFQNames="false">' % (
        name, quoteattr(value), quoteattr(description))]
    for var, expression, default in variables:
        lines.append('    <variable name="%s" expression=%s defaultValue=%s alwaysStopAt="true"/>' % (var, quoteattr(expression), quoteattr(default)))
    lines.append('    <context>\n      <option name="%s" value="true"/>\n    </context>\n  </template>' % context)
    return "\n".join(lines)

def quoteattr(text):
    return '"' + escape(text, {'"': "&quot;", "\n": "&#10;"}) + '"'

def write(path, group, templates, existing=None):
    names = [t[0] for t in templates]
    assert len(names) == len(set(names)), "duplicate names"
    body = "\n".join(template(*t) for t in templates)
    path.write_text('<templateSet group="%s">\n%s%s\n</templateSet>\n' % (group, existing or "", body), encoding="utf-8")

if __name__ == "__main__":
    write(ROOT / "TypeScript.xml", "TypeScript/JavaScript (Playwright Toolkit)", CODE)
    # Playwright.xml: the hand-written templates (log, pwt, pwd, pwstep, pwbe, pwexp, pwloc, pwfix, pwpage) stay first
    playwright = (ROOT / "Playwright.xml").read_text(encoding="utf-8")
    marker = "<!-- generated by tools/live-templates.py -->"
    head = playwright.split(marker)[0].replace("</templateSet>", "").rstrip() + "\n  " + marker + "\n"
    head = head.replace('<templateSet group="Playwright">\n', "", 1)
    write(ROOT / "Playwright.xml", "Playwright", PLAYWRIGHT, existing=head)
