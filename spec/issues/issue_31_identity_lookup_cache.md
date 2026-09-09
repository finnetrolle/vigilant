# VIG-31: Cache external identity lookup

- **ID:** `VIG-31`
- **Тип:** Issue
- **Статус:** Done
- **Приоритет:** High
- **Зависит от:** [VIG-30](issue_30_external_identity_extractor.md),
  [VIG-37-04](epic_37/issue_37_04_four_worker_qualification.md)
- **Блокирует:** нет
- **Оценка:** 4-5 инженерных дней
- **Уверенность:** Medium

## Цель

Повторные запросы с одним Bearer token получают ранее разрешённую identity из
локального bounded cache; Bridge вызывается только при отсутствии годного
результата. Cache имеет настраиваемые TTL и вместимость, объединяет concurrent
misses и сохраняет cancellation, fail-closed и privacy contract VIG-30.

Нагрузочное тестирование cache по решению пользователя не входит в VIG-31.
Завершение issue не является новым доказательством performance SLO.

## Известный контекст

- VIG-35 сохраняет `DUMMY`, `JWT` и `EXTERNAL` как три startup-selectable
  реализации общего async `BearerIdentityExtractor`. Cache принадлежит только
  `EXTERNAL`; локальные `DUMMY` и JWT не используют и не конфигурируют его.
- VIG-30 реализовал internal `ExternalIdentityLookup` seam с safe
  `Resolved(NormalizedIdentity)`/`Unavailable(code)` result. Cache decorator
  вставляется между `ExternalIdentityExtractor` и `BridgeIdentityClient`:
  hit не требует Bridge permit, miss проходит существующий immediate admission.
- Performance SLO `p99 <= 2 ms` при 2 000 RPS измеряется с warm cache или mock
  extractor.
- Cache хранит только безопасно выбранный key и normalized user/groups; raw
  Bearer token не сохраняется и не логируется.
- Cache miss вызывает external lookup. Его failure даёт `503`.

Нормативные ссылки: [MVP-05](../MVP_FUNCTIONS.md),
[PERF-01/02, CONC-03/04, PROXY-03, OBS-01/02 и Deployment](../MVP_NON_FUNCTIONAL_REQUIREMENTS.md),
startup, async lifecycle, admission и component boundaries
[VIG-30](issue_30_external_identity_extractor.md). Обе hard dependencies
`VIG-30` и `VIG-37-04` завершены. Это standalone issue без parent epic и без
dependent issues.

## Решения, согласованные на grill-сессии

### Хранилище и границы компонента

- Каждая реплика хранит собственный in-memory cache на Caffeine. Cache
  существует только в `EXTERNAL` mode и теряется при restart.
  Прямая dependency `com.github.ben-manes.caffeine:caffeine:3.2.4` фиксируется
  в version catalog; Spring, JCache и Guava adapter не требуются.
- `CachingExternalIdentityLookup` является application-scoped Decorator:
  реализует существующий `ExternalIdentityLookup`, принимает delegate того же
  типа и оборачивает `BridgeIdentityClient`.
- Типы Caffeine остаются внутри decorator. Новый Facade, generic `CacheStore`
  и абстракция для будущего distributed storage не добавляются. Возможная
  замена cache в будущем локализуется на этой границе; сетевой cache потребует
  отдельного контракта latency, ошибок, сериализации и межрепличных ключей.
- `OutboundClientResources` сохраняет владение Bridge client и общим
  connection factory; cache behavior принадлежит decorator.
- Этот же lifecycle root создаёт decorator и hasher один раз только для
  `EXTERNAL`; его `externalIdentityLookup` возвращает decorator через
  существующий interface. Поэтому `AppComponent.identityExtractorBinding`
  сохраняет существующую границу выбора modes.
- Decorator владеет completed entries, in-flight registry и caller futures.
  HTTP request/response, deadline, admission реального Bridge exchange и
  `ClientFactory` остаются у существующих owners. Cache не выполняет HTTP,
  parsing или identity normalization повторно.

### Ключи cache и отдельный владелец хэширования

- Ключ вычисляется как полный HMAC-SHA-256 от исходного Bearer token. Token
  не нормализуется и не сокращается перед вычислением отпечатка.
- По явному решению пользователя вводится отдельный internal класс
  `ExternalIdentityCacheKeyHasher`, инкапсулирующий создание и хранение
  случайного секрета и вычисление ключей. `CachingExternalIdentityLookup`
  использует этот класс при lookup; криптографическая логика и секрет не
  дублируются в decorator или Bridge client.
