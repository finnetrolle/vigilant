# Epic 05: v0 hardening - закрытие незакрытых пунктов спецификации v0

**ID:** `EPIC-05`
**Тип:** Epic
**Статус:** In progress
**Приоритет:** High
**Предварительная оценка:** 20-33 инженерных дня
**Связанные требования:** `PROXY-01`, `PROXY-03`, `CONC-04`, `PERF-01`, `PERF-06`, Наблюдаемость, Поставка, критерии готовности v0 №2, №3, №4, №6

## Контекст

Архитектурное ревью v0 (2026-08-21) подтвердило корректность ядра bypass-proxy,
но выявило пункты, которые спецификация `MVP_NON_FUNCTIONAL_REQUIREMENTS.md`
объявляет частью v0, а реализация и реестр work items не содержат:

- ошибки соединения/timeout upstream доходят клиенту как сырые исключения
  Armeria, а не стабильная proxy-ошибка (`PROXY-03`, критерий готовности №4);
- нет health/readiness endpoint'ов самого gateway: все пути, включая пробы,
  проксируются upstream;
- нет E2E-подтверждения стриминга без буферизации (критерий №2) и отмены
  клиента (критерий №3, `CONC-04`);
- upstream-клиент создан без явных таймаутов и настроек pooling;
- observability ограничена логами: нет correlation/trace ID, OTel metrics и
  traces;
- нет нагрузочного теста `PERF-01` и OCI-образа поставки.

Epic закрывает эти дыры без изменения функциональной прозрачности v0: тела
по-прежнему передаются как поток байтов без агрегации и JSON-разбора.

## Карта декомпозиции

```text
EPIC-05 v0 hardening
├── Надёжность proxy
│   ├── стабильные proxy-ошибки upstream (PROXY-03)
│   └── явные таймауты upstream-клиента
├── Верифицируемость поведения
│   ├── E2E-тест стриминга без буферизации (PROXY-01)
│   └── E2E-тест отмены клиента (CONC-04)
├── Наблюдаемость
│   ├── correlation/trace ID и OTel traces
│   └── OTel metrics прокси-трафика
├── Эксплуатация
│   ├── health/readiness endpoints
│   └── OCI-образ поставки
└── Производительность
    └── нагрузочный тест PERF-01
```

## Дочерние issues

- [x] [VIG-05-01: Стабильная proxy-ошибка при сбое upstream](../issues/epic_05/issue_05_01_upstream_error_mapping.md) - `Done`
- [x] [VIG-05-02: Health и readiness endpoints](../issues/epic_05/issue_05_02_health_endpoints.md) - `Done`
- [x] [VIG-05-03: E2E-тест стриминга без буферизации](../issues/epic_05/issue_05_03_streaming_e2e.md) - `Done`
- [x] [VIG-05-04: E2E-тест отмены клиента](../issues/epic_05/issue_05_04_cancellation_e2e.md) - `Done`
- [x] [VIG-05-05: Явные таймауты upstream-клиента](../issues/epic_05/issue_05_05_upstream_timeouts.md) - `Done`
- [x] [VIG-05-06: Correlation/trace ID и OTel traces](../issues/epic_05/issue_05_06_trace_id_otlp.md) - `Done`
- [x] [VIG-05-07: OTel metrics прокси-трафика](../issues/epic_05/issue_05_07_otlp_metrics.md) - `Done`
- [ ] [VIG-05-08: Нагрузочный тест PERF-01](../issues/epic_05/issue_05_08_load_test.md) - `Ready for implementation`
- [ ] [VIG-05-09: OCI-образ поставки](../issues/epic_05/issue_05_09_oci_image.md) - `Ready for implementation`

Issues независимы: жёстких блокировок нет, frontier - все девять. Предпочтительный
порядок (не блокировка): VIG-05-01 раньше VIG-05-05 и VIG-05-07, чтобы таймауты и
метрика transport errors опирались на принятую классификацию ошибок; VIG-05-06
раньше VIG-05-07, чтобы metrics переиспользовали инфраструктуру OTel SDK.

## Цель

Gateway v0 соответствует всем пунктам раздела «Входит в v0» и критериям
готовности v0 из `MVP_NON_FUNCTIONAL_REQUIREMENTS.md`, включая нерелизованные
сейчас: стабильные proxy-ошибки, health/readiness, подтверждённые стриминг и
отмена, явные таймауты, базовые metrics/traces, нагрузочный тест `PERF-01` и
OCI-поставка.

## Предварительные требования

- Ошибки соединения, timeout и некорректный ответ upstream преобразуются в
  стабильную proxy-ошибку; корректные HTTP-ответы upstream проходят без
  интерпретации.
- Health и readiness обслуживаются самим gateway и не проксируются upstream.
- Стриминг подтверждён тестом: time-to-first-byte не ждёт завершения ответа.
- Отмена клиента закрывает или отменяет upstream-запрос.
- Таймауты upstream-клиента задаются конфигурацией с дефолтами, безопасными для
  длинных LLM-стримов.
- Каждый запрос получает correlation/trace ID; gateway экспортирует metrics и
  traces через OpenTelemetry/OTLP.
- Нагрузочный тест подтверждает `PERF-01` (или фиксирует отчёт с причиной
  отклонения) по методике `PERF-06`.
- Поставка - один versioned артефакт и один OCI-образ с graceful shutdown.

## Не входит в задачу

- Политики, детекторы, plugin workers и разбор JSON в data path.
- Изменение прозрачности bypass: тела остаются потоком байтов.
- Retry, circuit breaker и multi-upstream routing.
- Control plane, web UI, динамическая конфигурация.

## Открытые решения

Решения локальны для отдельных issues и не меняют границы декомпозиции;
каждое снабжено recommended baseline внутри соответствующей issue:

- Формат стабильной proxy-ошибки: статус, тело, заголовки - VIG-05-01.
- Пути health/readiness endpoint'ов - VIG-05-02.
- Названия и дефолты настроек таймаутов; idle-based против общего
  response timeout для стримов - VIG-05-05.
- W3C trace context propagation и дефолты OTLP exporter - VIG-05-06.
- Базовый OCI-образ и способ упаковки versioned JAR - VIG-05-09.

## Предварительные критерии приёмки

- Выполнены критерии готовности v0 №2, №3, №4 и №6 спецификации.
- Health/readiness отвечают сам gateway; пробы не попадают в upstream.
- Метрики содержат как минимум request count, active requests, upstream
  latency, gateway processing latency, response status, timeouts,
  cancellations, transport errors.
- Ни одна из новых возможностей не агрегирует тела и не пишет секреты в логи.
- Все дочерние issues - `Done`, реестр `WORK_ITEMS.md` обновлён в тех же
  change set'ах.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ цель - прямое закрытие пунктов существующей спецификации
  Acceptance:   0.15  ✓ критерии привязаны к PROXY/CONC/PERF и критериям v0
  Boundaries:   0.0   ✓ non-goals наследуют OUT_OF_SCOPE v0
  Alternatives: 0.2   ✓ recommended baselines заданы, финальный выбор в issues
  Assumptions:  0.25  ⚠ поведение отмены Armeria pass-through не проверено (VIG-05-04)
  ──────────────────────────────────────
  Aggregate:    0.12  ✓ below threshold (0.2 spec)

Frontier: все 9 issues. VIG-05-04 - единственная с непроверенным допущением,
оно изолировано внутри этой issue.
```
