# Epic 03: Извлечение контекста применения политик

**ID:** `EPIC-03`  
**Тип:** Epic  
**Статус:** Done
**Приоритет:** High  
**Предварительная оценка:** 0 инженерных дней осталось
**Связанные требования:** `MVP-14`, `MVP-20`, `MVP-21`

## Карта декомпозиции

```text
EPIC-03 Policy context extraction
├── Context contract and trust boundary (Done)
├── URL normalization (Done)
├── Anonymous request context (Done, first increment)
├── Identity
│   ├── configurable extraction (Done)
│   └── secret-safe upstream stripping (Done)
├── Context assembly
│   └── HTTP-derived + protocol-derived attributes (Done)
└── Lifecycle
    ├── request-to-response handoff (Done)
    └── end-to-end security behavior (Done)
```

## Дочерние issues

- [x] [VIG-03-01: Контракт и trust boundary](../issues/epic_03/issue_03_01_context_contract.md) - `Done`
- [x] [VIG-03-02: Нормализация URL](../issues/epic_03/issue_03_02_url_normalization.md) - `Done`
- [x] [VIG-03-03: Настраиваемое identity extraction](../issues/epic_03/issue_03_03_identity_extraction.md) - `Done`
- [x] [VIG-03-04: Сборка PolicyContext из нормализованных данных](../issues/epic_03/issue_03_04_model_extraction.md) - `Done`
- [x] [VIG-03-05: Перенос контекста в response phase](../issues/epic_03/issue_03_05_response_handoff.md) - `Done`
- [x] [VIG-03-06: E2E security и upstream stripping](../issues/epic_03/issue_03_06_security_e2e.md) - `Done`
- [x] [VIG-03-07: Anonymous request PolicyContext](../issues/epic_03/issue_03_07_anonymous_request_context.md) - `Done`

## Контекст

Policy engine должен выбирать политики по четырём параметрам:

- URL назначения;
- модели;
- фазе обработки: запрос к модели или ответ модели;
- субъекту запроса: отдельному пользователю или группе.

Эти значения приходят из разных частей HTTP-обмена. URL, phase и identity
принадлежат HTTP context extraction. Значения внутри LLM protocol body или
events, включая model, извлекает отдельный
[EPIC-06](epic_06_llm_message_parsing.md). EPIC-03 получает их в готовом
нормализованном виде и не разбирает raw body повторно.

Пользователь может определяться через Basic Authentication или специальный
HTTP-заголовок. Названия служебных заголовков не могут быть жёстко заданы в
коде и должны настраиваться.

Извлечение и нормализация этих значений отделяются от механизма выбора политик. Policy engine должен получать готовый контекст и не должен самостоятельно разбирать HTTP-запрос, авторизацию или тело сообщения.

## Цель

Определить и реализовать слой, который получает нормализованные HTTP-derived
и protocol-derived данные, строит `PolicyContext` и сохраняет необходимые
значения для последующей проверки ответа.

Нормативный engine contract:

```text
PolicyContext
  url
  model
  phase
  user
  groups
```

`phase` принимает одно из двух значений:

```text
REQUEST
RESPONSE
```

Для ответа должны использоваться URL, модель и субъект, установленные при
обработке соответствующего запроса. Reported model из upstream response не
переопределяет request model. Policy engine и EPIC-03 не восстанавливают эти
значения по телу ответа.

## URL match key

Policy URL является canonical effective upstream URL, а не inbound gateway
address:

```text
lowercase-scheme://lowercase-idna-host[:non-default-port]/normalized-path
```

Configured upstream base path сначала объединяется с inbound request path.
Scheme/host lowercase; terminal DNS dot и default port удаляются. IPv6 literal
валидируется без DNS lookup и записывается в единственной lowercase compressed
форме. Empty path становится `/`, dot segments удаляются, percent escapes
получают uppercase hex, percent-encoded unreserved ASCII декодируется.
Repeated slash, trailing slash, path case и encoded reserved characters
сохраняются. EPIC-04 применяет свой существующий case-insensitive exact matcher
ко всему key.

Query, fragment и user-info не участвуют в matching и не попадают в context,
errors или logs. Unsupported scheme, absent host, malformed percent encoding,
IDNA или port возвращают typed `INVALID_POLICY_URL` без partial context.

## Protocol attributes и missing values

EPIC-06 передаёт immutable versioned input:

```text
NormalizedProtocolAttributes
  model: String
```

Arbitrary attributes map отсутствует. `model` является exact decoded
non-blank request model; normalization model ID не дублируется в EPIC-03.
Missing/invalid model даёт typed assembly failure. Protocol family, operation,
transport, fragments, provenance и inspection gaps не входят в
`PolicyContext`.

Отсутствующая identity представлена явно: `user=null`, `groups=empty immutable
set`. Это valid anonymous context, который совпадает с subject `ANY` и не
совпадает с USER/GROUP по contract EPIC-04.

