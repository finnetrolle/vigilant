# VIG-09-01: Стабильность памяти gateway при 2 000 RPS

**Статус:** Done
**Epic:** [EPIC-09](../../epics/epic_09_v0_architecture_closure.md)
**Ветка:** Performance and capacity > stable memory and throughput
**Зависит от:** нет
**Блокирует:** [VIG-09-02](issue_09_02_perf01_latency.md)
**Оценка:** 4-5 инженерных дней
**Уверенность:** Medium

## Результат

Gateway выдерживает полный proxy warm-up и measurement PERF-01 при 2 000 RPS
без `OutOfMemoryError`, connection collapse и потери измеряемого workload.
Профиль и memory evidence объясняют root cause исходного отказа и доказывают,
что исправление устраняет его, а не только отодвигает момент падения.

## Требования

`PERF-01`, `PERF-06`; findings AR-01 и AR-09 архитектурного ревью.

## Критерии готовности

- [x] Исходный отказ воспроизведён полным PERF-01 профилем либо сохранённый
  run подтверждён повторяемым более коротким diagnostic profile с тем же root
  cause; RED evidence содержит `OutOfMemoryError` и не содержит payload/secrets.
- [x] Heap/native-memory/JFR или эквивалентный профиль локализует retained или
  unbounded resource class до изменения production code.
- [x] Исправление ограничено найденной причиной и сохраняет streaming,
  cancellation, timeout и header semantics.
- [x] При baseline `-Xms512m -Xmx512m` proxy warm-up и measurement завершаются
  без OOM, accept/connect collapse и request failures.
- [x] Memory после warm-up остаётся bounded по зафиксированной методике; один
  только увеличенный heap без объяснения root cause не считается решением.
- [x] `docs/perf-01-result.md` дополнен фактической OOM-причиной исходного run и
  ссылкой на новый evidence/result.
- [x] Focused regression tests и `./gradlew build` проходят.

## Test/demo seam

Существующий packaged-gateway Gatling flow `./gradlew perfTest` плюс безопасный
JFR/heap/native-memory профиль gateway child process. Production fix выполняется
по обязательному TDD процессу проекта после зафиксированного RED.

## Не входит

Оптимизация p99 до 2 ms (VIG-09-02), изменение workload mix, повышение memory
budget как единственный fix, logging slow-sink scenarios VIG-01A.

## Рекомендуемое решение внутри issue

Сохранить 512 MiB heap как текущий воспроизводимый baseline. Если профиль
докажет native/off-heap exhaustion, фиксировать heap и native memory раздельно,
не переименовывать native pressure в heap leak.
