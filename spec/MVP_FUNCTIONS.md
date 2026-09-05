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

## MVP-02. Fast PII

Единственный detector MVP: встроенный deterministic `fast-pii`. Он всегда
проверяет полный фиксированный набор: email, российский телефон, банковскую
карту, IP-адрес, IBAN, ИНН физического лица, СНИЛС, паспорт РФ и ОМС.

Политика не выбирает отдельные PII types. Она включает весь detector либо не
применяется. Только text content является inspectable; изображения, аудио,
файлы и unknown content blocks передаются без изменения с safe inspection gap.

## MVP-03. Реакции policy

Каждая применимая policy явно задаёт одну реакцию на detection:

- `ALLOW` передаёт text без изменения;
- `MASK` заменяет только exact PII spans на необратимые typed markers, например
  `[EMAIL_MASKED]` и `[CARD_MASKED]`;
- `BLOCK` не передаёт трафик дальше.

Любая применимая `BLOCK` policy блокирует весь message. Иначе `MASK` изменяет
только PII spans, найденные `MASK` policies; `ALLOW` не изменяет text. Mutation
сохраняет valid OpenAI JSON, unknown fields и всю нетронутую структуру.

Точный HTTP status и OpenAI-compatible error body для `BLOCK` принадлежат
[VIG-29](issues/issue_29_openai_error_contract.md).

## MVP-04. Policies и группы

`politics.conf` загружается и строго валидируется при startup. Каждая policy
имеет unique `id`, `direction` (`REQUEST`, `RESPONSE` или `BOTH_WAYS`), deadline,
reaction и список groups. Group selector использует exact group name либо `*`;
matching работает по `ANY`.

Request и response выбирают независимые policy sets из одного immutable startup
snapshot и одной resolved identity. Пользователь без совпавшей policy проходит
без PII inspection. При startup полный загруженный policy file записывается в
technical logs для расследований.

## MVP-05. Identity

External Identity Extractor получает пользователя и groups у сторонней системы
по Bearer token. Token остаётся end-to-end credential клиента и передаётся
upstream byte-for-byte. Vigilant временно использует его только для lookup и
никогда не помещает token в audit, logs, metrics, traces или errors.

Результат lookup кэшируется отдельной bounded capability. Cache miss, timeout
или failure identity lookup не дают обойти policy: request получает `503`.
Детали extractor и cache принадлежат [VIG-30](issues/issue_30_external_identity_extractor.md)
и [VIG-31](issues/issue_31_identity_lookup_cache.md).

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
REQUEST pair принадлежит
[VIG-32-01](issues/epic_32/issue_32_01_stdout_request_audit_migration.md),
RESPONSE pair -
[VIG-20-02](issues/epic_20/issue_20_02_response_inspection_enforcement.md).
Текущая REQUEST inspection и ordinary JSON/SSE RESPONSE enforcement публикуют
эту pair до transport handoff и не ждут durable acknowledgement.

## MVP-07. Минимальная интеграция

Vigilant является OpenAI-compatible gateway только для Chat Completions.
Клиент подключает его заменой `base_url`; Bearer token прозрачно достигает
LiteLLM или другого configured LLM upstream. Другие OpenAI APIs не входят в
MVP.

## Источники сравнения

- [HiveTrace - руководство пользователя](https://hivetrace.ru/documents/hivetrace_user_guide.pdf)
- [NVIDIA NeMo Guardrails](https://docs.nvidia.com/nemo/guardrails/about-nemo-guardrails-library/rail-types)
- [Guardrails AI](https://guardrailsai.com/guardrails/docs)
- [Meta LlamaFirewall](https://github.com/meta-llama/PurpleLlama/tree/main/LlamaFirewall)
- [OpenAI Agents SDK Guardrails](https://openai.github.io/openai-agents-js/guides/guardrails/)
