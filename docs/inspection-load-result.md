# Inspection-load baseline: Production PII shadow proxy

**Дата прогона:** 2026-08-27

**Вердикт:** `PASS`

**Work item:** [VIG-18](../spec/issues/issue_18_inspection_load_report.md)

## Итог

Packaged `MainKt` выдержал обязательный профиль с точным request body `64 KiB`
при `2 000 RPS`. Все `240 000` запросов измеряемой фазы завершились успешно,
upstream подтвердил byte-identical replay каждого body, а safe audit содержал
ровно `240 000` соответствующих решений `DETECTED`.

Ориентиры `2 000 RPS` и total inspection p99 `50 ms` являются advisory, а не
release blockers. Оба ориентира достигнуты. Обязательные safety gates также
пройдены: OOM отсутствует, RSS не показывает восходящего тренда, silent bypass
и truncation не обнаружены, synthetic PII не попал в логи или отчёт.

## Phase benchmark

| Phase | Payload | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| parsing | 1 KiB | 0.625 us | 0.792 us | 0.958 us |
| parsing | 64 KiB | 27.552 us | 33.984 us | 49.600 us |
| windowing | 1 KiB | 22.240 us | 28.192 us | 39.488 us |
| windowing | 64 KiB | 1062.912 us | 1208.320 us | 1314.816 us |
| policy evaluation | 1 KiB | 51.968 us | 65.792 us | 85.888 us |
| policy evaluation | 64 KiB | 1142.784 us | 1341.440 us | 1566.556 us |
| total inspection | 1 KiB | 52.864 us | 68.608 us | 87.552 us |
| total inspection | 64 KiB | 1171.456 us | 1372.160 us | 1670.369 us |

JMH использовал `SampleTime`, один benchmark thread, три warm-up iteration по
одной секунде, пять measurement iteration по одной секунде и два fork с heap
`1 GiB`. Payload был точным synthetic Chat Completions request размером `1 KiB`
или `64 KiB` с одним PII-bearing fragment. Raw JMH JSON создаётся в
`build/reports/inspection/phase/results.json` и не содержит payload или matched
text.

Total inspection p99 для `64 KiB` равен `1.670 ms`, что ниже advisory
ориентира `50 ms` примерно в 30 раз. Это локальный single-thread phase
benchmark, а не обещание end-to-end latency для любого production hardware.

## Packaged load profile

| Measure | Observed | Expected |
|---|---:|---:|
| successful measured requests | 240000 | 240000 |
| successful measured throughput | 2000.0 RPS | 2000 RPS |
| failed measured requests | 0 | 0 |
| matched measured audit events | 240000 | 240000 |
| measured `DETECTED` decisions | 240000 | 240000 |

| Latency population | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|
| measured HTTP latency | 2 ms | 3 ms | 4 ms | 49 ms |

Профиль запускал packaged gateway и real Armeria upstream отдельными JVM.
Нагрузка линейно разгонялась до `2 000 RPS` за 60 секунд, удерживалась ещё 60
секунд для warm-up, затем измерялась 120 секунд при постоянных `2 000 RPS`.
Каждый request имел точный размер `65 536` bytes, response имел размер `1 024`
bytes, а общий client pool был ограничен 128 connections per host.
Gateway был настроен на 512 одновременно admitted request sources; общий
retained-byte quota оставался production default `64 MiB`.

HTTP percentiles рассчитаны deterministic nearest-rank только по успешным
запросам measurement phase. Gatling также выполнил все `180 000` warm-up
requests без ошибок, поэтому полный run содержит `420 000` успешных requests.

## Memory и safety

| Measure | Observed | Gate |
|---|---:|---:|
| gateway heap limit | 512 MiB | fixed profile |
| RSS samples | 121 | один sample в секунду |
| first-window RSS median | 542.4 MiB | baseline |
| last-window RSS median | 541.1 MiB | не выше baseline + 64 MiB |
| peak RSS | 545.9 MiB | published observation |
| `OutOfMemoryError` | false | false |
| synthetic sensitive value in logs | false | false |
| byte-identical replay | every success | every success |

RSS включает heap, direct/native memory, JVM и mapped regions, поэтому он может
быть выше heap limit. Последнее окно ниже первого на `1.3 MiB`, то есть
признаков unbounded memory trend в измеряемом интервале нет. Значения являются
baseline этого профиля и hardware, а не универсальным capacity promise.

## Найденный и устранённый дефект

Предварительный полный прогон обнаружил resource leak: gateway успешно
обработал `7 984` request, затем Netty исчерпал `512 MiB` direct memory и начал
закрывать соединения. Request adapter использовал `ByteBufAccessMode.FOR_IO`,
который создаёт retained direct buffer, хотя source синхронно копирует bytes и
не требует владения transport buffer.

Регрессионный packaged-process test воспроизвёл падение при
`-XX:MaxDirectMemorySize=64m` на серии из `1 100` requests размером `64 KiB`.
Adapter переведён на non-retaining `ByteBufAccessMode.DUPLICATE`; тот же test
после исправления прошёл. Release run выше выполнен только после этого fix.

Повторный run со строгим exact-volume gate выявил второй bounded deviation:
при production default 128 admitted request sources один краткий platform
stall дал 26 ответов `503 inspection_capacity_exhausted`. Это ожидаемый safe
overload outcome, но он не соответствует HTTP outcome этого load profile и
поэтому корректно завершил run с `FAIL`.

Фиксированный load profile использует configurable limit 512 concurrent
sources при неизменном global retained-byte quota `64 MiB`. Для request
`64 KiB` это ограничивает одновременно retained payload максимумом `32 MiB` и
оставляет запас на короткие platform stalls. После этой явно опубликованной
настройки строгий run прошёл без единого `503`; product default 128 этим
benchmark task не изменён.

## Среда

- Hardware: Apple M3 Max, 14 logical CPUs, 36 GiB physical memory.
- OS: macOS Darwin 25.3.0, arm64.
- JDK: OpenJDK 25.0.2, Homebrew build.
- Armeria: 1.41.0.
- Gatling: 3.15.1, Gradle plugin 3.15.1.2.
- JMH: 1.37, Gradle plugin 0.7.3.
- Gateway heap: `-Xms512m -Xmx512m`; load-generator heap: 2 GiB.
- Gateway request-source concurrency: 512; retained-byte quota: 64 MiB.

## Воспроизведение

~~~bash
./gradlew inspectionPhaseBenchmark
./gradlew inspectionLoadTest
~~~

Первый task создаёт raw JMH JSON и summary в
`build/reports/inspection/phase/`. Второй создаёт Gatling HTML, process logs и
safe measurement summary в `build/reports/inspection/load/`. Оба task являются
явными длительными проверками и не входят в обычный `build` или CI.
