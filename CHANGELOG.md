# Changelog

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
