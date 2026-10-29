# Changelog

## 1.12.0

- **Pick Locator in Browser** (Tools → Playwright, контекстное меню редактора) — аналог Web Inspector из IDEA и «Pick locator» из VS Code:
  - в браузере Show Browser элемент под мышью подсвечивается, клик выбирает его;
  - локатор вставляется у курсора и копируется в буфер: внутри кавычек — селектор (`internal:role=button[name="Sign in"i]`), иначе код (`getByRole('button', { name: 'Sign in' })`);
  - `testIdAttribute` из `playwright.config` учитывается.
- **Проверка селекторов в коде** — ошибки подчёркиваются красным в `locator()`, `click()`, `fill()`, `waitForSelector()`… и в значениях объектов `selectors` / `locators` Page Object:
  - XPath (`xpath=…`, `//…`) — сообщение движка XPath;
  - CSS — незакрытые скобки и кавычки, лишние или висящие комбинаторы, пустые `#`/`.`, неверные `[атрибуты]`, `:` без псевдокласса;
  - цепочки `>>`, `text=`, `role=`, `internal:` — по частям.
- **Значения переменных прямо в строках кода при отладке**, как в IDEA: `user = {login: "admin", …}` серым в конце строки, где переменная объявлена или использована. Для переменных замыканий (фикстуры теста) — после раскрытия группы Closure. Включается в Settings → Build, Execution, Deployment → Debugger → Data Views → *Show values inline*.
- **New → Playwright Test**: суффикс берётся из `testMatch` в `playwright.config` (`/.*\.test\.ts$/` → `.test.ts`); суффикс, введённый в имени вручную (`cart.spec.ts`), сохраняется.

## 1.11.0

- **Alt+Enter без ошибки — рефакторинги TypeScript** для выделения или места под курсором: *Extract to constant / function / method / readonly field*, *Convert to async function*, *Add / Remove braces from arrow function*, *Convert to template string*, *Convert named / default export*, *Move to a new file* и другие.
- **Extract Variable / Constant / Field / Method** (Cmd+Alt+V / C / F / M, на Windows и Linux Ctrl+Alt+…). Без выделения берётся выражение под курсором (для Extract Method — строка). Если областей несколько, IDE предложит выбрать. Новое имя сразу переименовывается на месте во всех вхождениях, как в IDEA.
- С нативным TypeScript 7 (в нём рефакторингов пока нет) они выполняются через TypeScript 5 из проекта или установленный для IDE: при первом Extract плагин предложит *Install TypeScript 5 for the IDE* (в служебную папку IDE, не в проект).
- **Go to Implementation (Cmd+Alt+B / Ctrl+Alt+B)** — наследники класса, реализации интерфейса, переопределения метода.
- **Go to Super (Cmd+U / Ctrl+U)** — переопределённый метод или реализуемый член интерфейса, суперкласс.
- **Значки в gutter, как в IDEA**: ↑ «Overrides BasePage.open» / «Implements Opens.open» у методов и полей; ↓ «Has subclasses» / «Has implementations» у классов и интерфейсов, «Overridden in …» у их членов. Клик — переход.
- **Call Hierarchy (Ctrl+Alt+H)** — дерево «кто вызывает» / «что вызывает» для функций и методов.

## 1.10.0

- **Type Info (Ctrl+Shift+P)** — тип имени под курсором: `loginPage` → `LoginPage`, `page` → `Page`.
- **Go to Type Declaration (Cmd+Shift+B / Ctrl+Shift+B)** — к объявлению типа: `loginPage` → класс `LoginPage`, `page` → интерфейс `Page` Playwright.
- **«N usages» над классами, функциями, методами и полями** (Code Vision), клик показывает использования. Отключается в Settings | Editor | Inlay Hints | Code Vision.
- **Постфиксные шаблоны, как в IDEA**: `.const`, `.let` (имя предлагается из выражения и сразу выделено), `.if`, `.not`, `.notnull`, `.null`, `.return`, `.await`, `.par`, `.for`, `.forof`, `.fori`, плюс прежние `.log` / `.error`. Список — Settings | Editor | General | Postfix Completion.
- **New → Playwright Test**: файл теста в стиле проекта — суффикс `.test.ts` или `.spec.ts`, как уже принято в проекте; `test` и `expect` импортируются из `baseTest.ts` / `fixtures.ts` проекта. Шаблон — Settings | Editor | File and Code Templates | Other | Playwright Test.

