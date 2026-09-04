# Vigilant

Vigilant - OpenAI-совместимый guardrails gateway для платформ ИИ-агентов. Он
проверяет запрос до отправки модели, применяет явно заданные политики и
формирует безопасный audit event, не раскрывая содержимое запроса.

> Статус: pre-release, версия `0.1.0-SNAPSHOT`. Первый production milestone
> request-side PII inspection в shadow mode и ordinary-response enforcement закрыты. Измерения и safety evidence
> опубликованы в [inspection-load report](docs/inspection-load-result.md), а
> следующий frontier перечислен в [roadmap](spec/ROADMAP.md#текущий-roadmap-frontier).

## Что работает сейчас

- `POST /v1/chat/completions` с `Content-Type: application/json`.
- Bounded приём request body и детерминированная проверка встроенным
  `fast-pii` detector по `politics.conf`.
- Request-side shadow decision: найденный PII фиксируется как `DETECTED`,
  но request disposition остаётся `ALLOW`.
- Byte-identical replay исходного body и сохранение неизвестных полей.
- Development/test Dummy Bearer identity с configured normalized user/groups,
  unchanged upstream Authorization и request-to-response context handoff.
- Streaming/backpressure в низкоуровневом bypass transport. Guardrail path
  полностью удерживает ordinary/SSE Chat Completions response до terminal
  protocol validation. Ordinary JSON проходит response policies и применяет
  exact `ALLOW`, `MASK` или `BLOCK` до первого client byte; SSE пока только
  валидируется и replay-ится без enforcement.
- Stable fail-closed ошибки для неподдерживаемой или неоднозначной request
  schema и при исчерпании inspection capacity.
- Safe best-effort stdout audit pair вокруг реально начатого request и
  ordinary-response analysis.
- JSONL-логи, correlation/trace ID, OTLP traces и metrics, health/readiness
  endpoints и non-root OCI image.

Пока не поддерживаются OpenAI Responses API, SSE response enforcement,
request-side `BLOCK`/`MASK`, `REMOVE`, authentication/external identity lookup,
request-body или response-body disk spill,
Kubernetes/Helm и ML/NER detector. Полные границы первого инкремента зафиксированы в
[roadmap](spec/ROADMAP.md#не-входит-в-первый-production-increment).

## Быстрый старт

Требуется JDK 25. Устанавливать Gradle отдельно не нужно: проект содержит
Gradle Wrapper.

~~~bash
./gradlew installDist
cp politics.conf.example politics.conf

VIGILANT_UPSTREAM_URL=https://api.openai.com \
  VIGILANT_ENVIRONMENT=development \
  VIGILANT_IDENTITY_MODE=DUMMY \
  VIGILANT_IDENTITY_DUMMY_USER=local-user \
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

Vigilant проверит видимый модели текст, best-effort запишет безопасную пару
`policy.analysis_started`/`policy.analysis_completed` в stdout и отправит
исходный JSON вышестоящему серверу без пересериализации. В теневом режиме
найденный адрес электронной почты не блокирует запрос.

## Как проходит запрос

Для поддержанного запроса выполняются извлечение настроенного идентификатора,
ограниченный приём данных, разбор протокола и вычисление политик. Вокруг реально
начатого detector execution публикуется best-effort stdout pair, после чего
исходный источник одноразово передаётся транспорту во владение и
воспроизводится без изменения байтов. Logging delivery не участвует в traffic
outcome или upstream handoff.

- [Диаграмма последовательности обработки запроса UML 2.0](docs/diagrams/request-inspection-sequence.puml)
- [Диаграмма компонентов исполняемой системы UML 2.0](docs/diagrams/runtime-components.puml)

Низкоуровневый bypass transport передаёт response потоком. Guardrail path
полностью удерживает ordinary/SSE response в RAM. Ordinary JSON атомарно
раскрывается только после response `ALLOW`/точного `MASK`, а `BLOCK` заменяет
весь upstream response safe `403`. SSE до VIG-20-05 проходит только terminal
protocol validation и exact replay. Подробный
разбор запуска, механизма политик, потоков и владения ресурсами приведён в
[описании архитектуры](docs/architecture.md). Поддерживаемый HTTP-контракт,
ошибки и модель таймаутов описаны в
[контракте исполнения](docs/runtime-contract.md).

`fast-pii` распознаёт email, российские телефоны, платёжные карты, IPv4/IPv6,
IBAN, ИНН, СНИЛС, внутренние паспорта РФ и полисы ОМС. Срабатывания содержат
только безопасные метаданные и смещения UTF-8, но не совпавший текст.

## Конфигурация

Application configuration загружается с приоритетом
`environment > HOCON file > defaults`. Для запуска обязательны:

- `VIGILANT_UPSTREAM_URL` или `vigilant.upstream-url` в HOCON;
- `VIGILANT_ENVIRONMENT`, `VIGILANT_IDENTITY_MODE=DUMMY` и
  `VIGILANT_IDENTITY_DUMMY_USER` для временного development/test extractor;
- валидный `politics.conf`, по умолчанию из текущей директории;
- `VIGILANT_PORT` необязателен, значение по умолчанию - `8080`.

Примеры находятся в [vigilant.conf.example](vigilant.conf.example) и
[politics.conf.example](politics.conf.example). Полный список настроек,
defaults, validation rules и порядок поиска файлов приведены в
[configuration reference](docs/configuration.md).

Невалидная или неполная application/policy configuration печатает безопасную
ошибку в stderr и завершает процесс с кодом `2`.

`DUMMY` запрещён в `production`. После VIG-27 production startup намеренно
невозможен до появления real Bearer extractor в следующем identity increment.

## OCI image

~~~bash
docker build --tag vigilant:0.1.0-SNAPSHOT .

docker run --rm --name vigilant \
  --publish 8080:8080 \
  --stop-timeout 35 \
  --env VIGILANT_UPSTREAM_URL=https://api.openai.com \
  --env VIGILANT_ENVIRONMENT=development \
  --env VIGILANT_IDENTITY_MODE=DUMMY \
  --env VIGILANT_IDENTITY_DUMMY_USER=local-user \
  --env VIGILANT_POLITICS_CONFIG=/etc/vigilant/politics.conf \
  --mount type=bind,src="$PWD/politics.conf",dst=/etc/vigilant/politics.conf,readonly \
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
- Application-owned audit persistence отсутствует: retention, rotation и
  delivery stdout принадлежат container runtime и deployment.
- Prometheus scrape endpoint отсутствует.

Имена метрик, JSONL schema, audit events, внешний stdout pipeline и правила
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

- [Индекс документации](docs/README.md)
- [Покрытие MVP/NFR/Stage 1 требований](docs/requirements-coverage.md)
- [План развития первого производственного этапа](spec/ROADMAP.md)
- [Реестр эпиков и задач](spec/WORK_ITEMS.md)
- [Функции MVP](spec/MVP_FUNCTIONS.md)
- [Нефункциональные требования и стек](spec/MVP_NON_FUNCTIONAL_REQUIREMENTS.md)
- [Функции Stage 1](spec/STAGE_1_FUNCTIONS.md)
- [Функции вне границ продукта](spec/OUT_OF_SCOPE_FUNCTIONS.md)
- [Справочник по конфигурации](docs/configuration.md)
- [Конфигурация и сопоставление политик](docs/policies.md)
- [Контракт запросов OpenAI Chat Completions](docs/openai-chat-completions.md)
- [Контракт обнаружения PII](docs/pii-detection.md)
- [Архитектура](docs/architecture.md)
- [Контракт исполнения](docs/runtime-contract.md)
- [Справочник по наблюдаемости](docs/observability.md)
- [Развёртывание](docs/deployment.md)
- [Разработка](docs/development.md)
- [Базовый профиль нагрузки проверки](docs/inspection-load-result.md)
- [UML-диаграммы 2.0](docs/diagrams/README.md)

Нормативная область хранится в `spec/`. README предназначен для быстрого входа
в проект и не заменяет требования, статусы задач или план развития. Достигнутый
этап теневой проверки PII не означает, что реализована вся целевая область
`MVP-01..21`; точное покрытие приведено в карте требований выше.
