# PERF-01: методика нагрузочного теста

## Цель

Тест измеряет накладные расходы одной реплики Vigilant при установившейся
нагрузке 2 000 RPS и проверяет неблокирующий logging path:

`proxy_overhead p99 = proxy p99 - direct p99`.

PERF-01 подтверждён, если direct и default gateway выдержали целевую частоту
без ошибок и `proxy_overhead p99 <= 2 ms`. Дополнительно тот же прогон требует,
чтобы gateway с медленным sink не следовал за задержкой sink, а JFR не находил
stdout/file/network I/O или ожидание logging queue на event-loop потоках.

## Запуск

Требуются JDK 25, свободные локальные порты `18080`, `18081` и `18082`, минимум
2 GiB heap для load generator и спокойный стенд без конкурирующей нагрузки.
Перед эталонным прогоном следует подключить питание, отключить
энергосберегающий режим и дождаться стабильной температуры машины.

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
| Пауза между соседними маршрутами | 5 s | `perf.phaseGapSeconds` |
| Non-streaming доля | 80% | `perf.nonStreamingPercent` |
| Streaming доля | 20% | `100 - perf.nonStreamingPercent` |
| Максимум соединений Gatling на host | 64 | `perf.connectionsPerHost` |
| Тело запроса | 1 024 bytes | `perf.requestBytes` |
| Non-streaming ответ | 4 096 bytes | `perf.nonStreamingResponseBytes` |
| Streaming ответ | 4 x 1 024 bytes | `perf.streamingChunks`, `perf.streamingChunkBytes` |
| Интервал между streaming chunks | 1 ms | `perf.streamingChunkDelayMs` |
| Upstream port | 18081 | `perf.upstreamPort` |
| Default gateway port | 18080 | `perf.gatewayPort` |
| Slow-sink gateway port | 18082 | `perf.slowSinkGatewayPort` |
| Задержка одного downstream write | 50 ms | `perf.slowSinkDelayMs` |

Нагрузка является open workload: каждый virtual user выполняет ровно один
HTTP-запрос. Gatling использует общий server-to-server connection pool и
ограничивает его указанным числом соединений на host.

## Порядок одного прогона

1. `installDist` собирает тот же runtime-дистрибутив, который запускается
   оператором.
2. Сценарий запускает локальный Armeria upstream и два packaged Vigilant в
   отдельных JVM-процессах с heap `512 MiB` каждый. Default gateway использует
   production `INFO` stdout logging, второй gateway отличается только тестовым
   console sink с задержкой 50 ms.
3. Direct workload линейно разгоняется до 2 000 RPS за 60 секунд.
4. Direct workload удерживается 60 секунд при 2 000 RPS, чтобы JVM достигла
   устойчивого состояния до начала измерения.
5. Direct workload измеряется 120 секунд при постоянных 2 000 RPS.
6. После пятисекундной паузы proxy workload отдельно разогревается до
   2 000 RPS за 60 секунд.
7. Proxy workload удерживается 60 секунд при 2 000 RPS до начала измерения.
8. Proxy workload измеряется 120 секунд при постоянных 2 000 RPS.
9. После пятисекундной паузы slow-sink workload проходит такой же отдельный
   ramp, steady-state warm-up и 120-секундное измерение.
10. Gatling проверяет нулевое число ошибок во всех шести измеряемых
    populations, а сценарий рассчитывает p50/p95/p99 и разницу p99.
11. После graceful shutdown анализируются audit delivery и JFR-записи обоих
    gateway только внутри фактически наблюдаемых измерительных окон.

Все три фазы последовательны: каждый маршрут получает полные 2 000 RPS и не
конкурирует с другим измеряемым маршрутом. Они обращаются к одному upstream.
Streaming latency измеряется до последнего chunk, а не только до response
headers. Запросы используют валидный Chat Completions JSON фиксированного
размера, а upstream проверяет digest тела и формирует одинаковые ответы для
всех маршрутов.

Slow sink работает за тем же bounded `AsyncAppender`: queue size `8192`,
discarding threshold `2048`, `neverBlock=true`, `maxFlushTime=2000`. Его
50-миллисекундная задержка намеренно переполняет очередь. Полный результат
принимается, если request p99 остаётся меньше задержки одного sink write и в
измерительном окне наблюдается потеря audit-событий. Это доказывает
независимость request latency от downstream logging latency, а не гарантирует
доставку логов.

Оба gateway запускаются с bounded JFR recording `maxsize=256m`. Профиль
включает execution/native samples, file I/O, socket writes и monitor/park
events. Анализ fail-closed: он требует event-loop samples и отклоняет
`PrintStream.write`, file I/O, network export или ожидание logging queue на
потоках Armeria/Netty в измерительном окне. Границы окна не выводятся из
расписания инъекции: сценарий конкурентно сохраняет самый ранний фактический
старт измеряемого запроса и самое позднее фактическое завершение. JFR читает
half-open интервал между этими наблюдениями, включая хвост последнего ответа и
исключая startup и warm-up.

Полный профиль намеренно не подключён к `build`, `check`, `verifyAll`,
verification pipeline или CI. Его запускают вручную после изменений сценария,
performance-sensitive gateway path либо для отдельной проверки SLO. Быстрый
контракт фаз без нагрузки запускается командой `./gradlew perfContractTest`.

## Результаты и воспроизводимость

После прогона доступны:

- `build/reports/gatling/<run>/index.html` - официальный HTML-отчёт Gatling;
- `build/reports/perf-01/latest-summary.md` - сводка трёх маршрутов,
  `proxy_overhead`, logging/JFR evidence, профиль, Git revision, JVM, OS, CPU и
  память;
- `build/reports/perf-01/perf-01-<UTC timestamp>.md` - неизменяемая копия
  сводки данного прогона;
- `build/perf-processes/upstream.log`, `gateway.log` и `slow-sink-gateway.log`
  - диагностические логи процессов без request/response bodies;
- `build/perf-processes/gateway.jfr` и `slow-sink-gateway.jfr` - bounded
  профили packaged gateway.

Сводка получает времена каждого успешного запроса через публичный Gatling
check `responseTimeInMillis`, использует nearest-rank p99 и сопоставляет число
успешных ответов с заданным объёмом workload. Full-profile подтверждает SLO
только если каждый маршрут успешно завершил не менее 99% от запланированного
числа запросов, default audit delivery не ниже 99%, slow sink показывает
bounded loss, а оба JFR-профиля содержат event-loop evidence без нарушений.
Любые Gatling request failures завершают задачу ошибкой.

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