- Секрет генерируется криптографическим RNG один раз для application-scoped
  hasher при startup `EXTERNAL`, существует только в памяти и не требует
  пользовательской конфигурации. Каждый новый запуск получает новый секрет.
- Production secret содержит 32 случайных байта от `SecureRandom`.
  `keyFor(token: String): String` возвращает полный lowercase hex digest
  длиной 64 символа от UTF-8 bytes token. Стандартный JCA `HmacSHA256`
  инкапсулирован в hasher; собственный криптографический алгоритм не пишется.
- В пределах одного запуска один и тот же token даёт один и тот же cache
  key. Содержимое digest используется для сравнения ключей; object identity
  массива байтов не является равенством ключей.
- Hasher не хранит входные токены и не мемоизирует пары token/digest. Ни
  completed cache, ни in-flight registry не используют raw token как ключ
  или сохранённое поле значения. Секрет не выходит за границы hasher.
- Concurrent lookup должен получать корректный ключ без совместного
  небезопасного использования mutable состояния криптографического API.
- Для pure test разрешена constructor injection источника случайности с
  известными bytes. Это test seam, не operator setting. Ожидаемый digest
  задаётся независимым опубликованным либо заранее проверенным test vector,
  а не повторным вызовом production calculation в assertion.

### Сохраняемые результаты и конкурентные запросы

- Cache сохраняет только успешный `Resolved(NormalizedIdentity)`.
  `Unavailable`, exceptional completion и cancellation не кешируются.
- Concurrent misses одного ключа присоединяются к одному in-flight Bridge
  lookup. После failure in-flight запись удаляется, следующий запрос может
  выполнить новую попытку через существующий Bridge admission.
- Каждый ожидающий запрос имеет независимую cancellation. Пока остаётся хотя
  бы один ожидающий клиент, общий lookup продолжается.
- Отмена последнего ожидающего клиента отменяет общий Bridge lookup.
  Отменённая загрузка не публикует запись в cache, включая поздний ответ.
- Caller получает отдельный future. Он не получает общий in-flight future
  или mutable cache value. Его cancellation/completion не меняет результат
  других callers и не позволяет положить поддельную identity в cache.
- Shared Bridge deadline отсчитывается от начала самого exchange по VIG-30;
  присоединение нового клиента не перезапускает timeout.
- Success, failure, timeout, last-caller cancellation и close выбирают один
  terminal transition. Если success уже выиграл, последующая cancellation
  завершённого caller не инвалидирует результат. Если cancellation/close
  выиграл первым, поздний success не заполняет cache.
- Удаление старого in-flight поколения условно по принадлежности этому
  поколению: его поздний callback не удаляет новую загрузку того же ключа.
- Результат публикуется в cache до выдачи success ожидающим; in-flight
  ownership освобождается без окна для дублирующего lookup. Delegate calls,
  cancellation и пользовательские completion callbacks не выполняются под
  общим lifecycle lock. На event loop нет `join`, `get` или blocking wait.
- `Unavailable` сохраняет исходный finite code. Неожиданный обычный exception
  delegate преобразуется в `Unavailable(TRANSPORT_ERROR)` без раскрытия
  exception. Cancellation остаётся cancellation, а не failure cache entry.

### Bounded ожидания

- Completed cache и незавершённые загрузки имеют раздельный lifecycle.
  `maximumSize` и TTL применяются к готовым identity и не вытесняют активный
  lookup. Координация ожидающих не подменяется eviction policy Caffeine.
- `N` равен существующему effective
  `inspection-max-concurrent-request-sources`. Одновременно decorator
  удерживает не более `N` ожидающих callers суммарно по всем ключам, включая
  присоединившихся. Число in-flight ключей тем самым также не превышает `N`.
- Ожидающий miss получает slot немедленно, без очереди. При отсутствии slot
  возвращается существующий `Unavailable(OVERLOADED)`, публично
  `503 identity_unavailable`; новый Bridge call не запускается. Новая
  configuration setting или новый public failure code не добавляется.
- Hit готовой identity не занимает waiter slot или Bridge permit и работает
  при исчерпанном лимите ожиданий. Слот освобождается один раз при любом
  terminal event соответствующего caller.
- Только создатель shared lookup вызывает delegate и проходит существующий
  Bridge admission. Joined callers дополнительных Bridge permits не берут.

