# VIG-18: Inspection load baseline and production report

- **ID:** `VIG-18`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** High
- **Зависит от:** [VIG-07-02](epic_07/issue_07_02_windowed_fast_pii_execution.md), [VIG-16](issue_16_packaged_shadow_proxy_evidence.md), [VIG-17](issue_17_request_tracing_stdout_otlp.md)
- **Блокирует:** Production PII shadow proxy milestone
- **Оценка:** 0 дней осталось
- **Уверенность:** Medium

## Результат

Воспроизводимый benchmark публикует actual throughput, memory и p50/p95/p99
для parsing, windowing, policy evaluation и total inspection первого
production increment. Отчёт отделяет advisory latency targets от обязательных
safety gates и фиксирует полный профиль и среду выполнения.

## Public seams

- JMH вызывает опубликованные pure parser, transport-neutral windowing и
  policy engine seams на immutable synthetic Chat Completions payload.
- Gatling запускает packaged `MainKt` и real upstream отдельными процессами,
  отправляет `POST /v1/chat/completions` и наблюдает только HTTP outcomes,
  process lifecycle, safe JSONL audit и OS-reported process memory.
- Report generator получает immutable measurement snapshots и пишет
  self-contained Markdown с точной командой воспроизведения.

## Критерии готовности

- [x] Phase benchmark публикует p50/p95/p99 parsing, windowing, policy
  evaluation и total inspection для `1 KiB` и `64 KiB` synthetic payload.
- [x] Full load profile использует packaged gateway, `2 000 RPS`, request до
  `64 KiB`, отдельные JVM warm-up и measurement phases.
- [x] Load report публикует planned/successful requests, actual throughput,
  p50/p95/p99 HTTP latency, gateway RSS samples/peak и heap limit.
- [x] Full run не содержит OOM, unbounded memory trend, silent bypass,
  truncation или unexpected HTTP outcome; expected PII request replay остаётся
  byte-identical и safe audit не содержит payload или matched text.
- [x] Ориентиры `2 000 RPS` и total inspection p99 `50 ms` отмечены как
  advisory; любое отклонение публикуется без сокрытия.
- [x] Fast contract tests проверяют schedule, exact request generation,
  percentile/report rendering и невозможность synthetic PASS без полного
  sample.
- [x] `./gradlew build`, `./gradlew inspectionPhaseBenchmark`,
  `./gradlew inspectionLoadTest` и `./gradlew validateWorkItems` проходят.
- [x] Итоговый versioned report сохранён в `docs/` и Stage 4 roadmap frontier
  обновлён по фактическому результату.

## Не входит

Оптимизация latency без подтверждённой проблемы, изменение detector semantics,
parallel window execution, enforcement reactions, response inspection,
production payload и прямой OTLP network export.

## Результат

[Versioned report](../../docs/inspection-load-result.md) фиксирует `PASS`:
`240 000/240 000` measured requests, `2 000 RPS`, HTTP p50/p95/p99
`2/3/4 ms`, total inspection `64 KiB` p99 `1.670 ms`, bounded RSS, exact replay
и `240 000` safe `DETECTED` audit events. Предварительный run выявил direct
buffer leak; packaged-process RED воспроизвёл его, после исправления тот же
test и полный профиль прошли.
