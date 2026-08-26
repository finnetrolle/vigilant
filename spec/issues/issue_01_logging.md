# Issue 01: Асинхронные JSON-логи только в stdout

**ID:** `VIG-01`  
**Тип:** Issue  
**Статус:** Done
**Приоритет:** High
**Связанные требования:** `PERF-01` (нагрузочная проверка вынесена в `issue_01A_benchmark.md`), раздел Observability и критерии приёмки v0 из `../MVP_NON_FUNCTIONAL_REQUIREMENTS.md`

## Принятое решение

Приложение Vigilant имеет ровно один logging sink: `stdout`.

Приложение не создаёт и не ротирует файлы логов и не отправляет логи по сети. Хранение, ротация и доставка в OpenTelemetry являются ответственностью Docker/container runtime и OpenTelemetry Collector.

Чтобы запись в `stdout` не блокировала Netty event loop, синхронный console appender должен находиться за ограниченным `AsyncAppender` с неблокирующей политикой переполнения. Для этого используется статический `logback.xml`; писать собственный logging bootstrap на Kotlin не нужно.

## Контекст

Vigilant работает поверх Armeria/Netty. Код обработки запроса выполняется на event-loop потоках, поэтому ожидание медленного `stdout`, logging driver или внешнего backend увеличило бы latency других запросов на том же event loop.

Сейчас проект использует `slf4j-simple` и содержит `TrafficLog`, который копирует части request/response body, собирает все заголовки и пишет сырые HTTP-пакеты на уровне `INFO`. Этот механизм:

- выполняет лишнюю работу на критическом пути;
- может раскрыть `Authorization`, cookies, query string и содержимое тела;
- противоречит правилу проекта не логировать bodies и auth headers;
- должен быть удалён без замены на per-request access log.

## Цель

Реализовать минимальную систему операционных логов:

```text
Application code
      |
      v
    SLF4J 2
      |
      v
Logback AsyncAppender
(bounded queue, neverBlock=true)
      |
      v
JSON ConsoleAppender
      |
      v
    stdout
      |
      v
Docker/container runtime
      |
      +--> локальное хранение и ротация
      |
      +--> OpenTelemetry Collector --> OTLP backend
```

После реализации:

- приложение пишет JSON Lines только в `stdout`;
- logging sink не выполняется на Netty event loop;
- переполнение logging queue не останавливает обработку запроса;
- потребление памяти очередью ограничено;
- тела HTTP-сообщений, query string, headers и секреты не попадают в логи;
- в application code отсутствуют file appenders, OTLP exporters и собственный logging bootstrap;
- логирование не блокирует event loop - подтверждение по нагрузке и профилю см. `issue_01A_benchmark.md`.

## Не входит в задачу

- File appender и запись в файл по пути, заданному приложению.
- Ротация или retention логов внутри JVM.
- OpenTelemetry SDK, Java Agent или OTLP exporter для логов внутри приложения.
- Программная сборка Logback appenders.
- Расширение `AppConfig` настройками logging queue/file/OTLP.
- Изменение Metro graph или startup flow ради логирования.
- Per-request access log успешных запросов.
- Логирование request/response body, полных headers, raw URI или query string даже на `DEBUG`/`TRACE`.
- Гарантированная доставка каждого события. Операционные логи могут быть потеряны при перегрузке.
- Юридически значимый audit log. Для него потребуется отдельный надёжный канал.
- Создание или выбор log storage/search UI.

## Почему выбран этот вариант

| Вариант | Решение | Причина |
|---|---|---|
| Async JSON stdout | Выбран | Минимум application-кода, единый container-native канал, event loop не ждёт sink |
| Синхронный stdout | Отклонён | `ConsoleAppender` и `PrintStream` остаются на вызывающем потоке и могут ждать медленный stdout |
| File appender в JVM | Отклонён | Дублирует функции container runtime, требует volume, rotation config и дополнительный worker |
| Прямой OTLP из JVM | Отклонён | Добавляет exporter, сетевые очереди, retry и lifecycle в gateway |
| Только Docker `mode=non-blocking`, без application async queue | Отклонён | Снижает риск backpressure logging driver, но console serialization/write и конкуренция за `System.out` остаются в request thread |