### Expiry и конфигурация

- TTL имеет default `10m`, переопределяется стандартными file/env настройками
  приложения, применяется при startup только в `EXTERNAL` mode.
- Используется `expireAfterWrite`: срок отсчитывается от успешного получения
  и сохранения identity. Cache hits не продлевают срок.
- После expiry старый результат не используется. Первый следующий запрос
  запускает новый Bridge lookup и ожидает его; concurrent requests того же
  ключа присоединяются к нему.
- Background refresh отсутствует: при отсутствии запросов новый lookup не
  выполняется. TTL ограничивает срок использования сохранённого результата,
  но не обещает обновление без следующего запроса или при недоступном Bridge.
- Возраст `>= TTL` означает miss, включая exact boundary. Loading time не
  входит в TTL готовой записи. Используется monotonic Caffeine `Ticker`;
  перевод wall clock не меняет expiry.
- Invalidation происходит только при expiry, size eviction и закрытии
  экземпляра. Административный flush/revoke API в этой issue не появляется.

Точные настройки под `vigilant`:

| HOCON key | Environment override | Default | Допустимые значения |
|---|---|---|---|
| `identity-external-cache-ttl` | `VIGILANT_IDENTITY_EXTERNAL_CACHE_TTL` | `10m` | Positive `java.time.Duration`, точно представимая положительным числом наносекунд `1..Long.MAX_VALUE` |
| `identity-external-cache-max-size` | `VIGILANT_IDENTITY_EXTERNAL_CACHE_MAX_SIZE` | `10000` | Целое число `1..Int.MAX_VALUE` |

Применяется текущий порядок `env > file > default`. Defaults применяются
после выбора `EXTERNAL`; в decoded settings отсутствие остаётся отличимым от
явно заданного значения. Каждая из двух settings, явно заданная даже своим
default, запрещена в `DUMMY` и `JWT`. Обе settings независимы: разрешено
переопределить только одну.

Zero, negative, empty, malformed и overflow значения отклоняются safe startup
error с exit code `2`; fraction для max-size также отклоняется. Диагностика
называет setting и допустимую форму без raw input, stack trace или secret.
Не вводятся disable-by-zero, runtime reload или режим unbounded cache.
Startup не обращается в Bridge и не выполняет прогрев.

### Вместимость

- Default `maximumSize` равен `10_000` записей на реплику и переопределяется
  стандартной startup-конфигурацией.
- Одна запись соответствует одному token-derived key; разные токены одного
  пользователя занимают разные записи.
- Используется штатная eviction policy Caffeine с учётом частоты и давности
  использования. Запрос по вытесненному ключу выполняет обычный cache miss.
- Это лимит числа записей, а не точный предел JVM heap в байтах. Размер
  normalized identity ограничен действующим контрактом user/groups VIG-30.
- `maximumSize` имеет штатную maintenance semantics Caffeine: допускается
  временное превышение до eviction maintenance. В quiescent состоянии после
  завершения maintenance число completed entries не превышает лимит.
  Строгая мгновенная quota, собственный LRU и JVM heap weigher не добавляются.
- Expired entries уже недоступны чтению, даже если физическое удаление ещё
  не произошло. Для тестов используются controlled ticker и выполнение
  maintenance через test seam; выбор конкретного eviction victim не является
  стабильным контрактом. Выделенные maintenance thread/pool не создаются.

### Наблюдаемость

- Для каждого запроса к открытому cache считается ровно один `hit` либо
  `miss`. Hit означает получение готовой неистёкшей identity; отсутствие
  готового результата, включая присоединение к in-flight lookup, есть miss.
- Отдельный `coalesced` counter считает misses, присоединившиеся к уже
  выполняющемуся lookup. Например, три одновременных запроса одного
  отсутствующего ключа дают `miss=3`, `coalesced=2`, один Bridge lookup.
- Удаления completed entries считаются отдельно с причинами expiry и size
  eviction. Они отражают фактическое удаление библиотекой, а не наступление
  TTL по таймеру; завершение in-flight failure/cancellation не является
  удалением успешной cache entry.
- Используются существующий OTel pipeline и bounded attributes. Метрики
  количества, исходов и длительности самого Bridge lookup сохраняются.
- Token, digest, HMAC secret, user и groups не попадают в audit, logs,
  metrics, traces или errors. Per-token/per-user метрики не создаются.

