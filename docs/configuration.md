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

  audit-directory = "/var/lib/vigilant/audit"
  audit-max-event-bytes = 65536
  audit-max-pending-events = 128
  audit-max-retained-bytes = 1073741824
  audit-max-segment-bytes = 16777216
  audit-max-segment-age = 5s

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

  identity-mode = "ANONYMOUS"

  otlp-enabled = true
}
~~~

Любой HOCON key `vigilant.some-setting` переопределяется environment
variable `VIGILANT_SOME_SETTING`.

| Environment variable | Назначение | Default |
|---|---|---:|
| `VIGILANT_UPSTREAM_URL` | Абсолютный HTTP(S) URL upstream | обязательна |
| `VIGILANT_PORT` | HTTP port gateway | `8080` |
| `VIGILANT_AUDIT_DIRECTORY` | Existing persistent directory, exclusively locked by one process | обязательна |
| `VIGILANT_AUDIT_MAX_EVENT_BYTES` | Максимальный framed audit record | `65536` |
| `VIGILANT_AUDIT_MAX_PENDING_EVENTS` | Максимум pending audit reservations | `128` |
| `VIGILANT_AUDIT_MAX_RETAINED_BYTES` | Максимум локально retained WAL bytes | `1073741824` |
| `VIGILANT_AUDIT_MAX_SEGMENT_BYTES` | Максимальный WAL segment | `16777216` |
| `VIGILANT_AUDIT_MAX_SEGMENT_AGE` | Максимальный возраст non-empty active segment | `5s` |
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
| `VIGILANT_IDENTITY_MODE` | Единственный identity source: `ANONYMOUS`, `TRUSTED_HEADERS` или `BASIC` | `ANONYMOUS` |
| `VIGILANT_IDENTITY_USER_HEADER` | Optional user header для `TRUSTED_HEADERS` | не задан |
| `VIGILANT_IDENTITY_GROUPS_HEADER` | Optional comma-separated groups header для `TRUSTED_HEADERS` | не задан |
| `VIGILANT_IDENTITY_TRUSTED_CIDRS` | Comma-separated literal IPv4/IPv6 CIDR immediate peers | не задан |
| `VIGILANT_OTLP_ENABLED` | Выводит traces и metrics как OTLP JSON Lines в stdout | `true` |
| `VIGILANT_CONFIG` | Явный путь к HOCON-файлу | не задан |

`VIGILANT_LOG_LEVEL` настраивает Logback отдельно от HOCON. Допустимые
значения: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`. Default -
`INFO`.

## Validation rules

- `upstream-url` должен быть абсолютным HTTP(S) URL без user info, query и
  fragment.
- `port` должен находиться в диапазоне `1..65535`.
- `audit-directory` обязателен, должен существовать, быть directory и иметь
  единственного process owner. Missing, unavailable или locked directory
  останавливает startup с code `2` без вывода raw path.
- Audit bounds положительны, `max-event-bytes <= 65536` и соблюдают
  `max-event-bytes <= max-segment-bytes <= max-retained-bytes`.
- Duration принимает значения вида `300ms`, `10s`, `5m` или `PT5M`.
- Duration должен быть положительным и помещаться в signed nanosecond scheduler
  bound, кроме
  `shutdown-quiet-period=0s`, который отключает ожидание quiet gap.
- `shutdown-force-timeout` должен быть не меньше quiet period.
- Global retained limit должен быть не меньше per-request limit.
- Inspection counts и limits должны быть положительными.
- Оба tracing header name должны быть валидными HTTP header names и не должны
  совпадать без учёта регистра.
- Identity mode задаётся явно и не смешивает sources. `ANONYMOUS` и `BASIC`
  запрещают trusted-header settings.
- `TRUSTED_HEADERS` требует хотя бы один valid user/groups header, distinct
  header names и непустой список literal IPv4/IPv6 CIDR. DNS lookup при
  validation не выполняется.

`TRUSTED_HEADERS` принимает user с одним header value и groups из repeated или
combined comma-separated values. Identity token grammar:
`[A-Za-z0-9][A-Za-z0-9._:@/\-]{0,127}`; результат приводится к lowercase через
`Locale.ROOT`, groups дедуплицируются и итоговый набор ограничен 128
уникальными нормализованными groups. Mode
`BASIC` принимает ровно один strict Basic header и сохраняет только ASCII
username до первого `:`. Password bytes и raw credential не сохраняются.

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
старта server. Его policy ID/version population также должна помещаться в
worst-case safe audit record при configured event bound; проверка выполняется
до открытия traffic.

## Ошибки startup

Недопустимая или неполная application/policy/audit configuration выводит
безопасное сообщение в stderr и завершает процесс с кодом `2`.
