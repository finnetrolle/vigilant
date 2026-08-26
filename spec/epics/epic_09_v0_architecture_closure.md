# Epic 09: Закрытие архитектурных рисков v0

**ID:** `EPIC-09`
**Тип:** Epic
**Статус:** In progress
**Приоритет:** Critical
**Суммарная оценка:** 19-29 инженерных дней
**Связанные требования:** `PERF-01`, `PERF-06`, `PROXY-01`, `PROXY-02`, `PROXY-03`, `CONC-01`, разделы «Входит в v0» и «Поставка»

## Контекст

Источник epic - [архитектурное ревью v0 от 2026-08-23](../../docs/architecture-review-2026-08-23.md).
Ревью подтвердило здравые component boundaries, но обнаружило один доказанный
runtime отказ и несколько verification gaps:

- полный PERF-01 run завершился `OutOfMemoryError`, после чего все измеряемые
  proxy requests упали;
- request-side streaming и обе стороны backpressure не доказаны E2E;
- connection reuse, malformed upstream response, dynamic response
  `Connection` stripping и active-request shutdown lifecycle не проверены.

Epic закрывает только фундамент v0. Он не добавляет parser, policies,
detectors, spooling или другие возможности будущего guardrail-enabled stage.

## Карта декомпозиции

```text
EPIC-09 v0 architecture closure
├── Performance and capacity
│   ├── stable memory and throughput at 2,000 RPS
│   └── PERF-01 p99 confirmation
├── Streaming verifiability
│   ├── request streaming and backpressure E2E
│   └── response backpressure E2E
├── Proxy reliability
│   ├── connection pooling/reuse E2E
│   ├── malformed upstream stable error E2E
│   └── dynamic response hop-by-hop stripping E2E
└── Operations
    └── bounded active-request shutdown and resource lifecycle
```

## Дочерние issues

- [x] [VIG-09-01: Стабильность памяти gateway при 2 000 RPS](../issues/epic_09/issue_09_01_memory_stability.md) - `Done`
- [x] [VIG-09-02: Подтверждение PERF-01 по p99](../issues/epic_09/issue_09_02_perf01_latency.md) - `Done`
- [x] [VIG-09-03: E2E request streaming и backpressure](../issues/epic_09/issue_09_03_request_backpressure.md) - `Done`
- [x] [VIG-09-04: E2E response backpressure](../issues/epic_09/issue_09_04_response_backpressure.md) - `Done`
- [x] [VIG-09-05: E2E connection pooling](../issues/epic_09/issue_09_05_connection_pooling.md) - `Done`
- [x] [VIG-09-06: Некорректный upstream HTTP как stable proxy error](../issues/epic_09/issue_09_06_malformed_upstream.md) - `Done`
- [ ] [VIG-09-07: Dynamic hop-by-hop response headers](../issues/epic_09/issue_09_07_response_connection_headers.md) - `Ready for implementation`
- [ ] [VIG-09-08: Полный graceful shutdown lifecycle](../issues/epic_09/issue_09_08_shutdown_lifecycle.md) - `Ready for implementation`

Жёсткая зависимость внутри epic одна: VIG-09-02 зависит от VIG-09-01, потому
что p99 невозможно подтвердить без успешного proxy sample. VIG-09-02 также
разблокирует standalone VIG-01A, которому нужен стабильный default baseline до
logging-specific overload/profile scenarios. Остальные шесть issues доступны
независимо.

Предпочтительный порядок (не блокировка): выполнить VIG-09-03 и VIG-09-04 до
интеграции EPIC-08, потому что spool обязан сохранить обе стороны
backpressure; выполнить VIG-09-08 до изменений production lifecycle следующих
этапов.

## Цель

Vigilant v0 выдерживает полный PERF-01 workload без resource collapse,
подтверждает требуемый p99 overhead и имеет E2E evidence для streaming,
backpressure, pooling, proxy protocol error paths и bounded shutdown.

## Требования

- Одна gateway replica выдерживает 2 000 RPS без `OutOfMemoryError`, connection
  collapse и незавершённых измеряемых requests.
- После устойчивого throughput `proxy_overhead p99` не превышает 2 ms по
  `PERF-01`/`PERF-06`.
- Request и response body остаются streaming publishers без полной агрегации;
  backpressure распространяется в обоих направлениях.
- Upstream connection pool действительно переиспользует connections и
  соблюдает configured idle policy.
- Malformed upstream HTTP до начала client response даёт safe stable proxy
  error и наблюдаемую transport failure.
- Headers, перечисленные в response `Connection`, не доходят до client.
- Shutdown прекращает новый traffic, даёт активному request ограниченный drain
  window, принудительно завершает зависший exchange в bound и освобождает owned
  client/OTel resources в определённом порядке.

## Не входит

- Изменение функциональной прозрачности bypass mode.
- JSON parsing, policies, detectors, masking, blocking по содержимому.
- Retry, circuit breaker, multi-upstream routing.
- Logging overload/profile scenarios VIG-01A, кроме его dependency на
  стабильный PERF-01 baseline.
- Изменение PERF-01 workload или memory budget только ради получения PASS без
  отдельного обоснованного нового baseline.

## Открытые решения

Решения локальны и не меняют границы issues:

- VIG-09-01 рекомендует сохранить `-Xms512m -Xmx512m` как baseline
  воспроизведения и дополнить его heap/native/JFR evidence.
- Test-only VIG-09-03..07 сначала проверяют заявленное поведение. Если тест
  выявляет production defect, fix выделяется в отдельную issue и не расширяет
  test-only scope.
- VIG-09-08 рекомендует закрывать dedicated upstream `ClientFactory` после
  server drain, а OTel providers после завершения request callbacks.

## Критерии готовности

- VIG-09-01..08 имеют status `Done`, а checklist и реестр обновлены в тех же
  change sets.
- Полный PERF-01 report содержит успешные direct/proxy samples, memory evidence
  и подтверждённый `proxy_overhead p99 <= 2 ms`.
- Все новые protocol/streaming/shutdown tests проходят через реальные Armeria
  servers или production child process согласно issue seam.
- Ни один fix не агрегирует body, не пишет payload/secrets в logs и не вводит
  будущий guardrail scope.
- `./gradlew build` и project work-item validator проходят.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   цель привязана к доказанным review findings
  Acceptance:   0.1   public seams и evidence заданы
  Boundaries:   0.0   только v0 foundation
  Alternatives: 0.15  profiling strategy локальна для VIG-09-01
  Assumptions:  0.15  test-only issues могут открыть отдельный defect
  Aggregate:    0.08  below threshold (0.2 spec)
```
