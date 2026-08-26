# VIG-09-02: Подтверждение PERF-01 по p99

**Статус:** Done
**Epic:** [EPIC-09](../../epics/epic_09_v0_architecture_closure.md)
**Ветка:** Performance and capacity > PERF-01 p99 confirmation
**Зависит от:** [VIG-09-01](issue_09_01_memory_stability.md)
**Блокирует:** [VIG-01A](../issue_01A_benchmark.md)
**Оценка:** 3-5 инженерных дней
**Уверенность:** Medium

## Результат

Полный PERF-01 run на устойчивом gateway содержит успешные direct и proxy
samples и подтверждает `proxy_overhead p99 <= 2 ms`. Если первый стабильный
run превышает SLO, минимальная профиль-guided оптимизация доводит data path до
требования без ослабления correctness или resource bounds.

## Требования

`PERF-01`, `PERF-06`; finding AR-02 архитектурного ревью.

## Критерии готовности

- [x] Run выполняет неизменённый full profile: 2 000 RPS, оба warm-up этапа,
  120 s measurement, 80/20 non-streaming/streaming и общий upstream baseline.
- [x] Direct и proxy paths завершают не менее 99% planned workload и не имеют
  Gatling request failures, требующих исключения samples.
- [x] Измеренный `proxy_overhead p99` не превышает 2 ms.
- [x] Если потребовалась optimization, профиль до изменения показывает её
  вклад, а targeted test/benchmark устанавливает RED до production fix.
- [x] Full report фиксирует hardware, JVM, connections, payload sizes,
  streaming profile, Git revision и memory settings по `PERF-06`.
- [x] `docs/perf-01-result.md` обновлён новым подтверждённым result без удаления
  истории предыдущего deviation.
- [x] `./gradlew build` проходит.

## Test/demo seam

`./gradlew perfTest` и generated Gatling/summary artifacts. Изменение
production code выполняется только при доказанном профилем bottleneck.

## Не входит

Logging slow-sink/JFR assertions VIG-01A, изменение SLO, исключение streaming
population или неуспешных requests из формального результата.
