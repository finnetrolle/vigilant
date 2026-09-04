# VIG-20-01: In-memory response source

**Статус:** Done
**Epic:** [EPIC-20](../../epics/epic_20_atomic_in_memory_response_analysis.md)
**Ветка:** Response source > ordinary response and SSE lifecycle
**Зависит от:** [VIG-06-03](../epic_06/issue_06_03_chat_completions_response_parser.md), [VIG-20-04](issue_20_04_retained_memory_response_contract.md)
**Блокирует:** [VIG-20-02](issue_20_02_response_inspection_enforcement.md) и [VIG-20-05](issue_20_05_sse_response_enforcement.md)
**Оценка:** 3-5 инженерных дней; confidence Medium

## Цель

Добавить временный retained in-memory response source для upstream Chat
Completions response, включая SSE. Guardrail-enabled response не раскрывается
клиенту до получения complete source и последующего policy decision.

Source не является audit storage и не сохраняет payload после terminal cleanup.
Он существует только чтобы MVP мог реально анализировать response до client
disclosure.

## Принятые решения

- Используется только RAM; disk spill, temporary files, encryption, recovery и
  persistent response storage не входят в MVP.
- Application-level raw/text byte limit и shared response capacity отсутствуют.
  Response source может использовать доступный JVM heap. После каждого
  terminal outcome он освобождает все owned buffers и references; дальнейшее
  освобождение памяти принадлежит JVM GC. Heap sizing и runtime OOM policy
  принадлежат deployment и не являются application memory safety guarantee.
- Ordinary response и SSE удерживаются полностью. Для SSE клиент не получает
  status, headers или event byte до terminal event и policy decision.
- Supported OpenAI Chat Completions SSE считается complete только после
  отдельного `data: [DONE]` event. Ordinary non-streaming response считается
  complete при end-of-stream. Missing/malformed `[DONE]`, malformed event или
  upstream error являются safe technical failure без partial client disclosure.
- Malformed upstream response/SSE, upstream protocol error или missing
  `data: [DONE]` возвращают `502` with safe
  `invalid_upstream_response` OpenAI-compatible error; upstream status,
  headers и partial body не раскрываются.
- Любой upstream Chat Completions response, включая `4xx`/`5xx`, удерживается
  и передаётся response inspection. Valid response получает normal policy
  outcome; malformed protocol response применяет safe `502` contract выше.
- Client cancellation before final policy decision cancels upstream read and
  analysis, then releases every response buffer/reference; no
  `analysis_completed` event is created. Upstream interruption returns safe
  `502` while client remains connected, then releases ownership. Shutdown
  starts no new response analysis and cancels active source after drain
  deadline, releasing every response buffer/reference.
- Source сохраняет exact original bytes для `ALLOW` replay. Ownership cleanup
  обязателен при success, policy rejection, parse/error, cancellation, upstream
  failure и shutdown.
- Payload, preview, temporary object identity, path и derived hash не попадают
  в stdout, metrics, traces или errors.

## Открытые решения

Нет. JVM heap sizing и runtime OOM policy deployment выбирает вне application;
gateway не вводит свой response quota или admission rejection.

## Не входит

- На момент реализации VIG-20-01 response policy evaluation,
  `ALLOW`/`MASK`/`BLOCK` integration и response audit pair оставались за
  следующими enforcement leaves; ordinary JSON path позднее закрыт VIG-20-02,
  SSE path позднее закрыт VIG-20-05.
- Audit persistence, WAL, file handoff, Collector protocol, disk spill или
  external object storage.
- Изменение завершённого request source EPIC-08.

## Критерии готовности

- [x] Real Armeria E2E tests prove ordinary response and SSE retain every byte
  until normal terminal state; client receives neither status, headers nor body
  before source completion.
- [x] SSE accepts only standalone `data: [DONE]`; missing/malformed terminal,
  malformed protocol and upstream interruption return exact safe `502`
  `invalid_upstream_response` without partial disclosure.
- [x] Source uses no audit file, disk spill, temporary file, application-level
  response quota or shared response capacity admission.
- [x] Every terminal path owned by this source leaf, including exact unmodified
  replay, protocol rejection, cancellation, upstream interruption and shutdown,
  releases all source-owned buffers/references. Response `MASK`/`BLOCK` paths and
  `analysis_completed` emission remain acceptance scope of the enforcement leaves.
- [x] New and modified Kotlin declarations/test methods have current KDoc;
  focused E2E suite and `./gradlew build` pass.

## Evidence

Evidence зафиксирован 2026-09-03:

- Behavioral RED: real Armeria test `ordinary response is retained completely
  before client disclosure` показал early response headers до upstream
  EOF до production изменения.
- `rtk proxy ./gradlew test --tests
  'io.vigilant.gateway.proxy.PiiShadowProxyServiceTest' --tests
  'io.vigilant.source.RetainedResponseSourceTest'` завершился `BUILD
  SUCCESSFUL`: 43 focused source/lifecycle tests.
- Full application suite в `rtk proxy ./gradlew build` выполнил 517
  tests без application-test failures; final build завершился
  `BUILD SUCCESSFUL` после синхронизации validator contract.
- `rtk proxy ./gradlew detekt` завершился `BUILD SUCCESSFUL`.
- `rtk proxy ./gradlew validateWorkItems` сообщил `Work-item graph is
  valid.`; `rtk proxy git diff --check` завершился без ошибок.

## Ambiguity Report

```text
Goals:        0.0   response source lifecycle defined
Acceptance:   0.10  deterministic E2E matrix explicit
Boundaries:   0.0   no persistence/quota path
Alternatives: 0.10  disk spill and quota rejected
Assumptions:  0.10  JVM heap/OOM remains deployment-owned
Aggregate:    0.06  Ready for implementation.
```
