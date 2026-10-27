# Playwright Toolkit for GigaIDE

Плагин для **GigaIDE 2026.1** (и других IDE на платформе IntelliJ 2025.1+ без коммерческого JavaScript-плагина) для тех, кто пишет автотесты на **Playwright + TypeScript/JavaScript**.

## Возможности

- **Go to Declaration (Cmd/Ctrl+Click)** для TS/JS: фикстуры из `test.extend<...>`, методы и поля Page Object, свойства локальных объектов, импорты, JSON.
- **Семантическая подсветка в тестах** (цвета WebStorm): `test`/`expect`, методы и поля Page Object, имена в `import`; радужные скобки; параметры — через Semantic highlighting в Language Defaults.
- **Run/Debug из gutter** у `test(...)` и `test.describe(...)`, запуск файла или папки из дерева проекта, «тест под курсором». Конфигурация **Playwright Test**: `--project`, `--grep`, доп. аргументы, env, `--headed`.
- **Дерево тестов** в окнах Run и Debug: статусы, время, переход к коду, кликабельный стек и вложения, expected/actual, **Rerun Failed Tests**, результат последнего прогона у `test(` в gutter.
- **Параметры прогона**: `--project` из конфига, `--grep`/`--grep-invert` с автодополнением `@tag`, retries, `--repeat-each`, `--max-failures`, workers, `--last-failed`, `--trace`.
- **Trace, вложения и отчёт** у упавшего теста (контекстное меню дерева): Open Trace, Open Screenshot / Attachment, Open in Report. История прогонов из playwright-extended-report — в выводе теста.
- **Show Browser**: один видимый браузер не закрывается между прогонами, как в VS Code (Tools → Playwright).
- **Tools → Playwright**: Show Report, Codegen, Install Browsers, Check Environment.
- **Шаблоны кода**: `pwt` (тест), `pwd` (describe), `pwstep`, `pwbe` (beforeEach), `pwexp`, `pwloc`, `pwfix` (фикстура), `pwpage` (Page Object).
- **Отладка с брейкпоинтами** в тестах, фикстурах и Page Object: step over/into/out, переменные, watches, Evaluate Expression, условные брейкпоинты, Run to Cursor.
- **Пауза на упавшем шаге** (как в VS Code): при падении `expect` или локатора отладчик останавливается на этой строке, текст ошибки выводится в консоль.
- При дебаге браузер открывается в видимом режиме, таймауты отключены, в DevTools браузера доступен объект `playwright`.
- В конце дебаг-сессии в консоли — список брейкпоинтов, до которых выполнение не дошло.
- **Обход бага GigaIDE:** Jest-маркер плагина NodeJS ломает анализ `*.test.ts` (вечное «Analyzing…»). Плагин отключает только этот маркер.

## Установка

1. Скачайте `playwright-toolkit-<версия>.zip` со страницы [Releases](../../releases). Распаковывать не нужно.
2. GigaIDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…** → выберите zip.
3. Перезапустите IDE.

## Требования к проекту

- Node.js (берётся из настройки плагина, PATH, `~/.nvm`, `/opt/homebrew`, `/usr/local`).
- `@playwright/test` в `node_modules` (`npm install`).
- Для навигации и подсветки — `typescript@^7` в `devDependencies`. Если его нет, плагин предложит поставить общую копию для IDE.
- Отладчик vscode-js-debug ставить не нужно: он входит в GigaIDE (`plugins/nodeJS/js-debug`). Лицензия GigaIDE Pro и плагин «JavaScript Debugger» не требуются.

## Использование

- **Навигация:** Cmd+Click или Cmd+B по идентификатору.
- **Запуск:** ▶ у `test(` или `test.describe(` → *Run* / *Debug*. Правый клик в теле теста → запустить этот тест. Правый клик по файлу или папке в дереве проекта → запустить все тесты в нём.
- **Брейкпоинты:** клик по gutter в любом `.ts`/`.js`-файле.
- **Настройки:** *Settings → Tools → Playwright Toolkit* — пути к Node, TypeScript 7 и js-debug; навигация и подсветка; браузер и пауза на ошибке при дебаге; обход Jest-маркера.

## Связка с playwright-extended-report

- **Open in Report** у теста в дереве запускает `pw-extended-report show` и открывает страницу этого теста (`#?testId=…`). Если пакет не установлен, открывается стандартный HTML-отчёт (`playwright show-report`) на главной странице.
- **История**: если в отчёте включена история (`insights: { history: {} }`), плагин читает `extended-report-history.json` (или `EXTENDED_REPORT_HISTORY_FILE`) и пишет её в вывод теста.
- **«Открыть в IDE» из отчёта прямо в GigaIDE**: в ⚙ отчёта выберите свой шаблон URL `http://localhost:63342/api/file/{path}:{line}` — это встроенный сервер GigaIDE, консольный лаунчер не нужен.

## Как это устроено

| Возможность | Реализация |
|---|---|
| Навигация, подсветка | LSP нативного TypeScript 7 (`tsc --lsp --stdio`): `textDocument/definition`, `semanticTokens`. Несохранённые правки передаются в сервер. |
| Отладка | Свой `XDebugProcess` и DAP-клиент поверх vscode-js-debug. Playwright запускается как `node node_modules/@playwright/test/cli.js test <file>:<line> --workers=1 --timeout=0`; worker-процессы подключаются как дочерние DAP-сессии (`startDebugging`). |
| Пауза на ошибке | Babel-плагин через `PW_TEST_SOURCE_TRANSFORM`: оборачивает шаги теста в `try/catch` с `debugger` (идея из `microsoft/playwright-vscode`). |

## Разработка

```bash
./gradlew test        # интеграционные тесты (нужен npm install в samples/playwright-offline)
./gradlew runIde      # GigaIDE-песочница с плагином
./gradlew buildPlugin # build/distributions/playwright-toolkit-<версия>.zip
```

Путь к GigaIDE задаётся в `gradle.properties` (`gigaIdePath`). `samples/playwright-offline` — офлайн-проект в стиле наших тестов: `tests/baseTest.ts` с фикстурами, `pages/*.page.ts`, `pages/blocks/*.block.ts`, `data/*.json`.

План развития: [docs/ROADMAP.md](docs/ROADMAP.md). История версий: [CHANGELOG.md](CHANGELOG.md).

## Лицензия

MIT, см. [LICENSE](LICENSE). Трансформация «пауза на упавшем шаге» адаптирована из microsoft/playwright-vscode (Apache-2.0).
