# VIG-33: Эксплуатационная готовность первого Docker-релиза

- **ID:** `VIG-33`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** Medium
- **Зависит от:** нет
- **Блокирует:** нет
- **Оценка:** 2-3 инженерных дня
- **Уверенность:** Medium; документация и проверка существующей поставки, без создания внешней инфраструктуры
- **Архитектурный риск:** High - фиксирует эксплуатационный контракт и обязанности deployment

## Context sources

- `docs/deployment.md#health-и-lifecycle`
- `docs/deployment.md#stdout-telemetry`
- `docs/observability.md#metrics`
- `spec/requirements/observability.md#stdout-topology-and-ownership`
- `spec/requirements/http-gateway.md#health-admission-and-shutdown`
- `docs/requirements-coverage.md#observability-evidence`

## Scope lock

1. **Наблюдаемый продуктовый результат:** администратор получает проверяемую
   инструкцию подготовки первого Docker deployment Vigilant. Выпуск готовится
   до накопления production telemetry и выбора численного availability SLO.
2. **Минимальное достаточное решение:** описать эксплуатацию существующего
   контейнера, границу stdout, методику оценки доступности и checklist приёмки
   на стенде; получить относящиеся к контейнеру и stdout evidence. Основной seam
   задачи - операторский контракт, сверенный с поведением Docker-поставки.
3. **Обязательные свойства результата:** одиночная первая production-реплика
   при поддержке нескольких stateless replicas за внешним балансировщиком;
   общий SLI с учётом технических отказов всех зависимостей и отдельной причиной;
   application logs и OTLP JSON metrics/traces в stdout; внешний probe с
   обнаружением падения за две минуты; первый review через 14 календарных дней.
4. **Явные non-goals:** поставка конфигураций telemetry chain, развёртывание
   Fluentd, Collector, OpenObserve, балансировщика или мониторинга, dashboards,
   alert delivery, autoscaling, Kubernetes/Helm, shared runtime state, durable
   audit, direct application network exporters, новые runtime SLI instruments,
   изменение enforcement, retries или shutdown semantics. Численный SLO,
   постоянное окно и error-budget policy выбираются после production review.
   Несвязанные conformance gaps не входят в задачу и не объявляются закрытыми.
5. **Более сложные альтернативы:** конфигурация всей telemetry chain отклонена
   владельцем: её настраивает администратор на стендах. Обязательное
   многорепличное первое production deployment не требуется. Ожидание
   production measurements перед выпуском заменено подготовкой измерений
   и последующим разбором данных.
6. **Условие пересмотра:** первый production review, недостаток данных для SLI
   или атрибуции, новые требования к доступности, масштабу или delivery.
   Расширение runtime либо инфраструктуры требует отдельного согласованного scope.
7. **Подтверждение:** владелец проекта согласовал границы в grill-сессии по
   VIG-33 от 2026-09-10: Docker, сначала одна реплика при поддержке нескольких,
   ответственность администратора за chain, все три signal types, включение
   dependency failures, внешний probe и review через 14 дней. Конфигурация
   цепочки явно исключена ответом на вопрос 8; период подтверждён в вопросе 9.

## Доступность и границы измерения

Production deployment и measurements пока отсутствуют. Подготовка выпуска и
выполнение VIG-33 не требуют ждать production observation period. Численный
SLO не выбран; 14 дней задают первый review, а не постоянное окно будущего SLO.

Методика описывает request-based SLI: долю штатно завершённых подходящих
клиентских попыток среди всех таких попыток за явно указанный период.
Подходящая попытка - корректный аутентифицированный запрос в поддерживаемом
контракте. HTTP status class сам по себе не определяет штатность результата.

