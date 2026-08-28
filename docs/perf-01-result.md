# PERF-01: история результатов

## Полный logging-прогон 2026-08-28

### Вердикт

**PASS: PERF-01 и неблокирующий logging path подтверждены.**

Три последовательных сценария завершили все 1 260 000 запросов без ошибок.
Каждое измерительное окно обработало 240 000 запросов при 2 000 RPS. Default
gateway не добавил задержку к combined p99, а request latency gateway с
медленным sink осталась на порядок ниже задержки одного sink write.

### Измеренные числа

| Path/profile | Успешно | Запланировано | Успешных RPS | Success | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| direct / non-streaming | 192 000 | 192 000 | 1 600.0 | 100.00% | 1 ms | 1 ms | 1 ms | 38 ms |
| direct / streaming | 48 000 | 48 000 | 400.0 | 100.00% | 3 ms | 4 ms | 5 ms | 41 ms |
| direct / combined | 240 000 | 240 000 | 2 000.0 | 100.00% | 1 ms | 4 ms | 4 ms | 41 ms |
| proxy / non-streaming | 192 000 | 192 000 | 1 600.0 | 100.00% | 1 ms | 1 ms | 1 ms | 23 ms |
| proxy / streaming | 48 000 | 48 000 | 400.0 | 100.00% | 4 ms | 4 ms | 4 ms | 25 ms |
| proxy / combined | 240 000 | 240 000 | 2 000.0 | 100.00% | 1 ms | 4 ms | 4 ms | 25 ms |
| slow sink / non-streaming | 192 000 | 192 000 | 1 600.0 | 100.00% | 1 ms | 1 ms | 2 ms | 24 ms |
| slow sink / streaming | 48 000 | 48 000 | 400.0 | 100.00% | 4 ms | 4 ms | 5 ms | 21 ms |
| slow sink / combined | 240 000 | 240 000 | 2 000.0 | 100.00% | 1 ms | 4 ms | 4 ms | 24 ms |

`proxy_overhead p99 = 4 ms - 4 ms = 0 ms`, поэтому требование
`proxy_overhead p99 <= 2 ms` выполнено.

Slow-sink request p99 составил 4 ms при фиксированной задержке каждого
downstream write 50 ms. Default gateway доставил 240 000 из 240 000 измеряемых
audit-событий. Slow-sink gateway доставил 0 из 240 000, то есть bounded queue
отбрасывала события под перегрузкой, не передавая backpressure request path.

### Профиль event loop

Оба packaged gateway записывались JFR с bounded `maxsize=256m`. Анализ каждого
маршрута ограничен фактически наблюдаемым half-open окном от самого раннего
старта измеряемого запроса до самого позднего завершения. Поэтому он включает
хвост последнего ответа, но не startup и warm-up I/O.

| Gateway | Событий проверено | Event-loop events | Нарушений |
|---|---:|---:|---:|
| default INFO stdout | 614 747 | 9 818 | 0 |
| slow sink 50 ms | 597 912 | 9 264 | 0 |

На потоках `armeria-common-worker-*` не обнаружены `PrintStream.write`, file
I/O, socket/OTLP export или ожидание logging queue. CPU-работа producer thread
по проверке уровня и созданию события остаётся частью измеренного overhead.

### Профиль и стенд

- Run UTC: `2026-08-28T14:13:54Z` - `2026-08-28T14:26:24Z`.
- Git revision: `fa25da7adc62640098a2012239605f712163b6dc`, worktree dirty.
- Target: 2 000 RPS отдельно для direct, default gateway и slow-sink gateway.
- Ramp warm-up: 60 s; steady-state warm-up: 60 s; measurement: 120 s.
- Gap: 5 s; distribution: 80% non-streaming / 20% streaming.
- Gatling connection pool: shared, максимум 64 соединения на host.
- Request body: валидный Chat Completions JSON, 1 024 bytes.
- Non-streaming response: 4 096 bytes; streaming response: 4 chunks по 1 024
  bytes, интервал 1 ms.
- Upstream и оба gateway: отдельные JVM-процессы, loopback network.
- Gateway/upstream heap: `-Xms512m -Xmx512m`; load-generator heap: 2 GiB.
- Slow sink: production-identical bounded async queue, console delay 50 ms.
- OS: macOS 26.3.1, arm64; CPU: Apple M3 Max, 14 logical processors.
- Physical memory: 36.0 GiB; Gatling: 3.15.1; load generator: JDK 21;
  gateway и upstream: JDK 25.

Команда: `./gradlew perfTest`. Generated summary сохранён как
`build/reports/perf-01/perf-01-20260828-141354.md`, Gatling HTML report как
`build/reports/gatling/perfloadsimulation-20260828141354407/index.html`.
JFR-файлы созданы как `build/perf-processes/gateway.jfr` и
`slow-sink-gateway.jfr`. Каталог `build/` не хранится в Git, поэтому
воспроизводимые параметры и значимые evidence counts зафиксированы здесь.

## Полный прогон 2026-08-26

### Вердикт

**PASS: gateway стабилен, PERF-01 подтверждён.**

