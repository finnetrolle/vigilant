# Покрытие требований документацией и реализацией

Нормативные [owners и 55 stable IDs](../spec/requirements/README.md) отделены от
каталога открытой работы. Перенос документации не меняет production behavior и не создаёт нового dynamic
evidence; карта уточняет ранее завышенные claims по source/test review.
Completion проверяется по
[project protocol](agent-workflow.md#work-item-completion).

Документальный authoring gate опубликован в
[risk-based readiness](../spec/WORK_ITEMS.md#risk-based-readiness), формат - в
[issue template](../spec/ISSUE_TEMPLATE.md). Два
[учебных примера](../spec/examples/issue-authoring.md#ручная-проверка-authoring-behavior)
проверяют Low path и незакрытый High lock при semantic review. Это process
contract; runtime coverage и product evidence ниже не меняются.

Task context реализован как [локальная read-only команда](task-context.md#contract)
с isolated CLI fixtures и отдельным [baseline context](agent-context-baseline.md).
[Startup routing](../CLAUDE.md#mandatory-routing) действует без issue ID;
[project procedures](agent-workflow.md) сохраняют обязательные TDD, lifecycle,
security и independent review gates. Это tooling evidence, не runtime claim.

Reusable mechanical evidence реализована в [durable runner](development.md#контракт-reusable-evidence)
и [единственном final gate](development.md#локальные-pipeline-scripts).
Isolated Python/shell fixtures проверяют exact contract, selected-input и
artifact invalidation, terminal/process ownership и отсутствие отдельного build
перед verifyAll. Это tooling evidence; semantic applicability, product runtime
coverage и external-data freshness между задачами из hashes не следуют.
[Cycle ledger](development.md#метрики-verification-cycle) позволяет собрать
следующие 3-5 наблюдений; измеренная wall-clock экономия пока unavailable.

[CI workflow и его локальная приёмка](development.md#локальная-приёмка-workflow)
различают проверку конфигурации, локальную сборку и фактическое выполнение
GitHub jobs. Локальное evidence не подтверждает доступность GitHub Actions
или актуальный OWASP scan; внешние ограничения остаются явными в
[наблюдениях CI](development.md#наблюдения-ci).

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

`./scripts/benchmark load` запускает всю существующую матрицу с изолированными
артефактами и общим verdict. Это автоматизация измерений, а не новый профиль
request/response SLO; статусы PERF-01/02 ниже не меняются.
[Состав и границы циклов](benchmark-cycles.md) также описывают полный
`pii-quality` run четырёх корпусов без объединения их quality metrics.

| ID | Статус | Текущий факт |
|---|---|---|
| `PERF-01` | Не реализовано | Есть detector/request-policy benchmarks, но нет отдельного request/response enforcement latency evidence с новым profile. |
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
| `OBS-02` | Частично | Audit pairs, client errors и application logs имеют channel-specific privacy controls; session/query-free path и valid propagation разрешены только operational MDC/traces. SERVER и upstream CLIENT transport failures используют finite category без exception diagnostics; SDK, production OTLP и installed stdout проверяются отдельно. Это не закрывает остальные channel/deployment gaps. |

## PII и windowing

Permanent owners: [recognition и quality](../spec/requirements/fast-pii.md),
[generic core и adapters](../spec/requirements/windowed-inspection.md).
Source/test review не является новым dynamic run. Команды и обязательная
matrix находятся в [development guide](development.md#pii-contract-checks).

| Target / invariant | Фактический source/test contract | Gap / evidence boundary |
|---|---|---|
| Fixed taxonomy, offsets, metadata, direct ordering, preflight/cancellation | `FastPiiDetector` и recognizer/public contract tests; canonical synthetic gate содержит минимум по 100 positive/negative cases на type; IP corpus расширен до 160 positive и 175 negative | ИНН поддержан только для физлиц, 12 digits; прежнее обещание 10 digits в runtime guide было ошибкой документации. IP regression evidence не закрывает полную detector matrix. |
| IPv4 перед decimal port 1..65535 | `fast.ip_address@1.2.0` принимает port в EOF и перед token boundary. `Ipv4PortRecognizerTest` проверяет 60 positive combinations, 125 negative continuations и 5 terminal-colon cases. `Ipv4PortEnforcementE2eTest` проверяет real-Armeria REQUEST/JSON/SSE ALLOW/MASK/BLOCK с exact bytes и no-handoff/no-disclosure oracles | Подтверждаются только перечисленные port boundaries и существующий policy path. URL/DNS/CIDR и IPv6 host-and-port не добавлены; private improvement не является полной PII qualification. |
| Plausible checksum-invalid SNILS под whole-word context | `SnilsCandidateRules.evidenceStrength` применяет issuance threshold до обоих evidence paths; tests сохраняют exact `снилс`, weak/partial keywords отвергают | Source contract явно сохраняет threshold для validated path, но не уточняет его применение к contextual path. Plausible contextual candidate ниже threshold не подтверждён; автоматического расширения surface нет. |
| Finite evidence span для каждой current surface | `FastPiiWindowCapability.VERSIONED`: W=1 MiB, E=4096, context=4095 с каждой стороны. KDoc дополняет IP port bound: максимум 29 UTF-8 bytes с обеими boundaries; capability остаётся `@2` | Остальной proof всё ещё ссылается на старый 254-code-point email bound и не перечисляет expanded email gaps/IDN source length, contextual phone/SNILS/OMS. Полнота для всех current recognizers требует отдельного conformance evidence. |
| Каждая surface через ownership boundary и все Unicode widths | `WindowedFastPiiExecutorTest` содержит 30 исходных surface cases по всем 9 types и email widths 1/2/3/4. `Ipv4PortWindowTest` добавляет 12 случаев: widths 1/2/3/4 x boundary внутри IPv4, перед colon, внутри port; независимый oracle проверяет global offsets и одну finding | Window corpus ещё не включает email gaps, national/contextual и Unicode phone, IP terminal punctuation, expanded/contextual SNILS/OMS. Direct recognizer/canonical tests этих forms не заменяют cross-window tests. |
| Complete error/adapter/lifecycle contract | `WindowedInspectionExecutorTest`, `WindowedFastPiiExecutorTest`, `FastPiiPolicyAdapterTest` проверяют typed errors, immutable aggregate, CPU handoff и cancellation; generic UTF-8 preflight также отвергает length > Int.MAX_VALUE как INVALID_FRAGMENT | Это ограничение текущей реализации, не новый global response/request limit. Exhaustive test-method matrix остаётся требованием; reviewed tests не объявляются новым process/load evidence. |
| Canonical и external quality floors | Canonical/report/scorer tests и `piiQualityQualification` сохраняют source/product views и используют independent `redmadrobot-ip-canonical-v2`: 10 nested URL-host spans и 8 нормализованных IPv4 endpoint spans. Baseline/current пересчитаны на общем gold; exact matching, IP/PHONE и aggregate floors сохранены | На canonical reference evaluation exact F1 вырос с 0.44918 до 0.45677; IP precision 0.98077 выше baseline 0.97931, IP recall 0.92727 и PHONE precision 0.93548 проходят. Qualification PASS относится к этому pinned reference; source-aligned отрицательные metrics сохранены. [Quality targets](../spec/requirements/fast-pii.md#quality) не снижены; исходный dataset и иные annotation gaps не объявлены исправленными. |
| Общий baseline детектора | [Замер 2026-09-16](pii-quality-evaluation.md) сохраняет свежие reports четырёх корпусов на одном detector/evaluator snapshot, полные scoped/per-type counts, denominators, suppression и команды воспроизведения | Canonical: 960 positive и 975 hard-negative PASS, mixed 13/0/0. RedMadRobot source full exact TP/FP/FN = 614/146/1286; P/R/F1 = 0.807895/0.323158/0.461654. Views и корпуса не объединены. Это evidence прямого `PiiDetector.detect`, без нового JMH qualification, production-quality claim или закрытия gateway gaps. |
| HiveTrace external PII seam | `hiveTracePiiBenchmark` воспроизводит pinned entity/domain corpus: 1810 processed, 0 rejected, 817 source-aligned mapped spans, 789 product-aligned spans и 850 unsupported spans. Оба split, девять domain codes и Full публикуются отдельно; [методика и команды](development.md#hivetrace-pii-benchmark) | Свежий общий [baseline 2026-09-16](pii-quality-evaluation.md): Full source exact TP/FP/FN = 720/37/97, P/R/F1 = 0.95112/0.88127/0.91487; relaxed = 723/34/94. Product exact = 720/37/69. Clean FPR domain = 0/378, entity = N/A (0 cases). Это external/non-gating evidence шести mapped types; смесь не представляет production traffic, остальные три типа не покрыты. Production recognizers не менялись. |
| AdvPIIBench adversarial PII seam | `advPiiBenchmark` проверяет pin/integrity/schema/coverage до scoring: 104728 processed, 0 rejected, 114101 source spans, 99696 mapped spans, 7772 SSN-only positive rows. Отдельные baseline, PII-only/combined family/configuration subsets, per-type/micro counts и FPR; [методика](development.md#advpiibench-adversarial-benchmark) | Online/offline run 2026-09-16: baseline exact TP/FP/FN = 1079/83/409, recall 0.72513; overlap = 1119/43/369, recall 0.75202. PII-only и combined exact/overlap recall = 0; source-aligned combined FP = 14496 с `pi_few_shot_safe` caveat. Negative FPR = 0/22560; hard_negative = 2/1232. Detailed groups/type groups требуют 5 distinct input IDs; suppressed groups не раскрывают counts. Это отдельное held-out non-gating evidence, без tuning и production-quality claim. |
| Current PERF-01/02 | JMH измеряет sync detect; existing inspection/load reports имеют собственный request-policy profile | Ни старые numeric results, ни новые ссылки не доказывают current request/response enforcement latency. Статусы PERF-01/02 выше сохранены. |

Неподтверждённые cases сохраняются как requirements. Focused IP correction
обновляет recognizer и synthetic evidence; она не заявляет полную qualification
остальных surfaces и не меняет runtime defaults. Отдельное расширение evaluation
reference не добавляет URL type или URL parser в production detector.

Tuning-only диагностика `fast.ip_address@1.2.0` через production detector и
canonical adapter/split/matcher даёт 111 exact TP и 12 FP против прежних 111
и 4. Восемь unmatched IPv4 findings перед валидным port с non-EOF suffix
соответствуют контракту; у шести нет пересекающегося IP gold. При 155 gold
во всём source-aligned scored subset даже perfect recall и устранение других
FP дают precision не выше `155 / 161 = 0.962733`, ниже baseline `0.972414`.
Это подтверждает несовместимость текущего recognition contract с данным
precision floor при исходном flat gold, но не ошибочность upstream gold или невозможность улучшить
evaluation F1. Evaluation cases не использовались для диагностики; публикуются
только безопасные агрегаты с privacy floor 5.

Исторический парный пересчёт `1aef8df59f3640148528e750e8213733` для reference
`redmadrobot-url-ip-host-v1` сохранил исходные baseline metrics без изменений.
Эталон v1 добавил 10 IP spans: full/tuning/evaluation scored spans `1910/1471/439`,
processed cases и split не изменились. В том прогоне current IP имел
`TP=150, FP=6, FN=15`, baseline `TP=142, FP=3, FN=23`. Пять оставшихся tuning
FP содержали canonical IPv4; детальные buckets ниже privacy floor не раскрываются.
Эталон v1 устранил конфликт вложенных URL-host labels, но не нормализовал
IPv4 endpoint spans. Актуальные результаты v2 приведены в таблице выше.

Historical Qualification run `7129e9a4e2854ed69e75127266c9b988` сохранил exit `1`:
canonical 960 positive, 975 negative и 3 mixed cases прошли; 18 paired JMH
cases прошли performance gate. Final build
`8df71d6fba0d431198fc82a58cd08789` завершился exit `0`: 1508 regular, 57
process и 55 work-item validator tests без failures/errors/skips. Эти
наблюдения относятся к реализации IP/port; документационное закрытие не
создаёт нового runtime или performance evidence.

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

Plaintext response reasoning реализован для ordinary JSON и SSE; field shapes,
отдельные logical buffers, exact rewrite, HTTP reactions, errors, atomicity,
cleanup и safe telemetry проверены по [reasoning evidence](response-reasoning-evidence.md).
Это synthetic Chat Completions contract, без утверждения о live payload
корпоративного LiteLLM или полной совместимости Filin.

Нормативные owners: [RESPONSE enforcement](../spec/requirements/response-enforcement.md)
и [HTTP gateway](../spec/requirements/http-gateway.md). Таблица основана на
source/test review и сохранённых implementation observations. Новые reasoning
observations приведены выше. Исторический перенос остальных строк не включал
новых runtime, process, OCI или load runs.

| Target / invariant | Фактический source/test contract | Gap / evidence boundary |
|---|---|---|
| Complete ordinary/SSE source и protocol-valid terminal до первого disclosure; exact `ALLOW`, source-patched `MASK`, whole-response `BLOCK`, safe `502`/`503` | `JsonResponseEnforcementE2eTest` и `SseResponseEnforcementE2eTest` используют causal upstream-terminal и detector barriers, отдельно наблюдают headers/body, literal outcome и retained cleanup | Existing observations подтверждают current implementation; encoder tests сами по себе retention не доказывают. Нового прогона при переносе нет. |
| Все ordinary fragments, gap/reaction precedence, JSON source coordinates и exact rewrite | Parser/rewriter suites и real-Armeria JSON matrix покрывают content, refusal, reasoning_content, modern/deprecated arguments, transcript/audio gap, `200`/`429`/`500`, Unicode/escapes/unknown metadata и invalid mapping | Полная nested type/segmentation cross-product matrix имеет gaps, перечисленные в [protocol evidence](#protocol-evidence). Contract не сужен. |
| Все SSE logical fields, standalone terminal и cross-event source patching | Parser/SSE rewriter suites покрывают interleaved choices/tools, cross-event spans, adjacent/overlapping instructions, LF/CRLF, comments, multi-line data, empty values, Unicode/escapes и deterministic invalid mappings | Полный field branch x every byte boundary cross-product и parser-local cancellation after each partial field не подтверждены; HTTP atomic outcomes покрываются отдельно. |
| Retained source и one-shot ownership на success/reject/failure/cancellation/shutdown | `RetainedResponseSourceTest`, `ReplayReadyResponseTest` и JSON/SSE E2E наблюдают one-item demand, view/replay exclusivity, both transfer/cancel race orders, callback failures, ingest/analysis/replay cancellation и forced cleanup | `runAllCleanupActions` проверен с first/suppressed failures; failure injection во все реальные network/resource owners не выполнялась. Heap bound намеренно отсутствует и не заявляется. |
| Bypass request/response streaming, backpressure, cancellation, pooling, malformed upstream и exact header filtering | `BypassProxyRequestBackpressureTest`, `BypassProxyResponseBackpressureTest`, `BypassProxyCancellationTest`, `UpstreamConnectionPoolingTest`, `BypassProxyServiceTest` и raw-upstream tests наблюдают public HTTP boundaries | Physical chunk boundaries не являются contract. Existing bypass PERF result не подтверждает current enforcement latency и не становится новым load evidence. |
| Connect/write/response/idle timeouts и stable transport failures | Config tests, `UpstreamTimeoutsTest`, `BypassProxyServiceTest` и malformed-upstream test покрывают independent settings, first object/inter-object idle model, `502`/`504` literals и privacy | Write-timeout lifecycle не имеет отдельного raw test, отличного от configuration plus shared transport failure path. Target сохраняется; нового runtime run нет. |
| Health/readiness/admission, graceful/forced shutdown и owned-resource order | `HealthEndpointsTest`, `ShutdownLifecycleTest`, response E2E и lifecycle helper tests наблюдают local probes, new-traffic rejection, active drain, force bound и cleanup ordering; [operations evidence](operations-evidence.md#проверки-приложения) фиксирует новый focused health/process run 2026-09-10 | Static composition плюс helper failure tests не равны injected failure каждого production closeable. Availability SLO не определён; стендовый rollout/recovery остаётся [operator checklist](operations.md#приёмка-на-стенде). |
| Versioned distribution, non-root OCI, read-only configuration и mandatory startup inputs | `ociArtifact`, installed-distribution/OCI smoke scripts и сохранённые process observations описывают текущую поставку; [operations evidence](operations-evidence.md#проверки-приложения) фиксирует новый OCI smoke 2026-09-10 | Test DUMMY identity не квалифицирует production identity integration. Registry publication, multi-arch, Kubernetes/Helm отсутствуют. Multi-replica rollout, probe и external delivery проверяет администратор. |

## Observability evidence

Нормативный owner: [observability](../spec/requirements/observability.md).
Таблица основана на current source/test review и сохранённых observations.
Documentation migration не запускала runtime, process, OCI или load suites и
не переносит former durable/WAL qualification в evidence текущего stdout
pipeline.
Отдельные observations 2026-09-10 и их точная граница перечислены в
[operations evidence](operations-evidence.md). Они обновляют только названные
checks и не означают повторный прогон всей observability matrix.

| Target / invariant | Фактический source/test contract | Gap / evidence boundary |
|---|---|---|
| Один JSONL stdout sink; exact bounded non-blocking Logback queue; logging failure не меняет traffic | `LoggingConfigurationTest` проверяет provider/topology, `8192/2048`, `neverBlock`, discard priority, queue-full producer, JSON envelope и bounded stop; семь logging tests перезапущены 2026-09-10. Existing request E2E проверяет slow/full/throwing sink | Queue и stdout могут терять events по contract. Container driver/Collector delivery не наблюдаются unit suite. HTTP slow/full/throwing-sink suite в operations run не перезапускалась. |
| Ровно одна REQUEST и ordinary/SSE RESPONSE pair только после начала detector execution | `RequestInspectionE2eTest`, `JsonResponseEnforcementE2eTest`, `SseResponseEnforcementE2eTest` используют causal detector/transport barriers и проверяют outcome/reaction/aggregate/absence/cancellation/privacy matrices; `PiiShadowProxyProcessTest` наблюдает packaged REQUEST JSONL | Existing tests являются current implementation evidence, но при migration не перезапускались. Packaged RESPONSE stdout pair отдельно не наблюдалась. |
| Session/W3C propagation, SERVER/INTERNAL/CLIENT lineage и transport privacy | `TracingServiceTest`, `TransportTrace*E2eTest`, `TransportTracePrivacyProcessTest`, gateway/identity tests и `tracing-sequence.puml` покрывают propagation, span tree, finite transport outcomes, cancellation/timeout ordering и отсутствие exception diagnostics | [Transport evidence](transport-trace-evidence.md) разделяет SDK, production exporter и installed stdout. HTTP/metrics classification и External identity spans имеют собственные contracts; transport evidence не доказывает внешний Collector. |
| Gateway metric names/units, finite dimensions и terminal publication | `MetricsServiceTest` покрывает request/response/status, active gauge, durations, timeout, transport failure и cancellation; OTLP export проверяется отдельно | `vigilant.proxy.transport_errors:error.type` содержит arbitrary exception class, а не finite allowlist. Dedicated inspection latency/outcome/capacity/deadline/PII OTel instruments из `OBS-01` отсутствуют; aggregates есть только в stdout completed event. |
| External lookup/cache metrics и initiating span | `BridgeIdentityClientTest` и `CachingExternalIdentityLookupTest` покрывают каждый outcome/status class, hit/miss/join/overload/removal, telemetry failure и lineage | Полная граница и дополнительные gaps принадлежат [identity evidence](#identity-evidence); generic owner не повышает её status. |
| OTLP trace/metric JSONL, on/off behavior, line atomicity и shutdown ownership | `OtlpExportTest`, `OtlpMetricsExportTest`, `OtlpJsonLineOutputStreamTest` и lifecycle composition проверяют envelopes, disabled export и complete-line publication; четыре exporter tests перезапущены 2026-09-10. [Docker observation](operations-evidence.md#наблюдение-stdout-artifact) отдельно проверяет три stdout signal types | Parallel line-atomicity suite отдельно не перезапускалась. External Collector parsing/routing, retention и delivery являются deployment responsibility; zero-loss evidence отсутствует по contract. |
| Channel-specific privacy | Audit/gateway/identity tests используют sentinels; transport suites отдельно проверяют finished SERVER/CLIENT schemas и production OTLP, включая synthetic message/cause/suppressed/stack sentinels | Safe client/audit output не доказывает trace exporter privacy. Trace evidence не закрывает arbitrary metric error class, отсутствующие inspection instruments или deployment delivery; blanket all-channel claim не подтверждён. |

Методика и commands: [observability checks](development.md#observability-contract-checks).

## Identity evidence

Нормативные owners: [identity/context](../spec/requirements/identity-and-context.md)
и [identity telemetry](../spec/requirements/observability.md#identity).
Таблица основана на source/test review и Bridge full-document regression/E2E
checks; они не добавляют OCI/load evidence. Методика и commands находятся
в [development guide](development.md#identity-contract-checks).

| Target / invariant | Фактический source/test contract | Gap / evidence boundary |
|---|---|---|
| Exact environment × mode, no fallback, reject any non-selected setting | `IdentityConfig.kt` требует один полный duplicate-free JWK environment array; `AppConfigLoadingTest` проверяет empty/SP/TAB/LF/CRLF, каждый second-root type, identifier/punctuation/truncated garbage и прежнюю invalid-JWK matrix. `MainTest` подтверждает installed startup exit `2`, safe stderr и process/reader cleanup. `AppComponentIdentityTest` и `ExternalIdentityCacheConfigTest` сохраняют выбранные modes и cache isolation | JWK environment full-document gap закрыт. Conformance gap остаётся: `identity-dummy-groups=[]` в JWT/EXTERNAL и `identity-jwt-jwks=[]` в DUMMY/EXTERNAL неотличимы от decoded empty defaults; `isEmpty()` пропускает явно заданную настройку чужого mode. Target полного запрета сохранён. |
| Single Bearer, normalized identity, offline RS256 trust/claims/rotation | `OfflineJwtIdentityExtractor` требует по одному complete duplicate-free JSON object в protected header и claims. `OfflineJwtIdentityExtractorTest` проверяет exact signed bytes с empty/SP/TAB/LF/CRLF, каждым second-root type, identifier/punctuation/truncated garbage и duplicate keys; `GatewayIdentityE2eTest` подтверждает safe 400, no body demand/analysis/upstream и privacy. Existing header/credential/trust/rotation и unchanged Authorization checks сохранены | JWT full-document gap закрыт. Остальная evidence matrix не полна: отсутствуют sub boolean/object/array, blank kid, null exp/nbf и non-object JSON roots. Generic anonymous assembly не означает anonymous runtime mode; Keycloak не ограничивает продукт. Full target не сужен. |
| Exact Bridge protocol, каждый status 201..599, protocol/transport failures и whole-exchange deadline | [BridgeIdentityClient](../src/main/kotlin/io/vigilant/gateway/identity/BridgeIdentityClient.kt) включает full-document validation и duplicate detection. [BridgeIdentityClientTest](../src/test/kotlin/io/vigilant/gateway/identity/BridgeIdentityClientTest.kt) проверяет empty/SP/TAB/LF/CRLF suffix, каждый second-root type, identifier/punctuation/truncated garbage и прежние malformed/UTF-8/duplicate/non-object/additive cases. [GatewayIdentityE2eTest](../src/test/kotlin/io/vigilant/gateway/proxy/GatewayIdentityE2eTest.kt) проверяет second-root/garbage safe 503, отсутствие body demand/upstream/disclosure и fresh same-token retry через real cache с двумя Bridge calls; existing status/deadline/telemetry evidence сохранено | Bridge full-document gap закрыт regression/E2E checks; новых OCI/load claims нет. |
| HMAC, write TTL, size, coalescing, waiter bound и every terminal path | `ExternalIdentityCacheKeyHasherTest`, `CachingExternalIdentityLookupTest`, `GatewayIdentityE2eTest` покрывают independent vectors, TTL-1ns/exact TTL/TTL+1ns, eviction, slots, cancellation, race winners, late generation и caller-forged completion | Entry limit действует после maintenance, не измеряет heap. Cache load testing не выполнено и не было условием реализации; PERF-01/02 не подтверждены cache functionality. |
| Shutdown, restart, cleanup failures | `ExternalIdentityProcessTest` проверяет installed restart/config/forced shared cancellation; gateway tests - graceful/forced drain; `OutboundClientResources` композиционно вызывает decorator/Bridge/factory через `runAllCleanupActions` | `TestResourceLifecycleTest` через `closeAllResources` доказывает first/suppressed errors helper, а static composition review - применение helper. Это не injection failure в реальный network factory. |
| URL/context/handoff и REQUEST/RESPONSE isolation | `PolicyUrlNormalizerTest`, `PolicyContextAssemblerTest`, `AnonymousRequestContextAssemblerTest`, `PolicyContextHandoffTest`, gateway tests: exact fields, immutable groups, independent contexts; handoff tests наблюдают cleanup при normal completion и timeout | Отдельные owning-handoff observations после cancellation, upstream error и client error отсутствуют. Gateway cancellation tests не доказывают release snapshot; все terminal targets сохранены. |
| Lookup/cache observations и privacy | Bridge/cache/gateway tests используют in-memory OTel и unique sentinels для success/hit/failures/cancellation/shutdown; shared span сохраняет initiating parent | Общий OBS-01 остаётся частичным. Наличие telemetry owner не доказывает новый numeric SLO. |

## Protocol evidence

Нормативные owners: [Chat Completions](../spec/requirements/chat-completions-protocol.md)
и [HTTP errors](../spec/requirements/http-gateway.md). Основание таблицы -
source/test review, durable role-neutral parser/gateway verification и
[новые reasoning observations](response-reasoning-evidence.md);
исторический перенос остальных clauses не включал новых parser, HTTP, process, OCI или load runs. Методика: [protocol checks](development.md#protocol-contract-checks).

| Target / invariant | Фактический source/test contract | Gap / evidence boundary |
|---|---|---|
| Request field map, exact routing, schema walker, gaps, budgets | `ChatCompletionsRequestParserTest`, `RequestEnforcementFieldCases`, `RequestRewritePlannerTest` содержат independent field/classification, schema, Unicode, negative и limit cases | Request defaults 8 MiB не подтверждают target 20 MiB raw/16 MiB text. Textual arguments не разрешают structured containers. Нового dynamic run нет. |
| Model-visible schema property names имеют SCHEMA_TEXT | `JsonSchemaWalker.collectNamedSchemaContainer` передаёт LABEL в `addTextValue`; tests закрепляют LABEL | Conformance gap semantic kind: properties/patternProperties/dependentSchemas names сохранены как SCHEMA_TEXT target, текущий LABEL не объявлен эквивалентным. Structural enforcement classification не зависит от этого kind. |
| Role-neutral recognized request messages | `ChatCompletionsRequestParserTest` проверяет 6 roles x 8 field families, presence/null/failure matrix и actual-role provenance; `RequestInspectionE2eTest` проверяет six-role detector sequence, gaps, no-policy/ALLOW exact replay, safe failures и noncanonical-role MASK/BLOCK | Role-aware policy matching, новые roles/content kinds, tool correlation, response/SSE roles и inner parsing arguments остаются вне текущего scope. |
| Role только при explicit protocol role | `ResponseCollector.addText` и `SseResponseCollector.result` всегда ставят ASSISTANT, включая source без role | Conformance gap provenance для отсутствующей role; target не изменён. |
| Ordinary JSON exhaustive field/terminal failures | `ChatCompletionsResponseParserTest` проверяет content/refusal/reasoning_content, modern/deprecated call order, audio transcript/gap, optional null, malformed/ambiguous input; rewriter tests проверяют maps | Corpus не является полной type matrix каждого nested field: отдельные missing/type combinations tool function/arguments и audio data не представлены. Требование exhaustive matrix сохранено. |
| SSE canonical fields, terminal и unknown event | `SseResponseCollector` сохраняет независимые choice/tool/semantic buffers; negative corpus покрывает missing DONE, mixed/after terminal, indices, repeated shape и provider error | Unknown event type даёт AMBIGUOUS_CONTENT в `consumeSseEvent` и negative test; target требует UNSUPPORTED_SCHEMA. На HTTP boundary оба дают safe 502, но parser mismatch остаётся. |
| Каждая field branch при каждом byte boundary; Unicode-safe result | Segmentation test сравнивает все splits и one-byte segments для JSON/SSE samples с content и reasoning_content, Unicode и JSON escape; другие field examples проверяются отдельно | Полный cross-product refusal, modern/deprecated tool arguments, audio и multi-line data × segmentation не подтверждён. Сравнение с unsplit baseline доказывает invariance этих samples, не independent semantic correctness всех shapes. |
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
| `ST1-13` | Частично | Base HTTP, audit и identity telemetry есть; dedicated inspection instruments, finite error taxonomy и измеряемый end-to-end availability SLI/SLO отсутствуют. [Operations contract](../spec/requirements/operations.md) и [operator reference](operations.md) задают методику первого Docker deployment и последующего review; это не runtime SLI instrument или production evidence. |
| `ST1-14` | Частично | OTel SDK формирует OTLP/JSON traces и metrics в stdout; [Docker stdout observation](operations-evidence.md#наблюдение-stdout-artifact) ограничено приложением. Complete operational Fluentd/Collector/OpenObserve delivery evidence отсутствует. |
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
| `OUT-12` | Вне MVP | Поддержан только Chat Completions; Responses, Anthropic, MCP, Realtime и Batch не имеют active contracts или work items. Условия возможной активации Responses сохранены у product non-goal owner. |
| `OUT-13` | Вне MVP | Policy snapshot immutable и startup-only; hot reload, control plane, plugin workers/API и marketplace отсутствуют. |

## Правило обновления

Любая production задача, меняющая requirement coverage, обновляет этот документ,
нормативную спецификацию-владельца, work item и runtime documentation в одном
change set. Dynamic evidence публикуется только после фактического прогона.