Приоритеты при перегрузке: **не блокировать event loop**, затем **ограничить память**, затем **доставить максимум операционных логов**. Одновременно гарантировать отсутствие блокировки, bounded memory и отсутствие потерь невозможно.

## Зависимости

В `../../build.gradle.kts`:

```kotlin
implementation("org.slf4j:slf4j-api:2.0.18")
implementation("ch.qos.logback:logback-classic:1.6.3")
```

Удалить:

```kotlin
runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
```

Требования:

- в runtime classpath должен быть ровно один SLF4J provider — Logback Classic;
- не добавлять `logstash-logback-encoder`: использовать встроенный `ch.qos.logback.classic.encoder.JsonEncoder`;
- не добавлять OpenTelemetry logging dependencies;
- не использовать dynamic dependency versions.

Указанные версии являются актуальными стабильными версиями на момент написания issue. При реализации разрешено обновить их до совместимых стабильных patch-релизов после проверки тестов и security advisories.

## Конфигурация Logback

Создать `src/main/resources/logback.xml`.

Целевая конфигурация:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <shutdownHook/>

    <property name="LOG_LEVEL" value="${VIGILANT_LOG_LEVEL:-INFO}"/>

    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <target>System.out</target>
        <encoder class="ch.qos.logback.classic.encoder.JsonEncoder">
            <withSequenceNumber>false</withSequenceNumber>
            <withNanoseconds>false</withNanoseconds>
            <withContext>false</withContext>
            <withMarkers>false</withMarkers>
            <withArguments>false</withArguments>
            <withFormattedMessage>true</withFormattedMessage>
            <withMessage>false</withMessage>
        </encoder>
    </appender>

    <appender name="ASYNC_STDOUT" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>8192</queueSize>
        <discardingThreshold>2048</discardingThreshold>
        <neverBlock>true</neverBlock>
        <includeCallerData>false</includeCallerData>
        <maxFlushTime>2000</maxFlushTime>
        <appender-ref ref="STDOUT"/>
    </appender>

    <root level="${LOG_LEVEL}">
        <appender-ref ref="ASYNC_STDOUT"/>
    </root>
</configuration>
```

Если конкретная версия Logback требует незначительной синтаксической корректировки, сохранить все перечисленные семантические настройки и зафиксировать рабочую форму тестом.

### Обязательные свойства конфигурации

- `STDOUT` подключён только к `ASYNC_STDOUT`, но не к root/application logger напрямую.
- Root logger подключает только `ASYNC_STDOUT`.
- `queueSize=8192` жёстко ограничивает число событий в application queue.
- Когда остаётся не более 2048 мест, `TRACE`, `DEBUG` и `INFO` могут отбрасываться.
- Когда очередь полностью занята, `neverBlock=true` разрешает потерять событие любого уровня вместо ожидания.
- `includeCallerData=false` запрещает дорогое вычисление caller location.
- При штатной остановке очередь сбрасывается не более 2000 мс; оставшиеся события могут быть потеряны.
- `<shutdownHook/>` останавливает Logback context и async worker при завершении JVM.
- Единственная runtime-настройка приложения — `VIGILANT_LOG_LEVEL`, default `INFO`.
- Поддерживаемые значения уровня: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`.

Размер очереди 8192 и threshold 2048 являются начальными значениями. Не добавлять для них application config до появления результатов нагрузки, которые докажут необходимость настройки.

## Формат событий

Каждая запись в stdout должна быть самостоятельным JSON-объектом и завершаться одним `\n`.

Ожидаемые поля встроенного `JsonEncoder`:

- `timestamp` — Unix epoch milliseconds;
- `level`;
- `threadName` — исходный producer thread, скопированный до передачи в async worker;
- `loggerName`;
- `formattedMessage`;
- `kvpList` — SLF4J key-value pairs, если они добавлены;
- `mdc` — MDC attributes, если они есть;
- `throwable` — структурированная ошибка либо `null`.

Не завязывать код или тесты на порядок JSON-полей. Collector должен парсить JSON, а не регулярное выражение по строке.

### Правила application logging