## 1.9.0

Навигация, как в IntelliJ IDEA: плагин разрешает через TypeScript ссылки, которые GigaIDE не понимает (методы Page Object, фикстуры, API Playwright), поэтому штатные функции IDE получили цель:
- **Ctrl+Click / Cmd+B на объявлении** — список его использований;
- **Show Usages (Cmd+Alt+F7 / Ctrl+Alt+F7)** — всплывающий список использований;
- **Find Usages (Alt+F7)** — штатное окно Find с использованиями по проекту, с курсора и на объявлении, и на использовании (отдельное действие *Find Usages (TypeScript)* больше не нужно);
- **подсветка вхождений** имени под курсором в файле;
- **Quick Definition (Space / Ctrl+Shift+I)** — код объявления во всплывающем окне;
- **Go to Symbol (Cmd+Alt+O)** — работает средствами GigaIDE.

## 1.8.0

- **Подсказка параметров, как в IDEA**:
  - появляется сама, если кликнуть внутрь скобок вызова (`selectFromDropDown(|)`) или прийти туда стрелками;
  - после запятой снова показывает подсказку, выделен уже следующий параметр;
  - не закрывается, пока вы печатаете аргументы.
- **Подсказки имён параметров** перед литералами: `selectFromDropDown(page, locator: "#course", name: "Course")` — серые `locator:` / `name:`, как в IDEA (не показываются, когда аргумент назван так же, как параметр). Отключаются в настройках (*Parameter name hints*).
- **Find Usages (Alt+F7)** в TS/JS: все использования метода, функции, фикстуры, поля — в окне Find, по всему проекту. Также в контекстном меню редактора: *Find Usages (TypeScript)*.
- **Structure (Cmd+F12 / Ctrl+F12 и окно Structure)**: классы с полями и методами, функции, тесты (`test("…") callback`); сортировка по алфавиту, переход по клику.

## 1.7.0

- **Ошибки показываются штатно**:
  - при наведении — подсказка с причиной (например, «'string' only refers to a type, but is being used as a value here (TS2693)»);
  - лампочка у ошибки и **Alt+Enter** со списком исправлений;
  - F2 — к следующей ошибке, ошибки текущего файла видны в окне **Problems**.

  В 1.6.0 ошибки рисовались мимо механизма IDE, поэтому подсказки и лампочки не было.
- **Исправления для хелперов** (в дополнение к исправлениям TypeScript):
  - `page` без типа → *Annotate 'page' as Page (@playwright/test)*: дописывает `: Page` и импорт;
  - параметры вроде `locator`, `selector`, `name` → *Annotate as string*; любой параметр → *Annotate as any*;
  - `name = string` → *Change '= string' to ': string'*.
- **Подсказка параметров**, как в WebStorm: при вводе `(` и по Ctrl+P / Cmd+P — `selectFromDropDown(page: Page, name: string, locator: string, custom?: any)`, текущий параметр выделен. Показывается и сразу после выбора метода в автодополнении.
- **Документация при наведении и по Ctrl+Q / F1**: тип и описание от TypeScript, в том числе для методов Playwright (`fill`, `click`, `waitForResponse`…). При Ctrl+наведении — сигнатура.

## 1.6.0

