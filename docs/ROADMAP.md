# План развития Playwright Toolkit

Цель — довести плагин до уровня официального расширения [Playwright Test for VS Code](https://marketplace.visualstudio.com/items?itemName=ms-playwright.playwright) там, где это даёт пользу в ежедневной работе, и **встроиться** в наш отчёт `playwright-extended-report`, а не дублировать его.

Работа идёт в ветке `feature/vscode-parity`. Каждый пункт — отдельный PR с тестами.

## Уже сделано (1.0.3)

| Возможность | Как |
|---|---|
| Go to Declaration для TS/JS | LSP нативного TypeScript 7 |
| Семантическая подсветка в тестах, радужные скобки | семантические токены TS 7 → цветовые ключи WebStorm/GigaIDE |
| Run/Debug из gutter, тест под курсором, файл/папка | конфигурация «Playwright Test» |
| Debug step-by-step, переменные, watches, условия | свой DAP-клиент поверх vscode-js-debug |
| Пауза на упавшем шаге | babel-трансформация `PW_TEST_SOURCE_TRANSFORM`, как в VS Code |
| Консоль отладчика, отчёт о недостигнутых брейкпоинтах | — |
| Обход бага GigaIDE (Jest-маркер ломает анализ `*.test.ts`) | снятие одного extension при старте |

## Этап 1 — результаты прогона внутри IDE ✅ (1.2.0)

**1.1. Дерево тестов и статус в gutter**

Что видит пользователь: после Run/Debug окно Run показывает дерево `файл → describe → test` с ✓/✗/⊘ и временем. Клик по тесту — переход к нему. У упавшего — текст ошибки, кликабельный стек (строки ведут в код), для `expect` — кнопка «Click to see difference» (expected/actual). Иконки ✓/✗ появляются у `test(` в gutter.

Как устроено:
- В IntelliJ дерево тестов (SMTRunner) строится из текстового протокола, который исторически называется **TeamCity service messages**: строки вида `##teamcity[testStarted name='...' locationHint='...']` в stdout. К серверу TeamCity это отношения не имеет — тот же протокол используют JUnit, Jest, pytest в IntelliJ.
- Плагин кладёт маленький репортер `pwtk-reporter.cjs` (~150 строк, публичный Reporter API Playwright), который печатает эти строки.
- Подключение — через переменную окружения **`PW_TEST_REPORTER`**: Playwright **добавляет** этот репортер к репортерам из `playwright.config.ts`. `html` и `playwright-extended-report` продолжают генерироваться как обычно. Флаг `--reporter` в CLI не используется: он заменил бы репортеры из конфига.
- Работает и при Debug: строки идут через вывод js-debug.

**1.2. Перезапуск и режимы прогона**

Всё это есть в CLI Playwright, плагин даёт кнопки и поля:
- «Rerun Failed Tests» в дереве → `--last-failed` (или список `file:line` упавших);
- поля «Повторить N раз» (`--repeat-each`), «Остановиться после N падений» (`--max-failures`), `--retries`, `--workers` в конфигурации;
- выбор `--project` (список из `playwright test --list --reporter=json`), `--grep`/`--grep-invert`, автодополнение тегов `@tag`.

**1.3. Связка с playwright-extended-report**

Плагин не повторяет функции отчёта, а связывает их с IDE:
- у упавшего теста в дереве — «Open in report»: открывает страницу теста в `extended-report` (через `pw-extended-report show`), там Insights, группы ошибок, AI-агент;
- «Open Trace» (`playwright show-trace <zip>`) и «Open screenshot» — пути берутся из attachments результата;
- **история**: если в проекте включена история отчёта (`insights: { history: {} }`, файл `extended-report-history.json`), плагин читает её и показывает у теста в дереве и в подсказке gutter: «новое падение», «падает N прогонов подряд», «нестабилен 3/10», регрессия длительности. Свою историю плагин не ведёт. Внутри IDE дополнительно доступна встроенная история прогонов окна Run (иконка часов);
- «Открыть в IDE» из отчёта прямо в GigaIDE без консольного лаунчера: шаблон URL `http://localhost:63342/api/file/{path}:{line}` (встроенный сервер GigaIDE). Описать в README обоих проектов.

**1.4. Codegen, браузеры, проверка окружения**
- Tools → Playwright → «Record new test (Codegen)», «Install browsers», «Check environment» (Node, Playwright, TS 7, js-debug, браузеры).

**1.5. Шаблоны кода (Live Templates)**
- `pwt` (тест), `pwd` (describe), `pwstep` (`test.step`), `pwbe` (beforeEach), `pwexp` (expect + toBeVisible/toHaveText…), `pwloc` (`page.locator`), `pwfix` (новая фикстура в `test.extend`), `pwpage` (Page Object в стиле проекта: `selectors`, `path`, конструктор).

## Этап 2 — браузер и Explore locators (приоритет)

**2.1. Переиспользуемый браузер (Show browser)** ✅ (1.2.0)
- Как `reusedBrowser.ts` в playwright-vscode: IDE держит фоновый `playwright run-server` и передаёт тестам `PW_TEST_CONNECT_WS_ENDPOINT` + `PW_TEST_REUSE_CONTEXT=1`. Окно браузера не закрывается между прогонами, видно, где остановился тест.
- Кнопка «Close browser» в тулбаре. Основа для 2.2.

**2.2. Explore locators** ✅ (1.3.0)
- Пока отладчик на паузе, курсор на `page.locator(...)`, `getByRole(...)` или `mainPage.selectors.heroSection` → элемент подсвечивается в браузере, рядом с кодом — сколько элементов найдено.
- Как `debugHighlight.ts`: плагин вычисляет выражение локатора в кадре отладчика (`evaluate` — только строка селектора, без async-вызовов) и отправляет его в переиспользуемый браузер (`locator.highlight()`).
- Без паузы (просто в редакторе) — подсветка в открытом браузере по наведению на селектор из `selectors` Page Object.

## Этап 3 — «как в IntelliJ IDEA» (редактор TS/JS)

Сделано (1.5.0–1.9.0):
- автодополнение с автоимпортом;
- ошибки TypeScript с подсказкой и Alt+Enter;
- подсказка параметров (по клику в скобки и после запятой) и подсказки имён параметров;
- документация при наведении и Ctrl+Q;
- Find Usages и Show Usages, Ctrl+Click на объявлении, подсветка вхождений, Quick Definition;
- Structure;
- `log` / `.log`.

Дальше, по порядку:
1. **Type Info (Ctrl+Shift+P)** — тип выражения под курсором.
2. **Go to Type Declaration (Cmd+Shift+B)** — к классу или интерфейсу типа: `page` → `Page`.
3. **Постфиксные шаблоны IDEA**: `.const`, `.let`, `.if`, `.not`, `.return`, `.await`, `.for`, `.forof`, `.notnull`.
4. **Code Vision** — «N usages» над функциями, методами и фикстурами.
5. **Intentions без ошибки (Alt+Enter)** — рефакторинги TypeScript: *Convert to async function*, *Add braces to arrow function*, *Convert to template string* и др.
6. **Extract Variable / Method / Constant (Cmd+Alt+V/M/C)**.
7. **Go to Implementation (Cmd+Alt+B), Go to Super (Cmd+U)**, значки переопределения в gutter.
8. **Call Hierarchy (Ctrl+Alt+H)**.
9. Отладчик: значения переменных прямо в строках кода (inline values) — проверить и доделать.

Бэклог (по решению пользователя): Rename (Shift+F6), Reformat Code, Optimize Imports.

## Этап 4 — из Test Automation / справки JetBrains по Playwright

Уже есть: запуск и отладка из gutter, конфигурация, дерево результатов, перезапуск упавших, HTML-отчёт, headed-режим.

Не хватает:
1. **New → Playwright Test** — файл теста по шаблону (с выбором фикстур из `baseTest`).
2. **Подсветка и проверка селекторов** в строках `locator("…")`: CSS и XPath (синтаксис, ошибки), подсказка «N совпадений» на открытой странице (через Show Browser).
3. **Web Inspector / Pick Locator** — выбрать элемент в браузере и вставить локатор в код (по роли, test-id, CSS, XPath); шаблон генерации локатора в Settings | Editor | File and Code Templates. Сейчас в бэклоге как «pick locator».
4. **Новый проект Playwright** через `create-playwright`.
5. Свёрнутые фреймы Playwright в стеке консоли.

## Отложено, в самом конце

- Watch mode (перезапуск по сохранению) — частично заменяется «Open UI Mode» (`playwright test --ui`).
- Pick locator со вставкой в код — опирается на внутренний API Playwright; фолбэк — Codegen.
- Расширения Chrome при Debug (SelectorsHub, LocatorLabs, CSS Selector Helper): `launchPersistentContext` + `--load-extension` в браузере Playwright (проверено: загружаются в Chrome for Testing 156); требует однократной правки `baseTest.ts`.

Не делаем: встроенный Trace Viewer (есть `show-trace` и отчёт), Intelligence/Scenario Lab, мутационное тестирование, Product Promises, Change Radar из playwright-studio.

## Из playwright-studio — итог

| Возможность | Решение |
|---|---|
| История прогонов, сравнение | Из `extended-report` (история уже есть там) — показываем в IDE (1.3) |
| Ошибки в окне Problems | Заменяется деревом тестов с кликабельным стеком (1.1); полный разбор — в отчёте |
| Run Failed / Repeat until failure | Флаги CLI `--last-failed`, `--repeat-each`, `--max-failures` — кнопки и поля (1.2) |
| Наборы параметров прогона, `.env` | Несколько конфигураций + EnvFile (1.2) |
| Hover по фикстуре | TS 7 `textDocument/hover` → `DocumentationProvider` |
| Health Check | 1.4 |

## Технический долг

- Сборка в CI: сейчас сборка и тесты требуют локальной GigaIDE. Для GitHub Actions — сборка против `intellijIdeaCommunity(2026.1)`; тесты с плагином GigaIDE остаются локальными.
- Ключи подсветки: следить, не начнёт ли GigaIDE сама регистрировать `JS.GLOBAL_FUNCTION` и т.п.
- Обход Jest-маркера: убрать, когда GigaIDE исправит `LinkageError` в NodeJS-плагине.
