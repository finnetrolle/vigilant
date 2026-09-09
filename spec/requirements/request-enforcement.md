# REQUEST enforcement

Нормативный owner выбора REQUEST policy result, field classification,
ALLOW/MASK/BLOCK, exact source rewrite и one-shot upstream handoff для
[MVP-01, MVP-03 и MVP-04](../MVP_FUNCTIONS.md#mvp-01-enforcement-обоих-направлений)
и [PROXY-02](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#proxy-02-lossless-forwarding-and-mutation).
Recognized protocol fields принадлежат
[Chat Completions request](chat-completions-protocol.md#request), policy
selection/engine - [policy engine](policy-engine.md), request memory lifecycle -
[bounded source](request-source.md), exact wire errors -
[HTTP gateway](http-gateway.md#inspection-error-matrix).

## Request boundary

Для supported authenticated Chat Completions request gateway до первого
upstream byte выполняет descriptor/identity checks, complete bounded ingest,
single protocol parse, context selection и required detector evaluations.
REQUEST и RESPONSE используют независимые policy sets и representations.
Request workflow не пересобирает body из DTO и не отправляет provisional bytes.

## Selection

PII inspection включается только explicit applied policies immutable startup
snapshot. Hidden global/default policy и mandatory global coverage отсутствуют.
Файл политик остаётся обязательным, но `policies = []` допустим.

Без applied REQUEST policy detector и REQUEST analysis audit pair не
запускаются. Original source replay-ится exact. Identity, descriptor, protocol
validation, source admission и request-to-response context handoff остаются
обязательными.

Полная selection matrix:

| Case | Snapshot/context | REQUEST / RESPONSE detector |
|---|---|---|
| `EMPTY_SNAPSHOT` | `policies=[]` | нет / нет |
| `DISABLED_ONLY` | только disabled PII policies | нет / нет |
| `RESPONSE_ONLY` | matched enabled RESPONSE policy | нет / есть |
| `REQUEST_ONLY` | matched enabled REQUEST policy | есть / нет |
| `URL_MISS` | REQUEST policy не совпала по URL | нет / без изменения |
| `MODEL_MISS` | REQUEST policy не совпала по model | нет / без изменения |
| `USER_MISS` | REQUEST policy не совпала по USER | нет / без изменения |
| `GROUP_MISS` | REQUEST policy не совпала по GROUP | нет / без изменения |
| `SELECTED_PII` | enabled REQUEST policy совпала | есть / независимо |
| `OVERRIDDEN_GLOBAL` | scoped matched policy overrides global REQUEST policy | один запуск на fragment для applied set / независимо |

## Request reactions and priority

Для detected REQUEST допустимы ровно `ALLOW+[]`, `ALLOW+[MASK]` и `BLOCK+[]`.
MASK является derived action, а не новым disposition. `BLOCK+[MASK]`, REMOVE,
unknown values и неверные types отклоняются strict startup validation.

REQUEST `clean` обязателен и допускает только ALLOW без transformations.
REQUEST `error` обязателен и допускает только BLOCK без transformations.
Оба правила проверяются для enabled, disabled и потенциально overridden
policies. Silent correction, ignored policy и fail-open migration запрещены.

Полная startup matrix для каждого state:

| State | Cases; каждый в enabled, disabled и overridden policy |
|---|---|
| `clean` | `CLEAN_ALLOW`, `CLEAN_BLOCK`, `CLEAN_ALLOW_MASK`, `CLEAN_BLOCK_MASK`, `CLEAN_MISSING`, `CLEAN_WRONG_TYPE` |
| `error` | `ERROR_BLOCK`, `ERROR_ALLOW`, `ERROR_ALLOW_MASK`, `ERROR_BLOCK_MASK`, `ERROR_MISSING`, `ERROR_WRONG_TYPE` |

Только CLEAN_ALLOW и ERROR_BLOCK валидны. Старый REQUEST snapshot с
`error=ALLOW` требует explicit operator update и завершает startup с code `2`.
Transport-neutral engine и RESPONSE validation не сужаются этим rule.

Workflow оценивает fragments в canonical source ordinal order. Policy BLOCK
или structural MASK в раннем fragment не скрывает technical error более позднего
fragment. Domain fail-fast внутри одной fragment evaluation сохраняется.
Итоговый приоритет:

1. любой detector error, deadline, invalid rewrite/source state или невозможность
   завершить workflow - request `503`;
2. иначе любой policy BLOCK или selected structural MASK - request `403`;
3. иначе хотя бы одна free-text MASK instruction - patched replay;
4. иначе - exact original replay.

Request 403/503 не начинает upstream handoff. Exact bodies и Retry-After
принадлежат [HTTP matrix](http-gateway.md#inspection-error-matrix).

## Field classification

Parser назначает каждому recognized fragment immutable ordinal, locator,
field class и source identity; free-text string также получает raw opening
quote location. Classification зависит от exact parsed field, а не только от
semantic kind или текстового JSON Pointer. Полный recognized vocabulary и
schema shapes принадлежат [protocol field map](chat-completions-protocol.md#semantic-field-map).

Selected MASK finding в любом structural case блокирует весь request. ALLOW
finding в том же field не блокирует. Полная structural matrix:

| Named cases | Recognized source |
|---|---|
| `FUNCTION_DEFINITION_NAME`, `CUSTOM_DEFINITION_NAME`, `LEGACY_FUNCTION_DEFINITION_NAME` | modern/custom/deprecated tool definition names |
| `FUNCTION_CALL_NAME`, `CUSTOM_CALL_NAME`, `LEGACY_FUNCTION_CALL_NAME` | modern/custom/deprecated call names |
| `FUNCTION_CHOICE_NAME`, `CUSTOM_CHOICE_NAME`, `LEGACY_FUNCTION_CHOICE_NAME` | modern/custom/deprecated selected choice names |
| `ALLOWED_FUNCTION_NAME`, `ALLOWED_CUSTOM_NAME` | allowed-tools function/custom names |
| `PROPERTIES_KEY`, `PATTERN_PROPERTIES_KEY`, `DEPENDENT_SCHEMAS_KEY` | model-visible schema member names |
| `FUNCTION_ARGUMENTS`, `LEGACY_FUNCTION_ARGUMENTS`, `CUSTOM_TOOL_INPUT` | whole textual arguments/input |
| `MESSAGE_NAME` | `messages[*].name` для developer/system/user/assistant/tool/function |
| `RESPONSE_SCHEMA_NAME` | response JSON schema name |
| `SCHEMA_ENUM`, `SCHEMA_CONST`, `SCHEMA_DEFAULT`, `SCHEMA_PATTERN` | recognized schema string constraints |
| `CUSTOM_GRAMMAR_LARK`, `CUSTOM_GRAMMAR_REGEX` | custom grammar definition по exact syntax |
| `LOCATION_COUNTRY`, `LOCATION_REGION`, `LOCATION_CITY`, `LOCATION_TIMEZONE` | approximate web-search location strings |

Function arguments, legacy arguments и custom input всегда остаются одной
opaque decoded string. Plain text, valid nested JSON с PII в string/number/key и
malformed nested JSON под selected MASK дают 403. Empty или no-finding value
под MASK не блокирует. Inner grammar/JSON не парсится и не ремонтируется.

Только следующая полная free-text matrix допускает exact-span rewrite:

| Named cases | Recognized source |
|---|---|
| `MESSAGE_SCALAR_TEXT`, `MESSAGE_PART_TEXT` | scalar/part content всех шести roles |
| `REFUSAL_TEXT` | assistant refusal part |
| `FUNCTION_DESCRIPTION`, `CUSTOM_DESCRIPTION`, `LEGACY_FUNCTION_DESCRIPTION` | modern/custom/deprecated descriptions |
| `SCHEMA_TITLE`, `SCHEMA_DESCRIPTION`, `SCHEMA_EXAMPLE` | schema title/description/string examples |
| `FILE_NAME` | user file filename; file data/ID остаются gap |
| `REASONING_TEXT`, `REASONING_SUMMARY` | open assistant reasoning |
| `PREDICTION_SCALAR_TEXT`, `PREDICTION_PART_TEXT` | prediction scalar/part content |

Schema key/value cases обязательны во всех трёх roots:
`tools[*].function.parameters`, deprecated `functions[*].parameters` и
`response_format.json_schema.schema`. Для каждого recognized walker container
сравниваются structural `const` и free-text `description`:
`properties`, `patternProperties`, `dependentSchemas`, `$defs`, `definitions`,
`items`, `contains`, `additionalProperties`, `not`, `if`, `then`, `else`,
`propertyNames`, `prefixItems`, `allOf`, `anyOf`, `oneOf` и schema-valued
legacy `dependencies`.

Обязательные contrasts: schema key `description` против annotation
`description`; schema key `examples` против string `examples[*]`; filename
против message name; schema title против response schema name; nested schema
node и JSON Pointer escaping `~`/`/`. Number/boolean/null/object/array values,
fixed keywords и arbitrary unknown fields не становятся новым text vocabulary.

IMAGE, AUDIO, FILE, OPAQUE_AUDIO_REFERENCE и OPAQUE_REASONING остаются explicit
inspection gaps и не переписываются. Unknown content discriminator,
malformed/ambiguous content и unsupported schema остаются fail-closed.

## Request marker

Canonical finding type и full marker сохраняются как policy/audit metadata.
Request representation заменяет весь selected decoded PII span и никогда не
оставляет prefix/suffix PII ради budget.

Пусть `N = endUtf8 - startUtf8` после canonical overlap/adjacency union:

- если full ASCII marker length не больше N, marker сохраняется;
- при `N >= 3` сохраняются brackets, внутреннее слово обрезается справа до
  `N - 2` ASCII characters;
- при `N = 1` используется `*`, при `N = 2` используется `**`;
- zero/invalid span остаётся technical error.

Например, `1.1.1.1` и `[IP_MASKED]` дают `[IP_MA]`, `a@b.co` и
`[EMAIL_MASKED]` дают `[EMAI]`. Budget измеряется в decoded UTF-8 bytes, не в
Kotlin Char, code points или raw JSON bytes. Direct и escaped representations
одного decoded value дают одинаковый marker.

Finite conformance matrix применяет budgets `1`, `2`, `3`, `L-1`, `L`, `L+1`
к каждому marker: `[EMAIL_MASKED]`, `[CARD_MASKED]`, `[PHONE_MASKED]`,
`[IP_MASKED]`, `[IBAN_MASKED]`, `[INN_MASKED]`, `[SNILS_MASKED]`,
`[PASSPORT_MASKED]`, `[OMS_MASKED]`, `[PII_MASKED]`. Отдельно проверяются
multibyte UTF-8, surrogate pair, escaped JSON и equal/mixed marker unions.
RESPONSE сохраняет full markers.

## Exact source rewrite

ALLOW и no-policy replay-ят exact original bytes. MASK меняет только validated
raw ranges selected free-text spans. Whitespace, field order, unknown metadata,
number spelling, untouched escapes, Unicode spelling и prefix/suffix string
остаются source-exact. Structural JSON parse и detector execution не
повторяются; DTO serialization и whole rewritten body copy отсутствуют.

Planner sequentially декодирует только selected raw string literals через
canonical JSON scalar rules и проверяет source identity, locator, decoded UTF-8
boundaries, raw ranges, ordering и exact non-expanding output length. Invalid
location/span/marker/owner binding даёт technical 503 до upstream без original
fallback. Patch plan immutable, compact и owner-bound; source выполняет
[bounded replay](request-source.md#original-and-patched-replay).

Для MASK `Content-Length` равен validated output length, даже если inbound body
был chunked. `Content-MD5`, `Digest`, `Content-Digest` и `Repr-Digest`
удаляются. `Want-Content-Digest`, `Want-Repr-Digest`, accepted Authorization и
остальные end-to-end headers сохраняются; hop-by-hop/Connection-nominated
headers, scheme/authority/path/Host принадлежат gateway transport. Digest не
пересчитывается, request trailers, compression и signing не добавляются.

## Aggregation and gaps

Обязательная outcome matrix:

| Cases | Outcome |
|---|---|
| `ALL_CLEAN`, `DETECTED_ALLOW_ONLY`, `NO_APPLIED_POLICY` | Original replay |
| `TEXT_MASK_ONLY`, `TEXT_MASK_PLUS_CLEAN`, `TEXT_MASK_PLUS_ALLOW` | Exact patched replay |
| `DETECTED_BLOCK`, `BLOCK_PLUS_TEXT_MASK`, `STRUCTURAL_MASK_PLUS_TEXT_MASK` | 403, no upstream |
| `ERROR_PLUS_ALLOW`, `ERROR_PLUS_TEXT_MASK`, `ERROR_PLUS_BLOCK`, `ERROR_PLUS_STRUCTURAL_MASK` | 503, no upstream |

Каждая mixed row выполняется с обоими fragment orders и policy snapshot orders.
Typed detector error и policy deadline проверяются отдельно. Несколько policies
одного fragment используют один detector invocation.

Within one fragment обязательны disjoint, duplicate, nested, partial overlap и
adjacent spans; для duplicate/nested/overlap/adjacent - equal и mixed markers в
обоих input orders. Spans разных fragments не объединяются.

Для каждого gap kind `IMAGE`, `AUDIO`, `FILE`, `OPAQUE_AUDIO_REFERENCE`,
`OPAQUE_REASONING` обязательны gap-only, plus-clean, plus-text-MASK,
plus-structural-MASK, plus-BLOCK и plus-error. Gap values остаются unchanged;
BLOCK/error запрещают весь request. Gap-only без error даёт
`INSPECTION_GAP/ALLOW`; ordinary empty text без gap даёт `CLEAN/ALLOW`;
no-policy не создаёт audit pair. Synthetic empty evaluation не считается
inspected fragment.

## Lifecycle and handoff

Complete owner атомарно передаётся workflow. Expected parser/source/context
reject, exception или cancellation до replay transfer закрывает owner.
Forward содержит `ReplayReadyRequest`, который владеет prepared original/patch
до единственного `transferTo`.

`transferTo` вызывается ровно один раз. Successful callback означает, что
publisher lifecycle принял ownership. Synchronous callback failure закрывает
resource и пробрасывается. Close до transfer освобождает owner и запрещает
handoff; close после accepted transfer не освобождает active replay раньше
terminal callback. Переходы ready/transferring/transferred/closed thread-safe.

Полная terminal matrix:

| Case | Required result |
|---|---|
| `BEFORE_ANALYSIS_CANCEL` | no detector/audit/upstream; source released |
| `DURING_ANALYSIS_CANCEL` | cancelled work, one ERROR completion attempt, no handoff, released source |
| `PREPARE_FAILURE` | 503 before upstream, one ERROR attempt, released source |
| `READY_CANCEL` | cancellation wins claim, no upstream, ready source closed |
| `READY_CLOSE` | no callback/subscription; repeated close idempotent |
| `HANDOFF_THROW` | no retry; ready source released; failure follows transport boundary |
| `REPLAY_SUCCESS` | exact ALLOW/MASK output; release after last callback |
| `REPLAY_CANCEL` | no later bytes; source/plan released |
| `REPLAY_SUBSCRIBER_FAILURE` | one terminal failure; no second transfer |
| `OWNER_CLOSE_DURING_REPLAY` | safe stop, one cleanup, no later bytes |
| `LAST_INPUT_PENDING_OUTPUT` | quota retained until pending output or cancel |
| `PEER_CLOSE` | safe transport failure, replay cancelled, quota restored; sent prefix may exist |
| `SHUTDOWN_BEFORE_HANDOFF` | no new upstream; cancellation and cleanup |
| `SHUTDOWN_ACTIVE_REPLAY` | readiness 503, bounded drain/cancel, process exit |
| `DOUBLE_TRANSFER`, `DOUBLE_SUBSCRIBE`, `VIEW_REPLAY_CONFLICT` | second access rejected without disturbing first owner |

Race evidence holds both orders for cancellation before/after handle and claim,
and close before/after last callback. No released ephemeral port reuse or
sleep-based proof. After upstream exchange starts, cancellation may leave an
already sent prefix; unmasked retry/fallback is forbidden.

## Audit and privacy boundary

Analysis lifecycle starts immediately before first detector invocation and
completes after final decision/validated replay preparation but before allowed
handoff. No applied policy, descriptor/identity/source/parser/context rejection
or cancellation before analysis creates no REQUEST pair. После actual start
каждый terminal path делает одну best-effort completion attempt. Logger failure
не меняет HTTP/readiness/handoff.

Successful completion reports actual reaction ALLOW/MASK/BLOCK. ERROR содержит
stable code и не содержит reaction. Outcome precedence for safe aggregate is
ERROR, DETECTED, INSPECTION_GAP, CLEAN. Counts come from canonical detector
outcomes once per fragment, not per policy or shortened marker.

Exact stdout schema, metrics/traces and privacy belong to
[observability contract](observability.md#analysis-lifecycle-audit). Body, PII value/span,
locator, headers, Bearer, user/groups, session, query and raw exception are
forbidden in audit and client error. Safe policy/detector references remain
operator metadata.

## Boundaries

No new detector, protocol surface, structured argument container, reaction,
policy schema/default, hot reload/control plane, audit persistence, disk spill,
request trailer/compression/signing, performance SLO or bulk rename is implied.
RESPONSE enforcement is independent. Production symbols containing `Shadow`
do not change contract semantics.

## Conformance

Independent evidence uses strict config tests, public parser/source/formatter/
planner contracts, real Armeria gateway/upstream, installed process and OCI
fixtures. Every finite matrix above uses literal expected bytes/status/body and
test-owned counters/barriers. Existing observed commands/results are retained in
[request enforcement evidence](../../docs/request-enforcement-evidence.md).
[Coverage](../../docs/requirements-coverage.md#policy-request-source-and-request-enforcement)
records target/runtime/evidence gaps; publication of this owner is not a new
dynamic run or performance qualification.