| Instrument | Unit | Attributes |
|---|---|---|
| `vigilant.identity.external.cache.requests` counter | `{request}` | `identity.mode=EXTERNAL`, `cache.result=hit\|miss` |
| `vigilant.identity.external.cache.coalesced` counter | `{request}` | `identity.mode=EXTERNAL` |
| `vigilant.identity.external.cache.removals` counter | `{entry}` | `identity.mode=EXTERNAL`, `cache.removal.reason=expired\|size` |

Отказ waiter admission есть miss без coalesced и без Bridge lookup. Explicit
clear при close и replacement не считаются expired/size removals. Metrics
listeners работают только с finite cause и не захватывают key/value. Ошибка
telemetry не меняет identity outcome. Cache hit не создаёт Bridge CLIENT
span; shared miss создаёт один существующий span от инициировавшего request,
joined requests не получают дополнительные spans или новые tracing links.

### Lifecycle и shutdown

`CachingExternalIdentityLookup` реализует idempotent `AutoCloseable`.
`OutboundClientResources.close()` последовательно пытается закрыть decorator,
Bridge client и общий factory через существующий `runAllCleanupActions`.
Decorator отменяет только принадлежащие ему delegate futures, но сам не
вызывает `BridgeIdentityClient.close()` и не закрывает factory.

Порядок Main сохраняется: not-ready -> server drain -> inspection cleanup ->
outbound cleanup -> telemetry cleanup. Closed decorator не выдаёт даже
сохранённый hit и не обращается к delegate: возвращает cancelled future.
Close прекращает публикацию новых cache entries, отменяет всех ожидающих,
освобождает slots и удаляет собственные ссылки на identity, in-flight state и
hasher. Освобождение heap выполняет GC; гарантированное затирание всех копий
секрета в JVM не заявляется.

| Owner | Terminal event | Наблюдаемое последствие и cleanup | Failure path |
|---|---|---|---|
| Decorator, completed entry | Expiry / size eviction | Следующий lookup требует новую identity; соответствующая removal metric после maintenance | Недоступный Bridge даёт `503`, stale identity не выдаётся |
| Decorator, shared lookup и callers | Success | Все ещё ожидающие получают разрешённую identity; последующий hit без Bridge; slots доступны | Поздний terminal callback не меняет результат |
| Decorator + Bridge | Provider failure / invalid response / transport failure | Ожидающие получают один safe исход; следующий запрос запускает fresh lookup | Ошибка не кешируется; slots/Bridge permit возвращены |
| Bridge, общий deadline | Timeout | Exchange отменён; все ещё ожидающие получают `Unavailable(TIMEOUT)` | Новый caller не продлевает срок; late success игнорируется |
| Один caller | Его cancellation при оставшихся клиентах | Отменён только его future, slot возвращён; shared lookup обслуживает остальных | Повторная cancellation ничего не освобождает повторно |
| Последний caller / decorator | Last cancellation / close | Shared exchange отменён; ожидающие и slots освобождены; cancelled поколение не кешируется | Cancel до установки delegate future применяется сразу после его установки |
| Outbound lifecycle root | Shutdown / повторный close | Decorator -> Bridge -> factory закрываются один раз | Ошибка одного cleanup не пропускает остальные; первая сохраняется, следующие suppressed |

## Проверяемые границы и acceptance matrix

Главный seam: real HTTP client -> Vigilant -> trusted Bridge test server ->
LLM upstream, существующий `GatewayIdentityE2eTest`. Bridge независимо
фиксирует полученные обращения, а upstream подтверждает exact Authorization
и body. Изменение групп проверяется через выбранную policy и её наблюдаемый
результат, а не только через число Bridge calls.

Дополнительные заранее согласованные seams:

- Pure hasher API с deterministic randomness и независимыми digest vectors.
- `ExternalIdentityLookup.lookup` + `close` для controlled race cases;
  controlled delegate воспроизводит boundary внешней операции. Real Caffeine
  остаётся в тесте. Ticker и maintenance executor подменяются только в тесте.
- `loadAppConfig` и существующие config/process fixtures для startup matrix.
- `GatewayProcessFixture.launchInstalled` для packaged startup, cache hit и
  shutdown; `ExternalIdentityProcessTest` остаётся в serial `processTest`.

Каждый перечисленный вариант в строке требует своего setup и assertion;
названия cases не заменяют достижение нужного состояния. Реальные HTTP failure
rows проверяют status, полный canonical VIG-29 body и headers, отсутствие
request-body demand и отсутствие LLM upstream call.

