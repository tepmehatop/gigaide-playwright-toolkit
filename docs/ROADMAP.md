# План развития Playwright Toolkit

Цель — довести плагин до уровня официального расширения [Playwright Test for VS Code](https://marketplace.visualstudio.com/items?itemName=ms-playwright.playwright) и забрать полезное из [playwright-studio](https://github.com/sumanthtps/playwright-studio).

Источники:
- `microsoft/playwright-vscode`: `src/playwrightTestServer.ts` (запуск и дебаг через `test-server`), `src/debugTransform.ts`, `src/reusedBrowser.ts` (общий браузер, pick/highlight), `src/debugHighlight.ts`;
- README и `docs/features.md` playwright-studio.

Работа идёт в ветке `feature/vscode-parity`. Каждый пункт — отдельный PR с тестами.

## Уже сделано (1.0.0)

| Возможность | Как |
|---|---|
| Go to Declaration для TS/JS | LSP нативного TypeScript 7 |
| Семантическая подсветка в тестах | семантические токены TS 7 → цветовые ключи WebStorm/GigaIDE |
| Run/Debug из gutter, тест под курсором, файл/папка | конфигурация «Playwright Test» |
| Debug step-by-step, переменные, watches, условия | свой DAP-клиент поверх vscode-js-debug |
| Пауза на упавшем шаге | babel-трансформация `PW_TEST_SOURCE_TRANSFORM`, как в VS Code |
| Браузер при дебаге, `PWDEBUG=console` | `--headed` |
| Консоль отладчика, отчёт о недостигнутых брейкпоинтах | — |
| Обход бага GigaIDE (Jest-маркер ломает анализ `*.test.ts`) | снятие одного extension при старте |

## Этап 1 — результаты прогонов и быстрые выигрыши

**1.1. Дерево тестов (SMTRunner) и статус в gutter**
- Собственный reporter Playwright (`.cjs` в ресурсах плагина), он печатает TeamCity service messages: `testSuiteStarted`, `testStarted`, `testFailed` с `message`/`details`/`expected`/`actual`, `testFinished` с `duration`, `testIgnored`. Подключается через `--reporter=<path>,list`.
- `SMTRunnerConsoleView` + `SMTestLocator` по протоколу `pwtest://file:line` для перехода к тесту по двойному клику.
- Иконки ✓/✗ у `test(` из `TestStateStorage` (как в JUnit).
- Действие «Rerun Failed Tests» (`AbstractRerunFailedTestsAction`): перезапуск по `file:line` упавших тестов.
- Работает и для Debug: те же сообщения идут через вывод js-debug.

**1.2. Выбор project / тегов / grep**
- Gutter-меню «Run in project…»: проекты читаются через `playwright test --list --reporter=json`.
- Чекбоксы `--project` и поле `--grep`/`--grep-invert` в редакторе конфигурации, автодополнение тегов `@tag` из исходников.

**1.3. Trace и отчёты**
- Переключатель «Record trace» (`--trace on`) в конфигурации и в настройках.
- У упавшего теста в дереве — «Open Trace» (`playwright show-trace <zip>`, путь берётся из attachments в reporter-сообщениях).
- Действие «Show HTML Report» (`playwright show-report`).

**1.4. Codegen и установка браузеров**
- Tools → Playwright → «Record new test (Codegen)», «Install browsers» (`playwright install`).

**1.5. Шаблоны кода (Live Templates)**
- `pwt` (тест), `pwd` (describe), `pwstep` (`test.step`), `pwbe` (beforeEach), `pwexp` (expect + toBeVisible/toHaveText…), `pwloc` (`page.locator`), `pwfix` (новая фикстура в `test.extend`), `pwpage` (Page Object в стиле проекта: `selectors`, `path`, конструктор).

## Этап 2 — браузер и цикл «правка → прогон»

**2.1. Show browser / переиспользуемый браузер**
- Как `reusedBrowser.ts`: IDE держит фоновый `playwright run-server` (или `launchServer`) и передаёт тестам `PW_TEST_CONNECT_WS_ENDPOINT` + `PW_TEST_REUSE_CONTEXT=1`. Окно браузера не закрывается между прогонами, видно, где остановился тест.
- Кнопка «Close browser» в тулбаре.

**2.2. Watch mode**
- Переключатель «Watch» у конфигурации: `VirtualFileManager`-слушатель на сохранение тестов, Page Object и фикстур. Перезапускаются затронутые тесты: сам файл или тесты, импортирующие изменённый модуль (граф импортов из TS 7: `textDocument/references` или `--list` + `--only-changed`).
- Альтернатива: «Open UI Mode» (`playwright test --ui`) — там watch уже есть.

**2.3. Pick locator**
- Кнопка «Pick locator» → в переиспользуемом браузере включается режим инспектора (`page._enableRecorder({ mode: 'inspecting' })` через подключение к тому же серверу) → выбранный локатор вставляется в код под курсором, вариант — в `selectors` Page Object.
- Риск: внутренний API Playwright. Фолбэк — `playwright codegen <url>` с ручным копированием.

## Этап 3 — исследование во время отладки

**3.1. Explore locators (подсветка в браузере при паузе)**
- Как `debugHighlight.ts`: когда отладчик на паузе и курсор стоит на `page.locator(...)` или `getByRole(...)`, плагин вычисляет выражение локатора (через `evaluate` в кадре — получить селектор, без async) и просит переиспользуемый браузер `locator.highlight()`.
- Зависит от 2.1.

**3.2. Встроенный Trace Viewer**
- `playwright show-trace --port 0` + JCEF-вкладка в IDE вместо отдельного окна.

## Из playwright-studio

| Берём | Где |
|---|---|
| История прогонов, сравнение двух прогонов | этап 1 (поверх SMTRunner: экспорт/импорт результатов) |
| Ошибки упавших тестов в окне Problems со ссылками на trace и скриншоты | этап 1.3 |
| Run Failed / Repeat until failure (`--repeat-each`, `--max-failures=1`) | этап 1.1 |
| Сохраняемые наборы параметров прогона (presets): projects, grep, retries, workers, env | этап 1.2 (несколько конфигураций + шаблон) |
| Профили окружения / `.env`-файл | этап 1.2 (EnvFile в конфигурации) |
| Hover по фикстуре: тип и место объявления | TS 7 `textDocument/hover` → `DocumentationProvider` |
| Health Check (Node, Playwright, TS 7, js-debug, браузеры) | Tools → Playwright → «Check environment» |

Не берём: Intelligence/Scenario Lab, мутационное тестирование, агентов, Product Promises, Change Radar — экспериментально, без пользы для ежедневной работы.

## Технический долг

- Сборка в CI: сейчас сборка и тесты требуют локальной GigaIDE. Для GitHub Actions — сборка против `intellijIdeaCommunity(2026.1)`; тесты с плагином GigaIDE остаются локальными.
- Ключи подсветки: следить, не начнёт ли GigaIDE сама регистрировать `JS.GLOBAL_FUNCTION` и т.п.
- Обход Jest-маркера: убрать, когда GigaIDE исправит `LinkageError` в NodeJS-плагине.
