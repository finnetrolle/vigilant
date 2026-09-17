# Plaintext response reasoning evidence

Нормативные owners: [protocol field contract](../spec/requirements/chat-completions-protocol.md#plaintext-response-reasoning)
и [RESPONSE enforcement](../spec/requirements/response-enforcement.md#fragments-gaps-and-reactions).

Наблюдения 2026-09-16 получены через реальные Armeria gateway/upstream servers
с синтетическими Chat Completions payloads. Они подтверждают два paths
`message.reasoning_content` и `delta.reasoning_content`. Live payload
корпоративного LiteLLM не снимался; полная совместимость Filin не проверялась.
Новые production resource owners, настройки или telemetry schema не добавлены.

| Проверяемая граница | Наблюдение и независимый oracle |
|---|---|
| JSON/SSE MASK | Email только в reasoning заменён на literal `[EMAIL_MASKED]`; соседний content `OK` и остальные bytes сохранены. Первоначальный HTTP regression run воспроизвёл unmasked JSON и SSE 502, затем те же tests стали GREEN. |
| Shapes и provenance | Missing/null/empty и reasoning-only формы, number/boolean/object/array и duplicate keys проверены parser + HTTP с включёнными и выключенными policies. Expected kinds, roles, direction, sparse locators и safe 502 заданы явно. |
| ALLOW/BLOCK | Clean, detected и no-policy ALLOW сохраняют literal body, status 200/429/500 и `x-fixture: kept`. No-policy detector counter равен 0, RESPONSE audit отсутствует. BLOCK скрывает весь body, включая чистый или содержащий PII соседний content. |
| Независимые buffers | Разделённые между choices или reasoning/content половины email не дают общий finding. Interleaved continuation одного choice даёт один finding; одинаковые полные email в двух fields дают два. Empty-first/null порядок проверен parser и наблюдаемым порядком detector payloads. |
| Exact rewrite | Два/три events, empty/null между частями, Unicode prefix и JSON escape, LF/CRLF, comments/usage проверены literal expected bytes и Content-Length. Public rewriter получает вручную заданные UTF-8 spans. HTTP ingest barrier разрывает source внутри UTF-8 и escape до подачи остатка. |
| Failures | Detector error/deadline и injected typed rewrite failure дают safe 503 с Retry-After: 1. Truncated JSON и SSE без DONE дают safe 502. Assertions проверяют полные error bodies. |
| Atomicity | До HTTP EOF upstream удерживается даже после SSE DONE, затем detector удерживается отдельным barrier. Наблюдатель не получает headers/body до обоих release; итоговый MASK проверяется literal body. |
| Cancellation | Ingest/analysis cancellation наблюдается у upstream/detector и retained source. Replay ALLOW/MASK отменяется реальным клиентом после получения headers при закрытом body demand; source освобождается, delegate получает cancel, body не запрашивается. |
| Privacy | Reasoning-only ALLOW/MASK/BLOCK/ERROR проверяют одну audit pair, counts/outcome/reaction и отсутствие reasoning sentinels в принимающем log sink, client errors и exported SDK span attributes/events/status. Для каждого trace ожидаются SERVER, CLIENT, request inspection и response inspection spans; проверяется тот же полный snapshot, на котором ожидание завершилось. Это не новая проверка production OTLP exporter или внешнего Collector. |

Основные suites: `ChatCompletionsResponseParserTest`, `JsonResponseRewriterTest`,
`SseResponseRewriterTest`, `JsonResponseEnforcementE2eTest`,
`SseResponseEnforcementE2eTest`, `ResponseInspectionWorkflowTest`,
`ReasoningResponseE2eTest`, `ReasoningResponseLifecycleE2eTest`.

Initial behavioral run `dec3d7b6261545138c94f3f64e75a721` выполнил все
восемь suites и detekt: 83 tests, 0 failures/errors/skips, exit 0.
Review corrections проверены run `74bde075a7154cd480e5563081da39d5`:
6 affected HTTP tests и detekt, exit 0.

После согласования audit type `EMAIL_ADDRESS:1` и усиления privacy
assertions run `6d0bbce8799b4dd8b81a6f126b8b24aa` проверил affected
HTTP matrix, detekt и work-item validation, exit 0. Полный pipeline
`901556931c2240ae9b197c35dda6b214` затем выполнил build, tests,
process tests, detekt, work-item validation и dependency check, exit 0.
Scoped Sonar run `09111bb714244ded8fdc122be2b7ff89` на том же snapshot
не нашёл blocking issues или hotspots в изменённых files.

Терминальный focused run `8b17106c7cee4a23a9347c85101eec77`
на 2026-09-17 повторил все восемь suites и detekt на текущем
committed implementation: 83 tests, 0 failures/errors/skips, exit 0.
Последний pre-closure full build `d604e6218d20471b960cb8415a3d04de`
также завершился с exit 0. Relevant production и test files не менялись
после implementation commit `499c6d1a34e5af80be1014f3858ce4cc789e52df`.
Logs и input snapshots хранятся в локальном `.git/check-runs/`.

Воспроизведение focused contract:

```bash
./scripts/check-run start --label reasoning-focused --timeout 600 -- ./gradlew test -x processTest --tests 'io.vigilant.protocol.openai.ChatCompletionsResponseParserTest' --tests 'io.vigilant.protocol.openai.JsonResponseRewriterTest' --tests 'io.vigilant.protocol.openai.SseResponseRewriterTest' --tests 'io.vigilant.gateway.proxy.JsonResponseEnforcementE2eTest' --tests 'io.vigilant.gateway.proxy.SseResponseEnforcementE2eTest' --tests 'io.vigilant.gateway.proxy.ResponseInspectionWorkflowTest' --tests 'io.vigilant.gateway.proxy.ReasoningResponseE2eTest' --tests 'io.vigilant.gateway.proxy.ReasoningResponseLifecycleE2eTest' detekt
```

Полный build и изменения каталога проверяются по
[project workflow](agent-workflow.md#behavior-first-development-and-selective-tdd).
Дополнительные OCI/load/Pitest observations в этот контракт не входят.