Для новых структурированных событий использовать SLF4J 2 fluent API:

```kotlin
logger.atInfo()
    .addKeyValue("event.name", "gateway.started")
    .addKeyValue("server.port", port)
    .log("Gateway started")
```

Правила:

- `formattedMessage` — короткий стабильный текст, а переменные значения — в key-value pairs;
- не собирать JSON вручную;
- не вызывать дорогой `toString()` до проверки, что уровень включён;
- одну ошибку логировать на одном ответственном слое, без повторных stack traces;
- для ожидаемой деградации использовать `WARN`, для неожиданной внутренней ошибки — `ERROR`;
- успешные запросы не логировать по одному; для их наблюдения использовать metrics/traces;
- не добавлять custom MDC на каждый запрос в рамках этой issue;
- если в будущем OTel instrumentation добавит `trace_id`/`span_id` в MDC, `JsonEncoder` передаст их без изменения этой конфигурации.

### Запрещённые данные

Нельзя передавать в logger или key-value pairs:

- request/response body или content preview;
- `HttpRequest`, `HttpResponse`, `RequestHeaders`, `ResponseHeaders` целиком;
- `Authorization`, `Proxy-Authorization`, cookies и API keys;
- полный набор HTTP headers;
- raw URI или query string;
- upstream URL с user-info/query;
- password, secret, token и credential values;
- объект, чей `toString()` может содержать перечисленные данные.

Правило действует на всех уровнях, включая `DEBUG` и `TRACE`.

## Удаление текущего traffic logging

Удалить:

- `../../src/main/kotlin/io/vigilant/gateway/TrafficLog.kt`;
- `../../src/test/kotlin/io/vigilant/gateway/TrafficLogTest.kt`.

Из `../../src/main/kotlin/io/vigilant/gateway/BypassProxyService.kt` удалить:

- `PacketSnapshot` для inbound/outbound;
- `peekData`, `peekTrailers`, `peekError`, `peekHeaders`;
- callbacks `whenRequestComplete` и `whenAvailable`, созданные только ради traffic log;
- обращения к `trafficLog`;
- импорты `RequestLogProperty` и `Instant`;
- устаревший KDoc о packet snapshots.

После изменения `serve` должен только переписать необходимые headers/path и передать streaming request/response:

```kotlin
override fun serve(
    ctx: ServiceRequestContext,
    request: HttpRequest,
): HttpResponse {
    val outbound = request.mapHeaders(::rewriteRequestHeaders)
    return upstream.execute(outbound).mapHeaders(::rewriteResponseHeaders)
}
```

Допустимо пометить неиспользуемый `ctx` как `_` только если сигнатура Kotlin/Armeria это позволяет без ухудшения читаемости. Не агрегировать body и не добавлять новый request decorator в этой issue.

## Граница ответственности Docker и OpenTelemetry

### Что гарантирует приложение

- Валидный JSONL в `stdout`.
- Bounded async queue.
- Неблокирующую политику переполнения application queue.
- Отсутствие file/network logging sink внутри JVM.

На этом ответственность application layer заканчивается.

### Что должен гарантировать deployment

Docker/container runtime должен:

- захватывать stdout контейнера;
- использовать неблокирующий delivery mode или эквивалент;
- иметь конечный buffer;
- иметь лимиты размера/количества локальных log files;
- передавать container logs инфраструктурному Collector.

Пример для Docker Compose:

```yaml
services:
  vigilant:
    logging:
      driver: json-file
      options:
        mode: non-blocking
        max-buffer-size: 4m
        max-size: 10m
        max-file: "3"
```

Это второй bounded buffer. При его заполнении Docker также может потерять новые сообщения; это намеренная защита приложения от backpressure.

Docker не является OTLP exporter по умолчанию. Для OpenTelemetry должен существовать отдельный Collector/adapter, настроенный deployment-командой.

Для Kubernetes рекомендуемый маршрут:

```yaml
mode: daemonset
presets:
  logsCollection:
    enabled: true
  kubernetesAttributes:
    enabled: true
```

Collector должен:

- прочитать container stdout;
- распарсить внутреннюю JSONL-строку;
- перенести `formattedMessage` в OTel log body либо сохранить согласованный mapping;
- перенести level в severity;
- сохранить KVP/MDC как attributes;
- добавить `service.name=vigilant` как resource attribute;
- использовать batch processor и OTLP exporter;
- исключить собственные stdout logs, чтобы не создать feedback loop.

Конфигурация Collector и backend credentials не должны попадать в application jar или `AppConfig`. В README достаточно описать контракт и привести минимальные Docker/Kubernetes примеры; полноценное production-развёртывание Collector может выполняться отдельной инфраструктурной задачей.

## План изменений по файлам

### Изменить

- `../../build.gradle.kts` — заменить `slf4j-simple` на SLF4J API + Logback Classic.
- `../../src/main/kotlin/io/vigilant/gateway/BypassProxyService.kt` — убрать traffic snapshots и логирование содержимого HTTP.
- `../../src/test/kotlin/io/vigilant/gateway/BypassProxyServiceTest.kt` — сохранить проверки streaming/proxy и добавить sentinel-проверку отсутствия секретов в stdout.
- `../../README.md` — описать stdout-only контракт, `VIGILANT_LOG_LEVEL`, Docker non-blocking/rotation и Collector responsibility.

### Создать

- `src/main/resources/logback.xml` — единственная production-конфигурация логирования.
- `src/test/kotlin/io/vigilant/gateway/LoggingConfigurationTest.kt` — проверки JSON и async topology.

### Удалить

- `../../src/main/kotlin/io/vigilant/gateway/TrafficLog.kt`.
- `../../src/test/kotlin/io/vigilant/gateway/TrafficLogTest.kt`.

Не изменять `AppConfig.kt`, `AppComponent.kt` или `Main.kt` ради конфигурации logging sinks.

## Порядок реализации

1. Заменить logging dependencies и проверить runtime classpath.
2. Добавить `src/main/resources/logback.xml`.
3. Удалить `TrafficLog` и все taps/callbacks из `BypassProxyService`.
4. Обновить тесты proxy и добавить logging configuration tests.
5. Обновить README с инфраструктурным контрактом.
6. Запустить unit/integration tests.

Нагрузочный overload test с медленным sink, benchmark `PERF-01` и проверка event-loop профиля выполняются в `issue_01A_benchmark.md`.

## Обязательные тесты

### Runtime и конфигурация

- В runtime classpath ровно один SLF4J provider.
- Production resource `logback.xml` успешно загружается без status errors.
- Root logger имеет только `ASYNC_STDOUT`.
- `ASYNC_STDOUT` имеет ровно один downstream appender — `STDOUT`.
- Проверены `queueSize=8192`, `discardingThreshold=2048`, `neverBlock=true`, `includeCallerData=false`, `maxFlushTime=2000`.
- `STDOUT` направлен в `System.out`, а не `System.err` или файл.
- Default level — `INFO`; `VIGILANT_LOG_LEVEL=DEBUG` меняет root level без изменения `AppConfig`.

### JSON

- Перехватить stdout, записать событие с KVP/MDC/exception и дождаться async drain.
- Каждая полученная строка парсится как отдельный JSON object.
- Проверить `timestamp`, `level`, `threadName`, `loggerName`, `formattedMessage`, `kvpList`, `mdc`, `throwable`.
- Многострочное exception message/stack trace не создаёт несколько физических log records.
- Порядок JSON-полей не проверяется.

### Неблокирующее поведение

В тестовом `LoggerContext` подключить к `AsyncAppender` appender, блокирующийся на latch и имитирующий зависший stdout. Проверить:

- producer-вызов завершается в короткий фиксированный срок и не ждёт latch;
- downstream appender вызывается на worker thread, а не на producer thread;
- очередь не превышает capacity;
- при заполнении producer продолжает работу, а события теряются;
- при достижении threshold первыми отбрасываются `TRACE`, `DEBUG`, `INFO`;
- остановка context завершается не позднее `maxFlushTime` плюс небольшой test tolerance.

Тест доказывает отсутствие ожидания sink/свободного места в очереди, а не отсутствие любой JVM pause.

### Отсутствие секретов и body

