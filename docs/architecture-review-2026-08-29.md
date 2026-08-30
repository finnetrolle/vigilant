# Архитектурное ревью Vigilant от 2026-08-29

## Вердикт

Текущий request-side PII shadow proxy имеет сильные component boundaries,
детерминированные domain contracts и убедительный happy-path production
tracer bullet. Для контролируемого shadow deployment архитектура пригодна.

Полным production-ready MVP её пока считать нельзя. Ревью обнаружило две
существенные границы:

1. единственный `policy.shadow_decision` публикуется как отбрасываемый INFO log,
   поэтому нормативный минимальный audit trail не гарантирован;
2. опубликованная resource qualification покрывает только request `64 KiB` с
   одним fragment, тогда как runtime принимает request до `8 MiB`, до `16 384`
   normalized fragments и gap-dense shapes.

Также два известных недетерминированных test seams находятся только в
`.papercuts.jsonl`, а текущий roadmap frontier содержит устаревшие утверждения.
Находки преобразованы в [EPIC-21](../spec/epics/epic_21_post_milestone_architecture_closure.md).

## Scope и нормативные источники

Ревью проверяло текущие production sources, tests, build/deployment artifacts,
операторскую документацию и work-item graph относительно:

- `spec/MVP_FUNCTIONS.md`;
- `spec/MVP_NON_FUNCTIONAL_REQUIREMENTS.md`;
- `spec/STAGE_1_FUNCTIONS.md`;
- `spec/OUT_OF_SCOPE_FUNCTIONS.md`;
- `spec/ROADMAP.md`;
- нормативных epic и issue contracts в `spec/epics/` и `spec/issues/`;
- текущих runtime contracts в `docs/` и `README.md`.

Историческое ревью `docs/architecture-review-2026-08-23.md` использовалось
только для проверки, что закрытые AR-01..AR-09 не публикуются повторно.

## Что сделано хорошо

- HTTP routing, identity extraction, bounded source, protocol normalization,
  policy evaluation, exact replay и upstream transport разделены явными typed
  seams.
- Unsupported descriptor и invalid identity отклоняются до body demand.
- Original request bytes имеют одного lifecycle owner и не реконструируются из
  protocol DTO перед upstream replay.
- Request source имеет exact owner, byte и segment accounting с cleanup matrix
  для success, failure, cancellation и shutdown.
- Parser использует pinned semantic map, strict duplicate detection, fail-closed
  ambiguous outcomes, depth limit и fragment-count limit.
- Fast PII CPU work вынесен с Netty event loop в bounded platform-thread pool;
  orchestration выполняется на blocking-safe virtual threads.
- Policy and detector outcomes детерминированы, а payload, matched text,
  credentials и locators не попадают в audit, traces или errors.
- Response path сохраняет streaming/backpressure, client resources имеют явного
  owner, shutdown ordering документирован и проверен process E2E.
- Project имеет canonical `./gradlew validateWorkItems`, который входит в
  `check`. Свежий `./gradlew build --rerun-tasks --no-daemon` завершился
  `BUILD SUCCESSFUL` за `5m 55s`, выполнив все 16 tasks.
- EPIC-20 честно остаётся `Draft`: response/SSE lifecycle, secure spill,
  encryption и crash cleanup не получили выдуманных defaults.

## Находки

| ID | Severity | Класс | Находка | Evidence | Work item |
|---|---|---|---|---|---|
| AR-10 | Major | `missing-behavior` | Минимальный обязательный audit trail не гарантирован | `MVP_FUNCTIONS.md:83-85` требует журнал решений, `OUT_OF_SCOPE_FUNCTIONS.md:43-47` оставляет за Vigilant минимальный audit trail. `ShadowAuditLogger.kt:84-108` пишет решение на INFO, а `logback-async-stdout.xml:3-8` разрешает discard. `observability.md:17-20` прямо признаёт, что канал не является guaranteed audit storage. | VIG-21-01 |
| AR-11 | Major | `unverified` | Resource closure не доказан на всей принимаемой request surface | Parser строит полный Jackson tree (`ChatCompletionsRequestParser.kt:34-38`), отдельно сохраняет fragments и gaps, ограничивает только fragments (`632-658`), затем workflow последовательно вызывает policy engine для каждого fragment (`ShadowInspectionWorkflow.kt:125-131`). VIG-18 измеряет только `64 KiB`, один fragment и heap `512 MiB` (`inspection-load-result.md:34-38, 59-65, 71-87`), хотя default request limit равен `8 MiB`. | VIG-21-02 |
| AR-12 | Major | `unverified` | Известный full-suite upstream-error race не имеет backlog owner | Open papercut `pc_cc7188284a8f` фиксирует intermittent HTTP/2 `RST_STREAM` вместо stable error. `BypassProxyServiceTest.kt:193-208` допускает client exception через `runCatching`, поэтому log assertion не доказывает тот же HTTP outcome. Свежий forced build прошёл, то есть defect не воспроизведён и не может честно классифицироваться как production bug. | VIG-21-03 |
| AR-13 | Minor | `code-defect` в test evidence | Response-streaming proof использует timestamp race | `BypassProxyStreamingTest.kt:79-85, 103-112, 133-136` сравнивает моменты на разных threads после полного completion. Open papercut `pc_617014d8204d` уже фиксирует false failure и рекомендует observation barrier до release последнего chunk. | VIG-21-04 |
| AR-14 | Minor | `nit` / process drift | Roadmap frontier не соответствует registry и текущему runtime | `ROADMAP.md:360-362` сохраняет VIG-01A во frontier, хотя `WORK_ITEMS.md:97` имеет status `Done`. Текст `ROADMAP.md:348-358` расширяет 64 KiB qualification до общих memory/concurrency bounds, а раздел первого increment не отделяет исторический baseline от текущей identity-capable реализации. | VIG-21-05 |

