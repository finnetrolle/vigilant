# Покрытие требований документацией и реализацией

Нормативные [owners и 55 stable IDs](../spec/requirements/README.md) отделены от
каталога открытой работы. Перенос документации не меняет production behavior и не создаёт нового dynamic
evidence; карта уточняет ранее завышенные claims по source/test review.
Completion проверяется по
[project protocol](../CLAUDE.md#work-item-completion).


## Назначение

Эта карта отделяет согласованный MVP contract от текущей runtime реализации.
Нормативные требования принадлежат [MVP functions](../spec/MVP_FUNCTIONS.md),
[MVP NFR](../spec/MVP_NON_FUNCTIONAL_REQUIREMENTS.md), [Stage 1](../spec/STAGE_1_FUNCTIONS.md)
и [product non-goals](../spec/OUT_OF_SCOPE_FUNCTIONS.md).

Статусы: `Работает` означает полный runtime contract, `Частично` означает
полезную основу без полного contract, `Не реализовано` означает отсутствие
возможности.

## Функциональные требования MVP

| ID | Статус | Текущий факт |
|---|---|---|
| `MVP-01` | Работает | Request ALLOW/MASK/BLOCK применяется до upstream; ordinary JSON/SSE response проходит atomic enforcement до client disclosure. |
| `MVP-02` | Частично | Все девять типов и UTF-8-safe windowing подключены к request, ordinary-response и SSE-response fragments. Полная conformance/evidence matrix имеет перечисленные ниже gaps; миграция документации их не закрывает. |
| `MVP-03` | Работает | Request и ordinary/SSE response применяют ALLOW/MASK/BLOCK. Request structural MASK блокирует целиком, markers сокращаются до decoded UTF-8 budget, technical failure выше policy BLOCK. |
| `MVP-04` | Работает | Immutable strict startup snapshot сохраняет URL/model/phase/USER/GROUP matching и overrides. Empty/disabled/unmatched selection не запускает detector; implicit global policy отсутствует. |
| `MVP-05` | Частично | Startup-selectable `DUMMY`, offline `JWT` и trusted Bridge `EXTERNAL` реализуют общий async cancellation-aware contract. EXTERNAL использует Caffeine с process-local HMAC keys, configurable write TTL/size, bounded coalescing, независимой cancellation и fail-closed refresh по следующему request. Strict startup имеет описанный ниже gap для explicit empty settings чужого mode. |
| `MVP-06` | Работает | REQUEST и ordinary JSON/SSE RESPONSE analysis публикуют safe best-effort started/completed pair через existing non-blocking stdout без application-owned persistence. |
| `MVP-07` | Частично | Chat Completions request и response JSON/SSE parsing/enforcement реализованы; точные protocol conformance/evidence gaps приведены ниже. Другие OpenAI APIs остаются вне MVP. |

## Нефункциональные требования MVP

| ID | Статус | Текущий факт |
|---|---|---|
| `PERF-01` | Не реализовано | Есть bypass/shadow benchmark, но нет отдельного request/response enforcement latency evidence с новым profile. |
| `PERF-02` | Частично | Existing reports фиксируют warmup и hardware; новый non-streaming profile и warm/mock identity setup отсутствуют. |
| `PERF-03` | Работает | Per-policy request и ordinary/SSE response deadlines дают fail-closed 503 без reaction fallback. |
| `CONC-01` | Частично | Request source и windowing bounded; retained response source использует one-item upstream demand и terminal cleanup, но по принятому MVP contract не имеет application-level limit или shared quota. Heap sizing и runtime OOM policy принадлежат deployment. |
| `CONC-02` | Частично | Existing request capacity даёт typed failure; response capacity намеренно отсутствует, response source освобождает ownership на всех terminal paths. |
| `CONC-03` | Работает | CPU inspection, response parsing и identity orchestration изолированы от event loop; External HTTP остаётся async, bounded и cancellation-aware. |
| `CONC-04` | Работает | Request и ordinary/SSE response ingest, analysis, transport cancellation и graceful/forced shutdown имеют bounded causal evidence. Отдельные gaps наблюдения identity-handoff snapshot cleanup перечислены в [identity evidence](#identity-evidence). |
| `PROXY-01` | Работает | Ordinary JSON и SSE удерживаются до EOF/standalone `[DONE]` и final policy decision, после чего атомарно применяют `ALLOW`/`MASK`/`BLOCK`. |
| `PROXY-02` | Работает | Request и ordinary JSON/SSE поддерживают byte-identical `ALLOW` и exact-span source-patched `MASK` с lossless preservation незатронутых bytes и header rewrite. |
| `PROXY-03` | Работает | Все пять [inspection outcomes](../spec/requirements/http-gateway.md#inspection-error-matrix) подключены: request policy/structural BLOCK и technical refusal запрещают handoff; response errors исключают partial disclosure. |
| `OBS-01` | Частично | Base HTTP metrics/tracing, REQUEST/RESPONSE audit pairs и External lookup/cache telemetry реализованы. Dedicated inspection OTel instruments отсутствуют, а proxy transport `error.type` не имеет finite allowlist; exact gaps приведены в [observability evidence](#observability-evidence). |
| `OBS-02` | Частично | Audit pairs, client errors и application logs имеют channel-specific privacy controls; session/query-free path и valid propagation разрешены только operational MDC/traces. SERVER и upstream CLIENT spans всё ещё записывают raw exception events при transport failure, поэтому all-channel target не подтверждён. |

## PII и windowing

Permanent owners: [recognition и quality](../spec/requirements/fast-pii.md),
[generic core и adapters](../spec/requirements/windowed-inspection.md).
Source/test review не является новым dynamic run. Команды и обязательная
matrix находятся в [development guide](development.md#pii-contract-checks).

| Target / invariant | Фактический source/test contract | Gap / evidence boundary |
|---|---|---|
| Fixed taxonomy, offsets, metadata, direct ordering, preflight/cancellation | `FastPiiDetector` и recognizer/public contract tests; canonical synthetic gate содержит по 100 positive/negative cases на type | ИНН поддержан только для физлиц, 12 digits; прежнее обещание 10 digits в runtime guide было ошибкой документации. Нового runtime run при переносе нет. |
| IPv4 перед decimal port 1..65535 | `IpAddressCandidateBoundaryResolver.findIpv4EndBeforeTerminalPort` требует `candidateEnd == payload.length`; `IpAddressRecognizerTest` проверяет terminal-port cases | Согласованное правило отделения port не ограничено концом payload. Port с последующим prose/whitespace не подтверждён и текущим resolver не принимается; target не сужен. |
| Plausible checksum-invalid SNILS под whole-word context | `SnilsCandidateRules.evidenceStrength` применяет issuance threshold до обоих evidence paths; tests сохраняют exact `снилс`, weak/partial keywords отвергают | Source contract явно сохраняет threshold для validated path, но не уточняет его применение к contextual path. Plausible contextual candidate ниже threshold не подтверждён; автоматического расширения surface нет. |
| Finite evidence span для каждой current surface | `FastPiiWindowCapability.VERSIONED`: W=1 MiB, E=4096, context=4095 с каждой стороны; KDoc proof ссылается на старый 254-code-point email bound | Proof не перечисляет expanded email gaps/IDN source length, contextual phone/SNILS/OMS и новые IP boundaries. Полнота для текущих recognizer versions требует отдельного conformance evidence, без изменения bound при миграции. |
| Каждая surface через ownership boundary и все Unicode widths | `WindowedFastPiiExecutorTest` содержит 30 исходных surface cases по всем 9 types; отдельный email case проверяет widths 1/2/3/4. Generic synthetic tests проверяют I/F/K, errors, ordering, cancellation, bounded execution | Window corpus не включает email gaps, national/contextual и Unicode phone, IP terminal punctuation/port, expanded/contextual SNILS/OMS. Direct recognizer/canonical tests этих forms не заменяют cross-window tests. |
| Complete error/adapter/lifecycle contract | `WindowedInspectionExecutorTest`, `WindowedFastPiiExecutorTest`, `FastPiiPolicyAdapterTest` проверяют typed errors, immutable aggregate, CPU handoff и cancellation; generic UTF-8 preflight также отвергает length > Int.MAX_VALUE как INVALID_FRAGMENT | Это ограничение текущей реализации, не новый global response/request limit. Exhaustive test-method matrix остаётся требованием; reviewed tests не объявляются новым process/load evidence. |
| Canonical и external quality floors | Canonical/report/scorer tests и `piiQualityQualification` имеют отдельные models/views. Qualification код проверяет aggregate, IP recall и evaluation floors, публикует per-type deltas | PHONE precision >=0.90 и IP precision не ниже baseline требуют отдельной проверки per-type report: aggregate `passed` не автоматизирует эти два условия. Historical qualification не равна свежему report. |
| Current PERF-01/02 | JMH измеряет sync detect; existing inspection/load reports имеют собственный shadow profile | Ни старые numeric results, ни новые ссылки не доказывают current request/response enforcement latency. Статусы PERF-01/02 выше сохранены. |

Неподтверждённые cases сохраняются как requirements. Их исправление/новая
qualification не выполняются в documentation-only migration; source, corpora,
report calculations и runtime defaults остаются неизменными.

## Policy, request source and request enforcement

Нормативные owners: [policy engine](../spec/requirements/policy-engine.md),
[bounded request source](../spec/requirements/request-source.md) и
[REQUEST enforcement](../spec/requirements/request-enforcement.md).
Эта таблица основана на current source/test review и сохранённом
[implementation evidence](request-enforcement-evidence.md). Миграция не
перезапускала process/OCI/load и не создаёт нового dynamic evidence.

| Target / invariant | Фактический source/test contract | Gap / evidence boundary |
|---|---|---|
| Strict complete policy schema, immutable startup snapshot, exact matching, simultaneous overrides and deterministic ordering | `PolicyConfigParser`, `PolicyValidator`, `PolicySelector`, `PolicyEngine` и focused tests покрывают empty/disabled/unmatched/selected/override states, validation graph и ordering | Старое mandatory global shadow coverage является superseded, а не gap. Runtime capability не повышена этой публикацией; сохранённые final build observations датированы implementation run. |
| Detector result invariants, deduplicated parallel execution, independent policy deadlines, last-consumer cancellation and deterministic BLOCK explanation | `DetectorExecutor`, `DetectorExecutionCoordinator`, `ReactionAggregator` и controlled tests наблюдают invocation counts, barriers, timeout consumers, cancellation и result ordering | Existing evidence подтверждает current implementation, но не новый latency/load claim. PERF-01/02 остаются в прежнем статусе. |
| Bounded owner/byte/segment ingest, one active view/replay lease, exact original/patched replay and every terminal cleanup | `RequestSourceQuota`, `BoundedRequestSourceOwner`, `BoundedRequestSourceTest` и `ReplayReadyRequestTest` наблюдают literal bytes, demand, counters, illegal interleavings и callback ownership | Runtime default 8 MiB на request не подтверждает target 16 MiB inspectable text / 20 MiB raw. Historical 64 KiB и 8 MiB profiles не становятся qualification всей target surface. |
| Explicit REQUEST selection and ALLOW/MASK/BLOCK priority, exhaustive field classes, marker budgets and non-expanding raw patches | `PolicyConfigurationLoadingTest`, parser/formatter/planner suites и real-Armeria `RequestInspectionE2eTest` содержат named selection, reaction, field/root/container, overlap/gap/header matrices с literal oracles | Implementation observations сохранены с exact commands/results; новый process/OCI run не выполнялся. Protocol semantic-kind gaps ниже не ослабляют structural enforcement classification. |
| One-shot workflow/handoff, cancellation, peer close, shutdown, safe audit/metrics/tracing | `ShadowInspectionWorkflow`, `ReplayReadyRequest`, gateway/process fixtures и recorded implementation ledger наблюдают both race orders, zero pre-handoff upstream, quota return and allowed partial-prefix boundary | Permanent audit owner опубликован: requirements и `docs/observability.md` владеют runtime schema/privacy. Новой availability или performance guarantee нет. |

## Response and gateway evidence

Нормативные owners: [RESPONSE enforcement](../spec/requirements/response-enforcement.md)
и [HTTP gateway](../spec/requirements/http-gateway.md). Таблица основана на
current source/test review и сохранённых implementation observations. Эта
documentation migration не перезапускала runtime, process, OCI или load suites
и не создаёт нового dynamic evidence.

| Target / invariant | Фактический source/test contract | Gap / evidence boundary |
|---|---|---|
| Complete ordinary/SSE source и protocol-valid terminal до первого disclosure; exact `ALLOW`, source-patched `MASK`, whole-response `BLOCK`, safe `502`/`503` | `JsonResponseEnforcementE2eTest` и `SseResponseEnforcementE2eTest` используют causal upstream-terminal и detector barriers, отдельно наблюдают headers/body, literal outcome и retained cleanup | Existing observations подтверждают current implementation; encoder tests сами по себе retention не доказывают. Нового прогона при переносе нет. |
| Все ordinary fragments, gap/reaction precedence, JSON source coordinates и exact rewrite | Parser/rewriter suites и real-Armeria JSON matrix покрывают content, refusal, modern/deprecated arguments, transcript/audio gap, `200`/`429`/`500`, Unicode/escapes/unknown metadata и invalid mapping | Полная nested type/segmentation cross-product matrix имеет gaps, перечисленные в [protocol evidence](#protocol-evidence). Contract не сужен. |
| Все SSE logical fields, standalone terminal и cross-event source patching | Parser/SSE rewriter suites покрывают interleaved choices/tools, cross-event spans, adjacent/overlapping instructions, LF/CRLF, comments, multi-line data, empty values, Unicode/escapes и deterministic invalid mappings | Полный field branch x every byte boundary cross-product и parser-local cancellation after each partial field не подтверждены; HTTP atomic outcomes покрываются отдельно. |
| Retained source и one-shot ownership на success/reject/failure/cancellation/shutdown | `RetainedResponseSourceTest`, `ReplayReadyResponseTest` и JSON/SSE E2E наблюдают one-item demand, view/replay exclusivity, both transfer/cancel race orders, callback failures, ingest/analysis/replay cancellation и forced cleanup | `runAllCleanupActions` проверен с first/suppressed failures; failure injection во все реальные network/resource owners не выполнялась. Heap bound намеренно отсутствует и не заявляется. |
| Bypass request/response streaming, backpressure, cancellation, pooling, malformed upstream и exact header filtering | `BypassProxyRequestBackpressureTest`, `BypassProxyResponseBackpressureTest`, `BypassProxyCancellationTest`, `UpstreamConnectionPoolingTest`, `BypassProxyServiceTest` и raw-upstream tests наблюдают public HTTP boundaries | Physical chunk boundaries не являются contract. Existing bypass PERF result не подтверждает current enforcement latency и не становится новым load evidence. |
| Connect/write/response/idle timeouts и stable transport failures | Config tests, `UpstreamTimeoutsTest`, `BypassProxyServiceTest` и malformed-upstream test покрывают independent settings, first object/inter-object idle model, `502`/`504` literals и privacy | Write-timeout lifecycle не имеет отдельного raw test, отличного от configuration plus shared transport failure path. Target сохраняется; нового runtime run нет. |
| Health/readiness/admission, graceful/forced shutdown и owned-resource order | `HealthEndpointsTest`, `ShutdownLifecycleTest`, response E2E и lifecycle helper tests наблюдают local probes, new-traffic rejection, active drain, force bound и cleanup ordering | Static composition плюс helper failure tests не равны injected failure каждого production closeable. Availability SLO не определён. |
| Versioned distribution, non-root OCI, read-only configuration и mandatory startup inputs | `ociArtifact`, installed-distribution/OCI smoke scripts и сохранённые process observations описывают текущую поставку | Smoke/OCI не запускались в этой migration; прежние результаты не объявляются новой qualification. Registry publication, multi-arch, Kubernetes/Helm отсутствуют. |

## Observability evidence

Нормативный owner: [observability](../spec/requirements/observability.md).
Таблица основана на current source/test review и сохранённых observations.
Documentation migration не запускала runtime, process, OCI или load suites и
не переносит former durable/WAL qualification в evidence текущего stdout
pipeline.

| Target / invariant | Фактический source/test contract | Gap / evidence boundary |
|---|---|---|
| Один JSONL stdout sink; exact bounded non-blocking Logback queue; logging failure не меняет traffic | `LoggingConfigurationTest` проверяет provider/topology, `8192/2048`, `neverBlock`, discard priority, queue-full producer, JSON envelope и bounded stop; request E2E проверяет slow/full/throwing sink | Queue и stdout могут терять events по contract. Container driver/Collector delivery не наблюдаются unit suite; нового process run нет. |
| Ровно одна REQUEST и ordinary/SSE RESPONSE pair только после начала detector execution | `RequestInspectionE2eTest`, `JsonResponseEnforcementE2eTest`, `SseResponseEnforcementE2eTest` используют causal detector/transport barriers и проверяют outcome/reaction/aggregate/absence/cancellation/privacy matrices; `PiiShadowProxyProcessTest` наблюдает packaged REQUEST JSONL | Existing tests являются current implementation evidence, но при migration не перезапускались. Packaged RESPONSE stdout pair отдельно не наблюдалась. |
| Session/W3C propagation и SERVER/INTERNAL/CLIENT lineage | `TracingServiceTest`, gateway response/request E2E, identity telemetry tests и `tracing-sequence.puml` покрывают default/custom headers, generated/replaced context, span tree и cold-miss lineage | Normative privacy запрещает raw exception events, но `TracingService` и `BypassProxyService` вызывают `Span.recordException` для transport failures. Полная trace privacy не соответствует target. |
| Gateway metric names/units, finite dimensions и terminal publication | `MetricsServiceTest` покрывает request/response/status, active gauge, durations, timeout, transport failure и cancellation; OTLP export проверяется отдельно | `vigilant.proxy.transport_errors:error.type` содержит arbitrary exception class, а не finite allowlist. Dedicated inspection latency/outcome/capacity/deadline/PII OTel instruments из `OBS-01` отсутствуют; aggregates есть только в stdout completed event. |
| External lookup/cache metrics и initiating span | `BridgeIdentityClientTest` и `CachingExternalIdentityLookupTest` покрывают каждый outcome/status class, hit/miss/join/overload/removal, telemetry failure и lineage | Полная граница и дополнительные gaps принадлежат [identity evidence](#identity-evidence); generic owner не повышает её status. |
| OTLP trace/metric JSONL, on/off behavior, line atomicity и shutdown ownership | `OtlpExportTest`, `OtlpMetricsExportTest`, `OtlpJsonLineOutputStreamTest` и lifecycle composition проверяют envelopes, disabled export и complete-line publication | External Collector parsing/routing, retention и delivery являются deployment responsibility; zero-loss evidence отсутствует по contract. |
| Channel-specific privacy | Audit/gateway/identity tests используют sentinels и проверяют safe schemas отдельно для logs, client errors, metrics и selected traces | Safe client/audit output не доказывает trace exporter privacy. Raw exception span gap выше остаётся открытым; blanket all-channel claim не подтверждён. |

Методика и commands: [observability checks](development.md#observability-contract-checks).

## Identity evidence

Нормативные owners: [identity/context](../spec/requirements/identity-and-context.md)
и [identity telemetry](../spec/requirements/observability.md#identity).
Таблица основана на source/test review; новые runtime, process, OCI и load
прогоны при переносе документации не выполнялись. Методика и commands находятся
в [development guide](development.md#identity-contract-checks).

| Target / invariant | Фактический source/test contract | Gap / evidence boundary |
|---|---|---|
| Exact environment × mode, no fallback, reject any non-selected setting | `IdentityConfig.kt`, `AppConfigLoadingTest`, `AppComponentIdentityTest`, `ExternalIdentityCacheConfigTest` покрывают выбранные modes, обязательные settings и cache isolation | Conformance gap: `identity-dummy-groups=[]` в JWT/EXTERNAL и `identity-jwt-jwks=[]` в DUMMY/EXTERNAL неотличимы от decoded empty defaults; `isEmpty()` пропускает явно заданную настройку чужого mode. Target полного запрета сохранён, runtime не изменён. |
| Single Bearer, normalized identity, offline RS256 trust/claims/rotation | `DummyIdentityExtractorTest`, `OfflineJwtIdentityExtractorTest`, `ExternalIdentityExtractorTest`, `GatewayIdentityE2eTest` проверяют header/credential cases, pre-body rejects и unchanged upstream Authorization | JWT evidence matrix не полна: отсутствуют sub boolean/object/array, groups numeric/boolean, blank kid, null exp/nbf, non-object/duplicate JSON cases. Generic anonymous assembly не означает anonymous runtime mode; Keycloak не ограничивает продукт. Full target не сужен. |
| Exact Bridge protocol, каждый status 201..599, protocol/transport failures и whole-exchange deadline | `BridgeIdentityClientTest` содержит индивидуальные status cases и acquisition/connect/write/headers/body timeout setups; gateway E2E проверяет safe 503, body-demand/upstream boundary и finite telemetry | Evidence gap: valid JSON root с trailing JSON/garbage не проверен. Bridge/JWT/JWK parsers используют readTree без явной full-document validation, поэтому полная strict JSON conformance не подтверждена; source target malformed rejection сохранён. Нового runtime run нет. |
| HMAC, write TTL, size, coalescing, waiter bound и every terminal path | `ExternalIdentityCacheKeyHasherTest`, `CachingExternalIdentityLookupTest`, `GatewayIdentityE2eTest` покрывают independent vectors, TTL-1ns/exact TTL/TTL+1ns, eviction, slots, cancellation, race winners, late generation и caller-forged completion | Entry limit действует после maintenance, не измеряет heap. Cache load testing не выполнено и не было условием реализации; PERF-01/02 не подтверждены cache functionality. |
| Shutdown, restart, cleanup failures | `ExternalIdentityProcessTest` проверяет installed restart/config/forced shared cancellation; gateway tests - graceful/forced drain; `OutboundClientResources` композиционно вызывает decorator/Bridge/factory через `runAllCleanupActions` | `TestResourceLifecycleTest` через `closeAllResources` доказывает first/suppressed errors helper, а static composition review - применение helper. Это не injection failure в реальный network factory. |
| URL/context/handoff и REQUEST/RESPONSE isolation | `PolicyUrlNormalizerTest`, `PolicyContextAssemblerTest`, `AnonymousRequestContextAssemblerTest`, `PolicyContextHandoffTest`, gateway tests: exact fields, immutable groups, independent contexts; handoff tests наблюдают cleanup при normal completion и timeout | Отдельные owning-handoff observations после cancellation, upstream error и client error отсутствуют. Gateway cancellation tests не доказывают release snapshot; все terminal targets сохранены. |
| Lookup/cache observations и privacy | Bridge/cache/gateway tests используют in-memory OTel и unique sentinels для success/hit/failures/cancellation/shutdown; shared span сохраняет initiating parent | Общий OBS-01 остаётся частичным. Наличие telemetry owner не доказывает новый numeric SLO. |

## Protocol evidence

Нормативные owners: [Chat Completions](../spec/requirements/chat-completions-protocol.md)
и [HTTP errors](../spec/requirements/http-gateway.md). Основание таблицы -
source/test review; новых parser, HTTP, process, OCI или load runs при
переносе документации нет. Методика: [protocol checks](development.md#protocol-contract-checks).

| Target / invariant | Фактический source/test contract | Gap / evidence boundary |
|---|---|---|
| Request field map, exact routing, schema walker, gaps, budgets | `ChatCompletionsRequestParserTest`, `RequestEnforcementFieldCases`, `RequestRewritePlannerTest` содержат independent field/classification, schema, Unicode, negative и limit cases | Request defaults 8 MiB не подтверждают target 20 MiB raw/16 MiB text. Textual arguments не разрешают structured containers. Нового dynamic run нет. |
| Model-visible schema property names имеют SCHEMA_TEXT | `JsonSchemaWalker.collectNamedSchemaContainer` передаёт LABEL в `addTextValue`; tests закрепляют LABEL | Conformance gap semantic kind: properties/patternProperties/dependentSchemas names сохранены как SCHEMA_TEXT target, текущий LABEL не объявлен эквивалентным. Structural enforcement classification не зависит от этого kind. |
| Assistant-only request tool_calls и audio | `ChatCompletionsRequestParser.collectMessage` обрабатывает modern/custom tool_calls и audio для любой role, а tool fragments получают ASSISTANT | Conformance gap: user с content и tool_calls/audio может быть принят; normative assistant-only scope сохранён. Deprecated function_call/reasoning проверяют assistant отдельно. |
| Role только при explicit protocol role | `ResponseCollector.addText` и `SseResponseCollector.result` всегда ставят ASSISTANT, включая source без role | Conformance gap provenance для отсутствующей role; target не изменён. |
| Ordinary JSON exhaustive field/terminal failures | `ChatCompletionsResponseParserTest` проверяет content/refusal, modern/deprecated call order, audio transcript/gap, optional null, malformed/ambiguous input; rewriter tests проверяют maps | Corpus не является полной type matrix каждого nested field: отдельные missing/type combinations tool function/arguments и audio data не представлены. Требование exhaustive matrix сохранено. |
| SSE canonical fields, terminal и unknown event | `SseResponseCollector` сохраняет независимые choice/tool/semantic buffers; negative corpus покрывает missing DONE, mixed/after terminal, indices, repeated shape и provider error | Unknown event type даёт AMBIGUOUS_CONTENT в `consumeSseEvent` и negative test; target требует UNSUPPORTED_SCHEMA. На HTTP boundary оба дают safe 502, но parser mismatch остаётся. |
| Каждая field branch при каждом byte boundary; Unicode-safe result | Segmentation test сравнивает все splits и one-byte segments для одного JSON content и одного SSE content response с Unicode; другие field examples проверяются отдельно | Полный cross-product refusal, modern/deprecated tool arguments, audio и multi-line data × segmentation не подтверждён. Сравнение с unsplit baseline доказывает invariance этих samples, не independent semantic correctness всех shapes. |
| No partial state/disclosure, cancellation и source ownership | Public parser cancellation test использует уже interrupted thread; `RequestInspectionE2eTest`, JSON/SSE enforcement E2E и rewrite suites проверяют rejection/replay | Pre-cancelled parse не доказывает cancellation после накопления каждого partial field. Полная terminal matrix остаётся target; transport retention evidence не заменяет parser-local observations. |
| Exact five inspection outcomes, privacy и Retry-After | `OpenAiErrorResponsesTest` использует real HTTP и closed literal field sets для всех пяти строк; request/response integration suites проверяют handoff/disclosure | Encoder-level synthetic response replacement не является production retention evidence само по себе; соответствующие JSON/SSE E2E нужны отдельно. External identity имеет отдельный согласованный identity_unavailable у identity owner, а не inspection error. |

## Stage 1 и non-goals

Stage 1 requirements остаются future scope. Наличие похожего
internal API не доказывает implementation capability.

| ID | Статус | Текущая граница / remaining gap |
|---|---|---|
| `ST1-01` | Не реализовано | Retrieval documents/chunks не имеют enforcement seam; текущий gateway проверяет только Chat Completions traffic. |
| `ST1-02` | Не реализовано | Multi-step trajectory state, policy и evidence отсутствуют. |
| `ST1-03` | Не реализовано | Исходная цель агента не входит в `PolicyContext`; goal-hijacking detector отсутствует. |
| `ST1-04` | Не реализовано | Application не хранит historical traces и не имеет replay runner. |
| `ST1-05` | Не реализовано | Gateway не управляет regeneration/retry циклом агента. |
| `ST1-06` | Не реализовано | Реакции MVP ограничены `ALLOW`/`MASK`/`BLOCK`; `fix`/`filter`/`reask`/`refrain`/`exception` не активированы. |
| `ST1-07` | Не реализовано | Protocol parser проверяет inspectability Chat Completions, но не выполняет arbitrary output/tool JSON Schema validation. |
| `ST1-08` | Не реализовано | Trusted-source retrieval и fact-check detector отсутствуют. |
| `ST1-09` | Не реализовано | File ingest/scanning не входит в current protocol surface. |
| `ST1-10` | Не реализовано | Generated-code inspection и external analyzer adapter отсутствуют. |
| `ST1-11` | Не реализовано | Token/cost/run-duration budgets не принадлежат gateway policy model. |
| `ST1-12` | Не реализовано | Gateway не считает agent actions/retries и не предотвращает side-effect loops. |
| `ST1-13` | Частично | Base HTTP, audit и identity telemetry есть; dedicated inspection instruments, finite error taxonomy и availability SLI/SLO отсутствуют. |
| `ST1-14` | Частично | OTel SDK формирует OTLP/JSON traces и metrics в stdout; complete operational export/collector evidence не повышается из наличия internal records. |
| `ST1-15` | Не реализовано | Critical-event webhook и delivery lifecycle отсутствуют. |
| `ST1-16` | Не реализовано | PII corpora не являются regression eval runner для каждой policy/model version. |
| `ST1-17` | Не реализовано | Каталог versioned guardrail packs отсутствует. |
| `ST1-18` | Не реализовано | Stable third-party detector plugin API и trust/lifecycle contract отсутствуют. |
| `ST1-19` | Не реализовано | OpenAI Agents SDK adapter не поставляется; доступен только HTTP base URL seam. |
| `ST1-20` | Не реализовано | LangChain/LangGraph callback и middleware отсутствуют. |
| `ST1-21` | Не реализовано | CrewAI adapter отсутствует; demand не подтверждён. |
| `ST1-22` | Не реализовано | MCP call/result middleware не входит в Chat Completions gateway. |
| `ST1-23` | Не реализовано | Application не имеет Syslog/webhook SIEM exporter; stdout может быть входом external deployment pipeline. |

Product non-goals не имеют implementation gap внутри Vigilant. Каждая
строка фиксирует excluded owner и допустимую integration boundary.

| ID | Статус | Текущая граница |
|---|---|---|
| `OUT-01` | Вне продукта | Conversation flow и next action принадлежат agent orchestrator; Vigilant решает только допустимость traffic. |
| `OUT-02` | Вне продукта | Model/tool loop и handoff принадлежат external agent runtime. |
| `OUT-03` | Вне продукта | Generation structured output не входит; future schema/type validation может стать guardrail seam. |
| `OUT-04` | Вне продукта | Opaque immutable policies не допускаются; policy packs должны быть transparent, testable и versioned. |
| `OUT-05` | Вне продукта | Public marketplace не активируется до mature plugin API и trust model. |
| `OUT-06` | Вне продукта | Application-owned observability storage/delivery отсутствует; safe records передаются deployment через stdout. |
| `OUT-07` | Вне продукта | Static analysis должен использовать external Semgrep/CodeQL-like system, а не analyzer в core. |
| `OUT-08` | Вне продукта | Investigation/storage принадлежат corporate SIEM; Vigilant может публиковать normalized events. |
| `OUT-09` | Вне продукта | Email/messenger delivery принадлежит external alerting system или adapters. |
| `OUT-10` | Вне MVP | Gateway может инспектировать model-visible tool fields, но не запускает tools и не владеет result middleware. |
| `OUT-11` | Вне MVP | Единственный detector MVP - `fast-pii`; prompt injection, secrets и rule detectors не включены. |
| `OUT-12` | Вне MVP | Поддержан только Chat Completions; Responses остаётся Draft, Anthropic/MCP/Realtime/Batch contracts отсутствуют. |
| `OUT-13` | Вне MVP | Policy snapshot immutable и startup-only; hot reload, control plane, plugin workers/API и marketplace отсутствуют. |

## Правило обновления

Любая production задача, меняющая requirement coverage, обновляет этот документ,
нормативную спецификацию-владельца, work item и runtime documentation в одном
change set. Dynamic evidence публикуется только после фактического прогона.