- **Ошибки TypeScript в редакторе**: красное подчёркивание и подсказка при наведении, F2 переходит к следующей ошибке — например, `Cannot find name 'selectFromDropDown'` для неимпортированной функции. Неиспользуемое показывается серым. JS-плагин GigaIDE таких ошибок не показывает. Отключается в настройках (*TypeScript errors in the editor*).
- **Alt+Enter**: исправления от TypeScript — *Add import from "../../utils/customHelpers"*, *Add missing function declaration*, *Infer parameter types from usage* и другие.
- **Автоимпорт в автодополнении**: функции и классы из других файлов проекта предлагаются при наборе (справа — откуда), а при выборе импорт добавляется сам.
- **Методы Playwright у параметров без типа** (`function select(page, locator) { page.click(locator) }`), как в IntelliJ:
  - Ctrl+Click ведёт к `Page.click`, `Locator.click`… — при нескольких вариантах IDE показывает список;
  - после `page.` предлагаются методы Page, Locator и других классов Playwright;
  - такие вызовы подсвечиваются цветом методов.
- Подсветка открытых файлов обновляется, когда TypeScript-сервер присылает новые ошибки.

## 1.5.0

- **Автодополнение от TypeScript-сервера**: после `page.`, `locator.`, фикстуры или Page Object — методы и свойства Playwright (`waitForResponse`, `waitForTimeout`, `innerText`…), ключевые слова (`await`) и глобальные имена (`console`). JS-плагин GigaIDE не знает типов из `node_modules`, поэтому сам ничего не предлагает. Список открывается после `.`, у методов добавляются скобки. Работает со всеми тремя серверами (TS 7, `tsserver.js`, typescript-language-server); отключается в настройках (*Code completion from TypeScript*).
- **`log` + Tab** → `console.log();` и постфиксные **`.log`** / **`.error`** + Tab: `page.url().log` → `console.log(page.url())`.
- Diagnose Highlighting: строка `goto '<слово под курсором>'` — что сервер ответил на Ctrl+Click, за сколько миллисекунд и смогла ли IDE открыть цель.

## 1.4.0

- **TypeScript-сервер на Node.js** — для машин, где политика безопасности запрещает запускать нативные бинарники (`Exec failed, error=1 (Операция не позволена)`, SberOS). Навигация и подсветка работают на TypeScript 5/6 из `node_modules` проекта, ставить TypeScript 7 не нужно.
- **Settings → Tools → Playwright Toolkit → TypeScript server**:
  - **Auto** — нативный TypeScript 7; если его нет или он не запускается — `tsserver.js`, затем typescript-language-server;
  - **Native TypeScript 7**;
  - **tsserver.js via Node.js** — `node_modules/typescript/lib/tsserver.js` проекта, протокол как у VS Code;
  - **typescript-language-server** — встроен в плагин (5.1.3, Apache-2.0, один файл без зависимостей), использует TypeScript проекта; нужен Node.js 20+.
- Три сборки отличаются только значением по умолчанию: `playwright-toolkit-1.4.0.zip` (auto), `-1.4.0-tsserver.zip`, `-1.4.0-tsls.zip`. В любой сборке сервер можно переключить в настройках.
- Diagnose Highlighting и Check Environment показывают выбранный сервер, почему пропущены другие (`TS skipped: native: … Exec failed`) и версию TypeScript.
- Исправлена гонка: запрос подсветки мог уйти в сервер раньше, чем текст файла.

## 1.3.1

- Linux: если бинарник TypeScript 7 после установки из корпоративного реестра без права на запуск, плагин выставляет его сам.
- «Install TypeScript 7 for the IDE» проверяет, что платформенный бинарник (`@typescript/typescript-<os>-<arch>`) действительно установлен, и доставляет его отдельной командой; иначе показывает понятную ошибку.
- Diagnose Highlighting: короткая сводка в окне (IDE, ОС, счётчики подсветки, состояние сервера TS 7, где искали бинарник, пробный запуск `tsc --version`) для переписывания вручную; полный отчёт — в буфере и в `idea.log`.
- Check Environment объясняет поиск TypeScript 7 по папкам `node_modules`.

## 1.3.0