## Identity modes и trust boundary

Strict config выбирает ровно один mode:

```text
ANONYMOUS
TRUSTED_HEADERS(userHeader?, groupsHeader?, trustedCidrs[])
BASIC
```

Modes взаимоисключающие, поэтому cross-source precedence отсутствует.
`TRUSTED_HEADERS` принимает values только от immediate socket peer внутри
configured CIDR. `Forwarded`, `X-Forwarded-For` и подобные client-controlled
headers не расширяют boundary. Supplied configured identity header от
untrusted peer даёт `UNTRUSTED_IDENTITY` и запрещает forwarding.

User/group IDs используют ASCII grammar
`[A-Za-z0-9][A-Za-z0-9._:@/\-]{0,127}` и Locale.ROOT lowercase. Groups
передаются comma-separated с optional OWS, допускают repeated header lines,
deduplicate-ятся и ограничены 128 values. User имеет максимум одно value.

`BASIC` strict-decodes Base64 bytes, берёт ASCII username до первого `:` и
никогда не преобразует password bytes в retained String. Отсутствие identity
value даёт anonymous identity; duplicate, malformed или contradictory values
дают typed safe failure. Configured Vigilant-only identity headers всегда
strip-ятся upstream. `Authorization` strip-ится этой capability только когда
consumed как BASIC identity; upstream authentication остаётся отдельной
integration responsibility.

## Immutable assembly и handoff

Assembler получает только normalized URL, explicit phase, normalized identity
и `NormalizedProtocolAttributes` и возвращает existing immutable
`PolicyContext` EPIC-04. Он не зависит от `PolicyProvider`, не принимает raw
HTTP/body types и не выполняет parsing или matching.

Request snapshot создаётся один раз и сохраняется typed attribute внутри
соответствующего Armeria `ServiceRequestContext`. Response handoff читает тот
же snapshot и создаёт context, меняя только `phase=RESPONSE`.
Thread-local/global maps и повторное extraction запрещены. Completion, error,
timeout и cancellation завершают request scope и не оставляют retained
context reference.

Первый production increment был поставлен отдельной VIG-03-07 как anonymous
`REQUEST` context из normalized URL и Chat Completions request model. После
завершения identity leaves production path использует полный assembler из
VIG-03-04.

## Нормативные требования

- URL назначения приводится к согласованному нормализованному виду.
- Модель и любые другие body-derived attributes принимаются из результата
  EPIC-06 и не извлекаются в EPIC-03.
- Фаза определяется точкой вызова policy engine, а не содержимым payload.
- Пользователь и группы извлекаются настраиваемым способом.
- Названия HTTP-заголовков с identity-данными задаются через конфигурацию.
- Должна быть предусмотрена возможность извлечения пользователя из Basic Authentication.
- Служебные identity-заголовки не должны передаваться upstream, если они предназначены только для Vigilant.
- Пароли, токены и исходные значения credentials не должны попадать в контекст политики, ошибки или логи.
- Результат сборки context не зависит от конкретной реализации
  `PolicyProvider`.

## Не входит в задачу

- Выбор подходящих политик.
- Разрешение `overrides` между политиками.
- Запуск детекторов.
- Выбор итоговой реакции.
- Хранение политик в файле или БД.
- Проверка существования пользователя или группы во внешнем каталоге.
- Разбор JSON, SSE, Realtime events или Batch JSONL.
- Извлечение payload fragments или любых attributes из protocol message.

## Отложенные решения

- Поддержка нескольких одновременных identity modes требует отдельного
  precedence contract и не добавляется скрыто.
- Trusted proxy chains требуют verified proxy protocol или отдельной
  forwarded-header trust model; immediate peer остаётся единственным authority.
- Response body inspection остаётся отдельной capability; immutable
  request-scoped handoff уже реализован и готов для её использования.

## Критерии приёмки epic

- Policy engine получает единый нормализованный контекст без доступа к HTTP headers или сырому request body.
- EPIC-03 не получает raw body, protocol events или protocol-specific document
  model и не извлекает из них attributes.
- Одинаковые логические значения из поддерживаемых источников дают одинаковый контекст.
- Контекст ответа содержит URL, модель, пользователя и группы исходного запроса.
- Названия служебных identity-заголовков не зашиты в коде.
- Чувствительные authentication-данные не передаются upstream и не попадают в логи.
- Каждый active leaf использует выбранные contracts без скрытого fallback.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ exact context and first anonymous slice fixed
  Acceptance:   0.10  ✓ URL, identity and handoff outcomes observable
  Boundaries:   0.05  ✓ protocol parsing and matching excluded
  Alternatives: 0.10  ✓ identity modes and immediate-peer trust selected
  Assumptions:  0.15  ✓ future response uses established request scope
  ──────────────────────────────
  Aggregate:    0.08  ✓ below threshold (0.2 spec)
```