| Наблюдаемый результат | Учёт |
|---|---|
| Полностью завершённый ALLOW/MASK или корректный policy BLOCK | Штатный результат, входит в числитель и знаменатель |
| Технический отказ Vigilant, identity provider, upstream LLM, балансировщика или инфраструктуры | Неуспех, входит только в знаменатель; причина учитывается отдельно |
| HTTP 200 с последующим техническим обрывом ответа | Неуспех; headers не доказывают завершение запроса |
| Явно некорректный запрос или отказ аутентификации по вине клиента | Вне знаменателя; не смешивается с недоступностью identity provider |
| Подтверждённая добровольная отмена клиентом до технического сбоя | Вне знаменателя; cancellation после timeout/failure не скрывает неуспех |
| Технический сбой с неизвестной причиной | Неуспех с неизвестной причиной, без произвольной атрибуции |
| Нет подходящих запросов | SLI не определён; это не 100% доступности |
| Пропущенные terminal observations или неполные данные о попытках | Явный evidence gap; отсутствие записи не считается успехом |

Одна попытка учитывается один раз; log, spans и metrics одного exchange не
суммируются как разные запросы. Retry клиента является отдельной попыткой.
Плановый restart не исключает технические неуспехи из общего показателя.
Классификация не меняет HTTP/enforcement contract.

Независимый пример проверки методики: два ALLOW, один MASK, один policy BLOCK
и по одному техническому отказу Vigilant, identity provider, upstream и
инфраструктуры дают `4 / 8 = 50%`. Дополнительный invalid client request не
меняет результат. Потеря terminal observation обозначает неполноту данных.

Нынешние proxy counters отражают дошедший до приложения трафик и HTTP status
classes; они не наблюдают запросы к упавшему контейнеру и не дают полной
атрибуции причин. Простое `1 - 5xx / requests` не публикуется как готовый
end-to-end SLI. Администратор определяет источники ingress/client observations
и проверяет полноту. Доступные данные и отсутствующие наблюдения фиксируются
раздельно; новый runtime collector или SLI instrument в задачу не входит.

## Ответственность и эксплуатационная приёмка

Vigilant предоставляет Docker artifact, health/readiness, текущий bounded
shutdown и документированный stdout. Администратор отвечает за запуск,
restart/rollout/recovery, балансировку, сбор и хранение telemetry, внешний
probe, уведомления и реакцию на инциденты.

Целевая deployment chain: Docker stdout -> Fluentd -> OpenTelemetry Collector
-> OpenObserve. Администратор настраивает её на стендах и сохраняет logs,
metrics и traces как соответствующие signal types. Приложение предоставляет
описание JSONL envelopes, metric names/units/attributes и correlation;
конфигурации chain, endpoints и secrets в поставку VIG-33 не входят.
Bounded non-blocking delivery и best-effort semantics остаются прежними.

Внешний probe обращается к `/readyz` каждые 30 секунд, ограничивает попытку
пятью секундами и формирует сигнал после трёх последовательных неудач.
Неудача - timeout, connection error или ответ, отличный от 200; успешная
проверка сбрасывает последовательность. Probe работает вне контейнера
Vigilant. От падения реплики до формирования сигнала проходит не более
120 секунд; канал и доставка уведомлений принадлежат администратору.
Сбой probe или telemetry chain не подтверждает доступность приложения.
`/readyz` не проверяет identity/upstream и не заменяет request-based SLI.

Операторский checklist задаёт наблюдаемые сценарии на стенде:

- Одна реплика: startup с production-compatible identity configuration,
  health/readiness, полный тестовый запрос, остановка и restart. На время
  остановки обслуживание недоступно; после restart readiness и тестовый
  запрос снова успешны. Отдельного численного recovery target нет.
- Graceful shutdown: readiness становится 503, новый admission запрещён,
  active exchanges drain-ятся до deadline, остаток отменяется. Default force
  timeout 30 секунд и Docker stop timeout 35 секунд сохраняются. Завершение
  долгого запроса при аварийном падении контейнера не гарантируется.
- Несколько реплик: запросы достигают обеих; после снятия одной с балансировки
  и остановки новые запросы обслуживает оставшаяся. Восстановленная реплика
  возвращается в rotation после readiness. При аварийном падении новые запросы
  доходят до оставшейся после исключения backend балансировщиком; ошибки
  переходного периода учитываются. Общий host и балансировщик остаются
  отдельными failure domains; численная HA-гарантия и zero-error rollout не заявлены.