## Подробности и рекомендации

### AR-10: lossy log event не является журналом решений

Безопасность содержимого audit event реализована хорошо, но safety и delivery
являются разными гарантиями. При заполнении `AsyncAppender` INFO event может
быть потерян до stdout. В таком случае аудитор не сможет установить policy
version и outcome конкретного request, хотя обработка уже завершилась.

Ревью не выбирает storage или delivery mechanism. Это решение затрагивает
durability, backpressure, disk exhaustion, shutdown, ownership external
Collector и границу OUT-06. Сначала нужен нормативный contract и отдельная
implementation decomposition. Именно этот результат владеет VIG-21-01.

### AR-11: raw-source quota не является полным inspection-memory budget

`RequestSourceQuota` точно ограничивает retained original bytes. После ingest
те же bytes декодируются в Jackson object tree, decoded `String` values,
fragment/gap collections, detector preflight arrays и window substrings. Эти
объекты не входят в raw-source byte accounting. Ограниченный input делает рост
конечным, но опубликованный load profile не показывает его коэффициент на
граничных shapes и при одновременных owners.

Дополнительный latency multiplier возникает из независимой последовательной
evaluation каждого fragment. Это является принятой semantics и не объявляется
дефектом без измерения. VIG-21-02 поэтому остаётся test-only qualification:
сначала воспроизводимые process measurements, затем отдельная RED-first issue,
только если один из обязательных safety outcomes нарушен.

### AR-12 и AR-13: test gates должны доказывать observation, а не timing

Один forced full build прошёл, поэтому open upstream race остаётся
`unverified`. Однако отсутствие owner в `WORK_ITEMS.md` делает известную
неопределённость невидимой для delivery frontier.

Streaming case уже содержит конкретный fixture defect: timestamps после
completion допускают scheduling inversion. Детерминированный seam должен
сначала дождаться первого client body chunk, затем разрешить upstream записать
последний chunk. Это прямо доказывает отсутствие full aggregation и не зависит
от относительной скорости callback threads.

## Process gaps

- `.papercuts.jsonl` хранит полезную диагностику, но не заменяет backlog: у
  открытых major/minor test gaps нет статуса, estimate и completion protocol.
- Current roadmap смешивает исторический milestone baseline и текущий runtime,
  поэтому фраза `Production milestone достигнут` выглядит шире фактического
  evidence profile.
- `build` и work-item validator являются сильными gates, но long-running
  inspection qualification не входит в обычный `check`. Это нормально только
  при точном ограничении публичных claims профилем VIG-18.
- Bundled validator skill не используется как evidence: open papercut
  `pc_060bc5a84c3e` фиксирует несовместимость с project metadata. Канонический
  project adapter `./gradlew validateWorkItems` имеет приоритет.

## Готовность следующего этапа

| Область | Готовность | Причина |
|---|---|---|
| Controlled request-side shadow deployment | Conditional ready | Основной path, exact replay, safe outcomes и normal-profile load доказаны; audit delivery и max-shape resource envelope ещё не закрыты. |
| Complete MVP audit capability | Not ready | Нет гарантированного minimal decision trail и утверждённого delivery/storage contract. |
| Response/SSE inspection and secure spill | Draft by design | EPIC-20 корректно блокирует implementation до human-owned lifecycle, encryption и crash-cleanup decisions. |
| Enforcement `BLOCK`/`MASK`/`REMOVE` | Not in current scope | Startup policy запрещает non-ALLOW reactions, reverse mapping и response atomicity не реализованы. |
| Broader OpenAI APIs | Not in current scope | Поддерживается только pinned Chat Completions request contract. |

## Рекомендуемый порядок

1. VIG-21-01 фиксирует минимальный audit durability contract и публикует
   independently grabbable implementation leaves без изменения текущего logger.
2. VIG-21-02 измеряет max accepted request shapes на packaged process и либо
   подтверждает envelope, либо открывает отдельный production defect.
3. VIG-21-03 и VIG-21-04 независимо стабилизируют две evidence ветви.
4. VIG-21-05 после предыдущих результатов синхронизирует roadmap, docs и
   frontier, не переписывая исторические reports.

## Coverage matrix

| Review finding | Child issue | Тип результата |
|---|---|---|
| AR-10 | VIG-21-01 | Documentation-only contract and implementation decomposition |
| AR-11 | VIG-21-02 | Test-only packaged resource qualification |
| AR-12 | VIG-21-03 | Test-only deterministic upstream-error evidence |
| AR-13 | VIG-21-04 | Test-fixture correction with no production behavior change |
| AR-14 | VIG-21-05 | Documentation/status consistency change |

## Unresolved decisions

- Какая durability boundary считается минимальным обязательным audit trail:
  application-owned WAL, acknowledged external delivery или иной mechanism.
- Как audit path ведёт себя при disk/collector exhaustion и shutdown, не
  блокируя Netty event loop и не теряя решение молча.
- Какой measured heap/RSS envelope принимается для `8 MiB`, fragment-dense и
  gap-dense requests на production hardware.

Эти решения намеренно не подменены defaults в ревью или issues.
