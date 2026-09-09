# REQUEST enforcement implementation evidence

Current normative rules live in
[REQUEST enforcement](../spec/requirements/request-enforcement.md),
[policy engine](../spec/requirements/policy-engine.md) and
[bounded request source](../spec/requirements/request-source.md). This document
retains applicable implementation observations and exact completed commands.
It is not a requirement owner or a historical issue archive.

The observations below belong to their recorded implementation snapshot.
Documentation-only catalog migration did not rerun process, OCI, load or full
runtime suites and does not turn earlier results into current performance
evidence. Current status and remaining gaps are in
[requirements coverage](requirements-coverage.md#policy-request-source-and-request-enforcement).

## Evidence map

| Contract | Runtime path | Public observation and independent oracle |
|---|---|---|
| Selection/startup | `PolicyConfiguration` -> existing `PolicySelector`/`PolicyEngine` -> request/response workflows | Explicit empty, disabled, unmatched, selected and overridden snapshots; literal original bytes, phase-specific detector counts and actual applied policy ID; no analysis pair without detector invocation. |
| REQUEST reactions | Executable REQUEST validation and typed HTTP mapping | Only clean `ALLOW` and error `BLOCK` load; detected `ALLOW`/`MASK`/`BLOCK` reach their real outcomes; old config exits `2`; literal `403`/`503`, `Retry-After` and upstream count zero are asserted independently. |
| Field classification | Protocol parser -> immutable `RequestFragmentSource` -> workflow | Each named field/role/root/container publishes its class and locator; actual raw `MASK`, original replay or structural `403` is checked with independent JSON and invocation-count oracles. |
| Aggregation | Existing `ReactionAggregator`/`ReactionPlan` over complete fragment evaluation | Literal outcome, selected union, body and counts for both fragment/snapshot orders; distinct detector contributions, clean/gap controls and typed/deadline failures prove priority without a duplicated production calculation. |
| Marker and raw rewrite | `RequestMaskingFormatter` -> `RequestRewritePlanner` -> patched source replay | Ten marker types and boundary budgets use literal replacements; Unicode/escaped input, unknown bytes, exact headers and patched length remain independently observable. |
| Bounded ownership | `BoundedRequestSourceOwner` -> `ReplayReadyRequest` -> transport | Original segments, compact patch plan and bounded scratch stay owned through the terminal callback; success, rejection, cancellation, peer close, shutdown and illegal interleavings return public owner/byte/segment counters to baseline. |
| Audit/telemetry | `RequestAnalysisLifecycle`/`ShadowAuditLogger`, metrics and tracing | Exact JSONL outcomes/reactions/counts, pair absence, cancellation, sink failures and privacy sentinels are observed per channel; no-upstream rejection has no upstream duration/span. |
| Packaging/startup consumers | Shared policy fixture, `GatewayProcessFixture`, installed distribution and OCI smoke | Installed original/full/short MASK, policy/structural BLOCK, empty snapshot and old-config rejection; OCI uses mounted policies, exact hashes and real upstream counters. |

## Exhaustive quantified cases

- Selection: `EMPTY_SNAPSHOT`, `DISABLED_ONLY`, `RESPONSE_ONLY`,
  `REQUEST_ONLY`, `URL_MISS`, `MODEL_MISS`, `USER_MISS`, `GROUP_MISS`,
  `SELECTED_PII`, `OVERRIDDEN_GLOBAL`. Per-phase invocation counts and selected
  ID are literals; `SELECTED_PII` actually produces `DETECTED`.
- Startup reactions: clean `ALLOW`/`BLOCK`/`ALLOW_MASK`/`BLOCK_MASK`/missing/
  wrong type and error `BLOCK`/`ALLOW`/`ALLOW_MASK`/`BLOCK_MASK`/missing/wrong
  type, each enabled, disabled and overridden. Runtime typed/exception/deadline
  failures each combine with configured detected `ALLOW`/`MASK`/`BLOCK`.
- Structural fields: function/custom/legacy definition, call and choice names;
  allowed/custom names; properties/patternProperties/dependentSchemas keys;
  modern/legacy/custom arguments; message and response-schema names; schema
  enum/const/default/pattern; Lark/regex grammars; country/region/city/timezone.
  Each has actual MASK finding, ALLOW finding and clean MASK control.
- Free text: scalar/part message text, refusal, function/custom/legacy
  descriptions, schema title/description/example, filename, reasoning text/
  summary and scalar/part prediction. Message text/name exercises all six
  roles. Schema fields and keys exercise modern function parameters,
  deprecated function parameters and response-format roots, including escaped
  nested keys.
- Schema containers: properties, patternProperties, dependentSchemas, `$defs`,
  definitions, items, contains, additionalProperties, not, if/then/else,
  propertyNames, prefixItems, allOf/anyOf/oneOf and schema-valued legacy
  dependencies. Each root/container contrasts structural const with free-text
  description.
- Opaque argument/input forms: modern arguments, deprecated arguments and
  custom input each contain plain PII, nested JSON string PII, numeric PII, key
  PII, malformed inner JSON PII, empty string and clean text under MASK and
  ALLOW. The full decoded string is not parsed as an inner language; literal
  outer bytes prove no reconstruction.
- Aggregation: `ALL_CLEAN`, `DETECTED_ALLOW_ONLY`, `NO_APPLIED_POLICY`,
  `TEXT_MASK_ONLY`, `TEXT_MASK_PLUS_CLEAN`, `TEXT_MASK_PLUS_ALLOW`,
  `DETECTED_BLOCK`, `BLOCK_PLUS_TEXT_MASK`,
  `STRUCTURAL_MASK_PLUS_TEXT_MASK`, `BLOCK_PLUS_CLEAN`,
  `STRUCTURAL_MASK_PLUS_CLEAN`, `STRUCTURAL_MASK_PLUS_ALLOW` and error plus
  ALLOW/text MASK/BLOCK/structural MASK. Both fragment and snapshot orders are
  covered; each error mix uses typed error and deadline.
- Overlap: disjoint, duplicate, nested, partial and adjacent spans, equal/mixed
  markers and both input orders. ALLOW findings never expand the selected MASK
  union. Literal endpoints and replacements do not call the production
  normalizer.
- Markers: email, card, phone, IP, IBAN, INN, SNILS, passport, OMS and generic
  PII marker at budgets 1, 2, 3, L-1, L and L+1. Multibyte/surrogate,
  Unicode/escape, invalid span/marker, direct/escaped IP and exact configured
  ingress limit/+1 retain independent source/output bytes and finding types.
- Gaps: image, audio, file, opaque audio reference and opaque reasoning, each
  combined with gap-only, clean, text MASK, structural MASK, BLOCK and error;
  empty text and no-policy remain independent controls.
- Headers: `Content-MD5`, `Digest`, `Content-Digest`, `Repr-Digest`
  individually/all x fixed/chunked x ALLOW/MASK. Preference headers remain;
  mixed-case/repeated `Connection` nominations are stripped at real upstream.
- Source demand: inside/start/end/cross-two/cross-three/adjacent/final-suffix/
  whole-source patches x no demand/one/batch/unbounded/zero/negative. Invalid,
  expanding, unsorted, overlapping and foreign-owner plans fail without
  stealing ownership.
- Audit: clean/ALLOW, detected/ALLOW/MASK/BLOCK, gap/ALLOW and error/no reaction.
  Unsupported, malformed, identity/context/source/no-policy and pre-analysis
  cancellation have no pair. Partial cancellation retains only completed
  fragment counts and known coverage. Slow/full/throwing sinks preserve HTTP
  and readiness; every output uses its own privacy sentinel and allowlist.

## Lifecycle owner and terminal matrix

| Named path | Owner and terminal event | Public observation / independent oracle |
|---|---|---|
| Before-analysis cancellation | Ingest source closes before first evaluation | Reservations zero; no detector, audit or upstream. |
| During-analysis cancellation | Workflow task closes while a later fragment is held | One ERROR pair with completed finding/counts, no upstream and quota zero. |
| Handle publication | Cancellation before, during and after handle install | Test-owned close callback once; task and completion cancellation observed in both orders. |
| Preparation failure | Workflow owns source until planner rejection | Location/span/marker/owner-binding failures produce literal `503`/ERROR, zero upstream and zero quota. |
| Ready cancellation/shutdown | Validated ready owner loses before handoff claim | Original/patched plan remains owned until published cancellation/admission result; one pair, no upstream, quota zero. |
| Ready close/handoff throw | Ready owner closes or transfer callback throws | Callback count is exact, no retry occurs and owner/quota close for ALLOW and MASK plans. |
| Replay success/pending final output | Source owns segments/plan/scratch through final callback | Literal original/patched bytes and reservations remain through final suffix demand, then reach zero. |
| Replay cancellation/subscriber failure | Sole lease cancels or subscriber throws | Actual first ALLOW/MASK bytes, no retry/further output, one terminal failure where applicable and quota zero. |
| Owner close during replay | Close races initial/final `onNext` | Borrowed bytes and original quota remain valid until callback return, then reach zero in both orders. |
| Invalid demand | Sole subscriber requests `0` or `-1` while callback is held | No overlapping `onError`; borrowed bytes remain intact; one terminal failure and cleanup follow callback return. |
| Peer/client close after claim | Transport has copied replay bytes and peer aborts | Real prefix/length, quota retained while held, competing `503`, safe peer failure/cancellation, then zero counters and renewed admission. |
| Shutdown during active replay | Installed process receives TERM during held upload | ALLOW/MASK prefix, readiness `503`, bounded exchange termination and exit `0`/`143`, one upstream/audit pair. |
| Double transfer/subscription/view conflict | First owner wins competing access | Second access fails without disturbing literal first output or releasing reservations early. |

## Completed command observations

The following results were definitive successful exits at the implementation
snapshot. Failed or superseded diagnostic attempts are not current evidence.

- Full request HTTP suite:
  `./gradlew test -x processTest --tests 'io.vigilant.gateway.proxy.RequestInspectionE2eTest'`
  completed with exit `0` in `3m29s`.
- Schema-key collision control:
  `./gradlew test -x processTest --tests 'io.vigilant.gateway.proxy.RequestInspectionE2eTest.schema key collision and clean structural text mask controls'`
  completed with exit `0` in `8s`. All three schema roots produced exact
  structural `403`, zero upstream and two email findings; the clean structural
  key control produced literal raw MASK bytes and one finding.
- Full process suites:
  `./gradlew processTest --tests 'io.vigilant.gateway.PiiShadowProxyProcessTest' --tests 'io.vigilant.gateway.MainTest'`
  completed with exit `0` in `3m2s`, including configured reactions, empty
  snapshot, stdout matrix, startup rejection and held-upload shutdown.
- `./gradlew installDist` completed with exit `0` in `1s`.
- The successful OCI smoke completed after a `6s` host prerequisite and `2m6s`
  container artifact build. It printed `OCI ALLOW passed`, `OCI MASK passed`,
  `OCI BLOCK passed`, `OCI STRUCTURAL_BLOCK passed` and
  `OCI OLD_CONFIG passed`; ALLOW/MASK used exact SHA-256 and upstream counts,
  BLOCK cases used safe errors and upstream zero, old config exited `2`.
- The definitive full `./gradlew build` completed with exit `0` in `16m6s`.
  Completed XML contained 57 process tests, 1,297 main tests and 36 work-item
  contract tests, each with zero failures, errors or skips. A final consistency
  update then passed `./gradlew validateWorkItems` in `939ms` and an up-to-date
  `./gradlew build` in `960ms`.

## Canonical reuse and evidence boundary

- Selection/order/overrides reuse `PolicyValidator`, `PolicySelector` and the
  immutable `ReactionPlan`; there is no second comparator, hidden policy or
  new schema.
- JSON parsing reuses one tree/token pass, pointer escaping,
  `decodeJsonUnit` and canonical masking order. Response algorithms and output
  remain unchanged.
- `TextMasker` retains shared marker validation; request formatting only
  shortens markers. Equal replacements are interned per request; source owns
  the immutable patch snapshot and bounded output scratch.
- Source/quota public counters, `GatewayTestFixture.awaitUntil`, raw HTTP
  helpers, `GatewayProcessFixture` and the shared startup policy fixture remain
  canonical. Expected bytes, statuses and JSON are independent literals.
- Packaged smoke reuses the installed/OCI launchers, non-reused loopback port,
  bounded polling and SHA-256 oracle. It does not add a report calculation or
  throughput claim.

The inactive `config/qualification/politics-durability-timeout.conf` has no
executable consumer and no active startup consumer selects it. Historical
performance readers of `policy.shadow_decision` do not prove current
request/response enforcement latency. No new performance/SLO, protocol,
reaction, configuration or persistence capability is claimed here.