| ID | Состояние / варианты | Обязательное наблюдение и oracle |
|---|---|---|
| `CFG-01` | EXTERNAL, обе settings отсутствуют; file-only TTL; file-only size; обе file settings | Defaults `10m/10000`; независимые overrides; parser возвращает точные значения |
| `CFG-02` | Env-only и env-over-file для каждой setting отдельно и вместе | Точные env значения имеют precedence; другая setting сохраняет свой file/default |
| `CFG-03` | TTL `1ns`, `10m`, `Long.MAX_VALUE ns`; zero, negative, empty, whitespace-only, malformed, nanos overflow через file и env | Valid boundaries принимаются; каждый invalid case safe error без input sentinel |
| `CFG-04` | Size `1`, `10000`, `Int.MAX_VALUE`; zero, negative, fraction, empty, whitespace-only, malformed, integer overflow через file и env | Valid boundaries принимаются без eager allocation; каждый invalid case safe error без input sentinel |
| `CFG-05` | DUMMY и JWT, каждая cache setting явно задана через file/env; отдельно оба modes без cache settings | Explicit settings запрещены; отсутствие сохраняет штатный mode, cache/hasher/Bridge не создаются |
| `KEY-01` | Fixed test secret и independently known token/digest vectors; одинаковый token в разных String objects | Полный HMAC-SHA-256 digest совпадает с literal oracle; равные inputs дают равный key |
| `KEY-02` | Tokens отличаются регистром либо одним символом; разные secrets при одинаковом token; concurrent смешанные inputs | Для каждой case точный digest своего vector; нет normalization, truncation или гонки mutable crypto state |
| `CACHE-01` | Первый и повторный HTTP requests одного token | Один Bridge call, оба проходят соответствующую policy, upstream получает exact исходные Authorization/body обоих запросов |
| `CACHE-02` | Разные tokens с одинаковым user, но различающимися groups | Два Bridge calls и независимые policy результаты; cache не объединяет по user |
| `TTL-01` | Время `TTL-1ns`, ровно `TTL`, `TTL+1ns` от сохранения; частые hits до границы | До границы old identity без Bridge; на/после границы fresh lookup и новые groups/policy; hits срок не продлили |
| `TTL-02` | Controlled lookup длится дольше cache TTL, затем successful completion; отдельно длительный idle после expiry | Новый результат получает полный TTL после completion; idle не запускает Bridge; следующий request обновляет identity |
| `FAIL-01` | Provider non-200; invalid identity JSON; transport failure; timeout; exhausted waiter admission после expiry | Каждый HTTP case сохраняет canonical `503 identity_unavailable`; stale identity не используется; следующий успешный lookup действительно обращается к Bridge |
| `FAIL-02` | Delegate выбрасывает обычный exception до выдачи future; завершает future exceptionally; завершает cancellation | Первые два дают safe `TRANSPORT_ERROR`, третий cancellation; все освобождают ожидание и допускают fresh lookup |
| `SIZE-01` | Малый configured capacity `C`, больше `C` уникальных successful keys, TTL не истёк, maintenance завершён | По independently counted inserts и size-removal events подтверждён предел; вытеснённый key снова требует Bridge, retained key даёт hit; конкретная жертва заранее не фиксируется |
| `SIZE-02` | Один pending lookup во время заполнения/eviction completed cache | Pending lookup не теряется и не дублируется из-за size eviction; joining caller получает его результат |
| `JOIN-01` | Три callers одного cold key; lookup удержан до наблюдаемого join; success и каждый finite failure code отдельными cases | Одна delegate operation; все неотменённые callers получают ожидаемый исход; `miss=3`, `coalesced=2`; failure позволяет fresh lookup. Success и provider failure дополнительно E2E через real Bridge; полный enum, включая `OVERLOADED`, проверяется controlled lookup seam |
| `JOIN-02` | Два разных cold keys одновременно | Независимые Bridge exchanges и результаты; один задержанный key не мешает второму |
| `BOUND-01` | `N` ожидающих на одном key и на разных keys; ещё один cold caller; готовый hit при заполненном лимите | Лишний caller получает overload без Bridge/join; existing callers не отменены; hit проходит без slot/permit |
| `BOUND-02` | После success, каждого failure, timeout, отмены одного и последнего caller повторное заполнение всех `N` slots | Лимит доступен вновь без утечки или double-release; caller `N+1` всё ещё отклоняется |
| `CANCEL-01` | Отмена инициатора при оставшемся joined caller; отмена joined caller при оставшемся инициаторе | Только выбранный caller отменён; Bridge продолжает; оставшийся получает identity; последующий hit работает |
| `CANCEL-02` | Отмена единственного caller; отмена последнего из нескольких; повторная cancellation | Bridge server наблюдает cancellation один раз, upstream не вызывается; следующий request запускает fresh lookup |
| `RACE-01` | Отмена присоединившегося caller и close до публикации delegate future; late success старого поколения после нового lookup того же key | Partial cancellation отменяет только joined caller, инициатор продолжает lookup; close отменяет поздно установленный delegate. Старый callback не заполняет cache и не удаляет новое поколение |
| `RACE-02` | Success первым, last cancellation первой, timeout первым, close первым, каждый порядок управляется барьером | Один terminal outcome; winner определяет cache publication; slots и Bridge work освобождаются согласно lifecycle matrix |
| `RACE-03` | Присоединение перед shared deadline; caller пытается complete/completeExceptionally/obtrude/completeAsync/orTimeout/completeOnTimeout свой future | Join не продлевает Bridge timeout; вмешательство caller не меняет другой caller, cache или владение shared operation |
| `LIFE-01` | Close пустого cache; close с готовыми entries; close с одним и несколькими активными keys; повторный close | Никакой hit после close, новые lookup отменены без Bridge; все активные operations завершены cancellation, позднее заполнение исключено |
| `LIFE-02` | Cleanup composition при ошибке первого либо второго действия | Review подтверждает точные decorator/Bridge/factory actions root в `runAllCleanupActions`; existing behavioral tests этого helper доказывают выполнение остальных actions и suppressed errors. Не выдавать тест helper за инъекцию отказа реального сетевого factory |
| `OBS-01` | Cold miss, hit, coalesced requests, waiter-overload; before/after snapshots из in-memory OTel | Точные приращения трёх cache counters по определённой семантике, только заданные attributes; failed lookup не становится hit |
| `OBS-02` | Expiry, size eviction, failure removal, explicit close | Только первые два увеличивают соответствующие removal counters после фактического удаления |
| `OBS-03` | Hit; shared miss с инициатором и joined requests; отмена инициатора при активном joined request | Нет Bridge span на hit; один Bridge CLIENT span с прежним parent на shared miss, он не дублируется и не завершается до shared terminal event |
| `SAFE-01` | Unique sentinels в token, digest, user/groups и тестовом secret; success, hit, failures, cancellation, shutdown | Снимки logs/audit/metrics/traces/errors не содержат sentinels; config failures также не выводят raw input |
| `SAFE-02` | Полный diff retained state, listeners и callbacks | Отдельный hasher действительно используется; ни raw token memoization, ни token-bearing fields/closures в cache не остаются; Caffeine types не выходят из decorator |
| `PROC-01` | Installed gateway EXTERNAL, config overrides; два последовательных одинаковых requests; restart и повторный request | Один Bridge call до restart, fresh call после; exact HTTP behavior и safe process output; startup не прогревает cache |
| `PROC-02` | Packaged startup с invalid TTL, invalid size и cache setting в каждом не-EXTERNAL mode | Exit code `2` и safe diagnostic, без Bridge call; полная invalid-value matrix отдельно в config tests |
| `PROC-03` | Installed gateway имеет два coalesced активных requests при forced shutdown | Один Bridge exchange отменён, оба clients завершаются; upstream не вызван; процесс выходит в текущем bounded shutdown window |
| `REG-01` | Existing DUMMY/JWT и VIG-30 success/failure/header validation | Shared Bearer parsing, immutable identity, byte-for-byte forwarding, stable errors и mode isolation сохраняются |

