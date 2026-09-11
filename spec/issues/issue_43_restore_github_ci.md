# VIG-43: Восстановить выполнение GitHub CI

- **ID:** `VIG-43`
- **Тип:** Issue
- **Статус:** In progress
- **Приоритет:** P1
- **Зависит от:** нет
- **Блокирует:** нет
- **Оценка:** 0.5-1 инженерного дня
- **Уверенность:** высокая
- **Архитектурный риск:** Low - локальная правка условия существующего workflow без изменения runtime или состава gates.

## Результат

Workflow `.github/workflows/ci.yml` валиден и запускает обязательный build на
PR и push в main. Отсутствие `NVD_API_KEY` не ломает workflow validation;
OWASP выполняется при доступном ключе, а недоступность ключа имеет явный
безопасный skip status.

## Контекст

Пять последних runs, просмотренных 2026-09-10, завершились failure до jobs.
[Run текущего тогда HEAD d2e272c](https://github.com/finnetrolle/vigilant/actions/runs/34465318732)
не имеет check runs; `gh run view` сообщает вероятную workflow file issue.
В job-level `if` стоит `secrets.NVD_API_KEY`, что не допускается
[правилами GitHub](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).
Отдельной post-line annotation API не вернул: выражение надо подтвердить
workflow validator, а не выдавать предположение за server diagnostic.

Минимальный подход: перенести secret в job environment, применять condition
на уровне steps, сохранить существующие build и dependency-check duties.
Secret value не печатать и не передавать в argv.

## Context sources

- `docs/development.md#ci`
- `docs/development.md#owasp-dependency-check`
- `docs/agent-workflow.md#behavior-first-development-and-selective-tdd`

## Критерии готовности

- [ ] Workflow validator (например actionlint) воспроизводит недопустимый secrets context и принимает исправленный workflow без ошибок.
- [ ] Матрица key present / absent / недоступен для fork или Dependabot: обязательный build остаётся исполним; dependency scan выполняется только с доступным ключом, отсутствие ключа явно отмечено без вывода значения.
- [ ] Сохранены `./gradlew build`, runtimeClasspath scan, действующий CVSS gate, trigger PR/push main, reports и минимальные permissions. Нельзя скрыть failure через continue-on-error или убрать проверки ради GREEN.
- [ ] После публикации изменения GitHub run содержит реально выполненный успешный build job; key-enabled OWASP подтверждён, если key доступен. Недоступную ветку нельзя объявлять PASS по одному YAML review.
- [ ] Development guide точно отражает фактическую skip/scan схему; каталог и ссылки валидны.

## Проверки

Локальный workflow validator, `./gradlew workItemValidatorTest --rerun
validateWorkItems`, `git diff --check`; после обычной публикации -
`gh run view <run-id>` и job outcomes на exact commit. Полный локальный runtime
build не повторять для одной правки YAML без отдельной причины.

## Не входит

- Новые CI gates, release publication, dependency upgrades, ротация secrets,
  изменение branch protection, релиз runtime, обход approval/push workflow.

## Ambiguity Report

Goals: 0.0; Acceptance: 0.1; Boundaries: 0.0; Alternatives: 0.0;
Assumptions: 0.1; Aggregate: 0.04.
