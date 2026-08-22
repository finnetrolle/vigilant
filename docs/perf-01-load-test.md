# PERF-01: методика нагрузочного теста

## Цель

Тест измеряет накладные расходы одной реплики Vigilant при установившейся
нагрузке 2 000 RPS:

`proxy_overhead p99 = proxy p99 - direct p99`.

PERF-01 подтверждён, если оба измеряемых пути выдержали целевую частоту без
ошибок и `proxy_overhead p99 <= 2 ms`.

## Запуск

Требуются JDK 25, свободные локальные порты `18080` и `18081`, минимум 2 GiB
heap для load generator и спокойный стенд без конкурирующей нагрузки. Перед
эталонным прогоном следует подключить питание, отключить энергосберегающий
режим и дождаться стабильной температуры машины.

Из корня репозитория:

```bash
./gradlew perfTest
```

Команда явно запускает только нагрузочный контур. `./gradlew build` и
`./gradlew check` не зависят от Gatling-задач.

Для быстрой проверки работоспособности сценария без оценки SLO:

```bash
./gradlew perfTest \
  -Dperf.rps=20 \
  -Dperf.warmupSeconds=2 \
  -Dperf.steadyWarmupSeconds=2 \
  -Dperf.measurementSeconds=3 \
  -Dperf.phaseGapSeconds=1 \
  -Dperf.connectionsPerHost=8
```

Сокращённый профиль получает вердикт `SMOKE ONLY` и не может подтверждать
PERF-01.

## Фиксированный профиль

| Параметр | Значение по умолчанию | System property |
|---|---:|---|
| Целевая частота каждого измеряемого пути | 2 000 RPS | `perf.rps` |
| Линейный разгон каждого пути | 60 s | `perf.warmupSeconds` |
| Steady-state прогрев каждого пути при целевом RPS | 60 s | `perf.steadyWarmupSeconds` |
| Измерение каждого пути | 120 s | `perf.measurementSeconds` |
| Пауза между direct и proxy | 5 s | `perf.phaseGapSeconds` |
| Non-streaming доля | 80% | `perf.nonStreamingPercent` |
| Streaming доля | 20% | `100 - perf.nonStreamingPercent` |
| Максимум соединений Gatling на host | 64 | `perf.connectionsPerHost` |
| Тело запроса | 1 024 bytes | `perf.requestBytes` |
| Non-streaming ответ | 4 096 bytes | `perf.nonStreamingResponseBytes` |
| Streaming ответ | 4 x 1 024 bytes | `perf.streamingChunks`, `perf.streamingChunkBytes` |
| Интервал между streaming chunks | 1 ms | `perf.streamingChunkDelayMs` |
| Upstream port | 18081 | `perf.upstreamPort` |
| Gateway port | 18080 | `perf.gatewayPort` |

Нагрузка является open workload: каждый virtual user выполняет ровно один
HTTP-запрос. Gatling использует общий server-to-server connection pool и
ограничивает его указанным числом соединений на host.

## Порядок одного прогона

1. `installDist` собирает тот же runtime-дистрибутив, который запускается
   оператором.
2. Сценарий запускает локальный Armeria upstream и packaged Vigilant в
   отдельных JVM-процессах с heap `512 MiB` каждый.
3. Direct workload линейно разгоняется до 2 000 RPS за 60 секунд.
4. Direct workload удерживается 60 секунд при 2 000 RPS, чтобы JVM достигла
   устойчивого состояния до начала измерения.
5. Direct workload измеряется 120 секунд при постоянных 2 000 RPS.
6. После пятисекундной паузы proxy workload отдельно разогревается до
   2 000 RPS за 60 секунд.
7. Proxy workload удерживается 60 секунд при 2 000 RPS до начала измерения.
8. Proxy workload измеряется 120 секунд при постоянных 2 000 RPS.
9. Gatling проверяет нулевое число ошибок во всех четырёх измеряемых
   populations, а сценарий рассчитывает p50/p95/p99 и разницу p99.

Direct и proxy фазы последовательны: каждый путь получает полные 2 000 RPS и
не конкурирует со вторым измеряемым путём. Обе фазы обращаются к одному и тому
же upstream-процессу. Streaming latency измеряется до последнего chunk, а не
только до response headers.

Полный профиль намеренно не подключён к `build`, `check`, `verifyAll`,
verification pipeline или CI. Его запускают вручную после изменений сценария,
performance-sensitive gateway path либо для отдельной проверки SLO. Быстрый
контракт фаз без нагрузки запускается командой `./gradlew perfContractTest`.

## Результаты и воспроизводимость

После прогона доступны:

- `build/reports/gatling/<run>/index.html` - официальный HTML-отчёт Gatling;
- `build/reports/perf-01/latest-summary.md` - сводка direct/proxy,
  `proxy_overhead`, профиль, Git revision, JVM, OS, CPU и память;
- `build/reports/perf-01/perf-01-<UTC timestamp>.md` - неизменяемая копия
  сводки данного прогона;
- `build/perf-processes/upstream.log` и `gateway.log` - диагностические логи
  процессов без request/response bodies.

Сводка получает времена каждого успешного запроса через публичный Gatling
check `responseTimeInMillis`, использует nearest-rank p99 и сопоставляет число
успешных ответов с заданным объёмом workload. Full-profile подтверждает SLO
только если direct и proxy успешно завершили не менее 99% от запланированного
числа запросов. Любые Gatling request failures завершают задачу ошибкой.

## Ограничения стенда

Локальный прогон намеренно измеряет чистый loopback overhead без внешней сети.
Load generator, upstream и gateway при этом делят одну физическую машину, а
direct всегда измеряется раньше proxy. Поэтому результат сравним только при
одинаковых настройках и спокойном стенде; при отклонении следует повторить
прогон на выделенном хосте и сопоставить official Gatling report, process logs,
температуру и загрузку CPU.

## Зафиксированный прогон

Результат полного профиля хранится в
[perf-01-result.md](perf-01-result.md). Он фиксирует измеренные числа и
характеристики конкретного стенда, а не заменяет повторный прогон при изменении
gateway, JVM или оборудования.
