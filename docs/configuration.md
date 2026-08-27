# Конфигурация Vigilant

## Источники и приоритет

Application configuration загружается в порядке:

1. переменные окружения;
2. HOCON-файл;
3. встроенные значения по умолчанию.

Если задан `VIGILANT_CONFIG`, указанный файл обязан существовать. Иначе
Vigilant выбирает первый существующий файл:

1. `./vigilant.conf`;
2. `/etc/vigilant/vigilant.conf`.

Если ни один файл не найден, application configuration читается только из
environment. Policy snapshot загружается отдельно и всегда обязателен.

## Application configuration

Полный пример находится в
[vigilant.conf.example](../vigilant.conf.example):

~~~hocon
vigilant {
  upstream-url = "http://127.0.0.1:18081"
  port = 8080

  upstream-connect-timeout = 10s
  upstream-write-timeout = 30s
  upstream-response-timeout = 5m
  upstream-connection-idle-timeout = 10s

  shutdown-quiet-period = 5s
  shutdown-force-timeout = 30s

  inspection-per-request-limit-bytes = 8388608
  inspection-global-retained-limit-bytes = 67108864
  inspection-max-concurrent-request-sources = 128
  inspection-max-retained-segments-per-request = 128

  tracing-session-header = "x-session-id"
  tracing-traceparent-header = "traceparent"

  otlp-enabled = true
}
~~~

Любой HOCON key `vigilant.some-setting` переопределяется environment
variable `VIGILANT_SOME_SETTING`.

| Environment variable | Назначение | Default |
|---|---|---:|
| `VIGILANT_UPSTREAM_URL` | Абсолютный HTTP(S) URL upstream | обязательна |
| `VIGILANT_PORT` | HTTP port gateway | `8080` |
| `VIGILANT_UPSTREAM_CONNECT_TIMEOUT` | Установка соединения с upstream | `10s` |
| `VIGILANT_UPSTREAM_WRITE_TIMEOUT` | Запись запроса в upstream | `30s` |
| `VIGILANT_UPSTREAM_RESPONSE_TIMEOUT` | Первый response object и максимальная пауза между objects | `5m` |
| `VIGILANT_UPSTREAM_CONNECTION_IDLE_TIMEOUT` | Жизнь idle connection в pool | `10s` |
| `VIGILANT_SHUTDOWN_QUIET_PERIOD` | Gap без active requests перед shutdown | `5s` |
| `VIGILANT_SHUTDOWN_FORCE_TIMEOUT` | Абсолютный graceful shutdown bound | `30s` |
| `VIGILANT_INSPECTION_PER_REQUEST_LIMIT_BYTES` | Максимум retained bytes одного request | `8388608` |
| `VIGILANT_INSPECTION_GLOBAL_RETAINED_LIMIT_BYTES` | Process-wide retained byte quota | `67108864` |
| `VIGILANT_INSPECTION_MAX_CONCURRENT_REQUEST_SOURCES` | Максимум одновременно admitted request sources | `128` |
| `VIGILANT_INSPECTION_MAX_RETAINED_SEGMENTS_PER_REQUEST` | Максимум storage segments одного request | `128` |
| `VIGILANT_TRACING_SESSION_HEADER` | Header с opaque session ID | `x-session-id` |
| `VIGILANT_TRACING_TRACEPARENT_HEADER` | Header со значением W3C `traceparent` | `traceparent` |
| `VIGILANT_OTLP_ENABLED` | Выводит traces и metrics как OTLP JSON Lines в stdout | `true` |
| `VIGILANT_CONFIG` | Явный путь к HOCON-файлу | не задан |

`VIGILANT_LOG_LEVEL` настраивает Logback отдельно от HOCON. Допустимые
значения: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`. Default -
`INFO`.

## Validation rules

- `upstream-url` должен быть абсолютным HTTP(S) URL без user info, query и
  fragment.
- `port` должен находиться в диапазоне `1..65535`.
- Duration принимает значения вида `300ms`, `10s`, `5m` или `PT5M`.
- Duration должен быть положительным, кроме
  `shutdown-quiet-period=0s`, который отключает ожидание quiet gap.
- `shutdown-force-timeout` должен быть не меньше quiet period.
- Global retained limit должен быть не меньше per-request limit.
- Inspection counts и limits должны быть положительными.
- Оба tracing header name должны быть валидными HTTP header names и не должны
  совпадать без учёта регистра.

`VIGILANT_OTLP_ENABLED=false` отключает только вывод OTLP/JSON traces и metrics.
Создание trace context, request-scoped JSON logs и сбор метрик внутри процесса
продолжаются. Настройки Collector endpoint нет: Vigilant не открывает к нему
сетевое соединение.

## Policy snapshot

Путь к обязательному policy file задаёт
`VIGILANT_POLITICS_CONFIG`. Без него используется `./politics.conf`. Файл
читается и валидируется один раз при startup; hot reload отсутствует.

Минимальный snapshot приведён в
[politics.conf.example](../politics.conf.example). Для текущего shadow
increment он обязан содержать хотя бы одну effective enabled global
`REQUEST` policy:

- `url=*`;
- `model=*`;
- anonymous subject `*`;
- detector `fast-pii`;
- disposition `ALLOW` без transformations для всех reactions.

Пустой, disabled, полностью overridden или enforcement snapshot отклоняется до
старта server.

## Ошибки startup

Недопустимая или неполная application/policy configuration выводит безопасное
сообщение в stderr и завершает процесс с кодом `2`.