Неизменённый full profile завершил все 840 000 запросов без ошибок. Direct и
proxy measurement обработали по 240 000 запросов при 2 000 RPS. Gateway с
`-Xms512m -Xmx512m` не получил `OutOfMemoryError`, не потерял соединения и
сохранил bounded live heap после warm-up.

### Измеренные числа

| Path/profile | Успешно | Запланировано | Успешных RPS | Success | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| direct / non-streaming | 192 000 | 192 000 | 1 600.0 | 100.00% | 1 ms | 1 ms | 1 ms | 45 ms |
| direct / streaming | 48 000 | 48 000 | 400.0 | 100.00% | 3 ms | 4 ms | 4 ms | 47 ms |
| direct / combined | 240 000 | 240 000 | 2 000.0 | 100.00% | 1 ms | 4 ms | 4 ms | 47 ms |
| proxy / non-streaming | 192 000 | 192 000 | 1 600.0 | 100.00% | 1 ms | 1 ms | 1 ms | 41 ms |
| proxy / streaming | 48 000 | 48 000 | 400.0 | 100.00% | 4 ms | 4 ms | 4 ms | 40 ms |
| proxy / combined | 240 000 | 240 000 | 2 000.0 | 100.00% | 1 ms | 4 ms | 4 ms | 41 ms |

`proxy_overhead p99 = 4 ms - 4 ms = 0 ms`, поэтому требование
`proxy_overhead p99 <= 2 ms` выполнено на этом стенде.

### Root cause исходного OOM

`responseIdleTimeoutDecorator` сбрасывал upstream response timeout на каждом
`ResponseHeaders` и `HttpData`. Armeria закрывала внутренний
`HttpResponseWrapper` и отменяла его timeout до того, как последний data object
проходил через внешний `peekData`. Последний callback снова планировал timeout
на 300 s, но последующего completion cleanup уже не было. До срабатывания этой
задачи каждый успешный exchange удерживал scheduled task, client/server request
contexts и оба request logs.

Исходный диагностический RED при 2 000 RPS и heap 512 MiB повторил OOM. Перед
collapse forced-GC histogram показывал live heap `230 625 888` bytes и линейное
удержание завершённых exchanges:

| Класс | Live instances до fix |
|---|---:|
| `DefaultClientRequestContext` | 23 672 |
| `DefaultServiceRequestContext` | 23 672 |
| `DefaultRequestLog` | 47 344 |
| `ScheduledFutureTask` | 23 745 |

Причинность дополнительно проверена A/B diagnostic profile: только уменьшение
response timeout с 300 s до 1 s позволило тому же gateway обработать 100 000
proxy requests без ошибок. После GC осталось примерно одна секунда трафика:
1 932 client contexts, 1 932 server contexts и 3 864 request logs. Это был
диагностический эксперимент, а не product fix.

Исправление сохраняет timeout первого объекта и idle gap между объектами, но
явно очищает deadline после полного consumption response, то есть после
последнего `peekData`. Focused production-process regression с heap 64 MiB и
timeout 30 s до fix завершался `OutOfMemoryError` на batch 54, а после fix
обработал все 12 800 запросов и сохранил readiness.

### Bounded-memory evidence после fix

Во время полного run снимались forced-GC class histograms без payload и
headers. Два snapshots разделены полным proxy warm-up и десятками тысяч
measurement requests:

| Точка | Live heap | Client ctx | Server ctx | Request logs | Scheduled tasks |
|---|---:|---:|---:|---:|---:|
| proxy warm-up | 20 736 432 bytes | 3 | 3 | 6 | 84 |
| после full proxy warm-up | 20 786 656 bytes | 1 | 1 | 2 | 96 |

Live set не растёт вместе с числом завершённых requests. Forced-GC safepoints
могли повлиять на единичный `max`, но formal p99 остался 4 ms для обоих путей.

### Профиль и стенд

- Run UTC: `2026-08-26T07:04:22Z` - `2026-08-26T07:12:27Z`.
- Git revision: `cafce8b1884f35dccc17346b005e42d2046779c3`, worktree dirty.
- Target: 2 000 RPS отдельно для direct и proxy.
- Ramp warm-up: 60 s; steady-state warm-up: 60 s; measurement: 120 s.
- Gap: 5 s; distribution: 80% non-streaming / 20% streaming.
- Gatling connection pool: shared, максимум 64 соединения на host.
- Request body: 1 024 bytes; non-streaming response: 4 096 bytes.
- Streaming response: 4 chunks по 1 024 bytes, интервал 1 ms.
- Upstream и gateway: отдельные JVM-процессы, loopback network.
- Gateway/upstream heap: `-Xms512m -Xmx512m`; load-generator heap: 2 GiB.
- OS: macOS 26.3.1, arm64; CPU: Apple M3 Max, 14 logical processors.
- Physical memory: 36.0 GiB; Gatling: 3.15.1; load generator: JDK 21;
  gateway и upstream: JDK 25.

Команда: `./gradlew perfTest`. Generated summary сохранён как
`build/reports/perf-01/perf-01-20260826-070422.md`, Gatling HTML report как
`build/reports/gatling/perfloadsimulation-20260826070422760/index.html`.
`build/` не хранится в Git, поэтому воспроизводимые параметры, root cause и
значимые evidence counts зафиксированы здесь.