Уточнение `RACE-01`, согласованное при реализации: future инициатора возвращается
из `lookup` только после установки delegate future. До этого через публичный
seam доступны partial cancellation присоединившегося caller и close; отмена
последнего caller проверяется после публикации в `CANCEL-02`. Дополнительный
executor или production test hook для недоступного извне порядка не вводится.

TTL проверяется fake monotonic clock без реального ожидания десяти минут.
Concurrent/E2E fixtures используют barriers на наблюдение, которое утверждают:
Bridge request/cancellation и published counters, а не client completion как
замену server observation. Для bounded polling переиспользовать
`GatewayTestFixture.awaitUntil`, для in-process servers `loopbackHttpAddress`,
для процессов `GatewayProcessFixture`; освобождённый ephemeral port повторно
не используется. Graceful cleanup переиспользует `runAllCleanupActions` и
`closeAllResources` вместо нового helper.

## Документация и реализационный scope

Основные owned paths: `gateway/identity` (два новых класса и тесты),
`gateway/config/IdentityConfig.kt`, `gateway/config/AppConfig.kt`,
`gateway/proxy/OutboundClientResources.kt`, необходимая DI/fixture wiring,
`gradle/libs.versions.toml`, `build.gradle.kts` и targeted tests из matrix.
Generic library extraction, реорганизация identity modules и переписывание
Bridge transport не являются результатами этой issue.

