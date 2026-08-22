# PERF-01: результат полного прогона 2026-08-22

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
- после насыщения proxy path преобладали connect timeout, затем появились
  локальное исчерпание ephemeral ports и request timeout;
- к началу measurement gateway не восстановился, поэтому успешных измеряемых
  proxy-ответов не было.

Наблюдаемая причина отклонения - gateway не поддерживает длительную нагрузку
2 000 RPS и перестаёт устанавливать клиентские соединения. Конкретный
production-code root cause внутри Armeria server/client path этим load-test
issue не устанавливался; для него нужен отдельный profiling/fix work item.
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
