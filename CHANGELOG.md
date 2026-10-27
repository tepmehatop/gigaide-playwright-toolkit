# Changelog

## 1.2.0

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
