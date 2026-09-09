# Vigilant: функции MVP

## Цель этапа

Vigilant MVP является traffic guardrail для LLM. Он анализирует и при
необходимости изменяет или прерывает информационный поток между клиентом и LLM,
не управляя агентом или выполнением его инструментов.

## MVP-01. Enforcement обоих направлений

Vigilant синхронно проверяет OpenAI Chat Completions request до передачи
upstream и response до передачи клиенту. Для streaming response он удерживает
весь ответ в retained in-memory response source до terminal event и решения
policy. Response source не имеет application-level limit или shared quota;
heap sizing и runtime OOM policy принадлежат deployment.
Полная atomic/lifecycle matrix принадлежит
[RESPONSE enforcement](requirements/response-enforcement.md#atomic-boundary).

## MVP-02. Fast PII

Единственный detector MVP: встроенный deterministic `fast-pii`. Он всегда
проверяет полный фиксированный набор: email, российский телефон, банковскую
карту, IP-адрес, IBAN, ИНН физического лица, СНИЛС, паспорт РФ и ОМС.

Политика не выбирает отдельные PII types. Она включает весь detector либо не
применяется. Только text content является inspectable; изображения, аудио,
файлы и schema-recognized provider-opaque content передаются без изменения с
safe inspection gap. Unknown content discriminators и ambiguous shapes
отклоняются по [protocol contract](requirements/chat-completions-protocol.md).

Точные formats, boundaries, validation, findings и quality gates принадлежат
[Fast PII](requirements/fast-pii.md#taxonomy); capability, UTF-8 offsets,
aggregation и adapter semantics - [windowed inspection](requirements/windowed-inspection.md#contract).

## MVP-03. Реакции policy

Каждая применимая policy явно задаёт одну реакцию на detection:

- `ALLOW` передаёт text без изменения;
- `MASK` заменяет только exact PII spans на необратимые typed markers, например
  `[EMAIL_MASKED]` и `[CARD_MASKED]`;
- `BLOCK` не передаёт трафик дальше.

Любая применимая `BLOCK` policy блокирует весь message. Иначе `MASK` изменяет
только PII spans, найденные `MASK` policies; `ALLOW` не изменяет text. Mutation
сохраняет valid OpenAI JSON, unknown fields и всю нетронутую структуру.

Для request действует согласованное ограничение
[REQUEST enforcement](requirements/request-enforcement.md#field-classification):
если выбранный `MASK`
затрагивает структурное содержимое, весь request блокируется вместо его
переписывания. Это имена инструментов, участников и схем; model-visible ключи
JSON Schema; modern/deprecated function arguments и custom tool input;
строковые `enum`, `const`, `default`, `pattern`; грамматики инструментов и поля
местоположения. Вложенный JSON или другой язык не разбирается ради masking.

Маскируется свободный текст: сообщения и результаты инструментов, описания,
заголовки, примеры, имена файлов, открытый reasoning и predicted output.
Точный конечный перечень recognized fields принадлежит REQUEST enforcement.
Наличие структурной находки только у `ALLOW` policy не вызывает блокировку.

При request `MASK` replacement занимает не больше UTF-8 bytes, чем selected
decoded PII span. Помещающийся полный marker сохраняется; иначе внутреннее
слово обрезается справа с сохранением brackets. Для span длиной 1 или 2 bytes
используются `*` или `**`. Заменяется весь PII span; сохранять часть исходного
PII ради длины запрещено. Остальные raw JSON bytes не меняются, поэтому
masked body не больше исходного и не отклоняется из-за роста marker.
Полные типы findings сохраняются в safe aggregate metadata. Точный
[rendering contract](requirements/request-enforcement.md#request-marker) не
меняет текущую RESPONSE representation.

В REQUEST policy реакция `clean` обязательна и допускает только `ALLOW` без
transformations. Это вклад успешно завершённой чистой проверки одного
fragment; он не отменяет `MASK` или `BLOCK` от других проверок. Невалидный
`clean` отклоняется при startup, в том числе у disabled policies. Доменный
engine и текущий RESPONSE contract сохраняют свои возможности; ограничение
исполняемого REQUEST path определено
[постоянным owner](requirements/request-enforcement.md#request-reactions-and-priority).

В REQUEST policy `error` обязательна и допускает только `BLOCK` без
transformations. Ошибка или timeout настроенной проверки возвращает HTTP inspection error
`503` с `Retry-After: 1` до upstream handoff; обнаружение PII при этом не
утверждается. `error = ALLOW` отклоняется при startup, в том числе у disabled
policies. Переход на текущий contract требует явного обновления прежних shadow policy
files; автоматический fallback на пропуск запроса отсутствует. Текущая
RESPONSE configuration semantics этим request contract не изменяется.

Точный HTTP status и OpenAI-compatible error body для `BLOCK` принадлежат
[HTTP inspection errors](requirements/http-gateway.md#inspection-error-matrix).

При request aggregation technical error/timeout имеет приоритет над policy
`BLOCK` и structural `MASK`: итог `503`. При отсутствии technical failure
любой блокирующий результат даёт `403`; иначе применяются текстовые masks
или original replay. Это
[согласованный REQUEST priority](requirements/request-enforcement.md#request-reactions-and-priority),
а не новый response contract.

## MVP-04. Policies и группы

`politics.conf` загружается и строго валидируется при startup.
[Policy engine](requirements/policy-engine.md#startup-snapshot-and-schema)
сохраняет полную schema: unique `id`, `version`, `enabled`, `match`,
`detectors`, `deadline`, `reactions` и `overrides`. Matching включает URL,
model, phase `REQUEST|RESPONSE` и subject `USER|GROUP|*`; existing exact/wildcard
rules и simultaneous overrides сохраняются. Замена этой schema на
`direction`/`BOTH_WAYS` или упрощённый список groups не входит в текущий MVP.

Request и response выбирают независимые policy sets из одного immutable startup
snapshot и одной resolved identity. Применение проверки выбирает администратор:
явный `policies = []` допустим, global coverage не требуется, без applied
PII policy `fast-pii` не запускается. Identity, protocol validation и source
admission при этом сохраняются. Файл остаётся обязательным; его полное
содержимое не записывается в logs, используются safe policy references и
validation diagnostics согласно audit/privacy contract. Selection и startup
coverage принадлежат
[REQUEST enforcement](requirements/request-enforcement.md#selection), а
наблюдённое implementation evidence хранится в
[closure ledger](../docs/request-enforcement-evidence.md).

## MVP-05. Identity

External Identity Extractor получает пользователя и groups у сторонней системы
по Bearer token. Token остаётся end-to-end credential клиента и передаётся
upstream byte-for-byte. Vigilant временно использует его только для lookup и
никогда не помещает token в audit, logs, metrics, traces или errors.

Результат lookup кэшируется отдельной bounded capability. Cache miss ожидает
новый Bridge lookup; timeout или failure lookup возвращает `503`, не позволяя
обойти policy или использовать stale identity.
Детали mode selection, extractor, cache и handoff принадлежат
[постоянному identity/context owner](requirements/identity-and-context.md).

External lookup дополняет, а не заменяет offline JWT validation. Обязательный
startup selector выбирает ровно одну реализацию общего async и
cancellation-aware `BearerIdentityExtractor`: `DUMMY`, `JWT` или `EXTERNAL`.
`DUMMY` разрешён только в `development`/`test`; JWT и `EXTERNAL` разрешены во
всех environments. Fallback, композиция modes и runtime switching отсутствуют.
Cache используется только `EXTERNAL` mode.

## MVP-06. Безопасный best-effort audit через stdout

Audit публикует safe structured JSON event через existing non-blocking Logback
stdout path и не участвует в admission, policy decision, readiness или traffic
forwarding. Для каждого направления analysis lifecycle состоит из
`policy.analysis_started` и `policy.analysis_completed`. Events содержат phase,
trace/span correlation, selected detector references, applied policy/reaction,
safe outcome и aggregate PII counts; payload, PII values/spans, Bearer, user ID,
groups, headers и identity запрещены.

Единственная queue - existing Logback `AsyncAppender` with `neverBlock=true`.
Event может быть потерян при overload или stdout failure; это не меняет traffic.
Application не создаёт file/WAL/Collector/own queue/worker/metric/drop alert.
Общая schema, triggers, privacy и delivery boundary принадлежат
[observability contract](requirements/observability.md#analysis-lifecycle-audit),
а RESPONSE application boundary -
[RESPONSE enforcement](requirements/response-enforcement.md#fragments-gaps-and-reactions).
Текущая REQUEST inspection и ordinary JSON/SSE RESPONSE enforcement публикуют
эту pair до transport handoff и не ждут durable acknowledgement.

## MVP-07. Минимальная интеграция

Vigilant является OpenAI-compatible gateway только для Chat Completions.
Клиент подключает его заменой `base_url`; Bearer token прозрачно достигает
LiteLLM или другого configured LLM upstream. Другие OpenAI APIs не входят в
MVP. Descriptor, field maps и terminal semantics принадлежат
[Chat Completions owner](requirements/chat-completions-protocol.md).

## Источники сравнения

- [HiveTrace - руководство пользователя](https://hivetrace.ru/documents/hivetrace_user_guide.pdf)
- [NVIDIA NeMo Guardrails](https://docs.nvidia.com/nemo/guardrails/about-nemo-guardrails-library/rail-types)
- [Guardrails AI](https://guardrailsai.com/guardrails/docs)
- [Meta LlamaFirewall](https://github.com/meta-llama/PurpleLlama/tree/main/LlamaFirewall)
- [OpenAI Agents SDK Guardrails](https://openai.github.io/openai-agents-js/guides/guardrails/)