- **Explore locators**: при открытом Show Browser локатор под курсором подсвечивается в браузере — селекторы Page Object, `page.locator(...)`/`getByRole(...)`, `mainPage.selectors.x` (значение находит TypeScript 7); на паузе отладчика выражение вычисляется в кадре. Действия не выполняются.
- Значок запуска через tsx/node:test от плагина NodeJS GigaIDE скрыт в Playwright-тестах (ошибка «tsx binary not found»).
- Пути в ошибках и вложениях — относительно проекта.
- Руководство пользователя со скриншотами: [docs/USER_GUIDE.md](docs/USER_GUIDE.md).

## 1.2.0 (не выпускалась отдельно, вошла в 1.3.0)

- **Дерево тестов** в окнах Run и Debug: файл → describe → тест, ✓/✗/⊘ и время, переход к коду по двойному клику, кликабельный стек и вложения, сравнение expected/actual. Репортер подключается через `PW_TEST_REPORTER`, поэтому `html` и `playwright-extended-report` из `playwright.config` продолжают генерироваться.
- Результат последнего прогона — значками у `test(` в gutter; кнопка **Rerun Failed Tests**.
- Параметры конфигурации: `--project` (выпадающий список из конфига), `--grep` и `--grep-invert` с автодополнением `@tag`, retries, `--repeat-each`, `--max-failures`, workers, `--last-failed`, `--trace`.
- Контекстное меню упавшего теста: **Open Trace**, **Open Screenshot / Attachment**, **Open in Report** (playwright-extended-report сразу на странице теста, иначе стандартный HTML-отчёт).
- История из `extended-report-history.json` в выводе теста: «✓✓✗ — failed 1 of 3, NEW failure».
- **Tools → Playwright**: Show Browser (браузер не закрывается между прогонами), Close Browser, Show Report, Record New Test (Codegen), Install Browsers, Check Environment, Diagnose Highlighting.
- Шаблоны кода: `pwt`, `pwd`, `pwstep`, `pwbe`, `pwexp`, `pwloc`, `pwfix`, `pwpage`.

## 1.0.3

- Подсветка тестов исправлена для всех тем (Darcula, Dark, Islands Dark):
  - вызовы `test(...)`, `expect(...)` и методы получают цвета WebStorm;
  - имена в `import` красятся как их использования;
  - наша подсветка рисуется поверх «обычной» подсветки GigaIDE, но ниже ошибок и предупреждений;
  - для Darcula плагин добавляет цвета JS-ключей WebStorm, которых в GigaIDE нет.
- Радужные скобки по уровню вложенности (настройка, включены по умолчанию).
- Параметры и локальные переменные раскрашиваются, если включён Settings → Editor → Color Scheme → Language Defaults → Semantic highlighting.
- Tools → «Playwright Toolkit: Diagnose Highlighting»: отчёт о подсветке строки под курсором.

## 1.0.0

Первый выпуск.

- Go to Declaration для TS/JS через нативный TypeScript 7 (фикстуры, Page Object, локальные объекты, импорты, JSON), с учётом несохранённых правок.
- Семантическая подсветка в тестах цветами WebStorm (`JS.GLOBAL_FUNCTION`, `JS.INSTANCE_MEMBER_FUNCTION`, поля, локальные переменные); имена в `import` красятся так же, как их использования.
- Конфигурация «Playwright Test», Run/Debug из gutter, тест под курсором, запуск файла или папки.
- Отладчик поверх vscode-js-debug из поставки GigaIDE: брейкпоинты в тестах, фикстурах и Page Object, шаги, переменные, watches, Evaluate, условия, Run to Cursor.
- Пауза на упавшем шаге с текстом ошибки, браузер в видимом режиме при дебаге, `PWDEBUG=console`.
- Вывод Playwright в консоли отладчика; список недостигнутых брейкпоинтов в конце сессии.
- Совместимость с отладчиком GigaIDE в split mode (2025.3+).
- Обход бага GigaIDE: отключение Jest-маркера NodeJS-плагина, из-за которого анализ `*.test.ts` не завершался.
