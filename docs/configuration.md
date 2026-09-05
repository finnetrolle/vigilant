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
  environment = "development"
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

  identity-mode = "DUMMY"
  identity-dummy-user = "local-user"
  identity-dummy-groups = ["local-group"]

  otlp-enabled = true
}
~~~

Production JWT заменяет три Dummy settings следующим immutable trust snapshot:

~~~hocon
vigilant {
  environment = "production"
  identity-mode = "JWT"
  identity-jwt-issuer = "https://keycloak.example/realms/platform"
  identity-jwt-audience = "vigilant"
  identity-jwt-jwks = [
    {
      kty = "RSA"
      kid = "key-2026-01"
      n = "<unpadded-base64url-rsa-modulus>"
      e = "AQAB"
    }
  ]
}
~~~

External mode использует trusted Bridge endpoint:

~~~hocon
vigilant {
  environment = "production"
  identity-mode = "EXTERNAL"
  identity-external-url = "http://bridge.internal/v1/identity?tenant=platform"
  identity-external-timeout = 1s
}
~~~

`DUMMY` разрешён только в `development`/`test`, а JWT и `EXTERNAL` разрешены
во всех environments. Startup выбирает ровно один mode; fallback и runtime
switching отсутствуют.

Любой HOCON key `vigilant.some-setting` переопределяется environment
variable `VIGILANT_SOME_SETTING`.

| Environment variable | Назначение | Default |
|---|---|---:|
| `VIGILANT_ENVIRONMENT` | Runtime profile: `development`, `test` или `production` | обязательна |
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
| `VIGILANT_IDENTITY_MODE` | Exact mode: `DUMMY`, `JWT` или `EXTERNAL` | обязательна |
| `VIGILANT_IDENTITY_DUMMY_USER` | Configured user для Dummy identity | обязательна в `DUMMY` |
| `VIGILANT_IDENTITY_DUMMY_GROUPS` | Comma-separated configured Dummy groups | пустой список |
| `VIGILANT_IDENTITY_JWT_ISSUER` | Exact trusted JWT issuer | обязательна в `JWT` |
| `VIGILANT_IDENTITY_JWT_AUDIENCE` | Audience, которая должна присутствовать в `aud` | обязательна в `JWT` |
| `VIGILANT_IDENTITY_JWT_JWKS` | Non-empty pinned RSA public JWK list | обязательна в `JWT` |
| `VIGILANT_IDENTITY_EXTERNAL_URL` | Exact absolute HTTP(S) trusted Bridge endpoint | обязательна в `EXTERNAL` |
| `VIGILANT_IDENTITY_EXTERNAL_TIMEOUT` | Whole-exchange Bridge deadline | `1s` в `EXTERNAL` |
| `VIGILANT_OTLP_ENABLED` | Выводит traces и metrics как OTLP JSON Lines в stdout | `true` |
| `VIGILANT_CONFIG` | Явный путь к HOCON-файлу | не задан |

`VIGILANT_LOG_LEVEL` настраивает Logback отдельно от HOCON. Допустимые
значения: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`. Default -
`INFO`.

Complex `VIGILANT_IDENTITY_JWT_JWKS` задаётся strict JSON array с полями
`kty`, `kid`, `n`, `e`; HOCON file использует native object-list syntax из
примера выше. Неизвестные или duplicate JSON fields отклоняются.

## Validation rules

- `upstream-url` должен быть абсолютным HTTP(S) URL без user info, query и
  fragment.
- `port` должен находиться в диапазоне `1..65535`.
- Удалённые `audit-*` HOCON keys и `VIGILANT_AUDIT_*` environment variables не
  имеют aliases или compatibility path и отклоняются strict loader-ом.
- Duration принимает значения вида `300ms`, `10s`, `5m` или `PT5M`.
- Duration должен быть положительным и помещаться в signed nanosecond scheduler
  bound, кроме
  `shutdown-quiet-period=0s`, который отключает ожидание quiet gap.
- `shutdown-force-timeout` должен быть не меньше quiet period.
- Global retained limit должен быть не меньше per-request limit.
- Inspection counts и limits должны быть положительными.
- Оба tracing header name должны быть валидными HTTP header names и не должны
  совпадать без учёта регистра.
- `environment` и `identity-mode` обязательны. `DUMMY` разрешён только в
  `development`/`test` и требует `identity-dummy-user`. `JWT` требует issuer,
  audience и non-empty JWK set. `EXTERNAL` требует absolute lowercase
  `http://` или `https://` URL с host; path/query сохраняются, user info и
  fragment запрещены, plain HTTP разрешён и в production.
- Settings любого невыбранного identity mode отклоняются. Mode aliases,
  fallback, health check и runtime switching отсутствуют.
- External timeout должен быть positive duration в scheduler bound. Он один
  охватывает acquisition, connect, write, response headers и полный body.
- Dummy user/groups используют grammar
  `[A-Za-z0-9][A-Za-z0-9._:@/\-]{0,127}` и `Locale.ROOT` lowercase. Groups
  дедуплицируются с сохранением первого порядка и ограничены 128 уникальными
  значениями.
- Каждый JWT JWK обязан иметь `kty=RSA`, non-blank unique `kid`, valid
  Base64url modulus `n` и exponent `e`. Только public fields принимаются strict
  loader-ом. Rotation выполняется deployment update: новый и старый keys
  одновременно добавляются в config, затем старый удаляется отдельным update.
- JWT принимает только `alg=RS256`; header `kid` выбирает ровно одну pinned
  key. Signature, exact issuer, containing audience, required integral `exp`
  и optional integral `nbf` проверяются до `sub`/`groups`. Discovery, JWKS
  fetch, refresh и introspection отсутствуют в JWT mode.

`VIGILANT_OTLP_ENABLED=false` отключает только вывод OTLP/JSON traces и metrics.
Создание trace context, request-scoped JSON logs и сбор метрик внутри процесса
продолжаются. Настройки Collector endpoint нет: Vigilant не открывает к нему
сетевое соединение. Захват, retention, rotation и delivery stdout принадлежат
container runtime и deployment.

## Снимок политик

Путь к обязательному файлу политик задаёт
`VIGILANT_POLITICS_CONFIG`. Без него используется `./politics.conf`. Файл
читается и проверяется один раз при запуске; горячая перезагрузка отсутствует.

Минимальный снимок приведён в
[politics.conf.example](../politics.conf.example). Полная строгая структура
HOCON, сопоставление, одновременные переопределения и правила проверки описаны
в [руководстве по политикам](policies.md). Для текущего этапа теневого режима
он обязан содержать хотя бы одну действующую включённую глобальную политику
`REQUEST`:

- `url=*`;
- `model=*`;
- анонимный субъект `*`;
- детектор `fast-pii`;
- значение `ALLOW` без преобразований для всех реакций.

Пустой, отключённый, полностью переопределённый снимок или снимок с
принудительными реакциями отклоняется до запуска сервера.

## Ошибки startup

Недопустимая или неполная application/policy configuration выводит
безопасное сообщение в stderr и завершает процесс с кодом `2`.
