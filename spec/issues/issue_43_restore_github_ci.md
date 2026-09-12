# VIG-43: Исправить GitHub CI workflow

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

Workflow `.github/workflows/ci.yml` валиден и сохраняет обязательный build
для PR и push в main. Отсутствие `NVD_API_KEY` не ломает workflow validation;
условия steps предусматривают OWASP только при доступном ключе и явный skip
при его отсутствии. Исправление принимается по локальному workflow validator,
проверке условий и полному локальному build на commit из PR.
GitHub execution недоступен из-за биллинга аккаунта и не объявляется успешным.

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
- [ ] Локально проверены условия workflow для двух разных входов: пустой и непустой `env.NVD_API_KEY`. Build независим; scan выбран только для непустого ключа, skip-step только для пустого. Точный skip shell-step публикует notice/summary без значения. Fork/Dependabot без предоставленного secret документированы как пустой вход; это не выдаётся за реальный запуск таких событий.
- [ ] Сохранены `./gradlew build`, runtimeClasspath scan, действующий CVSS gate, trigger PR/push main, reports и минимальные permissions. Нельзя скрыть failure через continue-on-error или убрать проверки ради GREEN.
- [ ] Полный локальный `./gradlew build` на commit из PR успешен; сохранены exact commit, run ID и reports. Недоступные GitHub execution и key-enabled OWASP явно отмечены и не объявляются PASS; hosted run не является условием закрытия этого исправления.
- [ ] Development guide точно отражает фактическую skip/scan схему; каталог и ссылки валидны.

## Согласованная локальная приёмка

2026-09-11 владелец сообщил: «я не смогу разблокировать аккаунт». На предложение
выполнить полный локальный build на commit из PR, изменить критерий приёмки,
зафиксировать недоступность GitHub execution и закрыть исправление YAML
владелец ответил: «выполняй». Это явное изменение границы приёмки VIG-43;
общие quality gates проекта и scope других задач не меняются.

[GitHub run 34641245206](https://github.com/finnetrolle/vigilant/actions/runs/34641245206)
на commit `96a4e7be1854ab3a88296f70378fa44fc2410c2a` создал обе jobs, но ни одна
не начала steps. Обе check-run annotations сообщают о блокировке аккаунта
из-за billing issue. Repository secrets отсутствуют; фактические hosted
build/skip/scan остаются неподтверждёнными.

## Проверки

Локальный actionlint; выполнение условий workflow через GitHub expression
evaluator для пустого/непустого env без настоящего ключа; точный skip shell-step;
`./gradlew build` на опубликованном commit через `scripts/check-run`;
`./gradlew workItemValidatorTest --rerun validateWorkItems` после окончательного
обновления каталога; `git diff --check`. Локальные результаты не подтверждают
исполнение workflow GitHub runner или актуальный vulnerability scan.

## Не входит

- Новые CI gates, release publication, dependency upgrades, ротация secrets,
  изменение branch protection, релиз runtime, обход approval/push workflow.
- Восстановление биллинга GitHub, перенос CI на другую платформу и заявление
  об успешных GitHub build/scan без их выполнения.

## Ambiguity Report

Goals: 0.0; Acceptance: 0.1; Boundaries: 0.0; Alternatives: 0.0;
Assumptions: 0.1; Aggregate: 0.04.
