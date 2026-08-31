# Vigilant

Vigilant - OpenAI-совместимый guardrails gateway для платформ ИИ-агентов. Он
проверяет запрос до отправки модели, применяет явно заданные политики и
формирует безопасный audit event, не раскрывая содержимое запроса.

> Статус: pre-release, версия `0.1.0-SNAPSHOT`. Первый production milestone
> request-side PII inspection в shadow mode закрыт. Измерения и safety evidence
> опубликованы в [inspection-load report](docs/inspection-load-result.md), а
> следующий frontier перечислен в [roadmap](spec/ROADMAP.md#текущий-roadmap-frontier).

## Что работает сейчас

- `POST /v1/chat/completions` с `Content-Type: application/json`.
- Bounded приём request body и детерминированная проверка встроенным
  `fast-pii` detector по `politics.conf`.
- Shadow-only решение: найденный PII фиксируется как `DETECTED`, но текущая
  disposition всегда `ALLOW`.
- Byte-identical replay исходного body и сохранение неизвестных полей.
- Config-driven `ANONYMOUS`, trusted-header или Basic identity с immediate-peer
  CIDR boundary, safe credential stripping и request-to-response context handoff.
- Streaming pass-through ответа upstream, включая SSE.
- Stable fail-closed ошибки для неподдерживаемой или неоднозначной request
  schema и при исчерпании inspection capacity.
- Force-backed local audit WAL до первого upstream byte или normal
  supported-request response.
- JSONL-логи, correlation/trace ID, OTLP traces и metrics, health/readiness
  endpoints и non-root OCI image.

Пока не поддерживаются OpenAI Responses API, response inspection, `BLOCK`,
`MASK`, `REMOVE`, authentication/external identity lookup, request-body disk spill,
Kubernetes/Helm и ML/NER detector. Полные границы первого инкремента зафиксированы в
[roadmap](spec/ROADMAP.md#не-входит-в-первый-production-increment).

## Быстрый старт

Требуется JDK 25. Устанавливать Gradle отдельно не нужно: проект содержит
Gradle Wrapper.

~~~bash
./gradlew installDist
cp politics.conf.example politics.conf
mkdir -p .vigilant-audit

VIGILANT_UPSTREAM_URL=https://api.openai.com \
  VIGILANT_AUDIT_DIRECTORY="$PWD/.vigilant-audit" \
  ./build/install/vigilant/bin/vigilant
~~~

Проверка готовности:

~~~bash
curl --fail http://127.0.0.1:8080/readyz
~~~

Пример запроса через gateway, где `OPENAI_MODEL` содержит доступную upstream
model:

~~~bash
curl --fail-with-body http://127.0.0.1:8080/v1/chat/completions \
  --header "Authorization: Bearer $OPENAI_API_KEY" \
  --header "Content-Type: application/json" \
  --data "{\"model\":\"$OPENAI_MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"Contact alice@example.com\"}]}"
~~~

Vigilant проверит model-visible text, запишет safe aggregate
`policy.shadow_decision` и отправит исходный JSON upstream без
пересериализации. В shadow mode найденный email не блокирует запрос.

## Как проходит запрос

~~~text
Client
  -> durable audit reservation
  -> configured identity extraction
  -> bounded request source
  -> OpenAI request parser
  -> policy selection + fast-pii inspection
  -> force-backed local audit record
  -> consumed identity header stripping + byte-identical replay
  -> upstream
~~~

Response body не агрегируется и передаётся клиенту потоково. Подробный
разбор startup, policy engine, threading и resource ownership приведён в
[описании архитектуры](docs/architecture.md). Поддерживаемый HTTP-контракт,
ошибки и модель таймаутов описаны в
[runtime contract](docs/runtime-contract.md).

`fast-pii` распознаёт email, российские телефоны, платёжные карты, IPv4/IPv6,
IBAN, ИНН, СНИЛС, внутренние паспорта РФ и полисы ОМС. Findings содержат только
безопасные метаданные и UTF-8 offsets, но не matched text.

## Конфигурация

Application configuration загружается с приоритетом
`environment > HOCON file > defaults`. Для запуска обязательны:

- `VIGILANT_UPSTREAM_URL` или `vigilant.upstream-url` в HOCON;
- существующий persistent `VIGILANT_AUDIT_DIRECTORY` или
  `vigilant.audit-directory` в HOCON;
- валидный `politics.conf`, по умолчанию из текущей директории;
- `VIGILANT_PORT` необязателен, значение по умолчанию - `8080`.

Примеры находятся в [vigilant.conf.example](vigilant.conf.example) и
[politics.conf.example](politics.conf.example). Полный список настроек,
defaults, validation rules и порядок поиска файлов приведены в
[configuration reference](docs/configuration.md).

Невалидная или неполная application/policy configuration печатает безопасную
ошибку в stderr и завершает процесс с кодом `2`.

## OCI image

~~~bash
docker build --tag vigilant:0.1.0-SNAPSHOT .

docker run --rm --name vigilant \
  --publish 8080:8080 \
  --stop-timeout 35 \
  --env VIGILANT_UPSTREAM_URL=https://api.openai.com \
  --env VIGILANT_POLITICS_CONFIG=/etc/vigilant/politics.conf \
  --mount type=bind,src="$PWD/politics.conf",dst=/etc/vigilant/politics.conf,readonly \
  --mount type=volume,src=vigilant-audit,dst=/var/lib/vigilant/audit \
  vigilant:0.1.0-SNAPSHOT
~~~

Образ запускается от UID/GID `10001`. Полный запуск с HOCON, формат
versioned artifact, lifecycle и требования smoke-теста описаны в
[deployment guide](docs/deployment.md).

~~~bash
./scripts/oci-smoke-test
~~~

Smoke-тест требует `curl`, Docker и Python 3. Он запускается явно и не
входит в `build` или CI.

## Observability

- Единственный logging sink - stdout в формате JSON Lines.
- Тела, query string, credentials, auth/cookie headers и content previews не
  логируются.
- Имена входных headers для session ID и W3C `traceparent` настраиваются через
  `VIGILANT_TRACING_SESSION_HEADER` и `VIGILANT_TRACING_TRACEPARENT_HEADER`.
- Валидный входящий trace продолжается, отсутствующий или некорректный контекст
  заменяется новым; effective session и trace context возвращаются клиенту и
  передаются upstream.
- Application JSON logs, OTLP/JSON traces и OTLP/JSON metrics пишутся только в
  stdout. Приложение не подключается к OpenTelemetry Collector.
- Local segmented WAL durably сохраняет safe aggregate record до forwarding;
  `policy.shadow_decision` остаётся только best-effort stdout projection.
- Prometheus scrape endpoint отсутствует.

Имена метрик, JSONL schema, audit events, настройка Collector и правила
безопасности приведены в [observability reference](docs/observability.md).

## Проверки

~~~bash
./gradlew build                  # compile, tests, detekt, work-item validation
./gradlew test                   # тесты
./gradlew dependencyCheckAnalyze # OWASP CVE scan runtimeClasspath
./gradlew verifyAll              # build + OWASP dependency check
./gradlew piiQualityReport       # synthetic PII quality gate + reports
./gradlew pitest                 # mutation testing, запускается отдельно
./gradlew installGitHooks        # установить versioned pre-push hook
~~~

`build` также проверяет, что JMH не попал в production runtime classpath.
CI на каждый push в `main` и pull request запускает `build`. OWASP job
запускается только при наличии секрета `NVD_API_KEY`. Mutation testing в
текущий CI не входит. Полное описание reports, CVE threshold и локальных
проверок находится в [development guide](docs/development.md).

## Performance

~~~bash
./gradlew piiJmhBaseline # JMH baseline fast-pii detector
./gradlew perfTest       # PERF-01 three-route logging load/profile test
./gradlew inspectionPhaseBenchmark # parsing/windowing/policy/total p50/p95/p99
./gradlew inspectionLoadTest       # packaged 2,000 RPS inspection profile
~~~

Все performance-проверки запускаются явно и не входят в `build`, `verifyAll`
или CI.
Описание JMH matrix находится в
[VIG-02-15](spec/issues/epic_02/issue_02_15_jmh_baseline.md), а нагрузочного
теста - в [методике PERF-01](docs/perf-01-load-test.md). Зафиксированные
результаты публикуются в [истории PERF-01](docs/perf-01-result.md).
Результаты production inspection profile опубликованы в
[inspection-load report](docs/inspection-load-result.md).

## Документация проекта

- [Roadmap первого production increment](spec/ROADMAP.md)
- [Реестр epics и issues](spec/WORK_ITEMS.md)
- [Функции MVP](spec/MVP_FUNCTIONS.md)
- [Нефункциональные требования и стек](spec/MVP_NON_FUNCTIONAL_REQUIREMENTS.md)
- [Функции Stage 1](spec/STAGE_1_FUNCTIONS.md)
- [Функции вне границ продукта](spec/OUT_OF_SCOPE_FUNCTIONS.md)
- [Configuration reference](docs/configuration.md)
- [Architecture](docs/architecture.md)
- [Runtime contract](docs/runtime-contract.md)
- [Observability reference](docs/observability.md)
- [Deployment guide](docs/deployment.md)
- [Development guide](docs/development.md)
- [Inspection-load baseline](docs/inspection-load-result.md)

Нормативный scope хранится в `spec/`. README предназначен для быстрого входа
в проект и не заменяет требования, статусы issues или roadmap.