Выполнить успешный и ошибочный end-to-end запросы с уникальными sentinel values в:

- `Authorization`;
- `Proxy-Authorization`;
- `Cookie` и `Set-Cookie`;
- query string;
- request body;
- response body;
- произвольный secret-like header.

Собрать stdout приложения и проверить, что ни один sentinel не присутствует. Также проверить, что удаление traffic taps не изменило method/path/query/header/body pass-through и streaming semantics.

Нагрузочные проверки (три сценария на одном стенде, `PERF-01` при 2 000 RPS, профиль без logging I/O на event loop) - в `issue_01A_benchmark.md`.

## Критерии приёмки

- [x] `slf4j-simple` удалён, Logback Classic — единственный SLF4J provider.
- [x] Приложение имеет только один logging sink: `stdout`.
- [x] Каждая stdout-запись является валидной JSONL-записью.
- [x] Root logger подключён только к bounded `ASYNC_STDOUT`.
- [x] `neverBlock=true`; медленный sink не блокирует producer/event-loop test thread.
- [x] Политика потери событий при threshold/full queue проверена тестом.
- [x] `TrafficLog`, `PacketSnapshot` и все body/header taps удалены.
- [x] В логах отсутствуют bodies, query, auth headers, cookies и sentinel secrets.
- [x] File appender и OTLP exporter отсутствуют в application runtime.
- [x] `AppConfig`, Metro graph и startup flow не усложнены logging sink-конфигурацией.
- [x] В README описаны Docker non-blocking buffer, rotation limits и внешний Collector.
- [x] Unit/integration tests проходят.

Нагрузочные критерии приёмки - в `issue_01A_benchmark.md`.

## Definition of Done

Задача завершена, когда приложение выдаёт безопасный JSONL только в stdout через bounded non-blocking async queue; текущий traffic dump удалён; README описывает инфраструктурную доставку; функциональные и security tests проходят. Нагрузочный benchmark и профиль - отдельная задача `issue_01A_benchmark.md`.

## Риски и эксплуатационные замечания

- И application queue, и Docker buffer могут отбрасывать сообщения. Это осознанный компромисс ради latency.
- `WARN`/`ERROR` также могут быть потеряны при полностью занятой queue. Не использовать этот канал для гарантированного audit trail.
- Docker logging mode по умолчанию может быть blocking; deployment обязан явно включить non-blocking mode или предоставить эквивалентную гарантию.
- У default Docker `json-file` нет безопасных для проекта rotation limits без явной настройки; deployment обязан задать их.
- Размер очереди 8192 нужно подтвердить фактическим размером событий и memory budget по результатам `issue_01A_benchmark.md`.
- Даже `AsyncAppender` оставляет на producer thread проверку уровня и подготовку события. Не строить дорогие logging arguments и не логировать каждое успешное обращение.
- Если Collector читает stdout самого себя и экспортирует ошибки в stdout, возможен feedback loop; собственные логи Collector нужно исключить.

## Ссылки

- [Logback AsyncAppender](https://logback.qos.ch/manual/appenders-async-sift.html)
- [Logback configuration and shutdown hook](https://logback.qos.ch/manual/configuration.html)
- [Logback JsonEncoder source](https://github.com/qos-ch/logback/blob/master/logback-classic/src/main/java/ch/qos/logback/classic/encoder/JsonEncoder.java)
- [Docker logging drivers and non-blocking mode](https://docs.docker.com/engine/logging/configure/)
- [Docker container logs](https://docs.docker.com/engine/logging/)
- [OpenTelemetry Collector Kubernetes logs collection](https://opentelemetry.io/docs/platforms/kubernetes/helm/collector/)
- [OpenTelemetry logs concepts](https://opentelemetry.io/docs/concepts/signals/logs/)

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ clear
  Acceptance:   0.0   ✓ clear
  Boundaries:   0.0   ✓ clear
  Alternatives: 0.0   ✓ clear
  Assumptions:  0.25  ✓ Docker/Collector guarantees belong to deployment
  ──────────────────────────────
  Aggregate:    0.05  ✓ below threshold (0.2 spec)

Push lightly on: queue sizing after the first representative load test.
```