## Полный прогон 2026-08-22

## Вердикт

**DEVIATION: PERF-01 не подтверждён.**

Direct baseline выдержал 2 000 RPS без ошибок после отдельного ramp и
steady-state прогрева. Gateway начал терять соединения во время собственного
steady-state прогрева и не восстановился к измеряемой фазе: все 240 000
proxy-запросов завершились ошибками. Формальный `proxy_overhead p99` при
отсутствии успешных proxy-ответов не вычисляется.

## Измеренные числа

| Path/profile | Успешно | Запланировано | Успешных RPS | Success | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| direct / non-streaming | 192 000 | 192 000 | 1 600.0 | 100.00% | 1 ms | 1 ms | 1 ms | 14 ms |
| direct / streaming | 48 000 | 48 000 | 400.0 | 100.00% | 3 ms | 4 ms | 4 ms | 35 ms |
| direct / combined | 240 000 | 240 000 | 2 000.0 | 100.00% | 1 ms | 4 ms | 4 ms | 35 ms |
| proxy / non-streaming | 0 | 192 000 | 0.0 | 0.00% | n/a | n/a | n/a | n/a |
| proxy / streaming | 0 | 48 000 | 0.0 | 0.00% | n/a | n/a | n/a | n/a |
| proxy / combined | 0 | 240 000 | 0.0 | 0.00% | n/a | n/a | n/a | n/a |

Gatling запланировал и завершил все 840 000 запросов полного сценария,
включая ramp, steady-state и измеряемые фазы обоих путей. Итог: 479 828 OK и
360 172 KO. В direct path ошибок не было. Proxy warm-up завершил 59 828 из
180 000 запросов успешно; все 240 000 запросов измеряемой proxy-фазы получили
ошибку.

| Ошибка Gatling | Количество |
|---|---:|
| `java.net.ConnectException: Operation timed out` | 348 608 |
| `java.net.BindException: Can't assign requested address` | 9 689 |
| `ConnectTimeoutException: connection timed out after 10000 ms` | 1 248 |
| `Request timeout ... after 30000 ms` | 627 |

## Причина отклонения

Отклонение локализовано в gateway path, а не в upstream или load generator:

- direct measurement выполнил 240 000 запросов без ошибок;
- proxy начал терять соединения во время steady-state прогрева;
- gateway process log зафиксировал повторяющийся `OutOfMemoryError` в Armeria
  worker и boss threads во время collapse;
- после насыщения proxy path преобладали connect timeout, затем появились
  локальное исчерпание ephemeral ports и request timeout;
- к началу measurement gateway не восстановился, поэтому успешных измеряемых
  proxy-ответов не было.

Наблюдаемая непосредственная причина отклонения - `OutOfMemoryError` в gateway
JVM под длительной нагрузкой 2 000 RPS, после которого gateway перестаёт
устанавливать клиентские соединения. Конкретная retained/allocation причина
внутри Armeria server/client path этим load-test issue не устанавливалась; для
неё нужен отдельный profiling/fix work item.
Исправление gateway не входит в VIG-05-08, которая явно допускает отчёт об
отклонении с причиной и baseline.

## Профиль

- Target: 2 000 RPS отдельно для direct и proxy.
- Ramp warm-up: 60 s перед steady-state фазой каждого пути.
- Steady-state warm-up: 60 s при 2 000 RPS перед measurement каждого пути.
- Measurement: 120 s на direct и 120 s на proxy.
- Gap: 5 s между direct measurement и proxy ramp.
- Distribution: 80% non-streaming / 20% streaming.
- Gatling connection pool: shared, максимум 64 соединения на host.
- Request body: 1 024 bytes.
- Non-streaming response: 4 096 bytes.
- Streaming response: 4 chunks по 1 024 bytes, интервал 1 ms.
- Upstream и gateway: отдельные локальные JVM-процессы, loopback network.
- Gateway/upstream heap: `-Xms512m -Xmx512m`.
- Load-generator heap: `-Xms2g -Xmx2g`.

## Стенд

- Run UTC: `2026-08-22T19:42:16Z` - `2026-08-22T19:50:30Z`.
- Git revision: `f04db7c89cabf210e673c91535583b84fb438092`, worktree dirty.
- OS: macOS 26.3.1, arm64.
- CPU: Apple M3 Max, 14 logical processors.
- Physical memory: 36.0 GiB.
- Load generator: OpenJDK 21.0.8 Microsoft.
- Gateway и upstream: OpenJDK 25.
- Gatling: 3.15.1, Gradle plugin 3.15.1.2.

Команда полного ручного прогона:

```bash
./gradlew perfTest
```

Gatling assertions закономерно завершили команду ненулевым кодом: 192 000
non-streaming и 48 000 streaming proxy failures. HTML-отчёт создан в
`build/reports/gatling/perfloadsimulation-20260822194216620/index.html`.
Каталог `build/` не хранится в Git; воспроизводимые параметры и все значимые
числа отчёта зафиксированы в этом документе.