- Stdout: при включённом OTLP видны отдельные валидные JSONL records application
  logs, `resourceSpans` и `resourceMetrics`. Транспорт до OpenObserve проверяет
  администратор на своём стенде.
- Внешний probe: падение реплики формирует сигнал в заданный срок; 200 после
  восстановления сбрасывает счётчик ошибок. Фиксируются время падения,
  результаты попыток и время сигнала.

## Production review

Администратор фиксирует timestamp первого production запуска и через
14 календарных дней предоставляет владельцу сервиса отчёт: период и объём
трафика, результаты запросов, причины технических ошибок, интервалы
недоступности, restart/rollout/recovery observations и полноту telemetry.
Владелец сервиса выбирает численный SLO и постоянное окно по результатам review.
При недостаточном трафике или пробелах фиксируются причина, необходимые
наблюдения и следующая дата review; срок продлевается без выдуманного SLO.
Эта процедура не создаёт календарное напоминание или автоматизацию и не
является ожиданием внутри реализации VIG-33.

## Критерии готовности

- [ ] Опубликован операторский контракт с поддержкой одного и нескольких
  Docker instances, распределением ответственности и сценариями выше.
- [ ] SLI-методика содержит классификацию, независимый пример, правила неполных
  данных и атрибуции; существующие counters/probes не объявлены достаточным
  end-to-end измерением.
- [ ] Описание stdout позволяет распознать все три signal types; получение
  application/trace/metric JSONL подтверждено на проверяемом Docker artifact.
  Версия artifact и команды наблюдения сохранены в evidence.
- [ ] Актуальные lifecycle/telemetry checks и OCI smoke подтверждают гарантии
  приложения. Администраторский checklist опубликован отдельно; его наличие
  не выдаётся за выполненный rollout, настроенный alert или доставку в OpenObserve.
- [ ] Описаны probe 30s/5s/3 failures/120s и review через 14 календарных дней
  с продлением при недостатке данных.
- [ ] Требования перенесены к постоянным owners, operator reference и coverage
  согласованы. Численный availability SLO отложен; известные runtime/evidence
  gaps не скрыты и не повышены до подтверждённых guarantees.

## Проверки

При реализации выполнять последовательно через
[durable runner](../../docs/development.md#устойчивый-запуск-проверок):

```bash
rtk proxy ./gradlew test -x processTest --tests 'io.vigilant.gateway.LoggingConfigurationTest' --tests 'io.vigilant.gateway.tracing.OtlpExportTest' --tests 'io.vigilant.gateway.metrics.OtlpMetricsExportTest' --tests 'io.vigilant.gateway.health.HealthEndpointsTest'
rtk proxy ./gradlew processTest --tests 'io.vigilant.gateway.ShutdownLifecycleTest'
rtk proxy ./scripts/oci-smoke-test
rtk proxy ./gradlew workItemValidatorTest --rerun validateWorkItems
rtk proxy ./scripts/task-context VIG-33
rtk proxy git diff --check
```

Отдельное наблюдение stdout OCI artifact с включённым OTLP должно показать все
три JSONL envelopes. Существующий smoke или in-memory SDK test сам по себе
не заменяет это наблюдение. Точные команды публикуются вместе с evidence без
credentials и реальных клиентских данных. Multi-replica, probe и OpenObserve
checks выполняет администратор по опубликованной методике, без поставки chain.

Текущая работа уточняет требования: перечисленные runtime checks ещё не
являются выполненными observations. При изменении production code во время
реализации действуют дополнительные обязательные gates из `CLAUDE.md`.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ подготовка выпуска до production measurements
  Acceptance:   0.25  ✓ app evidence и операторский checklist разделены
  Boundaries:   0.0   ✓ конфигурация внешней цепочки исключена
  Alternatives: 0.25  ✓ full-stack поставка и обязательные HA/SLO до выпуска отклонены
  Assumptions:  0.25  ✓ telemetry gaps проверены; production data ещё нет
  ──────────────────────────────
  Aggregate:    0.15  ✓ below threshold (0.3 ticket)

Push lightly on: полнота данных на первом production review.
```