В реализации обновить `vigilant.conf.example`, `docs/configuration.md`,
`docs/runtime-contract.md`, `docs/architecture.md`, `docs/observability.md`,
`docs/requirements-coverage.md` и затронутые UML sources в `docs/diagrams/`:
runtime components/classes и request sequence. Обновлять только cache
composition, lifecycle, configuration и metrics; текущую поддержку cache
указывать после реализации. Обязательные KDoc/Javadoc добавляются в том же
TDD slice, включая test methods, callbacks и helpers.

Контрактный sweep: `raw token`, `ExternalIdentityLookup`,
`BridgeIdentityClient`, `cache отсутствует`, `cache ещё`, `cache hit/miss`,
`permit`, `cancellation`, `10m`, `10000`, новые setting/metric names по
production, tests, KDoc, current docs, normative references и work items.
Исторический факт, что VIG-30 реализован без cache, остаётся историческим;
его component seam и provider protocol не переписываются.

Новые cache settings необязательны. Общие startup/process/packaging fixtures
сверяются на mode isolation; DUMMY/JWT launchers не получают EXTERNAL defaults.
Чужие working-tree изменения, papercuts, Sonar cleanup и generated reports
не включаются в diff задачи.

## Не входит

- External provider protocol, policy matching или distributed shared cache.
- Persistence cache на диск и cross-replica synchronization.
- Cache или fallback для `DUMMY`/JWT и переключение identity mode без restart.
- Нагрузочное тестирование cache, Gatling/JMH расширения, изменение perf
  harness, performance report или новый numeric latency gate. PERF-01/02
  остаются нормативными требованиями без нового evidence claim от VIG-31.
- Background refresh, stale-while-revalidate, stale-on-error, negative cache,
  retries, circuit breaker, prewarm и administrative invalidation API.
- Generic cache facade/store, Memcached/Redis adapters, L1/L2, shared secrets
  между репликами, cache persistence или конфигурируемый hashing algorithm.
- Строгий byte-level heap budget, собственный eviction algorithm, новое
  quota setting для ожиданий, отдельные executor pool или scheduler.
- Новые identity modes, policy reactions, protocol routes, provider schemas,
  per-user metrics, дополнительные cache spans или внешняя telemetry export.

## Критерии завершения реализации

- [x] Реализованы Caffeine decorator и отдельный hasher; boundaries и config
  соответствуют этому контракту.
- [x] Для всех строк acceptance matrix и каждого названного варианта есть
  требуемая behavioral evidence, включая lifecycle/race и packaged cases.
- [x] Privacy, cancellation, admission, EXTERNAL-only composition и
  deterministic tests подтверждены; non-goals не расширены.
- [x] Обновлены current docs, UML, KDoc/Javadoc и coverage status в пределах
  реализованного поведения; performance claims не добавлены.
- [x] Выполнены TDD RED -> GREEN slices и итоговые проверки ниже.
- [x] Перед закрытием повторно прочитаны полная issue, non-goals и diff;
  closure ledger связывает каждый criterion/non-goal с path, observation,
  independent oracle и exact command/result либо scope-review evidence.
  Unsupported rows, label-only cases, stale contracts и unapproved files
  отсутствуют. Issue и `spec/WORK_ITEMS.md` переведены в `Done` согласованно
  только после необходимых dynamic evidence и validator.

Targeted команды по соответствующему slice:

```bash
./gradlew test -x processTest --tests 'io.vigilant.gateway.identity.ExternalIdentityCacheKeyHasherTest'
./gradlew test -x processTest --tests 'io.vigilant.gateway.identity.CachingExternalIdentityLookupTest'
./gradlew test -x processTest --tests 'io.vigilant.gateway.proxy.GatewayIdentityE2eTest'
./gradlew test -x processTest --tests 'io.vigilant.gateway.config.AppConfigLoadingTest' --tests 'io.vigilant.gateway.AppComponentIdentityTest'
./gradlew processTest --tests 'io.vigilant.gateway.ExternalIdentityProcessTest'
```

