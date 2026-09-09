# Identity и policy context

Этот detailed owner определяет Bearer identity и context для
[MVP-04/05](../MVP_FUNCTIONS.md#mvp-05-identity),
[CONC-03/04](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md#ресурсы-и-cancellation).
Фактическая интеграция описана в [runtime contract](../../docs/runtime-contract.md#bearer-identity),
настройки оператора - в [configuration](../../docs/configuration.md),
проверки и границы evidence - в [coverage](../../docs/requirements-coverage.md#identity-evidence).
Identity telemetry имеет отдельного [нормативного владельца](observability.md#identity).

## Startup selection

`environment` обязателен и принимает exact lowercase `development`, `test`,
`production`. `identity-mode` обязателен и принимает exact `DUMMY`, `JWT`,
`EXTERNAL`; `VIGILANT_ENVIRONMENT` и `VIGILANT_IDENTITY_MODE` используют те же
значения. Startup выбирает одну реализацию общего async cancellation-aware
`BearerIdentityExtractor` на весь lifecycle процесса.

| Environment | DUMMY | JWT | EXTERNAL |
|---|---|---|---|
| development | разрешён | разрешён | разрешён |
| test | разрешён | разрешён | разрешён |
| production | startup error | разрешён | разрешён, включая trusted HTTP endpoint |

Missing selector, alias, неверный case, unknown mode/environment, unknown или
legacy identity setting, отсутствующая обязательная настройка и settings
невыбранного mode дают safe startup failure с exit code `2`. Defaults режима,
композиции extractors, автоматического fallback, anonymous fallback и switching
без restart нет. `ANONYMOUS`, `TRUSTED_HEADERS`, `BASIC` и прежние
`identity-user-header`, `identity-groups-header`, `identity-trusted-cidrs`
не являются допустимой конфигурацией или compatibility aliases.

Configuration precedence: `env > file > default`. Параметры выбранного mode
валидируются при startup; server не запускается при ошибке. DUMMY/JWT не
создают Bridge client, cache, hasher или External semaphore. EXTERNAL startup
не делает lookup, health check или cache prewarm, поэтому недоступный Bridge
сам по себе не мешает startup.

## Single Bearer boundary

Для поддержанного descriptor identity проверяется до первого request-body
demand и LLM upstream call. Ровно один `Authorization` содержит RFC-token scheme;
`Bearer` сравнивается case-insensitive. Первый ASCII space разделяет scheme
и credential; оставшиеся token bytes не trim-ятся, не декодируются и не
нормализуются. Malformed separator, например tab внутри scheme, не принимается.

| Input | Результат |
|---|---|
| Missing Authorization | `401`, `{"error":"authentication_required"}`, `WWW-Authenticate: Bearer realm="vigilant"` |
| Well-formed другой scheme | тот же `401` challenge |
| Duplicate Authorization, включая одинаковые values | `400`, `{"error":"invalid_identity"}` |
| Malformed representation | тот же safe `400` |
| DUMMY: `Bearer` с пустым, whitespace или непустым credential | configured normalized identity; token игнорируется |
| JWT: пустой или невалидный compact credential | safe `400 invalid_identity` |
| EXTERNAL: пустой или whitespace-only credential | safe `400 invalid_identity`, Bridge не вызывается |
| Mixed-case Bearer с допустимым credential | выбранный extractor получает exact token; success разрешает дальнейшую обработку |

Каждый reject оставляет body и LLM upstream нетронутыми; local header rejects
не вызывают Bridge. Accepted Authorization остаётся transport-owned и
передаётся LLM upstream byte-for-byte вместе с остальными разрешёнными
end-to-end headers. Identity extraction не переписывает body или unrelated
headers. Raw credentials не входят в `PolicyContext` или safe error.
Общая HTTP матрица приведена в [runtime errors](../../docs/runtime-contract.md#request-side-errors).

## Normalized identity

Source user/group имеет ASCII grammar `[A-Za-z0-9][A-Za-z0-9._:@/\-]{0,127}`:
от 1 до 128 символов, первый alphanumeric; whitespace, blank, Unicode и
grammar-invalid значения отклоняются. Normalization - только lowercase с
`Locale.ROOT`, без trim. Groups - defensive immutable set, не более 128
значений; locale и последующая мутация входной collection не меняют result.

| Источник | User | Groups |
|---|---|---|
| DUMMY configuration | required `identity-dummy-user` | optional `identity-dummy-groups`, default empty; normalized duplicates дедуплицируются; более 128 unique values отклоняются |
| Validated JWT | required string `sub` | optional top-level array `groups`; missing даёт empty set, explicit null/non-array, non-string/blank/invalid member, duplicate после normalization или 129 unique values отклоняются |
| Bridge JSON | required string `user` | required array `groups`; empty допустим; missing/null/non-array, invalid member, normalized duplicate или 129 unique values являются protocol failure |
| Generic normalized context | допускает `user=null` | explicit immutable set; anonymous form имеет empty set |

Generic anonymous context сохраняется как domain contract: subject `ANY`
совпадает, USER/GROUP с anonymous empty identity не совпадают. Он не создаёт
anonymous production mode и не обходит обязательный Bearer boundary. Canonical
candidate с duplicate groups или ненормализованными values невалиден.

## Offline JWT

JWT проверяет локальный immutable pinned trust snapshot и не ограничен
конкретным поставщиком identity. `identity-jwt-issuer`, `identity-jwt-audience`
и непустой `identity-jwt-jwks` обязательны. Каждый JWK - RSA public key с
non-blank unique exact `kid`, valid Base64url unsigned `n` и `e`. Невалидный
JWK, duplicate kid и incomplete trust configuration отклоняются при startup.
Environment JWK override - strict JSON array объектов с полями только
`kty`, `kid`, `n`, `e`, без duplicate keys и private material.

| Проверка credential | Условие success; остальные варианты дают safe `400` до body demand |
|---|---|
| Compact form | ровно три непустых Base64url segments; valid object header и claims, без duplicate keys |
| Algorithm/key | exact `alg=RS256`; string non-blank `kid` выбирает ровно один configured key; missing/unknown kid и другой algorithm запрещены |
| Signature | valid RS256 signature для exact signing input выбранным pinned public key |
| Issuer | string `iss` точно равен configured issuer |
| Audience | string либо array только strings, содержащая configured audience; missing, неверная shape или mismatch отклоняются |
| Expiry | required integral `exp`, представимый Long; `now < exp`; exact expiry уже невалиден |
| Not-before | optional integral Long `nbf`; если присутствует, `now >= nbf`; future, null и invalid shape отклоняются |
| Identity | `sub`/`groups` проходят [normalized identity](#normalized-identity) после signature и trust claims |

Identity claims не считаются доверенными до cryptographic validation. Ротация
ключей выполняется deployment configuration: сначала добавить новый JWK,
затем удалить старый; каждый configured key принимается по своему kid.
Discovery, JWKS fetch/refresh, UserInfo, introspection, Admin API, token exchange,
refresh/persistence/cache и иной runtime identity I/O отсутствуют. Claim names
не настраиваются; nested paths и roles-as-groups mapping не поддерживаются.

## External Bridge

`identity-external-url` / `VIGILANT_IDENTITY_EXTERNAL_URL` обязателен без default:
absolute lowercase HTTP(S) URI с host; relative URL, absent host, иной scheme,
userinfo и fragment запрещены. Path и query разрешены и используются exact,
empty path использует `/`. Bridge trusted внутри deployment boundary; HTTP
допустим во всех environments. TLS/mTLS, certificate management и отдельная
service credential не добавляются Vigilant, защита сети принадлежит deployment.

Создатель admitted cache miss делает одну Bridge attempt:

```http
POST <exact configured path and query>
Authorization: Bearer <original token>
Accept: application/json
Content-Length: 0
```

Body отсутствует. Client headers, client query/body и request context в Bridge
не копируются; credential используется transient. Redirects не follow-ятся,
retries, discovery, circuit breaker, несколько providers и plugin registry
отсутствуют. Cache hit и присоединение к shared lookup не создают attempt.

Success требует `200 application/json` (включая `charset=utf-8`), JSON object
без duplicate keys с required `user` и `groups` из normalized contract.
Unknown top-level fields игнорируются для additive compatibility. Missing
`groups` не преобразуется в empty set. HTTP aggregation использует standard
bounded Armeria `maxResponseLength` (текущий default `10 MiB`); отдельной
identity size setting, custom `64 KiB` limit и streaming JSON parser нет.
Compressed response отдельно не запрещён.

| Provider outcome | Safe code |
|---|---|
| Каждый final status `201..599`, включая все 3xx, 401, 403, остальные 4xx/5xx | `PROVIDER_STATUS` |
| Missing/non-JSON media type; invalid UTF-8; malformed JSON; duplicate keys; non-object root | `INVALID_RESPONSE` |
| Missing/non-string/blank/grammar-invalid user; missing/non-array groups; любой non-string/blank/invalid member; normalized duplicates; 129 unique groups | `INVALID_RESPONSE` |
| Превышение standard aggregate limit | `INVALID_RESPONSE` |
| DNS/connect failure или premature incomplete connection close | `TRANSPORT_ERROR` |
| Deadline на acquisition, connect, request write, headers или полном body | `TIMEOUT`, active exchange отменён |
| Exhausted immediate Bridge admission или cache waiter slots | `OVERLOADED`, новая attempt не выполняется |
| Client cancellation / forced shutdown | cancellation, без синтезированного HTTP response |

Каждый unavailable даёт только `503 Service Unavailable`, `Retry-After: 1`,
`Content-Type: application/json` и exact body:

```json
{"error":{"message":"Identity service unavailable.","type":"server_error","code":"identity_unavailable"}}
```

Никакие Bridge status/headers/body или exception details не раскрываются.
До identity success нет body demand или LLM upstream. Внутренний async result
имеет только `Resolved(NormalizedIdentity)` / `Unavailable(finite code)`;
client cancellation завершает future cancellation.

## Deadline и execution

`identity-external-timeout` / `VIGILANT_IDENTITY_EXTERNAL_TIMEOUT` имеет default
`1s`; valid positive duration должна представляться в `1..Long.MAX_VALUE`
nanoseconds. Malformed, zero, negative и overflow отклоняются safe startup
error `2`. Это safety bound, не performance SLO.

Один whole-exchange deadline начинается до connection acquisition и включает
pool wait, connect, request write, headers и весь response body. Join не
перезапускает срок. Timeout отменяет незавершённый exchange. Bridge использует
immediate nonfair semaphore с `N = inspection-max-concurrent-request-sources`:
N holders допускаются, N+1 сразу получает overload без очереди/Bridge call.
Permit освобождается ровно один раз при success, каждом provider/protocol/
transport failure, timeout, cancellation и shutdown. После release следующий
lookup допускается; повторный terminal callback не увеличивает capacity.

Общий `extract(headers): CompletableFuture<IdentityExtractionResult>`
инициируется на existing blocking-safe request executor. DUMMY возвращает
completed future, JWT выполняет локальную проверку, External связывает future
с async Armeria lookup. Continuation каждого caller исполняется на том же
request executor под своим request context, даже если shared completion
пришёл в контексте инициатора. На event loop нет blocking I/O, `join`/`get`
или ожидания identity. Cancellation распространяется до owned exchange.

## Cache settings

Cache существует только в EXTERNAL, отдельно на каждой replica, в памяти,
и теряется после restart. Сохраняются только successful normalized identities;
`Unavailable`, exceptional completion и cancellation никогда не кешируются.

| HOCON key | Environment override | Default | Принимаемые границы |
|---|---|---|---|
| `identity-external-cache-ttl` | `VIGILANT_IDENTITY_EXTERNAL_CACHE_TTL` | `10m` | positive Duration, `1ns..Long.MAX_VALUE ns` |
| `identity-external-cache-max-size` | `VIGILANT_IDENTITY_EXTERNAL_CACHE_MAX_SIZE` | `10000` | integer `1..Int.MAX_VALUE`, без eager allocation |

Overrides независимы: ни одной, только TTL, только size или обе settings через
file/env и env-over-file сохраняют `env > file > default` для каждого поля.
Явно заданное значение, даже default, запрещено в DUMMY/JWT. Zero, negative,
empty, whitespace-only, malformed, overflow и fraction для size отклоняются
через file и env: safe diagnostic называет setting и допустимую форму без raw
input/stack trace/secret, exit `2`. Disable-by-zero, unbounded mode и runtime
reload не поддерживаются.

## Cache keys и expiry

Отдельный `ExternalIdentityCacheKeyHasher` владеет 32-byte secret из
`SecureRandom`, созданным один раз при EXTERNAL startup. Key - полный
HMAC-SHA-256 от exact UTF-8 token, lowercase hex длиной 64 символа, без
normalization/truncation. Равные contents разных String дают равный key;
другой case/символ token или другой secret использует соответствующий другой
HMAC. JCA mutable state не разделяется небезопасно между concurrent calls.

Hasher не сохраняет token и не мемоизирует token/digest. Completed entries,
in-flight values/keys и callbacks не удерживают raw token; secret остаётся
в hasher. Каждый restart создаёт новый secret. Разные tokens одного user
занимают разные entries и сохраняют собственные groups/policy outcomes.

TTL - `expireAfterWrite` от successful получения и сохранения identity.
Monotonic ticker независим от wall-clock changes; время загрузки не входит
в TTL. При `TTL-1ns` entry ещё доступна, ровно `TTL` и `TTL+1ns` дают miss.
Частые hits срок не продлевают. После expiry следующий request ждёт fresh
lookup, concurrent callers присоединяются к нему; без запросов refresh нет.
При failure/timeout/overload после expiry stale identity не возвращается,
следующий request может выполнить новую попытку.

`maximumSize` ограничивает completed entries после Caffeine maintenance,
допуская временное превышение до eviction. Eviction учитывает частоту/давность;
конкретный victim не является стабильным контрактом. Evicted key даёт miss,
retained key - hit. Expired entry уже недоступна до физического удаления.
Это entry-count bound, не byte-level heap quota; normalized identity остаётся
bounded. TTL/size eviction не теряют и не дублируют pending lookup.

Invalidation происходит только при expiry, size eviction и close. Нет
negative cache, stale-on-error/while-revalidate, background refresh, prewarm,
admin flush/revoke, persistence, distributed cache или cross-replica sync.
Отдельные maintenance thread/pool, scheduler, LRU/weigher не создаются.

## Coalescing и caller ownership

Concurrent cold requests одного key разделяют один in-flight Bridge lookup;
разные keys независимы. Три callers одного cold key получают один success или
один и тот же finite failure (`PROVIDER_STATUS`, `INVALID_RESPONSE`, `TIMEOUT`,
`TRANSPORT_ERROR`, `OVERLOADED`) при одной delegate operation. Failure удаляет
in-flight generation и допускает fresh lookup.

Каждый caller получает отдельный future и immutable value. Он не может
подделать shared outcome или cache через `complete`, `completeExceptionally`,
`obtrudeValue`, `obtrudeException`, обе формы `completeAsync`, `orTimeout` или
`completeOnTimeout`. Cancellation/completion одного future не меняет другого.
Delegate exception до выдачи future и exceptional completion преобразуются
в safe `Unavailable(TRANSPORT_ERROR)`; cancellation остаётся cancellation.

Decorator удерживает максимум N ожидающих callers суммарно по всем keys,
включая joins, поэтому in-flight keys также не больше N. Slot получается
немедленно без очереди; N+1 cold caller получает `OVERLOADED` без join/delegate,
existing callers продолжают. Готовый hit работает при полном waiter limit и
не занимает slot или Bridge permit. Только создатель lookup берёт Bridge
permit; joined callers его не берут. Slots освобождаются ровно один раз для
каждого caller, и полный N снова доступен после всех terminal paths.

Success публикуется в cache до fan-out callers; in-flight ownership
освобождается без окна для duplicate lookup. Delegate calls, cancellation и
пользовательские callbacks выполняются вне общего lifecycle lock.

## Lifecycle

| Owner / событие | Обязательный результат |
|---|---|
| Shared lookup: success | все ещё ожидающие получают identity; cache готов до выдачи success; slots/Bridge permit освобождены |
| Shared lookup: каждый provider/protocol/transport failure | один safe исход всем ожидающим; failure не кешируется; slots/permit доступны для fresh lookup |
| Shared deadline: timeout | exchange отменён, ожидающие получают `TIMEOUT`; join не продлевает срок; late success игнорируется |
| Отмена инициатора при живом joined caller | только инициатор отменён, его slot возвращён; Bridge и остальные callers продолжают; последующий hit допустим |
| Отмена joined caller при живом инициаторе | только joined caller отменён, shared work продолжается |
| Отмена единственного либо последнего из нескольких callers | exchange отменён ровно один раз; cancelled generation не кешируется; новый request начинает fresh lookup |
| Повторная cancellation или late terminal callback | без double release, duplicate publication или повторного downstream effect |
| Partial cancellation joined caller до установки delegate future | инициатор продолжает lookup; только отменённый caller завершён |
| Close до установки delegate future | future отменяется сразу после установки; late result не публикуется |
| Late success старого поколения после нового lookup того же key | не заполняет cache и не удаляет новое поколение; removal условен по generation ownership |
| Success выиграл перед cancellation | success сохраняется, последующая cancellation не инвалидирует cache |
| Last cancellation, timeout либо close выиграли перед success | один соответствующий terminal outcome, late success не заполняет cache |
| Close пустого cache, с entries, с одним или несколькими active keys | entries/in-flight/hasher references удалены, все callers отменены, slots освобождены, owned delegates отменены |
| Повторный close; lookup после close даже по бывшему hit | idempotent close; cancelled future без delegate/Bridge и новой cache publication |
| Graceful shutdown | not-ready, server drain; admitted lookup может завершиться только в своём исходном deadline |
| Forced shutdown | active/shared lookup и callers отменены до закрытия factory, без LLM upstream handoff |

Future инициатора возвращается после установки delegate future; до этого
публично доступны cancellation joined caller и close. Отдельный executor или
production hook ради недоступного порядка не нужен. Гонки success, timeout,
client cancellation, shutdown выбирают один terminal result. Token не остаётся
в application-owned result, queued task, callback или exception после terminal.

`OutboundClientResources` владеет одним Armeria `ClientFactory`, upstream
`WebClient` и EXTERNAL-only отдельным Bridge client, decorator и hasher.
Endpoint pools разделены, threads/lifecycle общие; upstream timeout semantics
независимы от Bridge deadline. Decorator владеет completed cache, registry и
caller futures; Bridge владеет HTTP, parsing, admission, timeout и telemetry.
Caffeine types остаются внутри decorator, HTTP/normalization повторно в нём
не выполняются. Generic CacheStore/provider facade не вводятся.

Main cleanup: not-ready -> server drain -> inspection cleanup -> outbound ->
telemetry. Outbound idempotently пытается закрыть decorator, затем Bridge,
затем sole factory через `runAllCleanupActions`: ошибка первого или второго
действия не пропускает остальные, первая ошибка сохраняется, последующие
suppressed. Decorator отменяет owned delegate futures, но не закрывает сам
Bridge или factory. Освобождение heap делает JVM GC; гарантированное затирание
всех копий secret не обещается.

## Policy URL

Input - effective absolute HTTP(S) upstream URI после объединения configured
base path и inbound path; inbound gateway address не используется как key.
Нормализация чистая, deterministic, locale-independent и без DNS I/O.

| Часть | Canonical правило |
|---|---|
| Scheme/host | lowercase scheme и IDNA ASCII host; terminal DNS dot удалён |
| IPv6 | validated literal без DNS lookup, lowercase compressed canonical form, включая эквивалентные IPv4-mapped literals |
| Port | default HTTP 80 / HTTPS 443 удалён, non-default сохранён |
| Path | начинается с `/`, empty становится `/`, dot segments удаляются |
| Значимые path distinctions | repeated/trailing slash и case сохраняются |
| Percent encoding | uppercase hex, unreserved ASCII декодируется; reserved, включая encoded slash, остаются encoded |
| Query, fragment, userinfo | исключены полностью, не входят в context/error/log |
| Invalid escape, unsupported scheme, absent host, invalid IDNA/port | typed `INVALID_POLICY_URL`, без partial key/context и raw source |

Key имеет форму `scheme://authority/path`. Policy engine применяет свой
существующий case-insensitive exact matcher ко всему key; normalization не
реализует glob/regex, redirects или selection.

## Assembly и handoff

`PolicyContext(url, model, phase, user, groups)` immutable и не содержит
transport/protocol-specific types, raw headers/credentials, body, fragments,
provenance, gaps, protocol family/operation/transport или arbitrary map.
Assembler не зависит от `PolicyProvider`, не выполняет matching, parsing
или повторную normalization. Он принимает canonical typed URL, normalized
identity, explicit `REQUEST` phase и immutable versioned
`NormalizedProtocolAttributes(model)` из
[protocol layer](../../docs/openai-chat-completions.md). Schema-derived model
есть exact decoded non-blank request model; body parsing принадлежит protocol.

| Assembly input | Typed failure без partial context |
|---|---|
| Отсутствует URL, identity, phase или attributes | `MISSING_CONTEXT_INPUT` |
| Phase противоречит REQUEST assembly | `CONTRADICTORY_PHASE` |
| URL не canonical | `INVALID_NORMALIZED_URL` |
| Invalid normalized user/groups | `INVALID_NORMALIZED_IDENTITY` |
| Missing/blank/invalid model attributes | отсутствующий input либо `INVALID_PROTOCOL_ATTRIBUTES` по typed boundary |

Одинаковые normalized inputs дают структурно одинаковый context без влияния
locale или мутации исходных collections. Request snapshot создаётся один раз
и сохраняется до первого upstream call typed attribute в своём Armeria
`ServiceRequestContext`. Response получает тот же snapshot с единственным
изменением `phase=RESPONSE`: URL, request model, user и groups совпадают exact.
Reported response model не заменяет request model; phase задаёт точка вызова,
а не payload. REQUEST/RESPONSE независимо выбирают policy sets из одного
immutable startup snapshot с той же resolved identity.

Handoff без request snapshot даёт `MISSING_REQUEST_CONTEXT`, повторная запись -
`REQUEST_CONTEXT_ALREADY_SET`, запись не-REQUEST - `CONTRADICTORY_PHASE`.
Contexts concurrent requests не смешиваются. Thread-local/global maps и
повторное extraction из response body запрещены. Completion, upstream/client
error, timeout и cancellation завершают scope и удаляют retained snapshot;
callback не удерживает его после lifecycle. Handoff не меняет транспортные
streaming/backpressure/cancellation guarantees.