После последнего slice выполнить narrow affected suite, затем
`./gradlew validateWorkItems` и `./gradlew build` согласно `CLAUDE.md`.
Команды последовательны: предыдущий yielded Gradle process обязан завершиться
до следующего. В Codex команды оборачиваются `rtk proxy` для raw evidence.
Нагрузочный прогон не является условием `Done`. Handoff в `verify-changes`,
если он запрошен, требует стандартного `Pre-verification closure` из
`CLAUDE.md`; сама эта issue не запускает дополнительный workflow.

## Evidence завершения (2026-09-09)

- Behavioral RED -> GREEN slices проверили configuration, HMAC, write TTL,
  coalescing, cancellation, waiter bound, close, protected caller futures,
  eviction/metrics и EXTERNAL composition. Real HTTP regression выявила и
  исправила возврат shared completion под чужим Armeria request context:
  continuation каждого caller использует existing inspection executor.
- Итоговый affected suite: hasher, cache, Bridge, cache/config loading,
  AppComponent identity, GatewayIdentityE2eTest и cleanup helper - GREEN
  (`test -x processTest` с exact class filters), 4m42s.
- Все packaged restart/config/shutdown cases прошли в serial `processTest`.
  Shutdown independently наблюдает один Bridge cancellation и завершение
  обоих admitted clients; N=2 и отдельный overload доказывают shared admission.
- `./gradlew validateWorkItems` прошёл перед full build; `./gradlew build`
  завершился GREEN за 14m31s, включая оба test lanes, detekt и graph validator.
  Команды выполнялись последовательно через `rtk proxy`, с `--no-daemon`
  и `-Pkotlin.compiler.execution.strategy=in-process`.
- Полный closure ledger с отдельной строкой для каждого criterion/non-goal,
  командами, observations, independent oracles, lifecycle и scope review
  сохранён локально в ignored `build/reports/vig-31/closure.md`.
  Нагрузка не запускалась; нового performance evidence claim нет.

## Проверенные основания и альтернативы

- VIG-30 уже предоставляет async `ExternalIdentityLookup`, safe results,
  immutable normalized identity, real Bridge E2E и outbound cleanup root.
  Хэш-helper в существующем identity path не найден.
- Существующий norm допускает только local replica cache. Caffeine заменяет
  необходимость собственного eviction/TTL engine. Stateful cancellation
  coordination остаётся обязанностью decorator.
- Generic cache abstraction отклонён как будущая функциональность; существующий
  lookup interface сохраняет локальную точку замены. Distributed storage
  потребует отдельного решения о latency, failures, keys и serialization.
- Sliding TTL и refresh отклонены: частые hits не должны бесконечно
  продлевать старые группы. Negative caching и stale fallback отклонены:
  failure не должен превращаться в сохранённую identity или продлённый отказ.
- Limit-by-entry выбран явно; он не объявляется измерением heap. HMAC со
  случайным process-local secret выбран вместо сохранения raw token или
  конфигурируемого межрепличного секрета.
- Нагрузочный прогон предложен и явно исключён пользователем. Рабочий
  performance harness использует DUMMY; его изменение не входит в scope.

Источники library semantics:
[Caffeine 3.2.4](https://github.com/ben-manes/caffeine/tree/v3.2.4),
[eviction, expiry и test ticker](https://github.com/ben-manes/caffeine/wiki/Eviction),
[maintenance](https://github.com/ben-manes/caffeine/wiki/Cleanup),
[HMAC](https://www.rfc-editor.org/rfc/rfc2104).

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0   ✓ наблюдаемый cache outcome определён
  Acceptance:   0.0   ✓ конкретные config, HTTP, race и process cases
  Boundaries:   0.0   ✓ non-goals включают исключённую пользователем нагрузку
  Alternatives: 0.25 ✓ основные альтернативы разобраны; distributed design отложен
  Assumptions:  0.25 ✓ seams проверены; реальная производительность ещё не измерена
  ──────────────────────────────
  Aggregate:    0.10  ✓ below threshold (0.3 ticket)

Push lightly on: дополнительных решений для начала реализации не требуется.
```
